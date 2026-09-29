package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：接地棒（2026-08-30 引擎内置——交错电网抽象） =====
 * 接地棒 = 电阻到【地节点（0）】——建立参考地（V=0）。
 */
public final class GroundingRodAssembler implements Assembler {

    private final double resistance;
    private final ThermalModel thermal; // 可 null

    public GroundingRodAssembler(double resistance, ThermalModel thermal) {
        this.resistance = resistance > 0 ? resistance : 0.1;
        this.thermal = thermal;
    }

    @Override public String feature() { return "grounding_rod"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0);
        g.addElement(new Resistor(a, 0, resistance)); // 端子 → 地节点 0
    }

    @Override public void bind(Binding binding) { }
}
