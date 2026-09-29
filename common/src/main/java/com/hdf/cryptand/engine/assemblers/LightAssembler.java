package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.SwitchModel;

/**
 * ===== 预制组装器：灯（2026-08-30 引擎内置——纯 Java 独立仿真核心） =====
 * 灯 = SwitchModel（灯座开关——装灯泡=灯丝电阻闭合；未装/烧断=开路）。
 * 引擎预制——与平台无关（MC/网页/软件仿真器通用）。
 */
public final class LightAssembler implements Assembler {

    /** 灯丝电阻（Ω——装灯泡闭合时） */
    private final double onResistance;
    /** 开关态（true=装灯泡闭合；false=未装/烧断开路） */
    private volatile boolean on = true;

    public LightAssembler(double onResistance) {
        this.onResistance = onResistance > 0 ? onResistance : 100.0;
    }

    /** 设置开关态（装/取灯泡——平台调用——参数变化触发重解） */
    public void setOn(boolean on) {
        this.on = on;
    }

    @Override
    public String feature() { return "light"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1);
        SwitchModel sm = new SwitchModel(a, b, onResistance);
        sm.setOn(on);
        g.addModel(sm);
    }

    @Override
    public void bind(Binding binding) {
        // 灯绑定（可选——平台实现：护目镜/亮度/温度计——null 不绑定由引擎跳过）
    }
}
