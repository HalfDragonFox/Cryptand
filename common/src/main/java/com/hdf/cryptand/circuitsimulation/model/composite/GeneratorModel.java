package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 发电机复合模型（励磁 + 电枢，机械能→电能，为未来准备）。
 * <p>
 * 现实物理：发电机把机械能转电能——转子磁场切割定子绕组感应出电动势。
 * DC 发电机：电枢 R_a + 反电动势源（EMF = k_e·ω）+ 励磁（并励/串励/永磁）。
 * AC 发电机（交流发电机）：定子输出正弦（幅值正比转速与励磁）。
 * <p>
 * 相量域简化：电枢 R_a + 输出源（DC 用 DcVoltageSource，AC 用正弦源），
 * 幅值 = 励磁 × 转速（外部更新）。实现 {@link ThermalDevice}：电枢铜耗。
 * <p>
 * 端口：a/b = 输出端子。内部节点 x 分配（R_a 串联后）。
 * 转速/励磁 → EMF（外部调用 {@link #setOutputVoltage} → 参数消息 → 重解）。
 */
public class GeneratorModel extends CompositeModel implements ThermalDevice {

    /** 输出端子 + 内部节点（R_a 串联后） */
    public final int a, b, x;
    /** 电枢电阻（Ω） */
    public final double armatureR;
    /** 反电动势常数（V/(rad/s)）、当前转速（rad/s） */
    private volatile double ke, speed;
    /** 温度模型 */
    public final ThermalModel thermal;

    /** 输出源（setter 更新电压） */
    private final DcVoltageSource outSource;

    public GeneratorModel(int a, int b, int x,
                          double armatureR, double ke, double initialSpeed,
                          ThermalModel thermal) {
        super(build(a, b, x, armatureR, initialBackEmf(ke, initialSpeed)));
        this.a = a; this.b = b; this.x = x;
        this.armatureR = armatureR;
        this.ke = ke; this.speed = initialSpeed;
        this.thermal = thermal;
        DcVoltageSource src = null;
        for (Element e : simple) {
            if (e instanceof DcVoltageSource ds && ds.nodeA() == x) { src = ds; break; }
        }
        this.outSource = src;
    }

    private static double initialBackEmf(double ke, double speed) {
        return ke * speed;
    }

    /** 组合：x --R_a-- a（电枢）+ 输出源（x-b，EMF） */
    private static Element[] build(int a, int b, int x, double r, double emf) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        if (r > 0) els.add(new Resistor(a, x, r));
        els.add(new DcVoltageSource(x, b, Math.abs(emf), 1e-6));
        return els.toArray(new Element[0]);
    }

    /**
     * 更新转速（rad/s）→ 输出 EMF = ke·ω。
     * ⚠ 2026-08-30 审计 M8 根因：原实现只更新 speed 字段（DcVoltageSource.
     * voltage 是 final）→ 输出 EMF 恒初始值、updateSpeed 无效。现 DcVoltageSource
     * 支持 setVoltage（发参数变化消息 → 重解），EMF 真正跟随转速。
     */
    public void updateSpeed(double omegaRadPerSec) {
        this.speed = omegaRadPerSec;
        if (outSource != null) {
            outSource.setVoltage(Math.abs(ke * omegaRadPerSec));
        }
    }

    public double outputVoltage() { return ke * speed; }

    // ===== ThermalDevice =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 电枢铜耗（平均）：I²·R_a/2。2026-08-18 电流法：流过电枢电阻（a-x，
     *  x=EMF 输出节点）的真实电流——V_x 已含 EMF，I=(V_a−V_x)/R_a 天然正确
     *  （nodeVoltages 注入后精确；未注入估算兜底） */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (armatureR <= 0) return 0;
        return resistorLoss(va, vb, omega, armatureR, a, x, armatureR);
    }

    /** 序列化参数：[R_a, ke, speed, 端口] */
    public double[] params() {
        return new double[]{armatureR, ke, speed, a, b, x};
    }

    @Override
    public String toString() {
        return "GeneratorModel{" + a + "-" + b + " R=" + armatureR
                + " EMF=" + String.format("%.2f", outputVoltage())
                + " T=" + (thermal == null ? "none" : "on") + "}";
    }
}
