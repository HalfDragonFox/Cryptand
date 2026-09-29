package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.IdealTransformerModel;

/**
 * ===== 预制组装器：理想变压器（2026-08-30 引擎内置——交错电网抽象） =====
 * 变压器 = 理想变压器复合模型（原边 a1-a2 + 副边 b1-b2——磁耦合——互感 + 匝比）。
 * 4 端子设备（原边 2 + 副边 2）。
 */
public final class TransformerAssembler implements Assembler {

    private final double l1, l2, mutual, ratio; // 原/副自感、互感、匝比

    public TransformerAssembler(double l1, double l2, double mutual, double ratio) {
        this.l1 = l1;
        this.l2 = l2;
        this.mutual = mutual;
        this.ratio = ratio;
    }

    @Override
    public String feature() { return "transformer"; }

    @Override
    public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int a1 = g.terminal(0), a2 = g.terminal(1); // 原边
        int b1 = g.terminal(2), b2 = g.terminal(3); // 副边
        int x = g.addNode(), y = g.addNode(), k = g.addNode();
        g.addModel(new IdealTransformerModel(a1, a2, b1, b2, l1, l2, mutual, ratio, x, y, k));
    }

    @Override
    public void bind(Binding binding) { }
}
