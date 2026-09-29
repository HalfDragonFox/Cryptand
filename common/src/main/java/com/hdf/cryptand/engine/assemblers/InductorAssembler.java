package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.InductorModel;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：电感（2026-08-30 引擎内置——纯 Java） =====
 * 电感 = InductorModel（感抗 + DCR 电阻 + 能量模型——磁能）。
 */
public final class InductorAssembler implements Assembler {

    private final double inductance;    // 电感（H）
    private final double dcrResistance; // 直流电阻（Ω）
    private final EnergyModel energy;
    private final ThermalModel thermal; // 可 null

    public InductorAssembler(double inductance, double dcrResistance,
                             EnergyModel energy, ThermalModel thermal) {
        this.inductance = Math.max(inductance, 0);
        this.dcrResistance = Math.max(dcrResistance, 0);
        this.energy = energy != null ? energy : new EnergyModel(1.0);
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "inductor"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new InductorModel(a, b, x, inductance, dcrResistance, energy, thermal));
    }

    @Override
    public void bind(Binding binding) {
        // 电感绑定（可选）
    }
}
