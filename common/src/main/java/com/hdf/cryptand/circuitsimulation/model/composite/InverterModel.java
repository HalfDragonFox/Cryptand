package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 逆变器复合模型（DC→AC，PWM 近似正弦，为未来准备）。
 * <p>
 * 现实物理：逆变器把直流母线逆变成交流（PWM 开关调制，LC 滤波后近似正弦）。
 * 输出频率/电压可调（光伏并网、电机驱动、UPS）。
 * <p>
 * 相量域简化：输入直流 → 等效负载电阻（吸收功率）+ 母线滤波电容；输出 = 单相
 * 或三相正弦源（频率/幅值可调，PWM 基波近似）。效率 η 体现在输入等效电阻上。
 * <p>
 * 端口：din/gnd = 直流输入；oout/oret = 单相交流输出（单相版）。
 * 三相版见 {@link VfdModel}（整流+逆变一体化）。
 */
public class InverterModel extends CompositeModel {

    /** 直流输入端子 + 单相交流输出端子 */
    public final int din, gnd, oout, oret;
    /** 输入等效电阻（Ω）、母线电容（F）、输出内阻（Ω） */
    public final double inputR, busCap, outputR;

    /** 输出电压幅值（峰值，volatile：可调 → 参数消息） */
    private volatile double outAmplitude;
    /** 输出频率（Hz，volatile） */
    private volatile double outFreq;
    /** 输出相位（度） */
    private volatile double outPhaseDeg;

    /** 输出正弦源引用（setter 更新幅值/频率 → 参数消息 → 重解） */
    private final AcVoltageSource outSource;

    public InverterModel(int din, int gnd, int oout, int oret,
                         double inputR, double busCap, double outputR,
                         double outAmplitude, double outFreq, double outPhaseDeg) {
        super(build(din, gnd, oout, oret, inputR, busCap, outputR,
                outAmplitude, outFreq, outPhaseDeg));
        this.din = din; this.gnd = gnd; this.oout = oout; this.oret = oret;
        this.inputR = inputR; this.busCap = busCap; this.outputR = outputR;
        this.outAmplitude = outAmplitude;
        this.outFreq = outFreq;
        this.outPhaseDeg = outPhaseDeg;
        AcVoltageSource src = null;
        for (Element e : simple) {
            if (e instanceof AcVoltageSource vs && vs.nodeA() == oout) {
                src = vs;
                break;
            }
        }
        this.outSource = src;
    }

    /** 组合：输入等效电阻 + 母线电容 + 输出正弦源（内阻串联） */
    private static Element[] build(int din, int gnd, int oout, int oret,
                                   double rIn, double cBus, double rOut,
                                   double amp, double freq, double phaseDeg) {
        return new Element[]{
                new Resistor(din, gnd, Math.max(rIn, 1e-9)),
                new Capacitor(din, gnd, Math.max(cBus, 1e-12)),
                new AcVoltageSource(oout, oret, amp, phaseDeg, Math.max(rOut, 1e-9))
        };
    }

    /** 更新输出电压幅值（→ 参数消息 → 重解） */
    public void setOutput(double amplitude, double freq, double phaseDeg) {
        boolean ch = Double.compare(amplitude, outAmplitude) != 0
                || Double.compare(freq, outFreq) != 0
                || Double.compare(phaseDeg, outPhaseDeg) != 0;
        outAmplitude = amplitude;
        outFreq = freq;
        outPhaseDeg = phaseDeg;
        if (ch && outSource != null) {
            outSource.setAmplitude(amplitude);
            outSource.setPhaseDeg(phaseDeg);
        }
    }

    public double outputAmplitude() { return outAmplitude; }
    public double outputFrequency() { return outFreq; }

    /** 序列化参数：[R_in, C_bus, R_out, amp, freq, phase, 端口] */
    public double[] params() {
        return new double[]{inputR, busCap, outputR, outAmplitude, outFreq,
                outPhaseDeg, din, gnd, oout, oret};
    }

    @Override
    public String toString() {
        return "InverterModel{dc(" + din + "," + gnd + ") ac(" + oout + ","
                + oret + ") out=" + String.format("%.0f", outAmplitude) + "V "
                + String.format("%.1f", outFreq) + "Hz}";
    }
}
