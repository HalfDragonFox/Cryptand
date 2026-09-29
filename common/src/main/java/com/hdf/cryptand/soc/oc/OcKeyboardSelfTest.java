package com.hdf.cryptand.soc.oc;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * ===== 键盘输入链路自测（离线，不启动 MC，2026-09-18）=====
 *
 * <p>针对真机问题"进系统后无法打字"。覆盖两段**可离线验证**的部分：</p>
 * <ol>
 *   <li>{@link OcKeyboardInput}：OC 信号（键名 / 文本 / key_down 的 char+键码）→ 固件 shell 认得的字节
 *       （对齐 {@code console.c} 的 {@code shell_feed}：{@code \r}/{@code \n} 提交、8/127 退格、
 *       0x20..0x7E 入行、ESC 序列走行编辑）；</li>
 *   <li>输入通道：键盘**不再走 UART**（2026-09-27 起统一从消息缓存区进！）⇒ 这条通道的回归在
 *       {@code :common:runMsgQueueTest}（HostMessageRing 满/空边界 + 丢弃可观测）；
 *       UART 现在只服务"真实串口"那一路，其寄存器/状态位/OVR 语义回归在
 *       {@code :common:runUartCacheTest}。</li>
 * </ol>
 *
 * <p>剩下的"OC 键盘组件 → 信号 → onSignal"那一跳只有真人按键才会走，
 * 由平台侧的 {@code oc_key} 工具覆盖其**后续**路径（注入点相同）。</p>
 *
 * <p>跑法：{@code gradlew :common:runOcKeyboardTest}</p>
 */
public final class OcKeyboardSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== Cryptand keyboard input self test (key map only, no MC) ===");
        keyMap();
        textMap();
        signalMap();
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 键名映射 ====================

    private static void keyMap() {
        check("字母键：'a' → 'a'", Arrays.equals(OcKeyboardInput.encodeKey("a"), new byte[]{'a'}),
                new String(OcKeyboardInput.encodeKey("a"), StandardCharsets.US_ASCII));
        check("数字键：'7' → '7'", Arrays.equals(OcKeyboardInput.encodeKey("7"), new byte[]{'7'}), "7");
        check("大写字母键名照原样（OC 单字符键名即字符）",
                Arrays.equals(OcKeyboardInput.encodeKey("A"), new byte[]{'A'}), "A");
        check("enter → CR（固件 shell 以 \\r 提交行）",
                Arrays.equals(OcKeyboardInput.encodeKey("enter"), new byte[]{'\r'}), "CR");
        check("numpadenter 与 enter 同义",
                Arrays.equals(OcKeyboardInput.encodeKey("numpadenter"), new byte[]{'\r'}), "CR");
        check("space → 空格", Arrays.equals(OcKeyboardInput.encodeKey("space"), new byte[]{' '}), "SP");
        check("backspace → 8（console.c 的退格分支）",
                Arrays.equals(OcKeyboardInput.encodeKey("backspace"), new byte[]{8}), "8");
        check("delete → ESC [ 3 ~（与真实终端一致；127 是退格的编码，不是 Delete）",
                Arrays.equals(OcKeyboardInput.encodeKey("delete"), new byte[]{27, '[', '3', '~'}), "ESC[3~");
        check("tab → 9", Arrays.equals(OcKeyboardInput.encodeKey("tab"), new byte[]{'\t'}), "9");
        check("escape → 27", Arrays.equals(OcKeyboardInput.encodeKey("escape"), new byte[]{27}), "27");
        // ── 用户 2026-09-25："标准按键全部支持" ⇒ 按真实终端的 ANSI 表示法 ──
        check("方向键 → ANSI CSI（ESC [ A/B/C/D，与真实终端一致）",
                java.util.Arrays.equals(OcKeyboardInput.encodeKey("up"), new byte[]{27, '[', 'A'})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("down"), new byte[]{27, '[', 'B'})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("left"), new byte[]{27, '[', 'D'})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("right"), new byte[]{27, '[', 'C'}), "ESC[A..D");
        check("功能键 → 标准序列（F1 = SS3 P、F5 = CSI 15~）",
                java.util.Arrays.equals(OcKeyboardInput.encodeKey("f1"), new byte[]{27, 'O', 'P'})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("f5"),
                        new byte[]{27, '[', '1', '5', '~'}), "F1/F5");
        check("编辑键 → Home/End/Ins/PgUp/PgDn 的标准序列",
                java.util.Arrays.equals(OcKeyboardInput.encodeKey("home"), new byte[]{27, '[', 'H'})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("end"), new byte[]{27, '[', 'F'})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("insert"), new byte[]{27, '[', '2', '~'})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("pageup"), new byte[]{27, '[', '5', '~'}), "edit");
        check("键名归一 + 常见别名（Numpad_Enter / Return / Back / Del 都认）",
                java.util.Arrays.equals(OcKeyboardInput.encodeKey("Numpad_Enter"), new byte[]{'\r'})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("Return"), new byte[]{'\r'})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("Back"), new byte[]{8})
                        && java.util.Arrays.equals(OcKeyboardInput.encodeKey("Del"), new byte[]{27, '[', '3', '~'}), "aliases");
        check("空 / 纯修饰键仍不注入（不塞垃圾字节）", OcKeyboardInput.encodeKey("").length == 0
                && OcKeyboardInput.encodeKey(null).length == 0
                && OcKeyboardInput.encodeKey("lshift").length == 0
                && OcKeyboardInput.encodeKey("nonsense").length == 0, "[]");
        check("isMappable 与映射表一致（方向键已可映射，修饰键仍不可）",
                OcKeyboardInput.isMappable("enter") && OcKeyboardInput.isMappable("up")
                        && !OcKeyboardInput.isMappable("lshift"), "ok");
    }

    private static void textMap() {
        final byte[] t = OcKeyboardInput.encodeText("help");
        check("文本按 UTF-8 编码", Arrays.equals(t, "help".getBytes(StandardCharsets.UTF_8)), "help");
        check("空文本 → 空数组", OcKeyboardInput.encodeText("").length == 0
                && OcKeyboardInput.encodeText(null).length == 0, "[]");
    }

    /**
     * OC 信号形态 → 字节（**2026-09-25 的接线点**）。
     *
     * <p>覆盖的正是"能打字、回车退格无效"的根因：真人按键走 {@code key_down}，参数是
     * {@code (char, code)}（{@code Keyboard.scala:67-78}），而老代码只认
     * {@code args[1] instanceof String} ⇒ 控制键全被当"非键盘信号"丢掉。</p>
     */
    private static void signalMap() {
        // ⚠ 形态依据（2026-09-25 从**实际依赖的 jar + OC 源码**逐条核过，不是照抄旧假设）：
        //   Keyboard.scala:123  sendToReachable("computer.checked_signal", player, "key_down", char, code)
        //   Machine.scala:670   剥掉 player/name，把**组件地址**前置 ⇒ args = [地址, char, code]
        //   Machine.scala:385   每个参数都过 convertArg，而 convertArg:343 把 Character 转成
        //                       Integer.valueOf(arg.toInt)
        //   ⇒ **char 到架构这里是 Integer（码值），不是 Character**（按 Character 判断永远不成立）
        //   键码：方块键盘走 GLFWTranslator.glfwToLWJGL（LWJGL2 扫描码：回车 0x1C、↑ 0xC8…），
        //         屏幕 GUI 内的 TextBuffer 路径才是 GLFW 键码。
        final Object addr = "1a2b3c4d-0000-0000-0000-000000000000";
        check("key_down 回车（char=13 的 Integer + LWJGL 键码 0x1C）→ CR",
                Arrays.equals(OcKeyboardInput.encodeSignal("key_down",
                        new Object[]{addr, 13, 0x1C}), new byte[]{'\r'}), "CR");
        check("key_down 退格（char=8）→ 8；Delete（LWJGL 0xD3）→ ESC [ 3 ~",
                Arrays.equals(OcKeyboardInput.encodeSignal("key_down",
                        new Object[]{addr, 8, 0x0E}), new byte[]{8})
                        && Arrays.equals(OcKeyboardInput.encodeSignal("key_down",
                        new Object[]{addr, 0, 0xD3}), new byte[]{27, '[', '3', '~'}), "8/ESC[3~");
        check("key_down 的可打印字符不在这里输出（交给 text_input，防双写）",
                OcKeyboardInput.encodeSignal("key_down",
                        new Object[]{addr, (int) 'a', 0x1E}).length == 0, "[]");
        check("key_down 无字符的键按 LWJGL 键码转 ANSI（↑ = 0xC8 → ESC [ A）",
                Arrays.equals(OcKeyboardInput.encodeSignal("key_down",
                        new Object[]{addr, 0, 0xC8}), new byte[]{27, '[', 'A'}), "ESC[A");
        check("屏幕 GUI 的 TextBuffer 路径用 GLFW 键码（↑ = 265）也认得",
                Arrays.equals(OcKeyboardInput.encodeSignal("key_down",
                        new Object[]{addr, 0, 265}), new byte[]{27, '[', 'A'}), "ESC[A");
        check("key_down 带玩家名（inputUsername 打开时）照常解析",
                Arrays.equals(OcKeyboardInput.encodeSignal("key_down",
                        new Object[]{addr, 9, 0x0F, "player"}), new byte[]{'\t'}), "TAB");
        check("text_input 整段文本 → UTF-8",
                Arrays.equals(OcKeyboardInput.encodeSignal("text_input",
                        new Object[]{addr, "help"}), "help".getBytes(StandardCharsets.UTF_8)), "help");
        check("非输入信号（key_up / clipboard / component_added）→ null（消费掉，不注入）",
                OcKeyboardInput.encodeSignal("key_up", new Object[]{addr, 'a', 65}) == null
                        && OcKeyboardInput.encodeSignal("component_added", new Object[]{addr, OcAbi.GPU_COMPONENT_NAME}) == null
                        && OcKeyboardInput.encodeSignal("text_input", null) == null, "null");
        check("形态不符一律返回空数组（含已废弃的 Character 形态 —— OC 不会这么送，钉住它不被误认）",
                OcKeyboardInput.encodeSignal("key_down", new Object[]{addr, '\r', 257}).length == 0
                        && OcKeyboardInput.encodeSignal("key_down", new Object[]{addr, "enter", 257}).length == 0
                        && OcKeyboardInput.encodeSignal("text_input", new Object[]{addr, 42}).length == 0, "[]");
    }

    // ==================== 说明：UART RX 那两段已删除（2026-09-27）====================
    //
    // 原来这里测"注入 → 16550 的 LSR.DR 置位 → 读 RBR 取字节"（键盘走 UART RX 的旧口径）。
    // 2026-09-27 之后：① 键盘统一从**消息缓存区**进（见 :common:runMsgQueueTest）；
    // ② 16550 的 MMIO 寄存器组按定案整条删除，UART 变成"虚拟机建立的一块硬件 +
    //    寄存器窗口（UartRegs）+ 世界侧队列（UartHardware.offerRx）"⇒ 它的寄存器/状态位/OVR
    //    语义回归在 :common:runUartCacheTest（真固件 + 假 guest RAM 跑完整收发协议）。
    // 留在这里的旧断言会指向一个已经不存在的类（编译不过，也不该被保留成"第二套假绿"）。

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : "  " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }
}
