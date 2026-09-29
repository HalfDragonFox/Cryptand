package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.ThreePhaseTransformerModel;

/**
 * ===== 预制组装器：三相变压器（2026-08-30 引擎内置——交错电网抽象） =====
 * 三相变压器 = ThreePhaseTransformerModel（8 端子：原边 pa1/pa2/pa3/n1 + 副边
 * pb1/pb2/pb3/n2——匝比）。
 */
public final class ThreePhaseTransformerAssembler implements Assembler {

    private final double ratio;

    public ThreePhaseTransformerAssembler(double ratio) {
        this.ratio = ratio;
    }

    @Override public String feature() { return "three_phase_transformer"; }
    @Override public int terminalCount() { return 8; }

    @Override
    public void build(GraphBuilder g) {
        int pa1 = g.terminal(0), pa2 = g.terminal(1), pa3 = g.terminal(2), n1 = g.terminal(3);
        int pb1 = g.terminal(4), pb2 = g.terminal(5), pb3 = g.terminal(6), n2 = g.terminal(7);
        int x1 = g.addNode(), y1 = g.addNode(), k1 = g.addNode();
        g.addModel(new ThreePhaseTransformerModel(pa1, pa2, pa3, n1, pb1, pb2, pb3, n2,
                ratio, x1, y1, k1,
                g.addNode(), g.addNode(), g.addNode(), g.addNode(), g.addNode(), g.addNode()));
    }

    @Override public void bind(Binding binding) { }
}
