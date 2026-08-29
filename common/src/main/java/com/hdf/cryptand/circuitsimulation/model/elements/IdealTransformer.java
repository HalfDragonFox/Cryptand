package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.WaveformType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 理想变压器（4 端口 + 内部约束节点）。
 *
 * 电压关系：V_secondary = ratio × V_primary（ratio = 副边匝数/原边匝数）。
 * 无损（功率守恒），无励磁支路/漏感（一阶稳态模型，匹配 PowerGrid Tr2P2S
 * 的"匝数比"行为）。
 *
 * MNA 装配（标准理想变压器，内部节点 k 承载初级电流）：
 *   k 行电压约束：ratio·(Va1-Va2) - (Vb1-Vb2) = 0
 *   a1,a2: 初级电流 I1 = Vk（流入 a1）
 *   b1,b2: 次级电流 I2 = -Vk/ratio
 *   k,k: 小导纳防奇异（弱接地）
 *
 * 节点编码（params 序列化）：[ratio, a1, a2, b1, b2, k]
 * nodeA()/nodeB() 返回初级端口（满足 Element 两端口约定，实际是 4 端口）。
 */
public class IdealTransformer implements Element {

    public final double ratio;
    public final int a1;
    public final int a2;
    public final int b1;
    public final int b2;
    public final int k;

    /** 内部约束节点弱接地导纳（防奇异） */
    public static final double GMIN = 1e-9;

    public IdealTransformer(int a1, int a2, int b1, int b2, int k, double ratio) {
        this.a1 = a1;
        this.a2 = a2;
        this.b1 = b1;
        this.b2 = b2;
        this.k = k;
        this.ratio = ratio;
    }

    @Override
    public int nodeA() { return a1; }

    @Override
    public int nodeB() { return a2; }

    @Override
    public void stampReal(MnaBuilder m, double dt) {
        // V2 = n·V1 约束
        m.addG(k, a1, ratio);
        m.addG(k, a2, -ratio);
        m.addG(k, b1, -1);
        m.addG(k, b2, 1);
        m.addG(k, k, GMIN);
        // 电流约束（Vk 表示初级电流）
        m.addG(a1, k, -1);
        m.addG(a2, k, 1);
        m.addG(b1, k, 1.0 / ratio);
        m.addG(b2, k, -1.0 / ratio);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        // V2 = n·V1 约束
        m.addY(k, a1, new Complex(ratio, 0));
        m.addY(k, a2, new Complex(-ratio, 0));
        m.addY(k, b1, new Complex(-1, 0));
        m.addY(k, b2, new Complex(1, 0));
        m.addY(k, k, new Complex(GMIN, 0));
        // 电流约束（Vk 表示初级电流）
        m.addY(a1, k, new Complex(-1, 0));
        m.addY(a2, k, new Complex(1, 0));
        m.addY(b1, k, new Complex(1.0 / ratio, 0));
        m.addY(b2, k, new Complex(-1.0 / ratio, 0));
    }

    @Override
    public ElementType type() { return ElementType.IDEAL_TRANSFORMER; }

    @Override
    public WaveformType waveform() { return WaveformType.DC; }

    @Override
    public double[] params() { return new double[]{ratio, a1, a2, b1, b2, k}; }

    @Override
    public String toString() {
        return "IdealTransformer{" + a1 + "-" + a2 + ":" + b1 + "-" + b2
                + " n=" + ratio + " k=" + k + "}";
    }
}
