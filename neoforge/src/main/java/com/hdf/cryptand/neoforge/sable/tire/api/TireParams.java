/**
 * ===== 轮胎参数（轮胎拟真框架 API，2026-09-14） =====
 *
 * <p>⚠ 背景：官方 `TireLike` 只有 radius/rotation/offset/model/minimumFriction —— **没有**胎宽、
 * 胎压、刚度、滚阻等真实参数（见 ai_memory/repo/sable-tire-simulation-constraints.md C2）。
 * 因此参数采用【归一化】形式：力 = 系数 × 法向载荷 N × 曲线(x)，不需要绝对刚度数值。
 *
 * @param lateralStiffness      侧向刚度系数（1/rad）：Fy = −Cy · α · N（峰值前）
 * @param longitudinalStiffness 纵向刚度系数（1/滑移率）：Fx = Cx · κ · N（峰值前）
 * @param peakSlipAngleRad      侧偏角峰值（rad；超过后按 peak/α 衰减 → 模拟抓地饱和）
 * @param peakSlipRatio         滑移率峰值（超过后按 peak/κ 衰减 → 模拟空转/抱死饱和）
 * @param rollingResistance     滚阻系数（F = Crr · N，方向与纵向速度相反）
 * @param muScale               本轮胎对地面 μ 的修正（≥0；1.0 = 直接使用地面 μ）
 */
package com.hdf.cryptand.neoforge.sable.tire.api;

public record TireParams(double lateralStiffness, double longitudinalStiffness,
                         double peakSlipAngleRad, double peakSlipRatio,
                         double rollingResistance, double muScale) {

    /** 默认参数（街胎手感；配置可覆盖）。 */
    public static final TireParams DEFAULT = new TireParams(
            6.0,        // Cy：侧偏刚度（1/rad）
            4.0,        // Cx：纵滑刚度
            Math.toRadians(8.0),   // 峰值侧偏角
            0.15,       // 峰值滑移率
            0.015,      // 滚阻
            1.0);       // μ 修正

    public TireParams withStiffness(final double cy, final double cx) {
        return new TireParams(cy, cx, this.peakSlipAngleRad, this.peakSlipRatio,
                this.rollingResistance, this.muScale);
    }
}
