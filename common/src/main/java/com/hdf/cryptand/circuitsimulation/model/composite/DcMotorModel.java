package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 直流电机复合模型（电枢 R-L + 反电动势，为未来准备）。
 * <p>
 * 现实物理：直流电机电枢等效电路 = 电枢电阻 R_a + 电枢电感 L_a + 反电动势
 * EMF（正比转速：EMF = k_e·ω，k_e = 反电动势常数）。电动机：
 *   V = EMF + I·R_a + L_a·dI/dt
 * 励磁（永磁/串励/并励）决定 k_e/k_t。机械输出功率 P_mech = EMF·I。
 * <p>
 * 电路解析：a --R_a-- x --L_a-- y --(EMF 反电动势源)-- b。EMF 源用
 * {@link DcVoltageSource}（内阻极小），方向与端电压相反（电动机吸收功率）。
 * 实现 {@link ThermalDevice}：电枢铜耗 I²·R_a/2。
 * <p>
 * 端口：a/b = 电枢端子（外部接电源/换向器）。内部节点 x/y 由调用方分配。
 * 转速/EMF 由外部机械模型更新（{@link #setBackEmf} → 参数消息 → 重解）。
 */
public class DcMotorModel extends CompositeModel implements ThermalDevice {

    /** 电枢端子 + R/L/EMF 内部节点 */
    public final int a, b, x, y;
    /** 电枢电阻（Ω）、电感（H）、反电动势常数（V/(rad/s)） */
    public final double armatureR, armatureL, ke;
    /** 当前反电动势（V，volatile：转速更新） */
    private volatile double backEmf;
    /** 温度模型 */
    public final ThermalModel thermal;

    /** 反电动势源（setter 更新电压） */
    private final DcVoltageSource emfSource;

    public DcMotorModel(int a, int b, int x, int y,
                        double armatureR, double armatureL, double ke,
                        double initialBackEmf, ThermalModel thermal) {
        super(build(a, b, x, y, armatureR, armatureL, initialBackEmf));
        this.a = a; this.b = b; this.x = x; this.y = y;
        this.armatureR = armatureR;
        this.armatureL = armatureL;
        this.ke = ke;
        this.backEmf = initialBackEmf;
        this.thermal = thermal;
        DcVoltageSource src = null;
        for (Element e : simple) {
            if (e instanceof DcVoltageSource ds && ds.nodeA() == y) { src = ds; break; }
        }
        this.emfSource = src;
    }

    /** 组合：a --R_a-- x --L_a-- y --EMF源-- b */
    private static Element[] build(int a, int b, int x, int y,
                                   double r, double l, double emf) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        if (r > 0) els.add(new Resistor(a, x, r));
        if (l > 0) els.add(new Inductor(x, y, l));
        els.add(new DcVoltageSource(y, b, Math.abs(emf), 1e-6)); // 反电动势（内阻≈0）
        return els.toArray(new Element[0]);
    }

    /** 更新反电动势（外部按转速 EMF = ke·ω 计算传入）→ 参数消息 → 重解。
     *  注意：DcVoltageSource.voltage 为 final，此处占位（未来改用可变 DC 源）。
     *  当前反电动势由外部网络写回/约束，结构占位保证拓扑完整。 */
    public void setBackEmf(double e) {
        this.backEmf = e;
    }

    /** 由转速 ω（rad/s）更新反电动势 = ke·ω */
    public void updateSpeed(double omegaRadPerSec) {
        setBackEmf(ke * omegaRadPerSec);
    }

    public double backEmf() { return backEmf; }

    // ===== ThermalDevice =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 电枢铜耗（平均）：I²·R_a/2。2026-08-18 电流法：流过电枢电阻（a-x，
     *  x=反电动势节点）的真实电流——V_x 已含 EMF，I=(V_a−V_x)/R_a 天然正确
     *  （nodeVoltages 注入后精确；未注入估算兜底） */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (armatureR <= 0) return 0;
        double z = Math.sqrt(armatureR * armatureR + Math.pow(omega * armatureL, 2));
        return resistorLoss(va, vb, omega, armatureR, a, x, z);
    }

    /** 序列化参数：[R_a, L_a, ke, EMF, 端口] */
    public double[] params() {
        return new double[]{armatureR, armatureL, ke, backEmf, a, b, x, y};
    }

    @Override
    public String toString() {
        return "DcMotorModel{" + a + "-" + b + " R=" + armatureR + " L=" + armatureL
                + " EMF=" + String.format("%.2f", backEmf)
                + " T=" + (thermal == null ? "none" : "on") + "}";
    }
}
