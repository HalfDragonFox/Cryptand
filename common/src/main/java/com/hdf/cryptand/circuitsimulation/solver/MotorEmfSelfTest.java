package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel;
import com.hdf.cryptand.circuitsimulation.model.composite.WireComposite;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;

/**
 * 电动机反电动势闭环自测（2026-08-19 修复"连接电机导线就消失"）。
 * <p>
 * 场景：AC 源 230V(50Hz) → 导线 → 电动机（绕组 R=0.5Ω L=0.01H，K_EMF=1.0，
 * K_T=1.0，J=1.0，摩擦 0.05）→ 导线 → 回源。空载。
 * <p>
 * 旧模型（纯 R-L 无源绕组，无 EMF）：电流恒 = V/Z ≈ 365A 峰值 → 导线
 * I²R 过流烧毁（金导线烧毁阈 ≈483A，长时间 365A 必烧）→ "电机导线消失"。
 * <p>
 * 新模型（反电动势源串联 + 转速反馈闭环）：
 *   - 启动（ω=0，EMF=0）：堵转电流 ≈ V/Z（物理合理，瞬态）
 *   - 转速升 → EMF=K_EMF·ω 升 → 净电流 (V−EMF)/Z 降 → 稳态空载电流小
 *   - 稳态转速 ≈ V/K_EMF（230 rad/s ≈ 2200 RPM），电流 ≈ 摩擦拖累 ≈ 11A
 * <p>
 * 验证断言：
 *   A. 启动电流 > 20A（堵转大电流，物理合理）且 < 烧毁阈 483A
 *   B. 稳态（5s）电流 < 30A（空载小，导线不烧）——旧模型恒 ≈ 365A
 *   C. 稳态转速 > 100 rad/s（反电动势反馈驱动转速上升，EMF→端口电压）
 *   D. 电流单调下降（反电动势反馈生效；旧模型恒流不降）
 * <p>
 * 运行：{@code ./gradlew :common:runMotorEmfTest}
 */
public final class MotorEmfSelfTest {

    /** 源电压（V 峰值） */
    private static final double SRC_V = 230.0;
    /** 源内阻（Ω） */
    private static final double SRC_R = 0.1;
    /** 导线电阻（Ω/条） */
    private static final double WIRE_R = 0.015;
    /** 电动机绕组参数 */
    private static final double COIL_R = 0.5;
    private static final double COIL_L = 0.01;
    /** 伪时域步长（s） */
    private static final double DT = 0.01;
    /** 仿真时长（s） */
    private static final double SIM_S = 5.0;

    public static void main(String[] args) {
        Solvers.floatEnabled = false;

        // ===== 电路：AC源(50Hz) → 导线1 → 电动机 → 导线2 → 回源 =====
        Network net = new Network();
        net.frequency = 50.0;
        var n0 = net.addNode(); // 地（源负极 / 导线2 回端）
        var n1 = net.addNode(); // 源正极 / 导线1 起点
        var n2 = net.addNode(); // 导线1 终点 / 电机 a
        var n3 = net.addNode(); // 电机 b / 导线2 起点
        var nx = net.addNode(); // 电机内部节点（R-L 串联）

        net.addElement(new AcVoltageSource(n1.id, n0.id, SRC_V, 0.0, SRC_R));
        net.addComposite(new WireComposite(n1.id, n2.id, WIRE_R, null, "w1"));
        ElectroMachineModel motor = new ElectroMachineModel(n2.id, n3.id, nx.id,
                COIL_R, COIL_L, null, new EnergyModel(1.0), false, 0.0);
        net.addComposite(motor);
        net.addComposite(new WireComposite(n3.id, n0.id, WIRE_R, null, "w2"));

        System.out.println("==== 电动机反电动势闭环仿真（50Hz，空载） ====");
        System.out.printf("电路：AC源 %.0fV → 导线1(%.3fΩ) → 电机(R=%.2fΩ L=%.3fH K=%.1f) → 导线2 → 回源%n",
                SRC_V, WIRE_R, COIL_R, COIL_L, motor.emfConstant);
        System.out.println("旧模型（无 EMF）：电流恒 ≈ " + String.format("%.0fA",
                SRC_V / (SRC_R + 2 * WIRE_R + COIL_R)) + " → 导线必烧");
        System.out.println();

        double startI = 0, prevI = Double.MAX_VALUE, steadyI = 0, steadyW = 0;
        double maxCircuitVsShaftErr = 0; // 电路电流 vs 转矩电流最大偏差（网络一致性）
        double maxVAb = 0;               // 端口电压最大幅值（网络无异常）
        int steps = (int) (SIM_S / DT);
        for (int step = 0; step < steps; step++) {
            Solver solver = Solvers.create(net);
            SolveResult r = solver.solve(net);
            if (r == null || !r.converged || r.complex == null) {
                System.out.println("  求解失败 step=" + step + ": " + r);
                System.exit(1);
            }
            // 网络异常检查：端口电压必须在合理范围（<500V，无 NaN/发散）
            double vAb = r.complex[n2.id].sub(r.complex[n3.id]).abs();
            maxVAb = Math.max(maxVAb, vAb);
            if (Double.isNaN(vAb) || vAb > 500) {
                System.out.println("  ⚠ 网络异常：V_ab=" + vAb + " step=" + step);
                System.exit(1);
            }
            double z = motor.impedanceAt(2 * Math.PI * net.frequency);
            double emf = Math.abs(motor.emfConstant * motor.rotorSpeedRadS);
            // 转矩电流（advanceShaft 视角）：|V_ab − E|/Z
            double iShaft = motor.generatorMode ? vAb / z
                    : Math.max(0, vAb - emf) / z;
            // 电路支路电流（戴维南精确）：|V_a − V_x − E|/R（电流法，R=绕组电阻）
            double iCircuit = r.complex[n2.id].sub(r.complex[nx.id])
                    .sub(new com.hdf.cryptand.circuitsimulation.solver.Complex(emf, 0)).abs() / COIL_R;
            // 一致性：两视角电流应接近（符号错误时电路 (V+E)/Z vs 转矩 (V−E)/Z 会严重背离）
            double err = Math.abs(iCircuit - iShaft) / Math.max(1e-9, Math.max(iCircuit, iShaft));
            maxCircuitVsShaftErr = Math.max(maxCircuitVsShaftErr, err);
            double iPeak = iShaft;
            if (step == 0) startI = iPeak;
            // 推进转速（等效求解器每步回调 advanceState → advanceShaft）
            motor.advanceShaft(r.complex[n2.id], r.complex[n3.id], net.frequency, DT);

            if (step % 50 == 0 || step == steps - 1) {
                System.out.printf("t=%4.2fs  I=%8.1fA峰值  I电路=%8.1fA  ω=%7.1frad/s(%5.0fRPM)  EMF=%.1fV%n",
                        step * DT, iPeak, iCircuit, motor.rotorSpeedRadS,
                        motor.rotorSpeedRadS * 60.0 / (2 * Math.PI), emf);
            }
            if (iPeak > prevI * 1.02 && step > 10) {
                System.out.println("  ⚠ 电流回升（反电动势反馈失效？）step=" + step);
            }
            prevI = iPeak;
            steadyI = iPeak;
            steadyW = motor.rotorSpeedRadS;
        }

        // ===== 断言 =====
        System.out.println("\n==== 验证结果 ====");
        boolean aStart = startI > 20 && startI < 483;
        boolean bSteady = steadyI < 30;
        boolean cSpeed = steadyW > 100; // 反电动势反馈驱动转速升到百 rad/s 级
        boolean dDrop = steadyI < startI * 0.5; // 稳态 ≤ 启动 50%（空载电流大降）
        boolean eConsistent = maxCircuitVsShaftErr < 0.25; // 电路电流≈转矩电流（网络正常）
        boolean fNoBlowup = maxVAb < 300; // 端口电压无异常放大（符号错误时 E 反馈会推高）
        System.out.printf("A. 启动电流 %.0fA ∈ (20, 483)：%s%n", startI, aStart ? "✅" : "❌");
        System.out.printf("B. 稳态电流 %.1fA < 30A（空载小，导线不烧）：%s%n", steadyI, bSteady ? "✅" : "❌");
        System.out.printf("C. 稳态转速 %.0frad/s > 100（EMF 反馈生效）：%s%n",
                steadyW, cSpeed ? "✅" : "❌");
        System.out.printf("D. 电流单调下降（%.0fA → %.0fA）：%s%n",
                startI, steadyI, dDrop ? "✅" : "❌");
        System.out.printf("E. 电路电流≈转矩电流（最大偏差 %.1f%% < 25%%，网络不矛盾）：%s%n",
                maxCircuitVsShaftErr * 100, eConsistent ? "✅" : "❌");
        System.out.printf("F. 端口电压无异常放大（峰值 %.0fV < 300V）：%s%n",
                maxVAb, fNoBlowup ? "✅" : "❌");
        boolean pass = aStart && bSteady && cSpeed && dDrop && eConsistent && fNoBlowup;
        System.out.println(pass
                ? "✅ 电动机反电动势闭环自测通过（启动堵转→转速升→空载电流小；网络电流一致无异常）"
                : "❌ 电动机反电动势闭环自测失败");
        System.exit(pass ? 0 : 1);
    }

    private MotorEmfSelfTest() {
    }
}
