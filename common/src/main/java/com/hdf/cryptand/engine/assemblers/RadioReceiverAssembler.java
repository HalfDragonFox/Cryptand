package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.RadioReceiverModel;

/**
 * ===== 预制组装器：无线电接收机（2026-08-30 引擎内置——交错电网抽象） =====
 * 接收机 = RadioReceiverModel（4 端子：天线 ant/ret → 输出 out/gnd——调谐
 * LC + 检波负载）。
 */
public final class RadioReceiverAssembler implements Assembler {

    private final double tuneL, tuneC, inputR, detectLoadR;

    public RadioReceiverAssembler(double tuneL, double tuneC, double inputR, double detectLoadR) {
        this.tuneL = tuneL;
        this.tuneC = tuneC;
        this.inputR = inputR;
        this.detectLoadR = detectLoadR;
    }

    @Override
    public String feature() { return "radio_rx"; }

    @Override
    public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int ant = g.terminal(0), ret = g.terminal(1);
        int out = g.terminal(2), gnd = g.terminal(3), x = g.addNode();
        g.addModel(new RadioReceiverModel(ant, ret, out, gnd, x, tuneL, tuneC, inputR, detectLoadR));
    }

    @Override
    public void bind(Binding binding) { }
}
