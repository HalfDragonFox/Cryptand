package com.hdf.cryptand.engine;

import com.hdf.cryptand.circuitsimulation.core.SimulationCore;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.List;

/**
 * ===== 引擎独立自测（2026-08-30：引擎独立完整电路仿真核心——软件仿真器可用性） =====
 *
 * 纯 Java 运行（无 MC）：SimulationCore 实例 → 工厂注册（电池+灯+导线——批量
 * 天然成网）→ submitSolve → 验证求解结果（灯电流/电池输出——电压合理）。
 *
 * 运行：gradlew :common:test --tests EngineSelfTest（或 main 直接跑）。
 */
public final class EngineSelfTest {

    public static void main(String[] args) {
        // 1. 核心实例（隔离存储——软件仿真器会话）
        SimulationCore core = new SimulationCore("engine-self-test");
        // 2. 工厂注册：电池（12V + 0.1Ω 内阻）+ 灯（100Ω）+ 导线（连通回路）
        List<AssemblerFactory.DeviceReg> devices = List.of(
                new AssemblerFactory.DeviceReg(null,
                        new com.hdf.cryptand.engine.assemblers.BatteryAssembler(12.0, 0.1), 2),
                new AssemblerFactory.DeviceReg(null,
                        new com.hdf.cryptand.engine.assemblers.LightAssembler(100.0), 2));
        com.hdf.cryptand.engine.assemblers.WireAssembler wireA =
                new com.hdf.cryptand.engine.assemblers.WireAssembler(0.01, 80,
                        new com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel(
                                2.0, 5.0, 298.15, 473.15));
        List<AssemblerFactory.WireReg> wires = List.of(
                new AssemblerFactory.WireReg(0, 1, 1, 0, wireA), // 电池+(term1) → 灯(term0)
                new AssemblerFactory.WireReg(1, 1, 0, 0, wireA)); // 灯+(term1) → 电池-(term0)（回路）
        // 3. 注册（批量——天然成网——网络注册进实例隔离存储）
        core.factory().registerBatch(devices, wires);
        // 4. 求解（同步 API——executor.solve 或 submitSolve 异步）
        Object key = 1L; // 工厂注册键（factoryKey 自增——首个 = 1）
        core.submitSolve(key);
        // 5. 验证（轮询结果）
        SolveResult r = null;
        for (int i = 0; i < 100 && r == null; i++) {
            r = core.registry().result(key);
            if (r == null) {
                try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
            }
        }
        if (r == null || r.complex == null) {
            System.out.println("[EngineSelfTest] FAIL: 无求解结果（引擎异步未完成）");
            return;
        }
        // 6. 电路校验：电池 12V + 内阻 0.1 + 导线 0.02 + 灯 100 → 电流 ≈ 12/100.12 ≈ 0.12A
        int n = r.complex.length;
        double vA = n > 1 ? r.complex[1].abs() : 0;
        double vB = n > 2 ? r.complex[2].abs() : 0;
        double iLight = Math.abs(vA - vB) / 100.0; // 灯两端电压/100Ω
        System.out.println("[EngineSelfTest] nodes=" + n + " v[1]=" + String.format("%.3f", vA)
                + "V v[2]=" + String.format("%.3f", vB) + "V iLight="
                + String.format("%.4f", iLight) + "A");
        boolean ok = iLight > 0.05 && iLight < 0.5; // 预期 ~0.12A
        System.out.println("[EngineSelfTest] " + (ok ? "PASS ✓（引擎独立闭环——软件仿真器可用）"
                : "FAIL ✗（电流异常）"));
        // ===== 场景2：导线过载升温验证（温度模型被计算入——非额定以下不热） =====
        wireOverheatTest();
    }

    /** 导线过载升温：额定 1A 导线过载（~11A）→ 温度应升（>环境 25°C） */
    private static void wireOverheatTest() {
        try {
            SimulationCore core2 = new SimulationCore("wire-temp-test");
            com.hdf.cryptand.engine.assemblers.WireAssembler wireOver =
                    new com.hdf.cryptand.engine.assemblers.WireAssembler(0.5, 1.0,
                            new com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel(
                                    2.0, 5.0, 298.15, 473.15)); // 额定 1A + 0.5Ω（过载 ~11A → 功率大升温明显）
            core2.factory().registerBatch(
                    List.of(new AssemblerFactory.DeviceReg(null,
                                    new com.hdf.cryptand.engine.assemblers.BatteryAssembler(12.0, 0.1), 2),
                            new AssemblerFactory.DeviceReg(null,
                                    new com.hdf.cryptand.engine.assemblers.ResistorAssembler(1.0, null), 2)),
                    List.of(new AssemblerFactory.WireReg(0, 1, 1, 0, wireOver),
                            new AssemblerFactory.WireReg(1, 1, 0, 0, wireOver)));
            core2.submitSolve(1L);
            SolveResult r2 = null;
            for (int i = 0; i < 100 && r2 == null; i++) {
                r2 = core2.registry().result(1L);
                if (r2 == null) { try { Thread.sleep(50); } catch (InterruptedException ignored) { break; } }
            }
            // 多次求解推进温度（温度每轮 update——过载持续升温；固定步长 0.05s/轮）
            for (int i = 0; i < 50; i++) {
                core2.submitSolve(1L);
                for (int j = 0; j < 100; j++) {
                    if (core2.registry().result(1L) != null) break;
                    try { Thread.sleep(50); } catch (InterruptedException ignored) { break; }
                }
            }
            com.hdf.cryptand.engine.NetworkContext<?> ctx2 =
                    (com.hdf.cryptand.engine.NetworkContext<?>) core2.registry().ctx(1L);
            double wireTemp = -999;
            if (ctx2 != null && !ctx2.wireGroups.isEmpty()) {
                wireTemp = ctx2.wireGroups.get(0).thermal().tempCelsius();
            }
            boolean ok2 = wireTemp > 30.0; // 过载 → 温度应显著高于环境（25°C——大功率升温）
            System.out.println("[WireTempTest] wireTemp=" + String.format("%.2f", wireTemp)
                    + "C " + (ok2 ? "PASS ✓（导线温度模型被计算——过载升温）"
                    : "FAIL ✗（温度未升——推进未生效）"));
        } catch (Throwable t) {
            System.out.println("[WireTempTest] EX " + t);
        }
    }
}
