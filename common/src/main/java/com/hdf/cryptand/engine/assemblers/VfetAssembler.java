package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.VfetModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：VFET（2026-08-30 引擎内置——交错电网抽象） =====
 * VFET = VfetModel（3 端子：漏极/源极/栅极——N/P 沟道 + 阈值电压）。
 */
public final class VfetAssembler implements Assembler {

    private final boolean nChannel;
    private final double vth;
    private final ThermalModel thermal;

    public VfetAssembler(boolean nChannel, double vth, ThermalModel thermal) {
        this.nChannel = nChannel;
        this.vth = vth;
        this.thermal = thermal;
    }

    @Override public String feature() { return "vfet"; }
    @Override public int terminalCount() { return 3; }

    @Override
    public void build(GraphBuilder g) {
        int drain = g.terminal(0), source = g.terminal(1), gate = g.terminal(2);
        g.addModel(new VfetModel(drain, source, gate, nChannel, vth, thermal));
    }

    @Override public void bind(Binding binding) { }
}
