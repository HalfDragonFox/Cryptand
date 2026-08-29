/**
 * ===== 航空学（Aeronautics/Sable）物理化坐标系工具（2026-08-23 用户） =====
 *
 * 参考 CEE（SableCompanion / SubLevelAccess / logicalPose）：
 *   - getContaining(level, pos)：pos 是否在【物理化亚层】内（装置/飞艇），返回
 *     SubLevelAccess（null = 非物理化）——反射，零 Sable 编译期依赖
 *   - toWorld：装置局部世界坐标 → 真实世界坐标（logicalPose().transformPosition）
 *   - distanceWithSubLevels：玩家与装置内目标的真实距离（交互范围判断）
 * <p>统一接线层（WireTerminals.terminalPosWorld 等）对所有端子局部位置做
 * toWorld 变换 → 装置移动/物理化后：渲染、构建、放置、预览全部用真实世界坐标
 * → 网络不“消失”、端子可正常连接。
 */
package com.hdf.cryptand.neoforge.cee;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class CeePoseUtil {

    private static final String SABLE_COMPANION = "dev.ryanhcode.sable.companion.SableCompanion";

    /** 是否物理化亚层内（反射重试缓存） */
    private static volatile Object companionInstance;
    private static volatile boolean tried;

    private CeePoseUtil() {
    }

    private static Object companion() {
        try {
            if (companionInstance != null) return companionInstance;
            if (tried) return null;
            tried = true;
            Class<?> c = Class.forName(SABLE_COMPANION);
            Field f = c.getField("INSTANCE");
            Object inst = f.get(null);
            companionInstance = inst;
            return inst;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** pos 是否在物理化亚层内（装置/飞艇） */
    public static boolean contained(Level level, BlockPos pos) {
        return getContaining(level, pos) != null;
    }

    /** 物理化亚层（SubLevelAccess）；非物理化/异常 → null。
     *  ⚠ 反射签名必须用 Vec3i/Position（ActiveSableCompanion 声明的是
     *  getContaining(Level, Vec3i)/getContaining(Level, Position)——BlockPos
     *  是其子类，但 getMethod 要求【精确】声明类型，传 BlockPos.class 找不到）。 */
    public static Object getContaining(Level level, BlockPos pos) {
        try {
            Object inst = companion();
            if (inst == null || level == null || pos == null) return null;
            Method m = findMethod(inst.getClass(), "getContaining",
                    new Class<?>[]{Level.class, net.minecraft.core.Position.class},
                    new Class<?>[]{Level.class, net.minecraft.core.Vec3i.class},
                    new Class<?>[]{Level.class, org.joml.Vector3dc.class});
            if (m == null) return null;
            return m.invoke(inst, level, pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** pos（Position/Vec3 浮点）是否在物理化亚层内（渲染投影用） */
    public static Object getContainingPos(Level level, net.minecraft.core.Position pos) {
        try {
            Object inst = companion();
            if (inst == null || level == null || pos == null) return null;
            Method m = findMethod(inst.getClass(), "getContaining",
                    new Class<?>[]{Level.class, net.minecraft.core.Position.class},
                    new Class<?>[]{Level.class, net.minecraft.core.Vec3i.class},
                    new Class<?>[]{Level.class, org.joml.Vector3dc.class});
            if (m == null) return null;
            return m.invoke(inst, level, pos);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 按候选签名顺序查找方法（接口 default 方法也会被 getMethod 返回） */
    private static Method findMethod(Class<?> c, String name, Class<?>[]... signatures) {
        for (Class<?>[] sig : signatures) {
            try {
                return c.getMethod(name, sig);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 装置局部世界坐标 → 真实世界坐标（非物理化原样返回）。
     *  用 SableCompanion.projectOutOfSubLevel(Level, Vec3)（ActiveSableCompanion/
     *  DefaultSableCompanion 均有 (Level, Vec3) 覆写），内部自动：亚层内 →
     *  logicalPose().transformPosition；非亚层 → 原样。 */
    public static Vec3 toWorld(Level level, BlockPos pos, Vec3 localWorld) {
        if (localWorld == null) return null;
        return projectOut(level, localWorld);
    }

    /** 亚层内坐标 → 世界坐标（SableCompanion.projectOutOfSubLevel 反射；
     *  非物理化/未装 sable → 原样返回） */
    public static Vec3 projectOut(Level level, Vec3 localWorld) {
        if (localWorld == null) return null;
        try {
            Object inst = companion();
            if (inst == null) return localWorld;
            Method m = findMethod(inst.getClass(), "projectOutOfSubLevel",
                    new Class<?>[]{Level.class, net.minecraft.world.phys.Vec3.class},
                    new Class<?>[]{Level.class, net.minecraft.core.Position.class});
            if (m == null) return localWorld;
            Object r = m.invoke(inst, level, localWorld);
            return r instanceof Vec3 v ? v : localWorld;
        } catch (Throwable ignored) {
            return localWorld;
        }
    }

    /** 亚层内坐标 → 世界坐标【partialTicks 插值】（2026-08-23 渲染层面跟随）：
     *  客户端每帧调用——用 SubLevelAccess.lastPose()（上一 tick 姿态）与
     *  logicalPose()（本 tick 姿态）按 partialTicks 线性插值（CEE
     *  InWorldNode.toGlobalPos(level, partialTicks) 同语义）→ 物理化结构移动时
     *  导线端点平滑跟随（不再"跳"/滞后 0.5 tick）。非亚层原样返回。
     *  反射 lastPose/logicalPose + Pose3dc.transformPosition(Vec3)（CEE 同款
     *  MC 重载）；任何失败回退 projectOut。 */
    public static Vec3 toWorldInterp(Level level, BlockPos pos, Vec3 localWorld,
                                     float partialTicks) {
        if (localWorld == null) return null;
        try {
            Object sub = getContainingPos(level, localWorld);
            if (sub != null) {
                Object lastPose = sub.getClass().getMethod("lastPose").invoke(sub);
                Object logicalPose = sub.getClass().getMethod("logicalPose").invoke(sub);
                if (lastPose != null && logicalPose != null) {
                    Method tf = findMethod(logicalPose.getClass(), "transformPosition",
                            new Class<?>[]{Vec3.class});
                    if (tf != null) {
                        Object ra = tf.invoke(lastPose, localWorld);
                        Object rb = tf.invoke(logicalPose, localWorld);
                        if (ra instanceof Vec3 va && rb instanceof Vec3 vb) {
                            return va.lerp(vb, Mth.clamp(partialTicks, 0f, 1f));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return projectOut(level, localWorld);
    }

    /** 玩家与目标（可能装置内）的真实距离平方（交互范围判断） */
    public static double distanceSquaredWithSubLevels(Level level, Vec3 targetWorld, Vec3 eyePos) {
        try {
            Object inst = companion();
            if (inst == null) return targetWorld == null ? Double.MAX_VALUE
                    : targetWorld.distanceToSqr(eyePos);
            Method m = inst.getClass().getMethod("distanceSquaredWithSubLevels",
                    Level.class, Vec3.class, Vec3.class);
            Object r = m.invoke(inst, level, targetWorld, eyePos);
            return r instanceof Number n ? n.doubleValue() : Double.MAX_VALUE;
        } catch (Throwable ignored) {
            return targetWorld == null ? Double.MAX_VALUE : targetWorld.distanceToSqr(eyePos);
        }
    }
}
