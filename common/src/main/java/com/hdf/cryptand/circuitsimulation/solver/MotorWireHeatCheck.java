package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel;
import com.hdf.cryptand.circuitsimulation.model.composite.WireComposite;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * 一次性验证：电机电路导线发热复现（"5A 电流导线 300°C"）。
 * 模拟真实路径：AC 源 → 导线(带温度模型) → 电机 → 导线 → 回源。
 */
public final class MotorWireHeatCheck {
    public static void main(String[] args) {
        Solvers.floatEnabled = false;
        // 与 WireThermalStore 一致的导线热参数：2 W/K/m、5 J/K/m、25°C、200°C
        double G = 2.0, C = 5.0, amb = 298.15, max = 473.15;

        // 电路：AC源(230V峰值, 50Hz, 内阻0.1) → 导线1(0.015Ω) → 电机 → 导线2 → 回源
        Network net = new Network();
        net.frequency = 50.0;
        var n0 = net.addNode(); // 地
        var n1 = net.addNode(); // 源正
        var n2 = net.addNode(); // 导线1终点/电机a
        var n3 = net.addNode(); // 电机b/导线2起点
        var nx = net.addNode(); // 电机内部
        net.addElement(new AcVoltageSource(n1.id, n0.id, 230.0, 0.0, 0.1));
        ThermalModel th1 = new ThermalModel(G, C, amb, max); // 1m 导线
        ThermalModel th2 = new ThermalModel(G, C, amb, max);
        net.addComposite(new WireComposite(n1.id, n2.id, 0.015, th1, "w1"));
        ElectroMachineModel motor = new ElectroMachineModel(n2.id, n3.id, nx.id,
                0.5, 0.01, null, new EnergyModel(1.0), false, 0.0);
        net.addComposite(motor);
        net.addComposite(new WireComposite(n3.id, n0.id, 0.015, th2, "w2"));

        double dt = 0.01;
        int steps = (int) (30.0 / dt); // 30s 仿真
        System.out.println("==== 电机电路导线发热复现（30s） ====");
        System.out.printf("t(s)  I_导线1(A峰值)  I_导线2(A峰值)  T_导线1(°C)  T_导线2(°C)  ω(rad/s)%n");
        double i1 = 0, i2 = 0;
        for (int step = 0; step < steps; step++) {
            Solver solver = Solvers.create(net);
            SolveResult r = solver.solve(net);
            if (r == null || r.complex == null) break;
            // 导线电流（与 computeWireHeatOne 一致）：|Va−Vb|/R
            i1 = r.complex[n1.id].sub(r.complex[n2.id]).abs() / 0.015;
            i2 = r.complex[n3.id].sub(r.complex[n0.id]).abs() / 0.015;
            // 推进导线温度（与 computeWireHeatOne 一致：lossPower → advance）
            double p1 = i1 * i1 * 0.015 / 2.0;
            double p2 = i2 * i2 * 0.015 / 2.0;
            th1.update(p1, dt);
            th2.update(p2, dt);
            // 推进电机
            motor.advanceShaft(r.complex[n2.id], r.complex[n3.id], 50.0, dt);
            if (step % 100 == 0) {
                System.out.printf("%5.1f  %8.1f      %8.1f      %8.1f      %8.1f      %6.1f%n",
                        step * dt, i1, i2,
                        th1.tempCelsius(), th2.tempCelsius(), motor.rotorSpeedRadS);
            }
        }
        System.out.println("\n=== 结论 ===");
        System.out.printf("导线1 温度: %.1f°C（电流 %.1fA 峰值 = %.1fA RMS）%n",
                th1.tempCelsius(), i1, i1 / Math.sqrt(2));
        System.out.printf("导线2 温度: %.1f°C（电流 %.1fA 峰值 = %.1fA RMS）%n",
                th2.tempCelsius(), i2, i2 / Math.sqrt(2));
        if (th1.tempCelsius() > 200 || th2.tempCelsius() > 200) {
            System.out.println("⚠ 导线过热烧毁（300°C 症状复现）");
        } else {
            System.out.println("✅ 导线温度正常（远低于烧毁线）");
        }
    }
}
