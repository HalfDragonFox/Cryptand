package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.DiodeModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：二极管（2026-08-30 引擎内置——交错电网抽象） =====
 */
public final class DiodeAssembler implements Assembler {

    private final double vth, rForward, rReverse;
    private final ThermalModel thermal; // 可 null

    public DiodeAssembler(double vth, double rForward, double rReverse, ThermalModel thermal) {
        this.vth = vth;
        this.rForward = rForward > 0 ? rForward : 0.01;
        this.rReverse = rReverse > 0 ? rReverse : 1e6;
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "diode"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1);
        g.addModel(new DiodeModel(a, b, vth, rForward, rReverse, thermal));
    }

    @Override
    public void bind(Binding binding) { }
}
