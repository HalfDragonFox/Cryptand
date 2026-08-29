package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.AbstractElement;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.WaveformType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 时域波形源 —— 支持方波/三角波/正弦/锯齿/直流等任意波形。
 * 可作电压源（戴维南 → 诺顿，带串联内阻）或电流源。
 * <p>
 * 瞬时值 v(t) = offset + amplitude * waveform.unit(t, f, φ, duty)。
 * {@link #stampRealAt} 按网络当前仿真时间 t 求瞬时值（时域瞬态）；
 * 相量域 {@link #stampComplex} 仅对 SINE 严格成立，其余波形用傅里叶基波近似。
 * <p>
 * params（与 NetworkSnapshot 传输约定一致，下标固定，供 C++/集群解析）：
 * [isVoltage?1:0, amplitude, frequency, phaseDeg, duty, offset, seriesResistance]
 */
public class WaveformSource extends AbstractElement {

    @Override public boolean isSource() { return true; }
    public final boolean voltage;           // true=电压源, false=电流源
    public final WaveformType waveform;
    public final double amplitude;
    public final double frequency;          // Hz
    public final double phaseDeg;
    public final double duty;               // 方波占空比 [0,1]
    public final double offset;             // 直流偏置
    public final double seriesResistance;   // 电压源内阻

    /** 开路标志（2026-08-23 电压源开路：一端接下游而两端无闭合回路 → 不注入，
     *  避免诺顿等效经 GMin 假回路 → 假电流/温度爆炸；孤立源不标记保持开路电压） */
    private volatile boolean openCircuit;

    public void setOpenCircuit(boolean open) { this.openCircuit = open; }

    public boolean isOpenCircuit() { return openCircuit; }

    public WaveformSource(int a, int b, boolean voltage, WaveformType waveform,
                          double amplitude, double frequency, double phaseDeg,
                          double duty, double offset, double seriesResistance) {
        super(a, b);
        this.voltage = voltage;
        this.waveform = waveform;
        this.amplitude = amplitude;
        this.frequency = Math.max(frequency, 0);
        this.phaseDeg = phaseDeg;
        this.duty = Math.max(0, Math.min(1, duty));
        this.offset = offset;
        this.seriesResistance = Math.max(seriesResistance, 1e-9);
    }

    /** 当前时刻瞬时值（含幅值与偏置） */
    public double valueAt(double t) {
        return offset + amplitude * waveform.unit(t, frequency, phaseDeg, duty);
    }

    @Override
    public void stampRealAt(MnaBuilder m, double dt, double t) {
        if (openCircuit) {
            // 开路：不注入（电压源 → 纯内阻；电流源 → 断路）
            if (voltage) {
                double g = 1.0 / seriesResistance;
                m.addG(nodeA, nodeA, g);
                m.addG(nodeB, nodeB, g);
                m.addG(nodeA, nodeB, -g);
                m.addG(nodeB, nodeA, -g);
            }
            return;
        }
        double v = valueAt(t);
        if (voltage) {
            double g = 1.0 / seriesResistance;
            double i = v * g;               // 诺顿电流源，方向 a→b
            m.addG(nodeA, nodeA, g);
            m.addG(nodeB, nodeB, g);
            m.addG(nodeA, nodeB, -g);
            m.addG(nodeB, nodeA, -g);
            m.addB(nodeA, i);
            m.addB(nodeB, -i);
        } else {
            m.addB(nodeA, v);
            m.addB(nodeB, -v);
        }
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        if (openCircuit) { stampComplexPassive(m, omega); return; }
        // 多频自包含判断：固定频率源在非工作频率 → 仅内阻/开路（不注入）
        double targetF = omega / (2 * Math.PI);
        if (frequency > 0 && Math.abs(frequency - targetF) > 1e-6) {
            stampComplexPassive(m, omega);
            return;
        }
        // 相量域：SINE 严格；其余波形以傅里叶基波近似（基波幅值 = amplitude * factor）
        double amp = amplitude * waveform.fundamentalFactor();
        Complex phasor = Complex.fromPolar(amp, Math.toRadians(phaseDeg));
        if (voltage) {
            double g = 1.0 / seriesResistance;
            Complex i = phasor.scale(g);
            m.addY(nodeA, nodeA, new Complex(g, 0));
            m.addY(nodeB, nodeB, new Complex(g, 0));
            m.addY(nodeA, nodeB, new Complex(-g, 0));
            m.addY(nodeB, nodeA, new Complex(-g, 0));
            m.addB(nodeA, i);
            m.addB(nodeB, i.neg());
        } else {
            m.addB(nodeA, phasor);
            m.addB(nodeB, phasor.neg());
        }
    }

    // ===== 多频叠加（2026-08-13 PLC 核心） =====

    @Override
    public double sourceFrequency() { return frequency; }

    @Override
    public boolean activeAt(double omega, double dominantFreq) {
        double targetF = omega / (2 * Math.PI);
        if (frequency > 0) return Math.abs(frequency - targetF) < 1e-6;
        return Math.abs(dominantFreq - targetF) < 1e-6; // 跟随源只注入主导频率
    }

    @Override
    public void stampComplexPassive(ComplexMnaBuilder m, double omega) {
        if (voltage) {
            double g = 1.0 / seriesResistance;
            m.addY(nodeA, nodeA, new Complex(g, 0));
            m.addY(nodeB, nodeB, new Complex(g, 0));
            m.addY(nodeA, nodeB, new Complex(-g, 0));
            m.addY(nodeB, nodeA, new Complex(-g, 0));
        }
        // 电流源在非工作频率 → 开路（无注入）
    }

    @Override
    public ElementType type() { return ElementType.WAVEFORM_SOURCE; }

    @Override
    public WaveformType waveform() { return waveform; }

    @Override
    public double[] params() {
        return new double[]{voltage ? 1 : 0, amplitude, frequency, phaseDeg, duty, offset, seriesResistance};
    }
}
