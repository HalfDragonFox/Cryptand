package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.InverterModel;

/**
 * ===== 预制组装器：逆变器（2026-08-30 引擎内置——交错电网抽象） =====
 * 逆变器 = InverterModel（4 端子：直流入 din/gnd → 交流出 oout/oret——输出
 * 幅值/频率/相位参数化）。
 */
public final class InverterAssembler implements Assembler {

    private final double inputR, busCap, outputR, outAmplitude, outFreq, outPhaseDeg;

    public InverterAssembler(double inputR, double busCap, double outputR,
                             double outAmplitude, double outFreq, double outPhaseDeg) {
        this.inputR = inputR;
        this.busCap = busCap;
        this.outputR = outputR;
        this.outAmplitude = outAmplitude;
        this.outFreq = outFreq;
        this.outPhaseDeg = outPhaseDeg;
    }

    @Override
    public String feature() { return "inverter"; }

    @Override
    public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int din = g.terminal(0), gnd = g.terminal(1);
        int oout = g.terminal(2), oret = g.terminal(3);
        g.addModel(new InverterModel(din, gnd, oout, oret, inputR, busCap,
                outputR, outAmplitude, outFreq, outPhaseDeg));
    }

    @Override
    public void bind(Binding binding) { }
}
