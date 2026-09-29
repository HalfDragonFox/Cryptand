package com.hdf.cryptand.soc.fs;

/**
 * ===== 打开模式（照 OC 的 {@code li.cil.oc.api.fs.Mode}，2026-09-18）=====
 *
 * <p>OC 的 Mode 是枚举 + 五个谓词（{@code Mode.java:12-37}）。这里逐条对齐 ——
 * 谓词决定的是"能不能创建 / 要不要截断 / 从哪开始写 / 要不要读"，**不要**在实现里
 * 再散落 if 判断，否则两边语义会慢慢漂移。</p>
 *
 * <table border="1">
 *   <tr><th>Mode</th><th>readable</th><th>writable</th><th>truncate</th><th>append</th><th>requiresExisting</th></tr>
 *   <tr><td>READ</td>          <td>✔</td><td></td><td></td><td></td><td>✔</td></tr>
 *   <tr><td>WRITE</td>         <td></td><td>✔</td><td>✔</td><td></td><td></td></tr>
 *   <tr><td>APPEND</td>        <td></td><td>✔</td><td></td><td>✔</td><td></td></tr>
 *   <tr><td>READ_WRITE</td>    <td>✔</td><td>✔</td><td></td><td></td><td>✔</td></tr>
 *   <tr><td>READ_WRITE_TRUNCATE</td><td>✔</td><td>✔</td><td>✔</td><td></td><td></td></tr>
 *   <tr><td>READ_APPEND</td>   <td>✔</td><td>✔</td><td></td><td>✔</td><td></td></tr>
 * </table>
 */
public enum FsMode {

    READ(true, false, false, false, true),
    WRITE(false, true, true, false, false),
    APPEND(false, true, false, true, false),
    READ_WRITE(true, true, false, false, true),
    READ_WRITE_TRUNCATE(true, true, true, false, false),
    READ_APPEND(true, true, false, true, false);

    private final boolean readable;
    private final boolean writable;
    private final boolean truncate;
    private final boolean append;
    private final boolean requiresExisting;

    FsMode(boolean readable, boolean writable, boolean truncate, boolean append, boolean requiresExisting) {
        this.readable = readable;
        this.writable = writable;
        this.truncate = truncate;
        this.append = append;
        this.requiresExisting = requiresExisting;
    }

    public boolean readable() {
        return readable;
    }

    public boolean writable() {
        return writable;
    }

    /** 打开时是否把文件截断为 0（截断 = **释放**空间，配额算法依赖这一点） */
    public boolean truncate() {
        return truncate;
    }

    /** 是否总从文件末尾开始写 */
    public boolean append() {
        return append;
    }

    /** 是否要求文件已存在（不满足则 FileNotFoundException 语义） */
    public boolean requiresExisting() {
        return requiresExisting;
    }

    /**
     * ABI 模式 id → Mode（对应 {@code OcAbi.FS_MODE_*} / {@code hal.h} 的 {@code OC_FS_MODE_*}）。
     *
     * @throws FsException {@code ERR_BAD_MODE}：id 不在 0..5 内
     */
    public static FsMode fromId(int id) {
        return switch (id) {
            case 0 -> READ;
            case 1 -> WRITE;
            case 2 -> APPEND;
            case 3 -> READ_WRITE;
            case 4 -> READ_WRITE_TRUNCATE;
            case 5 -> READ_APPEND;
            default -> throw FsException.badMode("unsupported mode id " + id);
        };
    }

    /**
     * OC 组件层的字符串 → Mode（{@code server/component/FileSystem.scala:337-345}）。
     *
     * <p>固件走的是 id，这个方法留给"OC 的 Lua 程序直接调我们"以及 shell 命令用，
     * 语义与 OC 逐条对齐：{@code r|rb→READ}、{@code w|wb→WRITE}、{@code a|ab→APPEND}、
     * {@code r+|r+b|rb+→READ_WRITE}、{@code w+|w+b|wb+→READ_WRITE_TRUNCATE}、
     * {@code a+|a+b|ab+→READ_APPEND}，其余抛 {@code ERR_BAD_MODE}。</p>
     */
    public static FsMode fromString(String mode) {
        return switch (mode) {
            case "r", "rb" -> READ;
            case "w", "wb" -> WRITE;
            case "a", "ab" -> APPEND;
            case "r+", "r+b", "rb+" -> READ_WRITE;
            case "w+", "w+b", "wb+" -> READ_WRITE_TRUNCATE;
            case "a+", "a+b", "ab+" -> READ_APPEND;
            default -> throw FsException.badMode("unsupported mode: " + mode);
        };
    }
}
