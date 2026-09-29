package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.ThreePhaseMotorModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：三相电机（2026-08-30 引擎内置——交错电网抽象） =====
 * 三相电机 = ThreePhaseMotorModel（4 端子 a/b/c/n + 3 内部——三相绕组 R-L）。
 */
public final class ThreePhaseMotorAssembler implements Assembler {

    private final double phaseR, phaseL;
    private final ThermalModel thermal;

    public ThreePhaseMotorAssembler(double phaseR, double phaseL, ThermalModel thermal) {
        this.phaseR = phaseR;
        this.phaseL = phaseL;
        this.thermal = thermal;
    }

    @Override public String feature() { return "three_phase_motor"; }
    @Override public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), c = g.terminal(2), n = g.terminal(3);
        int xa = g.addNode(), xb = g.addNode(), xc = g.addNode();
        g.addModel(new ThreePhaseMotorModel(a, b, c, n, xa, xb, xc, phaseR, phaseL, thermal));
    }

    @Override public void bind(Binding binding) { }
}
