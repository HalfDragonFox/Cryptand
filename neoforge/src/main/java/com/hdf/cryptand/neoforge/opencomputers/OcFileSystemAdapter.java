package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.fs.FsException;
import com.hdf.cryptand.soc.fs.FsMode;
import com.hdf.cryptand.soc.oc.OcAbi;

import li.cil.oc.api.fs.FileSystem;
import li.cil.oc.api.fs.Handle;
import li.cil.oc.api.fs.Mode;

import net.minecraft.nbt.CompoundTag;

import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * ===== Cryptand 盘 → OC 文件系统（2026-09-18）=====
 *
 * <p>用户定案：「硬盘软盘等**还是走 OC**」。于是盘的权威语义 = OC 的
 * {@link FileSystem}，而实现留在 {@code common}（{@link CryptandFileSystem}，纯 Java 可离线测）。
 * 本类就是那层适配：**方法一一对应、错误码翻成 OC 的异常类型**。</p>
 *
 * <h3>为什么值得这么接（而不是自己搞一套）</h3>
 * <ul>
 *   <li>OC 的 Lua 程序、OC 的 {@code filesystem} 组件层、我们的固件，**共用同一份文件语义** ——
 *       不会出现"两个世界各自理解 EOF/目录尾斜杠"的漂移；</li>
 *   <li>设计文档 §6.4 那条"让 OC 的 Lua 程序也能读写 Cryptand 盘"从**可选项**变成顺带收获；</li>
 *   <li>{@link CryptandFileSystem} 仍可离线自测（{@code runFsTest} / {@code runFsE2ETest}），
 *       本类只做翻译，逻辑为零。</li>
 * </ul>
 *
 * <h3>错误映射（照 {@code cryptand-fs-design.md §3.3.3} 的反向表）</h3>
 * <p>OC 用"抛异常 vs 返回 false/nil"两种方式表达失败，所以适配器必须把
 * {@link FsException#errCode()} 翻回去：不存在 → {@code FileNotFoundException}、
 * 空间不足 → {@code IOException("not enough space")}、句柄坏 → {@code IOException("bad file descriptor")}、
 * 只读 → {@code IllegalArgumentException("...read only")}。照抄 OC 原版的措辞，
 * 让 OC 侧的调用方（与 Lua）看到与原生盘**一模一样**的错误。</p>
 *
 * <p>⚠ {@code saveData/loadData} 是空实现：Cryptand 盘的持久化就是**宿主文件夹本身**，
 * 不存在"把文件系统塞进 NBT"这回事（这正是"盘 = 文件夹"的收益）。</p>
 */
public final class OcFileSystemAdapter implements FileSystem {

    private final CryptandFileSystem fs;

    public OcFileSystemAdapter(CryptandFileSystem fs) {
        this.fs = fs;
    }

    public CryptandFileSystem cryptand() {
        return fs;
    }

    // ==================== 元信息 ====================

    @Override
    public boolean isReadOnly() {
        return fs.isReadOnly();
    }

    @Override
    public long spaceTotal() {
        return fs.spaceTotal();
    }

    @Override
    public long spaceUsed() {
        return fs.spaceUsed();
    }

    // ==================== 查询（OC 契约：绝不抛） ====================

    @Override
    public boolean exists(String path) {
        return fs.exists(path);
    }

    @Override
    public long size(String path) {
        return fs.size(path);
    }

    @Override
    public boolean isDirectory(String path) {
        return fs.isDirectory(path);
    }

    @Override
    public long lastModified(String path) {
        return fs.lastModified(path);
    }

    @Override
    public String[] list(String path) {
        return fs.list(path);
    }

    // ==================== 变更 ====================

    @Override
    public boolean delete(String path) {
        return fs.delete(path);
    }

    @Override
    public boolean makeDirectory(String path) {
        return fs.makeDirectory(path);
    }

    @Override
    public boolean rename(String from, String to) throws FileNotFoundException {
        try {
            return fs.rename(from, to);
        } catch (FsException e) {
            throw translateOpen(e, from);
        }
    }

    @Override
    public boolean setLastModified(String path, long time) {
        return fs.setLastModified(path, time);
    }

    // ==================== 句柄 ====================

    @Override
    public int open(String path, Mode mode) throws FileNotFoundException {
        try {
            return fs.open(path, map(mode));
        } catch (FsException e) {
            throw translateOpen(e, path);
        }
    }

    @Override
    public Handle getHandle(int handle) {
        final CryptandFileSystem.Handle h = fs.getHandle(handle);
        return h == null ? null : new OcHandle(h);      // 无此句柄返回 null（照 OC）
    }

    /**
     * OC 销毁环境（关机 / 拔盘 / 区块卸载）—— 这里**归还借用**，不直接关实例。
     *
     * <p>⚠ 为什么不能直接 {@code fs.close()}：这个 {@code fs} 是**挂载表持有的共享实例**
     * （一块盘 = 一个实例，按盘地址缓存），而"销毁一个环境"与"这块盘以后不再用"是两件事。
     * 直接关掉而挂载表里还留着它 ⇒ 下次挂同一块盘拿到的是**死实例**，
     * 症状正是 2026-09-26 真机日志里的
     * {@code disk … cannot be mounted: FsException[bad handle] filesystem already closed}。
     * 真正的卸载（落盘 + 关句柄 + 清索引）由 {@link OcDiskMounts#release} 在**最后一个**
     * 借用者归还时执行。</p>
     */
    @Override
    public void close() {
        OcDiskMounts.release(fs);
    }

    @Override
    public void saveData(CompoundTag nbt) {
        // 不需要：盘的持久化就是宿主文件夹本身（见类注释）
    }

    @Override
    public void loadData(CompoundTag nbt) {
        // 同上
    }

    // ==================== 内部：句柄包装 ====================

    /** 把 Cryptand 的句柄直接委托出去，只把异常翻译成 OC 认的类型 */
    private static final class OcHandle implements Handle {

        private final CryptandFileSystem.Handle h;

        OcHandle(CryptandFileSystem.Handle h) {
            this.h = h;
        }

        @Override
        public long position() {
            return h.position();
        }

        @Override
        public long length() {
            return h.length();
        }

        @Override
        public int read(byte[] into) throws IOException {
            try {
                return h.read(into);            // EOF 返回 -1（两边语义一致）
            } catch (FsException e) {
                throw translateIo(e);
            }
        }

        @Override
        public long seek(long to) throws IOException {
            try {
                return h.seek(to);
            } catch (FsException e) {
                throw translateIo(e);
            }
        }

        @Override
        public void write(byte[] value) throws IOException {
            try {
                h.write(value);
            } catch (FsException e) {
                throw translateIo(e);
            }
        }

        @Override
        public void close() {
            h.close();
        }
    }

    // ==================== 内部：映射 ====================

    /**
     * OC 的 {@link Mode} → Cryptand 的 {@link FsMode}。
     *
     * <p>⚠ **只能映射三个**：实际依赖的 OC（opencomputers-rebooted {@code 8614366}）里
     * {@code Mode} 只有 {@code Read}/{@code Write}/{@code Append}；带 {@code r+}/{@code w+}/{@code a+}
     * 的六值版是更晚的 API（{@code .ai_cache} 里的源码就是那个新版 —— 源码版本 ≠ 依赖版本，
     * 所以接口签名要以 {@code javap <实际 jar>} 为准，别直接照抄 .ai_cache 的新源码）。</p>
     *
     * <p>这不影响我们的固件：固件走的是 ABI 的六种模式（{@link FsMode#fromId}），
     * 根本不经过 OC 的 {@code Mode}；本方法只服务"OC 的 Lua 程序直接读写 Cryptand 盘"。</p>
     */
    private static FsMode map(Mode mode) {
        return switch (mode) {
            case Read -> FsMode.READ;
            case Write -> FsMode.WRITE;
            case Append -> FsMode.APPEND;
        };
    }

    /**
     * ABI 错误码 → {@code open}/{@code rename} 的异常。
     *
     * <p>OC 的这两个方法**只声明 FileNotFoundException** —— 所以按 OC 的实际契约，任何打不开的原因
     * （不存在 / 空间不足 / 只读 / 模式非法 / 句柄超限）都归成它，并把真实原因写进 message
     * （OC 自己的 {@code OutputStreamFileSystem.scala:27-35} 也是这么做的：条件不满足就抛
     * {@code FileNotFoundException(path)}）。</p>
     */
    private static FileNotFoundException translateOpen(FsException e, String path) {
        return new FileNotFoundException(path + " (" + OcAbi.errorText(e.errCode()) + ")");
    }

    /**
     * ABI 错误码 → 句柄操作的 {@link IOException}（这些方法声明了 IOException）。
     *
     * <p>措辞照 OC 原版（{@code "not enough space"} / {@code "bad file descriptor"} /
     * {@code "invalid offset"}），让 OC 侧的调用方与 Lua 脚本看到与原生盘**一样**的错误文本。</p>
     */
    private static IOException translateIo(FsException e) {
        return switch (e.errCode()) {
            case OcAbi.ERR_NO_SPACE -> new IOException("not enough space");
            case OcAbi.ERR_BAD_HANDLE -> new IOException("bad file descriptor");
            case OcAbi.ERR_READ_ONLY -> new IOException("bad file descriptor");
            case OcAbi.ERR_BAD_MODE -> new IOException("invalid offset");
            default -> new IOException(e.getMessage() == null ? "io error" : e.getMessage());
        };
    }
}
