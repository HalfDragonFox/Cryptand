package com.hdf.cryptand.circuitsimulation.model;

/**
 * 波形类型 —— 时域波形源（WaveformSource）的波形标识，
 * 同时随 NetworkSnapshot 序列化，使 C++/GPU/集群端能识别并重建同一种波形。
 * <p>
 * {@link #unit(double, double, double, double)} 返回归一化波形（-1..+1，不含幅值与偏置），
 * 幅值与直流偏置由元件层叠加。
 */
public enum WaveformType {
    /** 直流恒定（忽略频率/相位/占空比） */
    DC,
    /** 正弦：sin(2πft + φ) */
    SINE,
    /** 方波：占空比 duty 内 +1，其余 -1（duty 默认 0.5） */
    SQUARE,
    /** 对称三角波：0 → +1 → 0 → -1 → 0，周期 1 */
    TRIANGLE,
    /** 锯齿波（上升沿）：周期内线性 -1 → +1 */
    SAWTOOTH;

    /**
     * 归一化瞬时值 [-1,+1]。
     *
     * @param t         仿真时间（秒）
     * @param frequency 频率（Hz），0 视作直流
     * @param phaseDeg  相位（度），正数表示时间轴上左移（提前）
     * @param duty      占空比 [0,1]，方波用；其余波形忽略
     */
    public double unit(double t, double frequency, double phaseDeg, double duty) {
        double x;
        if (frequency <= 0) {
            x = 0.0;
        } else {
            x = (t * frequency + phaseDeg / 360.0) % 1.0;
            if (x < 0) x += 1.0;
        }
        switch (this) {
            case DC:       return 1.0;
            case SINE:     return Math.sin(2 * Math.PI * x);
            case SQUARE:   return x < duty ? 1.0 : -1.0;
            case TRIANGLE:
                if (x < 0.25) return 4.0 * x;
                if (x < 0.75) return 2.0 - 4.0 * x;
                return 4.0 * x - 4.0;
            case SAWTOOTH: return 2.0 * x - 1.0;
            default: throw new IllegalStateException("Unknown waveform " + this);
        }
    }

    /**
     * 相量域基波系数 —— 非正弦波形的傅里叶基波幅值倍率，
     * 仅供 stampComplex 在相量模式下做基波近似（SINE=1，DC 无交流分量=0）。
     */
    public double fundamentalFactor() {
        switch (this) {
            case DC:        return 0.0;
            case SINE:      return 1.0;
            case SQUARE:    return 4.0 / Math.PI;              // 4/π
            case TRIANGLE:  return 8.0 / (Math.PI * Math.PI);  // 8/π²
            case SAWTOOTH:  return 2.0 / Math.PI;              // 2/π
            default: throw new IllegalStateException("Unknown waveform " + this);
        }
    }
}
