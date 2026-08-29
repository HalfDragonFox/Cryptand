package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 三相整流桥复合模型（3 相 AC→DC，为未来准备——变频器/直流母线前端）。
 * <p>
 * 现实物理：三相桥式整流（6 脉波）把三相交流整成直流，输出直流电压
 *   V_dc ≈ 1.35 × V_line_rms（空载），纹波频率 6× 电源频率（更平滑）。
 * 每相两二极管（上/下桥臂），任意时刻最高/最低相导通。
 * <p>
 * 相量域简化：每相输入呈现等效电阻（桥式吸收功率），输出 = 直流母线
 * （滤波电容 + 负载电阻）。二极管导通损耗并入等效电阻。
 * <p>
 * 端口：ia/ib/ic/nin = 三相交流输入；dout/ngnd = 直流输出。无内部节点。
 */
public class ThreePhaseRectifierModel extends CompositeModel {

    /** 三相输入端子 + 直流输出端子 */
    public final int ia, ib, ic, nin, dout, ngnd;
    /** 每相等效电阻（Ω）、母线滤波电容（F）、负载电阻（Ω） */
    public final double phaseR, filterCap, loadR;

    public ThreePhaseRectifierModel(int ia, int ib, int ic, int nin,
                                    int dout, int ngnd,
                                    double phaseR, double filterCap, double loadR) {
        super(build(ia, ib, ic, nin, dout, ngnd, phaseR, filterCap, loadR));
        this.ia = ia; this.ib = ib; this.ic = ic; this.nin = nin;
        this.dout = dout; this.ngnd = ngnd;
        this.phaseR = phaseR;
        this.filterCap = filterCap;
        this.loadR = loadR;
    }

    /** 组合：三相输入等效电阻（各相 nin）+ 母线电容 + 负载 */
    private static Element[] build(int ia, int ib, int ic, int nin,
                                   int dout, int ngnd,
                                   double rPh, double cF, double rL) {
        return new Element[]{
                new Resistor(ia, nin, Math.max(rPh, 1e-9)),
                new Resistor(ib, nin, Math.max(rPh, 1e-9)),
                new Resistor(ic, nin, Math.max(rPh, 1e-9)),
                new Capacitor(dout, ngnd, Math.max(cF, 1e-12)),
                new Resistor(dout, ngnd, Math.max(rL, 1e-9))
        };
    }

    /** 理想三相桥式整流平均直流电压系数（相对线电压峰值）：3√3/π ≈ 1.654 */
    public static double dcFactor() { return 3.0 * Math.sqrt(3.0) / Math.PI; }

    /** 序列化参数：[R_ph, C_f, R_load, 端口] */
    public double[] params() {
        return new double[]{phaseR, filterCap, loadR, ia, ib, ic, nin, dout, ngnd};
    }

    @Override
    public String toString() {
        return "ThreePhaseRectifierModel{in(" + ia + "," + ib + "," + ic + ")"
                + " dc(" + dout + "," + ngnd + ") R=" + phaseR + " C=" + filterCap + "}";
    }
}
