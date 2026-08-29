package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.AbstractElement;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplex;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 交流电压源（相量模式）：v(t) = V_peak * sin(ωt + φ)。
 * 相量域用戴维南 → 诺顿：复数电流源 I = V∠φ * G，串联内阻 G=1/R。
 * 注意：时域瞬态 AC 请用 DcVoltageSource 逐采样改电压，本类是相量专用。
 * params: [amplitude, phaseDeg, seriesResistance]
 */
public class AcVoltageSource extends AbstractElement {

    @Override public boolean isSource() { return true; }
    /** 源幅值/相位/内阻（volatile：求解线程读 / 主线程刷新；参数变化经 setter 发消息） */
    public volatile double amplitude;
    public volatile double phaseDeg;
    public volatile double seriesResistance;
    /** 源频率（Hz，2026-08-13 多频叠加）：0 = 跟随网络主导频率（工频源）；
     *  &gt;0 = 固定频率（PLC 载波源，只在匹配频率求解时注入） */
    public final double frequency;

    /** 开路标志（2026-08-23：一端接下游而两端无真实闭合回路的电压源 → 不注入，
     *  避免诺顿等效经 GMin 形成假回路 → 导线 500KA/温度爆炸）。结构属性，
     *  求解器 stamp 前检测设置；孤立源（两端皆无其他元件）不标记（保持开路电压）。 */
    private volatile boolean openCircuit;

    /** 求解器开路检测设置：true = 有下游但无闭合回路 → stamp 不注入（纯内阻） */
    public void setOpenCircuit(boolean open) { this.openCircuit = open; }

    public boolean isOpenCircuit() { return openCircuit; }

    public AcVoltageSource(int a, int b, double amplitude, double phaseDeg, double seriesResistance) {
        this(a, b, amplitude, phaseDeg, seriesResistance, 0);
    }

    public AcVoltageSource(int a, int b, double amplitude, double phaseDeg, double seriesResistance,
                           double frequency) {
        super(a, b);
        this.amplitude = amplitude;
        this.phaseDeg = phaseDeg;
        this.seriesResistance = Math.max(seriesResistance, 1e-9);
        this.frequency = Math.max(frequency, 0);
    }

    /** 更新幅值：参数变化 → 发送参数变化消息（接收方更新求解，不重建网络） */
    public void setAmplitude(double v) {
        if (Double.compare(v, amplitude) != 0) {
            amplitude = v;
            notifyParamChanged();
        }
    }

    /** 更新相位：参数变化 → 发送参数变化消息（接收方更新求解，不重建网络） */
    public void setPhaseDeg(double p) {
        if (Double.compare(p, phaseDeg) != 0) {
            phaseDeg = p;
            notifyParamChanged();
        }
    }

    /** 更新串联内阻：参数变化 → 发送参数变化消息（接收方更新求解，不重建网络） */
    public void setSeriesResistance(double r) {
        double v = Math.max(r, 1e-9);
        if (Double.compare(v, seriesResistance) != 0) {
            seriesResistance = v;
            notifyParamChanged();
        }
    }

    @Override
    public void stampReal(MnaBuilder m, double dt) {
        if (openCircuit) { stampRealPassive(m); return; }
        // 时域模式下本元件退化为当前瞬时值（直流等效），由外部驱动源值；
        // 这里按峰值直流处理便于调试。
        double g = 1.0 / seriesResistance;
        double i = amplitude * g;
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, i);
        m.addB(nodeB, -i);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        if (openCircuit) { stampComplexPassive(m, omega); return; }
        // 多频自包含判断：固定频率源在非工作频率 → 仅内阻（源对目标频率短路）
        double targetF = omega / (2 * Math.PI);
        if (frequency > 0 && Math.abs(frequency - targetF) > 1e-6) {
            stampComplexPassive(m, omega);
            return;
        }
        double g = 1.0 / seriesResistance;
        Complex phasor = Complex.fromPolar(amplitude, Math.toRadians(phaseDeg));
        Complex i = phasor.scale(g);
        m.addY(nodeA, nodeA, new Complex(g, 0));
        m.addY(nodeB, nodeB, new Complex(g, 0));
        m.addY(nodeA, nodeB, new Complex(-g, 0));
        m.addY(nodeB, nodeA, new Complex(-g, 0));
        m.addB(nodeA, i);
        m.addB(nodeB, i.neg());
    }

    // ===== 多频叠加（2026-08-13 PLC 核心） =====

    @Override
    public double sourceFrequency() { return frequency; }

    @Override
    public boolean activeAt(double omega, double dominantFreq) {
        double targetF = omega / (2 * Math.PI);
        if (frequency > 0) return Math.abs(frequency - targetF) < 1e-6;
        // 跟随源（工频）：只在主导频率完整注入（不污染载波频率）
        return Math.abs(dominantFreq - targetF) < 1e-6;
    }

    @Override
    public void stampComplexPassive(ComplexMnaBuilder m, double omega) {
        // 非工作频率：源退化为内阻（理想源对高频/非工作频率 = 短路 + 内阻）
        double g = 1.0 / seriesResistance;
        m.addY(nodeA, nodeA, new Complex(g, 0));
        m.addY(nodeB, nodeB, new Complex(g, 0));
        m.addY(nodeA, nodeB, new Complex(-g, 0));
        m.addY(nodeB, nodeA, new Complex(-g, 0));
    }

    // ===== float 求解器（魔法数字 double 计算后转 float） =====

    @Override
    public boolean supportsFloatReal() { return true; }

    @Override
    public boolean supportsFloatComplex() { return true; }

    @Override
    public void stampRealFloat(FloatMnaBuilder m, double dt, double t) {
        if (openCircuit) {
            float g = (float) (1.0 / seriesResistance);
            m.addG(nodeA, nodeA, g);
            m.addG(nodeB, nodeB, g);
            m.addG(nodeA, nodeB, -g);
            m.addG(nodeB, nodeA, -g);
            return;
        }
        float g = (float) (1.0 / seriesResistance);
        float i = (float) (amplitude * g);
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, i);
        m.addB(nodeB, -i);
    }

    @Override
    public void stampComplexFloat(FloatComplexMnaBuilder m, double omega) {
        float g = (float) (1.0 / seriesResistance);
        if (openCircuit) {
            m.addY(nodeA, nodeA, new FloatComplex(g, 0));
            m.addY(nodeB, nodeB, new FloatComplex(g, 0));
            m.addY(nodeA, nodeB, new FloatComplex(-g, 0));
            m.addY(nodeB, nodeA, new FloatComplex(-g, 0));
            return;
        }
        double rad = Math.toRadians(phaseDeg);
        float ir = (float) (amplitude * Math.cos(rad) * g);
        float ii = (float) (amplitude * Math.sin(rad) * g);
        m.addY(nodeA, nodeA, new FloatComplex(g, 0));
        m.addY(nodeB, nodeB, new FloatComplex(g, 0));
        m.addY(nodeA, nodeB, new FloatComplex(-g, 0));
        m.addY(nodeB, nodeA, new FloatComplex(-g, 0));
        m.addB(nodeA, new FloatComplex(ir, ii));
        m.addB(nodeB, new FloatComplex(-ir, -ii));
    }

    @Override
    public ElementType type() { return ElementType.AC_VOLTAGE_SOURCE; }

    /** 开路时的纯内阻 stamp（无注入；源对网络只呈现内阻） */
    private void stampRealPassive(MnaBuilder m) {
        double g = 1.0 / seriesResistance;
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
    }

    @Override
    public double[] params() { return new double[]{amplitude, phaseDeg, seriesResistance}; }
}
