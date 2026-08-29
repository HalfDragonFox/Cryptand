package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementBinding;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 电机/R-L 绕组复合模型：绕组 = 铜阻 R 串联电感 L（基础元件组合）。
 * <p>
 * 实际电机（电动机 ElectricMotor / 恒速电机 ConstantSpeedMotor / 发电机
 * Generator / 换向器 Commutator）以及加热器/电磁铁/风扇等 R-L 绕组设备
 * 通过【包含】本模型做电路解析：
 *   - 分解元件：a --Resistor(R)-- x --Inductor(L)-- b（x = R-L 串联内部节点）
 *   - 纯阻设备（灯具/碳堆等）：L≈0 → 只 a --Resistor(R)-- b（x 退化为内部点）
 * <p>
 * 实现 {@link ThermalDevice}：可配 {@link ThermalModel}（温度模型：散热值/
 * 热容/最高温可配置，风扇冷却提高散热系数）做绕组发热/过热模拟。
 * 参数为固定量（魔法数字），更新参数并重新组合后即可重算。
 */
public class MotorModel extends CompositeModel implements ThermalDevice {

    /** 两端口 + R-L 串联内部节点（由外部分配） */
    public final int a, b, x;
    /** 绕组铜阻（Ω）、电感（H）——固定参数 */
    public final double resistance, inductance;
    /** 温度模型（可 null：不参与温度模拟；与基类同步引用，统一 update 推进） */
    public final ThermalModel thermal;

    public MotorModel(int a, int b, int x, double resistance, double inductance,
                      ThermalModel thermal) {
        // 温度模型也传给基类 → CompositeElement.update() 统一推进（算损耗→温度）
        super(build(a, b, x, resistance, inductance), thermal);
        this.a = a;
        this.b = b;
        this.x = x;
        this.resistance = resistance;
        this.inductance = inductance;
        this.thermal = thermal;
    }

    /** 组合简单元件：R 串联 L；纯阻（L=0）直接 a-b（不用内部点 x——否则
     *  悬空端 b 无元件引用成孤立节点 → 求解 GMin≈0 → 跨设备电压非零）。 */
    private static Element[] build(int a, int b, int x, double r, double l) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        if (r > 0) {
            if (l > 0) {
                els.add(new Resistor(a, x, r));
            } else {
                els.add(new Resistor(a, b, r));
            }
        }
        if (l > 0) els.add(new Inductor(x, b, l));
        return els.toArray(new Element[0]);
    }

    /** 当前绕组阻抗 |Z| = √(R² + (ωL)²)（ω 由调用方传，供温度/电流计算） */
    public double impedanceAt(double omega) {
        double xl = omega * inductance;
        return Math.sqrt(resistance * resistance + xl * xl);
    }

    /** 更新绕组/电阻值（可变电阻：碳堆/变阻器等可调设备）。
     *  内部 Resistor.setResistance 自动发参数变化消息 → ctx.paramVersion++ →
     *  重解（不重建网络）。 */
    public void setResistance(double r) {
        if (r <= 0) return;
        for (Element el : simple) {
            if (el instanceof Resistor res) {
                res.setResistance(r);
            }
        }
    }

    /**
     * 绑定参数变动回调（2026-08-12）：外部全局调整绑定源（如虚拟设备快照
     * setResistance）→ 应用到内部基础元件 → 自动重解，不依赖 MC 模型更新。
     */
    @Override
    protected void onBindingChanged(ElementBinding b) {
        double r = b.boundResistance();
        if (r > 0) setResistance(r);
    }

    // ===== 统一复合元件生命周期（CompositeElement） =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }

    /**
     * 绕组铜耗（平均）：I²·R/2。
     * <p>2026-08-18 用户要求：优先用【流过内部电阻的真实支路电流】算发热，
     * 而非端口电压差——R 串联在 a-x（L>0）时 I = |V_a − V_x|/R；纯阻
     * （L=0，R 在 a-b 即端口）时 I = |V_a − V_b|/R；nodeVoltages 未注入 →
     * 兜底用端口电压差 / 总阻抗估算（I = |V_ab| / |Z|）。
     */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (resistance <= 0) return 0;
        double iPeak;
        Complex vx = nodeVoltage(x);
        Complex vaa = nodeVoltage(a);
        if (inductance > 0 && vx != null && vaa != null) {
            // 内部电阻支路电流（R 串联在 a-x）：I = |V_a − V_x| / R
            iPeak = vaa.sub(vx).abs() / resistance;
        } else if (inductance <= 0) {
            // 纯阻：R 在 a-b（内部电阻即端口电阻）
            iPeak = va.sub(vb).abs() / resistance;
        } else {
            // 未注入内部电压：|V_ab| / |Z| 估算
            double z = impedanceAt(omega);
            iPeak = z < 1e-12 ? 0 : va.sub(vb).abs() / z;
        }
        return iPeak * iPeak * resistance / 2.0;
    }

    @Override
    public ElementType type() { return ElementType.INDUCTOR; }

    /** 序列化参数：[R, L, a, b, x] */
    public double[] params() {
        return new double[]{resistance, inductance, a, b, x};
    }

    @Override
    public String toString() {
        return "MotorModel{" + a + "-" + b + " R=" + resistance + " L=" + inductance
                + " x=" + x + " thermal=" + (thermal == null ? "none" : "on") + "}";
    }
}
