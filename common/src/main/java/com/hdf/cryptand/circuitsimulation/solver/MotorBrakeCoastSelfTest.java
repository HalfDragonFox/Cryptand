package com.hdf.cryptand.circuitsimulation.solver;

/**
 * 断电滑行惯性制动自测（2026-08-26）：
 * 用真实 ElectroMachineModel 普通电机，先有电 1s（应力/转速建立），再断电
 * 60s 连续推进（dt=0.05，20 步/s 仿真），打印每秒应力下降——验证惯性制动
 * 引擎数学是否如设计（空载 30s 满应力→0，即每秒 ≈ 16384/30 ≈ 546）。
 *
 * 运行：java -cp common/build/classes/java/main
 *      com.hdf.cryptand.circuitsimulation.solver.MotorBrakeCoastSelfTest
 */
public final class MotorBrakeCoastSelfTest {

    public static void main(String[] args) throws InterruptedException {
        com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel em =
                new com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel(
                        0, 1, 2, 25.6, 0.05, null,
                        new com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel(1.0),
                        false, 0.0);
        em.setSpecs(230, 258, 16384, 256, false, 0);
        Complex va = new Complex(230, 0), vb = new Complex(0, 0);
        // 有电 1s（固定步长 dt=0.05，驱动爬升 —— 与真实运行一致）
        for (int i = 0; i < 20; i++) em.advanceShaft(va, vb, 50, 0.05);
        System.out.printf("有电1s后 ω=%7.3f rad/s  σ=%9.1f%n",
                em.rotorSpeedRadS, em.lastStressSU);

        System.out.println("断电滑行（真实时间节拍，advanceReal 100ms 去重）：");
        System.out.printf("t=%02ds σ=%9.1f  ω=%7.3f%n", 0,
                em.lastStressSU, em.rotorSpeedRadS);
        Complex z = new Complex(
                em.emfConstant * em.rotorSpeedRadS, 0); // 断电：EMF 残压自洽（端口
        // 电压≈K·ω 仍在 → 旧 driven 判据恒 true 不滑行；新 powered 判据按注入电流
        // （V−E≈0 → iCur≈0）→ 滑行。验证修复。
        // 阶段A：空转（networkStressSU=0 → 即使 networkRadS=0 也不判堵转）1s 对照；
        // 阶段B：接入满网络应力消耗 + 网络转速 0（被过载卡死 → 堵转）→ 应力应立即归零。
        for (int frame = 1; frame <= 27; frame++) {
            if (frame == 10) em.setNetworkStressSU(10000); // 过载堵转：负载在，转速 0
            Thread.sleep(110);
            em.advanceShaft(z, z, 50, 0.05);
            if (frame % 9 == 0) {
                System.out.printf("t=%3ds σ=%9.1f  ω=%7.3f%n",
                        frame * 110 / 1000, em.lastStressSU, em.rotorSpeedRadS);
            }
        }
        System.out.println("\n==== 期望 ====");
        System.out.println("阶段A（networkStressSU=0，空转）：每秒减 ≈ maxStress/30 ≈ 546；"
                + "阶段B（设 networkStressSU=10000 且网络转速 0 = 过载堵转）→ 应力应立即"
                + "归零（不再不归零）。证明堵转检测生效。");
    }
}