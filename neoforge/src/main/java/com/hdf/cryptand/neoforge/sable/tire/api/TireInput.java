/**
 * ===== 轮胎模型输入（轮胎拟真框架 API，2026-09-14） =====
 *
 * <p>一次求解的全部输入。单位统一 SI（N / m / s / rad），归一化项在 {@link TireParams} 里说明。
 *
 * @param slipRatio      纵向滑移率 κ = (ω·r − v_x)/max(|v_x|, ε)
 * @param slipAngleRad   侧偏角 α = atan2(v_y, |v_x|)（右转为正）
 * @param normalLoad     接地点法向载荷 N（N；本框架用【自算悬挂弹簧力】，非官方单点近似）
 * @param groundMu       地面摩擦系数 μ（官方方块物理属性；未取到 → 默认值）
 * @param wheelRadius    轮半径（m）
 * @param forwardSpeed   轮坐标系纵向速度（m/s）
 * @param lateralSpeed   轮坐标系侧向速度（m/s）
 * @param dt             本 substep 时长（s）
 */
package com.hdf.cryptand.neoforge.sable.tire.api;

public record TireInput(double slipRatio, double slipAngleRad, double normalLoad, double groundMu,
                        double wheelRadius, double forwardSpeed, double lateralSpeed, double dt) {
}
