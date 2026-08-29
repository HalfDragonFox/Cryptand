package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementBinding;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 电感复合模型（2026-08-12 用户要求：电感带储能效果，组合为复合元件）。
 * <p>
 * 组合：
 *   - 基础电感 {@link Inductor}（Backward Euler：iPrev 电流记忆 = 【储能】，
 *     磁链/电流随时间积分）
 *   - 直流电阻 DCR（可选串联铜阻 → 温度发热）
 *   - 能量模型 {@link EnergyModel}（储能状态：电流记忆 → 磁链类比电荷）
 *   - 温度模型（DCR>0 时铜损 I²·R → 温度推进）
 * <p>
 * 实现 {@link EnergyDevice}（储能统一入口）与 {@link ThermalDevice}（温度）。
 */
public class InductorModel extends CompositeModel implements EnergyDevice, ThermalDevice {

    public final int a, b, x;
    public final EnergyModel energy;
    public final ThermalModel thermal;
    private final Inductor ind;
    private final Resistor dcr; // 直流电阻（可 null）

    public InductorModel(int a, int b, int x, double inductance, double dcrResistance,
                         EnergyModel energy, ThermalModel thermal) {
        super(build(a, b, x, inductance, dcrResistance), thermal);
        this.a = a;
        this.b = b;
        this.x = x;
        this.energy = energy == null ? new EnergyModel(1.0) : energy;
        this.thermal = thermal;
        this.ind = (Inductor) decompose()[decompose().length - 1];
        Resistor r = null;
        for (Element e : decompose()) {
            if (e instanceof Resistor re) { r = re; break; }
        }
        this.dcr = r;
    }

    /** 组合：a --Resistor(DCR)-- x --Inductor-- b（DCR>0 才有电阻） */
    private static Element[] build(int a, int b, int x, double l, double dcr) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        if (dcr > 0) els.add(new Resistor(a, x, dcr));
        els.add(new Inductor(x, b, l));
        return els.toArray(new Element[0]);
    }

    /** 更新电感值（参数刷新） */
    public void setInductance(double l) {
        ind.setInductance(l);
    }

    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 铜耗（平均）：I²·R/2。2026-08-18 电流法：流过内部电阻（a-x）的真实
     *  电流（nodeVoltages 注入后精确；未注入用端口电压/阻抗估算兜底） */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (dcr == null) return 0;
        double xl = omega * ind.inductance;
        double z = Math.sqrt(dcr.resistance * dcr.resistance + xl * xl);
        return resistorLoss(va, vb, omega, dcr.resistance, a, x, z);
    }

    // ===== 储能（EnergyDevice：电感磁链/电流记忆 = 储能状态） =====
    @Override public EnergyModel energy() { return energy; }

    /** 储能同步：电感电流记忆 iPrev（Backward Euler 每轮更新）→ 磁链类比电荷 */
    @Override
    public void syncCharge(Complex va, Complex vb) {
        energy.charge = energy.capacitance * ind.iPrev;
    }

    /** 绑定参数变动（外部全局调感值） */
    @Override
    protected void onBindingChanged(ElementBinding b) {
        double l = b.boundInductance();
        if (l > 0) setInductance(l);
    }

    @Override
    public ElementType type() { return ElementType.INDUCTOR; }

    @Override
    public String toString() {
        return "InductorModel{" + a + "-" + b + " L=" + ind.inductance
                + " i=" + String.format("%.3f", ind.iPrev)
                + " thermal=" + (thermal == null ? "none" : "on") + "}";
    }
}
