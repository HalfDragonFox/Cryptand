package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.ThreePhaseSourceModel;

/**
 * ===== 预制组装器：三相源（2026-08-30 引擎内置——交错电网抽象） =====
 * 三相源 = ThreePhaseSourceModel（4 端子：a/b/c 三相 + n 中性——幅值/内阻）。
 */
public final class ThreePhaseSourceAssembler implements Assembler {

    private final double amplitude, sourceR;

    public ThreePhaseSourceAssembler(double amplitude, double sourceR) {
        this.amplitude = amplitude;
        this.sourceR = sourceR;
    }

    @Override public String feature() { return "three_phase_source"; }
    @Override public boolean isSource() { return true; }
    @Override public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), c = g.terminal(2), n = g.terminal(3);
        g.addModel(new ThreePhaseSourceModel(a, b, c, n, amplitude, sourceR));
    }

    @Override public void bind(Binding binding) { }
}
