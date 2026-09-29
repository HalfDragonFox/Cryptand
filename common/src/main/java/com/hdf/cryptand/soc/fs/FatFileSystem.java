package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * ===== FAT 分区 → Cryptand 文件系统契约（2026-09-18，B4 收尾）=====
 *
 * <p>用户定案的分区两类在这里合流：<b>IMAGE 分区</b>（块设备）用本类（FAT 驱动 + 句柄层），
 * <b>FOLDER 分区</b>用 {@link DiskFileSystem}（宿主文件树）。上层（OC / 沙箱 ABI）只认
 * {@link CryptandFileSystem}，因此"盘上是 FAT 还是文件夹"对 guest 完全透明 —— 这正是要的效果。</p>
 *
 * <h3>句柄语义（与 {@link DiskFileSystem} 逐条对齐，也是 OC 的语义）</h3>
 * <ul>
 *   <li>可写模式在<b>内存</b>里编辑，{@code close()} 时整文件落盘 —— 一次落盘而不是每次 write 重写簇链</li>
 *   <li>{@code append} 模式每次写都定位到末尾（不依赖 position 是否被 seek 改过）</li>
 *   <li>{@code truncate} 打开即清空（空间立刻释放：配额算法依赖这个信号）</li>
 *   <li>只读句柄 close 不写回；不可读模式调用 read / 不可写模式调用 write ⇒ {@code ERR_BAD_MODE}</li>
 * </ul>
 */
public final class FatFileSystem implements CryptandFileSystem {

    private final FatVolume vol;
    private final boolean readOnly;
    private final Map<Integer, FatHandle> handles = new HashMap<>();
    private int nextHandle = 1;
    private boolean closed;

    public FatFileSystem(FatVolume vol) {
        this(vol, false);
    }

    /**
     * @param readOnly 只读盘（用户 2026-09-18 澄清的介质语义：**软盘是只读程序介质**，
     *                 硬盘才是模仿 SD 卡/Flash 的可写数据盘）
     */
    public FatFileSystem(FatVolume vol, boolean readOnly) {
        this.vol = vol;
        this.readOnly = readOnly;
    }

    public FatVolume volume() {
        return vol;
    }

    /** 见 {@link CryptandFileSystem#isOpen()}：关掉之后不能再被任何缓存交付出去 */
    @Override
    public boolean isOpen() {
        return !closed;
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public long spaceTotal() {
        return readOnly ? 0 : vol.capacityBytes();   // 照 OC：只读系统返回 0（与 DiskFileSystem 对齐）
    }

    @Override
    public long spaceUsed() {
        return vol.usedBytes();
    }

    @Override
    public boolean exists(String path) {
        return vol.exists(path);
    }

    @Override
    public long size(String path) {
        return vol.size(path);
    }

    @Override
    public boolean isDirectory(String path) {
        return vol.isDirectory(path);
    }

    @Override
    public long lastModified(String path) {
        return vol.lastModified(path);
    }

    /** 路径不存在 ⇒ null（OC 契约：区分"空目录"与"没这个目录"要靠 null，不是空数组） */
    @Override
    public String[] list(String path) {
        try {
            final var entries = vol.list(path);
            final String[] out = new String[entries.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = entries.get(i).name();
            }
            return out;
        } catch (FsException e) {
            return null;
        }
    }

    @Override
    public boolean delete(String path) {
        if (readOnly) {
            return false;
        }
        try {
            vol.remove(path);
            return true;
        } catch (FsException e) {
            return false;
        }
    }

    @Override
    public boolean makeDirectory(String path) {
        if (readOnly) {
            return false;
        }
        try {
            vol.mkdir(path);
            return true;
        } catch (FsException e) {
            return false;
        }
    }

    /**
     * 改名 = 读内容 → 写新名字 → 删旧名字。
     *
     * <p>⚠ 为什么不做"改目录项里的名字"那条快路：FAT 的短名与 LFN 必须同时改（还要重算校验和），
     * 而目标名字可能已被占用、可能需要新的短名 —— 那一堆分支的出错面远大于收益。
     * 目录改名暂不支持，返回 false 而不是假装成功。</p>
     */
    @Override
    public boolean rename(String from, String to) {
        if (readOnly) {
            return false;
        }
        try {
            if (vol.isDirectory(from) || !vol.exists(from) || vol.exists(to)) {
                return false;
            }
            vol.write(to, vol.read(from));
            vol.remove(from);
            return true;
        } catch (FsException e) {
            return false;
        }
    }

    /** FAT 的写入时间由"写入动作"决定，不能任意设置 ⇒ 明确不支持（返回 false，不静默忽略） */
    @Override
    public boolean setLastModified(String path, long time) {
        return false;
    }

    @Override
    public int open(String path, FsMode mode) {
        if (closed) {
            throw new FsException(OcAbi.ERR_BAD_HANDLE, "filesystem already closed");
        }
        if (readOnly && mode.writable()) {
            throw FsException.readOnly(path);       // 与 DiskFileSystem 同一语义：只读盘拒绝可写模式
        }
        if (vol.isDirectory(path)) {
            throw FsException.invalidPath("is a directory: " + path);
        }
        final boolean exists = vol.exists(path);
        if (!exists && mode.requiresExisting()) {
            throw FsException.notFound(path);
        }
        byte[] data;
        if (exists && !mode.truncate()) {
            data = vol.read(path);
        } else {
            data = new byte[0];
        }
        if (mode.truncate() && exists) {
            vol.write(path, new byte[0]);        // 立刻释放簇：配额与 spaceUsed 依赖这一点
        }
        final FatHandle h = new FatHandle(nextHandle++, path, mode, data);
        handles.put(h.id, h);
        return h.id;
    }

    @Override
    public Handle getHandle(int handle) {
        final FatHandle h = handles.get(handle);
        if (h == null) {
            throw FsException.badHandle(handle);
        }
        return h;
    }

    @Override
    public void close() {
        // 先提交所有可写句柄，再清表：中途抛异常也不会留下"表空了但没落盘"的假象
        final var copy = new java.util.ArrayList<>(handles.values());
        handles.clear();
        closed = true;
        for (FatHandle h : copy) {
            h.close();
        }
    }

    /** 单个句柄：内存缓冲 + 位置指针；落盘发生在 close */
    private final class FatHandle implements Handle {

        private final int id;
        private final String path;
        private final FsMode mode;
        private byte[] data;
        private long position;
        private boolean open = true;

        FatHandle(int id, String path, FsMode mode, byte[] data) {
            this.id = id;
            this.path = path;
            this.mode = mode;
            this.data = data;
            if (mode.append()) {
                this.position = data.length;
            }
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public long length() {
            return data.length;
        }

        @Override
        public int read(byte[] into) {
            if (!mode.readable()) {
                throw FsException.badMode("handle is not readable: " + path);
            }
            if (position >= data.length) {
                return -1;                        // OC 契约：读到结尾返回 -1（不是 0）
            }
            final int n = (int) Math.min(into.length, data.length - position);
            System.arraycopy(data, (int) position, into, 0, n);
            position += n;
            return n;
        }

        @Override
        public long seek(long to) {
            position = Math.max(0, Math.min(to, data.length));
            return position;
        }

        @Override
        public void write(byte[] value) {
            if (!mode.writable()) {
                throw FsException.badMode("handle is not writable: " + path);
            }
            final long target = mode.append() ? data.length : position;
            final long end = target + value.length;
            if (end > data.length) {
                data = Arrays.copyOf(data, (int) end);
            }
            System.arraycopy(value, 0, data, (int) target, value.length);
            position = end;
        }

        @Override
        public void close() {
            if (!open) {
                return;
            }
            open = false;
            handles.remove(id);
            if (mode.writable()) {
                vol.write(path, data);            // 唯一的落盘点
            }
        }
    }
}
