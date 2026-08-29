package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 无刷直流电机复合模型（三相绕组 + 电子换向等效，为未来准备）。
 * <p>
 * 现实物理：BLDC 电机定子三相绕组、转子永磁体；电子换向器（逆变桥）按转子
 * 位置依次给两相通电，产生梯形波反电动势（120° 导通）。等效：每相 R-L 绕组
 * + 梯形反电动势（基波近似为正弦）。转矩 = k_t·I（I = 相电流）。
 * <p>
 * 相量域简化：三相 R-L 绕组（星形）+ 每相反电动势源（幅值正比转速）。
 * 实现 {@link ThermalDevice}：三相铜耗。电子换向等效为理想（无换向损耗）。
 * <p>
 * 端口：a/b/c = 三相端子，n = 中性点。内部节点 xa/xb/xc 分配。
 */
public class BrushlessDcMotorModel extends CompositeModel implements ThermalDevice {

    /** 三相端子 + 中性点 + 每相 R-L 内部节点 */
    public final int a, b, c, n, xa, xb, xc;
    /** 每相电阻/电感（Ω/H） */
    public final double phaseR, phaseL;
    /** 反电动势常数（V/(rad/s)）与当前转速（rad/s） */
    private volatile double ke, speed;
    /** 温度模型 */
    public final ThermalModel thermal;

    public BrushlessDcMotorModel(int a, int b, int c, int n,
                                 int xa, int xb, int xc,
                                 double phaseR, double phaseL,
                                 double ke, double initialSpeed,
                                 ThermalModel thermal) {
        super(build(a, b, c, n, xa, xb, xc, phaseR, phaseL));
        this.a = a; this.b = b; this.c = c; this.n = n;
        this.xa = xa; this.xb = xb; this.xc = xc;
        this.phaseR = phaseR; this.phaseL = phaseL;
        this.ke = ke; this.speed = initialSpeed;
        this.thermal = thermal;
    }

    /** 组合：三相 R-L 串联绕组（A-N, B-N, C-N） */
    private static Element[] build(int a, int b, int c, int n,
                                   int xa, int xb, int xc, double r, double l) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        if (r > 0) {
            els.add(new Resistor(a, xa, r));
            els.add(new Resistor(b, xb, r));
            els.add(new Resistor(c, xc, r));
        }
        if (l > 0) {
            els.add(new Inductor(xa, n, l));
            els.add(new Inductor(xb, n, l));
            els.add(new Inductor(xc, n, l));
        }
        return els.toArray(new Element[0]);
    }

    /** 更新转速（rad/s）→ 反电动势 = ke·ω（梯形波基波近似） */
    public void updateSpeed(double omegaRadPerSec) {
        this.speed = omegaRadPerSec;
    }

    public double backEmfAmplitude() { return ke * speed; }

    // ===== ThermalDevice =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return n; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 三相铜耗（平均）：3 × I_ph²·R/2。2026-08-18 电流法：A 相内部电阻
     *  （a-xa）支路电流（nodeVoltages 注入后精确，反电动势由端口电压体现；
     *  未注入用 (|V|−EMF)/|Z| 兜底）。 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (phaseR <= 0) return 0;
        double z = Math.sqrt(phaseR * phaseR + Math.pow(omega * phaseL, 2));
        Complex vxa = nodeVoltage(xa);
        Complex vaa = nodeVoltage(a);
        double iPeak;
        if (vxa != null && vaa != null) {
            // A 相电阻支路电流（对称三相，I_ph 相同）
            iPeak = vaa.sub(vxa).abs() / phaseR;
        } else {
            double vEff = Math.max(0, va.sub(vb).abs() - backEmfAmplitude());
            iPeak = z < 1e-12 ? 0 : vEff / z;
        }
        return 3.0 * iPeak * iPeak * phaseR / 2.0;
    }

    /** 序列化参数：[R, L, ke, speed, 端口] */
    public double[] params() {
        return new double[]{phaseR, phaseL, ke, speed, a, b, c, n, xa, xb, xc};
    }

    @Override
    public String toString() {
        return "BrushlessDcMotorModel{" + a + "/" + b + "/" + c + "-N" + n
                + " R=" + phaseR + " ke=" + ke + " ω=" + String.format("%.1f", speed) + "}";
    }
}
