package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.RectifierModel;

/**
 * ===== 预制组装器：整流器（2026-08-30 引擎内置——交错电网抽象） =====
 * 整流器 = RectifierModel（4 端子：交流入 ain/nin → 直流出 dout/ngnd——桥式
 * 整流 + 滤波电容 + 负载）。
 */
public final class RectifierAssembler implements Assembler {

    private final RectifierModel.Kind kind;
    private final double bridgeR, filterCap, loadR;

    public RectifierAssembler(RectifierModel.Kind kind, double bridgeR,
                              double filterCap, double loadR) {
        this.kind = kind != null ? kind : RectifierModel.Kind.FULL_BRIDGE;
        this.bridgeR = bridgeR;
        this.filterCap = filterCap;
        this.loadR = loadR;
    }

    @Override
    public String feature() { return "rectifier"; }

    @Override
    public int terminalCount() { return 4; }

    @Override
    public void build(GraphBuilder g) {
        int ain = g.terminal(0), nin = g.terminal(1);
        int dout = g.terminal(2), ngnd = g.terminal(3);
        g.addModel(new RectifierModel(ain, nin, dout, ngnd, kind, bridgeR, filterCap, loadR));
    }

    @Override
    public void bind(Binding binding) { }
}
