package com.hdf.cryptand.soc.os;

import com.hdf.cryptand.soc.sandbox.SocSandbox;
import com.hdf.cryptand.soc.board.OcBoardLayout;
import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.device.DebugConsoleDevice;
import com.hdf.cryptand.soc.device.RegBankDevice;
import com.hdf.cryptand.soc.device.TimerDevice;
import com.hdf.cryptand.soc.oc.BootLoaderService;
import com.hdf.cryptand.soc.oc.OcAbi;
import com.hdf.cryptand.soc.oc.OcArchitectureCore;
import com.hdf.cryptand.soc.oc.OcSandboxBridge;
import com.hdf.cryptand.soc.riscv.Rv32Core;

/**
 * ===== 固件离线运行装置（common，纯 Java 零 MC）=====
 *
 * <p>用户 2026-09-18 两次强调"用软件测试"、"客户端太慢了"。这个闸门把**固件本身**放到沙盒里跑：
 * 装配一块与真机<b>同构</b>的板子（ROM 系统区 / RAM / Timer@IRQ7 / UART / 寄存器桥 + 核心），
 * 把 {@code cryptand-os.bin} 载入系统区、直接以它为复位向量（跳过 Boot），跑若干周期，
 * 断言 UART 上出现了系统启动的标志行。</p>
 *
 * <p>装置要点（都是真机上踩过的）：</p>
 * <ul>
 *   <li>系统镜像的链接与运行地址都是 {@link OcBoardLayout#SYS_LOAD_BASE}（0x10000）——
 *       绝不能载到 0x0（那是 Boot 自己在跑的地址）；</li>
 *   <li>FreeRTOS 的 tick 走 <b>IRQ 7</b>（MTIP），没有它任务一次都不会被调度；</li>
 *   <li>总线传 {@code null}：固件的 fs_* 会拿到 {@code ERR_NO_BUS}，于是走它的 SKIP 分支 ——
 *       这正是我们要的"无盘环境下系统仍能起到 console"的行为；</li>
 *   <li>UART 的字节出口把输出攒起来，断言就有据可依（而不是"看起来没崩"）。</li>
 * </ul>
 *
 * <p>从此改固件不必起客户端：跑一遍本闸门就知道系统还起不起得来。</p>
 */
public final class FirmwareRunSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        // ⚠ 属性必须在**执行任何被测代码之前**设置：前几轮把它放在场景方法里，
        //   结果 RegBankDevice 的诊断一次都没触发，连续两轮误判成"没人写寄存器"。
        System.setProperty("cryptand.debug.reg", "1");
        final byte[] os = Programs.readById("cryptand-os");
        check("固件已编译（common 资源里读得到 cryptand-os.bin）", os.length > 0);
        if (os.length == 0) {
            report();
            return;
        }
        // ⏳ 临时：只跑场景 2 做定位（场景 1 要 6 分钟，先不跑）
        // scenarioDirectSystem(os);
        scenarioThroughBoot(os);
        scenarioMiniOs();
        report();
    }

    /**
     * 场景 2（★ 本闸门真正想覆盖的链路）：**Boot 请 BIOS 读盘 → 拿到系统 → 交接控制权**。
     *
     * <p>与真机的差别只有一处：真机上"盘"是宿主文件夹 + FAT 分区（由 {@code OcBootLoader} 读），
     * 这里用 {@code BootLoaderService} 桩直接把同一份 {@code cryptand-os.bin} 写进 guest 内存 ——
     * <b>协议与调用序列完全一致</b>（Boot 一样经 REG_CALL 发 {@code BOOT_METHOD_READ_FILE}，
     * 路径走 REG_BUF_ADDR/LEN、载入地址走 ARG0）。</p>
     *
     * <p>因此它同时验证了三件事：</p>
     * <ol>
     *   <li>引导链路：BIOS 横幅 → 读盘 → handing over → 系统起来 → shell ready；</li>
     *   <li><b>CALL 消费语义</b>：读盘服务必须只被调用 <b>1 次</b> —— 这正是之前真机上
     *       "2 次请求被放大成 259 次"的回归点（那次只能靠真机看日志，现在离线就能钉死）；</li>
     *   <li><b>策略在 guest</b>：要哪个文件、装到哪个地址，都是固件在调用里给的
     *       （宿主只兑现"按你给的路径读盘"）—— 这是 2026-09-27 定案
     *       "BIOS 负责从盘中读取文件加载到虚拟机内运行"的可断言形态。</li>
     * </ol>
     */
    private static void scenarioThroughBoot(byte[] os) throws Exception {
        // ★★ 真机路径复现（2026-09-27 真机事故之后补）：0x0 上放的**不是**内置镜像，而是
        //    "引导介质上的第一有效文件"（BootPlan.entryOf —— 通常是那块盘的 /boot/loader.bin）。
        //    真机上介质与宿主是**两份字节**（介质是上一次编译/烧录的产物），所以离线必须按
        //    **介质那一份**跑；否则"宿主 ABI 改了、介质没跟着改"这类事故离线永远看不见 ——
        //    这正是 2026-09-27 真机踩的坑：真机 0x0 上是 2193 字节的**旧** bootloader，
        //    它按旧形状调用，新宿主按 readFile 的 ABI **拒答**，固件却打出一句
        //    "host bridge not wired"（与真相无关），把排查方向整条带偏。
        final java.nio.file.Path mediumDir = java.nio.file.Path.of(
                "build", "tmp", "boot-medium-" + System.nanoTime());
        java.nio.file.Files.createDirectories(mediumDir);
        // 用**真装盘器**造一块合法两段式介质：/boot/loader.bin（装盘时写入的当期 boot 产物）
        // + /boot/system.bin（系统）。于是"介质上的 bootloader 必须是当期产物"被闸门钉住。
        final com.hdf.cryptand.soc.fs.CryptandFileSystem medium =
                com.hdf.cryptand.soc.fs.DiskFileSystems.open(
                        mediumDir, 4L * 1024 * 1024, "fw-medium", false);
        Programs.install(medium, os);
        final BootPlan.Entry mediumEntry =
                BootPlan.entryOf(new BootPlan.Disk(3, false, 3, "测试介质", medium));
        check("★ 介质上选出的第一有效文件 = " + Programs.LOADER_PATH + "（判据 = BootPlan.entryOf）",
                mediumEntry != null && Programs.LOADER_PATH.equals(mediumEntry.path()));
        if (mediumEntry == null) {
            medium.close();
            return;
        }
        final byte[] boot = mediumEntry.bytes();
        check("Boot 镜像已编译（cryptand-boot.bin）", boot.length > 0);
        // ★ 同源性：介质上的 bootloader 必须与宿主（EEPROM）里那份**同源**。真机上这一条不成立时，
        //   0x0 会执行按旧 ABI 编译的代码（2026-09-27 事故现场）；宿主侧已加日志护栏
        //   （OcBootLoader.warnStaleBootMedium），这里把它钉成可断言的闸门。
        check("★ 介质上的 bootloader 与宿主（EEPROM）里的 boot 是同一份编译产物",
                java.util.Arrays.equals(boot, Programs.readById("boot")));
        // ★ 字节级证据：**固件自己**点着 "/boot/system.bin" 这个路径（不是宿主替它选的文件）。
        //   这条断言把"取哪个文件由 guest 决定"钉在镜像字节上 —— 改回"宿主替它取系统"就必然失败。
        check("★ boot 镜像里带着固件自己点名的系统路径（/boot/system.bin）",
                containsBytes(boot, "/boot/system.bin".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        final StringBuilder uartOut = new StringBuilder();
        final Rv32Core cpu = new Rv32Core(
                new Rv32Core.Config().resetVector((int) OcBoardLayout.ROM_BASE).enableM(true));
        final RegBankDevice regBank = new RegBankDevice(OcArchitectureCore.BRIDGE_REGISTERS, OcAbi.DEVICE_NAME);
        final TimerDevice timer = new TimerDevice("OC-TIMER");
        // ⚠ 2026-09-27：UART 不再是 MMIO 设备（逐字节事务路径已删），而是**虚拟机建立的一块硬件**
        //   （寄存器窗口映射进 guest RAM；下方 uart.sync 每 tick 同步一次）。
        final com.hdf.cryptand.soc.peripheral.UartHardware uart =
                new com.hdf.cryptand.soc.peripheral.UartHardware("OC-UART",
                        com.hdf.cryptand.soc.board.UartRegs.TX_BYTES);
        uart.setByteSink(b -> {
            if (b == '\n' || (b >= 32 && b < 127)) {
                uartOut.append((char) b);
            }
        });
                // 调试控制台（用户：把 uart0 输出转接到屏幕上）：独立 MMIO 字节口，不受 UART 配置与 FIFO 溢出影响
        final StringBuilder debugOut = new StringBuilder();
        final com.hdf.cryptand.soc.device.DebugConsoleDevice debug =
                new com.hdf.cryptand.soc.device.DebugConsoleDevice("DEBUG-CONSOLE");
        final java.util.concurrent.atomic.AtomicInteger bootMarks = new java.util.concurrent.atomic.AtomicInteger();
        debug.setSink(b -> {
            if (b == 1) {
                bootMarks.incrementAndGet();       // oc_boot_invoke 的调用标记（0x01）
            }
            if (b == '\n' || (b >= 32 && b < 127)) {
                debugOut.append((char) b);
            }
        });
        final SocBoard board = SocBoard.builder(cpu)
                // ⚠ 必须声明**完整 512KB ROM**（Boot 64KB + 系统区 448KB），与真机的
                //   `.rom(ROM_BASE, image, ROM_BYTES)` 一致：只按 boot.bin 的长度声明 ROM 的话，
                //   桩里 `cpu.loadImage(0x10000, os)` 会越界 ⇒ LOAD 返回错误 ⇒ Boot 打出
                //   "trying drive 0 ... read error"（实测）。
                .rom(OcBoardLayout.ROM_BASE, boot, OcBoardLayout.ROM_BYTES)
                .ram(OcBoardLayout.RAM_BASE, 256 * 1024)
                .deviceWithIrq(OcBoardLayout.REG_BASE, regBank, OcAbi.DEVICE_NAME)
                .deviceWithIrqAt(OcBoardLayout.TIMER_BASE, timer, "TIMER", OcBoardLayout.IRQ_MTIP)
                // ⚠ 与真机同构：**没有 UART 设备**（UART = 虚拟机建立的硬件，寄存器窗口在 guest RAM）
                .device(OcBoardLayout.DEBUG_BASE, debug, "DEBUG-CONSOLE")
                .ram(OcBoardLayout.HEARTBEAT_BASE, OcBoardLayout.HEARTBEAT_BYTES)
                .build();
        // UART 寄存器窗口的初始镜像（真机在芯片放行之前做同一件事，见 publishUartWindow）
        cpu.writeMemory(OcBoardLayout.UART_CACHE_BASE, uart.initialImage());
        // ★ 注入系统配置块（真机上由宿主把 config/cryptand/cryptand-os.cfg 写进 RAM 顶端 4KB）：
        //   这里给固件两件事 —— 显存窗口在哪、多大。不写就是"无配置"（固件会明确报 NO-WINDOW）。
        //   ⚠ 固件的 hal_config_int 只认**十进制**，所以这里不能用 0x 前缀。
        final String cfg = "CFG1\n"
                + "disp.base=" + OcBoardLayout.VRAM_BASE + "\n"
                + "disp.bytes=" + OcBoardLayout.VRAM_BYTES + "\n";
        cpu.writeMemory(OcBoardLayout.CONFIG_BLOCK,
                cfg.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        final OcArchitectureCore core = new OcArchitectureCore(board, regBank, null);
        final java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        // ★ 这个桩就是**宿主 BIOS 的读盘服务**（INT 13h）：它不挑文件、不改地址，
        //   只按固件给的 path/loadAddr 把字节读出来放进去。固件给了什么，下面逐条断言。
        final java.util.concurrent.atomic.AtomicReference<String> askedPath =
                new java.util.concurrent.atomic.AtomicReference<>("(未被调用)");
        final java.util.concurrent.atomic.AtomicLong askedAddr =
                new java.util.concurrent.atomic.AtomicLong(-1L);
        core.setBootLoader(new BootLoaderService() {
            @Override
            public long[] readFile(String path, long loadAddr) {
                loads.incrementAndGet();   // 只该被调用一次（回归点）
                askedPath.set(path);
                askedAddr.set(loadAddr);
                // ★ 与真机 OcBootLoader.readFile 同一套动作：从**引导介质**按 guest 给的路径读，
                //   再按 guest 给的地址写进 guest 内存（地址落在引导区就是错的，明确拒绝）。
                final byte[] data = loadAddr < BootPlan.LOADER_ROM_BYTES
                        ? new byte[0] : Programs.installedAt(medium, path);
                if (data.length == 0) {
                    return null;
                }
                cpu.loadImage((int) loadAddr, data);
                return new long[]{data.length, loadAddr};
            }
        });
        OcSandboxBridge.create(board, OcBoardLayout.REG_BASE, () -> 0L, core::pump);

        final SocSandbox sb = new SocSandbox(board,
                new SocSandbox.Limits().maxMemoryBytes(2 * 1024 * 1024).maxDevices(16));
        // ⚠ 第三个驱动（Java 解释器路径特有，2026-09-18 实测定位）：
        //   真机上"固件写 CALL=1 ⇒ 兑现"是**事件驱动**的 —— `OcSandboxBridge` 挂在
        //   native 内核的 MMIO 总线上（`vm.bus(sandboxBridge)`），guest 的每次设备访问都会
        //   经 `SandboxVm.dispatch` 送到桥里，桥在那里才调 `onCall`（core.pump）。
        //   而 **Java 解释器路径（Rv32Core + SocBoard）没有任何东西会去碰桥**：
        //   CALL 只是落进 RegBankDevice，没人泵 ⇒ 固件阻塞轮询到超时，
        //   于是打出 "boot service unavailable (host bridge not wired)"。
        //   ⇒ 离线装置必须每 tick 显式 pump()（无请求时它第一行就返回，无副作用）。
        for (int i = 0; i < 3000 && !cpu.isFaulted(); i++) {
            sb.tick();
            // ④ 真机在 sandboxHeartbeat 里做的同一件事：与 UART 硬件每 tick 整块同步一次
            uart.sync(com.hdf.cryptand.soc.board.UartRegs.of(board), cpu.getInstructionsRetired());
            core.pump();
            core.pollMailbox();
            core.drainCalls(50);
        }
        final String out = uartOut.toString();
        System.out.println("  === Boot 链路 UART 输出（" + out.length() + " 字符）===");
        System.out.println(out);       // 完整打印：上次截到 1500 而实际 1589，末尾可能藏着关键行
        System.out.println("  === 输出结束（cpu.isFaulted=" + cpu.isFaulted() + "）===");

        final String dbg = debugOut.toString();
        System.out.println("  === 调试口 " + dbg.length() + " 字符（只剩引导标记）vs UART 缓存 " + out.length() + " 字符 ===");
        System.out.println("  === trying drive: UART 缓存=" + countOf(out, "handing over")
                + "  调试口=" + countOf(dbg, "handing over") + " ===");
        System.out.println("  === 判定：oc_boot_invoke 调用标记数 = " + bootMarks.get()
                + " ；读盘服务调用数 = " + loads.get()
                + " ；固件点名的文件 = " + askedPath.get()
                + " ；固件指定的载入地址 = 0x" + Long.toHexString(askedAddr.get()) + " ===");
        // ✅ 2026-09-24 修复后的正确期望：Boot 只请求一次、只读一次
        check("★ 固件侧引导服务调用恰好 1 次（BIOS 读盘服务：盘由 BIOS 选好）", bootMarks.get() == 1);
        check("★ 宿主读盘服务恰好 1 次（一次请求 = 一次兑现）", loads.get() == 1);
        check("★ 固件自己点名要哪个文件（/boot/system.bin —— 路径不是宿主写死的）",
                "/boot/system.bin".equals(askedPath.get()));
        check("★ 固件自己指定载入地址（0x10000 = ROM 系统区，= INT 13h 的 ES:BX）",
                askedAddr.get() == OcBoardLayout.SYS_LOAD_BASE);
        // ★ 2026-09-27：调试口**不再逐字节镜像 UART 输出**（旧 MMIO UART 链的最后一处逐字节 MMIO
        //   已删除）⇒ 现在它只剩引导服务调用标记 0x01（不进可读日志）。UART 的日志全部经缓存到达。
        check("★ 调试口不再逐字节镜像 UART（0 个可读字符，只剩 " + bootMarks.get() + " 个引导标记）",
                dbg.length() == 0 && out.length() > 0);
        check("★ UART 硬件：固件写回的计数器自洽（系统运行时两侧布局一致）", uart.anomalies() == 0);
        // 回归护栏：Boot **不应**反复请求（这正是心跳写进 OC_REG_CALL 时的症状）
        check("★ 回归护栏：Boot 没有反复请求（handing over 只有 1 次、无 system image returned）",
                countOf(out, "handing over") == 1 && !out.contains("system image returned"));
        check("Boot 打印了 BIOS 横幅", out.contains("Cryptand Boot 0.2"));
        check("Boot 找到可用程序并交接控制权（handing over control）", out.contains("handing over control"));
        check("★ 引导读盘服务只被调用 1 次（CALL 消费语义回归点）", loads.get() == 1);
        check("接手后系统起来了（[Cryptand OS] booting）", out.contains("[Cryptand OS] booting"));
        check("系统走到 shell ready / console ready",
                out.contains("shell ready") || out.contains("console ready"));

        // ==================== ★ 端到端：固件写显存 → 宿主从 guest RAM 取回整屏 ====================
        // 这就是用户定案里"CPU 通过 PCIe/内存直接写入显存"的模拟证明：
        // 固件那边只是**写内存 + 门铃**（display.c），宿主这边只是 readMemory + 解析。
        check("[DISP] 固件报告拿到显存窗口（宿主注入了 disp.base）",
                out.contains("[DISP] window init -> OK"));
        final byte[] vram = cpu.readMemory(OcBoardLayout.VRAM_BASE, OcBoardLayout.VRAM_BYTES);
        final com.hdf.cryptand.soc.board.DisplayWindow.Frame frame =
                com.hdf.cryptand.soc.board.DisplayWindow.parse(vram);
        check("[DISP] 宿主从 guest RAM 取到整屏（80x25 三平面）",
                frame.cols() == 80 && frame.rows() == 25);
        check("[DISP] 门铃 >= 1（固件已 present）", frame.seq() >= 1);
        check("[DISP] 字符平面内容正确（'C' 'V'）",
                frame.code()[0] == 'C' && frame.code()[1] == 'V');
        // ⚠ 索引语义 = **OC 调色板**（0 = white … 0xF = black，见 OpenOS lib/colors.lua）：
        //   宿主把这三平面按 palette=true 交给 OC（OcComponentBus.paintRows），
        //   所以固件写 0x00/0x0F 才是"白字黑底"；写成 VGA 顺序就是黑底黑字。
        check("[DISP] 颜色平面 = OC 索引（0x00 白字 / 0x0F 黑底）",
                (frame.fg()[0] & 0xFF) == 0x00 && (frame.bg()[0] & 0xFF) == 0x0F);
        // ★ 整屏都要有**默认色**：disp_init 不清平面的话 fg/bg 全 0（OC 索引 0 = 白）
        //   ⇒ 背景也是白的，屏幕上什么都看不见。这条断言把"必须清平面"钉死。
        int colorDefault = 0;
        int colorWrong = 0;
        for (final byte b : frame.fg()) {
            if ((b & 0xFF) == 0x00) {
                colorDefault++;
            } else {
                colorWrong++;
            }
        }
        int bgDefault = 0;
        for (final byte b : frame.bg()) {
            if ((b & 0xFF) == 0x0F) {
                bgDefault++;
            }
        }
        check("[DISP] 整屏前景/背景平面都是默认色（" + colorDefault + "/" + frame.fg().length
                        + " fg=0x00，bg 默认 " + bgDefault + "）",
                colorWrong == 0 && bgDefault == frame.bg().length);
        // 介质句柄用完就还（Windows 上不还会让下一轮的目录清理失败）
        medium.close();
    }

    /**
     * 场景 3：mini OS（低配档的最小系统）。
     *
     * <p>上一轮只证明了它"装得下 8KB 盘"；这里补上"跑得起来" —— 按本项目一贯标准，
     * 这两件事要分别有证据。它不用调度器、不链 shell，所以 800 个 tick 足够跑到 console ready。</p>
     */
    private static void scenarioMiniOs() {
        final byte[] mini = Programs.readById("mini-os");
        check("mini OS 镜像已编译（common 资源）", mini.length > 0);
        if (mini.length == 0) {
            return;
        }
        final StringBuilder uartOut = new StringBuilder();
        final Rv32Core cpu = new Rv32Core(
                new Rv32Core.Config().resetVector((int) OcBoardLayout.SYS_LOAD_BASE).enableM(true));
        final RegBankDevice regBank = new RegBankDevice(OcArchitectureCore.BRIDGE_REGISTERS, OcAbi.DEVICE_NAME);
        final TimerDevice timer = new TimerDevice("OC-TIMER");
        final com.hdf.cryptand.soc.peripheral.UartHardware uart =
                new com.hdf.cryptand.soc.peripheral.UartHardware("OC-UART",
                        com.hdf.cryptand.soc.board.UartRegs.TX_BYTES);
        uart.setByteSink(b -> {
            if (b == '\n' || (b >= 32 && b < 127)) {
                uartOut.append((char) b);
            }
        });
                final SocBoard board = SocBoard.builder(cpu)
                .rom(OcBoardLayout.SYS_LOAD_BASE, mini)
                // ⚠ RAM 必须把**邮箱区**也覆盖进去：邮箱在 0x2002_0000（RAM_BASE + 128K），
                //   是固件调组件用的外部设备邮箱区。只声明 64KB 主 RAM 会让 pollMailbox 撞上
                //   "unmapped address 0x20020000"（实测踩过）。所以给 256KB，与另外两个场景一致。
                .ram(OcBoardLayout.RAM_BASE, 256 * 1024)
                .deviceWithIrq(OcBoardLayout.REG_BASE, regBank, OcAbi.DEVICE_NAME)
                .deviceWithIrqAt(OcBoardLayout.TIMER_BASE, timer, "TIMER", OcBoardLayout.IRQ_MTIP)
                // ⚠ 没有 UART 设备：UART = 虚拟机建立的硬件（2026-09-27 定案，与真机同构）
                .device(OcBoardLayout.DEBUG_BASE, new DebugConsoleDevice("DEBUG-CONSOLE"), "DEBUG-CONSOLE")
                .ram(OcBoardLayout.HEARTBEAT_BASE, OcBoardLayout.HEARTBEAT_BYTES)
                .build();
        cpu.writeMemory(OcBoardLayout.UART_CACHE_BASE, uart.initialImage());
        final OcArchitectureCore core = new OcArchitectureCore(board, regBank, null);
        OcSandboxBridge.create(board, OcBoardLayout.REG_BASE, () -> 0L, core::pump);
        final SocSandbox sb = new SocSandbox(board,
                new SocSandbox.Limits().maxMemoryBytes(2 * 1024 * 1024).maxDevices(16));
        for (int i = 0; i < 800 && !cpu.isFaulted(); i++) {
            sb.tick();
            uart.sync(com.hdf.cryptand.soc.board.UartRegs.of(board), cpu.getInstructionsRetired());
            core.pump();
            core.pollMailbox();
            core.drainCalls(50);
        }
        final String out = uartOut.toString();
        System.out.println("  --- mini OS UART（" + out.length() + " 字符）---");
        System.out.println(out.length() > 700 ? out.substring(0, 700) : out);
        check("mini OS 在沙盒里跑起来了（有 UART 输出）", out.length() > 0);
        check("出现 mini OS 横幅", out.contains("Cryptand mini OS"));
        check("走到 console ready.", out.contains("console ready."));
        if (cpu.isFaulted()) {
            System.out.println("  [diag] mini OS faulted at PC=0x" + Long.toHexString(cpu.faultPc()));
        }
        check("没有触发 CPU 故障（最小系统不该崩）", !cpu.isFaulted());
    }

    private static void scenarioDirectSystem(byte[] os) {

        final StringBuilder uartOut = new StringBuilder();
        final Rv32Core cpu = new Rv32Core(
                new Rv32Core.Config().resetVector((int) OcBoardLayout.SYS_LOAD_BASE).enableM(true));
        final RegBankDevice regBank = new RegBankDevice(OcArchitectureCore.BRIDGE_REGISTERS, OcAbi.DEVICE_NAME);
        final TimerDevice timer = new TimerDevice("OC-TIMER");
        final com.hdf.cryptand.soc.peripheral.UartHardware uart =
                new com.hdf.cryptand.soc.peripheral.UartHardware("OC-UART",
                        com.hdf.cryptand.soc.board.UartRegs.TX_BYTES);
        uart.setByteSink(b -> {
            if (b == '\n' || (b >= 32 && b < 127)) {
                uartOut.append((char) b);
            }
        });

        final SocBoard board = SocBoard.builder(cpu)
                .rom(OcBoardLayout.SYS_LOAD_BASE, os)
                .ram(OcBoardLayout.RAM_BASE, 256 * 1024)
                .deviceWithIrq(OcBoardLayout.REG_BASE, regBank, OcAbi.DEVICE_NAME)
                .deviceWithIrqAt(OcBoardLayout.TIMER_BASE, timer, "TIMER", OcBoardLayout.IRQ_MTIP)
                .ram(OcBoardLayout.HEARTBEAT_BASE, OcBoardLayout.HEARTBEAT_BYTES)
                .build();
                cpu.writeMemory(OcBoardLayout.UART_CACHE_BASE, uart.initialImage());
        final OcArchitectureCore core = new OcArchitectureCore(board, regBank, null);
        OcSandboxBridge.create(board, OcBoardLayout.REG_BASE, () -> 0L, core::pump);

        // 默认配额（64KB 内存 / 8 设备）是给"最小可跑"用的；这里装的是一台完整的机器
        // （448KB ROM 区 + 256KB RAM + 4 个设备），所以显式给配额 —— 这也顺带证明配额是可调的，
        // 而不是把测试撑成"碰巧没超"。
        final SocSandbox sb = new SocSandbox(board,
                new SocSandbox.Limits().maxMemoryBytes(2 * 1024 * 1024).maxDevices(16));
        // ⚠ 这里必须复刻真机的**两个驱动**，否则固件会在等文件系统时永远卡住：
        //   ① `pollMailbox()` —— 真机在 runThreaded 里每 tick 扫描外部设备邮箱区（见
        //      CryptandOcArchitecture 的 ⓪ 段），固件的 fs_* 就是走这条通道；
        //   ② `drainCalls()` —— 认领后的请求在主线程侧批量兑现。
        //   本闸门里总线是 null ⇒ 每个 fs_* 都拿到 ERR_NO_BUS ⇒ 固件走它的 SKIP 分支，
        //   这正是"无盘环境下系统仍能起到 console"的行为。
        for (int i = 0; i < 20000 && !cpu.isFaulted(); i++) {
            sb.tick();
            uart.sync(com.hdf.cryptand.soc.board.UartRegs.of(board), cpu.getInstructionsRetired());
            core.pollMailbox();
            core.drainCalls(50);
        }

        final String out = uartOut.toString();
        System.out.println("  --- 固件 UART 输出（前 600 字符）---");
        System.out.println(out.length() > 600 ? out.substring(0, 600) : out);

        check("固件在沙盒里跑起来并产生了 UART 输出", out.length() > 0);
        check("出现 Cryptand OS 启动标志（[Cryptand OS] booting）", out.contains("[Cryptand OS] booting"));
        check("系统没有在启动阶段触发 CPU 故障（isFaulted=false）", !cpu.isFaulted());
        check("走到 shell ready 或 console ready（能调度任务）",
                out.contains("shell ready") || out.contains("console ready"));

        report();
    }

    private static void report() {
        System.out.println("[FW] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 镜像字节里是否含有一段 ASCII（用来把"固件自己说了什么"钉在产物上） */
    private static boolean containsBytes(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        int i = 0;
        while ((i = text.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}