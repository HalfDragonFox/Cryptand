package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 同步电机复合模型（定子绕组 + 励磁 + 反电动势，为未来准备）。
 * <p>
 * 现实物理：同步电机转子转速 = 电源频率/极对数（n = 60f/p，严格同步）。
 * 转子励磁产生磁场，定子三相绕组通交流产生旋转磁场拖动转子。等效电路
 * （单相，凸极简化为隐极）：每相 E_a（励磁反电动势）串联同步电抗 X_s
 * （= 漏感 + 电枢反应）+ 电枢电阻 R_a。功率角 δ 决定有功传输。
 * <p>
 * 相量域简化：每相 = R_a + L_s（同步电抗）+ 励磁反电动势源（AcVoltageSource，
 * 相位/幅值随励磁/负载角）。实现 {@link ThermalDevice}：定子铜耗。
 * <p>
 * 端口：a/b = 定子单相端口（三相用 {@link ThreePhaseMotorModel} 骨架）。内部
 * 节点 x/y 由调用方分配。
 */
public class SynchronousMotorModel extends CompositeModel implements ThermalDevice {

    /** 定子端子 + 内部节点（R/L/EMF 串联） */
    public final int a, b, x, y;
    /** 定子电阻（Ω）、同步电抗电感（H） */
    public final double statorR, synchL;
    /** 励磁反电动势幅值（V）、相位（度） */
    private volatile double emfAmplitude;
    private volatile double emfPhaseDeg;
    /** 温度模型 */
    public final ThermalModel thermal;

    private final AcVoltageSource emfSource;

    public SynchronousMotorModel(int a, int b, int x, int y,
                                 double statorR, double synchL,
                                 double emfAmplitude, double emfPhaseDeg,
                                 ThermalModel thermal) {
        super(build(a, b, x, y, statorR, synchL, emfAmplitude, emfPhaseDeg));
        this.a = a; this.b = b; this.x = x; this.y = y;
        this.statorR = statorR; this.synchL = synchL;
        this.emfAmplitude = emfAmplitude;
        this.emfPhaseDeg = emfPhaseDeg;
        this.thermal = thermal;
        AcVoltageSource src = null;
        for (Element e : simple) {
            if (e instanceof AcVoltageSource vs && vs.nodeA() == y) { src = vs; break; }
        }
        this.emfSource = src;
    }

    /** 组合：a --R_a-- x --L_s-- y --EMF源-- b */
    private static Element[] build(int a, int b, int x, int y,
                                   double r, double l, double emfAmp, double emfPhase) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        if (r > 0) els.add(new Resistor(a, x, r));
        if (l > 0) els.add(new Inductor(x, y, l));
        els.add(new AcVoltageSource(y, b, emfAmp, emfPhase, 1e-6));
        return els.toArray(new Element[0]);
    }

    /** 更新励磁反电动势（励磁电流/负载角变化）→ 参数消息 → 重解 */
    public void setExcitation(double amp, double phaseDeg) {
        boolean ch = Double.compare(amp, emfAmplitude) != 0
                || Double.compare(phaseDeg, emfPhaseDeg) != 0;
        emfAmplitude = amp;
        emfPhaseDeg = phaseDeg;
        if (ch && emfSource != null) {
            emfSource.setAmplitude(amp);
            emfSource.setPhaseDeg(phaseDeg);
        }
    }

    // ===== ThermalDevice =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 定子铜耗（平均）：I²·R_a/2。2026-08-18 电流法：流过定子内部电阻
     *  （a-x）的真实支路电流（nodeVoltages 注入后精确，EMF 已由端口电压体现；
     *  未注入用 |V|/|Z| 兜底）。 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (statorR <= 0) return 0;
        double z = Math.sqrt(statorR * statorR + Math.pow(omega * synchL, 2));
        return resistorLoss(va, vb, omega, statorR, a, x, z);
    }

    /** 序列化参数：[R, L_s, E_a, phase, 端口] */
    public double[] params() {
        return new double[]{statorR, synchL, emfAmplitude, emfPhaseDeg, a, b, x, y};
    }

    @Override
    public String toString() {
        return "SynchronousMotorModel{" + a + "-" + b + " R=" + statorR
                + " Xs=" + synchL + " E=" + String.format("%.2f", emfAmplitude)
                + "∠" + String.format("%.0f", emfPhaseDeg) + "°}";
    }
}
