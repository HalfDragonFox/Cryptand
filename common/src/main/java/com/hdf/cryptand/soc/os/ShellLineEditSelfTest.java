package com.hdf.cryptand.soc.os;

import com.hdf.cryptand.soc.board.OcBoardLayout;
import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.device.DebugConsoleDevice;
import com.hdf.cryptand.soc.device.RegBankDevice;
import com.hdf.cryptand.soc.device.TimerDevice;
import com.hdf.cryptand.soc.oc.ComponentBus;
import com.hdf.cryptand.soc.oc.OcAbi;
import com.hdf.cryptand.soc.oc.OcArchitectureCore;
import com.hdf.cryptand.soc.oc.OcSandboxBridge;
import com.hdf.cryptand.soc.riscv.Rv32Core;
import com.hdf.cryptand.soc.sandbox.SocSandbox;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * ===== 固件 shell 行编辑自测（离线跑**真固件**，2026-09-25）=====
 *
 * <p>为什么要有这个闸门：键盘接线的最后一跳（OC 信号 → 宿主 → UART → 固件行编辑）
 * **只有真人按键才会走**，而真机验证要开客户端（两分钟一次）。这里把整条链路搬进离线沙箱：
 * 装配一块与真机同构的板（RV32 + Timer@IRQ7 + UART + 寄存器桥），把 {@code cryptand-os.bin}
 * 直接当复位向量跑起来，再用一个**假组件总线把 {@code gpu.blit} 的字符阵列截下来当屏幕** ——
 * 于是"按键 → 行编辑 → 屏幕"这条链可以逐条断言，不需要 MC、不需要客户端。</p>
 *
 * <p>覆盖（对应用户 2026-09-25 的"标准键全支持 + 固件行编辑"）：</p>
 * <ol>
 *   <li>可打印字符进行、回车提交、退格删左边一个；</li>
 *   <li>← / → / Home / End 的行内移动与插入（行中间插入必须整行重绘）；</li>
 *   <li>Delete（{@code ESC [ 3 ~}）删**光标处**的字符；</li>
 *   <li>↑↓ 历史，含"↓ 回到翻历史前的草稿"；</li>
 *   <li><b>不认得的 ESC 序列整段丢弃</b>：按 F5/F1/F9 不该在命令行里留下 {@code 15~} 之类的残留；</li>
 *   <li>单独按 ESC ⇒ 超时复位，不吃掉后续输入。</li>
 * </ol>
 *
 * <p>跑法：{@code gradlew :common:runShellLineEditTest}</p>
 */
public final class ShellLineEditSelfTest {

    /** console.c 的 CON_COLS / CON_ROWS（cryptand-os 用默认 40×25） */
    private static final int COLS = 40;
    private static final int ROWS = 25;

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== Cryptand shell line-edit self test (real firmware in the offline sandbox) ===");
        final byte[] os = Programs.readById("cryptand-os");
        check("固件已编译（common 资源里读得到 cryptand-os.bin，大小 " + os.length + "）", os.length > 0);
        if (os.length == 0) {
            report();
            return;
        }

        final Rig rig = new Rig(os);
        // ⚠ 起系统的预算：固件 shell 任务每 20ms 轮询一次 UART（vTaskDelay(20)），
        //   FreeRTOS tick = 100Hz，而**一次 sb.tick() 的预算 200k 周期正好等于一个 tick**
        //   （configCPU_CLOCK_HZ = 20MHz）⇒ "跑 n 次 tick" 就是 n×10ms 虚拟时间。
        final boolean ready = rig.bootToShell(3000);
        check("固件在沙箱里起到 shell（屏幕上出现 shell 横幅）", ready);
        if (!ready) {
            rig.dump("未能起到 shell：屏幕快照");
            report();
            return;
        }

        rig.dump("起到 shell 之后（启动输出）");
        scenarioEchoAndBackspace(rig);
        rig.dump("场景 1 之后");
        scenarioCursorLeftInsert(rig);
        scenarioHomeEnd(rig);
        scenarioDelete(rig);
        scenarioUnknownEscDropped(rig);
        scenarioHistory(rig);
        rig.dump("场景 6 之后");
        scenarioLoneEscTimeout(rig);
        rig.dump("场景 7 之后");

        // ⚠ 这条是"固件真的还活着"的硬证据：心跳任务每 100ms 写一次心跳区（0x1000_4000）。
        //   漏声明这块 MMIO 区时它的表现恰恰就是"按键没反应"（固件已停进异常处理器）——
        //   所以心跳不涨必须报错，而不是让整轮测试显示成"输入没生效"（2026-09-25 踩过这个坑）。
        final long hb1 = rig.heartbeat();
        rig.run(20);                            // 200ms 虚拟时间 ⇒ 心跳任务必写一次
        final long hb2 = rig.heartbeat();
        check("固件仍活着：心跳区在增长（" + hb1 + " → " + hb2 + "，同时证明该 MMIO 区已映射）", hb2 > hb1);
        check("全程没有触发 CPU 故障（行编辑不该把固件跑崩）", !rig.cpu.isFaulted());
        check("全程真的画过屏（假总线上收到过 gpu.blit）", rig.blits > 0);
        report();
    }

    // ==================== 场景 ====================

    /** 可打印字符 ⇒ 行里出现；回车 ⇒ 提交并执行；退格 ⇒ 删左边一个 */
    private static void scenarioEchoAndBackspace(Rig rig) {
        System.out.println("  --- 场景 1：输入 / 回车 / 退格 ---");
        rig.clearScreen();
        rig.type("echo AAA\r");
        check("可打印字符进行、回车提交：屏幕上有回显行 '> echo AAA'", rig.waitForLinePrefix("> echo AAA", 60));
        check("命令真的被执行（echo 的输出行 'AAA'）", rig.waitForLine("AAA", 60));

        rig.clearScreen();
        rig.type("echo ABX");
        rig.key(8);                         // 退格
        rig.type("C\r");
        check("退格删掉左边一个字符（'> echo ABC'）", rig.waitForLinePrefix("> echo ABC", 60));
        check("退格后提交执行的是 ABC（不是 ABXC）", rig.waitForLine("ABC", 60));
    }

    /** ← 把光标移到行中间后插入 —— 行中间插入必须整行重绘，否则屏幕会错位 */
    private static void scenarioCursorLeftInsert(Rig rig) {
        System.out.println("  --- 场景 2：← 行中间插入 ---");
        rig.clearScreen();
        rig.type("echo ac");
        rig.esc('D');                       // ←
        rig.type("b\r");
        check("← 到 c 之前插入 b ⇒ 行是 '> echo abc'", rig.waitForLinePrefix("> echo abc", 60));
        check("执行结果确实是 abc（插入位置正确，不是追加到行尾）", rig.waitForLine("abc", 60));
    }

    /** Home / End：光标跳到行首 / 行尾 */
    private static void scenarioHomeEnd(Rig rig) {
        System.out.println("  --- 场景 3：Home / End ---");
        rig.clearScreen();
        rig.type("cho HOMEX");              // 故意少一个 e
        rig.esc('H');                       // Home
        rig.type("e");                      // 补在行首 ⇒ echo HOMEX
        rig.esc('F');                       // End（回到行尾）
        rig.type("\r");
        check("Home 把光标移到行首（补 e 后成为合法命令 'echo HOMEX'）", rig.waitForLinePrefix("> echo HOMEX", 60));
        check("Home 生效后命令执行成功（输出 HOMEX）", rig.waitForLine("HOMEX", 60));
    }

    /** Delete：删**光标处**的字符（与退格的区别就是它删右边那个） */
    private static void scenarioDelete(Rig rig) {
        System.out.println("  --- 场景 4：Delete（ESC [ 3 ~） ---");
        rig.clearScreen();
        rig.type("echo abXc");
        rig.esc('D');
        rig.esc('D');                       // ←← 光标停在 X 之前
        rig.escTilde(3);                    // Delete
        rig.type("\r");
        check("Delete 删掉光标处的 X ⇒ '> echo abc'", rig.waitForLinePrefix("> echo abc", 60));
        check("删除后执行 abc（删的是右边那个字符）", rig.waitForLine("abc", 60));
    }

    /** 不认得的 ESC 序列（F1..F12 / Ins / PgUp / PgDn / 多参数 CSI）必须**整段**丢弃 */
    private static void scenarioUnknownEscDropped(Rig rig) {
        System.out.println("  --- 场景 5：未识别的 ESC 序列整段丢弃 ---");
        rig.clearScreen();
        rig.type("echo z");
        rig.escTilde(15);                   // F5
        rig.ss3('P');                       // F1 = ESC O P
        rig.escTilde(20);                   // F9
        rig.escTilde(23);                   // F11（序列长度与前面的都不同）
        rig.type("z\r");
        check("未识别的序列整段丢弃：行里只剩 '> echo zz'", rig.waitForLinePrefix("> echo zz", 60));
        check("执行的输出是 zz（序列字节一个都没漏进命令行）", rig.waitForLine("zz", 60));
        check("屏幕上没有残留的序列参数（'15~' / '20~' / '23~'）",
                !rig.screenText().contains("15~") && !rig.screenText().contains("20~")
                        && !rig.screenText().contains("23~"));
    }

    /** ↑↓ 历史：↑ 取回上一条、连按 ↑ 到更早、↓ 回到翻历史前的草稿 */
    private static void scenarioHistory(Rig rig) {
        System.out.println("  --- 场景 6：↑↓ 历史 ---");
        rig.clearScreen();
        // ⚠ 历史的内容必须是**确定的**：无论之前跑过什么，最后两条一定是我们刚发的这两条。
        // ⚠ 取基线之前必须**等命令真的执行完**：命令是异步的（固件任务每 20ms 轮询一次），
        //   基线在"上一帧"上测的话，计数会在断言期间自然 +1 ⇒ 断言假通过（2026-09-25 踩过）。
        rig.type("echo H1\r");
        check("历史场景准备：H1 已执行落地", rig.waitForLine("H1", 60));
        rig.type("echo H2\r");
        check("历史场景准备：H2 已执行落地", rig.waitForLine("H2", 60));

        final int h2 = rig.countLines("H2");
        rig.esc('A');                       // ↑ ⇒ 'echo H2'（最新一条）
        rig.type("\r");                    // 直接回车 ⇒ 再执行一次
        check("↑ 取回最新一条命令（H2 的输出行 +1）", rig.waitForCount("H2", h2 + 1, 60));

        final int h1 = rig.countLines("H1");
        rig.esc('A');                       // ↑ ⇒ echo H2
        rig.esc('A');                       // ↑ ⇒ echo H1（更早）
        rig.type("\r");
        check("连按 ↑ 取到更早的一条（H1 的输出行 +1）", rig.waitForCount("H1", h1 + 1, 60));

        rig.clearScreen();
        rig.type("echo DRAFT");             // 正在编辑的新行（草稿）
        rig.esc('A');                       // ↑ 进入历史
        rig.esc('B');                       // ↓ 回到"新行"
        rig.type("\r");
        check("↓ 回到翻历史前的草稿（输出 DRAFT）", rig.waitForLine("DRAFT", 60));
    }

    /** 单独按 ESC（序列被截断）⇒ 超时复位，后续输入不能被吃掉 */
    private static void scenarioLoneEscTimeout(Rig rig) {
        System.out.println("  --- 场景 7：单独按 ESC 的超时复位 ---");
        rig.clearScreen();
        rig.key(27);                        // 只按 ESC，不给后续字节
        rig.run(8);                         // 80ms 虚拟时间 > 50ms 超时 ⇒ 固件必须复位 ESC 状态
        rig.type("echo ESC\r");
        check("单独按 ESC 后超时复位，后续输入照常（输出 ESC）", rig.waitForLine("ESC", 60));
    }

    // ==================== 沙箱装置 ====================

    /**
     * 离线装置：与真机同构的板 + **假组件总线把屏幕截下来**。
     *
     * <p>屏幕内容怎么来的：固件 {@code con_flush()} 把 40×25 个字符一次性交给 {@code gpu.blit}，
     * 核心把它组包成 {@link ComponentBus.Call}（方法 {@code "#5"}、缓冲区 = 那 1000 字节），
     * 假总线抄进 {@link #screen} 即可 —— 这与真机上"宿主收到 blit 参数再画到 OC 屏幕"是同一份数据。</p>
     */
    private static final class Rig {

        private final Rv32Core cpu;
        private final SocBoard board;
        /** 虚拟机建立的那块 UART 硬件（2026-09-27）：世界侧注入的字节交给它，它再变成芯片看到的状态位 */
        private final com.hdf.cryptand.soc.peripheral.UartHardware uart;
        private final OcArchitectureCore core;
        private final SocSandbox sb;
        private final char[][] screen = new char[ROWS][COLS];
        private int blits;

        Rig(byte[] os) {
            this.cpu = new Rv32Core(new Rv32Core.Config()
                    .resetVector((int) OcBoardLayout.SYS_LOAD_BASE).enableM(true));
            final RegBankDevice regBank =
                    new RegBankDevice(OcArchitectureCore.BRIDGE_REGISTERS, OcAbi.DEVICE_NAME);
            final TimerDevice timer = new TimerDevice("OC-TIMER");
            this.uart = new com.hdf.cryptand.soc.peripheral.UartHardware("OC-UART",
                    com.hdf.cryptand.soc.board.UartRegs.TX_BYTES);
            this.board = SocBoard.builder(cpu)
                    // 直接以系统镜像为复位向量（跳过 Boot，本闸门只测 shell 这一层）
                    .rom(OcBoardLayout.SYS_LOAD_BASE, os)
                    .ram(OcBoardLayout.RAM_BASE, 256 * 1024)
                    .deviceWithIrq(OcBoardLayout.REG_BASE, regBank, OcAbi.DEVICE_NAME)
                    .deviceWithIrqAt(OcBoardLayout.TIMER_BASE, timer, "TIMER", OcBoardLayout.IRQ_MTIP)
                    // ⚠ 没有 UART 设备：UART 现在是虚拟机建立的一块硬件（2026-09-27 定案），
                    //   世界侧输入经 uart.offerRx 交给它、每 tick 由 sync 把它变成芯片看到的状态位。
                    //   调试口仍在（引导服务标记 0x01 / UART 窗口未就绪报警 0xEE）。
                    .device(OcBoardLayout.DEBUG_BASE, new DebugConsoleDevice("DEBUG-CONSOLE"), "DEBUG-CONSOLE")
                    // ⚠ 心跳区必须声明（固件的第 5 个 MMIO 区）：漏了它，心跳任务一写就 store fault，
                    //   固件停进 FreeRTOS 默认异常处理器 ⇒ 表现成"按键没反应"（2026-09-25 踩过）
                    .ram(OcBoardLayout.HEARTBEAT_BASE, OcBoardLayout.HEARTBEAT_BYTES)
                    .build();
            for (int r = 0; r < ROWS; r++) {
                java.util.Arrays.fill(screen[r], ' ');
            }
            // UART 寄存器窗口的初始镜像（真机在芯片放行之前做同一件事，见 publishUartWindow）
            cpu.writeMemory(OcBoardLayout.UART_CACHE_BASE, uart.initialImage());
            this.core = new OcArchitectureCore(board, regBank, null);
            core.setBus(new ScreenBus());
            OcSandboxBridge.create(board, OcBoardLayout.REG_BASE, () -> 0L, core::pump);
            this.sb = new SocSandbox(board, new SocSandbox.Limits()
                    .maxMemoryBytes(2 * 1024 * 1024).maxDevices(16));
        }

        /** 一次 tick = 200k 周期 = 10ms 虚拟时间（见类注释）；照抄真机的三个驱动 */
        void run(int ticks) {
            for (int i = 0; i < ticks && !cpu.isFaulted(); i++) {
                // ⚠ 顺序有讲究：真机上芯片是**连续跑**的，而织物每 tick 同步一次。装置里芯片只在
                //   sb.tick() 里跑 ⇒ 必须**先把世界侧字节搬进窗口**再让芯片跑，否则每个注入字节都要
                //   多等一个 tick（实测：ESC 序列 4 个键被 50ms 超时截断，行编辑整段失效）。
                uart.sync(com.hdf.cryptand.soc.board.UartRegs.of(board), cpu.getInstructionsRetired());
                sb.tick();
                // 芯片这一轮写出的字节再搬给硬件（按波特率吐给世界侧出口）
                uart.sync(com.hdf.cryptand.soc.board.UartRegs.of(board), cpu.getInstructionsRetired());
                core.pump();
                core.pollMailbox();
                core.drainCalls(50);
            }
        }

        boolean bootToShell(int maxTicks) {
            for (int i = 0; i < maxTicks; i++) {
                run(1);
                // ⚠ 判据必须两条都满足（2026-09-25 踩过）：
                //   ① 提示符已经画出来 —— 只看横幅会在 con_line 的那次 flush 之后就返回；
                //   ② **心跳区已经有值** —— 横幅与提示符都是 main 在 vTaskStartScheduler 之前画的，
                //      此时固件根本还没在读 UART（shell 任务还没被调度）。判早了，注入的字节就会
                //      在 16 字节的 RX FIFO 里堆积、溢出被丢 ⇒ 断言看到的是残缺的命令行。
                if (hasLinePrefix("Cryptand OS shell") && hasLinePrefix("> ") && heartbeat() > 0) {
                    return true;
                }
            }
            return false;
        }

        /**
         * 读固件的心跳字（{@code HAL_HEARTBEAT_BASE} 的第一个字 = main.c 的 g_heartbeat）。
         *
         * <p>它一次证明两件事：**这块 MMIO 区确实被映射**（漏映射 ⇒ 心跳任务一写就 store fault，
         * 固件停进 FreeRTOS 的默认异常处理器）与**心跳任务在正常调度**（值在增长）。</p>
         */
        long heartbeat() {
            final byte[] b = board.readMemory(OcBoardLayout.HEARTBEAT_BASE, 4);
            return (b[0] & 0xFFL) | ((b[1] & 0xFFL) << 8) | ((b[2] & 0xFFL) << 16)
                    | ((b[3] & 0xFFL) << 24);
        }

        /** 注入一个原始字节（与宿主 OcKeyboardInput 发出来的一模一样；交给 UART 硬件） */
        void key(int b) {
            offered++;
            uart.offerRx(b & 0xFF);
            run(2);
        }

        /** 诊断：本装置一共交给 UART 硬件多少字节（与器件侧计数对账用） */
        private int offered;

        /** 注入一串字节（不等待）：分批发是为了让硬件每一批都有机会搬进窗口 */
        void type(String ascii) {
            final byte[] b = ascii.getBytes(StandardCharsets.US_ASCII);
            for (int i = 0; i < b.length; i += 8) {
                final int n = Math.min(8, b.length - i);
                offered += uart.offerRx(b, i, n);
                run(2);
            }
            run(6);                          // 让 shell 任务至少被调度几次并把行执行完
        }

        /** ANSI CSI 序列：{@code ESC [ <final>}（方向键 / Home / End） */
        void esc(char finalByte) {
            key(27);
            key('[');
            key(finalByte);
        }

        /** {@code ESC [ <n> ~}（Insert / Delete / PgUp / PgDn / F5..F12） */
        void escTilde(int n) {
            key(27);
            key('[');
            for (char c : Integer.toString(n).toCharArray()) {
                key(c);
            }
            key('~');
        }

        /** {@code ESC O <final>}（F1..F4） */
        void ss3(char finalByte) {
            key(27);
            key('O');
            key(finalByte);
        }

        /** 清屏：走固件自己的 {@code clear} 命令（不伪造屏幕，屏幕内容只可能来自固件） */
        void clearScreen() {
            type("clear\r");
            // 等屏幕真的只剩提示符那一行：clear 也是一条命令，执行完才轮到下一条输入。
            for (int i = 0; i < 40 && nonEmptyRows() > 1; i++) {
                run(1);
            }
        }

        String screenRow(int r) {
            final StringBuilder sb = new StringBuilder();
            for (int c = 0; c < COLS; c++) {
                sb.append(screen[r][c]);
            }
            int end = sb.length();
            while (end > 0 && sb.charAt(end - 1) == ' ') {
                end--;
            }
            return sb.substring(0, end);
        }

        String screenText() {
            final StringBuilder sb = new StringBuilder();
            for (int r = 0; r < ROWS; r++) {
                sb.append(screenRow(r)).append('\n');
            }
            return sb.toString();
        }

        /**
         * 轮询屏幕直到出现以 prefix 开头的行（最多 maxTicks 个 tick）。
         * 命令执行是**异步**的（固件任务每 20ms 轮询一次 UART），断言表达的是"最终会出现"。
         */
        boolean waitForLinePrefix(String prefix, int maxTicks) {
            for (int i = 0; i <= maxTicks; i++) {
                if (hasLinePrefix(prefix)) {
                    return true;
                }
                run(1);
            }
            return false;
        }

        /** 轮询屏幕直到出现恰好等于 text 的一行 */
        boolean waitForLine(String text, int maxTicks) {
            for (int i = 0; i <= maxTicks; i++) {
                if (hasLine(text)) {
                    return true;
                }
                run(1);
            }
            return false;
        }

        /** 轮询直到某行的计数达到 want（"命令又执行了一次"这类断言用） */
        boolean waitForCount(String text, int want, int maxTicks) {
            for (int i = 0; i <= maxTicks; i++) {
                if (countLines(text) >= want) {
                    return true;
                }
                run(1);
            }
            return false;
        }

        /** 非空行数（清屏完成的判据） */
        int nonEmptyRows() {
            int n = 0;
            for (int r = 0; r < ROWS; r++) {
                if (!screenRow(r).isEmpty()) {
                    n++;
                }
            }
            return n;
        }

        /** 屏幕上是否存在以 prefix 开头的行（提示符行尾会带光标标记 '_'，所以按前缀比较） */
        boolean hasLinePrefix(String prefix) {
            for (int r = 0; r < ROWS; r++) {
                if (screenRow(r).startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        /** 屏幕上是否存在**恰好等于** text 的一行（回显与命令输出因此可以分开断言） */
        boolean hasLine(String text) {
            for (int r = 0; r < ROWS; r++) {
                if (screenRow(r).equals(text)) {
                    return true;
                }
            }
            return false;
        }

        /** 恰好等于 text 的行数（"命令又执行了一次"这类断言靠它） */
        int countLines(String text) {
            int n = 0;
            for (int r = 0; r < ROWS; r++) {
                if (screenRow(r).equals(text)) {
                    n++;
                }
            }
            return n;
        }

        /** 当前命令行内容（光标 '_' 所在行；仅用于人看诊断，光标可能盖住一个字符） */
        String currentInput() {
            for (int r = 0; r < ROWS; r++) {
                final String row = screenRow(r);
                if (row.indexOf('_') >= 0) {
                    final int p = row.indexOf('>');
                    return (p >= 0 ? row.substring(p + 2) : row).replace("_", "<光标>");
                }
            }
            return "(没有找到光标行)";
        }

        /** 直接读 guest 里固件自己发布的读计数（诊断：字节到没到底、读没读重） */
        int guestRxPop() {
            final byte[] b = board.readMemory(OcBoardLayout.UART_CACHE_BASE
                    + com.hdf.cryptand.soc.board.UartRegs.OFF_RX_POP, 4);
            return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
        }

        void dump(String title) {
            final long[] regs = cpu.getRegisters();
            System.out.println("  === " + title + "（blits=" + blits + "，cpu.isFaulted=" + cpu.isFaulted()
                    + "，state=" + sb.state() + "，pc=0x" + Long.toHexString(cpu.getProgramCounter())
                    + "，UART 交给器件=" + offered + " 器件已投窗口=" + uart.rxTotal()
                    + " 固件已读=" + guestRxPop() + " 收窗=" + uart.rxWindowLevel()
                    + " 溢出=" + uart.ovrTotal() + " 状态位=0x" + Integer.toHexString(uart.lastSr())
                    + "）===");
            // ⚠ 卡在 FreeRTOS 的默认异常处理器（j self）时，t0/t1/t2 正是它刚读出的
            //   mcause / mepc / mstatus —— 这就是"固件到底在哪条指令炸了"的直接证据。
            System.out.println("  [diag] mcause=0x" + Long.toHexString(regs[5]) + " mepc=0x" + Long.toHexString(regs[6])
                    + " mstatus=0x" + Long.toHexString(regs[7]) + " ra=0x" + Long.toHexString(regs[1])
                    + " sp=0x" + Long.toHexString(regs[2]));
            for (int r = 0; r < ROWS; r++) {
                final String line = screenRow(r);
                if (!line.isEmpty()) {
                    System.out.println("  | " + line);
                }
            }
            System.out.println("  | currentInput = " + currentInput());
        }

        /**
         * 假组件总线：组件表第 0 项 = {@code gpu}（{@link OcAbi#HANDLE_GPU}）。
         *
         * <p>只兑现 gpu 的取分辨率 set/fill/blit；其余（fs_* 等）一律按"没有这个组件"失败 ——
         * 与真机没插盘时同构：固件拿到错误码后走它自己的 SKIP 分支，**绝不静默成功**。</p>
         */
        private final class ScreenBus implements ComponentBus {

            @Override
            public Result invoke(Call call) {
                if (!OcAbi.GPU_COMPONENT_NAME.equals(call.component())) {
                    return Result.error(OcAbi.ERR_NO_BUS, "no such component: " + call.component());
                }
                final int id = methodId(call.method());
                switch (id) {
                    case OcAbi.GPU_METHOD_BLIT -> {
                        return Result.ok(blit(call));
                    }
                    case OcAbi.GPU_METHOD_GET_RES -> {
                        return Result.ok(COLS, ROWS);
                    }
                    case OcAbi.GPU_METHOD_SET, OcAbi.GPU_METHOD_FILL,
                         OcAbi.GPU_METHOD_SET_FG, OcAbi.GPU_METHOD_SET_BG -> {
                        return Result.ok(1);        // 本闸门只关心整屏 blit
                    }
                    default -> {
                        return Result.error(OcAbi.ERR_NO_BUS, "unsupported gpu method: " + id);
                    }
                }
            }

            @Override
            public List<Entry> components() {
                return List.of(new Entry("screen-0", OcAbi.GPU_COMPONENT_NAME));
            }

            /** {@code blit(x, y, w, h) + 缓冲区 = w×h 个字符} ⇒ 抄进屏幕矩阵 */
            private int blit(Call call) {
                if (call.buffer() == null || call.args().size() < 4) {
                    return 0;
                }
                final int x = num(call, 0);
                final int y = num(call, 1);
                final int w = num(call, 2);
                final int h = num(call, 3);
                if (w <= 0 || h <= 0 || call.buffer().length < w * h) {
                    return 0;
                }
                for (int r = 0; r < h; r++) {
                    for (int c = 0; c < w; c++) {
                        // OC 的坐标是 1-based（con_init(1,1)）
                        final int sy = y - 1 + r;
                        final int sx = x - 1 + c;
                        if (sy >= 0 && sy < ROWS && sx >= 0 && sx < COLS) {
                            screen[sy][sx] = (char) (call.buffer()[r * w + c] & 0xFF);
                        }
                    }
                }
                blits++;
                return 1;
            }

            private int num(Call call, int index) {
                final Object v = call.args().get(index);
                return v instanceof Number n ? n.intValue() : 0;
            }

            /** 核心给的是 {@code "#<方法id>"}（平台层才翻成方法名） */
            private int methodId(String method) {
                try {
                    return method != null && method.startsWith("#")
                            ? Integer.parseInt(method.substring(1)) : -1;
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
    }

    // ==================== 断言与报告 ====================

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static void report() {
        System.out.println("[SHELL] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }
}
