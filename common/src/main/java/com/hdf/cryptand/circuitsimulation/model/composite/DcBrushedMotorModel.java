package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 简单仿真 DC 直流电机模型（2026-08-29 用户：原版普通电机退回 DC 仿真模型）。
 * <p>
 * 规格（用户）：功率小（500W）、最高转速 5000RPM、可瞬开瞬停。
 * 参考之前普通电机（NormalMotorModel）的统一现实 DC 物理公式链（继承基类
 * {@link ElectroMachineModel#advanceShaft}，不另写公式）：
 * <pre>
 *   EMF = K_E·ω          （K_E = 额定电压/额定角速度，230V → 5000RPM 空载平衡）
 *   I   = (V_ab − EMF)/R （供电自动涌现；断电 I=0 → 摩擦滑行立即停）
 *   T_em = K_T·I         （K_T = K_E，物理严格）
 *   J·dω/dt = T_em − T_load − b·ω   （惯性）
 *   P = min(P_max, σ·ω_r)  （P_max = 500W 小功率封顶）
 *   σ = σ_max × min(1, |ω|/ω_r)     （16384 满刻度量级）
 * </pre>
 * 瞬开瞬停 = 极小转动惯量 J（加速极快）＋断电无电气转矩且摩擦小 → 立即停。
 * 输出/诊断沿用基类 get 接口（getRotorRPM/getStressSU/getOutputPowerW/...）。
 */
public class DcBrushedMotorModel extends ElectroMachineModel {

    /** 额定功率上限（W，用户规格：功率小 500W） */
    private static final double RATED_POWER_W = 500.0;
    /** 额定/最高转速（RPM，用户规格：5000 RPM） */
    private static final double RATED_RPM = 5000.0;

    public DcBrushedMotorModel(int a, int b, int x, double resistance, double inductance,
                               ThermalModel thermal, EnergyModel energy,
                               boolean generatorMode, double emfVoltage) {
        super(a, b, x, resistance, inductance, thermal, energy, generatorMode, emfVoltage);
        // 参考之前普通电机规格基座：额定 230V / 最大 258V / 最大应力 16384，
        // 仅把额定转速从 256RPM 提到 5000RPM（K_E = 230/523.6 ≈ 0.439 → 空载
        // EMF≈230V 平衡在 5000RPM）。恒速关、无设置转速。
        setSpecs(230.0, 258.0, 16384.0, RATED_RPM, false, 0);
        // 功率上限 = 500W（小功率；0 = 自动 maxStress×ω_r ≈ 8.6MW → 必须显式设）
        setMaxPowerW(RATED_POWER_W);
        // ⚠ 2026-08-29 调速教训：5000RPM 电机的转矩常数极小（K_E=230/523.6≈0.439
        //  N·m/A）→ 之前 friction=0.02 相对过大 → 空载稳态只有 ~1368RPM（看着像
        //  "接入不转"）。摩擦必须【极小】（≈0.0008）才能接近 5000RPM 额定：
        //  稳态 ~4500RPM（90%）。
        //  - 瞬开：极小惯量 inertia=0.02（加速 ~2s 到 4500RPM、带载快降）
        //  - 瞬停：见下方 advanceShaft 覆写的【断电一阶制动】（用户明确要求"瞬停"，
        //    与最高 5000RPM 无法同时由粘性摩擦满足 → 断电分支单独加制动，见注释）。
        inertia = 0.02;
        friction = 0.0008;
    }

    /**
     * 断电一阶制动的时间常数（s）：τ=0.3 → 空载断电 ~1.5~2s 内基本停转（"瞬停"）。
     * 只在断电分支生效（有功供电时零影响）。
     */
    private static final double BRAKE_TAU_S = 0.3;

    /**
     * 覆写基类推进，实现用户要求的【瞬开瞬停】里的"瞬停"：
     * <p>
     * 5000RPM 电机转矩常数小（K_E≈0.439），粘性摩擦必须≈0 才到得了额定转速；
     * 但摩擦≈0 → 断电开路后只能滑行几十秒（不是"瞬停"）。基类公式无法同时满足
     * 这两个互斥目标。本模型在【断电分支（!powered）】叠加一个一阶指数制动：
     *   ω' = ω×(1 − dt/τ)，τ = BRAKE_TAU_S = 0.3s
     * 使空载断电 ~1.5~2s 内停转；供电维持不变（即"瞬开"仍靠小惯量 + EMF 反馈）。
     * 只作用于本模型（不波及发电机/恒速/伺服/感应）。这是按用户显式要求的
     * 模型行为，非数值稳定性补丁。
     */
    @Override
    public void advanceShaft(Complex va, Complex vb, double freqHz, double dt) {
        double vabMag = (va != null && vb != null) ? va.sub(vb).abs() : 0;
        super.advanceShaft(va, vb, freqHz, dt);
        boolean powered = vabMag > 0.5 && vabMag < 5.0 * Math.max(maxVoltage, 1e-9)
                && Double.isFinite(vabMag);
        if (!powered && Math.abs(rotorSpeedRadS) > 1e-3) {
            double k = Math.max(0.01, dt) / Math.max(BRAKE_TAU_S, 1e-3);
            double w = rotorSpeedRadS * Math.max(0.0, 1.0 - Math.min(k, 1.0));
            if (Math.abs(w) < 0.5) w = 0; // 近零直接停（避免永续微旋）
            rotorSpeedRadS = w;
        }
    }
}