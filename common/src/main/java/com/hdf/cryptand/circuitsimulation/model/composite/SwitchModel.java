package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;

/**
 * 开关复合模型：单个【可变电阻】（参数更新，不改变电路结构）。
 * <p>
 * PowerGrid 开关（Switch/HvSwitch/HvBreaker/Contactor/Fuse）状态切换在
 * 原版里要么改 SwitchedWire 导纳（不触发 addWire），要么断开时移除内部
 * wire（触发 addWire/removeWire → 我们的拓扑重建）。频繁开关（接触器
 * 高频通断/玩家快速开关）若每次都整体重建网络 → 性能问题 + 电压瞬态
 * 跳变（电机抖动）。
 * <p>
 * 本模型把开关表达为【常驻可变电阻】：
 *   - 闭合 = onResistance（实际触点电阻）
 *   - 断开 = OFF_RESISTANCE（1e9 Ω 近似开路，魔法数字）
 * 开关切换 → {@link #setOn}/{@link #setResistance} → 内部 Resistor 参数
 * 变化消息 → ctx.paramVersion++ → 同 ctx 重解（网络不重建）。同时
 * ElectricalNetworkMixin 过滤 SwitchedWire 的 add/remove → 开关切换不再
 * 触发拓扑重建 → 局部重解 + 参数更新。
 * <p>
 * 注意：1e9 Ω 断开近似在闭合回路里吃掉全部压降（负载 0V，物理正确）；
 * 悬空端由叶子修剪等电位处理，不受断开大电阻影响。
 */
public class SwitchModel extends CompositeModel {

    /** 断开近似开路电阻（Ω）——魔法数字。与 PowerGrid SwitchedWire
     *  OFF_CONDUCTANCE=G_MIN·0.5（≈2e8Ω）同量级，比其略大更接近开路。 */
    public static final double OFF_RESISTANCE = 1e9;

    public final int a, b;
    /** 构造时初始闭合电阻（Ω） */
    public final double onResistance;
    /** 当前闭合电阻（可变：过压等场景会更新） */
    private volatile double onR;
    private final Resistor res;

    public SwitchModel(int a, int b, double onResistance) {
        super(new Element[]{ new Resistor(a, b, onResistance) });
        this.a = a;
        this.b = b;
        this.onResistance = onResistance;
        this.onR = onResistance;
        this.res = (Resistor) simple[0];
    }

    /** 开关状态切换：闭合=当前闭合电阻，断开=OFF_RESISTANCE。
     *  内部 Resistor 值变 → 参数变化消息 → 重解（不重建）。 */
    public void setOn(boolean on) {
        res.setResistance(on ? onR : OFF_RESISTANCE);
    }

    /** 更新闭合电阻（触点电阻变化/过压）：闭合中立即生效，断开时只记新值。 */
    public void setResistance(double r) {
        if (r <= 0) return;
        this.onR = r;
        if (isOn()) res.setResistance(r);
    }

    /** 当前是否闭合 */
    public boolean isOn() {
        return res.resistance < OFF_RESISTANCE / 2;
    }

    @Override
    public ElementType type() { return ElementType.RESISTOR; }

    @Override
    public String toString() {
        return "SwitchModel{" + a + "-" + b + " Ron=" + onResistance
                + " on=" + isOn() + "}";
    }
}
