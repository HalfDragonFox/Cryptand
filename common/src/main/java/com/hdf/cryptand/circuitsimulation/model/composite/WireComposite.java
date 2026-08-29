package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 导线连续段复合元件（2026-08-12 用户要求：导线也是一种复合，包含温度模型和电阻）。
 * <p>
 * 一段连续导线 = 一个电阻元件（Σ 段内导线电阻）+ 一套温度模型（散热/热容）。
 * 分叉/接线端子/设备端子等不连续处为不同段，各自独立复合元件与温度。
 * <p>
 * 物理：段两端节点独立（不 union）→ MNA 计算压降/电流；开路无闭合路径 →
 * 无电流 → 两端等电位（电压沿导线等电位传递）。损耗 = 段 I²R（平均 /2），
 * 求解后由统一 {@link #update} 推进温度（散热与发热同时算，解析解稳定）。
 */
public class WireComposite extends CompositeElement {

    /** 段两端引擎节点 */
    public final int a, b;
    /** Σ 段内导线电阻（Ω） */
    public final double resistance;

    /**
     * @param a,b        段两端引擎节点
     * @param resistance 段总电阻（Ω，>0）
     * @param thermal    温度模型（可 null：不参与温度模拟）
     * @param thermalKey 去重 key（段路径签名；同段多网络 ctx 只更新一次）
     */
    public WireComposite(int a, int b, double resistance, ThermalModel thermal, String thermalKey) {
        super(new Element[]{ new Resistor(a, b, resistance) }, thermal);
        this.a = a;
        this.b = b;
        this.resistance = resistance;
        setCompositeKey(thermalKey);
    }

    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }

    /** 段平均损耗功率：I²·R/2，I = |Va−Vb| / R（峰值） */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (resistance <= 1e-9) return 0;
        double vDiff = va.sub(vb).abs();
        double iPeak = vDiff / resistance;
        return iPeak * iPeak * resistance / 2.0;
    }

    @Override
    public String toString() {
        return "WireComposite{" + a + "-" + b + " R=" + String.format("%.4f", resistance)
                + " T=" + (thermal() == null ? "none" : "on") + "}";
    }
}
