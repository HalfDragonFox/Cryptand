package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.WaveformGroup;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

import java.util.Map;

/**
 * 多频叠加（WaveformGroup + MultiToneSolver）自测（2026-08-13，PLC 核心）。
 * <p>
 * 电路：节点 1 接【工频源 230V@50Hz（跟随）】+【载波源 5V@100kHz（固定频）】
 * + 负载 100Ω 到地。
 * <p>
 * 验证：
 *   1) 多频网络 → MultiToneSolver（绕开频率阈值）
 *   2) 源频率选择性：50Hz 求解只有工频源注入；100kHz 只有载波源注入
 *   3) 每频率独立求解（toneVoltages 含 2 频率）
 *   4) 合成 RMS = √(V50²/2 + V100k²/2)
 *   V50 ≈ 230·100/(100+0.05) ≈ 229.89V（两个内阻 0.1 并联 → 0.05Ω）
 *   V100k ≈ 5·100/(100+0.05) ≈ 5.00V
 *   RMS ≈ √(229.89²/2 + 5²/2) ≈ 162.6V
 */
public final class MultiToneSelfTest {

    public static void main(String[] args) {
        // ===== 网络 =====
        Network net = new Network();
        net.frequency = 50; // 主导频率
        int gnd = net.addNode().id; // 0
        int p1 = net.addNode().id;  // 1

        // 工频源（frequency=0 → 跟随主导频率 50Hz）
        net.addElement(new AcVoltageSource(p1, gnd, 230, 0, 0.1, 0));
        // 载波源（frequency=100000 → PLC 载波，只在 100kHz 求解时注入）
        net.addElement(new AcVoltageSource(p1, gnd, 5, 0, 0.1, 100000));
        // 负载
        net.addElement(new Resistor(p1, gnd, 100));

        // ===== 波形组（多频声明） =====
        WaveformGroup wg = new WaveformGroup()
                .add(50, 230, 0)      // 工频分量
                .add(100000, 5, 0);   // 载波分量
        net.setWaveforms(wg);

        System.out.println("==== 多频叠加（WaveformGroup）自测 ====");
        System.out.println("波形组: " + wg + " 主导频率=" + wg.dominantFrequency()
                + "Hz 多频=" + wg.isMultiTone() + " RMS=" + String.format("%.3f", wg.rms()));

        // ===== 求解器选择 =====
        Solver solver = Solvers.create(net);
        System.out.println("求解器: " + solver.getClass().getSimpleName()
                + " (期望 MultiToneSolver)");

        // ===== 求解 =====
        SolveResult r = solver.solve(net);
        if (r == null) {
            System.out.println("❌ 求解失败");
            System.exit(1);
        }
        System.out.println("结果: converged=" + r.converged + " tones="
                + (r.toneVoltages == null ? "null" : r.toneVoltages.size())
                + " 迭代=" + r.iterations);

        // ===== 每频率相量 =====
        double v50 = 0, v100k = 0;
        if (r.toneVoltages != null) {
            for (Map.Entry<Double, Complex[]> e : r.toneVoltages.entrySet()) {
                double f = e.getKey();
                Complex v = e.getValue()[1];
                System.out.printf("  频率 %.6g Hz: V(节点1) = %.3f ∠%.2f° V%n",
                        f, v.abs(), Math.toDegrees(Math.atan2(v.im, v.re)));
                if (Math.abs(f - 50) < 1) v50 = v.abs();
                if (Math.abs(f - 100000) < 1) v100k = v.abs();
            }
        }

        // ===== 合成 RMS =====
        double rms = r.voltages[1];
        double expectRms = Math.sqrt(v50 * v50 / 2 + v100k * v100k / 2);
        System.out.printf("  合成 RMS(节点1) = %.3f V (期望 %.3f) 相对误差 %.3e%n",
                rms, expectRms, Math.abs(rms - expectRms) / expectRms);

        // ===== 理论对照（诺顿叠加，两个源内阻都并联在节点 1） =====
        // 节点 1 对地总导纳 G = 1/0.1(工频源) + 1/0.1(载波源) + 1/100(负载) = 20.01S
        // 50Hz：工频源主动（I=230/0.1=2300A）、载波源被动（内阻并联）→ V50 = 2300/20.01
        // 100kHz：载波源主动（I=5/0.1=50A）、工频源被动 → V100k = 50/20.01
        double gTotal = 1.0 / 0.1 + 1.0 / 0.1 + 1.0 / 100;
        double expectV50 = 2300.0 / gTotal;
        double expectV100k = 50.0 / gTotal;
        System.out.printf("  理论: V50=%.3f V100k=%.3f RMS=%.3f (G=%.3fS)%n",
                expectV50, expectV100k,
                Math.sqrt(expectV50 * expectV50 / 2 + expectV100k * expectV100k / 2),
                gTotal);

        // ===== 验证 =====
        boolean pass = solver instanceof MultiToneSolver
                && r.converged
                && r.toneVoltages != null && r.toneVoltages.size() == 2
                && Math.abs(v50 - expectV50) / expectV50 < 1e-3
                && Math.abs(v100k - expectV100k) / expectV100k < 1e-2
                && Math.abs(rms - expectRms) / expectRms < 1e-3;

        System.out.println(pass ? "✅ 多频叠加自测通过" : "❌ 多频叠加自测失败");
        System.exit(pass ? 0 : 1);
    }

    private MultiToneSelfTest() {}
}
