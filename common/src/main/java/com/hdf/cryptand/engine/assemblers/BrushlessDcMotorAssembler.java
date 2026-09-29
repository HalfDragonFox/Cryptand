package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.BrushlessDcMotorModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：直流无刷电机（2026-08-30 引擎内置——交错电网抽象） =====
 * BLDC = BrushlessDcMotorModel（4 端子 a/b/c/n + 3 内部——三相绕组 + ke 反电动势）。
 */
public final class BrushlessDcMotorAssembler implements Assembler {

    private final double phaseR, phaseL, ke, initialSpeed;
    private final ThermalModel thermal;

    public BrushlessDcMotorAssembler(double phaseR, double phaseL, double ke,
                                     double initialSpeed, ThermalModel thermal) {
        this.phaseR = phaseR;
        this.phaseL = phaseL;
        this.ke = ke;
        this.initialSpeed = initialSpeed;
        this.thermal = thermal;
    }

    @Override public String feature() { return "brushless_dc_motor"; }
    @Override public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), c = g.terminal(2), n = g.terminal(3);
        int xa = g.addNode(), xb = g.addNode(), xc = g.addNode();
        g.addModel(new BrushlessDcMotorModel(a, b, c, n, xa, xb, xc, phaseR, phaseL,
                ke, initialSpeed, thermal));
    }

    @Override public void bind(Binding binding) { }
}
