package com.hdf.cryptand.soc.fs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * ===== 盘文件系统实现：真实文件夹 + OC 配额 + 随机句柄（2026-09-18）=====
 *
 * <p>三层语义各归其位：</p>
 * <ul>
 *   <li>{@link DiskMount} —— 路径与安全边界（jail / 非法字符 / 符号链接）；</li>
 *   <li><b>本类</b> —— OC 的 FileSystem 契约（查询绝不抛、list 带尾斜杠…）+ 配额 + 句柄；</li>
 *   <li>核心侧（阶段 2）—— 把这套语义映射成 {@code OcAbi.FS_*} 寄存器/邮箱调用。</li>
 * </ul>
 *
 * <h3>配额（照 {@code cryptand-fs-design.md §3.5} 策略 A：每次操作前重算）</h3>
 * <pre>
 *   used = Σ 对所有条目(文件与目录) [ FILE_COST + (文件 ? 实际字节 : 0) ]
 * </pre>
 * <p>为什么每次重算而不是记计数器：盘背后是<b>真实文件夹</b>，玩家/AI/别的程序都能直接往里写，
 * 计数器一定会漂。数据量是 KB 级（Cryptand 盘 ≤ 256 KB、条目数十级），O(n) 完全可接受 ——
 * "永远正确"比"省一点 CPU"值钱。三个检查点照 OC（{@code Capacity.scala:49/97/141}）：
 * mkdir、open(写类)、write；且<b>先检查后写</b>，绝不产生半个文件。</p>
 *
 * <h3>句柄</h3>
 * <ul>
 *   <li>id <b>随机正数</b>（照 OC，降低复合文件系统里的冲突概率）；</li>
 *   <li>上限 {@value #MAX_HANDLES}（超出 ⇒ {@code ERR_TOO_MANY_HANDLES}）；</li>
 *   <li>一个文件同时只允许一个写句柄（照 OC，再开 ⇒ {@code ERR_NOT_FOUND}）；</li>
 *   <li><b>打开中的文件/目录不可删</b>（照 OC {@code canDelete = handle.isEmpty}）。</li>
 * </ul>
 */
public final class DiskFileSystem implements CryptandFileSystem {

    /** 每个条目的固定开销（照 OC 的 {@code fileCost}；数值自定、机制同源） */
    public static final long FILE_COST = 512;

    /** 同时打开的句柄上限（照 OC 组件层的语义） */
    public static final int MAX_HANDLES = 16;

    private final DiskMount mount;
    /** 总容量（字节）；**< 0 表示不限制**（照 OC：spaceTotal 返回负值即无限制） */
    private final long capacityBytes;
    private final boolean readOnly;

    private final Map<Integer, DiskHandle> handles = new LinkedHashMap<>();

    /** 实例是否已被 {@link #close()} 关掉（与 {@link FatFileSystem} 同一份契约） */
    private boolean closed;

    public DiskFileSystem(DiskMount mount, long capacityBytes, boolean readOnly) {
        this.mount = mount;
        this.capacityBytes = capacityBytes;
        this.readOnly = readOnly;
    }

    public DiskMount mount() {
        return mount;
    }

    // ==================== 元信息 ====================

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public long spaceTotal() {
        return readOnly ? 0 : capacityBytes;      // 照 OC：只读系统返回 0
    }

    @Override
    public long spaceUsed() {
        return computeUsed();
    }

    /** used = Σ [FILE_COST + (文件? 实际字节 : 0)]，每次重算（策略 A） */
    private long computeUsed() {
        final Path root = mount.root();
        if (!Files.isDirectory(root)) {
            return 0;
        }
        long sum = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (final Path p : (Iterable<Path>) walk::iterator) {
                if (p.equals(root)) {
                    continue;
                }
                sum += FILE_COST;
                if (Files.isRegularFile(p)) {
                    sum += Files.size(p);
                }
            }
        } catch (IOException ignored) {
            // 读不到就当没占用：配额是"防止写爆"，不是审计
        }
        return sum;
    }

    /** 配额闸门：正增量才检查；容量为负 = 无限 */
    private void ensureSpace(long delta) {
        if (readOnly) {
            throw FsException.readOnly("(read-only disk)");
        }
        if (capacityBytes < 0 || delta <= 0) {
            return;
        }
        final long used = computeUsed();
        if (used + delta > capacityBytes) {
            throw FsException.noSpace(delta, Math.max(0, capacityBytes - used));
        }
    }

    // ==================== 查询（绝不抛） ====================

    @Override
    public boolean exists(String path) {
        try {
            return Files.exists(mount.resolve(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public long size(String path) {
        try {
            final Path real = mount.resolve(path);
            return Files.isRegularFile(real) ? Files.size(real) : 0;   // 目录返回 0
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    @Override
    public boolean isDirectory(String path) {
        try {
            return Files.isDirectory(mount.resolve(path));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public long lastModified(String path) {
        try {
            final Path real = mount.resolve(path);
            return Files.exists(real) ? Files.getLastModifiedTime(real).toMillis() : 0;
        } catch (IOException | RuntimeException e) {
            return 0;
        }
    }

    @Override
    public String[] list(String path) {
        final Path real;
        try {
            real = mount.resolve(path);
        } catch (FsException e) {
            return null;
        }
        if (!Files.isDirectory(real)) {
            return null;                      // 不存在 / 不是目录 ⇒ null（不是空数组）
        }
        final List<String> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(real)) {
            for (final Path p : (Iterable<Path>) s::iterator) {
                // 子目录**带尾斜杠**（照 OC）；文件系统枚举顺序不保证，这里排序让结果可断言
                out.add(p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""));
            }
        } catch (IOException e) {
            return null;
        }
        out.sort(String::compareTo);
        return out.toArray(new String[0]);
    }

    // ==================== 变更 ====================

    @Override
    public boolean delete(String path) {
        if (readOnly) {
            return false;                     // 照 OC：只读系统 delete 永远 false
        }
        final String clean = DiskMount.normalizeGuestPath(path);
        if (clean.isEmpty()) {
            return false;                     // 根不可删
        }
        final Path real;
        try {
            real = mount.resolve(clean);
        } catch (FsException e) {
            return false;
        }
        if (!Files.exists(real) || isOpen(clean)) {
            return false;                     // 不存在 / 打开中（含其内部项）
        }
        try {
            if (Files.isDirectory(real)) {
                try (Stream<Path> s = Files.list(real)) {
                    if (s.findAny().isPresent()) {
                        return false;         // 非空目录不可删（照 OC）
                    }
                }
            }
            Files.delete(real);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean makeDirectory(String path) {
        if (readOnly) {
            return false;
        }
        final String clean = DiskMount.normalizeGuestPath(path);
        if (clean.isEmpty()) {
            return false;
        }
        // 只建一层：父目录必须已经存在（照 OC 的 makeDirectory 语义；递归建走 makeDirectories）
        final int slash = clean.lastIndexOf('/');
        final Path parent = slash < 0 ? mount.root() : mount.resolve(clean.substring(0, slash));
        if (!Files.isDirectory(parent)) {
            return false;
        }
        final Path real = mount.resolve(clean);
        if (Files.exists(real)) {
            return false;
        }
        ensureSpace(FILE_COST);               // 检查点 1：建目录也要占 FILE_COST
        try {
            Files.createDirectory(real);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean rename(String from, String to) {
        final String a = DiskMount.normalizeGuestPath(from);
        final String b = DiskMount.normalizeGuestPath(to);
        if (readOnly) {
            throw FsException.readOnly(from);
        }
        if (a.isEmpty() || b.isEmpty()) {
            return false;                     // 根不可改名
        }
        final Path src = mount.resolve(a);
        if (!Files.exists(src)) {
            throw FsException.notFound(from);  // 照 OC：源不存在 ⇒ FileNotFoundException
        }
        final Path dst = mount.resolve(b);
        if (Files.exists(dst) || isOpen(a) || isOpen(b)) {
            return false;
        }
        try {
            Files.move(src, dst);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public boolean setLastModified(String path, long time) {
        if (readOnly) {
            return false;
        }
        try {
            final Path real = mount.resolve(path);
            if (!Files.exists(real)) {
                return false;
            }
            Files.setLastModifiedTime(real, FileTime.fromMillis(time));
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    // ==================== 句柄 ====================

    @Override
    public int open(String path, FsMode mode) {
        if (closed) {
            throw new FsException(com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_HANDLE, "filesystem already closed");
        }
        final String clean = DiskMount.normalizeGuestPath(path);
        if (clean.isEmpty()) {
            throw FsException.notFound(path);              // 根不是文件
        }
        if (readOnly && mode.writable()) {
            throw FsException.readOnly(path);
        }
        final Path real = mount.resolve(clean);
        if (Files.isDirectory(real)) {
            throw FsException.notFound(path);              // 目标是目录 ⇒ 任何模式都不行（照 OC）
        }
        final boolean exists = Files.isRegularFile(real);
        if (!exists && mode.requiresExisting()) {
            throw FsException.notFound(path);              // READ / READ_WRITE 要求已存在
        }
        if (mode.writable() && hasWriteHandle(clean)) {
            throw FsException.notFound(path);              // 写句柄独占（照 OC）
        }
        if (handles.size() >= MAX_HANDLES) {
            throw FsException.tooManyHandles(MAX_HANDLES);
        }
        // 检查点 2：新建 → 占 FILE_COST；截断 → **释放**空间（所以是负增量，不检查）
        final long delta = mode.truncate() ? -size(clean) : (exists ? 0 : FILE_COST);
        if (delta > 0) {
            ensureSpace(delta);
        }
        try {
            final RandomAccessFile raf = new RandomAccessFile(real.toFile(), mode.writable() ? "rw" : "r");
            if (mode.truncate()) {
                raf.setLength(0);
            }
            if (mode.append()) {
                raf.seek(raf.length());
            }
            final int id = nextHandleId();
            handles.put(id, new DiskHandle(id, clean, mode, raf));
            return id;
        } catch (IOException e) {
            throw FsException.notFound(path + " (" + e.getMessage() + ")");
        }
    }

    @Override
    public Handle getHandle(int handle) {
        return handles.get(handle);           // 无此句柄 ⇒ null（绝不抛）
    }

    @Override
    public void close() {
        closed = true;
        for (final DiskHandle h : new ArrayList<>(handles.values())) {
            h.close();
        }
        handles.clear();
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    /** 该路径（或它内部的任何东西）是否有打开的句柄 —— 打开中的不可删/不可改名 */
    private boolean isOpen(String clean) {
        for (final DiskHandle h : handles.values()) {
            if (h.path.equals(clean) || h.path.startsWith(clean + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean hasWriteHandle(String clean) {
        for (final DiskHandle h : handles.values()) {
            if (h.path.equals(clean) && h.mode.writable()) {
                return true;
            }
        }
        return false;
    }

    /** 随机句柄 id（照 OC：随机而非递增，降低复合 FS 里的冲突概率） */
    private int nextHandleId() {
        for (int i = 0; i < 4096; i++) {
            final int id = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
            if (!handles.containsKey(id)) {
                return id;
            }
        }
        throw FsException.tooManyHandles(MAX_HANDLES);
    }

    // ==================== 句柄实现 ====================

    private final class DiskHandle implements CryptandFileSystem.Handle {

        private final int id;
        private final String path;            // guest 路径（干净形式）
        private final FsMode mode;
        private final RandomAccessFile raf;
        private boolean closed;

        DiskHandle(int id, String path, FsMode mode, RandomAccessFile raf) {
            this.id = id;
            this.path = path;
            this.mode = mode;
            this.raf = raf;
        }

        @Override
        public long position() {
            ensureOpen();
            try {
                return raf.getFilePointer();
            } catch (IOException e) {
                throw FsException.badHandle(id);
            }
        }

        @Override
        public long length() {
            ensureOpen();
            try {
                return raf.length();
            } catch (IOException e) {
                throw FsException.badHandle(id);
            }
        }

        @Override
        public int read(byte[] into) {
            ensureOpen();
            if (!mode.readable()) {
                throw FsException.badHandle(id);   // 照 OC："bad file descriptor"
            }
            try {
                return raf.read(into);             // EOF ⇒ -1（RandomAccessFile 语义与 OC 一致）
            } catch (IOException e) {
                throw FsException.badHandle(id);
            }
        }

        @Override
        public long seek(long to) {
            ensureOpen();
            if (to < 0) {
                throw FsException.badMode("invalid offset " + to);   // 照 OC："invalid offset"
            }
            try {
                raf.seek(to);
                return raf.getFilePointer();
            } catch (IOException e) {
                throw FsException.badHandle(id);
            }
        }

        @Override
        public void write(byte[] value) {
            ensureOpen();
            if (!mode.writable()) {
                throw FsException.badHandle(id);
            }
            if (value == null || value.length == 0) {
                return;
            }
            try {
                final long length = raf.length();
                final long target = mode.append() ? length : raf.getFilePointer();
                // 检查点 3：required = 这次写会让文件**净增**多少（覆盖写不占新空间）
                final long required = Math.max(0, target + value.length - length);
                if (required > 0) {
                    ensureSpace(required);
                }
                if (mode.append()) {
                    raf.seek(length);
                }
                raf.write(value);
            } catch (IOException e) {
                throw FsException.badHandle(id);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            handles.remove(id);
            try {
                raf.close();
            } catch (IOException ignored) {
                // 关闭失败没有补救动作；句柄已经从表里摘掉了
            }
        }

        private void ensureOpen() {
            if (closed) {
                throw FsException.badHandle(id);   // 照 OC："file is closed"
            }
        }

        @Override
        public String toString() {
            return "Handle[" + id + " " + path + " " + mode + (closed ? " closed" : "") + "]";
        }
    }
}
