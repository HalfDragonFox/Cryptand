package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 无线电发射机复合模型（载波 + 调制 + 输出阻抗，为未来准备）。
 * <p>
 * 现实物理：发射机把低频信息（音频/数据）调制到高频载波上经天线辐射。
 * 调制方式：AM（幅度调制，载波幅度随信号）、FM（频率调制）、数字调制。
 * 输出功率 P_tx 由末级功放提供，馈给天线。
 * <p>
 * 相量域简化：载波源（AcVoltageSource，频率/幅值可调）+ 输出内阻 R_out
 * （功放输出阻抗）。调制表现为载波幅值/相位随外部信号更新（setter →
 * 参数消息 → 重解）。实现 {@link ThermalDevice}：功放损耗发热。
 * <p>
 * 端口：a/b = 射频输出端子（接天线）。无内部节点。
 */
public class RadioTransmitterModel extends CompositeModel implements ThermalDevice {

    /** 射频输出端子 */
    public final int a, b;
    /** 功放输出阻抗（Ω） */
    public final double outputR;
    /** 载波幅值（V）、相位（度）、功率（W） */
    private volatile double carrierAmp, carrierPhase, txPower;
    /** 温度模型 */
    public final ThermalModel thermal;

    private final AcVoltageSource carrier;

    public RadioTransmitterModel(int a, int b, double outputR,
                                 double carrierAmp, double txPower,
                                 ThermalModel thermal) {
        super(build(a, b, outputR, carrierAmp));
        this.a = a; this.b = b;
        this.outputR = outputR;
        this.carrierAmp = carrierAmp;
        this.carrierPhase = 0;
        this.txPower = txPower;
        this.thermal = thermal;
        AcVoltageSource src = null;
        for (Element e : simple) {
            if (e instanceof AcVoltageSource vs && vs.nodeA() == a) { src = vs; break; }
        }
        this.carrier = src;
    }

    /** 组合：载波源（a-b，内阻 R_out） */
    private static Element[] build(int a, int b, double rOut, double amp) {
        return new Element[]{
                new AcVoltageSource(a, b, amp, 0.0, Math.max(rOut, 1e-9))
        };
    }

    /** AM 调制：按调制深度 m 和信号 s(t) 更新载波幅值 */
    public void setModulatedAmplitude(double carrierAmpBase, double depth, double signal) {
        double amp = carrierAmpBase * (1.0 + depth * signal);
        if (amp < 0) amp = 0;
        carrierAmp = amp;
        if (carrier != null) carrier.setAmplitude(amp);
    }

    /** 设置发射功率（功放耗散，用于发热） */
    public void setTxPower(double w) { this.txPower = Math.max(0, w); }

    public double carrierAmplitude() { return carrierAmp; }
    public double txPower() { return txPower; }

    // ===== ThermalDevice =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 功放损耗（平均）：约 = 发射功率 × (1−效率)（简化） */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        // 简化：输出电流 I²·R_out/2 代表射频功率；损耗近似为功放效率损失
        double iPeak = outputR <= 0 ? 0 : va.sub(vb).abs() / outputR;
        double pRadio = iPeak * iPeak * outputR / 2.0;
        return Math.max(0, pRadio * 0.4); // 功放效率 ~60% → 40% 损耗（魔法数字）
    }

    /** 序列化参数：[R_out, carrierAmp, txPower, a, b] */
    public double[] params() {
        return new double[]{outputR, carrierAmp, txPower, a, b};
    }

    @Override
    public String toString() {
        return "RadioTransmitterModel{" + a + "-" + b + " carrier="
                + String.format("%.1f", carrierAmp) + "V P_tx="
                + String.format("%.1f", txPower) + "W}";
    }
}
