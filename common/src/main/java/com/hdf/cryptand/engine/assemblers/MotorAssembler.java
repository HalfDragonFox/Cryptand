package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.DcBrushedMotorModel;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：直流电机（2026-08-30 引擎内置——纯 Java） =====
 * 直流电机 = DcBrushedMotorModel（电枢 R-L + 反电动势 EMF=Kω + 机械/温度模型）——
 * 数学公式建模（黑盒 set/compute/get）。
 */
public final class MotorAssembler implements Assembler {

    private final double resistance;    // 电枢电阻（Ω）
    private final double inductance;    // 电枢电感（H）
    private final ThermalModel thermal;
    private final EnergyModel energy;
    private final boolean generatorMode;
    private final double emfVoltage;    // 发电机模式：反电动势（V）

    public MotorAssembler(double resistance, double inductance, ThermalModel thermal,
                          EnergyModel energy, boolean generatorMode, double emfVoltage) {
        this.resistance = resistance > 0 ? resistance : 2.0;
        this.inductance = Math.max(inductance, 0);
        this.thermal = thermal != null ? thermal
                : new ThermalModel(4.0, 200.0, 298.15, 473.15);
        this.energy = energy != null ? energy : new EnergyModel(1.0);
        this.generatorMode = generatorMode;
        this.emfVoltage = emfVoltage;
    }

    @Override
    public String feature() { return generatorMode ? "generator" : "motor"; }

    @Override
    public boolean isSource() { return generatorMode; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new DcBrushedMotorModel(a, b, x, resistance, inductance,
                thermal, energy, generatorMode, emfVoltage));
    }

    @Override
    public void bind(Binding binding) {
        // 电机绑定（可选）
    }
}
