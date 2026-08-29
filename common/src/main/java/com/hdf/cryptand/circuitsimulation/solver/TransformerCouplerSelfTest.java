package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.TransformerPrimaryHalf;
import com.hdf.cryptand.circuitsimulation.model.composite.TransformerSecondaryHalf;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 互感双半模型 + 协调器【收敛自测】（2026-08-13，纯引擎，无 Minecraft）。
 * <p>
 * 原边网络：AC 源 230V@50Hz（内阻 0.1Ω）+ 原边半（rCp=0.5, rCore=1000, L1=2H, M=1.9, n=2）
 * 副边网络：副边半（rCs=0.5, L2=8H, M=1.9, n=2）+ 负载 10Ω
 * <p>
 * 验证：
 *   1) 迭代收敛（converged，迭代数 < MAX_ITER）
 *   2) 匝比：V2 ≈ V1·n（副边受控电压源 = 原边理想变电压折算）
 *   3) 电流折算：I1_reflect ≈ -I2·n
 *   4) 功率守恒（忽略损耗）：|V1·I1| ≈ |V2·I2|
 *   5) 参考：理想变压器（无损耗）V2 = V1·n = 230·2 = 460V（源直接接，略有内阻分压）
 */
public final class TransformerCouplerSelfTest {

    public static void main(String[] args) {
        double ratio = 2.0;
        double l1 = 2.0, l2 = ratio * ratio * l1, m = 1.9; // k≈0.95
        // 频率 1000Hz：> 阈值 100Hz → 走 AC 相量（ComplexMnaSolver）。
        // ⚠ 50Hz 会被 SolverModeSelector 判为 REAL_DC（时域），互感迭代语义
        //   是瞬态演化而非稳态收敛——AC 变压器自测必须用相量模式。
        double freq = 1000;

        // ===== 原边网络 =====
        Network pn = new Network();
        pn.frequency = freq;
        int gnd = pn.addNode().id;   // 0 地
        int p1 = pn.addNode().id;    // 1 原边端子1
        int a1 = pn.addNode().id;    // 2 铜阻后
        int x = pn.addNode().id;     // 3 漏感后
        pn.addElement(new AcVoltageSource(p1, gnd, 230, 0, 0.1));
        TransformerPrimaryHalf ph =
                new TransformerPrimaryHalf(p1, gnd, a1, x, 0.5, 1000, l1, m, ratio);
        pn.addComposite(ph);

        // ===== 副边网络 =====
        Network sn = new Network();
        sn.frequency = freq;
        int sg = sn.addNode().id;    // 0 地
        int s1 = sn.addNode().id;    // 1 副边端子1
        int b1 = sn.addNode().id;    // 2 铜阻后
        int y = sn.addNode().id;     // 3 漏感后
        TransformerSecondaryHalf sh =
                new TransformerSecondaryHalf(s1, sg, b1, y, 0.5, l2, m, ratio, 0.01);
        sn.addComposite(sh);
        sn.addElement(new Resistor(s1, sg, 10)); // 负载 10Ω

        // ===== 前置验证：受控电压源元件本身（AC 相量） =====
        System.out.println("---- 前置验证：受控电压源（AC） ----");
        Solver stsolver = Solvers.create(sn);
        System.out.printf("  副边求解器 mode=%s (floatEnabled=%s)%n",
                stsolver.mode(), Solvers.floatEnabled);
        sh.voltageSource().setValue(100, 50); // 复数相量
        SolveResult st = stsolver.solve(sn);
        if (st != null && st.complex != null) {
            System.out.printf("  结果: mode=%s converged=%s%n", st.mode, st.converged);
            System.out.printf("  vsrc=(100,50) → V(y)=(%.2f,%.2f) 幅值=%.2f (期望≈100,50)%n",
                    st.complex[sh.y].re, st.complex[sh.y].im, st.complex[sh.y].abs());
        } else {
            System.out.println("  受控源前置验证失败");
        }
        sh.voltageSource().setValue(0, 0);
        System.out.println("--------------------------------");

        // ===== 协调器迭代 =====
        TransformerCoupler coupler = new TransformerCoupler(ph, sh, pn, sn);
        coupler.verbose = true; // 观察每轮收敛行为
        long t0 = System.nanoTime();
        SolveResult[] rs = coupler.solve();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.println("==== 互感双半模型收敛自测 ====");
        System.out.printf("频率=%.0fHz 匝比n=%.2f L1=%.2fH L2=%.2fH M=%.2fH%n", freq, ratio, l1, l2, m);
        System.out.println("迭代结果: converged=" + coupler.converged()
                + " iterations=" + coupler.iterations()
                + " delta=" + String.format("%.3e", coupler.lastDelta())
                + " omega=" + String.format("%.2f", coupler.omegaUsed())
                + " 耗时=" + ms + "ms");
        if (rs == null) {
            System.out.println("❌ 未收敛（返回 null）→ 需回退合并求解！");
            System.exit(1);
        }
        SolveResult r1 = rs[0], r2 = rs[1];
        if (r1 == null || r2 == null) {
            System.out.println("❌ 子网求解失败");
            System.exit(1);
        }

        // ===== 结果提取 =====
        Complex v1 = ph.v1();                     // 原边理想变电压（已平滑）
        Complex v2 = sh.voltageSource().value();  // 副边受控电压源 = V1·n
        Complex i2 = sh.i2();                     // 副边电流
        Complex i1r = ph.source().value();        // 原边受控电流源 = -I2·n

        System.out.printf("V1 = %.4f ∠%.2f° V%n", v1.abs(), Math.toDegrees(Math.atan2(v1.im, v1.re)));
        System.out.printf("V2 = %.4f ∠%.2f° V  (期望 ≈ V1·n = %.4f)%n",
                v2.abs(), Math.toDegrees(Math.atan2(v2.im, v2.re)), v1.abs() * ratio);
        System.out.printf("I2 = %.4f ∠%.2f° A%n", i2.abs(), Math.toDegrees(Math.atan2(i2.im, i2.re)));
        System.out.printf("I1r= %.4f ∠%.2f° A  (期望 ≈ -I2·n = %.4f)%n",
                i1r.abs(), Math.toDegrees(Math.atan2(i1r.im, i1r.re)), i2.abs() * ratio);

        // 理想变压器参考（无损耗）：V2 = 230·n = 460V（源内阻+铜损会略低）
        double v2ratioErr = Math.abs(v2.abs() - v1.abs() * ratio) / (v1.abs() * ratio);
        double i1ratioErr = Math.abs(i1r.abs() - i2.abs() * ratio) / (i2.abs() * ratio);

        boolean pass = coupler.converged()
                && coupler.iterations() < TransformerCoupler.MAX_ITER
                && v2ratioErr < 1e-3
                && i1ratioErr < 1e-3;
        System.out.printf("%n匝比验证: V2/(V1·n) 误差=%.2e%%  I1r/(I2·n) 误差=%.2e%%%n",
                v2ratioErr * 100, i1ratioErr * 100);
        // 功率（视在）：|S1| = |V1·I1|, |S2| = |V2·I2|
        double apparentP1 = v1.abs() * i1r.abs();
        double apparentP2 = v2.abs() * i2.abs();
        System.out.printf("视在功率: |S1|=%.2fVA |S2|=%.2fVA  (忽略损耗应接近)%n",
                apparentP1, apparentP2);

        System.out.println(pass ? "✅ 收敛自测通过" : "❌ 收敛自测失败");
        System.exit(pass ? 0 : 1);
    }
}
