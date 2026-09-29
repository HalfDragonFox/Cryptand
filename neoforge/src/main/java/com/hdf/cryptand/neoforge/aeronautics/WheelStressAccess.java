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

import com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero;

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

    /** [WheelStress] log4j 诊断（进入 latest.log，2026-08-29 调整为 log4j） */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Cryptand-AeroWheel");

    /** [WheelStress] 诊断节流（5s；确认公式真实计算） */
    private static volatile long WS_DBG_LAST;

    /**
     * 结构质量同步缓存：pos → kg。服务端算好 → KineticBlockEntity.write 写入 NBT
     * → 客户端 read 缓存 → frictionStressOf 客户端用它（2026-08-29 实锤：
     * ClientSubLevel 无质量 API，goggle 在客户端跑 N=0 → 恒 4 根因）。
     */
    private static final java.util.concurrent.ConcurrentHashMap<
            net.minecraft.core.BlockPos, Double> STRUCT_MASS_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 客户端缓存结构质量（KineticStressHookMixin.read 注入调用）。 */
    public static void cacheStructureMass(net.minecraft.core.BlockPos pos, double mass) {
        if (pos == null || !(mass > 0) || !Double.isFinite(mass)) return;
        STRUCT_MASS_CACHE.put(pos.immutable(), mass);
    }

    /** 读缓存结构质量（客户端 frictionStressOf 用；miss → 0）。 */
    private static double structureMassAt(net.minecraft.core.BlockPos pos) {
        if (pos == null) return 0;
        Double m = STRUCT_MASS_CACHE.get(pos.immutable());
        return (m != null && Double.isFinite(m) && m > 0) ? m : 0;
    }

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

    /** 读取轮子应力（SU）——按【实际转速】(getSpeed()) 计算，供 goggle 显示当前消耗。
     *  2026-08-30 用户简化：SU = (16基础 + 重量×系数)×|rpm|，无摩擦力/衰减。 */
    public static double frictionStressOf(Object be) {
        try {
            ensureInit();
            if (!ready() || be == null) return 0;
            double radius = tireRadiusOf(be);
            if (radius <= 1e-6) return 0; // 空轮架（无轮胎）不计轮子应力
            // Create getSpeed() 是【RPM】语义
            float speedRpm = ((Number) M_GET_SPEED.invoke(be)).floatValue();
            return computeStress(be, Math.abs((double) speedRpm));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 读取轮子应力（SU）——按【指定转速 RPM】计算。
     * ⚠ 网络账本（超载判定）用【理论转速】——否则超载→降速→SU(实际)变小→误判
     *   "应力足够"但转速不恢复。用于 KineticNetworkStressMixin.getActualStressOf。
     */
    public static double frictionStressOfAt(Object be, double rpm) {
        try {
            ensureInit();
            if (!ready() || be == null) return 0;
            double radius = tireRadiusOf(be);
            if (radius <= 1e-6) return 0;
            return computeStress(be, Math.abs(rpm));
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 核心：SU = (baseImpact + N×weightPerKg) × |rpm|。
     * baseImpact 默认 16（1 转速=16 应力，原版基线）；N 为轮胎所受重量(kg)；
     * weightPerKg 每 kg 额外系数。静止 rpm=0 → 0（不转不耗）。
     */
    private static double computeStress(Object be, double rpm) {
        try {
            boolean clientSide = be instanceof net.minecraft.world.level.block.entity.BlockEntity b
                    && b.getLevel() != null && b.getLevel().isClientSide();
            double normalN = normalLoadOf(be, clientSide);
        // ★ 2026-09-14 轮胎拟真：SU 切【真实摩擦功率】（用户拍板）
        //   命中 TirePowerCache（轮胎模型算出的 P = |Fx·vx| + |Fy·vy|）→ SU = P / wattPerSU；
        //   未命中/过期 → 自动回退下面的 v9 公式。
        if (com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_TIRE_SU_FROM_FRICTION.get()) {
            try {
                final Object bp = com.hdf.cryptand.neoforge.sable.tire.impl.TireReflect.blockPos(be);
                if (bp != null) {
                    final long key = com.hdf.cryptand.neoforge.sable.tire.impl.TireReflect.posKey(bp);
                    final com.hdf.cryptand.neoforge.sable.tire.impl.TirePowerCache.Sample sample =
                            com.hdf.cryptand.neoforge.sable.tire.impl.TirePowerCache.get(key);
                    if (sample != null && key != 0L) {
                        final double wattPerSu = com.hdf.cryptand.neoforge.sable.config.ConfigSable
                                .SABLE_TIRE_WATT_PER_SU.get();
                        return wattPerSu <= 1.0e-9 ? 0.0 : sample.watts() / wattPerSu;
                    }
                }
            } catch (final Throwable ignored) {
                // 反射/配置异常 → 回退 v9 公式
            }
        }

            double base = readBaseImpactPerRpm();
            double wkg = readWeightPerKg();
            double su = WheelStressFormula.stressOf(base, normalN, wkg, rpm);
            // 诊断节流 5s 确认公式真实计算生效（N/rpm/base/w/SU）
            long now = System.currentTimeMillis();
            if (now - WS_DBG_LAST >= 5000) {
                WS_DBG_LAST = now;
                LOGGER.info("[WheelStress] " + be.getClass().getSimpleName()
                        + " N=" + fmt(normalN) + " rpm=" + fmt(rpm)
                        + " base=" + fmt(base) + " w/kg=" + fmt(wkg)
                        + " -> SU=" + fmt(su));
            }
            return su;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 格式化双精度（%.2f） */
    private static String fmt(double d) {
        return String.format("%.2f", d);
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
     * 法向承载 N（kg）：
     *  - 服务端：Sable 结构总质量（MassData.getMass 稳定；不再用波动的 getInverseNormalMass），
     *    算到后顺手写入同步缓存（供 write 带出）
     *  - 客户端：NBT 同步缓存（客户端 ClientSubLevel 无质量 API，2026-08-29 实锤）
     * 2026-08-29 实测日志：服务端 N=6.65~7.69（结构小），客户端 N=0 → 恒 4 根因。
     */
    private static double normalLoadOf(Object be, boolean clientSide) {
        try {
            if (!clientSide) {
                double m = structureMassOf(be);
                if (m > 0) {
                    cacheStructureMass(
                            ((net.minecraft.world.level.block.entity.BlockEntity) be)
                                    .getBlockPos(), m);
                    return m;
                }
            }
            // 客户端 / 服务端反射失败 → 读 NBT 同步缓存
            double cached = structureMassAt(
                    ((net.minecraft.world.level.block.entity.BlockEntity) be).getBlockPos());
            if (cached > 0) return cached;
            LOGGER.warn("[WheelStress] normalN=0 (clientSide=" + clientSide
                    + ", no mass source)");
            return 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 服务端：Sable 结构总质量（kg）。MassData.getMass() 稳定总质量；
     * 不在亚层 / 反射失败 → 0。客户端调用（M_GET_MASS_TRACKER 取自 ServerSubLevel，
     * ClientSubLevel 无此方法 → invoke 抛异常被吞 → 0）→ 走缓存。
     * public：供 KineticStressHookMixin.write 注入（服务端算好写 NBT 同步客户端）。
     */
    public static double structureMassOf(Object be) {
        try {
            if (mSableHelper == null || M_GET_CONTAINING == null) return 0;
            Object sub = M_GET_CONTAINING.invoke(mSableHelper, be);
            if (sub == null) return 0;
            Object mass = M_GET_MASS_TRACKER.invoke(sub);
            if (mass == null) return 0;
            Method getMass = mass.getClass().getMethod("getMass");
            Object m = getMass.invoke(mass);
            return (m instanceof Number n && Double.isFinite(n.doubleValue())
                    && n.doubleValue() > 0) ? n.doubleValue() : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 基础系数（每转速 SU；配置 aeroWheelBaseImpact；默认 16=原版 1转速16应力）。 */
    private static double readBaseImpactPerRpm() {
        try {
            return ConfigAero.AERO_WHEEL_BASE_IMPACT.get();
        } catch (Throwable t) {
            return 16.0;
        }
    }

    /** 每 kg 载荷额外系数（配置 aeroWheelWeightPerKg；默认 0.01=100kg→系数+1）。 */
    private static double readWeightPerKg() {
        try {
            return ConfigAero.AERO_WHEEL_WEIGHT_PER_KG.get();
        } catch (Throwable t) {
            return 0.01;
        }
    }
}
