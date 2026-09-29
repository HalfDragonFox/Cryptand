package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.ResistorModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：电阻（2026-08-30 引擎内置——纯 Java） =====
 * 电阻 = ResistorModel（基础电阻 + 温度模型——I²R 发热）。
 */
public final class ResistorAssembler implements Assembler {

    private final double resistance;
    private final ThermalModel thermal; // 可 null（无温度）

    public ResistorAssembler(double resistance, ThermalModel thermal) {
        this.resistance = resistance > 0 ? resistance : 10.0;
        this.thermal = thermal;
    }

    @Override
    public String feature() { return "resistor"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1);
        g.addModel(new ResistorModel(a, b, resistance, thermal));
    }

    @Override
    public void bind(Binding binding) {
        // 电阻绑定（可选）
    }
}
