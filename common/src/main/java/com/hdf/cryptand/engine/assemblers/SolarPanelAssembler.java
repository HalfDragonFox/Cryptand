package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.SolarPanelModel;

/**
 * ===== 预制组装器：太阳能板（2026-08-30 引擎内置——交错电网抽象） =====
 * 光伏源（光电流 + 串联电阻 + 并联分流）。
 */
public final class SolarPanelAssembler implements Assembler {

    private final double photoCurrent; // 光电流（A——光照）
    private final double seriesR, shuntR;

    public SolarPanelAssembler(double photoCurrent, double seriesR, double shuntR) {
        this.photoCurrent = photoCurrent;
        this.seriesR = Math.max(seriesR, 0);
        this.shuntR = shuntR > 0 ? shuntR : 1e6;
    }

    @Override
    public String feature() { return "solar_panel"; }

    @Override
    public boolean isSource() { return true; }

    @Override
    public void build(GraphBuilder g) {
        int pout = g.terminal(0), nout = g.terminal(1), x = g.addNode();
        g.addModel(new SolarPanelModel(pout, nout, x, photoCurrent, seriesR, shuntR));
    }

    @Override
    public void bind(Binding binding) { }
}
