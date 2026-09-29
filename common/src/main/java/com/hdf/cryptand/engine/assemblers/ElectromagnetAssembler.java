package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：电磁铁（2026-08-30 引擎内置——交错电网抽象） =====
 * 电磁铁 = R-L 绕组（MotorModel——励磁线圈电阻 + 电感 + 温度——电磁吸力由
 * 电流决定——平台读取电流驱动磁力）。
 */
public final class ElectromagnetAssembler implements Assembler {

    private final double resistance, inductance;
    private final ThermalModel thermal;

    public ElectromagnetAssembler(double resistance, double inductance, ThermalModel thermal) {
        this.resistance = resistance > 0 ? resistance : 10.0;
        this.inductance = Math.max(inductance, 0);
        this.thermal = thermal != null ? thermal
                : new ThermalModel(4.0, 200.0, 298.15, 473.15);
    }

    @Override
    public String feature() { return "electromagnet"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new MotorModel(a, b, x, resistance, inductance, thermal));
    }

    @Override
    public void bind(Binding binding) { }
}
