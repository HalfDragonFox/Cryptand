package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.peripheral.PeripheralMap;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 显示拓扑闸门（纯 Java 零 MC，2026-09-18）=====
 *
 * <p>要钉住用户定案的三条：没有 GPU 就**看不见显示区域**；屏必须挂在显卡下（不能直连内核）；
 * OC 原版显卡只驱动字符屏，真彩屏要 Cryptand 显卡（不支持的组合**明确报错**，不静默降级）。</p>
 *
 * <p>跑法：{@code ./gradlew :common:runDisplayTest}</p>
 */
public final class DisplayOwnershipSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        // ---- 1. MCU 默认模块集没有 GPU ⇒ 看不见显示区域 ----
        final List<SocModule> mcu = SocModules.of(SocCpuTiers.Family.MCU);
        check("MCU 默认模块集没有 GPU 模块", !DisplayTopology.hasGpu(mcu));
        final DisplayTopology.Layout mcuLayout = new DisplayTopology.Layout(mcu);
        check("没有 GPU ⇒ 看不见任何显示区域", !mcuLayout.visible());
        check("摘要写清原因（不是黑屏，是没有显示设备）",
                mcuLayout.summary().contains("看不见显示区域"));

        boolean noGpu = false;
        try {
            mcuLayout.attach("GPU0", new DisplayTopology.Screen("screen0",
                    DisplayTopology.ScreenKind.TRUE_COLOR, 160, 50, 8, "GPU0"));
        } catch (IllegalStateException e) {
            noGpu = e.getMessage().contains("没有显卡模块");
        }
        check("往不存在的显卡挂屏 ⇒ 明确报错", noGpu);

        // ---- 2. SOC：GPU0 走 QSPI ⇒ Cryptand 能力 ⇒ 字符屏 + 真彩屏都能驱动 ----
        final List<SocModule> soc = SocModules.of(SocCpuTiers.Family.SOC);
        check("SOC 的 GPU 走 QSPI ⇒ Cryptand 能力",
                DisplayTopology.gpuKindOf(SocModules.byName(soc, "GPU0")) == DisplayTopology.GpuKind.CRYPTAND);
        final DisplayTopology.Layout socLayout = new DisplayTopology.Layout(soc);
        socLayout.attach("GPU0", new DisplayTopology.Screen("screen0",
                DisplayTopology.ScreenKind.TEXT, 80, 25, 0, "GPU0"));
        socLayout.attach("GPU0", new DisplayTopology.Screen("screen1",
                DisplayTopology.ScreenKind.TRUE_COLOR, 160, 50, 32, "GPU0"));
        check("SOC 能同时挂字符屏与 32bpp 真彩屏", socLayout.screens().size() == 2);
        check("摘要含色深与承载显卡",
                socLayout.summary().contains("32bpp") && socLayout.summary().contains("via GPU0"));

        // ---- 3. CPU：GPU0 走 PCIe ⇒ 同样支持真彩 ----
        final List<SocModule> cpu = SocModules.of(SocCpuTiers.Family.CPU);
        check("CPU 的 GPU 走 PCIe ⇒ Cryptand 能力",
                DisplayTopology.gpuKindOf(SocModules.byName(cpu, "GPU0")) == DisplayTopology.GpuKind.CRYPTAND);

        // ---- 4. OC 原版显卡：只驱动字符屏；真彩屏必须报错 ----
        final List<SocModule> machine = new ArrayList<>(SocModules.base());
        machine.add(new SocModule("GPU-OC", PeripheralMap.Carrier.INTERNAL, 0,
                0x1000_8000L, 0x1000, -1, "screen0"));
        check("走片上/并口的 GPU 视为 OC 原版显卡",
                DisplayTopology.gpuKindOf(SocModules.byName(machine, "GPU-OC"))
                        == DisplayTopology.GpuKind.OC_VANILLA);

        final DisplayTopology.Layout ocLayout = new DisplayTopology.Layout(machine);
        ocLayout.attach("GPU-OC", new DisplayTopology.Screen("c0",
                DisplayTopology.ScreenKind.TEXT, 80, 25, 0, "GPU-OC"));
        check("OC 原版显卡能驱动字符屏", ocLayout.visible());

        boolean trueColorRejected = false;
        try {
            ocLayout.attach("GPU-OC", new DisplayTopology.Screen("t0",
                    DisplayTopology.ScreenKind.TRUE_COLOR, 160, 50, 16, "GPU-OC"));
        } catch (IllegalStateException e) {
            trueColorRejected = e.getMessage().contains("真彩屏需要 Cryptand 显卡");
        }
        check("OC 原版显卡 + 真彩屏 ⇒ 明确报错（不静默降级）", trueColorRejected);

        // ---- 5. 重复挂载 / 声明与装配不一致 ⇒ 报错 ----
        boolean dup = false;
        try {
            ocLayout.attach("GPU-OC", new DisplayTopology.Screen("c0",
                    DisplayTopology.ScreenKind.TEXT, 80, 25, 0, "GPU-OC"));
        } catch (IllegalStateException e) {
            dup = e.getMessage().contains("屏 id 重复");
        }
        check("重复挂同一块屏 ⇒ 报错", dup);

        boolean wrongGpu = false;
        try {
            ocLayout.attach("GPU-OC", new DisplayTopology.Screen("c9",
                    DisplayTopology.ScreenKind.TEXT, 80, 25, 0, "GPU-OTHER"));
        } catch (IllegalStateException e) {
            wrongGpu = e.getMessage().contains("声明挂在");
        }
        check("屏声明挂 A 却装配到 B ⇒ 报错", wrongGpu);

        // ---- 6. 能力表本身（OC 原版 vs Cryptand）----
        check("能力表：Cryptand 显卡 → 字符屏与真彩屏都能驱动",
                DisplayTopology.canDrive(DisplayTopology.GpuKind.CRYPTAND, DisplayTopology.ScreenKind.TEXT)
                        && DisplayTopology.canDrive(DisplayTopology.GpuKind.CRYPTAND,
                        DisplayTopology.ScreenKind.TRUE_COLOR));
        check("能力表：OC 原版 → 只能字符屏",
                DisplayTopology.canDrive(DisplayTopology.GpuKind.OC_VANILLA, DisplayTopology.ScreenKind.TEXT)
                        && !DisplayTopology.canDrive(DisplayTopology.GpuKind.OC_VANILLA,
                        DisplayTopology.ScreenKind.TRUE_COLOR));

        System.out.println("[DISPLAY] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
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
