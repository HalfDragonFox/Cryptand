package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.composite.MotorModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：加热器（2026-08-30 引擎内置——纯 Java） =====
 * 加热器 = R-L 绕组（MotorModel——电阻 + 电感 + 温度模型——高耐温）。
 */
public final class HeaterAssembler implements Assembler {

    private final double resistance;
    private final double inductance;
    private final ThermalModel thermal;

    public HeaterAssembler(double resistance, double inductance, ThermalModel thermal) {
        this.resistance = resistance > 0 ? resistance : 25.6;
        this.inductance = Math.max(inductance, 0);
        this.thermal = thermal != null ? thermal
                : new ThermalModel(4.0, 200.0, 298.15, 673.15); // 高耐温默认
    }

    @Override
    public String feature() { return "heater"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1), x = g.addNode();
        g.addModel(new MotorModel(a, b, x, resistance, inductance, thermal));
    }

    @Override
    public void bind(Binding binding) {
        // 加热器绑定（可选——平台实现）
    }
}
