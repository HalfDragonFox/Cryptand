package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.SwitchModel;

/**
 * ===== 预制组装器：开关（2026-08-30 引擎内置——纯 Java） =====
 * 开关 = SwitchModel（闭合=小电阻导通；断开=开路）。
 */
public final class SwitchAssembler implements Assembler {

    private final double closedResistance; // 闭合电阻（Ω）
    private volatile boolean closed = true; // 开关态

    public SwitchAssembler(double closedResistance) {
        this.closedResistance = closedResistance > 0 ? closedResistance : 0.01;
    }

    /** 设置开关态（平台调用——参数变化触发重解） */
    public void setClosed(boolean closed) {
        this.closed = closed;
    }

    @Override
    public String feature() { return "switch"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1);
        SwitchModel sm = new SwitchModel(a, b, closedResistance);
        sm.setOn(closed);
        g.addModel(sm);
    }

    @Override
    public void bind(Binding binding) {
        // 开关绑定（可选）
    }
}
