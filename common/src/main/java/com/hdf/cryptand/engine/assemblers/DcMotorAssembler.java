package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.DcMotorModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：直流电机（带 ke 反电动势系数——2026-08-30 引擎内置） =====
 * DcMotorModel（4 端子：电枢 a/b + x/y——ke 反电动势 + 初始反电动势）。
 */
public final class DcMotorAssembler implements Assembler {

    private final double armatureR, armatureL, ke, initialBackEmf;
    private final ThermalModel thermal;

    public DcMotorAssembler(double armatureR, double armatureL, double ke,
                            double initialBackEmf, ThermalModel thermal) {
        this.armatureR = armatureR;
        this.armatureL = armatureL;
        this.ke = ke;
        this.initialBackEmf = initialBackEmf;
        this.thermal = thermal;
    }

    @Override public String feature() { return "dc_motor"; }
    @Override public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.terminal(2), y = g.terminal(3);
        g.addModel(new DcMotorModel(a, b, x, y, armatureR, armatureL, ke, initialBackEmf, thermal));
    }

    @Override public void bind(Binding binding) { }
}
