package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.SemiconductorElement;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 半导体复合模型基类（2026-08-12：原版三极管/二极管/电子管等纳入本架构）。
 * <p>
 * 组合半导体基础元件（DiodeElement/BjtElement/VfetElement/ElectronTubeElement，
 * 三端子 + 非线性 + 时间参数）：
 *   - 统一复合元件生命周期（reset/损耗/update）
 *   - 温度模型（ThermalDevice）：结温推进 → 同步到内部半导体元件
 *     （Vth 温度系数 -2mV/K）
 *   - 时间参数：update 里把 nowNanos 同步为元件仿真时间 t
 *   - 控制端电压（基极/栅极）：{@link #setControlVoltage} 由外部求解循环用
 *     完整节点电压数组设置（三端子工作区判定）
 * <p>
 * 子类只需：组合元件、nodeA/nodeB、lossPower、setControlVoltage。
 */
public abstract class SemiconductorModel extends CompositeModel implements ThermalDevice {

    /** 端口节点（主通道 a-b） */
    public final int nodeA, nodeB;
    /** 温度模型（可 null） */
    public final ThermalModel thermal;

    protected SemiconductorModel(Element[] simple, ThermalModel thermal,
                                 int nodeA, int nodeB) {
        super(simple, thermal);
        this.nodeA = nodeA;
        this.nodeB = nodeB;
        this.thermal = thermal;
    }

    @Override public int nodeA() { return nodeA; }
    @Override public int nodeB() { return nodeB; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 设置控制端电压（基极/栅极，三端子工作区判定）；两端口元件默认无操作 */
    public void setControlVoltage(double v) {}

    /** 同步结温 + 时间参数到所有内部半导体元件 */
    protected void syncJunction(long nowNanos) {
        double tc = thermal == null ? 20.0 : thermal.tempCelsius();
        double tSec = nowNanos / 1e9;
        for (Element e : simple) {
            if (e instanceof SemiconductorElement se) {
                se.temperatureCelsius = tc;
                se.t = tSec;
            }
        }
    }

    /** 更新：温度推进（解析解）+ 结温/时间同步（统一生命周期） */
    @Override
    public void update(Complex va, Complex vb, double omega, long nowNanos) {
        applyBinding();
        if (thermal != null) {
            thermal.advance(lossPower(va, vb, omega), nowNanos);
        }
        syncJunction(nowNanos);
    }

    @Override
    public void reset() {
        super.reset();
        if (thermal != null) thermal.reset();
    }
}
