package com.hdf.cryptand.circuitsimulation.model.dynamics;

import com.hdf.cryptand.circuitsimulation.model.state.StateDriven;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;

/**
 * 动力学模型（2026-08-20 用户命名：惯性/时域统一模型）。
 * <p>
 * 统一描述"带惯量的一阶动力学"：任何物理量 x 按
 * <pre>
 *   τ · dx/dt + x = x_ss(t)          （一阶惯性环节，控制论标准形式）
 *   x_ss = 当前驱动下的稳态值（随输入/外力变化）
 *   τ    = 时间常数（s，越大越"钝"，响应越慢）
 * </pre>
 * 解析解（无条件稳定，任意 dt 不振荡）：
 * <pre>
 *   x(t+dt) = x_ss + (x(t) − x_ss)·exp(−dt/τ)
 * </pre>
 * 同一套动力学适用于所有"状态随时间演化"的物理量——这是能量、温度、
 * 转速、应力等模型的【共同数学基础】：
 *   - 机械动力学：电机转速 ω（τ = J/b，稳态 ω_ss = τ_net/b）——转动惯量
 *   - 热动力学：温度 T（τ = C/G 热时间常数，稳态 T_ss = T_amb + P/G）——热容
 *   - 电动力学：电荷 Q（τ = RC，稳态 Q_ss = C·V）——电容
 *   - 应力动力学：Create 应力（一阶跟随转速，τ = 应力响应时间）
 * <p>
 * 计算时只需实现本模型的推进（advance），无需为每种物理量单独写一套
 * 积分器——统一入口 + 时变目标函数即可覆盖全部场景。
 * <p>
 * 特性：
 *   - 显式传 dt（固定节拍）或按真实时间推进（advanceReal，内部记录时间戳）
 *   - 输入驱动 x_ss 可以是常量或函数（{@code DoubleUnaryOperator}，支持时变）
 *   - 状态钳制（min/max，如转速≥0）、输入平滑（EMA 防抖）、重置
 *   - 线程安全：volatile 状态 + 单线程推进方调用（与 ThermalModel 一致）
 * <p>
 * 【继承设计，2026-08-20】温度/能量等模型可【继承】本类，只需重写
 * {@link #steadyTarget(double)}（把输入 → 稳态目标 x_ss 的计算），然后调
 * {@link #advanceInput(double, double)} / {@link #advanceInputReal(double, long)}
 * ——父类算法（输入平滑 + 解析解）自动完成，子类可再叠加自实现。
 * <p>
 * 【统一运算接口，2026-08-20】实现 {@link StateDriven}（advanceState）——
 * 与 ThermalModel/EnergyModel 共享同一基础运算接口，复合元件多模型时
 * 【直接强转 StateDriven 调用】（无需注册表）：把各模型存为字段，
 * advanceState 里逐个 cast 调用即可。
 */
public class DynamicsModel implements StateDriven {

    /** 时间常数 τ（s）——越大变化越慢；≥ 极小值防除零 */
    public volatile double timeConstant;
    /** 状态下限（默认 -∞；电机转速设 0 防反转） */
    public volatile double minValue = Double.NEGATIVE_INFINITY;
    /** 状态上限（默认 +∞；应力/转速可设上限） */
    public volatile double maxValue = Double.POSITIVE_INFINITY;

    /** 当前状态 x（protected：子类可读写） */
    protected volatile double value;
    /** 上次推进时间戳（ns）；0 = 未初始化（protected：子类可读写） */
    protected long lastUpdateNanos;

    /** 输入平滑系数（0~1，默认 1 = 直接跟随 x_ss；小值 = 输入先低通） */
    private double inputAlpha = 1.0;
    /** 平滑后的驱动值（inputAlpha &lt; 1 时用；protected：子类可读写） */
    protected double smoothedTarget = Double.NaN;

    public DynamicsModel(double timeConstant) {
        this.timeConstant = Math.max(timeConstant, 1e-6);
    }

    /** 带初值构造 */
    public DynamicsModel(double timeConstant, double initialValue) {
        this(timeConstant);
        this.value = initialValue;
    }

    /** 当前状态 */
    public double value() { return value; }

    /**
     * 【稳态目标钩子，子类重写】输入 input → 稳态目标 x_ss。
     * 默认实现 = 输入即稳态（恒等）。温度模型重写为 T_amb+P/G、电荷模型
     * 重写为 i·τ、应力模型重写为 f(转速) 等。
     */
    protected double steadyTarget(double input) { return input; }

    /**
     * 【模板方法】从输入推进（固定节拍 dt）：先 {@link #steadyTarget} 算稳态，
     * 再父类解析解推进。子类直接调本方法即可（也可覆写叠加自实现）。
     */
    public double advanceInput(double input, double dt) {
        return advance(steadyTarget(input), dt);
    }

    /** 【模板方法】从输入按真实时间推进（内部记录时间戳，去重）。 */
    public double advanceInputReal(double input, long nowNanos) {
        return advanceReal(steadyTarget(input), nowNanos);
    }

    /**
     * 【统一运算接口，StateDriven】默认实现 = 向当前平滑目标收敛（无外部
     * 输入时的自然弛豫）。子类（温度/转速等）通常覆写 {@link #steadyTarget}
     * 后用 {@link #advanceInput} 或直接覆写本方法。
     * 返回 true = 状态仍在变化（未到稳态，需下轮重解）；false = 已稳态。
     * mode = 当前求解器类型（REAL_DC/COMPLEX_AC，统一接口约定）。
     */
    @Override
    public boolean advanceState(Complex va, Complex vb, double freqHz, double dt, SolveMode mode) {
        if (dt <= 0) return false;
        double before = value;
        double target = Double.isNaN(smoothedTarget) ? value : smoothedTarget;
        advance(target, dt);
        // 稳态跳过（2026-08-20）：状态未变/已稳态 → false（网络可跳过）
        return Math.abs(value - before) > steadyEpsilon;
    }

    /** 直接设置状态（初始化/外部同步，不走动力学） */
    public void setValue(double v) {
        this.value = clamp(v);
        this.smoothedTarget = this.value;
    }

    /** 仅给状态加增量（即时传导/扰动，不动平滑目标——下次 advance 平滑基线
     *  不受影响；如热扩散 addHeat、外部冲击）。返回新状态。 */
    public double addDelta(double delta) {
        if (delta == 0) return value;
        this.value = clamp(value + delta);
        return value;
    }

    /** 钳制到 [min, max] */
    private double clamp(double v) {
        return Math.max(minValue, Math.min(maxValue, v));
    }

    /** 输入平滑系数（0~1）：1 = 无平滑直接跟随；小 = 输入先滤波（防抖动） */
    public void setInputAlpha(double a) { this.inputAlpha = Math.max(0.05, Math.min(1.0, a)); }

    /**
     * 稳态收敛判据（2026-08-20 用户要求：稳态电路跳过，加强实时能力）：
     * |x − x_ss| 小于该值 → 判定稳定（advanceState 返回 false → 网络跳过）。
     * 绝对容差（默认 1e-3）；对接近 0 的状态相对意义不大，用绝对即可。
     */
    public volatile double steadyEpsilon = 1e-3;

    /** 是否已到达稳态（|x − 当前目标| ≤ steadyEpsilon）。 */
    public boolean isSteady() {
        double t = Double.isNaN(smoothedTarget) ? value : smoothedTarget;
        return Math.abs(value - t) <= steadyEpsilon;
    }

    /**
     * 用目标稳态值推进（固定节拍 dt，s）：
     * <pre>
     *   x(t+dt) = x_ss + (x(t) − x_ss)·exp(−dt/τ)
     * </pre>
     * @return 推进后的状态
     */
    public double advance(double targetSteady, double dt) {
        if (dt <= 0) return value;
        // 输入平滑（可选）：target 先 EMA 低通，防驱动值抖动
        if (Double.isNaN(smoothedTarget)) {
            smoothedTarget = targetSteady;
        } else if (inputAlpha < 1.0) {
            smoothedTarget += inputAlpha * (targetSteady - smoothedTarget);
        } else {
            smoothedTarget = targetSteady;
        }
        double frac = Math.exp(-dt / timeConstant);
        value = clamp(smoothedTarget + (value - smoothedTarget) * frac);
        return value;
    }

    /** 同轮重复推进防抖阈值（ns，与 ThermalModel 一致） */
    private static final long ADVANCE_DEDUP_NS = 100_000_000L;

    /**
     * 按真实时间推进（内部记录上次调用时间戳 → dt 由调用间隔决定）；
     * 同一轮重复调用（间隔 &lt; 100ms）跳过。返回当前状态。
     */
    public double advanceReal(double targetSteady, long nowNanos) {
        if (lastUpdateNanos != 0) {
            long since = nowNanos - lastUpdateNanos;
            if (since > 0 && since < ADVANCE_DEDUP_NS) return value;
        }
        double dt = lastUpdateNanos == 0 ? 0.05
                : Math.min(1.0, Math.max(0.01, (nowNanos - lastUpdateNanos) / 1e9));
        lastUpdateNanos = nowNanos;
        return advance(targetSteady, dt);
    }

    /** 按真实时间推进，目标稳态由函数提供（支持时变驱动：应力/负载变化）。 */
    public double advanceReal(java.util.function.DoubleUnaryOperator targetFn, long nowNanos) {
        if (lastUpdateNanos != 0) {
            long since = nowNanos - lastUpdateNanos;
            if (since > 0 && since < ADVANCE_DEDUP_NS) return value;
        }
        double dt = lastUpdateNanos == 0 ? 0.05
                : Math.min(1.0, Math.max(0.01, (nowNanos - lastUpdateNanos) / 1e9));
        lastUpdateNanos = nowNanos;
        return advance(targetFn.applyAsDouble(value), dt);
    }

    /**
     * 【线性时间差推进，2026-08-26 用户：断电空转】：
     * 不做指数弛豫（非 advanceReal），直接算【两次调用】的时间差 dt，按固定
     * 每秒变化量【线性】增减：Δx = ratePerSec × dt（ratePerSec 正=增、负=减）。
     * <p>用户规格："直接计算两次的时间差，然后根据 1s 固定空转应力消耗去算这次
     * 计算减多少"——即每次被调（无论外部推进稀疏/步长大小）都拿真实流逝秒乘以
     * 固定每秒消耗。首调用默认 dt=0.05（避免首次跳变/不降）。dt 上限 10s 仅防
     * 长暂停/挂机跳变（常规节拍 0.01~0.1s 完全不受影响）。返回推进后状态。
     */
    public double advanceLinear(double ratePerSec, long nowNanos) {
        double dt = lastUpdateNanos == 0 ? 0.05
                : Math.min(10.0, Math.max(0.0, (nowNanos - lastUpdateNanos) / 1e9));
        lastUpdateNanos = nowNanos;
        if (dt <= 0) return value;
        value = clamp(value + ratePerSec * dt);
        return value;
    }

    /** 重置到初值（并清空时间戳/平滑） */
    public void reset(double initialValue) {
        value = clamp(initialValue);
        lastUpdateNanos = 0;
        smoothedTarget = Double.NaN;
    }

    public void reset() { reset(0); }

    /** 当前时间常数（s） */
    public double tau() { return timeConstant; }

    /** 设置时间常数（越大越"钝"） */
    public void setTimeConstant(double tau) { this.timeConstant = Math.max(tau, 1e-6); }

    @Override
    public String toString() {
        return "DynamicsModel{τ=" + String.format("%.3f", timeConstant)
                + "s x=" + String.format("%.2f", value) + "}";
    }
}
