package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.IdealTransformer;

/**
 * 三相变压器复合模型（3 个单相理想变压器，Y-Y / Y-Δ 连接，为未来准备）。
 * <p>
 * 现实物理：三相变压器由三台单相变压器组合，常见连接组：
 *   - Y-Y：原边/副边均星形（公共中性点 n1/n2），可带中线或浮空
 *   - Y-Δ：原边星形、副边三角形（无中性点，三次谐波被三角形环流抑制）
 *   - Δ-Y / Δ-Δ
 * <p>
 * 电路解析：每相一个 {@link IdealTransformer}（原边 pa_i-n1，副边 pb_i-n2，
 * 匝比 ratio，约束节点 k_i）。对称三相 → 三台参数相同。
 * <p>
 * 端口：pa1/pa2/pa3 = 原边三相，n1 = 原边中性点（Δ 连接悬空/内部短接）；
 * pb1/pb2/pb3 = 副边三相，n2 = 副边中性点。内部节点 x/y/k（每相漏感/约束）
 * 由调用方分配（与本模型同级的外部节点分配约定）。
 */
public class ThreePhaseTransformerModel extends CompositeModel {

    /** 原边三相 + 中性点、副边三相 + 中性点 */
    public final int pa1, pa2, pa3, n1, pb1, pb2, pb3, n2;
    /** 每相漏感/约束内部节点（x/y/k） */
    public final int x1, y1, k1, x2, y2, k2, x3, y3, k3;
    /** 匝比（副边匝数/原边匝数） */
    public final double ratio;

    public ThreePhaseTransformerModel(int pa1, int pa2, int pa3, int n1,
                                      int pb1, int pb2, int pb3, int n2,
                                      double ratio,
                                      int x1, int y1, int k1,
                                      int x2, int y2, int k2,
                                      int x3, int y3, int k3) {
        super(build(pa1, pa2, pa3, n1, pb1, pb2, pb3, n2, ratio,
                x1, y1, k1, x2, y2, k2, x3, y3, k3));
        this.pa1 = pa1; this.pa2 = pa2; this.pa3 = pa3; this.n1 = n1;
        this.pb1 = pb1; this.pb2 = pb2; this.pb3 = pb3; this.n2 = n2;
        this.ratio = ratio;
        this.x1 = x1; this.y1 = y1; this.k1 = k1;
        this.x2 = x2; this.y2 = y2; this.k2 = k2;
        this.x3 = x3; this.y3 = y3; this.k3 = k3;
    }

    /** 组合：3 个单相理想变压器（每相原边 pa_i-n1，副边 pb_i-n2） */
    private static Element[] build(int pa1, int pa2, int pa3, int n1,
                                   int pb1, int pb2, int pb3, int n2,
                                   double ratio,
                                   int x1, int y1, int k1,
                                   int x2, int y2, int k2,
                                   int x3, int y3, int k3) {
        return new Element[]{
                new IdealTransformer(pa1, n1, pb1, n2, k1, ratio),
                new IdealTransformer(pa2, n1, pb2, n2, k2, ratio),
                new IdealTransformer(pa3, n1, pb3, n2, k3, ratio)
        };
    }

    /** 序列化参数：[ratio, 全部端口/内部节点] */
    public double[] params() {
        return new double[]{ratio,
                pa1, pa2, pa3, n1, pb1, pb2, pb3, n2,
                x1, y1, k1, x2, y2, k2, x3, y3, k3};
    }

    @Override
    public String toString() {
        return "ThreePhaseTransformerModel{ratio=" + ratio
                + " P(" + pa1 + "," + pa2 + "," + pa3 + ")-N" + n1
                + " S(" + pb1 + "," + pb2 + "," + pb3 + ")-N" + n2 + "}";
    }
}
