package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 临时自测（2026-08-21）：验证【相量伪时域】DC 电容充电（用户：1F+30V DC 应
 * 瞬态充电——初始大电流 → 指数衰减 → 存电；DC/AC 都走相量，节拍达成伪时域）。
 *
 * 电路：30V DC 源 --2Ω-- 1F 电容（相量求解 + 电容 Backward Euler 伴随逐节拍
 * 充电，ComplexMnaSolver 求解后 commit 更新 vPrev）。
 * 运行：./gradlew :common:runCapacitorChargeTest
 */
public class CapacitorChargeSelfTest {

    public static void main(String[] args) {
        Solvers.floatEnabled = false; // 纯引擎测试环境无 native，走 double

        // ===== 搭建：DC 源(30V) --2Ω-- 1F 电容 =====
        Network net = new Network();
        net.frequency = 0.0; // DC
        net.dt = 0.05;       // 伪时域节拍 0.05s（solveAll 每节拍设置）
        var n0 = net.addNode(); // 地
        var n1 = net.addNode(); // 源正极
        var n2 = net.addNode(); // 电容正极
        net.addElement(new AcVoltageSource(n1.id, n0.id, 30.0, 0.0, 1e-4));
        net.addElement(new Resistor(n1.id, n2.id, 2.0));  // 2Ω
        Capacitor cap = new Capacitor(n2.id, n0.id, 1.0); // 1F
        net.addElement(cap);
        cap.simDt = net.dt; // 伪时域节拍（solveAll 会同步）

        // ===== 相量伪时域逐节拍求解（每次 solve 内部 commit 更新电容 vPrev） =====
        Solver solver = Solvers.create(SolveMode.COMPLEX_AC, net); // DC 也走相量
        double lastI = 0;
        boolean currentFalling = true; // 电流应单调衰减
        boolean charged = false;       // 电容应存到 ~30V
        double peakI = 0;
        System.out.println("==== DC 电容充电（30V --2Ω-- 1F，相量伪时域 dt=0.05s，τ=RC=2s）====");
        for (int step = 0; step < 240; step++) { // 240 步 = 12s = 6τ
            SolveResult r = solver.solve(net);
            if (r == null || r.complex == null || !r.converged) {
                System.out.println("  求解失败 @step " + step + ": " + r);
                System.exit(1);
                return;
            }
            double v1 = r.complex[n1.id].re; // DC 相量实部 = 瞬时电压
            double v2 = r.complex[n2.id].re;
            double i = (v1 - v2) / 2.0; // 经 2Ω 电阻的电流
            if (step % 20 == 0) {
                System.out.printf("  t=%4.1fs V=%.3fV I=%.3fA%n", step * net.dt, v2, i);
            }
            if (step == 0) peakI = i;
            if (step > 0 && i > lastI + 1e-6) currentFalling = false;
            lastI = i;
            if (v2 > 29.5) charged = true;
        }
        // 最终电流（应衰减到 ~0）
        SolveResult rf = solver.solve(net);
        double vf = rf.complex[n2.id].re;
        double ifin = (rf.complex[n1.id].re - vf) / 2.0;
        System.out.printf("  最终: V=%.3fV I=%.4fA%n", vf, ifin);
        System.out.printf("  初始峰值 I=%.2fA（期望≈15A）%n", peakI);

        boolean pass = currentFalling && charged && ifin < 0.1 && Math.abs(peakI - 15.0) < 3.0;
        System.out.println(pass ? "✅ 相量伪时域电容充电自测通过（大电流→衰减→存电）"
                : "❌ 自测失败");
        System.exit(pass ? 0 : 1);
    }
}
