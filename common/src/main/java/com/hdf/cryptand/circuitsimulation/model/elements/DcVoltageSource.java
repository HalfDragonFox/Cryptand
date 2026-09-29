package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.AbstractElement;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 直流电压源（戴维南 → 诺顿等效）。
 * 用内阻串联，内部转成 电导 + 电流源 参与 MNA，避免理想源需要额外行。
 * params: [voltage, seriesResistance]
 */
public class DcVoltageSource extends AbstractElement {

    @Override public boolean isSource() { return true; }
    /** ⚠ 2026-08-30 审计 M8 根因：电压从 final 改 volatile + setVoltage——
     *  发电机/可调 DC 源的 EMF 必须随转速/励磁变化（原 final 使 GeneratorModel.
     *  updateSpeed 无效，输出 EMF 恒初始值）。setter 发参数变化消息 → 重解。 */
    public volatile double voltage;
    public final double seriesResistance;

    /** 开路标志（2026-08-23 电压源开路：一端接下游而两端无闭合回路 → 不注入，
     *  避免诺顿等效经 GMin 假回路 → 假电流/温度爆炸；孤立源不标记保持开路电压） */
    private volatile boolean openCircuit;

    public void setOpenCircuit(boolean open) { this.openCircuit = open; }

    public boolean isOpenCircuit() { return openCircuit; }

    public DcVoltageSource(int a, int b, double voltage, double seriesResistance) {
        super(a, b);
        this.voltage = voltage;
        this.seriesResistance = Math.max(seriesResistance, 1e-9);
    }

    /** 更新电压（EMF 源/可调 DC 源）：参数变化 → 发送参数变化消息（重解不重建） */
    public void setVoltage(double v) {
        if (Double.compare(v, voltage) != 0) {
            voltage = v;
            notifyParamChanged();
        }
    }

    @Override
    public void stampReal(MnaBuilder m, double dt) {
        double g = 1.0 / seriesResistance;
        if (openCircuit) {
            m.addG(nodeA, nodeA, g);
            m.addG(nodeB, nodeB, g);
            m.addG(nodeA, nodeB, -g);
            m.addG(nodeB, nodeA, -g);
            return;
        }
        double i = voltage * g;               // 诺顿电流源，方向 a→b
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, i);
        m.addB(nodeB, -i);
    }

    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        // ⚠ 2026-08-30 审计：DC 源相量注入（DC/AC 一套计算拼图）——原实现
        // 无条件只内阻（注释只描述 AC 语义"DC 源对 AC 短路"），DC 网络
        // （omega<1 伪时域）也不注入 → 电池（BatteryAssembler→DcVoltageSource）
        // 在 DC 电路里只表现为内阻、不供电。补 omega<1.0 && !openCircuit 分支：
        // 诺顿注入 i=voltage×g（0 相位相量，DC=恒定电压），与 AcVoltageSource
        // 的相量注入统一为"相量源"概念。AC 网络（omega>=1）行为不变（只内阻，
        // 理想 DC 源对 AC 是零阻抗——真实物理）。
        double g = 1.0 / seriesResistance;
        m.addY(nodeA, nodeA, new Complex(g, 0));
        m.addY(nodeB, nodeB, new Complex(g, 0));
        m.addY(nodeA, nodeB, new Complex(-g, 0));
        m.addY(nodeB, nodeA, new Complex(-g, 0));
        // DC 网络（伪时域）：注入 DC 电压为 0 相位相量（诺顿，方向 a→b）
        if (omega < 1.0 && !openCircuit) {
            double i = voltage * g;
            m.addB(nodeA, new Complex(i, 0));
            m.addB(nodeB, new Complex(-i, 0));
        }
    }

    // ===== float 求解器 =====

    @Override
    public boolean supportsFloatReal() { return true; }

    @Override
    public boolean supportsFloatComplex() { return true; }

    @Override
    public void stampRealFloat(FloatMnaBuilder m, double dt, double t) {
        float g = (float) (1.0 / seriesResistance);
        if (openCircuit) {
            m.addG(nodeA, nodeA, g);
            m.addG(nodeB, nodeB, g);
            m.addG(nodeA, nodeB, -g);
            m.addG(nodeB, nodeA, -g);
            return;
        }
        float i = (float) (voltage * g);
        m.addG(nodeA, nodeA, g);
        m.addG(nodeB, nodeB, g);
        m.addG(nodeA, nodeB, -g);
        m.addG(nodeB, nodeA, -g);
        m.addB(nodeA, i);
        m.addB(nodeB, -i);
    }

    @Override
    public void stampComplexFloat(FloatComplexMnaBuilder m, double omega) {
        // ⚠ 2026-08-30 审计：与 double 版一致——DC 网络（omega<1）注入 DC 电压
        // （0 相位相量），AC 网络只内阻。
        float g = (float) (1.0 / seriesResistance);
        m.addY(nodeA, nodeA, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(g, 0));
        m.addY(nodeB, nodeB, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(g, 0));
        m.addY(nodeA, nodeB, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(-g, 0));
        m.addY(nodeB, nodeA, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(-g, 0));
        if (omega < 1.0 && !openCircuit) {
            float i = (float) (voltage * g);
            m.addB(nodeA, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(i, 0));
            m.addB(nodeB, new com.hdf.cryptand.circuitsimulation.solver.FloatComplex(-i, 0));
        }
    }

    @Override
    public ElementType type() { return ElementType.DC_VOLTAGE_SOURCE; }

    @Override
    public double[] params() { return new double[]{voltage, seriesResistance}; }
}
