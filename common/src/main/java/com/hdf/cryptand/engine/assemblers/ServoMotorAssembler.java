package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.ServoMotorModel;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：伺服电机（2026-08-30 引擎内置——交错电网抽象） =====
 * 伺服 = ServoMotorModel（R-L 绕组 + 位置/速度控制——目标角度由平台驱动）。
 */
public final class ServoMotorAssembler implements Assembler {

    private final double resistance, inductance;
    private final ThermalModel thermal;

    public ServoMotorAssembler(double resistance, double inductance, ThermalModel thermal) {
        this.resistance = resistance > 0 ? resistance : 2.0;
        this.inductance = Math.max(inductance, 0);
        this.thermal = thermal != null ? thermal
                : new ThermalModel(4.0, 200.0, 298.15, 473.15);
    }

    @Override public String feature() { return "servo_motor"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new ServoMotorModel(a, b, x, resistance, inductance,
                thermal, new EnergyModel(1.0), false, 0.0));
    }

    @Override public void bind(Binding binding) { }
}
