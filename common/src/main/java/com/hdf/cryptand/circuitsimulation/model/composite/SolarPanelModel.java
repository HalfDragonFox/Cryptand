package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 太阳能电池板复合模型（光电流源 + 并联二极管等效 + 串/并联电阻，为未来准备）。
 * <p>
 * 现实物理：光伏电池 = 光电流源 I_ph（正比辐照度）+ 并联二极管（PN 结）+ 并联
 * 分流电阻 R_sh（漏电）+ 串联电阻 R_s（接触/体电阻）。I-V 曲线：
 *   I = I_ph − I_0·(exp(V/nVt)−1) − V/R_sh，V_out = I·R_s 外。
 * <p>
 * 相量域简化：非线性二极管以【并联分流电阻 R_sh】线性近似（小信号工作点），
 * 光电流源 I_ph（辐照度可调）+ R_sh 并联 + R_s 串联。DC 求解 = 光伏输出。
 * <p>
 * 端口：pout/nout = 电池板输出端。内部节点 x（R_s 串联后）。
 * 辐照度/温度 → 光电流 I_ph（外部更新 setter → 参数消息 → 重解）。
 */
public class SolarPanelModel extends CompositeModel {

    /** 输出端子 + 内部节点（R_s 串联后） */
    public final int pout, nout, x;
    /** 串联电阻（Ω）、并联分流电阻（Ω） */
    public final double seriesR, shuntR;
    /** 光电流（A，volatile：辐照度/温度更新） */
    private volatile double photoCurrent;
    /** 光电流源引用 */
    private final CurrentSource lightSource;

    public SolarPanelModel(int pout, int nout, int x,
                           double photoCurrent, double seriesR, double shuntR) {
        super(build(pout, nout, x, photoCurrent, seriesR, shuntR));
        this.pout = pout; this.nout = nout; this.x = x;
        this.photoCurrent = photoCurrent;
        this.seriesR = seriesR;
        this.shuntR = shuntR;
        CurrentSource cs = null;
        for (Element e : simple) {
            if (e instanceof CurrentSource c) { cs = c; break; }
        }
        this.lightSource = cs;
    }

    /** 组合：光电流源（x→nout 内部）+ 分流电阻 + 串联电阻（pout-x） */
    private static Element[] build(int pout, int nout, int x,
                                   double iPh, double rS, double rSh) {
        return new Element[]{
                new CurrentSource(x, nout, iPh),
                new Resistor(x, nout, Math.max(rSh, 1e-9)),
                new Resistor(pout, x, Math.max(rS, 1e-9))
        };
    }

    /** 更新辐照度（光电流 I_ph）→ 参数消息 → 重解 */
    public void setPhotoCurrent(double i) {
        if (i < 0) return;
        if (Double.compare(i, photoCurrent) != 0) {
            photoCurrent = i;
            if (lightSource != null) lightSource.setCurrent(i);
        }
    }

    public double photoCurrent() { return photoCurrent; }

    /** 序列化参数：[I_ph, R_s, R_sh, 端口] */
    public double[] params() {
        return new double[]{photoCurrent, seriesR, shuntR, pout, nout, x};
    }

    @Override
    public String toString() {
        return "SolarPanelModel{" + pout + "-" + nout + " Iph=" + photoCurrent
                + "A Rs=" + seriesR + " Rsh=" + shuntR + "}";
    }
}
