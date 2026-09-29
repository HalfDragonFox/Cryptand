package com.hdf.cryptand.circuitsimulation.model.thermal;

import com.hdf.cryptand.circuitsimulation.model.dynamics.DynamicsModel;

/**
 * 一阶热模型（【继承】 {@link DynamicsModel} 统一动力学，2026-08-20）。
 *
 * 热方程（集总参数一阶模型，散热与发热同时求解）：
 *   C · dT/dt = P_in − G_eff · (T − T_amb)
 *   G_eff = G · K_fan（有效散热系数 = 基础散热值 × 风扇冷却倍率）
 *   K_fan = 风扇冷却倍率（>1 = 被鼓风机吹，提高散热；默认 1）
 *   G = 基础散热系数（W/K，散热值，越大散热越快）
 *   C = 热容（J/K，越大升温越慢），T_amb = 环境温度（K）
 *
 * 继承设计（用户要求）：只重写 {@link #steadyTarget(double)}（功率 → 稳态
 * 温度 T_ss = T_amb + P/G_eff），推进用父类 {@link #advanceInput} /
 * {@link #advanceInputReal}（输入平滑 + 解析解自动完成）；本类叠加热语义
 * （温度单位/过热判定/散热系数）。不再单独持有 DynamicsModel 实例。
 *
 * 用于复合元件/设备的温度模拟（电流表、变压器等过热行为）：
 * 实际设备通过【包含】本模型，每仿真步用发热功率推进温度；
 * 被鼓风机吹时调用 {@link #setCoolingMultiplier} 提高最终散热系数。
 */
public class ThermalModel extends DynamicsModel {

    /** 基础散热值（W/K）——热量散到环境的速率，越大散热越快 */
    public final double conductance;
    /** 热容（J/K）——温度惯性，越大升温越慢 */
    public final double heatCapacity;
    /** 环境温度（K），默认 293.15 = 20°C */
    public final double ambient;
    /** 最高安全温度（K），超过判过热 */
    public final double maxTemp;

    /** 风扇冷却倍率（>1 = 被鼓风机吹，提高散热系数） */
    private double coolingMultiplier = 1.0;

    /** 外部附加散热（倍率贡献 k，可叠加；2026-09-12 用户设计：风扇按台累加、
     *  风扇被破坏时只减掉该风扇那一份 → 多台风扇线性叠加互不干扰）。
     *  G_eff = conductance × (coolingMultiplier + extraCoolingFactor)。
     *  volatile：主线程写（FanCoolingRegistry 应用），后台线程读（温度推进）。 */
    private volatile double extraCoolingFactor = 0.0;

    /** 温度计算节流（2026-08-21 用户要求可配置：每计算 N 次才推进一次温度）。
     *  neoforge ConfigLoad 启动时写入；1 = 每次计算都推进。
     *  节流时用【累积 dt + 最近功率】推进 → 温度速率不变，仅更新频率降低。 */
    public static volatile int temperatureComputeInterval = 1;

    /** 节流计数器/累积 dt/最近功率（实例级） */
    private int computeCounter;
    private double pendingDt;
    private double lastPower;

    /** 功率平滑系数（0~1，越大跟随越快；默认 0.1 → 时间常数 ~10 tick = 0.5s）。
     *  网络重建/负载调节导致功率逐 tick 波动 → 小 α 让稳态温度缓慢跟随，
     *  温度平滑不跳动（响应稍慢但稳定）。 */
    private static final double POWER_ALPHA = 0.1;

    public ThermalModel(double conductance, double heatCapacity, double ambient, double maxTemp) {
        // 父类：τ = C/G_eff（初始 K_fan=1），初值 = 环境温度
        super(Math.max(heatCapacity, 1e-9) / Math.max(conductance, 1e-9), ambient);
        this.conductance = Math.max(conductance, 1e-9);
        this.heatCapacity = Math.max(heatCapacity, 1e-9);
        this.ambient = ambient;
        this.maxTemp = maxTemp;
        this.setInputAlpha(POWER_ALPHA); // 功率 EMA 平滑在父类输入侧完成
        this.minValue = ambient;         // 温度不低于环境
    }

    /** 便捷构造：环境 20°C，最高 200°C */
    public ThermalModel(double conductance, double heatCapacity) {
        this(conductance, heatCapacity, 293.15, 473.15);
    }

    /** 有效散热系数（W/K）＝基础散热值 ×（风扇冷却倍率 + 外部附加散热贡献） */
    public double effectiveConductance() {
        return conductance * (coolingMultiplier + extraCoolingFactor);
    }

    /** 设置风扇冷却倍率（>1 = 被鼓风机吹，提高最终散热系数） */
    public void setCoolingMultiplier(double m) {
        this.coolingMultiplier = Math.max(0.1, m);
        // 冷却倍率改变 → 时间常数 τ = C/G_eff 随之变（父类重设）
        this.setTimeConstant(heatCapacity / effectiveConductance());
    }

    public double getCoolingMultiplier() { return coolingMultiplier; }

    /**
     * 设置【外部附加散热】贡献 k（0 = 无附加；风扇冷却用）。
     *
     * <p>与 {@link #setCoolingMultiplier} 的区别：倍率是"整体替换"（同一时刻只能有一个
     * 值），本值是"叠加量"——调用方（FanCoolingRegistry）把若干台风扇的贡献累加后
     * 一次性写入，风扇被破坏时减掉自己那份再写回，因此多风扇天然可叠加、可精确撤销。
     *
     * <p>只改 volatile 字段 + 重算时间常数；温度推进（后台线程）下一轮即生效，
     * 不需要重建温度模型（温度与热惯性全程保留）。
     */
    public void setExtraCoolingFactor(double k) {
        double nk = Math.max(0.0, k);
        if (nk == extraCoolingFactor) return;
        this.extraCoolingFactor = nk;
        // 附加散热改变 → 时间常数 τ = C/G_eff 随之变（父类重设）
        this.setTimeConstant(heatCapacity / effectiveConductance());
    }

    /** 当前外部附加散热贡献 k（0 = 无） */
    public double extraCoolingFactor() { return extraCoolingFactor; }

    /** 外部附加散热的物理量（W/K）= conductance × k（日志/诊断用） */
    public double extraConductanceWPerK() { return conductance * extraCoolingFactor; }

    /** 当前温度（K） */
    public double getTemperature() { return value(); }

    /** 稳态目标（继承钩子重写）：功率 P → 稳态温度 T_ss = T_amb + P/G_eff */
    @Override
    protected double steadyTarget(double power) {
        return ambient + Math.max(0, power) / effectiveConductance();
    }

    /**
     * 施加发热功率 P（W）推进温度 dt（s）——【线性累加】温度变量：
     * 每次计算向温度变量【加减】增量（能量守恒显式欧拉）：
     *   ΔT = (P − G·(T − T_amb)) / C × dt
     * 短暂大功率只产生一次有限增量，不会把温度瞬时拉向 P/G 稳态（用户
     * 2026-08-23：温度计算为线性累加值，不会因短暂计算温度非常高就立马高温）。
     * 2026-08-21 节流保留（每 N 次才推进，速率不变）。
     */
    public double update(double power, double dt) {
        int interval = temperatureComputeInterval;
        if (interval > 1) {
            lastPower = power;
            pendingDt += dt;
            if (++computeCounter % interval != 0) return value();
            double d = pendingDt;
            pendingDt = 0;
            return linearStep(lastPower, d);
        }
        return linearStep(power, dt);
    }

    /** ⚠ 2026-08-30 固定步长推进（引擎仿真时间——导线组/模型每轮固定 dt；
     *  真实时间 realDt 在快轮次（毫秒）下升温极慢——固定 0.05s/轮累积正确）。 */
    public double advanceStep(double power, double dt) {
        return linearStep(power, dt > 0 ? dt : 0.05);
    }

    /** 按真实时间推进（内部记录上次调用时间戳）；同为线性累加。 */
    public double advance(double power, long nowNanos) {
        int interval = temperatureComputeInterval;
        if (interval > 1) {
            if (++computeCounter % interval != 0) return value();
            return linearStep(power, realDt(nowNanos));
        }
        return linearStep(power, realDt(nowNanos));
    }

    /** 显式欧拉线性累加步（含稳定性保护：dt 截断到 0.5×C/G） */
    private double linearStep(double power, double dt) {
        if (dt <= 0) return value();
        double g = effectiveConductance();
        double c = Math.max(heatCapacity, 1e-9);
        // 显式欧拉稳定条件 dt < 2·C/G；截断到 0.5×C/G（不振荡但保持线性速率）
        if (g > 0) dt = Math.min(dt, 0.5 * c / g);
        double deltaT = (power - g * (value() - ambient)) / c * dt;
        addDelta(deltaT);
        return value();
    }

    /** 真实时间间隔（advance 用；上次调用时间戳） */
    private double realDt(long nowNanos) {
        long last = lastNanos;
        lastNanos = nowNanos;
        if (last <= 0) return 0;
        return Math.max(0, (nowNanos - last) / 1e9);
    }

    /** 上次真实时间戳（advance 用） */
    private long lastNanos;

    /** 直接设置温度（K），用于初始化/外部同步 */
    public void setTemperature(double kelvin) {
        setValue(Math.max(ambient, kelvin));
    }

    /**
     * 热扩散传导（2026-08-18 热扩散模型）：把热量 heatJ（焦耳）按热容直接转成
     * 温度变化——扩散是即时传导（不走功率 EMA 平滑/解析解），下限为环境温度。
     * 由扩散执行方决定方向（单向：仅源更热时传导），本方法只负责温度增减。
     */
    public void addHeat(double heatJ) {
        if (heatJ == 0) return;
        addDelta(heatJ / heatCapacity);
    }

    /** 摄氏温度（°C） */
    public double tempCelsius() { return getTemperature() - 273.15; }

    /** 高于环境温度的温升（K） */
    public double tempAboveAmbient() { return getTemperature() - ambient; }

    /** 是否过热（≥ 最高安全温度） */
    public boolean overheated() { return getTemperature() >= maxTemp; }

    /** 重置到环境温度（并清空功率平滑样本） */
    public void reset() {
        super.reset(ambient);
    }

    @Override
    public String toString() {
        return "ThermalModel{G=" + conductance + "×" + String.format("%.1f", coolingMultiplier)
                + "W/K C=" + heatCapacity + "J/K T=" + String.format("%.1f", tempCelsius()) + "°C}";
    }
}
