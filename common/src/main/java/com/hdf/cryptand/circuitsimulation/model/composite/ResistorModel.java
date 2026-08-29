package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementBinding;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 电阻复合模型（2026-08-12 用户要求：电阻也带温度模型，组合为复合元件）。
 * <p>
 * 组合基础电阻 + 温度模型（{@link ThermalModel}）：
 *   - 损耗 I²·R/2 → 温度推进（散热与发热同时，解析解稳定）
 *   - 过热 → 事件接收器通知实际模型（基类支持）
 *   - 绑定数据（ElementBinding）：外部全局调整电阻 → 自动应用重解
 * 与原版导线（WireComposite）一致的温度架构。
 */
public class ResistorModel extends CompositeModel implements ThermalDevice {

    public final int a, b;
    public final ThermalModel thermal;
    private final Resistor res;

    public ResistorModel(int a, int b, double resistance, ThermalModel thermal) {
        super(new Element[]{new Resistor(a, b, resistance)}, thermal);
        this.a = a;
        this.b = b;
        this.thermal = thermal;
        this.res = (Resistor) decompose()[0];
    }

    /** 更新电阻值（可变电阻：参数刷新/绑定应用） */
    public void setResistance(double r) {
        res.setResistance(r);
    }

    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 电阻发热（平均）：I²·R/2 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        double i = va.sub(vb).abs() / res.resistance;
        return i * i * res.resistance / 2.0;
    }

    /** 绑定参数变动（外部全局调阻 → 自动应用重解） */
    @Override
    protected void onBindingChanged(ElementBinding b) {
        double r = b.boundResistance();
        if (r > 0) setResistance(r);
    }

    @Override
    public ElementType type() { return ElementType.RESISTOR; }

    @Override
    public String toString() {
        return "ResistorModel{" + a + "-" + b + " R=" + res.resistance
                + " thermal=" + (thermal == null ? "none" : "on") + "}";
    }
}
