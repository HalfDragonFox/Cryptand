package com.hdf.cryptand.soc.oc;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * ===== 键盘输入 → 固件字符流（纯 Java 零 MC，2026-09-18 / 全键支持 2026-09-25）=====
 *
 * <p>背景（真机问题："进系统后无法打字"）：OC 的键盘按键是**信号**
 * （{@code Keyboard.scala:73-76} 发 {@code "key_down"} / {@code "text_input"} →
 * {@code Machine.scala:670-672} 转成信号 → {@code Architecture.onSignal()}），
 * 而 Cryptand OS 的 shell 是**从 UART 读字符**的（{@code console.c} 的 {@code shell_poll_uart}）。</p>
 *
 * <p>本类只做"键名/文本 → 要喂给 UART 的字节"，**不碰任何 OC 类型**，因此可以离线自测
 * （{@code runOcKeyboardTest}）。</p>
 *
 * <h3>用户 2026-09-25 要求：标准按键全部支持</h3>
 * <p>按**真实终端**的表示法来映射（不发明自己的编码）：</p>
 * <ul>
 *   <li>回车/退格/Tab/Esc ⇒ ASCII 控制码（`\r` / 8 / `\t` / 27）；
 *       <b>Delete 不是控制码</b>：真实终端里它和方向键一样是 ANSI 序列（`ESC [ 3 ~`），
 *       127（DEL）是**退格键**的另一种编码 —— 两者混用会让"删光标处"变成"删左边"；</li>
 *   <li>方向键 / Home / End ⇒ ANSI CSI 序列（`ESC [ A/B/C/D/H/F`），
 *       Insert/PageUp/PageDown ⇒ `ESC [ 2~/5~/6~`，F1..F12 ⇒ 各自的标准序列；</li>
 *   <li>可打印字符（含 Shift 后的符号）⇒ 字符本身的字节。</li>
 * </ul>
 * <p>⚠ 键名一律**小写归一**后再匹配（OC 送 {@code "numpad_enter"} 还是 {@code "numpadenter"}
 * 这类差别不该让我们静默丢键），并且同时接受常见别名（`return`/`back`/`del`/`esc`…）。</p>
 *
 * <h3>⚠ 未映射的键名不再静默丢弃</h3>
 * <p>原实现 `default -> EMPTY` 什么都不打，导致"回车按下去没反应"这种问题只能靠猜
 * （无法知道 OC 到底送了什么键名）。现在按项目统一口径：`-Dcryptand.debug.calls=1` 时
 * **逐次打印真实键名**，默认静默 —— 诊断要么关掉、要么全打，不做采样。</p>
 */
public final class OcKeyboardInput {

    private static final byte[] EMPTY = new byte[0];

    /** 诊断开关（与核心的组件调用诊断同一个属性，便于一次开启全部相关日志） */
    private static boolean debugKeys() {
        return Boolean.getBoolean("cryptand.debug.calls");
    }

    private static byte[] ansi(char finalByte) {
        return new byte[]{27, '[', (byte) finalByte};
    }

    private static byte[] csi(String digits) {
        final byte[] out = new byte[3 + digits.length()];
        out[0] = 27;
        out[1] = '[';
        for (int i = 0; i < digits.length(); i++) {
            out[2 + i] = (byte) digits.charAt(i);
        }
        out[out.length - 1] = '~';
        return out;
    }

    private OcKeyboardInput() {
    }

    /**
     * OC 的 {@code key_down} 键名 → UART 字节（真实终端的表示法）。
     *
     * @return 要注入的字节；确实没有字符表示的键（纯修饰键等）返回**空数组**，
     *         并在诊断开关打开时打印键名（不再静默）
     */
    public static byte[] encodeKey(String key) {
        if (key == null || key.isEmpty()) {
            return EMPTY;
        }
        if (key.length() == 1) {
            final char c = key.charAt(0);
            return c >= 0x20 && c < 0x7F ? new byte[]{(byte) c} : EMPTY;
        }
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "space", "spacebar" -> new byte[]{' '};
            case "enter", "return", "numpadenter", "numpad_enter", "kp_enter", "kpenter" -> new byte[]{'\r'};
            case "backspace", "back" -> new byte[]{8};
            case "delete", "del" -> csi("3");
            case "tab" -> new byte[]{'\t'};
            case "escape", "esc" -> new byte[]{27};

            // ── 方向键与编辑键：真实终端的 ANSI 序列 ──
            case "up", "arrowup" -> ansi('A');
            case "down", "arrowdown" -> ansi('B');
            case "right", "arrowright" -> ansi('C');
            case "left", "arrowleft" -> ansi('D');
            case "home" -> ansi('H');
            case "end" -> ansi('F');
            case "insert", "ins" -> csi("2");
            case "pageup", "pgup", "prior" -> csi("5");
            case "pagedown", "pgdn", "next" -> csi("6");

            // ── 功能键：F1..F4 是 SS3，F5..F12 是 CSI ~ 序列（与真实终端一致）──
            case "f1" -> new byte[]{27, 'O', 'P'};
            case "f2" -> new byte[]{27, 'O', 'Q'};
            case "f3" -> new byte[]{27, 'O', 'R'};
            case "f4" -> new byte[]{27, 'O', 'S'};
            case "f5" -> csi("15");
            case "f6" -> csi("17");
            case "f7" -> csi("18");
            case "f8" -> csi("19");
            case "f9" -> csi("20");
            case "f10" -> csi("21");
            case "f11" -> csi("23");
            case "f12" -> csi("24");

            default -> {
                // 纯修饰键（shift/ctrl/alt…）本来就不产生字符，但**必须看得见**：
                // 打开诊断开关后逐次打印，等于把"OC 实际送来的键名"交给我们，而不是靠猜。
                if (debugKeys()) {
                    System.out.println("[Keyboard] 未映射的键名（无字符表示）：" + key);
                }
                yield EMPTY;
            }
        };
    }

    /** {@code text_input} 的整段文本（含输入法）→ UTF-8 字节 */
    public static byte[] encodeText(String text) {
        return text == null || text.isEmpty() ? EMPTY : text.getBytes(StandardCharsets.UTF_8);
    }

    /** 该键名是否有可注入的表示（诊断/日志用；⚠ 未映射时也会打印原因） */
    public static boolean isMappable(String key) {
        final byte[] b = encodeKey(key);
        if (b.length == 0 && debugKeys()) {
            System.out.println("[Keyboard] isMappable=false：" + key);
        }
        return b.length > 0;
    }

    /**
     * OC 信号 → 要注入 UART 的字节。**键盘接线的唯一入口**（2026-09-25 定案）。
     *
     * <p>真正的参数从 {@code args[0]}（组件地址）之后开始；形态**以实际依赖的 OC 为准**
     * （2026-09-25 从依赖 jar 与源码逐条核过，不是照抄旧假设）：</p>
     * <ul>
     *   <li>{@code text_input}（{@code Keyboard.scala:91-98}）⇒ {@code args[1]} 是 {@code String}；</li>
     *   <li>{@code key_down}（{@code Keyboard.scala:67-78}）⇒ {@code args[1]} 是 <b>{@code Integer}</b>
     *       （字符码值）、{@code args[2]} 是 <b>{@code Integer}</b>（键码）。
     *       ⚠ <b>不是 {@code Character}</b>：{@code Machine.signal} 对每个参数跑
     *       {@code convertArg}（{@code Machine.scala:385}），而它明确写着
     *       {@code case arg: java.lang.Character => Integer.valueOf(arg.toInt)}（{@code Machine.scala:343}）。
     *       按 Character 判断的接线**永远不成立** ⇒ 控制键全被当"形态不符"丢掉
     *       —— 症状恰恰是"能打字、回车无效"（打字走 {@code text_input} 的 String）。</li>
     * </ul>
     *
     * @return 要注入的字节；<b>不是我们处理的信号</b>返回 {@code null}（调用方消费掉即可）；
     *         是键盘信号但没有字符表示时返回空数组
     */
    public static byte[] encodeSignal(String name, Object[] args) {
        if (name == null || args == null || args.length < 2) {
            return null;
        }
        if ("text_input".equals(name)) {
            return args[1] instanceof String s ? encodeText(s) : shapeMismatch(name, args);
        }
        if (!"key_down".equals(name)) {
            return null;        // key_up / clipboard / component_added …：都不是"要输给固件"的东西
        }
        if (args[1] instanceof Number n) {
            final char ch = (char) n.intValue();
            // 键码缺失（裁剪过的信号）按 0 处理：控制字符仍能靠 ch 认出来
            final int code = args.length >= 3 && args[2] instanceof Number c ? c.intValue() : 0;
            return encodeKeyCode(code, ch);
        }
        return shapeMismatch(name, args);
    }

    /** 诊断开关状态（平台层据此决定要不要组装信号日志；默认**完全静默**） */
    public static boolean debugEnabled() {
        return debugKeys();
    }

    /**
     * 信号的"人看得懂"摘要（诊断用：类型 + 值）。
     *
     * <p>为什么要有它：真机上"某个键没反应"唯一能定性的事实就是**OC 到底送了什么**。
     * 只打类型不够（Integer 到底是字符码还是键码看不出来），所以数值一并打出来。</p>
     */
    public static String describeArgs(Object[] args) {
        if (args == null) {
            return "null";
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            final Object a = args[i];
            if (a == null) {
                sb.append("null");
            } else if (a instanceof Number num) {
                sb.append(a.getClass().getSimpleName()).append('(').append(num).append(')');
            } else if (a instanceof CharSequence s) {
                sb.append('"').append(s).append('"');
            } else {
                sb.append(a.getClass().getSimpleName());
            }
        }
        return sb.toString();
    }

    /**
     * 信号参数形态与预期不符：**不静默**（否则又是"某个键没反应，只能靠猜"）。
     * 默认静默、{@code -Dcryptand.debug.calls=1} 时逐次打印真实参数类型，不做采样。
     */
    private static byte[] shapeMismatch(String name, Object[] args) {
        if (debugKeys()) {
            final StringBuilder sb = new StringBuilder("[Keyboard] 信号参数形态不符：").append(name);
            for (int i = 0; i < args.length; i++) {
                sb.append(" args[").append(i).append("]=")
                        .append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
            }
            System.out.println(sb);
        }
        return EMPTY;
    }

    /**
     * OC 的 `key_down` 原始参数 → UART 字节。**这是真机回车/退格的正解**。
     *
     * <p>键码是**两套体系**（OC 的两条输入路径各自如此；值域不重叠，这里都覆盖）：</p>
     * <ul>
     *   <li><b>LWJGL2 扫描码</b> —— 方块键盘路径：{@code InputBuffer.flushQueuedKey()} 发的是
     *       {@code GLFWTranslator.glfwToLWJGL(...)}（回车 0x1C、↑ 0xC8、← 0xCB、→ 0xCD、↓ 0xD0…）；</li>
     *   <li><b>GLFW 键码</b> —— 屏幕 GUI 内 TextBuffer 的路径（{@code TextBuffer.scala:949}）。</li>
     * </ul>
     * ⚠ 只按 GLFW 查表会让方块键盘上的方向键 / Home / End / Delete / 功能键**全部无表示**
     * （键码对不上 ⇒ 落进 default ⇒ 丢弃）。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>`ch` 是控制字符（`\r`/`\n`/`\b`/`\t`/27/127）⇒ 直接输出该字节；</li>
     *   <li>`ch` 可打印 ⇒ **不在这里输出**（交给 `text_input`，否则会双写一次）；</li>
     *   <li>`ch == 0`（无字符的键）⇒ 按键码转 ANSI 序列（方向键/编辑键/功能键）。</li>
     * </ul>
     *
     * @param keyCode LWJGL2 扫描码或 GLFW 键码（OC 传的第二个参数）
     * @param ch      OC 传的字符码值（无字符的键为 0）
     */
    public static byte[] encodeKeyCode(int keyCode, char ch) {
        if (ch == '\r' || ch == '\n' || ch == '\b' || ch == '\t' || ch == 27 || ch == 127) {
            return new byte[]{(byte) ch};
        }
        if (ch >= 0x20 && ch < 0x7F) {
            return EMPTY;   // 可打印字符由 text_input 负责，避免重复输入
        }
        return switch (keyCode) {
            // ── LWJGL2 扫描码：方块键盘路径（InputBuffer 的 toLWJGL 表）──
            case 0xC8 -> ansi('A');   // ↑
            case 0xD0 -> ansi('B');   // ↓
            case 0xCD -> ansi('C');   // →
            case 0xCB -> ansi('D');   // ←
            case 0xC7 -> ansi('H');   // Home
            case 0xCF -> ansi('F');   // End
            case 0xD2 -> csi("2");    // Insert
            case 0xD3 -> csi("3");    // Delete（与真实终端一致：127 是退格）
            case 0xC9 -> csi("5");    // PageUp
            case 0xD1 -> csi("6");    // PageDown
            case 0x3B -> new byte[]{27, 'O', 'P'};   // F1
            case 0x3C -> new byte[]{27, 'O', 'Q'};   // F2
            case 0x3D -> new byte[]{27, 'O', 'R'};   // F3
            case 0x3E -> new byte[]{27, 'O', 'S'};   // F4
            case 0x3F -> csi("15");   // F5
            case 0x40 -> csi("17");
            case 0x41 -> csi("18");
            case 0x42 -> csi("19");
            case 0x43 -> csi("20");
            case 0x44 -> csi("21");   // F10
            case 0x57 -> csi("23");   // F11
            case 0x58 -> csi("24");   // F12
            case 0x0E -> new byte[]{8};        // Backspace（char 为 0 的裁剪信号）
            case 0x0F -> new byte[]{'\t'};     // Tab
            case 0x1C -> new byte[]{'\r'};     // Enter

            // ── GLFW 键码：屏幕 GUI 内 TextBuffer 路径 ──
            case 265 -> ansi('A');
            case 264 -> ansi('B');
            case 262 -> ansi('C');
            case 263 -> ansi('D');
            case 268 -> ansi('H');
            case 269 -> ansi('F');
            case 260 -> csi("2");
            case 261 -> csi("3");
            case 266 -> csi("5");
            case 267 -> csi("6");
            case 290 -> new byte[]{27, 'O', 'P'};
            case 291 -> new byte[]{27, 'O', 'Q'};
            case 292 -> new byte[]{27, 'O', 'R'};
            case 293 -> new byte[]{27, 'O', 'S'};
            case 294 -> csi("15");
            case 295 -> csi("17");
            case 296 -> csi("18");
            case 297 -> csi("19");
            case 298 -> csi("20");
            case 299 -> csi("21");
            case 300 -> csi("23");
            case 301 -> csi("24");
            case 259 -> new byte[]{8};
            case 258 -> new byte[]{'\t'};
            case 257 -> new byte[]{'\r'};

            default -> {
                if (debugKeys()) {
                    System.out.println("[Keyboard] key_down 未映射：code=0x" + Integer.toHexString(keyCode)
                            + " (" + keyCode + ") char=" + (int) ch);
                }
                yield EMPTY;
            }
        };
    }
}
