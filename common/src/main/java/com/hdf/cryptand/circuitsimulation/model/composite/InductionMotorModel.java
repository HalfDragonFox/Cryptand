package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 单相 AC 感应电机模型（2026-08-28 用户：现实参数体系，普通电机专用）。
 * <p>
 * 现实规格（单相电容运转感应电机，2 极 / 50Hz / 230V AC）：
 *   - 同步转速 3000 RPM（ω_s=314.16）；额定输出 1 kW；效率 η=80%；功率因数 cosφ=0.8
 *   - 额定转速 ~2900 RPM（转差 ~3.3%）→ 额定转矩 T_rated = P/ω = 3.29 N·m
 *   - 应力【单独单位】：σ[SU] = T[N·m] × 16（用户：扭矩强制×16 作为应力）
 *   - 功率【物理】：P = T·ω（输出瓦特；不再 DC 的 P=σ·ω_r 规格映射）
 * <p>
 * 机械 = 【完整异步感应曲线】（重载难启动的真实特性，用户选"完整异步曲线"）：
 *   - 转矩-转速曲线（线性插值表 ×T_rated → {@link #torqueFactor}）：
 *     ω=0 启动转矩 = 1.30×T_rated（电容辅助起动：满载起得来、超载起不来）
 *     ω≈65% 同步 → 最大(拉出)转矩 ≈1.9×T_rated
 *     ω≈96.7% 同步（额定点）→ 1.00×T_rated
 *     ω→同步（空载）→ → 0（s=0 无转矩）
 *     ω&gt;同步（被拖动）→ 转矩负 = 发电/再生制动
 *     ω&lt;0（外力反拖）→ 转矩仍为正（拖回正转；单相不自反转）
 *   - 电压平方修正：(Vab/230)²（感应转矩 ∝ V²）
 *   - J·dω/dt = T_em − T_load − b·ω（惯性/摩擦滑行沿用基类）
 * <p>
 * 电气：沿用基类 AcVoltageSource(EMF=0)+阻抗结构接入网络（纯负载）；
 * 电流/发热由【功率反推】：P_in = P_shaft/η；I = P_in/(Vab·cosφ)；
 * 发热 lossPower = (1−η)·|P_in|（效率 80% → 铜铁耗 20%）。
 */
public class InductionMotorModel extends ElectroMachineModel {

    // ===== 现实规格（单相电容运转感应电机，2极50Hz） =====
    /** 同步角速度（3000RPM） */
    private static final double SYNC_RAD_S = 3000.0 * 2.0 * Math.PI / 60.0; // 314.16
    /** 额定输出功率（W） */
    private static final double RATED_POWER_W = 1000.0;
    /** 效率 */
    private static final double EFFICIENCY = 0.8;
    /** 功率因数 */
    private static final double COS_PHI = 0.8;
    /** 额定转速（rad/s，~2900RPM，转差 3.3%） */
    private static final double RATED_RAD_S = 2900.0 * 2.0 * Math.PI / 60.0; // 303.69
    /** 额定转矩 T_rated = P/ω（N·m） */
    private static final double T_RATED = RATED_POWER_W / RATED_RAD_S; // 3.29
    /** 电压额定值（AC 230V） */
    private static final double VOLT_RATED = 230.0;

    // ===== 感应转矩-速度曲线：速度比 u=ω/ω_s，输出=×T_rated =====
    // 单相电容辅助起动感应特性（不对称）：启动 1.3、最大转矩在~65%转速(1.9)、
    // 额定点(96.7%)1.0、同步0、超同步负（发电制动）；|u|>1 才负 → 负转也获正转矩
    private static final double[] CURVE_U = {
            0.0, 0.25, 0.50, 0.65, 0.80, 0.90, 0.967, 0.99, 1.00, 1.03, 1.10, 1.50};
    private static final double[] CURVE_T = {
            1.30, 1.60, 1.78, 1.90, 1.70, 1.35, 1.00, 0.55, 0.00, -0.30, -0.60, -1.00};

    /**
     * 感应转矩-速度线性插值：u=ω/ω_s（正向超同步 u&gt;1 → 负=发电制动；
     * 负转速 u&lt;0 按 |u| 求曲线正值=不自反转，拖回正转——单相感应真实）。
     */
    private static double torqueFactor(double u) {
        double uu = Math.max(0.0, Math.min(Math.abs(u), CURVE_U[CURVE_U.length - 1]));
        int i = 1;
        while (i < CURVE_U.length - 1 && CURVE_U[i] < uu) i++;
        return (CURVE_U[i] - CURVE_U[i - 1]) <= 1e-12
                ? CURVE_T[i]
                : CURVE_T[i - 1] + (CURVE_T[i] - CURVE_T[i - 1])
                * (uu - CURVE_U[i - 1]) / (CURVE_U[i] - CURVE_U[i - 1]);
    }

    /** 应力基准换算：σ[SU] = T[N·m]×16（真实单位基准） */
    private static final double STRESS_PER_NM = 16.0;
    /** 应力输出缩放倍数（2026-08-28 用户：按 300 倍缩放——额定 σ=3.293×16×300 ≈
     *  15805SU，对齐 Create 16384 满刻度量级） */
    private static final double STRESS_SCALE = 300.0;
    /** 最终应力系数 = 16×300 = 4800 SU/(N·m)：输出 σ=T×4800，反馈 T=σ/4800（对称）。
     *  16384 之类输出上限由【具体电机的组装器】额外实现（模型只做纯计算，2026-08-28） */
    private static final double STRESS_SU_PER_NM = STRESS_PER_NM * STRESS_SCALE;
    /** 堵转判速（rad/s；≤此视为堵转/近零速）：堵转走电流法大热（I²R） */
    private static final double STALL_RAD = 0.05 * SYNC_RAD_S; // ≈15.7

    /** 最近电输入功率（W）：诊断/运行效率发热用（堵转发热走电流法） */
    private volatile double lastPInW;

    public InductionMotorModel(int a, int b, int x, double resistance, double inductance,
                               ThermalModel thermal, EnergyModel energy,
                               boolean generatorMode, double emfVoltage) {
        super(a, b, x, resistance, inductance, thermal, energy, generatorMode, emfVoltage);
        // 电压上限不变（230V 额定 / 258V 最大，>258V 过热爆炸沿用）
        maxVoltage = 258.0;
        // 最大应力 = 拉出(最大)转矩×4800 ≈ 30030 SU（物理上限；16384 饱和由组装器实现）
        maxStress = 1.90 * T_RATED * STRESS_SU_PER_NM;
        // 感应专属惯量/阻尼（基类 DC 的 J=1/b=0.05 对 3000RPM 尺度过大）：
        //   J=0.5 → 启动 ~2s（真实 1kW）；摩擦≈0 → 空载精确趋同步、满载额定点
        //   断电制动靠“残留负载阻力 tLoad”（真实轴承摩擦几乎拖不停空转）
        friction = 0.0;
        inertia = 0.5;
    }

    /**
     * 感应电机机械推进（覆写 DC 的 advanceShaft）：
     * 电压判据供电 → 感应转矩曲线 → J·dω/dt → ω → σ/功率/电流/发热。
     */
    @Override
    public void advanceShaft(Complex va, Complex vb, double freqHz, double dt) {
        if (dt < 0) return;
        // 端口电压（供电判据沿用：0.5V < Vab < 5×maxV = 有源；悬空漂移门控）
        double vabMag = (va != null && vb != null) ? va.sub(vb).abs() : 0;
        lastVabV = vabMag;
        boolean powered = vabMag > 0.5 && vabMag < 5.0 * Math.max(maxVoltage, 1e-9)
                && Double.isFinite(vabMag);
        double bF = Math.max(friction, 0.001);
        double jI = Math.max(inertia, 0.01);
        // 负载转矩 = 本机负载应力（组装器注入）÷4800 还原 N·m。现实：负载≤额定 →
        // 稳定额定工作点（~2900RPM 不降速，转差仅 3.3%）；>额定 → 转差加大转速下降；
        // 到 clamp 3×T_rated（9.88N·m>拉出6.26）→ 失速堵转。断电也保留：负载拖轴快停。
        // 16384/过载堵转策略全由组装器决定注入多少应力（模型仅执行计算）
        double tLoad = Math.min(3.0 * T_RATED,
                Math.max(0, motorLoadStressSU) / STRESS_SU_PER_NM);
        double tEm;
        double pIn;
        double iPhys;
        if (!powered) {
            tEm = 0; pIn = 0; iPhys = 0;
        } else {
            // 电压平方修正（感应转矩 ∝ V²；上限 1.6 防 5V 超压区失控）
            double vRat = Math.min(1.6, vabMag / VOLT_RATED);
            double u = rotorSpeedRadS / SYNC_RAD_S; // 带符号速度比
            // 感应转矩 = T_rated × 曲线(u) × (V/230)²
            tEm = T_RATED * torqueFactor(u) * vRat * vRat;
            // 物理输出功率 P = T·ω；效率 → 电输入 P_in = P/η（负=发电回馈）
            double pShaft = tEm * rotorSpeedRadS;
            pIn = pShaft / EFFICIENCY;
            // 电流诊断 I = |P_in|/(V·cosφ)
            iPhys = Math.abs(pIn) / Math.max(vabMag * COS_PHI, 1e-9);
        }
        // 惯性方程：启动转矩小(≈0.42) 且重载 → dω<0 → 起不来堵转（真实难启动）
        double dOmega = (tEm - tLoad - bF * rotorSpeedRadS) / jI;
        // 超速柔钳（防脏状态失控）
        double wLim = 1.8 * SYNC_RAD_S;
        if (Math.abs(rotorSpeedRadS) > 1.5 * SYNC_RAD_S) {
            dOmega -= 0.3 * (Math.abs(rotorSpeedRadS) - 1.5 * SYNC_RAD_S) / jI;
        }
        double wReal = rotorSpeedRadS + dOmega * Math.max(dt, 0.01);
        if (wReal < 0) wReal = 0; // 单相不自反转：负载反拖 → 堵转静止（重载难启动）
        if (Math.abs(wReal) > wLim) {
            wReal = Math.copySign(wLim, wReal); // 硬钳
        }
        rotorSpeedRadS = wReal;

        // ---- 输出（现实单位） ----
        lastPInW = powered ? Math.abs(pIn) : 0; // 诊断（发热走 lossPower 电流法/效率法）
        lastStressSU = Math.abs(tEm) * STRESS_SU_PER_NM; // 真实应力 = |T|×4800（16384 由组装器 cap）
        lastOutputPowerW = tEm * wReal;                 // 物理功率 P=T·ω
        lastCurrentA = Math.abs(iPhys);
        lastEmfV = 0; // 感应电机无显式 EMF 源（转子短路感应）

        // 过压热惩罚（电压上限不变 258V；仅 258~2×258 真过压区罚——更高=悬空
        // 漂移脏数据不罚，防单根线误判供电秒爆）
        if (thermal != null && powered && vabMag > maxVoltage
                && vabMag < 2.0 * Math.max(maxVoltage, 1e-9)) {
            double ov = (vabMag / Math.max(maxVoltage, 1e-9)) - 1.0;
            double penaltyW = overVoltPenaltyBaseW * Math.max(0, ov * ov * ov);
            thermal.addHeat(penaltyW * Math.max(dt, 0.01));
        }
        // 转速事件 → BE（沿用基类通知约定）
        if (shaftListener != null) {
            try {
                shaftListener.onRotorSpeed(rotorSpeedRadS,
                        rotorSpeedRadS * 60.0 / (2.0 * Math.PI));
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 感应电机发热（覆写 DC 铜耗；2026-08-28 防单根线爆炸重构）【电流法+双模】：
     *  - 堵转/近零速（|ω|&lt;STALL_RAD 且真实供电）：只算真实流过 EMF 源内阻 R 的
     *    支路电流 I=|V_a−V_x|/R 的铜耗 I²R/2——单根线/悬空恒等势无回路 → I≈0 →
     *    天然不热（此前功率反推+硬编码 4500W 不看电流 → 单根线误判供电秒爆）。
     *  - 运行（转速正常）：效率损耗 (1−η)·|P_in|（≈250W，不爆）。
     * 双线真实堵转（重载卡死）：电流法大热 → 持续过热爆炸（真实）。
     */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        double pm = (va != null && vb != null && Double.isFinite(va.sub(vb).abs()))
                ? va.sub(vb).abs() : 0;
        boolean powered = pm > 0.5 && pm < 5.0 * Math.max(maxVoltage, 1e-9);
        if (!powered || resistance <= 0) return 0;
        // 堵转态：电流法（真实支路电流穿过 R）
        if (Math.abs(rotorSpeedRadS) < STALL_RAD) {
            double iPeak;
            Complex vA = nodeVoltage(a);
            // EMF 源终点：L>0 → 内部节点 x（电感前）；L=0 → 端口 b
            Complex vE = nodeVoltage(inductance > 0 ? x : b);
            if (vA != null && vE != null) {
                iPeak = vA.sub(vE).abs() / resistance; // 悬空恒等势 → 0（防炸关键）
            } else {
                double z = impedanceAt(omega);
                iPeak = (z > 1e-12) ? pm / z : 0; // 兜底
            }
            return iPeak * iPeak * resistance / 2.0;
        }
        // 运行态：效率损耗
        if (!Double.isFinite(lastPInW) || lastPInW <= 0) return 0;
        return (1.0 - EFFICIENCY) * lastPInW;
    }

    // ===== 诊断常量（脚本/测试可核对现实参数） =====
    public static double tRated() { return T_RATED; }
    public static double syncRadS() { return SYNC_RAD_S; }
    public static double ratedRadSl() { return RATED_RAD_S; }
    public static double ratedPowerW() { return RATED_POWER_W; }
    public static double efficiency() { return EFFICIENCY; }
}
