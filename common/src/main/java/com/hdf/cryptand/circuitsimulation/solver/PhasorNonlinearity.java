package com.hdf.cryptand.circuitsimulation.solver;

/**
 * 相量非线性工具（2026-08-15 谐波平衡法配套）：
 * 时域采样 ↔ 谐波相量 的 DFT/重构。
 *
 * 约定（与 {@link MultiToneSolver} 一致）：
 *   - 时域 v(t) = Re{ Σ_h V_h·exp(jhωt) } = Σ_h (re_h·cos(hωt) - im_h·sin(hωt))
 *   - 相量 V_h 为幅值相量（峰值）；h>0 用 2/M 归一（单边谱，与 MultiTone 一致）
 *   - 直流 h=0 用 1/M 平均
 */
public final class PhasorNonlinearity {

    private PhasorNonlinearity() {
    }

    /** 一个基波周期均分 M 点：第 m 个采样时刻（s） */
    public static double sampleTime(double omega, int m, int total) {
        return m * (2.0 * Math.PI) / (omega * total);
    }

    /** 由谐波相量（h=0..H）重构第 m 个采样点的时域值（含直流） */
    public static double reconstruct(Complex[] harmonics, double omega, int m, int total) {
        double t = sampleTime(omega, m, total);
        double v = 0;
        for (int h = 0; h < harmonics.length; h++) {
            Complex x = harmonics[h];
            if (x == null) continue;
            double th = h * omega * t;
            v += x.re * Math.cos(th) - x.im * Math.sin(th);
        }
        return v;
    }

    /**
     * 时域采样序列 → 谐波相量（0..harmonics）。
     * h>0 用 2/M 归一（幅值相量）；h=0 用 1/M（直流平均）。
     */
    public static Complex[] dft(double[] samples, int harmonics, double omega) {
        int mCount = samples == null ? 0 : samples.length;
        int hMax = Math.max(0, harmonics);
        Complex[] out = new Complex[hMax + 1];
        if (mCount == 0) {
            for (int h = 0; h <= hMax; h++) out[h] = Complex.ZERO;
            return out;
        }
        for (int h = 0; h <= hMax; h++) {
            double re = 0, im = 0;
            for (int m = 0; m < mCount; m++) {
                double t = sampleTime(omega, m, mCount);
                double th = h * omega * t;
                double x = samples[m];
                re += x * Math.cos(th);
                im += x * Math.sin(th);
            }
            if (h == 0) {
                out[h] = new Complex(re / mCount, im / mCount);
            } else {
                out[h] = new Complex(2.0 * re / mCount, 2.0 * im / mCount);
            }
        }
        return out;
    }

    /** 谐波相量 → 每个节点的合成 RMS（√(Σ|V_h|²/2) + 直流 |V0|²） */
    public static double[] harmonicRms(Complex[][] V, int n) {
        double[] rms = new double[n];
        if (V == null) return rms;
        for (int i = 0; i < n; i++) {
            double s = 0;
            for (int h = 0; h < V.length; h++) {
                Complex c = V[h][i];
                if (c == null) continue;
                if (h == 0) s += c.abs() * c.abs();
                else s += c.abs() * c.abs() / 2.0;
            }
            rms[i] = Math.sqrt(s);
        }
        return rms;
    }
}
