package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.VariacModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：自耦变压器（2026-08-30 引擎内置——交错电网抽象） =====
 * 自耦变压器 = VariacModel（4 端子：a-b 主线圈 + x-k 抽头——可调匝比）。
 */
public final class VariacAssembler implements Assembler {

    private final double primaryStray, mutual, ratio;
    private final ThermalModel thermal;

    public VariacAssembler(double primaryStray, double mutual, double ratio, ThermalModel thermal) {
        this.primaryStray = primaryStray;
        this.mutual = mutual;
        this.ratio = ratio;
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "variac"; }

    @Override
    public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.terminal(2), k = g.terminal(3);
        g.addModel(new VariacModel(a, b, x, k, primaryStray, mutual, ratio, thermal));
    }

    @Override
    public void bind(Binding binding) { }
}
