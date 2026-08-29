package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.AbstractElement;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplex;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/** 理想电流源（方向 a→b，电流为正则从 a 流向 b）。
 *  2026-08-15 多 AC 源修复：加 frequency（0=跟随网络主导频率；&gt;0=固定频率
 *  PLC/多频电流源，只在匹配频率注入）。
 *  2026-08-20 求解器原生开路支持（用户要求：开路分支在求解时计算，不并联
 *  参考电阻 hack）：理想电流源裸注入到悬空端（无闭合回路）会由 GMin 决定
 *  电压 → 巨大值（100MV 实测）。求解器（ComplexMnaSolver/MergedNetworkSolver）
 *  在 stamp 前检测每个电流源两端是否通过其他元件连通 → 不连通（开路）→
 *  setOpenCircuit(true) → stamp 不注入（开路电流源 = 断路，两端等电位/悬空
 *  由 GMin 兜底，不发散）。电压源（AcVoltageSource 诺顿等效）开路天然 = 源
 *  电压，无需处理。 */
public class CurrentSource extends AbstractElement {

    @Override public boolean isSource() { return true; }
    /** 电流值（volatile：求解线程读 / 主线程刷新；参数变化经 setter 发消息） */
    public volatile double current;
    /** 源频率（Hz）：0 = 跟随网络主导频率（工频源）；&gt;0 = 固定频率（多频电流源） */
    public final double frequency;
    /** 开路标志（求解器 stamp 前检测设置）：两端无闭合回路 → 不注入。
     *  非参数变化（结构属性），setter 不触发参数变化消息。 */
    private volatile boolean openCircuit;

    /** 求解器开路检测设置：true = 两端无真实闭合回路 → stamp 不注入 */
    public void setOpenCircuit(boolean open) { this.openCircuit = open; }

    public boolean isOpenCircuit() { return openCircuit; }

    public CurrentSource(int a, int b, double current) {
        this(a, b, current, 0);
    }

    public CurrentSource(int a, int b, double current, double frequency) {
        super(a, b);
        this.current = current;
        this.frequency = Math.max(frequency, 0);
    }

    /** 更新电流值：参数变化 → 发送参数变化消息（接收方更新求解，不重建网络） */
    public void setCurrent(double c) {
        if (Double.compare(c, current) != 0) {
            current = c;
            notifyParamChanged();
        }
    }

    @Override
    public void stampReal(MnaBuilder m, double dt) {
        if (openCircuit) return; // 开路电流源 = 断路（无电流）
        m.addB(nodeA, current);
        m.addB(nodeB, -current);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        if (openCircuit) return; // 开路电流源 = 断路（无电流）
        // 多频：固定频率源在非工作频率 → 理想电流源开路（不注入）
        double targetF = omega / (2 * Math.PI);
        if (frequency > 0 && Math.abs(frequency - targetF) > 1e-6) {
            return;
        }
        m.addB(nodeA, new Complex(current, 0));
        m.addB(nodeB, new Complex(-current, 0));
    }

    // ===== 多频叠加（2026-08-15 多 AC 源修复） =====

    @Override
    public double sourceFrequency() { return frequency; }

    @Override
    public boolean activeAt(double omega, double dominantFreq) {
        double targetF = omega / (2 * Math.PI);
        if (frequency > 0) return Math.abs(frequency - targetF) < 1e-6;
        // 跟随源：只在主导频率注入（不污染载波频率）
        return Math.abs(dominantFreq - targetF) < 1e-6;
    }

    @Override
    public void stampComplexPassive(ComplexMnaBuilder m, double omega) {
        // 非工作频率：理想电流源 = 开路（不注入）
    }

    // ===== float 求解器 =====

    @Override
    public boolean supportsFloatReal() { return true; }

    @Override
    public boolean supportsFloatComplex() { return true; }

    @Override
    public void stampRealFloat(FloatMnaBuilder m, double dt, double t) {
        if (openCircuit) return; // 开路电流源 = 断路（无电流）
        float i = (float) current;
        m.addB(nodeA, i);
        m.addB(nodeB, -i);
    }

    @Override
    public void stampComplexFloat(FloatComplexMnaBuilder m, double omega) {
        if (openCircuit) return; // 开路电流源 = 断路（无电流）
        double targetF = omega / (2 * Math.PI);
        if (frequency > 0 && Math.abs(frequency - targetF) > 1e-6) {
            return;
        }
        m.addB(nodeA, new FloatComplex((float) current, 0));
        m.addB(nodeB, new FloatComplex((float) -current, 0));
    }

    @Override
    public ElementType type() { return ElementType.CURRENT_SOURCE; }

    @Override
    public double[] params() { return new double[]{current, frequency}; }
}
