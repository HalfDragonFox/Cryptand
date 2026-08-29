package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.IdealTransformer;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;

/**
 * 理想变压器复合模型：由基础元件组合而成（互感简式 T 型等效）。
 *
 * 组合的简单元件：
 *   原边漏感 Llp = max(L1 − M/n, L1·1e-6)   电感 a1−x（串联）
 *   磁化电感 Lm  = M/n                        电感 x−a2（并联原边）
 *   副边漏感 Lls = max(L2 − n·M, L2·1e-6)    电感 b1−y（串联）
 *   理想变压器 1:n (x,a2):(y,b2)              约束节点 k（稳定）
 *
 * 全部电感导纳 −j/(ωL)（值域正常）+ 理想变压器约束（±n/±1/GMIN）
 * → 无 1/Δ 病态，float 量级稳定。参数为魔法数字（固定量，更新即重算）。
 *
 * 端口：a1−a2（原边绕组），b1−b2（副边绕组）；内部节点 x/y/k 由外部分配。
 * 实际变压器通过【包含】本模型做电路解析，再配 ThermalModel 做温度模拟。
 *
 * 继承 {@link ThreadDispatchElement}（可选线程分发基类）：变压器是【需要单独
 * 线程计算】的复合元件（原边/副边多回路温度推进可单独提交线程分配器），
 * 普通设备模型（电机等）不继承线程分发基类。
 */
public class IdealTransformerModel extends ThreadDispatchElement {

    /** 原边绕组端口、副边绕组端口 */
    public final int a1, a2, b1, b2;
    /** 内部节点：x=原边漏感后（理想变原边/磁化电感）、y=副边漏感前（理想变副边）、k=约束 */
    public final int x, y, k;
    /** 绕组自感/互感（H）——固定参数（魔法数字） */
    public final double l1, l2, m;
    /** 匝比 n = 副边/原边 */
    public final double ratio;
    /** 内部约束节点弱接地导纳（防奇异） */
    public static final double GMIN = 1e-9;

    public IdealTransformerModel(int a1, int a2, int b1, int b2,
                                 double l1, double l2, double m, double ratio,
                                 int x, int y, int k) {
        super(build(a1, a2, b1, b2, l1, l2, m, ratio, x, y, k));
        this.a1 = a1; this.a2 = a2; this.b1 = b1; this.b2 = b2;
        this.l1 = l1; this.l2 = l2; this.m = m; this.ratio = ratio;
        this.x = x; this.y = y; this.k = k;
    }

    /** 由固定参数（魔法数字）换算漏感/磁化电感并组合简单元件 */
    private static Element[] build(int a1, int a2, int b1, int b2,
                                   double l1, double l2, double m, double ratio,
                                   int x, int y, int k) {
        double n = ratio;
        double lm = m / n;                              // 磁化电感（归算原边）
        double llp = Math.max(l1 - lm, l1 * 1e-6);      // 原边漏感（下限魔法数字）
        double lls = Math.max(l2 - n * m, l2 * 1e-6);   // 副边漏感（下限魔法数字）
        return new Element[]{
                new Inductor(a1, x, llp),      // 原边漏感
                new Inductor(x, a2, lm),       // 磁化电感（并联原边）
                new Inductor(b1, y, lls),      // 副边漏感
                new IdealTransformer(x, a2, y, b2, k, n)  // 理想耦合 1:n
        };
    }

    /** 当前换算出的磁化电感（H）＝M/n */
    public double magnetizingInductance() { return m / ratio; }

    /** 当前换算出的原边漏感（H） */
    public double primaryLeakage() { return Math.max(l1 - magnetizingInductance(), l1 * 1e-6); }

    /** 当前换算出的副边漏感（H） */
    public double secondaryLeakage() { return Math.max(l2 - ratio * m, l2 * 1e-6); }

    @Override
    public ElementType type() { return ElementType.MUTUAL_INDUCTOR; }

    /** 序列化参数（与旧 MutualInductor 兼容：[L1, L2, M, ratio, a1, a2, b1, b2, x, y, k]） */
    public double[] params() {
        return new double[]{l1, l2, m, ratio, a1, a2, b1, b2, x, y, k};
    }

    @Override
    public String toString() {
        return "IdealTransformerModel{" + a1 + "-" + a2 + ":" + b1 + "-" + b2
                + " L1=" + l1 + " L2=" + l2 + " M=" + m + " n=" + ratio
                + " x=" + x + " y=" + y + " k=" + k + "}";
    }
}
