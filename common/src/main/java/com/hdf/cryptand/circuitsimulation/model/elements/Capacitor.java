package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.AbstractElement;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 电容（Backward Euler 伴随模型，时域瞬态）。
 *   G = C / dt, I_hist = C/dt * vPrev
 * 每个时间步解完后用 commit() 更新 vPrev。
 * 相量模式（AC）用 Y = jωC，不使用状态。
 * <p>2026-08-21 伪时域充电：DC/低频（omega 小）相量求解里也用 Backward Euler
 * 伴随（G=C/dt + I_hist=vPrev×C/dt 实部）——相量下也能逐节拍充电（现实充电：
 * 大电流→指数衰减→存电）；AC 用 Y=jωC（隔直通交）。simDt 为伪时域节拍
 * （由 PhasorEngine 每节拍设置，volatile 求解线程读）。
 * params: [capacitance, vPrev]
 */
public class Capacitor extends AbstractElement {
    /** 电容值（volatile：求解线程读 / 主线程刷新；参数变化经 setter 发消息） */
    public volatile double capacitance;
    public double vPrev;
    /** 伪时域节拍（s；Backward Euler 充电用，PhasorEngine 每节拍设置） */
    public volatile double simDt = 0.05;
    /** 孤立电容（两端无闭合回路，2026-08-21）：开路不注入（G=0/I_hist=0），
     *  vPrev 保持（电荷由 advanceState 自放电节流管理）——剪线后不假电流/不爆炸。 */
    public volatile boolean openCircuit;

    public Capacitor(int a, int b, double capacitance) {
        super(a, b);
        this.capacitance = Math.max(capacitance, 1e-12);
    }

    /** 更新电容值：参数变化 → 发送参数变化消息（接收方更新求解，不重建网络） */
    public void setCapacitance(double c) {
        double nc = Math.max(c, 1e-12);
        if (Double.compare(nc, capacitance) != 0) {
            capacitance = nc;
            notifyParamChanged();
        }
    }

    /** 带能量状态（电荷/储能）→ 非线性（2026-08-15） */
    @Override
    public boolean isNonlinear() { return true; }

    @Override
    public void stampReal(MnaBuilder m, double dt) {
        if (openCircuit) return; // 孤立：开路不注入（保持 vPrev，不假电流）
        double g = capacitance / dt;
        double iHist = vPrev * g;
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, iHist);
        m.addB(nodeB, -iHist);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        // 2026-08-21 伪时域充电：DC/低频（omega 小，< 1 ≈ 0.16Hz）用 Backward
        // Euler 伴随（G=C/simDt + I_hist=vPrev×C/simDt，实部）——相量下逐节拍
        // 充电（大电流→指数衰减→存电，符合现实）。AC 用 Y=jωC（隔直通交）。
        if (omega < 1.0) {
            if (openCircuit) return; // 孤立：开路不注入（保持 vPrev，不假电流）
            double g = capacitance / Math.max(simDt, 1e-3);
            Complex iHist = new Complex(vPrev * g, 0);
            m.addY(nodeA, nodeA, new Complex(g, 0));
            m.addY(nodeB, nodeB, new Complex(g, 0));
            m.addY(nodeA, nodeB, new Complex(-g, 0));
            m.addY(nodeB, nodeA, new Complex(-g, 0));
            m.addB(nodeA, iHist);
            m.addB(nodeB, iHist.neg());
            return;
        }
        if (openCircuit) return; // 孤立：AC 也开路
        double xc = 1.0 / (omega * capacitance);       // 容抗
        Complex y = new Complex(0, 1.0 / xc);          // Y = jωC
        m.addY(nodeA, nodeA, y);
        m.addY(nodeB, nodeB, y);
        m.addY(nodeA, nodeB, y.neg());
        m.addY(nodeB, nodeA, y.neg());
    }

    @Override
    public void commit(double va, double vb, double dt) {
        if (openCircuit) return; // 孤立：保持 vPrev（电荷由 advanceState 自放电管理）
        vPrev = va - vb;
    }

    // ===== float 求解器（魔法数字 double 计算后转 float；状态 vPrev 保持 double） =====

    @Override
    public boolean supportsFloatReal() { return true; }

    @Override
    public boolean supportsFloatComplex() { return true; }

    @Override
    public void stampRealFloat(FloatMnaBuilder m, double dt, double t) {
        float g = (float) (capacitance / dt);
        float iHist = (float) (vPrev * g);
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, iHist);
        m.addB(nodeB, -iHist);
    }

    @Override
    public void stampComplexFloat(FloatComplexMnaBuilder m, double omega) {
        float yv = (float) (omega * capacitance); // Y = jωC
        m.addY(nodeA, nodeA, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(0, yv));
        m.addY(nodeB, nodeB, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(0, yv));
        m.addY(nodeA, nodeB, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(0, -yv));
        m.addY(nodeB, nodeA, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(0, -yv));
    }

    @Override
    public void commitFloat(float va, float vb, double dt) {
        vPrev = va - vb; // 状态保持 double
    }

    @Override
    public ElementType type() { return ElementType.CAPACITOR; }

    @Override
    public double[] params() { return new double[]{capacitance, vPrev}; }
}
