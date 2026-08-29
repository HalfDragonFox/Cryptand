package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.AbstractElement;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 电感（Backward Euler 伴随模型，时域瞬态）。
 *   G = dt / L, I_hist = iPrev
 * 每个时间步解完后用 commit() 更新 iPrev。
 * 相量模式（AC）用 Y = 1/(jωL)，不使用状态。
 * <p>2026-08-21 伪时域：DC/低频（omega 小）相量求解里也用 Backward Euler
 * 伴随（G=dt/L + I_hist=iPrev 实部）——电感在 DC 相量下不再是短路
 * （ω=0 → Y=1/(jωL)=∞ 会注入超大电流 600A+），逐节拍按电流记忆演化
 * （与 REAL_DC 时域行为一致）。simDt 为伪时域节拍（PhasorEngine 设置）。
 * params: [inductance, iPrev]
 */
public class Inductor extends AbstractElement {
    /** 电感值（volatile：求解线程读 / 主线程刷新；参数变化经 setter 发消息） */
    public volatile double inductance;
    public double iPrev;
    /** 伪时域节拍（s；Backward Euler 用，PhasorEngine 每节拍设置） */
    public volatile double simDt = 0.05;

    public Inductor(int a, int b, double inductance) {
        super(a, b);
        this.inductance = Math.max(inductance, 1e-12);
    }

    /** 更新电感值：参数变化 → 发送参数变化消息（接收方更新求解，不重建网络） */
    public void setInductance(double l) {
        double nl = Math.max(l, 1e-12);
        if (Double.compare(nl, inductance) != 0) {
            inductance = nl;
            notifyParamChanged();
        }
    }

    /** 带能量状态（磁链/电流记忆）→ 非线性（2026-08-15） */
    @Override
    public boolean isNonlinear() { return true; }

    @Override
    public void stampReal(MnaBuilder m, double dt) {
        double g = dt / inductance;
        double iHist = iPrev;
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        // 电感电流从 nodeA 流入 nodeB，历史源注入 nodeB（与电容相反）
        m.addB(nodeA, -iHist);
        m.addB(nodeB, iHist);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        // 2026-08-21 伪时域：DC/低频（omega < 1 ≈ 0.16Hz）用 Backward Euler
        // 伴随（G=dt/L + I_hist=iPrev 实部）——电感在 DC 相量下不短路
        // （ω=0 → Y=1/(jωL)=∞ 会注入超大电流 600A+），逐节拍按电流记忆演化。
        if (omega < 1.0) {
            double g = Math.max(simDt, 1e-3) / inductance;
            Complex iHist = new Complex(iPrev, 0);
            m.addY(nodeA, nodeA, new Complex(g, 0));
            m.addY(nodeB, nodeB, new Complex(g, 0));
            m.addY(nodeA, nodeB, new Complex(-g, 0));
            m.addY(nodeB, nodeA, new Complex(-g, 0));
            m.addB(nodeA, iHist.neg());
            m.addB(nodeB, iHist);
            return;
        }
        double xl = omega * inductance;                // 感抗
        Complex y = new Complex(0, -1.0 / xl);         // Y = 1/(jωL) = -j/(ωL)
        m.addY(nodeA, nodeA, y);
        m.addY(nodeB, nodeB, y);
        m.addY(nodeA, nodeB, y.neg());
        m.addY(nodeB, nodeA, y.neg());
    }

    @Override
    public void commit(double va, double vb, double dt) {
        // i(t) = i(t-dt) + (dt/L)*(va-vb)
        iPrev += ((va - vb) / inductance) * dt;
    }

    // ===== float 求解器（魔法数字 double 计算后转 float；状态 iPrev 保持 double） =====

    @Override
    public boolean supportsFloatReal() { return true; }

    @Override
    public boolean supportsFloatComplex() { return true; }

    @Override
    public void stampRealFloat(FloatMnaBuilder m, double dt, double t) {
        float g = (float) (dt / inductance);
        float iHist = (float) iPrev;
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, -iHist);
        m.addB(nodeB, iHist);
    }

    @Override
    public void stampComplexFloat(FloatComplexMnaBuilder m, double omega) {
        float yv = (float) (1.0 / (omega * inductance)); // Y = 1/(jωL) = -j/(ωL)
        m.addY(nodeA, nodeA, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(0, -yv));
        m.addY(nodeB, nodeB, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(0, -yv));
        m.addY(nodeA, nodeB, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(0, yv));
        m.addY(nodeB, nodeA, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(0, yv));
    }

    @Override
    public void commitFloat(float va, float vb, double dt) {
        // 状态保持 double（长期积分精度），输入电压转 float 求解
        iPrev += ((va - vb) / inductance) * dt;
    }

    @Override
    public ElementType type() { return ElementType.INDUCTOR; }

    @Override
    public double[] params() { return new double[]{inductance, iPrev}; }
}
