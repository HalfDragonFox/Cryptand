package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.BjtModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：BJT 晶体管（2026-08-30 引擎内置——交错电网抽象） =====
 * BJT = BjtModel（3 端子：集电极/发射极/基极——NPN/PNP + β 放大）。
 */
public final class BjtAssembler implements Assembler {

    private final boolean pnp;
    private final double beta;
    private final ThermalModel thermal;

    public BjtAssembler(boolean pnp, double beta, ThermalModel thermal) {
        this.pnp = pnp;
        this.beta = beta > 0 ? beta : 100;
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "bjt"; }

    @Override
    public int terminalCount() { return 3; }

    @Override
    public void build(GraphBuilder g) {
        int collector = g.terminal(0), emitter = g.terminal(1), base = g.terminal(2);
        g.addModel(new BjtModel(collector, emitter, base, pnp, beta, thermal));
    }

    @Override
    public void bind(Binding binding) { }
}
