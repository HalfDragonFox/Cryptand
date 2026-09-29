package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.ElectronTubeModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：电子管（2026-08-30 引擎内置——交错电网抽象） =====
 * 电子管 = ElectronTubeModel（3 端子：阳极/阴极/栅极——μ 放大系数 + 内阻）。
 */
public final class ElectronTubeAssembler implements Assembler {

    private final double mu, rp, vCutoff;
    private final ThermalModel thermal;

    public ElectronTubeAssembler(double mu, double rp, double vCutoff, ThermalModel thermal) {
        this.mu = mu;
        this.rp = rp;
        this.vCutoff = vCutoff;
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "electron_tube"; }

    @Override
    public int terminalCount() { return 3; }

    @Override
    public void build(GraphBuilder g) {
        int anode = g.terminal(0), cathode = g.terminal(1), grid = g.terminal(2);
        g.addModel(new ElectronTubeModel(anode, cathode, grid, mu, rp, vCutoff, thermal));
    }

    @Override
    public void bind(Binding binding) { }
}
