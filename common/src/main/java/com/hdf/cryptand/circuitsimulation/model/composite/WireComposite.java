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
    /** ⚠ 2026-08-30 额定功率（W）= 额定电流²×段电阻；P≤此 → 不发热（保持 25°C
     *  环境温），超过 → 超额定部分加热升温到温度上限烧毁（用户需求）。
     *  0/未设 = 无额定（全功率加热，旧行为）。 */
    public final double ratedPowerW;

    /**
     * @param a,b        段两端引擎节点
     * @param resistance 段总电阻（Ω，>0）
     * @param thermal    温度模型（可 null：不参与温度模拟）
     * @param thermalKey 去重 key（段路径签名；同段多网络 ctx 只更新一次）
     */
    public WireComposite(int a, int b, double resistance, ThermalModel thermal, String thermalKey) {
        this(a, b, resistance, thermal, thermalKey, 0);
    }

    /** 带额定功率构造（2026-08-30：WireAssembler 传 I_rated²×R；0 = 无额定全加热） */
    public WireComposite(int a, int b, double resistance, ThermalModel thermal,
                         String thermalKey, double ratedPowerW) {
        super(new Element[]{ new Resistor(a, b, resistance) }, thermal);
        this.a = a;
        this.b = b;
        this.resistance = resistance;
        this.ratedPowerW = ratedPowerW > 0 ? ratedPowerW : Double.MAX_VALUE;
        setCompositeKey(thermalKey);
    }

    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }

    /** 段平均损耗功率：I²·R/2（I = |Va−Vb|/R 峰值）。
     *  ⚠ 2026-09-12 用户："发热按照公式来计算…而不是强制限制"——**不再扣减
     *  ratedPowerW**，一律返回完整焦耳热；"额定电流下温升很小（≈25°C 环境附近）"
     *  改由【散热标定】实现（见 WireAssembler / WireThermalStore.RATED_RISE_C：
     *  G = 额定功率/额定温升 ⇒ 额定电流下稳态温升 = 额定温升），超过额定则按
     *  I²R/G 自然升温 → 到温度上限烧毁。
     *  ratedPowerW 字段保留为【额定功率元数据】（= 额定电流²×段电阻），供标定/诊断。 */
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
