package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 振荡器复合模型（LC 谐振 + 反馈负阻，为未来准备——时钟/射频载波/本振）。
 * <p>
 * 现实物理：振荡器 = 选频网络（LC 谐振）+ 有源放大反馈（等效负阻 −R 抵消
 * 谐振损耗 R_loss），维持等幅振荡 f_osc = 1/(2π√(LC))。起振条件：|负阻| ≥ R_loss。
 * <p>
 * 相量域简化：LC 并联谐振（L ∥ C）+ 等效负阻 −R（反馈近似）+ 谐振损耗 R_loss。
 * 负阻在 MNA 中为负电导（可收敛，小信号线性近似）。输出从谐振端取。
 * <p>
 * 端口：a/b = 谐振输出端子。内部节点 x 分配（LC 并联点）。
 */
public class OscillatorModel extends CompositeModel {

    /** 谐振输出端子 + 内部节点 */
    public final int a, b, x;
    /** 谐振电感（H）、电容（F）、谐振损耗（Ω）、反馈负阻（Ω） */
    public final double inductance, capacitance, lossR, feedbackNegR;

    public OscillatorModel(int a, int b, int x,
                           double inductance, double capacitance,
                           double lossR, double feedbackNegR) {
        super(build(a, b, x, inductance, capacitance, lossR, feedbackNegR));
        this.a = a; this.b = b; this.x = x;
        this.inductance = inductance;
        this.capacitance = capacitance;
        this.lossR = lossR;
        this.feedbackNegR = feedbackNegR;
    }

    /** 组合：LC 并联（a-x 电感，b-x 电容）+ 谐振损耗 + 反馈负阻（a-b 负电导） */
    private static Element[] build(int a, int b, int x,
                                   double l, double c, double rLoss, double rNeg) {
        return new Element[]{
                new Inductor(a, x, Math.max(l, 1e-9)),
                new Capacitor(x, b, Math.max(c, 1e-12)),
                new Resistor(a, b, Math.max(rLoss, 1e-9)),
                new Resistor(a, b, -Math.abs(rNeg)) // 负阻：负电阻元件（反馈近似）
        };
    }

    /** 谐振频率（Hz）= 1/(2π√(LC)) */
    public double resonantFrequency() {
        if (inductance <= 0 || capacitance <= 0) return 0;
        return 1.0 / (2 * Math.PI * Math.sqrt(inductance * capacitance));
    }

    /** 品质因数 Q = R_总·√(C/L)（起振/选频能力） */
    public double qualityFactor() {
        if (inductance <= 0) return 0;
        return (lossR + feedbackNegR) * Math.sqrt(capacitance / inductance);
    }

    /** 序列化参数：[L, C, R_loss, R_neg, 端口] */
    public double[] params() {
        return new double[]{inductance, capacitance, lossR, feedbackNegR, a, b, x};
    }

    @Override
    public String toString() {
        return "OscillatorModel{" + a + "-" + b + " f="
                + String.format("%.0f", resonantFrequency()) + "Hz Q="
                + String.format("%.1f", qualityFactor()) + "}";
    }
}
