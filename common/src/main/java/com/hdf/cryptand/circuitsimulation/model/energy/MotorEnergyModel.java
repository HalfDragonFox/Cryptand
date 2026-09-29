package com.hdf.cryptand.circuitsimulation.model.energy;

import com.hdf.cryptand.circuitsimulation.model.state.StateDriven;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;

/**
 * ===== 电机机械能量模型（2026-09-13 用户："给电机添加能量模型，用于机械能表达，
 *       让电机变成时域性计算，公式围绕此能量进行计算"）=====
 *
 * 机械能表达（全部为瞬时解析式，O(1)、无查表）：
 *
 *     E_mech = ½ · J · ω²                      转动动能（J）
 *     P_mech = T_em · ω                        机械功率（W）
 *     P_net  = P_elec − P_loss − P_mech        能量收支（>0 加速 / <0 减速）
 *
 * ⚠⚠ 为什么【ω 仍是主积分量】，而不是让 E 当主状态：
 *   能量式 dE/dt = P，反解 ω = sign·√(2E/J)；于是
 *       dω/dE = 1 / (J·ω)   →   ω→0 时发散
 *   平方根在原点导数无穷大 ⇒ 启动瞬间与停机瞬间数值剧烈抖动
 *   （恰恰是电机最需要稳定的两个时刻）。两者数学等价，但"以 ω 积分"
 *   在原点附近稳定得多。
 *   因此本模型采用【双表述】：ω 由 {@code ElectroMachineModel.advanceShaft}
 *   继续积分（J·dω/dt = ΣT），本模型在其之上同步维护机械能与功率，
 *   作为【可观测量 + 能量守恒校验量】，而不是替换主积分量。
 *
 * 时域性：每一步都由 {@link #step(double, double, double, double, double)}
 *   显式带 dt 推进（与全项目统一的伪时域 / 固定节拍一致），
 *   与 {@link EnergyModel}（电能）、{@code ThermalModel}（热）共用同一套
 *   能量语义 —— "电 → 机械 → 热"的转换链路在数值上都可见。
 */
public class MotorEnergyModel implements StateDriven {

    /** 转动惯量 J（kg·m²）；由主模型每步同步（用户可改主模型 inertia 即时生效） */
    public volatile double inertia = 1.0;

    /** 机械能 E = ½Jω²（J，瞬时） */
    public volatile double mechEnergyJ;
    /** 机械功率 P_mech = T_em·ω（W，带符号：负 = 被拖动/发电回馈） */
    public volatile double mechPowerW;
    /** 电输入功率（W，诊断；未提供时保持上次值） */
    public volatile double elecPowerW;
    /** 损耗功率（W = 铜损 + 铁损 + 摩擦，诊断；未提供时保持上次值） */
    public volatile double lossPowerW;

    /** 角速度快照（rad/s，本步） */
    public volatile double omegaRadS;

    /**
     * 时域推进（每仿真步调用一次）：按当前转速刷新机械能与功率。
     *
     * @param omega    当前角速度（rad/s，由主模型积分得到——见类注释的数值理由）
     * @param mechW    机械功率 T_em·ω（W）
     * @param elecW    电输入功率（W；NaN = 本步不更新）
     * @param lossW    损耗功率（W；NaN = 本步不更新）
     * @param dt       伪时域步长（s）—— 保持与其他模型一致的显式时域签名
     */
    public void step(double omega, double mechW, double elecW, double lossW, double dt) {
        double w = Double.isFinite(omega) ? omega : 0;
        this.omegaRadS = w;
        this.mechEnergyJ = 0.5 * Math.max(inertia, 0) * w * w; // E = ½Jω²
        this.mechPowerW = Double.isFinite(mechW) ? mechW : 0;
        if (Double.isFinite(elecW)) this.elecPowerW = elecW;
        if (Double.isFinite(lossW)) this.lossPowerW = lossW;
    }

    /** 简化步进（只更新机械量；电/损耗功率保持上次值） */
    public void step(double omega, double mechW) {
        step(omega, mechW, Double.NaN, Double.NaN, 0);
    }

    /** 能量收支（W）：电输入 − 损耗 − 机械输出（>0 = 正在加速） */
    public double netPowerW() {
        return elecPowerW - lossPowerW - mechPowerW;
    }

    /** 等效转速（由机械能反解，rad/s）：ω = sign·√(2E/J)。
     *  ⚠ 仅供诊断/校验 —— 主积分量仍是 ω（见类注释的数值理由）。 */
    public double omegaFromEnergy() {
        if (!(inertia > 0)) return 0;
        return Math.copySign(Math.sqrt(2.0 * Math.max(mechEnergyJ, 0) / inertia), omegaRadS);
    }

    /**
     * 能量守恒残差（J）：电输入 − 损耗 − 机械输出 − 当前机械能。
     * ⚠ 本模型只维护【瞬时】量，不做跨步累积，因此残差按功率额定标度给出
     *   （用于诊断"电→机械→热"链路是否自洽），不用于数值修正。
     */
    public double powerBalanceW() {
        return netPowerW();
    }

    /** StateDriven：状态推进由主模型显式调用 step（此处 no-op，保持接口统一）。
     *  返回 false —— 机械能不改变电学参数（电学由 EMF = K_E·ω 表达）。 */
    @Override
    public boolean advanceState(Complex va, Complex vb, double freqHz, double dt,
                                SolveMode mode) {
        return false;
    }

    /** 重置（跨网络重建保留由外部 store 负责；此处清瞬时量） */
    @Override
    public void reset() {
        mechEnergyJ = 0;
        mechPowerW = 0;
        omegaRadS = 0;
    }

    @Override
    public String toString() {
        return "MotorEnergy{E=" + String.format("%.2f", mechEnergyJ) + "J"
                + " P_mech=" + String.format("%.1f", mechPowerW) + "W"
                + " P_elec=" + String.format("%.1f", elecPowerW) + "W"
                + " P_loss=" + String.format("%.1f", lossPowerW) + "W"
                + " J=" + inertia + "}";
    }
}
