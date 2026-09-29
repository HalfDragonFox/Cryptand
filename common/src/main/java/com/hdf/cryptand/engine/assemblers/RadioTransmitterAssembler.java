package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.RadioTransmitterModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：无线电发射机（2026-08-30 引擎内置——交错电网抽象） =====
 */
public final class RadioTransmitterAssembler implements Assembler {

    private final double outputR, carrierAmp, txPower;
    private final ThermalModel thermal;

    public RadioTransmitterAssembler(double outputR, double carrierAmp,
                                     double txPower, ThermalModel thermal) {
        this.outputR = outputR;
        this.carrierAmp = carrierAmp;
        this.txPower = txPower;
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "radio_tx"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1);
        g.addModel(new RadioTransmitterModel(a, b, outputR, carrierAmp, txPower, thermal));
    }

    @Override
    public void bind(Binding binding) { }
}
