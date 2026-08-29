/**
 * ===== 轮子摩擦物理反射访问器（2026-08-28） =====
 *
 * 背景：offroad/sable 均为 runtimeOnly（无编译期 API），而 WheelMountBlockEntity
 * 无 public 的"摩擦应力"访问器。本类用【反射】从 BE 与 Sable 物理体读取真实
 * 数据（touchingFriction / MassData 法向质量 / 速度 / TireLike radius），交给
 * {@link WheelStressFormula} 纯公式换算 SU——零编译期依赖，未装自动回退。
 *
 * 反射面（全部经字节码/API 确认）：
 *   - BE.getSpeed()            ：KineticBlockEntity，网络角速度（rad/s 语义）
 *   - BE.getHeldItem()          ：当前轮胎 ItemStack（→ TireLike data component）
 *   - BE.touchingFriction       ：接触面摩擦（physicsTick 实测 + fudge）
 *   - TireLike.radius()          ：轮半径（record 方法）
 *   - Sable.HELPER.getContaining(BE) → ServerSubLevel
 *   - ServerSubLevel.getMassTracker() → MassData
 *   - MassData.getInverseNormalMass(pos, normal) → 单点法向质量
 *
 * 缓存：Method/Field 反射一次缓存（类加载后不变）；数据每 tick 现取（物理实时）。
 */
package com.hdf.cryptand.neoforge.aeronautics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class WheelStressAccess {

    /** W/SU 基准（与公式类默认一致：400W = 1 SU） */
    private static final double WATTS_PER_SU = 400.0;

    // ===== 反射句柄（懒初始化 + 缓存；失败 = 未装/API 变动 → 回退原版） =====
    private static volatile Method M_GET_SPEED;
    private static volatile Method M_GET_HELD_ITEM;
    private static volatile Method M_GET_ITEM_STACK_COMPONENT;
    private static volatile Method M_GET_CONTAINING;
    private static volatile Method M_GET_MASS_TRACKER;
    private static volatile Method M_GET_INVERSE_NORMAL_MASS;
    private static volatile Field F_TOUCHING_FRICTION;
    private static volatile Field F_TIRE_COMPONENT;
    private static volatile Object mSableHelper;

    private static volatile boolean inited;

    private WheelStressAccess() {
    }

    /** 初始化全部反射句柄（一次；失败不阻塞，后续重试）。 */
    private static synchronized void ensureInit() {
        if (inited) return;
        inited = true;
        try {
            Class<?> kbe = Class.forName(
                    "com.simibubi.create.content.kinetics.base.KineticBlockEntity");
            M_GET_SPEED = kbe.getMethod("getSpeed");
        } catch (Throwable ignored) {
        }
        try {
            Class<?> wm = Class.forName(
                    "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity");
            M_GET_HELD_ITEM = wm.getMethod("getHeldItem");
            F_TOUCHING_FRICTION = wm.getDeclaredField("touchingFriction");
            F_TOUCHING_FRICTION.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> items = Class.forName("net.minecraft.world.item.ItemStack");
            M_GET_ITEM_STACK_COMPONENT = items.getMethod("get",
                    net.minecraft.core.component.DataComponentType.class);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> odm = Class.forName(
                    "dev.ryanhcode.offroad.index.OffroadDataComponents");
            F_TIRE_COMPONENT = odm.getDeclaredField("TIRE");
            F_TIRE_COMPONENT.setAccessible(true);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
            Field helper = sable.getDeclaredField("HELPER");
            helper.setAccessible(true);
            mSableHelper = helper.get(null);
            M_GET_CONTAINING = mSableHelper.getClass().getMethod(
                    "getContaining", net.minecraft.world.level.block.entity.BlockEntity.class);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> subLevel = Class.forName(
                    "dev.ryanhcode.sable.sublevel.ServerSubLevel");
            M_GET_MASS_TRACKER = subLevel.getMethod("getMassTracker");
            Class<?> massData = Class.forName(
                    "dev.ryanhcode.sable.api.physics.mass.MassData");
            M_GET_INVERSE_NORMAL_MASS = massData.getMethod("getInverseNormalMass",
                    org.joml.Vector3dc.class, org.joml.Vector3dc.class);
        } catch (Throwable ignored) {
        }
    }

    private static boolean ready() {
        return M_GET_SPEED != null && M_GET_HELD_ITEM != null;
    }

    /**
     * 读取轮子摩擦应力（SU）。全部数据实时取值，物理准确。
     * 反射失败/非轮子/无接触 → 安全回退 0（无摩擦负荷不消耗应力）。
     *
     * @param be WheelMountBlockEntity 实例（Object 传入，零编译期依赖）
     * @return 摩擦应力 SU（≥0）
     */
    public static double frictionStressOf(Object be) {
        try {
            ensureInit();
            if (!ready() || be == null) return 0;
            // 1) 轮胎半径 r（TireLike.radius；无轮胎 → 0）
            double radius = tireRadiusOf(be);
            if (radius <= 1e-6) return 0; // 空轮架：无摩擦接触面
            // 2) 接触摩擦 μ
            double mu = touchingFrictionOf(be);
            // 3) 线速度 v = |ω|·r
            float speed = ((Number) M_GET_SPEED.invoke(be)).floatValue();
            double omega = Math.abs((double) speed);
            double v = WheelStressFormula.linearVelocityOf(omega, radius);
            // 4) 法向承载 N
            double normalN = normalLoadOf(be);
            // 5) 公式换算 SU（含空转固定消耗；运行时读配置——此刻 ModConfigSpec 已 build）
            double baseSU = readIdleBaseSU();
            double omegaRef = readIdleOmegaRef();
            return WheelStressFormula.stressOf(mu, normalN, v, radius, WATTS_PER_SU,
                    baseSU, omegaRef);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 轮胎半径（m）。无轮胎/读取失败 → 0（空轮架无摩擦面，SU 0 正确）。 */
    private static double tireRadiusOf(Object be) {
        try {
            Object held = M_GET_HELD_ITEM.invoke(be);
            if (held == null) return 0;
            Object comp = F_TIRE_COMPONENT.get(null);
            if (comp == null) return 0;
            Object tireLike = M_GET_ITEM_STACK_COMPONENT.invoke(held, comp);
            if (tireLike == null) return 0;
            Method radius = tireLike.getClass().getMethod("radius");
            Object r = radius.invoke(tireLike);
            return r instanceof Number n ? n.doubleValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 接触摩擦 μ（BE.touchingFriction；读取失败 → 默认 1.0 保守）。 */
    private static double touchingFrictionOf(Object be) {
        try {
            if (F_TOUCHING_FRICTION == null) return 1.0;
            double mu = F_TOUCHING_FRICTION.getDouble(be);
            return Double.isFinite(mu) && mu > 0 ? mu : 1.0;
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /**
     * 法向承载 N（kg）：Sable 装置实时质量。优先用 MassData.getInverseNormalMass
     * （法向质量；悬空 → 0 精确），回退 getMass()（整车质量）。
     *
     * ⚠ 单轮分摊：N_轮 ≈ M_总 / 轮数（均分重心，简化；重型航空器足够——
     * 后续可经 WheelMount 数量精确化）。
     */
    private static double normalLoadOf(Object be) {
        try {
            if (mSableHelper == null || M_GET_CONTAINING == null) return 0;
            Object sub = M_GET_CONTAINING.invoke(mSableHelper, be);
            if (sub == null) return 0; // 不在亚层（普通世界）→ 0
            Object mass = M_GET_MASS_TRACKER.invoke(sub);
            if (mass == null) return 0;
            return massValue(mass);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 空转固定应力（SU，配置 aeroWheelIdleStressSU；0=不启用）。运行时安全读（spec 已 build）。 */
    private static double readIdleBaseSU() {
        try {
            return com.hdf.cryptand.neoforge.core.config.ConfigLoad
                    .AERO_WHEEL_IDLE_STRESS_SU.get();
        } catch (Throwable t) {
            return 0.0;
        }
    }

    /** 固定项平滑参考角速度（rad/s，配置 aeroWheelIdleOmegaRef）。 */
    private static double readIdleOmegaRef() {
        try {
            return com.hdf.cryptand.neoforge.core.config.ConfigLoad
                    .AERO_WHEEL_IDLE_OMEGA_REF.get();
        } catch (Throwable t) {
            return 1.0;
        }
    }

    /** MassData 真实质量：法向质量（含悬空判 0）或整车质量。 */
    private static double massValue(Object mass) throws Exception {
        if (M_GET_INVERSE_NORMAL_MASS != null) {
            try {
                Object invN = M_GET_INVERSE_NORMAL_MASS.invoke(mass,
                        new org.joml.Vector3d(0, 0, 0), new org.joml.Vector3d(0, 0, 0));
                if (invN instanceof Number num && num.doubleValue() > 1e-9) {
                    return 1.0 / num.doubleValue();
                }
                // 悬空/失效 → 回退整车质量（仍有承载）
            } catch (Throwable ignored) {
            }
        }
        Method getMass = mass.getClass().getMethod("getMass");
        Object m = getMass.invoke(mass);
        return m instanceof Number n && n.doubleValue() > 0 ? n.doubleValue() : 0;
    }
}
