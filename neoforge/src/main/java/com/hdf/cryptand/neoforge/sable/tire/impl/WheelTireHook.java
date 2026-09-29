/**
 * ===== 轮胎拟真力修正核心（2026-09-14） =====
 *
 * <p>注入点：offroad {@code WheelMountBlockEntity.sable$physicsTick} 的 RETURN ——
 * 官方已把"悬挂力 + 简化摩擦/驱动"提交进该结构的 {@code ForceTotal}；本类随后
 * **追加一条修正冲量**，把官方摩擦分量替换为【轮胎模型】的结果。悬挂力完全不改动。
 *
 * <h3>⚠ 量纲策略（关键设计决定）</h3>
 * 官方的"摩擦/驱动力"是**伪力**（strength × 速度 的混合量纲，不是牛顿），
 * 直接用牛顿量级的轮胎力替换会让车辆行为剧变（飞车/不动）。因此第一版采用
 * <b>无量纲滑移调制</b>：
 * <pre>
 *   κ = (ω·r − v_纵向) / max(|v_纵向|, 1.0)     侧偏角 α = atan2(v_侧向, max(|v_纵向|, 0.5))
 *   s_纵 = 曲线(κ, peakκ) / peakκ                s_侧 = 曲线(α, peakα) / peakα      // 峰值 = 1
 *   附着椭圆：usage = √(s_纵² + s_侧²) > 1 → 两者等比缩放
 *   F_纵 = F_官方纵 × s_纵 ,  F_侧 = F_官方侧 × s_侧
 * </pre>
 * 效果 = "打滑即牵引力饱和/侧向抓不住"（用户拍板的折中），且**零量纲风险**。
 * 真实牛顿量级的轮胎模型（{@link com.hdf.cryptand.neoforge.sable.tire.api.TireModel}）
 * 仍被调用并缓存，供力显示与后续标定使用。
 *
 * <h3>参数与安全</h3>
 * - 参数来自配置（{@link com.hdf.cryptand.neoforge.sable.config.ConfigSable}）
 * - 全程 try/catch：任何异常都不影响官方物理（退化 = 官方行为）
 * - 开关关闭 = 本类零行为
 */
package com.hdf.cryptand.neoforge.sable.tire.impl;

import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import com.hdf.cryptand.neoforge.sable.force.impl.ForceReflect;
import org.joml.Vector3d;
import org.joml.Vector3dc;

public final class WheelTireHook {

    private WheelTireHook() {
    }

    /** 每次 substep 的修正次数（诊断）。 */
    private static volatile long corrections = 0L;

    public static void onPhysicsTickReturn(final Object wheel, final Object subLevel,
                                           final Object handle, final double timeStep) {
        if (!ConfigSable.ENABLE_SABLE_TIRE_REALISM.get()) return;
        if (!TireReflect.available() || wheel == null || subLevel == null) return;

        try {
            applyCorrection(wheel, subLevel, timeStep);
        } catch (final Throwable ignored) {
            // 任何异常 → 不做修正（保持官方物理）
        }
    }

    private static void applyCorrection(final Object wheel, final Object subLevel, final double dt) {
        if (dt <= 0.0) return;

        // ① 前置：必须有轮胎、必须已接地（extension 有效）
        final float radius = TireReflect.tireRadius(wheel);
        if (radius <= 1.0e-6f) return;
        final double extension = TireReflect.extension(wheel);
        if (Double.isNaN(extension)) return;

        // ② 位姿 + 速度（sable 速度单位 = m/tick）
        final Object pose = ForceReflect.logicalPose(subLevel);
        if (pose == null) return;

        final Object blockPos = TireReflect.blockPos(wheel);
        final Object state = TireReflect.blockState(wheel);
        if (blockPos == null || state == null) return;

        final Object level = TireReflect.level(wheel);
        if (level == null) return;

        final double[] wheelCenter = TireReflect.wheelCenterWorld(state, blockPos);
        if (wheelCenter == null) return;

        final Vector3d vWorld = TireReflect.velocity(level, wheelCenter[0], wheelCenter[1], wheelCenter[2]);
        if (vWorld == null) return;

        // 世界 → 结构局部（速度是方向量：只做旋转，不含平移）
        final Vector3d vLocal = TireReflect.transformNormalInverse(pose, new Vector3d(vWorld));

        // ③ 轮轴基（复刻官方 getRotatedWheelAxis：normal → rotateY(chasingYaw)）
        final double yaw = TireReflect.chasingYaw(wheel);
        final boolean xAxis = TireReflect.isXFacing(state);
        // sideD：facing 轴向的正方向，绕 Y 旋转 yaw
        final Vector3d sideD = new Vector3d(xAxis ? 1.0 : 0.0, 0.0, xAxis ? 0.0 : 1.0).rotateY(yaw);
        // normalD：把轴向旋转 90°（x↔z）再旋转 yaw
        final Vector3d normalD = new Vector3d(xAxis ? 0.0 : 1.0, 0.0, xAxis ? 1.0 : 0.0).rotateY(yaw);

        // ④ 速度分量
        final double vLong = vLocal.dot(normalD);
        final double vLat = vLocal.dot(sideD);

        // ⑤ 滑移率与侧偏角（等效：无独立轮速自由度 → 用 Create 网络转速推算轮缘速度）
        final double wheelOmega = Math.abs(TireReflect.speedRpm(wheel)) * (Math.PI * 2.0 / 60.0); // rad/s
        final double vWheelSurface = wheelOmega * radius;   // m/tick 与 RPM 混合 → 仅用于相对比较
        final double kappa = (vWheelSurface - vLong) / Math.max(Math.abs(vLong), 1.0);
        final double alpha = Math.atan2(vLat, Math.max(Math.abs(vLong), 0.5));

        // ⑥ 饱和因子（峰值 = 1；过峰值衰减 → 打滑）
        final double peakKappa = Math.max(1.0e-3, ConfigSable.SABLE_TIRE_PEAK_SLIP_RATIO.get());
        final double peakAlpha = Math.max(1.0e-3,
                Math.toRadians(ConfigSable.SABLE_TIRE_PEAK_SLIP_ANGLE_DEG.get()));
        final double sLong0 = satFactor(kappa, peakKappa);
        final double sLat0 = satFactor(alpha, peakAlpha);

        // 附着椭圆：√(s纵² + s侧²) > 1 → 等比缩放（纵向用掉的抓地挤占侧向）
        double sLong = sLong0;
        double sLat = sLat0;
        final double usage = Math.sqrt(sLong * sLong + sLat * sLat);
        if (usage > 1.0) {
            sLong /= usage;
            sLat /= usage;
        }

        // ⑦ 官方摩擦分量（冲量；复刻官方公式，见 sable$physicsTick L210-219）
        final double mu = Math.max(0.05, TireReflect.touchingFriction(wheel));
        final double normalMass = Math.max(1.0e-6, ForceReflect.mass(subLevel));
        final double strength = TireReflect.suspensionStrength(wheel);
        if (strength <= 1.0e-6) return;
        final double normalMassScaling = Math.min(normalMass / strength, 1.0) * 10.0;
        final double strengthMul = strength * normalMassScaling * 2.0;

        final double surfaceBraking = Math.min(mu, 1.0);
        final double brake = TireReflect.redstoneBrake(level, blockPos);
        final double brakingFrictionStrength = (0.075 + brake * 0.3) * surfaceBraking;
        final double kineticSpeed = xAxis ? TireReflect.speedRpm(wheel) : -TireReflect.speedRpm(wheel);

        final double officialLong = vLong * -brakingFrictionStrength * strengthMul
                + kineticSpeed * (1.0 - brake) * surfaceBraking * 1.75;
        final double officialLat = vLat * -0.6 * mu * strengthMul;

        // ⑦.5 真实轮胎模型（牛顿量级）→ 摩擦功率缓存（供 SU 应力与力显示使用）
        recordTirePower(blockPos, subLevel, radius, kappa, alpha, vLong, vLat, mu);

        // ⑧ 修正量 = 官方 ×(饱和因子 − 1)（即"把超出附着极限/过峰值的那部分削掉"）
        final double dLong = officialLong * (sLong - 1.0);
        final double dLat = officialLat * (sLat - 1.0);
        if (Math.abs(dLong) < 1.0e-9 && Math.abs(dLat) < 1.0e-9) return;

        final Vector3dc forcePos = TireReflect.queuedForcePos(wheel);
        final Object forceTotal = TireReflect.forceTotal(wheel);
        if (forcePos == null || forceTotal == null) return;

        // 局部冲量 = (Δ纵 · normalD + Δ侧 · sideD) × dt
        final Vector3d impulse = new Vector3d(normalD).mul(dLong * dt).fma(dLat * dt, sideD);
        TireReflect.applyImpulseAtPoint(forceTotal, subLevel, forcePos, impulse);

        corrections++;

        if (ConfigSable.SABLE_TIRE_DEBUG.get() && (corrections % 40L == 0L)) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[Tire] κ={} α={}° s纵={} s侧={} μ={} ΔF纵={} ΔF侧={} (corrections={})",
                    String.format("%.3f", kappa), String.format("%.2f", Math.toDegrees(alpha)),
                    String.format("%.2f", sLong), String.format("%.2f", sLat),
                    String.format("%.2f", mu),
                    String.format("%.1f", dLong), String.format("%.1f", dLat), corrections);
        }
    }

    /**
     * 调真实轮胎模型（牛顿量级）算力，并把【摩擦功率 P = |Fx·vx| + |Fy·vy|】写进
     * {@link TirePowerCache} —— 应力侧据此算 SU（用户拍板："SU 切真实摩擦功率"）。
     *
     * <p>单轮载荷用 结构质量×g×0.25 近似（无逐轮法向质量数据源）。
     * 速度单位：sable = m/tick → ×20 转 m/s。
     */
    private static void recordTirePower(final Object blockPos, final Object subLevel, final float radius,
                                        final double kappa, final double alpha,
                                        final double vLong, final double vLat, final double mu) {
        try {
            final var entry = com.hdf.cryptand.neoforge.sable.tire.api.TireRegistry.defaultEntry();
            final var params = com.hdf.cryptand.neoforge.sable.tire.api.TireRegistry.paramsFor(entry.params());
            final double normalLoad = Math.max(1.0, ForceReflect.mass(subLevel) * 9.81 * 0.25);

            final var input = new com.hdf.cryptand.neoforge.sable.tire.api.TireInput(
                    kappa, alpha, normalLoad, mu, radius,
                    vLong * 20.0, vLat * 20.0, 1.0 / 80.0);
            final var forces = entry.model().solve(input, params);

            final double watts = Math.abs(forces.fx() * vLong * 20.0)
                    + Math.abs(forces.fy() * vLat * 20.0);
            TirePowerCache.put(TireReflect.posKey(blockPos), watts, normalLoad, mu);
        } catch (final Throwable ignored) {
            // 模型失败 → 不写缓存（应力侧自动回退旧公式）
        }
    }

    /** 峰值前线性上升、峰值 = 1、过峰值按 peak/|x| 衰减（打滑饱和）。 */
    private static double satFactor(final double x, final double peak) {
        final double ax = Math.abs(x);
        if (ax < 1.0e-6) return 0.0;
        if (ax <= peak) return ax / peak;
        return peak / ax;
    }
}
