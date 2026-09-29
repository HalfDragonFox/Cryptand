package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.MeterModel;

/**
 * ===== 预制组装器：仪表（2026-08-30 引擎内置——交错电网抽象） =====
 * 仪表（万用表/功率计）= MeterModel（3 端子：series 低阻串联测流 + shunt 高阻
 * 分流测压）。
 */
public final class MeterAssembler implements Assembler {

    private final double seriesR, shuntR;

    public MeterAssembler(double seriesR, double shuntR) {
        this.seriesR = Math.max(seriesR, 1e-9);
        this.shuntR = Math.max(shuntR, 1e3);
    }

    @Override
    public String feature() { return "meter"; }

    @Override
    public int terminalCount() { return 3; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), c = g.terminal(2);
        g.addModel(new MeterModel(a, b, c, seriesR, shuntR));
    }

    @Override
    public void bind(Binding binding) { }
}
