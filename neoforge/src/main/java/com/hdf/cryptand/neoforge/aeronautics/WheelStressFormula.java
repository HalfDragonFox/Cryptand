/**
 * ===== 轮子摩擦力→应力换算公式（纯公式链，零 MC 依赖，可单元测试） =====
 *
 * 2026-08-28 用户："轮子能否进行改造，改成和现实类似，根据实际摩擦力消耗
 * 来计算应力消耗"。Create 网络应力账单（SU）原为静态 stressImpact（与重量/
 * 摩擦无关）；本类把它变成【现实摩擦物理】：
 *
 *   滚动力矩 τ_roll = μ · N · r            （滚动阻力矩：摩擦系数×法向力×轮半径）
 *   功率 P = τ_roll · ω = μ · N · v        （ω=滚速/r → 功率=摩擦×法向×线速度）
 *   应力 SU = P / P_ref · SU_ref           （按物理功率比例映射回 Create SU 账单）
 *
 * 参数（全部来自 Sable/Offroad 真实现实物理，非魔法数字）：
 *   - μ   接触面摩擦系数（BE touchingFriction，PhysicsBlockPropertyHelper 实测）
 *   - N   单轮法向承载（MassData.getInverseNormalMass，Sable 质量追踪器真实数据）
 *   - v   地速（= ω_网络 × r / 传动比；无移动时即轮面线速度）
 *   - r   轮半径（TireLike.radius，小/大/巨型轮胎各不相同）
 *
 * 规律（涌现，非手动分支）：
 *   - 固定项 baseSU·min(1,ω/ω_ref)：轮子【转动即消耗】（轴承/传动/风阻基础
 *     损耗），即使悬空也有——2026-08-28 用户"空转也消耗一定"；低速按比例
 *     平滑（ω→0 → 固定项→0，不转不耗）
 *   - 摩擦项 μ·N·g·v/400：接触+承载+移动才消耗
 *   - 悬空（N→0）→ 只剩固定项（空转基础损耗，不再白嫖）
 *   - 光滑面（μ→0）→ 只剩固定项（冰面打滑驱动不输出，但轴承照转照耗）
 *   - 重载（N↑）→ 摩擦项↑；快滚（v↑ 或 r 大）→ 摩擦项↑
 *   - 车辆整体质量 ∝ Σ N → 整车越重总应力越高（用户期望的"和重量有关"）
 *
 * 设计约束：
 *   - 纯公式（铁律 5）：无 if/else 阈值分支；clamp 仅为数值稳定，语义仍是公式
 *   - 输出与 Create SU 同量纲（应力计可读），基准换算 SU_ref/P_ref 可配置微调
 */
package com.hdf.cryptand.neoforge.aeronautics;

public final class WheelStressFormula {

    /** 基准换算：1 SU 对应多少 W（模拟可读性：满载荷满速度≈原版静态 impact 量级）。 */
    private static final double DEFAULT_WATTS_PER_SU = 400.0;

    private WheelStressFormula() {
    }

    /**
     * 轮子摩擦应力（SU）——重载，固定项默认 0（空转不耗）。
     * 保留兼容旧调用（无固定项）。
     */
    public static double stressOf(double friction, double normalN, double linearV,
                                  double radius, double wattsPerSU) {
        return stressOf(friction, normalN, linearV, radius, wattsPerSU, 0.0, 1.0);
    }

    /**
     * 轮子应力（SU）= 固定项 + 摩擦项（2026-08-28 用户"空转也消耗一定"）。
     * <p>
     *   SU = baseSU·min(1, |ω|/ω_ref)  +  μ·N·g·v / wattsPerSU
     *       └── 固定项（转动即耗）      └── 摩擦项（接触承载移动才耗）
     *
     * @param friction     接触面摩擦系数 μ（0..1+；BE touchingFriction，fudge 后）
     * @param normalN      单轮法向承载质量 N（kg；Sable MassData 真实权重；0=悬空）
     * @param linearV      轮面线速度 v（m/s = ω_网络×r；≤0 = 静止）
     * @param radius       轮半径 r（m，TireLike.radius；经 v=ω·r 间接参与）
     * @param wattsPerSU   W/SU 基准（≤0 → 默认 400）
     * @param baseSU       空转固定应力（≥0；0 = 不启用固定项，仅摩擦项）
     * @param omegaBaseRef 固定项平滑参考角速度（rad/s；>0；ω=0 → 固定项 0）
     * @return 应力 SU（≥0；转速 0 → 0，不转不耗）
     */
    public static double stressOf(double friction, double normalN, double linearV,
                                  double radius, double wattsPerSU, double baseSU,
                                  double omegaBaseRef) {
        // 数值稳定（公式语义不变：转速 0 / 悬空 N=0 → 固定项/摩擦项自然归 0）
        double mu = (Double.isFinite(friction) && friction >= 0) ? friction : 0;
        double n = (Double.isFinite(normalN) && normalN >= 0) ? normalN : 0;
        double v = (Double.isFinite(linearV) && linearV >= 0) ? linearV : 0;
        double r = (Double.isFinite(radius) && radius > 0) ? radius : 1e-6;
        double wps = (wattsPerSU > 1e-6 && Double.isFinite(wattsPerSU))
                ? wattsPerSU : DEFAULT_WATTS_PER_SU;
        double base = (Double.isFinite(baseSU) && baseSU > 0) ? baseSU : 0;
        double wRef = (Double.isFinite(omegaBaseRef) && omegaBaseRef > 1e-6)
                ? omegaBaseRef : 1.0;
        // 固定项：轮子转动即消耗（轴承/传动/风阻基础损耗）；ω=v/r，低速平滑
        double omega = v / r;
        double fixedSU = base * Math.min(1.0, omega / wRef);
        // 摩擦项：滚动阻力 F_roll = μ·N·g（比例于法向力与摩擦系数；g=9.81）
        // 滚动功率 P = F_roll · v（= τ·ω，τ=μ·N·g·r，ω=v/r → 化简为 F·v）
        double rollForceN = mu * n * 9.81;
        double frictionSU = rollForceN * v / wps;
        return Math.max(0, fixedSU + frictionSU);
    }

    /**
     * 便捷重载：由轮子转速推算线速度。
     *
     * @param omegaRadS 网络角速度（rad/s；WheelMount 网络 getSpeed RAD/s 语义）
     * @param radius    轮半径（m）
     */
    public static double linearVelocityOf(double omegaRadS, double radius) {
        if (!Double.isFinite(omegaRadS) || !Double.isFinite(radius)
                || radius <= 1e-6) return 0;
        return Math.abs(omegaRadS) * radius;
    }
}
