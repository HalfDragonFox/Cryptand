package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * ===== 原版三电机（普通/恒速/伺服）的机械侧模型（2026-09-13 用户指令）=====
 *
 * 用户："每次异步线程计算完成转速后发送消息更新到主线程"。
 *
 * 电气侧：纯电阻（继承 {@link ResistorModel}）——无 EMF、无内部节点、无反馈，
 *         与原版 {@code buildCircuit}（coil = builder.connect(resistance(), …)）一致。
 * 机械侧：原版公式 {@code rpm = P / torque() × 60π/2 × sign(I)}，{@code P = I²·R}，
 *         限幅 ±maxRPM —— 即开即停、固定应力、无反馈。
 * 上报：每轮求解后经 {@link #lossPower} 钩子 → 心跳策略（转速变化 > 0.05 rad/s
 *       立即发；不变每 250ms 发一次，与 {@code ElectroMachineModel} 相同）→
 *       shaftListener → {@code EngineBus.post(ROTOR_SPEED)} → 主线程 BE 应用。
 *
 * ⚠ 为什么把上报挂在 lossPower：求解器每轮对"注册了温度模型的设备"调用它
 *   （{@code EngineThermalCompute.computeDeviceHeatOne}），且入参正是两端电压
 *   —— 这是引擎侧唯一"每轮 + 有端子电压"的稳定钩子。
 *   （{@code commit(va,vb,dt)} 由求解器对【简单元件】调用，复合模型收不到，
 *     不能用作设备级钩子。）
 *
 * ⚠ 本模型不产生 EMF：{@code I = V/R} 由外电路决定，转速只是"读出来再算"的
 *   输出量，绝不反馈回电气侧 —— 这就是用户要的"不会有反馈"。
 */
public class VanillaMotorModel extends ResistorModel {

    /**
     * 转速 **+ 应力** 事件监听（引擎侧每轮回调）。
     *
     * 2026-09-13 用户："应力和转速都通过每次计算发送" —— 因此本模型的回调
     * 带第 3 个参数（应力 SU），与 {@code ElectroMachineModel.MachineShaftListener}
     * （只有转速）刻意区分：原版电机的应力由本模型按
     * {@code 应力 = |rpm| × capacity} 算出（与原版恒速电机的
     * {@code generatedSU = clamp(avgSpeed/5) × capacity} 语义一致）。
     */
    public interface MotorShaftListener {
        void onRotorSpeed(double radS, double rpm, double stressSU);
    }

    public volatile MotorShaftListener motorListener;

    /** 原版 torque()（N·m；主线程组装时读取后传入，引擎线程只读本字段） */
    private final double torque;
    /** 原版 maxRPM（主线程读取后传入） */
    private final double maxRpm;
    /** Create 应力容量（SU/RPM，主线程读 BlockStressValues.getCapacity(block) 传入；
     *  ≤0 = 未取到 → 应力发 NaN，由 BE 保持原值）。 */
    private final double capacityPerRpm;
    /** 绕组电阻（Ω；构造时固定，供转速公式使用） */
    private final double resistance;

    /* 上报心跳（与 ElectroMachineModel 同策略）：转速变化 > 0.05 rad/s 立即发，
     * 不变也每 250ms 发一次 —— 避免"稳态转速不变 → 消息断流 → BE 收不到更新"。 */
    private volatile double lastEmittedRadS = Double.NaN;
    private volatile long lastEmitMs;

    /** 最近一次计算出的转速（RPM，诊断） */
    public volatile double lastRpm = Double.NaN;
    /** 最近一次算得的电流（A，诊断） */
    public volatile double lastCurrentA;
    /** 最近一次算得的应力（SU；NaN = capacity 未知，未下发） */
    public volatile double lastStressSU = Double.NaN;

    public VanillaMotorModel(int a, int b, double resistance, ThermalModel thermal,
                             double torque, double maxRpm, double capacityPerRpm) {
        super(a, b, resistance, thermal);
        this.resistance = resistance > 0 ? resistance : 25.6;
        this.torque = torque;
        this.maxRpm = maxRpm > 0 ? maxRpm : 256.0;
        this.capacityPerRpm = capacityPerRpm;
    }

    /** 绑定转速+应力监听（引擎 → 主线程消息；主线程组装时调用） */
    public void bindMotorListener(MotorShaftListener l) {
        this.motorListener = l;
    }

    /* ===== BE 引用（2026-09-13 用户："组装器绑定时直接绑定对应 BE 类引用"、
     *  "直接传递 BE 类引用作为消息参数传递到主线程然后使用"）=====
     * 类型为 Object —— common 层保持零 MC 依赖（与 engine 不碰 Level/BE 的铁律
     *  一致：引擎线程【只持有不访问】，引用随消息回主线程后由主线程使用）。
     * 组装器在主线程 refreshCache 时把真实 BE 放进 MotorAssembler.BE_REF，
     *  后台 assembleFromCache 取出绑到本字段 → listener 发消息时带上。 */
    public volatile Object beRef;

    public void bindBe(Object be) { this.beRef = be; }
    public Object be() { return beRef; }

    /** 每轮求解后：先算损耗（基类，温度推进），再用端子电压算转速并上报。 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        double loss = super.lossPower(va, vb, omega);
        try {
            report(va, vb);
        } catch (Throwable ignored) {
        }
        return loss;
    }

    /** 原版公式算转速 + 心跳上报（引擎线程执行，只碰本对象字段） */
    private void report(Complex va, Complex vb) {
        if (!(torque > 0) || va == null || vb == null) return;
        Complex d = va.sub(vb);
        double i = d.abs() / resistance;            // 幅值电流（A）
        double sign = (d.re >= 0) ? 1.0 : -1.0;     // 方向（相量实部符号）
        // rpm = P / torque × 60π/2 × sign(I)，P = I²R（等价原版 V²/R）
        double rpm = i * i * resistance / torque * (60.0 * Math.PI / 2.0) * sign;
        if (!Double.isFinite(rpm)) rpm = 0;
        if (rpm > maxRpm) rpm = maxRpm;
        if (rpm < -maxRpm) rpm = -maxRpm;
        // 应力（2026-09-13 用户："应力和转速都通过每次计算发送"）——
        // 与原版恒速电机语义一致：应力容量 = 转速 × capacity（SU）。
        double stress = (capacityPerRpm > 0) ? (rpm * capacityPerRpm) : Double.NaN;
        lastCurrentA = i;
        lastRpm = rpm;
        lastStressSU = stress;
        double radS = rpm * (2.0 * Math.PI) / 60.0;
        MotorShaftListener l = motorListener;
        if (l == null) return;
        long now = System.currentTimeMillis();
        if (Double.isNaN(lastEmittedRadS) || Math.abs(radS - lastEmittedRadS) > 0.05
                || now - lastEmitMs >= 250) {
            lastEmittedRadS = radS;
            lastEmitMs = now;
            try {
                l.onRotorSpeed(radS, rpm, stress);
            } catch (Throwable ignored) {
            }
        }
    }

    public double torque() { return torque; }
    public double maxRpm() { return maxRpm; }
    public double windingResistance() { return resistance; }

    @Override
    public String toString() {
        return "VanillaMotorModel{" + a + "-" + b + " R=" + resistance
                + " torque=" + torque + " maxRpm=" + maxRpm
                + " rpm=" + String.format("%.1f", lastRpm) + "}";
    }
}
