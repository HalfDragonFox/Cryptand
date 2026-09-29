package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 电容复合模型（2026-08-12 用户要求：电容是复合模型，不是基础电容元件）。
 * <p>
 * 组合基础元件：
 *   - 基础电容元件 {@link Capacitor}：相量导纳 Y = jωC → 【隔直通交】
 *     （AC 导通、DC 开路）；时域 Backward Euler 伴随模型（vPrev 记忆，
 *     时间相关变量）。
 *   - 等效串联电阻 ESR（{@link Resistor}）：真实电容损耗 → 【温度模型】
 *     发热（ESR 的 I²·R）
 * 持有能量模型 {@link EnergyModel}：
 *   - 储能状态（电荷 Q、电压 V = Q/C、容量）——【类似电池】可放电；
 *   - 求解后 {@link #syncCharge} 从端口电压同步电荷（时间相关变量绑定）。
 * <p>
 * 实现 {@link EnergyDevice}（储能）与 {@link ThermalDevice}（ESR 温度）。
 */
public class CapacitorModel extends CompositeModel implements EnergyDevice, ThermalDevice {

    /** 端口节点 + ESR 内部节点 */
    public final int a, b, x;
    /** 能量模型（储能状态） */
    public final EnergyModel energy;
    /** 温度模型（ESR 损耗发热，可 null） */
    public final ThermalModel thermal;
    /** 组合的基础电容元件（相量 jωC 隔直通交 + 时域伴随记忆） */
    private final Capacitor cap;
    /** 等效串联电阻（ESR，可 null） */
    private final Resistor esr;

    public CapacitorModel(int a, int b, int x, double capacitance, double esrResistance,
                          EnergyModel energy, ThermalModel thermal) {
        super(build(a, b, x, capacitance, esrResistance), thermal);
        this.a = a;
        this.b = b;
        this.x = x;
        this.energy = energy == null ? new EnergyModel(capacitance) : energy;
        this.thermal = thermal;
        this.cap = (Capacitor) decompose()[decompose().length - 1];
        Resistor r = null;
        for (Element e : decompose()) {
            if (e instanceof Resistor re) { r = re; break; }
        }
        this.esr = r;
    }

    /** 组合基础元件：a --Resistor(ESR)-- x --Capacitor-- b（ESR≤0 → 直连 a-b） */
    private static Element[] build(int a, int b, int x, double c, double esr) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        // ⚠ 2026-08-30 审计 M9 根因：esr≤0 时直接 a-b 直连储能元件（x 弃用）——
        // 否则 a 与 x 之间无元件 → a 端口悬空（仅 GMin）→ 电容实际未接入电路。
        // 与 MotorModel.build 的 L=0 处理（直接 a-b）一致。
        if (esr > 0) {
            els.add(new Resistor(a, x, esr));
            els.add(new Capacitor(x, b, c));
        } else {
            els.add(new Capacitor(a, b, c));
        }
        return els.toArray(new Element[0]);
    }

    /** 更新电容值（参数刷新）：同步能量模型 + 基础元件（自动发参数变化消息） */
    public void setCapacitance(double c) {
        double nc = Math.max(c, 1e-12);
        if (Double.compare(nc, energy.capacitance) != 0) {
            energy.capacitance = nc;
            cap.setCapacitance(nc);
        }
    }

    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }
    @Override public ThermalModel thermal() { return thermal; }

    /** 当前基础电容 vPrev（充电状态；2026-08-21 跨网络重建/测量持久用） */
    public double capVPrev() { return cap.vPrev; }

    /** 恢复基础电容 vPrev（2026-08-21：网络重建/测量构建时从持久存储恢复） */
    public void setCapVPrev(double v) { cap.vPrev = v; }

    /** 设置孤立标记（2026-08-21 剪线不爆炸）：两端无闭合回路 → 基础电容开路
     *  （不注入 I_hist，不 commit，电荷由能量模型保持）。⚠ 必须同时把内部 ESR
     *  也开路——否则源会直驱 ESR 到悬空端 → 巨大电流 → ESR 发热 → 电容爆炸。 */
    public void setOpenCircuit(boolean open) {
        cap.openCircuit = open;
        if (esr != null) esr.openCircuit = open;
    }

    /** 是否孤立（两端无闭合回路） */
    public boolean isOpenCircuit() { return cap.openCircuit; }

    /** ESR 损耗（平均）：I²·R/2。2026-08-18 电流法：流过内部 ESR（a-x）的
     *  真实电流（nodeVoltages 注入后精确；未注入用 |V|/|Z| 估算兜底）。
     *  DC（ω=0）电容隔直无电流 → 无损耗；AC 有 ESR 发热。
     *  ⚠ 2026-08-21 接入正极爆炸修复：孤立电容（openCircuit，如拆线后再接入
     *  一端）不发热——否则 a 连源（如 10V）、x/b 悬空（GMin=0）时 lossPower
     *  用假电压差算 ESR 损耗 = V²/ESR = 10²/0.005 = 2万W → 瞬间 200°C 爆炸。 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (cap.openCircuit) return 0; // 孤立电容不发热
        if (esr == null) return 0;
        double xc = omega > 0 ? 1.0 / (omega * energy.capacitance) : Double.POSITIVE_INFINITY;
        double z = Math.sqrt(esr.resistance * esr.resistance + xc * xc);
        double zz = (Double.isInfinite(z) || z < 1e-12) ? 0 : z;
        return resistorLoss(va, vb, omega, esr.resistance, a, x, zz);
    }

    @Override public EnergyModel energy() { return energy; }

    /**
     * 时间相关变量绑定：求解后同步储能电荷。
     * 【放电实时性，2026-08-12】优先用时域记忆 vPrev——Backward Euler
     * （Capacitor.stampReal）每轮按电流积分更新 vPrev（放电 → 电流流出 →
     * vPrev 下降 → 下轮伴随源电压更新 → 电路实时重新分配，即"放电时实时
     * 更新电路参数"）；无时域记忆（纯相量稳态）用相量幅值（Q = C·V）。
     */
    @Override
    public void syncCharge(Complex va, Complex vb) {
        if (va == null || vb == null) return;
        double v = cap.vPrev != 0 ? cap.vPrev : va.sub(vb).abs();
        energy.charge = energy.capacitance * v;
    }

    @Override
    public ElementType type() { return ElementType.COMPOSITE; }

    @Override
    public String toString() {
        return "CapacitorModel{" + a + "-" + b + " " + energy + "}";
    }
}
