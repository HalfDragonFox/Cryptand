/**
 * ===== C++ 内核 vs Java 内核 —— 一致性自测（2026-09-17）=====
 *
 * <p>用户决定"一步到 C++ 内核"后的**回归闸门**：同一段固件、同样的板级装配，
 * 分别跑纯 Java 的 {@code Rv32Core} 与 native 的 {@link NativeRv32Core}，
 * 逐项比对执行指令数 / PC / 寄存器 / 设备寄存器 —— 任何不一致都说明两版语义有偏差。</p>
 *
 * <p>运行：{@code gradlew :common:runNativeKernelTest}
 * （DLL 缺失时打印 SKIP 并正常退出，不阻塞无 native 的环境）。</p>
 */
package com.hdf.cryptand.soc;

import com.hdf.cryptand.soc.api.CpuCore;
import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.device.RegBankDevice;
import com.hdf.cryptand.soc.nativebridge.NativeRv32;
import com.hdf.cryptand.soc.nativebridge.NativeRv32Core;
import com.hdf.cryptand.soc.riscv.Rv32Asm;
import com.hdf.cryptand.soc.riscv.Rv32Core;

import java.nio.file.Files;
import java.nio.file.Path;

public final class NativeKernelSelfTest {

    private static final long ROM_BASE = 0x0000_0000L;
    private static final long RAM_BASE = 0x2000_0000L;
    private static final long REG_BASE = 0x1000_0000L;
    private static final int RAM_SIZE = 8 * 1024;
    private static final int STEPS = 4_000;

    private static int passed;
    private static int failed;
    private static final StringBuilder log = new StringBuilder();

    public static void main(String[] args) {
        System.out.println("=== Cryptand native kernel self test (C++ RV32 vs Java RV32) ===");

        locateLibrary();
        if (!NativeRv32.available()) {
            System.out.println("[SKIP] native 内核不可用：" + NativeRv32.loadError());
            System.out.println("       （先跑 excode/cryptand-rv32/build.ps1，或设 -Dcryptand.rv32.lib=<dll>）");
            System.exit(0);
        }
        System.out.println("[OK] native 内核已加载");

        // 固件：counter 自增，写 **RAM**（普通内存）与设备寄存器 REG[0]（MMIO），然后死循环。
        // ⚠ 写 RAM 是"内存归内核"的回归点：native 内核自持 RAM（C++ 缓冲）⇒ 宿主读 guest 内存
        //   必须经 CpuCore.readMemory；以前宿主直接读 SocBoard 的 MemoryMap，在 native 下读到的
        //   是从来没被写过的影子 RamDevice（恒 0）—— 组件桥取 gpu.blit 字符阵列全 0，屏幕全黑。
        final Rv32Asm asm = new Rv32Asm();
        asm.li(1, 0);                       // x1 = counter
        asm.li(3, (int) RAM_BASE);          // x3 = RAM 基址
        final int loop = asm.index();
        asm.addi(1, 1, 1);                  // x1++
        asm.sw(1, 0, 3);                    // RAM[0] = x1   （普通内存写：宿主侧要读得到）
        asm.li(2, (int) REG_BASE);          // x2 = 设备基址
        asm.sw(1, 0, 2);                    // REG[0] = x1   （MMIO 写）
        asm.jal(0, (loop - asm.index()) * 4);
        final byte[] firmware = asm.toBytes();
        System.out.println("固件 " + firmware.length + " 字节，跑 " + STEPS + " 条指令预算");

        final RegBankDevice regJava = new RegBankDevice(4, "REG-JAVA");
        final RegBankDevice regNative = new RegBankDevice(4, "REG-NATIVE");

        final CpuCore javaCore = new Rv32Core(new Rv32Core.Config().resetVector(ROM_BASE).enableM(true));
        final SocBoard boardJava = SocBoard.builder(javaCore)
                .rom(ROM_BASE, firmware)
                .ram(RAM_BASE, RAM_SIZE)
                .deviceWithIrq(REG_BASE, regJava, "REGBANK")
                .build();

        final NativeRv32Core nativeCore = new NativeRv32Core(ROM_BASE, RAM_BASE, RAM_SIZE, ROM_BASE, 64 * 1024);
        nativeCore.loadFirmware(firmware);
        final SocBoard boardNative = SocBoard.builder(nativeCore)
                .rom(ROM_BASE, new byte[0])          // 镜像已由 nativeCore.loadFirmware 装好
                .ram(RAM_BASE, RAM_SIZE)
                .deviceWithIrq(REG_BASE, regNative, "REGBANK")
                .build();

        // 等价推进：预算切成小片，模拟真实 tick 派发
        int javaDone = 0;
        int nativeDone = 0;
        for (int i = 0; i < 40; i++) {
            javaDone += boardJava.step(STEPS / 40);
            nativeDone += boardNative.step(STEPS / 40);
        }

        check("两版执行指令数一致（java=" + javaDone + ", native=" + nativeDone + "）",
                Math.abs(javaDone - nativeDone) <= 2);
        check("设备寄存器 REG[0] 一致（java=" + regJava.get(0) + ", native=" + regNative.get(0) + "）",
                regJava.get(0) == regNative.get(0));
        check("REG[0] 真的被固件写过（> 0）", regNative.get(0) > 0);

        // 宿主侧读 guest 内存（本次修复的回归点：内存的持有者是内核，宿主不得旁路到 SocBoard 的 MemoryMap）
        final int javaRam = readInt(boardJava, RAM_BASE);
        final int nativeRam = readInt(boardNative, RAM_BASE);
        check("宿主侧读 guest RAM：native 非 0（旧实现读的是空影子设备 ⇒ 恒 0）", nativeRam > 0);
        check("宿主侧读 guest RAM 两版一致（java=" + javaRam + ", native=" + nativeRam + "）",
                Math.abs(javaRam - nativeRam) <= 2);
        final byte[] romJava = boardJava.readMemory(ROM_BASE, 8);
        final byte[] romNative = boardNative.readMemory(ROM_BASE, 8);
        check("宿主侧读 guest ROM 两版一致且非空（native 首 4 字节=0x"
                        + Integer.toHexString(readInt(boardNative, ROM_BASE)) + "）",
                java.util.Arrays.equals(romJava, romNative) && readInt(boardNative, ROM_BASE) != 0);
        check("PC 一致（java=0x" + Long.toHexString(javaCore.getProgramCounter())
                        + ", native=0x" + Long.toHexString(nativeCore.getProgramCounter()) + "）",
                Math.abs(javaCore.getProgramCounter() - nativeCore.getProgramCounter()) <= 8);
        check("minstret 语义一致（java=" + javaCore.getInstructionsRetired()
                        + ", native=" + nativeCore.getInstructionsRetired() + "）",
                Math.abs(javaCore.getInstructionsRetired() - nativeCore.getInstructionsRetired()) <= 4);
        check("寄存器 x1 一致（java=" + javaCore.getRegisters()[1] + ", native=" + nativeCore.getRegisters()[1] + "）",
                javaCore.getRegisters()[1] == nativeCore.getRegisters()[1]);
        check("两版都未故障", !javaCore.isFaulted() && !nativeCore.isFaulted());
        check("native 发生了 MMIO 往返（设备访问确实由 Java 兑现）", nativeCore.mmioCount() > 0);
        check("native 自报名称含 native", nativeCore.getName().contains("native"));

        nativeCore.close();

        System.out.println();
        System.out.print(log);
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 定位 DLL：显式环境变量 → 仓库 build/native → 游戏引擎目录 */
    private static void locateLibrary() {
        if (System.getProperty("cryptand.rv32.lib") != null) {
            return;
        }
        final String env = System.getenv("CRYPTAND_RV32_DLL");
        if (env != null && Files.isRegularFile(Path.of(env))) {
            System.setProperty("cryptand.rv32.lib", Path.of(env).toAbsolutePath().toString());
            return;
        }
        final Path[] candidates = {
                Path.of("build", "native", "cryptand_rv32.dll"),
                Path.of("..", "build", "native", "cryptand_rv32.dll"),
                Path.of("neoforge", "run", "config", "cryptand", "engines", "cryptand_rv32.dll"),
                Path.of("..", "neoforge", "run", "config", "cryptand", "engines", "cryptand_rv32.dll"),
        };
        for (final Path p : candidates) {
            if (Files.isRegularFile(p)) {
                System.setProperty("cryptand.rv32.lib", p.toAbsolutePath().normalize().toString());
                return;
            }
        }
    }

    /** 经 SocBoard（= 内核）读一个 32 位小端整数 */
    private static int readInt(SocBoard board, long address) {
        final byte[] b = board.readMemory(address, 4);
        return (b[0] & 0xFF) | ((b[1] & 0xFF) << 8) | ((b[2] & 0xFF) << 16) | ((b[3] & 0xFF) << 24);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            log.append("  [PASS] ").append(name).append('\n');
        } else {
            failed++;
            log.append("  [FAIL] ").append(name).append('\n');
        }
    }
}
