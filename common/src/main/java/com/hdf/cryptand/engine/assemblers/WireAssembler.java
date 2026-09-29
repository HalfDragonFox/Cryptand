package com.hdf.cryptand.engine.assemblers;

import com.hdf.cryptand.engine.Assembler;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.engine.GraphBuilder;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * ===== 预制组装器：导线（2026-08-30 引擎内置——纯 Java） =====
 * 导线 = 基础电阻元件（可带温度模型——过流发热/烧毁；额定功率以下不热）。
 */
public final class WireAssembler implements Assembler {

    private final double resistance;
    private final double ratedPowerW; // 额定功率（额定电流²×R；P≤此不热）
    private final ThermalModel thermal; // 可 null（无温度）

    public WireAssembler(double resistance, double ratedCurrent, ThermalModel thermal) {
        this.resistance = resistance > 0 ? resistance : 0.0015;
        double maxA = ratedCurrent > 0 ? ratedCurrent : 80.0;
        this.ratedPowerW = maxA * maxA * this.resistance;
        this.thermal = thermal;
    }

    /** ⚠ 2026-08-30 导线合并（用户：连续一组带一个组装器——多个段合并统一计算）：
     *  电阻合并（相加）+ 共享温度模型（统一——计算好温度后统一赋值）。
     *  合并后为一个组装器（R = ΣR；thermal 共享——同组统一温度）。 */
    public static WireAssembler merged(java.util.List<WireAssembler> segments) {
        double rSum = 0, maxA = 80.0;
        ThermalModel th = null;
        for (WireAssembler w : segments) {
            if (w == null) continue;
            rSum += w.resistance;
            if (th == null) th = w.thermal;
        }
        return new WireAssembler(rSum, maxA, th);
    }

    /** 段电阻（Ω）——合并/统一计算用 */
    public double resistance() { return resistance; }
    /** 温度模型（共享——合并组统一温度） */
    public ThermalModel thermal() { return thermal; }
    /** 额定功率（W——额定以下不热） */
    public double ratedPowerW() { return ratedPowerW; }
    /** 额定电流（A——√(额定功率/电阻)——导线组用） */
    public double ratedCurrent() {
        return resistance > 0 ? Math.sqrt(ratedPowerW / resistance) : 0;
    }

    @Override
    public String feature() { return "wire"; }

    @Override
    public void build(GraphBuilder g) {
        int a = g.terminal(0), b = g.terminal(1);
        g.addElement(new Resistor(a, b, resistance));
        // 温度模型导线（WireComposite 带 ratedPowerW——引擎层）
        if (thermal != null) {
            g.addModel(new com.hdf.cryptand.circuitsimulation.model.composite.WireComposite(
                    a, b, resistance, thermal, "W" + a + "-" + b, ratedPowerW));
        }
    }

    @Override
    public void bind(Binding binding) {
        // 导线绑定（可选）
    }
}
