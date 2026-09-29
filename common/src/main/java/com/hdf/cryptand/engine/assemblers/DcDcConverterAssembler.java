package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.DcDcConverterModel;

/**
 * ===== 预制组装器：DC-DC 变换器（2026-08-30 引擎内置——交错电网抽象） =====
 * DC-DC = DcDcConverterModel（4 端子：vin/vgnd 输入 → vout/ognd 输出——变压比
 * + 电感储能）。
 */
public final class DcDcConverterAssembler implements Assembler {

    private final double ratio, inductance, inputR;

    public DcDcConverterAssembler(double ratio, double inductance, double inputR) {
        this.ratio = ratio;
        this.inductance = inductance;
        this.inputR = inputR;
    }

    @Override public String feature() { return "dc_dc_converter"; }
    @Override public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int vin = g.terminal(0), vgnd = g.terminal(1);
        int vout = g.terminal(2), ognd = g.terminal(3), x = g.addNode();
        g.addModel(new DcDcConverterModel(vin, vgnd, vout, ognd, x, ratio, inductance, inputR));
    }

    @Override public void bind(Binding binding) { }
}
