package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;

/**
 * ===== 预制组装器：交流源（2026-08-30 引擎内置——交错电网抽象） =====
 */
public final class AcSourceAssembler implements Assembler {

    private final double amplitude;   // 幅值（V）
    private final double phaseDeg;    // 相位（度）
    private final double seriesR;     // 内阻（Ω）
    private final double frequency;   // 频率（Hz）

    public AcSourceAssembler(double amplitude, double phaseDeg, double seriesR, double frequency) {
        this.amplitude = amplitude;
        this.phaseDeg = phaseDeg;
        this.seriesR = Math.max(seriesR, 0);
        this.frequency = frequency;
    }

    @Override
    public String feature() { return "ac_source"; }

    @Override
    public boolean isSource() { return true; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1);
        g.addElement(new AcVoltageSource(a, b, amplitude, phaseDeg, seriesR, frequency));
    }

    @Override
    public void bind(Binding binding) { }
}
