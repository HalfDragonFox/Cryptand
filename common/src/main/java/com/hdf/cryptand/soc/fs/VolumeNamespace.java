package com.hdf.cryptand.soc.fs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ===== 多卷命名空间：把分区表变成一个"现代系统的目录树"（common，纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-24）："完整 os 可以支持通过分区来实现功能，对应现代系统"。
 * {@link DiskVolumes} 把分区变成卷；这一层把卷<b>挂到目录树上</b>：</p>
 *
 * <pre>
 *   系统卷 → /          程序卷 → /usr      用户卷 → /home
 *   数据卷 → /data      认不出的卷 → /mnt/part&lt;index&gt;
 * </pre>
 *
 * <p>⚠ 关键设计：它实现的仍然是同一个 {@link CryptandFileSystem} 契约 ⇒
 * 上层（OC 的 {@code filesystem} 组件、{@code FS_*} ABI 派发、命令、AI 工具）<b>一行都不用改</b>
 * 就获得了现代系统的目录布局。固件侧同理：它只知道"一个盘句柄 + 绝对路径"，
 * 路径以 {@code /usr/} 开头就会落到程序卷上 —— 分区语义留在宿主，guest 不需要理解分区表。</p>
 *
 * <p>⚠ 句柄是**跨卷**的：不同卷各自维护自己的句柄表，一旦合并就可能撞 id，
 * 所以本层自己发号（随机、且避开已用 id），并把"这个句柄属于哪个卷"记在 {@link #handles} 里。</p>
 */
public final class VolumeNamespace implements CryptandFileSystem {

    /** 一条挂载：前缀 → 卷文件系统（{@code fs} 可为 null：那个卷没挂上） */
    private record Mount(String point, CryptandFileSystem fs) {
    }

    /** 一个已打开的句柄：属于哪个卷、在那个卷里的句柄 id */
    private record Opened(Mount mount, int inner) {
    }

    private final Mount root;
    private final List<Mount> mounts;
    private final Map<Integer, Opened> handles = new LinkedHashMap<>();

    /** 实例是否已被 {@link #close()} 关掉（它一并关掉每个卷的文件系统） */
    private boolean closed;

    private VolumeNamespace(Mount root, List<Mount> mounts) {
        this.root = root;
        this.mounts = mounts;
    }

    /** 按盘的**分区表**建命名空间（分区角色 → 挂载点，规则只有 {@link DiskVolumes} 一份） */
    public static VolumeNamespace of(Path diskDir, long capacityBytes, String address, boolean readOnly) {
        return of(DiskVolumes.volumes(diskDir, capacityBytes, address, readOnly));
    }

    /** 由已有卷表建命名空间（调用方已经枚举过卷时用它，避免重复挂载） */
    public static VolumeNamespace of(List<DiskVolumes.Volume> volumes) {
        CryptandFileSystem rootFs = null;
        final List<Mount> list = new ArrayList<>();
        for (final DiskVolumes.Volume v : volumes) {
            if (!v.available()) {
                continue;   // 不可用的卷不参与挂载（它的原因在 DiskVolumes 的 note 里）
            }
            switch (v.role()) {
                case SYSTEM -> {
                    if (rootFs == null) {
                        rootFs = v.fs();
                    }
                }
                case USR -> list.add(new Mount("usr", v.fs()));
                case HOME -> list.add(new Mount("home", v.fs()));
                case DATA -> list.add(new Mount("data", v.fs()));
                case RAW -> list.add(new Mount("mnt/part" + v.index(), v.fs()));
            }
        }
        // 最长前缀优先：/mnt/part0 必须赢过 /mnt（将来若有按目录挂载的卷也一样）
        list.sort((a, b) -> Integer.compare(b.point().length(), a.point().length()));
        // ⚠ 挂载点用**无前导斜杠的内部形式**（"usr"、"mnt/part0"，根是空串）：
        //   DiskMount.normalizeGuestPath 把 "/usr/x" 归一成 "usr/x"、"/" 归一成 ""，
        //   两边形式一致才谈得上比较（第一版用了 "/usr" 作挂载点 ⇒ 路由永远不匹配）。
        return new VolumeNamespace(new Mount("", rootFs), List.copyOf(list));
    }

    /** 这台"命名空间"里挂了哪些点（日志/诊断用） */
    public List<String> mountPoints() {
        final List<String> out = new ArrayList<>(mounts.size() + 1);
        out.add("/");
        for (final Mount m : mounts) {
            out.add("/" + m.point());
        }
        return out;
    }

    // ==================== 路由 ====================

    /** 路径落在哪个卷上（最长匹配的挂载点；没有匹配 ⇒ 根） */
    private Mount route(String path) {
        final String p = DiskMount.normalizeGuestPath(path);
        Mount best = root;
        int bestLen = -1;
        for (final Mount m : mounts) {
            if (p.equals(m.point()) || p.startsWith(m.point() + "/")) {
                if (m.point().length() > bestLen) {
                    best = m;
                    bestLen = m.point().length();
                }
            }
        }
        return best;
    }

    /** 命名空间路径 → 卷内路径（挂载点自身 ⇒ 该卷的根） */
    private String innerPath(Mount m, String path) {
        final String p = DiskMount.normalizeGuestPath(path);
        if (m.point().isEmpty()) {
            return p;
        }
        // 挂载点自身 ⇒ 卷根（内部形式是空串，底层文件系统认它）
        return p.equals(m.point()) ? "" : p.substring(m.point().length() + 1);
    }

    /**
     * 命名空间**自己**的目录项：挂载点在 {@code /} 与中间目录上的合成名字。
     *
     * <p>为什么需要：真实系统的 {@code /usr}、{@code /home} 是"挂载点目录"，
     * 而系统卷里未必真有一个叫 usr 的目录。不合成就会出现"挂上了 /home，但 {@code ls /} 看不见它"。</p>
     */
    private String[] namespaceEntries(String path) {
        final String p = DiskMount.normalizeGuestPath(path);
        final String prefix = p.isEmpty() ? "" : p + "/";
        final Set<String> names = new TreeSet<>();
        for (final Mount m : mounts) {
            if (m.point().isEmpty() || !m.point().startsWith(prefix)) {
                continue;
            }
            final String rest = m.point().substring(prefix.length());
            final int slash = rest.indexOf('/');
            names.add((slash < 0 ? rest : rest.substring(0, slash)) + "/");
        }
        return names.toArray(new String[0]);
    }

    /** 这个路径是命名空间自己造出来的目录（挂载点或它的父目录）吗 */
    private boolean isNamespaceDir(String path) {
        final String p = DiskMount.normalizeGuestPath(path);
        if (p.isEmpty()) {
            return true;
        }
        for (final Mount m : mounts) {
            if (p.equals(m.point()) || m.point().startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 取句柄所属的卷文件系统；卷没挂上 ⇒ 抛明确错误（不静默给个空文件系统） */
    private CryptandFileSystem require(Mount m, String path) {
        if (m.fs() == null) {
            throw new FsException(com.hdf.cryptand.soc.oc.OcAbi.ERR_NOT_FOUND,
                    "no volume mounted for " + DiskMount.normalizeGuestPath(path));
        }
        return m.fs();
    }

    // ==================== 元信息 ====================

    @Override
    public boolean isReadOnly() {
        if (root.fs() == null || !root.fs().isReadOnly()) {
            return false;
        }
        for (final Mount m : mounts) {
            if (m.fs() != null && !m.fs().isReadOnly()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long spaceTotal() {
        long total = root.fs() == null ? 0 : root.fs().spaceTotal();
        for (final Mount m : mounts) {
            if (m.fs() != null && m.fs().spaceTotal() > 0) {
                total += m.fs().spaceTotal();
            }
        }
        return total;
    }

    @Override
    public long spaceUsed() {
        long used = root.fs() == null ? 0 : root.fs().spaceUsed();
        for (final Mount m : mounts) {
            if (m.fs() != null) {
                used += m.fs().spaceUsed();
            }
        }
        return used;
    }

    // ==================== 查询（绝不抛） ====================

    @Override
    public boolean exists(String path) {
        final Mount m = route(path);
        if (m.fs() != null && m.fs().exists(innerPath(m, path))) {
            return true;
        }
        // 挂载点及其父目录在命名空间里一定存在（现代系统的挂载点目录）
        return isNamespaceDir(path);
    }

    @Override
    public long size(String path) {
        final Mount m = route(path);
        return m.fs() == null ? 0 : m.fs().size(innerPath(m, path));
    }

    @Override
    public boolean isDirectory(String path) {
        final Mount m = route(path);
        if (m.fs() != null && m.fs().isDirectory(innerPath(m, path))) {
            return true;
        }
        return isNamespaceDir(path);
    }

    @Override
    public long lastModified(String path) {
        final Mount m = route(path);
        return m.fs() == null ? 0 : m.fs().lastModified(innerPath(m, path));
    }

    @Override
    public String[] list(String path) {
        final Mount m = route(path);
        final String[] fromVolume = m.fs() == null ? null : m.fs().list(innerPath(m, path));
        final String[] synthetic = isNamespaceDir(path) ? namespaceEntries(path) : new String[0];
        if (fromVolume == null && synthetic.length == 0) {
            return null;   // 既不在卷里、也不是挂载点 ⇒ 真的不存在（照契约返回 null）
        }
        final Set<String> names = new TreeSet<>();
        if (fromVolume != null) {
            names.addAll(java.util.Arrays.asList(fromVolume));
        }
        names.addAll(java.util.Arrays.asList(synthetic));
        return names.toArray(new String[0]);
    }

    // ==================== 变更 ====================

    @Override
    public boolean delete(String path) {
        final Mount m = route(path);
        return m.fs() != null && m.fs().delete(innerPath(m, path));
    }

    @Override
    public boolean makeDirectory(String path) {
        final Mount m = route(path);
        return m.fs() != null && m.fs().makeDirectory(innerPath(m, path));
    }

    @Override
    public boolean rename(String from, String to) {
        final Mount a = route(from);
        final Mount b = route(to);
        if (a.fs() == null || a != b) {
            // 跨卷 rename 不是"不支持"而是"必须由上层决定语义"（真机 mv 也是先拷贝再删）：
            // 这里明确拒绝，免得出现"看起来成功了、内容却在另一个卷上"。
            throw new FsException(com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_ARGS,
                    "rename across volumes is not supported: " + from + " -> " + to);
        }
        return a.fs().rename(innerPath(a, from), innerPath(b, to));
    }

    @Override
    public boolean setLastModified(String path, long time) {
        final Mount m = route(path);
        return m.fs() != null && m.fs().setLastModified(innerPath(m, path), time);
    }

    // ==================== 句柄 ====================

    @Override
    public int open(String path, FsMode mode) {
        final Mount m = route(path);
        final int inner = require(m, path).open(innerPath(m, path), mode);
        int id = newId();
        while (handles.containsKey(id)) {
            id = newId();
        }
        handles.put(id, new Opened(m, inner));
        return id;
    }

    @Override
    public Handle getHandle(int handle) {
        final Opened o = handles.get(handle);
        if (o == null || o.mount().fs() == null) {
            return null;
        }
        final Handle h = o.mount().fs().getHandle(o.inner());
        if (h == null) {
            return null;
        }
        return new Handle() {

            @Override
            public long position() {
                return h.position();
            }

            @Override
            public long length() {
                return h.length();
            }

            @Override
            public int read(byte[] into) {
                return h.read(into);
            }

            @Override
            public long seek(long to) {
                return h.seek(to);
            }

            @Override
            public void write(byte[] value) {
                h.write(value);
            }

            @Override
            public void close() {
                h.close();
                handles.remove(handle);
            }
        };
    }

    private static int newId() {
        return 1 + ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE - 1);
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public void close() {
        closed = true;
        handles.clear();
        if (root.fs() != null) {
            root.fs().close();
        }
        for (final Mount m : mounts) {
            if (m.fs() != null && m.fs() != root.fs()) {
                m.fs().close();
            }
        }
    }
}
