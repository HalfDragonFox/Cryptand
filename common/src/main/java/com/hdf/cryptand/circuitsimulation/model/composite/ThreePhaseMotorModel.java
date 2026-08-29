package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 三相电机复合模型（星形绕组等效，为未来准备——三相感应/同步电机通用骨架）。
 * <p>
 * 现实物理：三相电机定子有三套绕组（A/B/C），星形连接公共中性点 N。每相绕组
 * = 铜阻 R_ph 串联漏感 L_ph（基波等效）。感应电机还叠加转子耦合（此处简化
 * 为每相 R-L；精确转差率模型见 {@link InductionMotorModel}）。
 * <p>
 * 电路解析：A-N / B-N / C-N 各一条 R-L 串联支路（内部节点 xa/xb/xc）。
 * 实现 {@link ThermalDevice}：三相铜耗 = 3 × (I_ph²·R_ph/2)。
 * <p>
 * 端口：a/b/c = 三相端子，n = 中性点。内部节点 xa/xb/xc 由调用方分配。
 */
public class ThreePhaseMotorModel extends CompositeModel implements ThermalDevice {

    /** 三相端子 + 中性点 + 每相 R-L 内部节点 */
    public final int a, b, c, n, xa, xb, xc;
    /** 每相铜阻（Ω）、漏感（H）、温度模型 */
    public final double phaseR, phaseL;
    public final ThermalModel thermal;

    public ThreePhaseMotorModel(int a, int b, int c, int n,
                                int xa, int xb, int xc,
                                double phaseR, double phaseL,
                                ThermalModel thermal) {
        super(build(a, b, c, n, xa, xb, xc, phaseR, phaseL));
        this.a = a; this.b = b; this.c = c; this.n = n;
        this.xa = xa; this.xb = xb; this.xc = xc;
        this.phaseR = phaseR; this.phaseL = phaseL;
        this.thermal = thermal;
    }

    /** 组合：三套 R-L 串联绕组（A-N, B-N, C-N） */
    private static Element[] build(int a, int b, int c, int n,
                                   int xa, int xb, int xc,
                                   double r, double l) {
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

    // ===== ThermalDevice =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return n; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 三相铜耗（平均）：3 × I_ph²·R/2。2026-08-18 电流法：A 相内部电阻
     *  （a-xa）支路电流（nodeVoltages 注入后精确；未注入 |V|/|Z| 兜底）。 */
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
            iPeak = z < 1e-12 ? 0 : va.sub(vb).abs() / z;
        }
        return 3.0 * iPeak * iPeak * phaseR / 2.0;
    }

    /** 序列化参数：[R, L, 端口/内部节点] */
    public double[] params() {
        return new double[]{phaseR, phaseL, a, b, c, n, xa, xb, xc};
    }

    @Override
    public String toString() {
        return "ThreePhaseMotorModel{" + a + "/" + b + "/" + c + "-N" + n
                + " R=" + phaseR + " L=" + phaseL
                + " T=" + (thermal == null ? "none" : "on") + "}";
    }
}
