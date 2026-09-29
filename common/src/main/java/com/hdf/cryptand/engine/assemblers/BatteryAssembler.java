package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * ===== 预制组装器：电池（2026-08-30 引擎内置——纯 Java） =====
 * 电池 = DC 电压源（EMF）+ 串联内阻（构建为两元件——端子 a → 内阻 → 源 → 端子 b）。
 */
public final class BatteryAssembler implements Assembler {

    private final double emf;         // 电动势（V）
    private final double internalR;   // 内阻（Ω）

    public BatteryAssembler(double emf, double internalR) {
        this.emf = emf;
        this.internalR = internalR >= 0 ? internalR : 0.1;
    }

    @Override
    public String feature() { return "battery"; }

    @Override
    public boolean isSource() { return true; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1);
        int x = g.addNode(); // 内阻中间节点
        if (internalR > 0) g.addElement(new Resistor(a, x, internalR));
        g.addElement(new DcVoltageSource(x, b, emf, internalR));
    }

    @Override
    public void bind(Binding binding) {
        // 电池绑定（可选）
    }
}
