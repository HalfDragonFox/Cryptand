package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 天线复合模型（辐射电阻 + 调谐电抗，为未来准备）。
 * <p>
 * 现实物理：天线把导线电流转化为电磁波辐射。等效电路（馈电点看入）：
 *   Z_ant = R_rad（辐射电阻）+ R_loss（欧姆损耗）+ jX（电抗，长度/频率决定：
 *   短天线容性、长天线感性、谐振长度 X≈0）。调谐网络（LC）匹配 50Ω 馈线。
 * <p>
 * 相量域简化：辐射电阻 R_rad + 欧姆损耗 R_loss 串联，电抗由调谐元件（可选
 * L 或 C，谐振时抵消）表达。实现 {@link ThermalDevice}：欧姆损耗发热
 * （辐射电阻不发热——能量辐射出去了）。
 * <p>
 * 端口：a/b = 馈电点端子。无内部节点。
 */
public class AntennaModel extends CompositeModel implements ThermalDevice {

    /** 馈电点端子 */
    public final int a, b;
    /** 辐射电阻（Ω）、欧姆损耗电阻（Ω）、调谐电抗类型/值 */
    public final double radiationR, lossR;
    /** 调谐元件：0=无，1=电感，2=电容 */
    public final int tuneKind;
    public final double tuneValue;
    /** 温度模型（欧姆损耗发热） */
    public final ThermalModel thermal;

    public AntennaModel(int a, int b, double radiationR, double lossR,
                        int tuneKind, double tuneValue, ThermalModel thermal) {
        super(build(a, b, radiationR, lossR, tuneKind, tuneValue));
        this.a = a; this.b = b;
        this.radiationR = radiationR;
        this.lossR = lossR;
        this.tuneKind = tuneKind;
        this.tuneValue = tuneValue;
        this.thermal = thermal;
    }

    /** 组合：辐射电阻 + 损耗电阻 + 可选调谐电抗（串联等效） */
    private static Element[] build(int a, int b, double rRad, double rLoss,
                                   int kind, double value) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        els.add(new Resistor(a, b, Math.max(rRad + rLoss, 1e-9)));
        if (kind == 1 && value > 0) els.add(new Inductor(a, b, value));      // 感性调谐
        else if (kind == 2 && value > 0) els.add(new Capacitor(a, b, value)); // 容性调谐
        return els.toArray(new Element[0]);
    }

    /** 总输入电阻 = 辐射 + 欧姆 */
    public double inputResistance() { return radiationR + lossR; }

    /** 辐射效率 = R_rad/(R_rad+R_loss) */
    public double efficiency() {
        double t = radiationR + lossR;
        return t <= 0 ? 0 : radiationR / t;
    }

    // ===== ThermalDevice =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 欧姆损耗发热（平均）：I²·R_loss/2（辐射电阻不发热） */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (lossR <= 0) return 0;
        double rIn = inputResistance();
        if (rIn <= 0) return 0;
        double iPeak = va.sub(vb).abs() / rIn;
        return iPeak * iPeak * lossR / 2.0;
    }

    /** 序列化参数：[R_rad, R_loss, tuneKind, tuneValue, a, b] */
    public double[] params() {
        return new double[]{radiationR, lossR, tuneKind, tuneValue, a, b};
    }

    @Override
    public String toString() {
        return "AntennaModel{" + a + "-" + b + " Rrad=" + radiationR
                + " Rloss=" + lossR + " η=" + String.format("%.0f%%", efficiency() * 100) + "}";
    }
}
