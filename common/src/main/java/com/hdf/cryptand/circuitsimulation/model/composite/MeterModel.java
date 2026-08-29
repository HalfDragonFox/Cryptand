package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 万用表/电流表复合模型：由简单元件组合（3 端子 a/b/c）。
 *
 *   series：a−b 低阻（默认 0.05Ω，串联测流——电流从 a 流入经 series 到 b）
 *   shunt： a−c 高阻（默认 20MΩ，分流测压——电压表并联支路）
 *
 * 实际电流表/万用表通过【包含】本模型做电路解析（series 测流、shunt 测压），
 * 并可搭配 {@link com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel}
 * 模拟温度（series 低阻流过大电流时发热）。
 *
 * 参数（电阻/分流值）为魔法数字（固定量），更新设备参数即重算。
 */
public class MeterModel extends CompositeModel {

    /** 端子：a=公共端，b=series 端（测流），c=shunt 端（测压） */
    public final int a, b, c;
    /** series 电阻（Ω，低阻串联）与 shunt 电阻（Ω，高阻分流） */
    public final double seriesR, shuntR;

    public MeterModel(int a, int b, int c, double seriesR, double shuntR) {
        super(new Element[]{
                new Resistor(a, b, Math.max(seriesR, 1e-9)),
                new Resistor(a, c, Math.max(shuntR, 1e-9))
        });
        this.a = a; this.b = b; this.c = c;
        this.seriesR = seriesR; this.shuntR = shuntR;
    }

    /** series 电阻元件（低阻测流） */
    public Resistor seriesResistor() {
        for (Element e : simple) {
            if (e instanceof Resistor r && (r.nodeA() == a && r.nodeB() == b
                    || r.nodeA() == b && r.nodeB() == a)) return r;
        }
        return null;
    }

    /** shunt 电阻元件（高阻测压） */
    public Resistor shuntResistor() {
        for (Element e : simple) {
            if (e instanceof Resistor r && (r.nodeA() == a && r.nodeB() == c
                    || r.nodeA() == c && r.nodeB() == a)) return r;
        }
        return null;
    }

    @Override
    public ElementType type() { return ElementType.RESISTOR; }

    /** 序列化参数：[seriesR, shuntR, a, b, c] */
    public double[] params() { return new double[]{seriesR, shuntR, a, b, c}; }

    @Override
    public String toString() {
        return "MeterModel{a=" + a + " b=" + b + " c=" + c
                + " series=" + seriesR + "Ω shunt=" + shuntR + "Ω}";
    }
}
