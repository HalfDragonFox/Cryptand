package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.OscillatorModel;

/**
 * ===== 预制组装器：振荡器（2026-08-30 引擎内置——交错电网抽象） =====
 * 振荡器 = OscillatorModel（LC 谐振 + 损耗 + 反馈负阻——自激振荡）。
 */
public final class OscillatorAssembler implements Assembler {

    private final double inductance, capacitance, lossR, feedbackNegR;

    public OscillatorAssembler(double inductance, double capacitance,
                               double lossR, double feedbackNegR) {
        this.inductance = inductance;
        this.capacitance = capacitance;
        this.lossR = lossR;
        this.feedbackNegR = feedbackNegR;
    }

    @Override
    public String feature() { return "oscillator"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new OscillatorModel(a, b, x, inductance, capacitance, lossR, feedbackNegR));
    }

    @Override
    public void bind(Binding binding) { }
}
