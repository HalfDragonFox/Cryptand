package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;

/**
 * 三相电压源复合模型（对称 Y 连接，三相相位互差 120°，为未来准备）。
 * <p>
 * 现实物理：工业/电力系统三相交流——A/B/C 三相各差 120° 电角度，星形（Y）
 * 连接有公共中性点 N。相电压 = 线电压/√3，相间 120°。
 * <p>
 * 电路解析：A-N / B-N / C-N 各一个 {@link AcVoltageSource}（相位 0°/120°/240°，
 * 内阻串联），幅值相等对称。频率由网络全局 frequency 决定（相量域）。
 * <p>
 * 端口：a/b/c = 三相端子，n = 中性点（Y 连接公共点；Δ 连接可把 n 悬空）。
 * 以后接入：三相变压器原边、三相电机、三相整流桥等。
 */
public class ThreePhaseSourceModel extends CompositeModel {

    /** 三相端子 + 中性点 */
    public final int a, b, c, n;
    /** 相电压幅值（峰值）、内阻（Ω）——固定参数（魔法数字） */
    public final double amplitude, sourceR;

    /**
     * @param a,b,c    三相端子（引擎节点）
     * @param n        中性点（Y 连接公共点）
     * @param amplitude 每相电压幅值（峰值，V）
     * @param sourceR  每相内阻（Ω，>0 防奇异）
     */
    public ThreePhaseSourceModel(int a, int b, int c, int n,
                                 double amplitude, double sourceR) {
        super(build(a, b, c, n, amplitude, sourceR));
        this.a = a;
        this.b = b;
        this.c = c;
        this.n = n;
        this.amplitude = amplitude;
        this.sourceR = sourceR;
    }

    /** 组合三相对称源：A-N(0°)、B-N(120°)、C-N(240°) */
    private static Element[] build(int a, int b, int c, int n,
                                   double amp, double r) {
        return new Element[]{
                new AcVoltageSource(a, n, amp, 0.0, r),
                new AcVoltageSource(b, n, amp, 120.0, r),
                new AcVoltageSource(c, n, amp, 240.0, r)
        };
    }

    /** 相电压（峰值）→ 线电压（峰值）= √3 × 相电压 */
    public double lineAmplitude() { return amplitude * Math.sqrt(3.0); }

    /** 序列化参数：[amp, R, a, b, c, n] */
    public double[] params() {
        return new double[]{amplitude, sourceR, a, b, c, n};
    }

    @Override
    public String toString() {
        return "ThreePhaseSourceModel{A=" + a + " B=" + b + " C=" + c
                + " N=" + n + " amp=" + amplitude + " R=" + sourceR + "}";
    }
}
