package com.hdf.cryptand.circuitsimulation.model;

import com.hdf.cryptand.circuitsimulation.solver.Complex;

import java.util.ArrayList;
import java.util.List;

/**
 * 波形组（多频叠加，2026-08-13 PLC 核心）。
 * <p>
 * 电网承载多频率成分（工频 50Hz 功率 + 高频载波 kHz~MHz 通信）→ 用本组
 * 存储各频率分量。线性电路叠加定理：各频率独立求解（同一矩阵结构不同 omega），
 * 总响应 = 各频率响应的线性叠加。
 * <pre>
 *   v(t) = Σ A_k · sin(2π·f_k·t + φ_k)
 *   RMS  = √(Σ A_k²/2)   （正交频率）
 * </pre>
 * 本类完全独立于 Minecraft/PowerGrid，随 Network 参与求解/序列化。
 */
public final class WaveformGroup {

    /** 单频率分量 */
    public static final class Component {
        /** 频率（Hz） */
        public final double frequency;
        /** 峰值幅值（V 或 A） */
        public final double amplitude;
        /** 相位（度） */
        public final double phaseDeg;
        /** 波形类型（SINE=正弦载波；DC=直流偏置；其余按傅里叶基波近似） */
        public final WaveformType type;

        public Component(double frequency, double amplitude, double phaseDeg, WaveformType type) {
            this.frequency = Math.max(frequency, 0);
            this.amplitude = amplitude;
            this.phaseDeg = phaseDeg;
            this.type = type == null ? WaveformType.SINE : type;
        }

        /** 相量（峰值） */
        public Complex phasor() {
            return Complex.fromPolar(amplitude, Math.toRadians(phaseDeg));
        }

        /** 瞬时值贡献 */
        public double valueAt(double t) {
            return amplitude * type.unit(t, frequency, phaseDeg, 0.5);
        }

        @Override
        public String toString() {
            return String.format("%.3gHz %.3gV ∠%.1f° %s", frequency, amplitude, phaseDeg, type);
        }
    }

    private final List<Component> components = new ArrayList<>();

    public WaveformGroup() {
    }

    public WaveformGroup add(Component c) {
        if (c != null && c.amplitude != 0) components.add(c);
        return this;
    }

    public WaveformGroup add(double frequency, double amplitude, double phaseDeg) {
        return add(new Component(frequency, amplitude, phaseDeg, WaveformType.SINE));
    }

    public int count() { return components.size(); }

    public List<Component> components() { return components; }

    public Component component(int i) { return components.get(i); }

    /** 主导频率（工频）：最低非零频率。多频网络缓存键/求解器模式用它。 */
    public double dominantFrequency() {
        double dom = 0;
        for (Component c : components) {
            if (c.frequency > 0 && (dom <= 0 || c.frequency < dom)) dom = c.frequency;
        }
        return dom;
    }

    /** 所有唯一频率（升序，含 0=DC 偏置） */
    public List<Double> frequencies() {
        java.util.TreeSet<Double> set = new java.util.TreeSet<>();
        for (Component c : components) set.add(c.frequency);
        return new ArrayList<>(set);
    }

    /** 合成 RMS = √(Σ A²/2)（正交频率；DC 分量 A 直接计入 A²） */
    public double rms() {
        double s = 0;
        for (Component c : components) {
            if (c.frequency <= 0) s += c.amplitude * c.amplitude; // DC
            else s += c.amplitude * c.amplitude / 2.0;            // AC
        }
        return Math.sqrt(s);
    }

    /** 任意时刻合成瞬时值 v(t) = Σ A_k·sin(2πf_k t + φ_k) */
    public double valueAt(double t) {
        double v = 0;
        for (Component c : components) v += c.valueAt(t);
        return v;
    }

    /** 是否为多频（≥2 个非零频率分量；单频/纯 DC → false，走单频求解） */
    public boolean isMultiTone() {
        int ac = 0;
        for (Component c : components) if (c.frequency > 0) ac++;
        return ac >= 2;
    }

    @Override
    public String toString() {
        return "WaveformGroup" + components;
    }
}
