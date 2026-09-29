package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.InductionMotorModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：感应电机（2026-08-30 引擎内置——交错电网抽象） =====
 * 单相异步电机 = InductionMotorModel（极数参数化——2/4/6/8 极；n_s=120f/p；
 * 额定功率/转差/转矩曲线——现实参数建模）。
 */
public final class InductionMotorAssembler implements Assembler {

    private final int poles;          // 极数（2/4/6/8）
    private final double resistance, inductance;
    private final ThermalModel thermal;
    private final double ratedPowerW; // 额定功率（W）

    public InductionMotorAssembler(int poles, double resistance, double inductance,
                                   ThermalModel thermal, double ratedPowerW) {
        this.poles = (poles == 2 || poles == 4 || poles == 6 || poles == 8) ? poles : 4;
        this.resistance = resistance > 0 ? resistance : 25.6;
        this.inductance = Math.max(inductance, 0);
        this.thermal = thermal != null ? thermal
                : new ThermalModel(4.0, 200.0, 298.15, 473.15);
        this.ratedPowerW = ratedPowerW > 0 ? ratedPowerW : 2000.0;
    }

    @Override
    public String feature() { return "induction_motor"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new InductionMotorModel(poles, a, b, x, resistance, inductance,
                thermal,
                new com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel(1.0),
                false, 0.0)); // 电机模式（非发电）；反电动势由引擎 EMF=Kω 推进
    }

    @Override
    public void bind(Binding binding) { }
}
