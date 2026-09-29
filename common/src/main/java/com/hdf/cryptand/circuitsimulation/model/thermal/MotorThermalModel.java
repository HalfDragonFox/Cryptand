package com.hdf.cryptand.circuitsimulation.model.thermal;

/**
 * ===== 电机族温度模型（2026-09-13 用户："温度模型、机械模型等需要精确到具体功能，
 *       比如电机，这样的话可以对电机一类都进行统一的温度计算"）=====
 *
 * 设计要点：**族级子类 + 参数化**，不是"每个方块一个类"。
 * 普通/恒速/伺服/感应/自研电机全部共用本模型，族内差异（绕组电阻、铁损系数、
 * 摩擦系数）由构造参数表达 ⇒ 新增一个电机变体是【零核心改动】。
 *
 * 统一损耗公式（一条式子覆盖整族）：
 *
 *     P_loss [W] = I² · R                     铜损（绕组焦耳热）
 *                + k_fe · |ω|^1.5             铁损（磁滞 + 涡流，Steinmetz 近似）
 *                + k_fric · ω²                机械损耗（轴承摩擦 + 风阻）
 *
 * 温度推进沿用父类 {@link ThermalModel} 的一阶解析解：
 *     C·dT/dt = P_loss − G_eff·(T − T_amb),   G_eff = G·(风扇倍率 + 外附散热)
 *     T_ss = T_amb + P_loss / G_eff,          T(t+dt) = T_ss + (T−T_ss)·exp(−dt/τ)
 * 无条件稳定，任意 dt 不振荡；风扇冷却（含多台叠加）由父类的 coolingMultiplier /
 * extraCoolingFactor 直接生效，无需本类关心。
 *
 * ⚠ 全部为解析式：无查表、无分支表、无兜底算法（用户："要优雅和性能高效"）。
 */
public class MotorThermalModel extends ThermalModel {

    /** 绕组电阻（Ω）：铜损 I²R 用 */
    public final double windingResistance;
    /** 铁损系数（W/(rad/s)^1.5）：铁损 = k_fe·|ω|^1.5（ω=0 时无铁损，天然正确） */
    public final double ironLossK;
    /** 摩擦/风阻系数（W/(rad/s)²）：机械损耗 = k_fric·ω² */
    public final double frictionLossK;

    public MotorThermalModel(double conductance, double heatCapacity,
                             double windingResistance, double ironLossK,
                             double frictionLossK) {
        super(conductance, heatCapacity);
        this.windingResistance = Math.max(windingResistance, 0);
        this.ironLossK = Math.max(ironLossK, 0);
        this.frictionLossK = Math.max(frictionLossK, 0);
    }

    /** 电机族统一损耗（W）——"电机一类统一温度计算"的唯一入口 */
    public double motorLossW(double currentA, double omegaRadS) {
        double i = Math.abs(currentA);
        double w = Math.abs(omegaRadS);
        return i * i * windingResistance
                + ironLossK * Math.pow(w, 1.5)
                + frictionLossK * w * w;
    }

    /**
     * 按电机工况推进温度（每仿真步调用一次）：
     * 算统一损耗 → 交给父类解析解推进（含功率 EMA 平滑、风扇散热、节流）。
     *
     * @return 推进后的温度（K）
     */
    public double advanceMotor(double currentA, double omegaRadS, double dt) {
        return advanceStep(motorLossW(currentA, omegaRadS), dt);
    }

    @Override
    public String toString() {
        return "MotorThermalModel{T=" + String.format("%.1f", tempCelsius()) + "°C"
                + " R=" + windingResistance + " kfe=" + ironLossK
                + " kfric=" + frictionLossK + " G=" + conductance
                + " C=" + heatCapacity + "}";
    }
}
