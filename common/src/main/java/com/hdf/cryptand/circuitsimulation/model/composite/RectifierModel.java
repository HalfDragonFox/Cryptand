package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 整流器复合模型（AC→DC，半波/全波/桥式，为未来准备）。
 * <p>
 * 现实物理：整流器把交流变成直流（二极管单向导通）。理想整流输出 = 输入
 * 峰值 ×（半波 1/π、全波/桥式 2/π 的平均值），输出有纹波，滤波电容平滑。
 * <p>
 * 相量域简化：整流器对交流源是【非线性负载】，相量 MNA 无法直接表达二极管。
 * 本模型把整流器等效为：输入侧呈现等效电阻（吸收有功），输出侧 = 直流母线
 * （滤波电容 + 等效负载电阻）——交流分析时二极管整流表现为"桥式等效电阻"
 * R_bridge = 8·V²/(π²·P)（吸收功率 P），直流侧由外部 DC 电路继续建模。
 * <p>
 * 端口：ain/nin = 交流输入；dout/ngnd = 直流输出。
 * 内部节点：无。
 */
public class RectifierModel extends CompositeModel {

    /** 交流输入端子 + 直流输出端子 */
    public final int ain, nin, dout, ngnd;
    /** 整流类型：HALF / FULL（桥式、全波） */
    public enum Kind { HALF_WAVE, FULL_BRIDGE }
    public final Kind kind;
    /** 等效桥式电阻（Ω）、输出滤波电容（F）、输出负载电阻（Ω） */
    public final double bridgeR, filterCap, loadR;

    public RectifierModel(int ain, int nin, int dout, int ngnd,
                          Kind kind, double bridgeR, double filterCap, double loadR) {
        super(build(ain, nin, dout, ngnd, kind, bridgeR, filterCap, loadR));
        this.ain = ain; this.nin = nin;
        this.dout = dout; this.ngnd = ngnd;
        this.kind = kind;
        this.bridgeR = bridgeR;
        this.filterCap = filterCap;
        this.loadR = loadR;
    }

    /** 组合：输入等效桥式电阻 + 输出滤波电容 + 输出负载 */
    private static Element[] build(int ain, int nin, int dout, int ngnd,
                                   Kind kind, double rBridge, double cFilter, double rLoad) {
        return new Element[]{
                new Resistor(ain, nin, Math.max(rBridge, 1e-9)),
                new Capacitor(dout, ngnd, Math.max(cFilter, 1e-12)),
                new Resistor(dout, ngnd, Math.max(rLoad, 1e-9))
        };
    }

    /** 理想整流输出平均电压 = 峰值 × k（半波 1/π，全波/桥式 2/π） */
    public double averageDcFactor() {
        return kind == Kind.HALF_WAVE ? 1.0 / Math.PI : 2.0 / Math.PI;
    }

    /** 序列化参数：[kind(0=半波,1=桥式), R_b, C_f, R_load, 端口] */
    public double[] params() {
        return new double[]{kind == Kind.HALF_WAVE ? 0 : 1,
                bridgeR, filterCap, loadR, ain, nin, dout, ngnd};
    }

    @Override
    public String toString() {
        return "RectifierModel{" + (kind == Kind.HALF_WAVE ? "half" : "bridge")
                + " in(" + ain + "," + nin + ") dc(" + dout + "," + ngnd
                + ") R=" + bridgeR + " C=" + filterCap + "}";
    }
}
