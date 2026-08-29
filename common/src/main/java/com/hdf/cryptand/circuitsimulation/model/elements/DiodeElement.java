package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.NonlinearPhasorElement;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 二极管（2026-08-12 纳入本架构：PowerGrid PNJunctionWire 等效）。
 * <p>
 * 非线性 PN 结，分段线性近似：
 *   - 正向导通（vPrev &gt; Vth）：小电阻 RF + 阈值电压 Vth（理想二极管）
 *   - 反向截止（vPrev &le; Vth）：大电阻 RO（漏电流）
 *   - 温度：Vth 随结温变化（-2mV/K）
 * <p>
 * 【时间参数】stampRealAt(m, dt, t)：用上一轮电压 vPrev 判定导通/截止
 * （迭代稳定），t 记录仿真时间。AC 相量下退化为小信号电导（按工作点）。
 * <p>
 * 【相量非线性（2026-08-15）】实现 {@link NonlinearPhasorElement}：分段线性化
 * （工作点切线外推）、谐波平衡（时域采样电流）、动态相量（工作点导纳）
 * 三种算法共用——非线性直接相量计算。
 */
public class DiodeElement extends SemiconductorElement implements NonlinearPhasorElement {

    /** 阈值电压（V，25°C）——硅二极管典型 0.7V */
    public final double vth;
    /** 正向导通电阻（Ω）、反向截止电阻（Ω）——魔法数字 */
    public final double rForward, rReverse;

    public DiodeElement(int a, int b, double vth, double rForward, double rReverse) {
        super(a, b, -1);
        this.vth = vth;
        this.rForward = Math.max(rForward, 1e-6);
        this.rReverse = Math.max(rReverse, 1e6);
    }

    /** 默认硅二极管（Vth=0.7V，RF=0.05Ω，RO=1MΩ） */
    public DiodeElement(int a, int b) {
        this(a, b, 0.7, 0.05, 1_000_000.0);
    }

    @Override
    public void stampRealAt(MnaBuilder m, double dt, double t) {
        this.t = t;
        double vthT = vthAtTemp(vth); // 温度修正阈值
        boolean forward = vPrev > vthT; // 时间相关状态：上一轮电压判工作区
        double r = forward ? rForward : rReverse;
        double g = 1.0 / r;
        m.addG(a, a, g);
        m.addG(b, b, g);
        m.addG(a, b, -g);
        m.addG(b, a, -g);
        if (forward) {
            // 理想二极管：导通时钳位 Vth（等效串联电压源 → 电流源）
            double i = vthT * g;
            m.addB(a, i);
            m.addB(b, -i);
        }
    }

    // ===== 相量非线性（2026-08-15 三种相量算法共用） =====

    /** 工作点导纳：导通 → 1/RF，截止 → 1/RO（用工作点电压实部判工作区） */
    @Override
    public Complex equivalentAdmittance(Complex v, double omega) {
        double vthT = vthAtTemp(vth);
        double g = v.re > vthT ? 1.0 / rForward : 1.0 / rReverse;
        return new Complex(g, 0);
    }

    /** 切线补偿源：导通 I0 = -Vth/RF（I = g·V - g·Vth），截止 0 */
    @Override
    public Complex equivalentCurrent(Complex v, double omega) {
        double vthT = vthAtTemp(vth);
        if (v.re > vthT) {
            return new Complex(-vthT / rForward, 0);
        }
        return Complex.ZERO;
    }

    /** 时域电流采样（谐波平衡 DFT 用）：导通 (v-Vth)/RF，截止 v/RO */
    @Override
    public double[] timeDomainCurrent(double[] vSamples, double omega) {
        double vthT = vthAtTemp(vth);
        double[] out = new double[vSamples.length];
        for (int m = 0; m < vSamples.length; m++) {
            double vm = vSamples[m];
            out[m] = vm > vthT ? (vm - vthT) / rForward : vm / rReverse;
        }
        return out;
    }

    @Override
    public String toString() {
        return "DiodeElement{" + a + "-" + b + " Vth=" + vth + " fwd=" + forward() + "}";
    }

    private boolean forward() { return vPrev > vthAtTemp(vth); }
}
