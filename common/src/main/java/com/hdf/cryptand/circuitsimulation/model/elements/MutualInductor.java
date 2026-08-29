package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.WaveformType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 互感（耦合电感）基础元件：两个绕组通过互感 M 磁耦合。【简式实现，2026-08-11】
 *
 * 相量（AC）域【简式：T 型等效 + 理想变压器 1:1 约束】——替代病态互感 Y 矩阵：
 *   原导纳 Y = (1/(jωΔ))·[L2 -M; -M L1]，Δ = L1·L2 - M²。
 *   当耦合系数 k = M/√(L1L2) → 1（真实变压器 k≈0.99999）时 Δ→0，Y 元素
 *   ∝ 1/Δ 巨大 → MNA 矩阵 LU 数值病态 → 带载时副边电压塌缩为 0。
 *
 *   简式等效（固定参数 L1/L2/M/ratio 直接换算出魔法数字，更新参数即重算）：
 *     n   = ratio（副边/原边匝比）
 *     Lm  = M/n        磁化电感（归算到原边，并联 x-a2）＝k·L1
 *     Llp = L1 - M/n   原边漏感（串联 a1-x）＝L1(1-k)，恒正
 *     Lls = L2 - n·M   副边漏感（串联 b1-y）＝L2(1-k)，恒正
 *     理想变压器 1:n (x,a2):(y,b2)，约束节点 k（电压/电流约束，值 ±n ±1 + GMIN）
 *   ⚠ 不能用 1:1 变压器 + Lm=M（n≠1 时 Llp = L1-M 为负 → 被 clamp → 开路
 *     电压比错误）。必须带匝比 n，磁化电感归算到原边 M/n。
 *   全部电感导纳 = -j/(ωL)（值域正常）→ 无 1/Δ 病态，float 量级即可稳定求解。
 *   计算过程用 double 保证精度，最后输出 float 节省性能/内存（为 float 化准备）。
 *
 * 拓扑：
 *   a1 ──Llp── x           y ──Lls── b1
 *              │  [1:n]    │
 *   a2 ──Lm────x   约束    y ──────── b2
 *
 * 内部节点由调用方分配传入：x（原边漏感后）、y（副边漏感前）、k（约束）。
 * 时域（DC）Backward Euler 已禁用（本元件只走 AC stampComplex），
 * stampReal/commit 仅保留作历史实现，不再使用。
 *
 * params 序列化：[L1, L2, M, ratio, a1, a2, b1, b2, x, y, k]
 * nodeA()/nodeB() 返回原边绕组端口（满足 Element 两端口约定，实际 4 端口）。
 */
public class MutualInductor implements Element {

    /** 原边绕组（绕组1）端子 */
    public final int a1;
    public final int a2;
    /** 副边绕组（绕组2）端子 */
    public final int b1;
    public final int b2;
    /** 绕组1 自感（H）、绕组2 自感（H）、互感（H）——固定参数（魔法数字） */
    public final double l1;
    public final double l2;
    public final double m;
    /** 匝比 n = 副边/原边（T 型等效理想变压器匝比，固定参数） */
    public final double ratio;
    /** 内部节点：x=原边漏感后（理想变原边/磁化电感）、y=副边漏感前（理想变副边）、k=约束 */
    public final int x;
    public final int y;
    public final int k;

    /** 内部约束节点弱接地导纳（防奇异） */
    public static final double GMIN = 1e-9;

    /** 时域状态：两绕组历史电流（A）（时域已禁用，仅保留兼容） */
    public double i1Prev;
    public double i2Prev;

    public MutualInductor(int a1, int a2, int b1, int b2, double l1, double l2, double m,
                          double ratio, int x, int y, int k) {
        this.a1 = a1;
        this.a2 = a2;
        this.b1 = b1;
        this.b2 = b2;
        this.l1 = l1;
        this.l2 = l2;
        this.m = m;
        this.ratio = ratio;
        this.x = x;
        this.y = y;
        this.k = k;
    }

    @Override
    public int nodeA() { return a1; }

    @Override
    public int nodeB() { return a2; }

    @Override
    public void stampReal(MnaBuilder b, double dt) {
        double d = l1 * l2 - m * m;
        if (Math.abs(d) < 1e-12) d = 1e-12;
        double g11 = dt * l2 / d;
        double g12 = -dt * m / d;
        double g22 = dt * l1 / d;
        // 历史电流源：I_src1 = (L2·h1 - M·h2)/Δ，h1=L1·i1p+M·i2p，h2=M·i1p+L2·i2p
        double h1 = l1 * i1Prev + m * i2Prev;
        double h2 = m * i1Prev + l2 * i2Prev;
        double is1 = (l2 * h1 - m * h2) / d;
        double is2 = (-m * h1 + l1 * h2) / d;
        // 电导（诺顿）
        b.addG(a1, a1, g11); b.addG(a1, a2, -g11); b.addG(a1, b1, g12); b.addG(a1, b2, -g12);
        b.addG(a2, a1, -g11); b.addG(a2, a2, g11); b.addG(a2, b1, -g12); b.addG(a2, b2, g12);
        b.addG(b1, a1, g12); b.addG(b1, a2, -g12); b.addG(b1, b1, g22); b.addG(b1, b2, -g22);
        b.addG(b2, a1, -g12); b.addG(b2, a2, g12); b.addG(b2, b1, -g22); b.addG(b2, b2, g22);
        // 历史电流源：流入 a1/b1
        b.addB(a1, is1);
        b.addB(a2, -is1);
        b.addB(b1, is2);
        b.addB(b2, -is2);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder b, double omega) {
        // 简式带匝比 T 型等效（固定参数 → 魔法数字）：
        //   n = ratio（副边/原边匝比）
        //   Lm  = M/n        磁化电感（归算到原边，并联 x-a2）＝k·L1
        //   Llp = L1 - M/n   原边漏感（串联 a1-x）＝L1(1-k)，恒正
        //   Lls = L2 - n·M   副边漏感（串联 b1-y）＝L2(1-k)，恒正
        //  理想变压器 1:n (x,a2):(y,b2)，约束节点 k
        // 电感导纳 = -j/(ωL)（值域正常）→ 无 1/Δ 病态，float 量级稳定。
        // 计算用 double（精度）→ 输出 float（性能/内存，为 float 化准备）。
        // ⚠ 漏感魔法数字下限：k→1（cf≈1）时 L-M/n→0，若 clamp 到 1e-12，
        //   导纳 1/(ωL)≈1e9 巨大 → 矩阵 LU 病态。物理上漏磁恒存在，用固定
        //   比例（自感的 1e-6=0.0001%）做下限（配 cf 上限 0.999999 一致），
        //   更新参数即重算。下限不能过大（过大 → 漏感感抗巨大 → 带载塌缩）。
        double n = ratio;
        double lm = m / n;
        double llp = Math.max(l1 - lm, l1 * 1e-6);
        double lls = Math.max(l2 - n * m, l2 * 1e-6);
        float yLlp = (float) (-1.0 / (omega * llp));
        float yLm = (float) (-1.0 / (omega * lm));
        float yLls = (float) (-1.0 / (omega * lls));
        // 三个电感：原边漏感 a1-x、磁化电感 x-a2、副边漏感 b1-y
        stampInductor(b, a1, x, yLlp);
        stampInductor(b, x, a2, yLm);
        stampInductor(b, b1, y, yLls);
        // 理想变压器 1:n（x,a2 : y,b2，约束节点 k）——稳定约束，无大导纳
        float gm = (float) GMIN;
        b.addY(k, x, new Complex((float) n, 0f));
        b.addY(k, a2, new Complex((float) -n, 0f));
        b.addY(k, y, new Complex(-1f, 0f));
        b.addY(k, b2, new Complex(1f, 0f));
        b.addY(k, k, new Complex(gm, 0f));
        b.addY(x, k, new Complex(-1f, 0f));
        b.addY(a2, k, new Complex(1f, 0f));
        b.addY(y, k, new Complex((float) (1.0 / n), 0f));
        b.addY(b2, k, new Complex((float) (-1.0 / n), 0f));
    }

    /** 电感导纳装配：Y = -j·y（y 为 float 量级导纳幅值）。 */
    private static void stampInductor(ComplexMnaBuilder b, int n1, int n2, float y) {
        Complex c = new Complex(0f, y);
        Complex cn = new Complex(0f, -y);
        b.addY(n1, n1, c); b.addY(n1, n2, cn);
        b.addY(n2, n1, cn); b.addY(n2, n2, c);
    }

    @Override
    public void commit(double va1, double va2, double dt) {
        // 求解后更新两绕组电流（本元件是 4 端口，nodeA/B 只是原边；副边电流无法
        // 从双参 commit 得到 → 时域已禁用，此处仅通过原边电压估算保持状态不漂移）
        double d = l1 * l2 - m * m;
        if (Math.abs(d) < 1e-12) d = 1e-12;
        // 假设副边电流近似不变，用原边方程反推 i1 增量
        double v1 = va1 - va2;
        i1Prev += (dt * (l2 * v1 - m * 0.0)) / d;
    }

    @Override
    public ElementType type() { return ElementType.MUTUAL_INDUCTOR; }

    @Override
    public WaveformType waveform() { return WaveformType.DC; }

    @Override
    public double[] params() { return new double[]{l1, l2, m, ratio, a1, a2, b1, b2, x, y, k}; }

    @Override
    public String toString() {
        return "MutualInductor{" + a1 + "-" + a2 + ":" + b1 + "-" + b2
                + " L1=" + l1 + " L2=" + l2 + " M=" + m + " n=" + ratio
                + " x=" + x + " y=" + y + " k=" + k + "}";
    }
}
