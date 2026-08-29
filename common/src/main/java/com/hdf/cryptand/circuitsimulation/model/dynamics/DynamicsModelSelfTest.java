package com.hdf.cryptand.circuitsimulation.model.dynamics;

import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * 动力学模型自测（2026-08-20 用户要求：能量/温度/转速统一用动力学模型）。
 * <p>
 * 验证 DynamicsModel 作为【统一一阶动力学】的正确性：
 *   A. 解析解准确性：与解析公式 x(t) = x_ss + (x₀−x_ss)·exp(−t/τ) 一致
 *   B. 温度等价性：ThermalModel（委托 DynamicsModel）稳态 = 理论 T_ss =
 *      T_amb + P/G，且与原独立实现行为一致（指数趋近、不振荡）
 *   C. 电机转速爬升：ElectroMachineModel.shaftInertia（τ=J/b）让转速
 *      从低到高平滑爬升（用户核心诉求：加速度/惯性）
 *   D. 电荷松弛：EnergyModel.flowDyn（τ=RC）电荷趋向 i·τ
 *   E. 钳制与重置：min/max、reset 生效
 * <p>
 * 运行：{@code ./gradlew :common:runDynamicsTest}
 */
public final class DynamicsModelSelfTest {

    private static int pass = 0, fail = 0;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ✅ " + name + " — " + detail);
        } else {
            fail++;
            System.out.println("  ❌ " + name + " — " + detail);
        }
    }

    public static void main(String[] args) {
        System.out.println("==== 动力学模型统一自测（DynamicsModel） ====");

        // A. 解析解准确性
        System.out.println("\nA. 解析解与理论公式一致");
        {
            DynamicsModel d = new DynamicsModel(2.0, 0.0); // τ=2s, x₀=0
            double x = 0;
            double tau = 2.0, xss = 10.0, dt = 0.05;
            double maxErr = 0;
            for (int i = 0; i < 400; i++) { // 20s
                x = d.advance(xss, dt);
                double theory = xss + (0 - xss) * Math.exp(-(i + 1) * dt / tau);
                maxErr = Math.max(maxErr, Math.abs(x - theory));
            }
            check("A1 指数趋近精确", maxErr < 1e-9,
                    "20s 内最大误差=" + String.format("%.2e", maxErr));
            // 10τ（20s/2s）后残差 = 10·e⁻¹⁰ ≈ 4.5e-4（未完全收敛属正常）
            check("A2 稳态收敛", Math.abs(x - 10.0) < 5e-3,
                    "x=" + String.format("%.4f", x));
        }

        // B. 温度等价性：委托 DynamicsModel 的 ThermalModel
        System.out.println("\nB. ThermalModel 委托 DynamicsModel（温度）");
        {
            double G = 2.0;   // W/K
            double C = 10.0;  // J/K → τ = 5s
            double P = 100.0; // W → T_ss = 293.15 + 100/2 = 343.15K = 70°C
            ThermalModel t = new ThermalModel(G, C); // 环境 20°C 最高 200°C
            double tAmb = 293.15;
            double expectedSteady = tAmb + P / G;
            double tCur = tAmb;
            for (int i = 0; i < 2000; i++) { // 100s，足够 5τ=25s
                tCur = t.update(P, 0.05);
            }
            check("B1 稳态温度 = T_amb+P/G", Math.abs(tCur - expectedSteady) < 0.5,
                    "T=" + String.format("%.2f", tCur - 273.15) + "°C (期望 "
                            + String.format("%.1f", expectedSteady - 273.15) + "°C)");
            // 散热冷却：P=0 → 回环境
            for (int i = 0; i < 2000; i++) tCur = t.update(0, 0.05);
            check("B2 冷却回环境", Math.abs(tCur - tAmb) < 0.5,
                    "T=" + String.format("%.2f", tCur - 273.15) + "°C");
            // 无振荡：温度单调（POWER_ALPHA 平滑 + 解析解）
            t.setTemperature(tAmb);
            double prev = tAmb;
            boolean monotonic = true;
            for (int i = 0; i < 200; i++) {
                double now = t.update(P, 0.05);
                if (now < prev - 1e-6) monotonic = false;
                prev = now;
            }
            check("B3 升温单调不振荡", monotonic, "10s 内单调递增");
            // 风扇冷却倍率：G_eff 增大 → 稳态降低、τ 减小
            t.setCoolingMultiplier(2.0); // G_eff = 4 W/K
            for (int i = 0; i < 2000; i++) tCur = t.update(P, 0.05);
            double fanSteady = tAmb + P / (G * 2.0);
            check("B4 风扇冷却稳态降低", Math.abs(tCur - fanSteady) < 0.5,
                    "T=" + String.format("%.2f", tCur - 273.15) + "°C (期望 "
                            + String.format("%.1f", fanSteady - 273.15) + "°C)");
            check("B5 过热判定", t.maxTemp > t.getTemperature() || !t.overheated(),
                    "overheated=" + t.overheated() + " T="
                            + String.format("%.1f", t.tempCelsius()) + "°C max="
                            + (t.maxTemp - 273.15) + "°C");
        }

        // C. 电机转速爬升（ElectroMachineModel.shaftInertia 语义）
        System.out.println("\nC. 电机转速爬升（惯性）");
        {
            // 模拟：J=1, b=0.1 → τ = 10s；净转矩 5 N·m → ω_ss = 50 rad/s
            double J = 1.0, b = 0.1, tau = J / b;
            DynamicsModel shaft = new DynamicsModel(tau, 0);
            shaft.minValue = 0;
            double w = 0;
            double dt = 0.05;
            // 1τ（10s）后应达稳态的 63.2%；3τ 后 95%
            for (int i = 0; i < (int) (tau / dt); i++) w = shaft.advance(50.0, dt);
            check("C1 1τ 达 63.2%", Math.abs(w - 50 * (1 - Math.exp(-1))) < 0.5,
                    "ω=" + String.format("%.2f", w) + " rad/s (理论 "
                            + String.format("%.2f", 50 * (1 - Math.exp(-1))) + ")");
            for (int i = 0; i < (int) (2 * tau / dt); i++) w = shaft.advance(50.0, dt);
            check("C2 3τ 达 95%", Math.abs(w - 50 * (1 - Math.exp(-3))) < 0.5,
                    "ω=" + String.format("%.2f", w) + " rad/s (理论 "
                            + String.format("%.2f", 50 * (1 - Math.exp(-3))) + ")");
            // 断电停机：目标 0 → 指数衰减
            double wBefore = w;
            for (int i = 0; i < (int) (3 * tau / dt); i++) w = shaft.advance(0, dt);
            check("C3 断电惯性停机", w < 50 * 0.05,
                    "ω=" + String.format("%.2f", w) + " rad/s（3τ 衰减到 "
                            + String.format("%.1f", wBefore * Math.exp(-3)) + "）");
            // 单向旋转钳制
            shaft.reset(0);
            shaft.advance(-100, dt);
            check("C4 单向旋转钳制≥0", shaft.value() >= 0,
                    "advance(-100) → " + String.format("%.3f", shaft.value()));
        }

        // D. 电荷松弛（EnergyModel.flowDyn）
        System.out.println("\nD. EnergyModel 电荷动力学（τ=RC）");
        {
            EnergyModel e = new EnergyModel(1e-3); // 1mF
            e.internalResistance = 1000;            // 1kΩ → τ = 1s
            double q = 0;
            double i = 0.001;                       // 1mA 充电 → Q_ss = i·τ = 1mC
            for (int t = 0; t < 500; t++) q = e.flowDyn(i, 0.05); // 25s = 25τ
            double expectedQ = i * (e.internalResistance * e.capacitance);
            check("D1 电荷趋向 i·τ", Math.abs(q - expectedQ) < expectedQ * 0.01,
                    "Q=" + String.format("%.4e", q) + "C (期望 "
                            + String.format("%.4e", expectedQ) + "C)");
            check("D2 端电压 = Q/C", Math.abs(e.voltage() - q / e.capacitance) < 1e-12,
                    "V=" + String.format("%.2f", e.voltage()) + "V");
        }

        // E. 钳制 / 重置 / addDelta
        System.out.println("\nE. 钳制 / 重置 / 即时扰动");
        {
            DynamicsModel d = new DynamicsModel(1.0, 5.0);
            d.minValue = 0;
            d.maxValue = 10;
            d.advance(100, 1.0);   // 目标 100 → 被钳制
            check("E1 上限钳制", d.value() <= 10.0,
                    "advance(100) → " + String.format("%.2f", d.value()));
            d.advance(-100, 1.0);  // 目标 -100 → 被钳制
            check("E2 下限钳制", d.value() >= 0.0,
                    "advance(-100) → " + String.format("%.2f", d.value()));
            d.addDelta(3.0);
            check("E3 addDelta 即时扰动", Math.abs(d.value() - 3.0) < 1e-9,
                    "5→0（钳制）→ +3 = " + String.format("%.2f", d.value()));
            d.reset(2.0);
            check("E4 reset", Math.abs(d.value() - 2.0) < 1e-9,
                    "reset(2) → " + String.format("%.2f", d.value()));
        }

        // F. 多模型复合（2026-08-20 用户要求）：一个复合元件同时挂
        //    温度（内置）+ 转速动力学 + 应力（注册），advanceState 统一推进
        System.out.println("\nF. 多模型复合（温度 + 动力学 + 自定义状态）");
        {
            // 模拟一个电机复合元件：thermal（内置）+ shaft（注册）+ stress（注册）
            ThermalModel thermal = new ThermalModel(2.0, 10.0); // τ=5s
            DynamicsModel shaft = new DynamicsModel(1.0, 0);     // τ=1s 转速
            DynamicsModel stress = new DynamicsModel(0.5, 0);    // τ=0.5s 应力跟随转速
            stress.minValue = 0;
            // 用真实 CompositeElement 子类验证：ResistorModel（带 thermal）
            com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel comp =
                    new com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel(
                            0, 1, 10.0, thermal);
            comp.attachStateModel(new com.hdf.cryptand.circuitsimulation.model.state.StateDriven() {
                public boolean advanceState(com.hdf.cryptand.circuitsimulation.solver.Complex va,
                                            com.hdf.cryptand.circuitsimulation.solver.Complex vb,
                                            double freqHz, double dt,
                                            com.hdf.cryptand.circuitsimulation.solver.SolveMode mode) {
                    shaft.advance(10.0, dt);        // 转速爬向 10 rad/s
                    stress.advance(shaft.value(), dt); // 应力跟随转速
                    return false;
                }
            });
            // 推进 3s（3τ），验证三个状态都推进且不互相干扰
            com.hdf.cryptand.circuitsimulation.solver.Complex va =
                    new com.hdf.cryptand.circuitsimulation.solver.Complex(10, 0);
            com.hdf.cryptand.circuitsimulation.solver.Complex vb =
                    new com.hdf.cryptand.circuitsimulation.solver.Complex(0, 0); // 两端压差 → 有损耗
            for (int i = 0; i < 60; i++) comp.advanceState(va, vb, 50.0, 0.05,
                    com.hdf.cryptand.circuitsimulation.solver.SolveMode.COMPLEX_AC);
            check("F1 注册模型被推进（转速→稳态）",
                    Math.abs(shaft.value() - 10 * (1 - Math.exp(-3))) < 0.5,
                    "ω=" + String.format("%.2f", shaft.value()) + " rad/s (期望 "
                            + String.format("%.2f", 10 * (1 - Math.exp(-3))) + ")");
            check("F2 第二个注册模型推进（应力跟随）",
                    Math.abs(stress.value() - shaft.value()) < 0.5,
                    "应力=" + String.format("%.2f", stress.value())
                            + " ≈ 转速=" + String.format("%.2f", shaft.value()));
            check("F3 内置温度同时推进",
                    thermal.tempCelsius() > 20.0, // 有损耗 → 升温
                    "T=" + String.format("%.2f", thermal.tempCelsius()) + "°C");
            check("F4 状态版本递增", comp.stateVersion() >= 60,
                    "stateVersion=" + comp.stateVersion());
        }

        System.out.println("\n==== 结果: " + pass + " 通过, " + fail + " 失败 ====");
        if (fail > 0) System.exit(1);
        System.out.println("✅ 全部通过");
    }
}
