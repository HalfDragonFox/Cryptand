package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 临时自测（2026-08-21）：验证【相量伪时域】DC 电感不短路（用户：DC 走相量后
 * 导线电流 615A——电感 ω=0 → Y=1/jωL=∞ → 短路）。修复后电感 DC/低频用
 * Backward Euler 伴随（G=dt/L + I_hist=iPrev），电流随节拍演化收敛到稳态。
 *
 * 电路：30V DC 源 --2Ω-- 1H 电感。稳态 I = 30/2 = 15A（电感 DC 通）。
 * 运行：./gradlew :common:runInductorTest
 */
public class InductorDCSelfTest {

    public static void main(String[] args) {
        Solvers.floatEnabled = false; // 纯引擎测试环境无 native，走 double

        // ===== 搭建：DC 源(30V) --2Ω-- 1H 电感 =====
        Network net = new Network();
        net.frequency = 0.0; // DC
        net.dt = 0.05;       // 伪时域节拍 0.05s
        var n0 = net.addNode(); // 地
        var n1 = net.addNode(); // 源正极
        var n2 = net.addNode(); // 电感正极
        net.addElement(new AcVoltageSource(n1.id, n0.id, 30.0, 0.0, 1e-4));
        net.addElement(new Resistor(n1.id, n2.id, 2.0));  // 2Ω
        Inductor ind = new Inductor(n2.id, n0.id, 1.0);   // 1H
        net.addElement(ind);
        ind.simDt = net.dt;

        // ===== 相量伪时域逐节拍求解 =====
        Solver solver = Solvers.create(SolveMode.COMPLEX_AC, net);
        double peakI = 0;
        double lastI = 0;
        boolean monotonic = true;
        System.out.println("==== DC 电感（30V --2Ω-- 1H，相量伪时域 dt=0.05s，τ=L/R=0.5s）====");
        for (int step = 0; step < 240; step++) { // 240 步 = 12s = 24τ
            SolveResult r = solver.solve(net);
            if (r == null || r.complex == null || !r.converged) {
                System.out.println("  求解失败 @step " + step + ": " + r);
                System.exit(1);
                return;
            }
            double v1 = r.complex[n1.id].re;
            double v2 = r.complex[n2.id].re;
            double i = (v1 - v2) / 2.0; // 经 2Ω 电阻的电流
            if (step == 0) peakI = i;
            if (step > 0 && i > lastI + 1e-6) monotonic = false;
            lastI = i;
            if (step % 20 == 0) {
                System.out.printf("  t=%4.1fs I=%.3fA%n", step * net.dt, i);
            }
        }
        SolveResult rf = solver.solve(net);
        double ifin = (rf.complex[n1.id].re - rf.complex[n2.id].re) / 2.0;
        System.out.printf("  初始峰值 I=%.2fA（应 < 30A，不是 600A+）%n", peakI);
        System.out.printf("  最终稳态 I=%.4fA（期望≈15A）%n", ifin);

        boolean pass = Math.abs(ifin - 15.0) < 1.0 && peakI < 30.0;
        System.out.println(pass ? "✅ 电感 DC 不短路（电流收敛 15A，非 600A）"
                : "❌ 自测失败 peakI=" + peakI + " ifin=" + ifin);
        System.exit(pass ? 0 : 1);
    }
}
