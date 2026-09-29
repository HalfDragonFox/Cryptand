package com.hdf.cryptand.circuitsimulation.model.energy;

import com.hdf.cryptand.circuitsimulation.model.dynamics.DynamicsModel;
import com.hdf.cryptand.circuitsimulation.model.state.StateDriven;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;

/**
 * 能量存储模型（2026-08-12 用户要求：电池/电容共用）。
 * <p>
 * 核心状态：电荷 {@code charge}（库仑，时间相关变量：dQ/dt = i）。
 *   - 电容模式（默认）：V = Q/C，电场储能 E = ½CV²；隔直通交由基础电容元件
 *     的相量导纳 Y = jωC 提供（AC 导通、DC 开路）；带初始电荷（charge > 0）
 *     → 两端有电压 = 【类似电池可放电】。
 *   - 电池模式（{@code batteryMode}）：恒压 + 内阻 + 容量（charge 为剩余电荷，
 *     可充放电）。
 * <p>
 * 求解策略：相量（稳态 AC）能求 → 用相量阻抗（隔直通交）；相量求不出
 * （瞬态充放电 / DC 阶跃）→ 绑定时间相关变量（charge 积分驱动电压）。
 * <p>
 * 2026-08-20 动力学统一：电荷演化委托 {@link DynamicsModel}（一阶动力学）。
 *   - 稳态目标 Q_ss = i·τ = i·R·C（电流 i 驱动电荷趋向该值）
 *   - τ = RC（电阻×电容）即电荷松弛时间常数；τ 大 → 充放电慢（大电容）
 *   - 保留硬积分 {@link #flow}（调用方要精确电荷积分时用）；
 *     {@link #flowDyn} 走动力学（有惯性、平滑）。
 * <p>
 * 2026-08-20 统一运算接口：实现 {@link StateDriven}（advanceState 委托
 * 内部动力学）——复合元件多模型时可直接强转 StateDriven 调用。
 */
public class EnergyModel implements StateDriven {

    /** 电容（F）/电池等效电容 */
    public volatile double capacitance;
    /** 电荷（C）——时间相关变量 */
    public volatile double charge;
    /** 内阻（Ω） */
    public volatile double internalResistance;
    /** 能量容量（J）；<=0 = 无限 */
    public volatile double capacity;
    /** 电池模式（true = 恒压电池；false = 电容） */
    public volatile boolean batteryMode;
    /** 电池开路电压（V，电池模式用） */
    public volatile double batteryVoltage;

    /** 电荷动力学（2026-08-20：τ = R·C，目标 Q_ss = i·τ；min/max = ±容量） */
    private final DynamicsModel dyn;

    public EnergyModel(double capacitance) {
        this.capacitance = Math.max(capacitance, 1e-12);
        this.dyn = new DynamicsModel(
                Math.max(internalResistance * this.capacitance, 1e-6), 0);
        if (capacity > 0) {
            dyn.minValue = -capacity;
            dyn.maxValue = capacity;
        }
    }

    /** 当前端电压（V）：电池 = 恒压；电容 = Q/C */
    public double voltage() {
        return batteryMode ? batteryVoltage : charge / capacitance;
    }

    /** 电荷推进（时间相关变量绑定）：current 流入为正（充电），dt 秒。
     *  受容量限制（capacity > 0 时钳制在 ±capacity）。
     *  【硬积分】精确电荷：dQ = i·dt。 */
    public void flow(double current, double dt) {
        if (dt <= 0) return;
        charge += current * dt;
        if (capacity > 0) {
            charge = Math.max(-capacity, Math.min(capacity, charge));
        }
        if (dyn != null) dyn.setValue(charge); // 同步动力学状态
    }

    /**
     * 自放电时间常数（s）—— 2026-09-13 用户："停电时电容还能持续输出，并且输出
     * 停止时能量正常保存并随时间能量慢慢变低"。
     *
     * 物理来源：介质损耗 + 漏电流（等效并联漏电阻 R_leak，τ_self = R_leak·C）。
     * 默认 600s（10 分钟）——电荷按指数缓慢衰减，"慢慢变低"而不是漏光。
     * ≤0 = 关闭自放电（理想电容，用于对照/测试）。
     */
    public volatile double selfDischargeTauS = 600.0;

    /**
     * 自放电推进（介质损耗/漏电流）：
     *
     *     Q(t+dt) = Q(t) · exp(−dt / τ_self)
     *
     * 与 DynamicsModel 同形式的解析解 —— 无条件稳定、任意 dt 不振荡、
     * O(1) 无查表（用户："不要用查表之类的低效兜底的算法"）。
     * 停电（无外部电流）时由 advanceState 每轮调用 ⇒ 能量自然保存并缓慢衰减。
     */
    public void selfDischarge(double dt) {
        if (!(dt > 0) || !(selfDischargeTauS > 0)) return;
        if (charge == 0) return;
        double k = Math.exp(-dt / selfDischargeTauS);
        if (k >= 1.0) return;
        double q = charge * k;
        if (Math.abs(q) < 1e-9) q = 0; // 近零归零（避免永续微电荷）
        charge = q;
        if (dyn != null) dyn.setValue(q); // 同步内部动力学状态
    }

    /** 电荷动力学推进（2026-08-20）：一阶松弛，Q 趋向 Q_ss = i·τ。
     *  τ = R·C（内部电阻×电容）；比硬积分更平滑（充放电有惯性）。
     *  返回推进后的电荷。 */
    public double flowDyn(double current, double dt) {
        if (dt <= 0) return charge;
        double tau = Math.max(internalResistance * capacitance, 1e-6);
        dyn.setTimeConstant(tau);
        charge = dyn.advance(current * tau, dt);
        return charge;
    }

    /**
     * 【统一运算接口，StateDriven】委托内部动力学推进（当前无外部电流输入
     * → 自然弛豫）。电荷同步已由 syncDyn/flow 维护。返回 false。
     * mode = 当前求解器类型（REAL_DC/COMPLEX_AC，统一接口约定）。
     */
    @Override
    public boolean advanceState(Complex va, Complex vb, double freqHz, double dt, SolveMode mode) {
        if (dyn != null && dt > 0) dyn.advanceState(va, vb, freqHz, dt, mode);
        // 2026-09-13 用户："停电时电容还能持续输出，输出停止时能量正常保存并随时间
        // 能量慢慢变低" —— 每轮叠加自放电（解析解；τ 可配，默认 10 分钟）。
        // 停电时没有 flow() 注入电流，电荷就靠这一步自然衰减 ⇒ 既持续输出又缓慢变低。
        selfDischarge(dt);
        return false;
    }

    /** 储能（J）：E = ½CV² */
    public double energy() {
        return 0.5 * capacitance * voltage() * voltage();
    }

    /** 重置到无电荷 */
    public void reset() {
        charge = 0;
        if (dyn != null) dyn.reset(0);
    }

    /** 同步电荷到动力学状态（外部直接改 charge 后调用） */
    public void syncDyn() {
        if (dyn != null) dyn.setValue(charge);
    }

    @Override
    public String toString() {
        return "EnergyModel{" + (batteryMode ? "bat V=" + batteryVoltage
                : "C=" + capacitance + "F") + " Q=" + String.format("%.3f", charge)
                + "C V=" + String.format("%.1f", voltage()) + "V}";
    }
}

