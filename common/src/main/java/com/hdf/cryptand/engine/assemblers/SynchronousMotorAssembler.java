package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.SynchronousMotorModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：同步电机（2026-08-30 引擎内置——交错电网抽象） =====
 * 同步电机 = SynchronousMotorModel（定子 R-L + 励磁 EMF——转子同步于电网频率）。
 */
public final class SynchronousMotorAssembler implements Assembler {

    private final double statorR, synchL, emfAmplitude, emfPhaseDeg;
    private final ThermalModel thermal;

    public SynchronousMotorAssembler(double statorR, double synchL,
                                     double emfAmplitude, double emfPhaseDeg, ThermalModel thermal) {
        this.statorR = statorR;
        this.synchL = synchL;
        this.emfAmplitude = emfAmplitude;
        this.emfPhaseDeg = emfPhaseDeg;
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "synchronous_motor"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode(), y = g.addNode();
        g.addModel(new SynchronousMotorModel(a, b, x, y, statorR, synchL,
                emfAmplitude, emfPhaseDeg, thermal));
    }

    @Override
    public void bind(Binding binding) { }
}
