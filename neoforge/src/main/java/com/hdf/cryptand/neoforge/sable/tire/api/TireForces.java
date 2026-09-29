/**
 * ===== 轮胎模型输出（轮胎拟真框架 API，2026-09-14） =====
 *
 * <p>轮坐标系下的力（N）与回正力矩（Nm·占位）。方向约定：
 * <ul>
 *   <li>{@code fx} 纵向：正 = 前进方向（驱动力为正，制动力为负）</li>
 *   <li>{@code fy} 侧向：正 = 车体右侧</li>
 *   <li>{@code mz} 回正力矩（当前模型恒 0，接口预留）</li>
 * </ul>
 */
package com.hdf.cryptand.neoforge.sable.tire.api;

public record TireForces(double fx, double fy, double mz) {

    public static final TireForces ZERO = new TireForces(0.0, 0.0, 0.0);
}
