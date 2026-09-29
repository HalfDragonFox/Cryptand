package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

/**
 * ===== 文件系统错误（携带 ABI 错误码，2026-09-18）=====
 *
 * <p>为什么不用 OC 那套"抛 {@code IOException} / 返回 {@code false}"：
 * 寄存器 ABI 必须把两种失败**区分开**（见 {@code cryptand-fs-design.md §3.3.3}）——
 * "该重试（空间不足/缓冲太小）"和"该报错（句柄坏了/路径非法）"是两码事。
 * 所以异常带一个 {@code errCode}，核心侧 {@code catch (FsException e) → STATUS=ERROR + e.errCode()}
 * 一条映射就完事，不需要在核心里猜字符串。</p>
 *
 * <p>⚠ 查询类方法（{@code exists/size/isDirectory/lastModified}）**绝不抛**（照 OC 契约），
 * 返回 false / 0；只有"确实有异常语义"的调用才抛 —— 别为了统一而让查询也抛。</p>
 */
public final class FsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int errCode;

    public FsException(int errCode, String message) {
        super(message);
        this.errCode = errCode;
    }

    /** ABI 错误码（{@link OcAbi#ERR_NOT_FOUND} 等），直接写进 STATUS=ERROR 的错误槽 */
    public int errCode() {
        return errCode;
    }

    public static FsException notFound(String path) {
        return new FsException(OcAbi.ERR_NOT_FOUND, "not found: " + path);
    }

    public static FsException badHandle(int handle) {
        return new FsException(OcAbi.ERR_BAD_HANDLE, "bad file descriptor: " + handle);
    }

    public static FsException noSpace(long required, long free) {
        return new FsException(OcAbi.ERR_NO_SPACE, "not enough space (need " + required + ", free " + free + ")");
    }

    public static FsException readOnly(String path) {
        return new FsException(OcAbi.ERR_READ_ONLY, "read only: " + path);
    }

    public static FsException invalidPath(String path) {
        return new FsException(OcAbi.ERR_INVALID_PATH, "invalid path: " + path);
    }

    public static FsException tooManyHandles(int max) {
        return new FsException(OcAbi.ERR_TOO_MANY_HANDLES, "too many open handles (max " + max + ")");
    }

    public static FsException badMode(String detail) {
        return new FsException(OcAbi.ERR_BAD_MODE, detail);
    }

    public static FsException bufTooSmall(long needed) {
        return new FsException(OcAbi.ERR_BUF_TOO_SMALL, "buffer too small (need " + needed + ")");
    }

    @Override
    public String toString() {
        return "FsException[" + OcAbi.errorText(errCode) + "] " + getMessage();
    }
}
