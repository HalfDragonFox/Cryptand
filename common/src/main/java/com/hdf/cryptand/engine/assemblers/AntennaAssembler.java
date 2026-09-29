package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.AntennaModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：天线（2026-08-30 引擎内置——交错电网抽象） =====
 * 天线 = AntennaModel（辐射电阻 + 损耗 + 调谐——发射/接收电磁波）。
 */
public final class AntennaAssembler implements Assembler {

    private final double radiationR, lossR;
    private final int tuneKind;
    private final double tuneValue;
    private final ThermalModel thermal;

    public AntennaAssembler(double radiationR, double lossR, int tuneKind,
                            double tuneValue, ThermalModel thermal) {
        this.radiationR = radiationR;
        this.lossR = lossR;
        this.tuneKind = tuneKind;
        this.tuneValue = tuneValue;
        this.thermal = thermal;
    }

    @Override public String feature() { return "antenna"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1);
        g.addModel(new AntennaModel(a, b, radiationR, lossR, tuneKind, tuneValue, thermal));
    }

    @Override public void bind(Binding binding) { }
}
