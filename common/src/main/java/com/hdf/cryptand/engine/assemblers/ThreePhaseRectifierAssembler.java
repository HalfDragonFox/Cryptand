package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.ThreePhaseRectifierModel;

/**
 * ===== 预制组装器：三相整流器（2026-08-30 引擎内置——交错电网抽象） =====
 * 三相整流 = ThreePhaseRectifierModel（6 端子：ia/ib/ic 三相入 + nin + dout/ngnd 直流出）。
 */
public final class ThreePhaseRectifierAssembler implements Assembler {

    private final double phaseR, filterCap, loadR;

    public ThreePhaseRectifierAssembler(double phaseR, double filterCap, double loadR) {
        this.phaseR = phaseR;
        this.filterCap = filterCap;
        this.loadR = loadR;
    }

    @Override public String feature() { return "three_phase_rectifier"; }
    @Override public int terminalCount() { return 6; }

    @Override
    public void build(GraphBuilder g) {
        int ia = g.terminal(0), ib = g.terminal(1), ic = g.terminal(2);
        int nin = g.terminal(3), dout = g.terminal(4), ngnd = g.terminal(5);
        g.addModel(new ThreePhaseRectifierModel(ia, ib, ic, nin, dout, ngnd,
                phaseR, filterCap, loadR));
    }

    @Override public void bind(Binding binding) { }
}
