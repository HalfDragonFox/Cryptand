package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.ConstantSpeedMotorModel;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：恒速电机（2026-08-30 引擎内置——交错电网抽象） =====
 * 恒速电机 = ConstantSpeedMotorModel（恒定转速——EMF 恒定——转速不随负载变）。
 */
public final class ConstantSpeedMotorAssembler implements Assembler {

    private final double resistance, inductance;
    private final ThermalModel thermal;
    private final boolean generatorMode;
    private final double emfVoltage;

    public ConstantSpeedMotorAssembler(double resistance, double inductance,
                                       ThermalModel thermal, boolean generatorMode,
                                       double emfVoltage) {
        this.resistance = resistance > 0 ? resistance : 2.0;
        this.inductance = Math.max(inductance, 0);
        this.thermal = thermal != null ? thermal
                : new ThermalModel(4.0, 200.0, 298.15, 473.15);
        this.generatorMode = generatorMode;
        this.emfVoltage = emfVoltage;
    }

    @Override
    public String feature() { return generatorMode ? "constant_speed_generator" : "constant_speed_motor"; }

    @Override
    public boolean isSource() { return generatorMode; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new ConstantSpeedMotorModel(a, b, x, resistance, inductance,
                thermal, new EnergyModel(1.0), generatorMode, emfVoltage));
    }

    @Override
    public void bind(Binding binding) { }
}
