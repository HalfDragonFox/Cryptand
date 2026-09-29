package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.ComponentBus;
import com.hdf.cryptand.soc.oc.OcAbi;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ===== FS_* 调用 → 盘文件系统（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>职责很单一：把 ABI 方法 id + 标量参数 + 缓冲区（入方向）翻译成
 * {@link CryptandFileSystem} 的一次调用，并把结果（标量或 byte[]）包成
 * {@link ComponentBus.Result}。</p>
 *
 * <p><b>为什么放在 common 而不是平台侧的 {@code OcComponentBus}</b>：</p>
 * <ul>
 *   <li>平台侧那份依赖 OC 类型 ⇒ 只能开着 MC 才能测；而文件系统的语义（EOF、目录尾斜杠、
 *       配额、错误码）恰恰是**最需要反复验证**的部分；</li>
 *   <li>放这里 ⇒ 用"假组件总线 + 真 {@code DiskFileSystem}"就能端到端跑
 *       （{@code runFsE2ETest}），不需要 MC、不需要固件；</li>
 *   <li>平台侧只需要把 {@code filesystem} 组件的调用转发到本类（一行），分层与
 *       "common 纯 Java" 的既有纪律一致。</li>
 * </ul>
 *
 * <h3>两个方向的约定（与 {@code OcAbi} / {@code hal.h} 同一张表）</h3>
 * <ul>
 *   <li><b>入方向</b>（固件 → 宿主）：{@code call.buffer()} 就是数据（path / 待写字节）；
 *       {@code FS_RENAME} 用 {@code from + NUL + to} 单缓冲表达两个名字；</li>
 *   <li><b>出方向</b>（宿主 → guest）：{@link #read} / {@link #list} 直接返回 {@code byte[]}，
 *       由核心写进固件自带的缓冲区并把 result0 换成实际写入量（见
 *       {@code OcArchitectureCore} 的出方向分支）；EOF 返回 {@code -1}（不是 byte[]），
 *       核心据此不改写缓冲区 —— 语义与 OC 的 {@code Handle.read} 一致。</li>
 * </ul>
 *
 * <p>失败一律抛 {@link FsException}（带 ABI 错误码），本类翻成
 * {@link ComponentBus.Result#error(int, String)} ⇒ 核心写 {@code STATE=ERROR} + {@code RESULT0=错误码}。</p>
 */
public final class FsCallDispatcher {

    private final CryptandFileSystem fs;

    public FsCallDispatcher(CryptandFileSystem fs) {
        this.fs = fs;
    }

    public CryptandFileSystem fileSystem() {
        return fs;
    }

    /** 从调用里取方法 id 并分发 */
    public ComponentBus.Result invoke(ComponentBus.Call call) {
        return invoke(methodId(call), call.args(), call.buffer());
    }

    /** 分发一次 FS_* 调用 */
    public ComponentBus.Result invoke(int methodId, List<Object> args, byte[] buffer) {
        try {
            return switch (methodId) {
                case OcAbi.FS_OPEN -> ComponentBus.Result.ok(
                        fs.open(text(buffer), FsMode.fromId(argi(args, 0, 0))));
                case OcAbi.FS_READ -> read(argi(args, 0, -1), argi(args, 1, 0));
                case OcAbi.FS_WRITE -> {
                    handle(argi(args, 0, -1)).write(buffer == null ? new byte[0] : buffer);
                    yield ComponentBus.Result.ok(1);
                }
                case OcAbi.FS_SEEK -> ComponentBus.Result.ok(
                        seek(argi(args, 0, -1), argi(args, 1, 0), argi(args, 2, 0)));
                case OcAbi.FS_CLOSE -> {
                    handle(argi(args, 0, -1)).close();
                    yield ComponentBus.Result.ok(0);
                }
                case OcAbi.FS_LIST -> list(text(buffer));
                case OcAbi.FS_DELETE -> ComponentBus.Result.ok(fs.delete(text(buffer)) ? 1 : 0);
                case OcAbi.FS_RENAME -> rename(buffer);
                // 递归建：ABI 表里 FS_MKDIR 对齐的是 OC **组件层**的行为（:120-127），
                // 而 CryptandFileSystem.makeDirectory 只有"单层"语义（照 OC 的 FileSystem 契约）。
                case OcAbi.FS_MKDIR -> ComponentBus.Result.ok(fs.makeDirectories(text(buffer)) ? 1 : 0);
                case OcAbi.FS_EXISTS -> ComponentBus.Result.ok(fs.exists(text(buffer)) ? 1 : 0);
                case OcAbi.FS_SIZE -> ComponentBus.Result.ok(fs.size(text(buffer)));
                case OcAbi.FS_IS_DIR -> ComponentBus.Result.ok(fs.isDirectory(text(buffer)) ? 1 : 0);
                case OcAbi.FS_LAST_MODIFIED ->
                        ComponentBus.Result.ok(fs.lastModified(text(buffer)) / 1000L);
                case OcAbi.FS_SPACE -> ComponentBus.Result.ok(fs.spaceTotal(), fs.spaceUsed());
                case OcAbi.FS_IS_READONLY -> ComponentBus.Result.ok(fs.isReadOnly() ? 1 : 0);
                case OcAbi.FS_STAT -> ComponentBus.Result.ok(
                        fs.exists(text(buffer)) ? 1 : 0,
                        fs.isDirectory(text(buffer)) ? 1 : 0,
                        fs.size(text(buffer)),
                        fs.lastModified(text(buffer)) / 1000L,
                        fs.isReadOnly() ? 1 : 0);
                default -> ComponentBus.Result.error(OcAbi.ERR_UNKNOWN_METHOD,
                        "not a filesystem method: #" + methodId);
            };
        } catch (FsException e) {
            return ComponentBus.Result.error(e.errCode(), e.getMessage());
        } catch (RuntimeException e) {
            return ComponentBus.Result.error(OcAbi.ERR_COMPONENT_FAILED, String.valueOf(e));
        }
    }

    // ==================== 各方法 ====================

    private ComponentBus.Result read(int handleId, int count) {
        final CryptandFileSystem.Handle h = handle(handleId);
        final int want = Math.max(1, Math.min(count <= 0 ? 512 : count, OcAbi.BUF_MAX));
        final byte[] buf = new byte[want];
        final int got = h.read(buf);
        if (got < 0) {
            return ComponentBus.Result.ok(-1);          // EOF：不是 byte[] ⇒ 核心不改写缓冲区
        }
        return ComponentBus.Result.ok(got == buf.length ? buf : java.util.Arrays.copyOf(buf, got));
    }

    private long seek(int handleId, int whence, int offset) {
        final CryptandFileSystem.Handle h = handle(handleId);
        final long base = switch (whence) {
            case OcAbi.FS_SEEK_SET -> 0L;
            case OcAbi.FS_SEEK_CUR -> h.position();
            case OcAbi.FS_SEEK_END -> h.length();
            default -> throw FsException.badMode("bad whence " + whence);
        };
        return h.seek(base + offset);
    }

    /**
     * {@code list} → NUL 分隔的名字（目录带尾斜杠，直接用文件系统给的形态）。
     *
     * <p>⚠ FS_LIST 是唯一"入方向与出方向共用同一块缓冲区"的方法：输入的 path 以 **NUL 结尾**
     * 放在缓冲区起始（容量由 {@code MB_BUF_LEN} 给出），结果由核心**原地覆盖**写回。
     * 这里只负责"按 NUL 截断读出路径 + 产出结果字节"，覆盖与长度写回在核心的出方向分支完成。</p>
     */
    private ComponentBus.Result list(String path) {
        final String[] names = fs.list(path);
        if (names == null) {
            return ComponentBus.Result.ok(-1);          // 不是目录 / 不存在（照 OC 返回 null）
        }
        final StringBuilder sb = new StringBuilder();
        for (final String n : names) {
            sb.append(n).append('\0');
        }
        return ComponentBus.Result.ok(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private ComponentBus.Result rename(byte[] buffer) {
        final String[] parts = splitNul(buffer);
        if (parts.length < 2) {
            throw FsException.badMode("rename needs \"from\\0to\" in one buffer");
        }
        return ComponentBus.Result.ok(fs.rename(parts[0], parts[1]) ? 1 : 0);
    }

    // ==================== 工具 ====================

    private CryptandFileSystem.Handle handle(int id) {
        final CryptandFileSystem.Handle h = id < 0 ? null : fs.getHandle(id);
        if (h == null) {
            throw FsException.badHandle(id);
        }
        return h;
    }

    /** 缓冲区 → 路径（NUL 截断；空缓冲视作根） */
    private static String text(byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return "";
        }
        int end = buffer.length;
        for (int i = 0; i < buffer.length; i++) {
            if (buffer[i] == 0) {
                end = i;
                break;
            }
        }
        return new String(buffer, 0, end, StandardCharsets.UTF_8);
    }

    private static String[] splitNul(byte[] buffer) {
        if (buffer == null || buffer.length == 0) {
            return new String[0];
        }
        final String s = new String(buffer, StandardCharsets.UTF_8);
        return s.split("\0", -1);
    }

    private static int argi(List<Object> args, int index, int def) {
        if (args == null || index < 0 || index >= args.size() || !(args.get(index) instanceof Number n)) {
            return def;
        }
        return n.intValue();
    }

    static int methodId(ComponentBus.Call call) {
        final String m = call == null ? null : call.method();
        if (m == null || m.length() < 2 || m.charAt(0) != '#') {
            return -1;
        }
        try {
            return Integer.parseInt(m.substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
