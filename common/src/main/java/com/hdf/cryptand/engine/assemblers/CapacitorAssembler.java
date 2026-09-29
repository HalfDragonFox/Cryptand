package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：电容（2026-08-30 引擎内置——纯 Java） =====
 * 电容 = CapacitorModel（容抗 + ESR + 能量模型——电荷积分驱动电压——隔直通）。
 */
public final class CapacitorAssembler implements Assembler {

    private final double capacitance;   // 电容（F）
    private final double esrResistance; // 等效串联电阻（Ω）
    private final EnergyModel energy;
    private final ThermalModel thermal; // 可 null

    public CapacitorAssembler(double capacitance, double esrResistance,
                              EnergyModel energy, ThermalModel thermal) {
        this.capacitance = capacitance > 0 ? capacitance : 1e-4;
        this.esrResistance = Math.max(esrResistance, 0);
        this.energy = energy != null ? energy : new EnergyModel(capacitance > 0 ? capacitance : 1e-4);
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "capacitor"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new CapacitorModel(a, b, x, capacitance, esrResistance, energy, thermal));
    }

    @Override
    public void bind(Binding binding) {
        // 电容绑定（可选）
    }
}
