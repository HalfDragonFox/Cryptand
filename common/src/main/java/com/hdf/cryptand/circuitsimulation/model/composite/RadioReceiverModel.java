package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 无线电接收机复合模型（调谐选频 + 检波解调 + 输出负载，为未来准备）。
 * <p>
 * 现实物理：接收机天线感应微弱射频信号 → 调谐回路（LC 选频，滤除邻频）→
 * 检波（二极管整流包络，AM 解调）→ 音频放大 → 扬声器。灵敏度/选择性由
 * 调谐 Q 值决定。
 * <p>
 * 相量域简化：输入调谐 LC（选频，谐振在载频）+ 输入阻抗 R_in + 检波负载
 * R_load（解调后音频输出端）。检波二极管以负载电阻线性近似。
 * <p>
 * 端口：ant/ret = 天线输入端子；out/gnd = 解调音频输出。内部节点 x 分配。
 */
public class RadioReceiverModel extends CompositeModel {

    /** 天线输入端子 + 音频输出端子 */
    public final int ant, ret, out, gnd, x;
    /** 调谐电感（H）、电容（F）、输入阻抗（Ω）、检波负载（Ω） */
    public final double tuneL, tuneC, inputR, detectLoadR;

    public RadioReceiverModel(int ant, int ret, int out, int gnd, int x,
                              double tuneL, double tuneC, double inputR,
                              double detectLoadR) {
        super(build(ant, ret, out, gnd, x, tuneL, tuneC, inputR, detectLoadR));
        this.ant = ant; this.ret = ret; this.out = out; this.gnd = gnd; this.x = x;
        this.tuneL = tuneL; this.tuneC = tuneC;
        this.inputR = inputR; this.detectLoadR = detectLoadR;
    }

    /** 组合：输入阻抗 + 调谐 LC（ant-x 电感，x-ret 电容）+ 检波负载 */
    private static Element[] build(int ant, int ret, int out, int gnd, int x,
                                   double l, double c, double rIn, double rLoad) {
        return new Element[]{
                new Resistor(ant, ret, Math.max(rIn, 1e-9)),
                new Inductor(ant, x, Math.max(l, 1e-9)),
                new Capacitor(x, ret, Math.max(c, 1e-12)),
                new Resistor(out, gnd, Math.max(rLoad, 1e-9))
        };
    }

    /** 调谐频率（Hz）= 1/(2π√(LC)) */
    public double tuneFrequency() {
        if (tuneL <= 0 || tuneC <= 0) return 0;
        return 1.0 / (2 * Math.PI * Math.sqrt(tuneL * tuneC));
    }

    /** 序列化参数：[L, C, R_in, R_load, 端口] */
    public double[] params() {
        return new double[]{tuneL, tuneC, inputR, detectLoadR, ant, ret, out, gnd, x};
    }

    @Override
    public String toString() {
        return "RadioReceiverModel{ant(" + ant + "," + ret + ") out(" + out
                + "," + gnd + ") f=" + String.format("%.0f", tuneFrequency()) + "Hz}";
    }
}
