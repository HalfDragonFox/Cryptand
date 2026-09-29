/**
 * ===== Sable 力系统反射访问器（框架内部，2026-09-14） =====
 *
 * <p>sable 在本项目是 runtimeOnly（无编译期依赖）→ 全部经反射访问，且【未安装时全部降级为 null】，
 * 不影响 Cryptand 自身启动。
 *
 * <p>用到的官方 API（证据行号见 ai_memory/repo/sable-force-visualization.md）：
 * <ul>
 *   <li>ServerSubLevel#getQueuedForceGroups() → Map&lt;ForceGroup, QueuedForceGroup&gt;</li>
 *   <li>ServerSubLevel#enableIndividualQueuedForcesTracking(boolean)</li>
 *   <li>QueuedForceGroup#getRecordedPointForces() → List&lt;PointForce&gt;</li>
 *   <li>PointForce#point() / #force()（record accessor，Vector3dc）</li>
 *   <li>ForceGroups.REGISTRY#getKey(ForceGroup) → ResourceLocation</li>
 *   <li>MassTracker#getCenterOfMass() / #getMass()</li>
 *   <li>SubLevel#logicalPose() → Pose3d（transformPosition / transformNormal）</li>
 * </ul>
 */
package com.hdf.cryptand.neoforge.sable.force.impl;

import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public final class ForceReflect {

    private ForceReflect() {
    }

    private static volatile boolean init = false;
    private static volatile boolean ok = false;

    private static Class<?> cSubLevel;
    private static Class<?> cMassTracker;
    private static Class<?> cPose;
    private static Class<?> cPointForce;

    private static Method mGetQueuedForceGroups;
    private static Method mGetRecordedPointForces;
    private static Method mPoint;
    private static Method mForce;
    private static Method mGetCenterOfMass;
    private static Method mGetMass;
    private static Method mLogicalPose;
    private static Method mTransformPosition;
    private static Method mTransformNormal;
    private static Method mEnableTrack;
    private static Method mRegistryGetKey;
    private static Method mGetUniqueId;
    private static Method mGetGravity;

    private static Object oRegistry;

    /** 反射初始化（幂等；sable 未安装 → ok=false，全部 API 返回 null/0）。 */
    public static synchronized void ensure() {
        if (init) return;
        init = true;
        try {
            cSubLevel = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
            cMassTracker = Class.forName("dev.ryanhcode.sable.api.physics.mass.MassTracker");
            cPointForce = Class.forName("dev.ryanhcode.sable.api.physics.force.QueuedForceGroup$PointForce");
            final Class<?> cQueued = Class.forName("dev.ryanhcode.sable.api.physics.force.QueuedForceGroup");
            final Class<?> cForceGroups = Class.forName("dev.ryanhcode.sable.api.physics.force.ForceGroups");

            mGetQueuedForceGroups = cSubLevel.getMethod("getQueuedForceGroups");
            mEnableTrack = cSubLevel.getMethod("enableIndividualQueuedForcesTracking", boolean.class);
            mLogicalPose = cSubLevel.getMethod("logicalPose");
            mGetUniqueId = cSubLevel.getMethod("getUniqueId");
            mGetRecordedPointForces = cQueued.getMethod("getRecordedPointForces");
            mPoint = cPointForce.getMethod("point");
            mForce = cPointForce.getMethod("force");
            mGetCenterOfMass = cMassTracker.getMethod("getCenterOfMass");
            mGetMass = cMassTracker.getMethod("getMass");

            final Field fRegistry = cForceGroups.getField("REGISTRY");
            oRegistry = fRegistry.get(null);
            mRegistryGetKey = oRegistry.getClass().getMethod("getKey", Object.class);

            final Class<?> cMassData = Class.forName("dev.ryanhcode.sable.api.physics.mass.MassData");
            final Method mGetTracker = cSubLevel.getMethod("getMassTracker");
            // 质心来自 MassTracker；pose 类型运行时解析
            cPose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3d");
            mTransformPosition = cPose.getMethod("transformPosition", Vector3dc.class);
            mTransformNormal = cPose.getMethod("transformNormal", Vector3dc.class);

            try {
                final Class<?> cDim = Class.forName("dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData");
                mGetGravity = cDim.getMethod("getGravity", net.minecraft.world.level.Level.class);
            } catch (final Throwable ignored) {
                mGetGravity = null;   // 可选：失败则用默认 (0,-9.81,0)
            }

            ok = true;
        } catch (final Throwable t) {
            ok = false;
        }
    }

    public static boolean available() {
        ensure();
        return ok;
    }

    // ===== 访问 =====

    /** ServerSubLevel（Object）。 */
    public static Object queuedForceGroups(final Object subLevel) {
        ensure();
        if (!ok || subLevel == null || !cSubLevel.isInstance(subLevel)) return null;
        try {
            return mGetQueuedForceGroups.invoke(subLevel);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 打开/关闭"逐点力记录"（官方开关；关闭 = 零记录零成本）。 */
    public static boolean setTrackIndividual(final Object subLevel, final boolean enable) {
        ensure();
        if (!ok || subLevel == null || !cSubLevel.isInstance(subLevel)) return false;
        try {
            mEnableTrack.invoke(subLevel, enable);
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }

    /** 力组 → ResourceLocation（如 sable:lift；失败 → null）。 */
    public static net.minecraft.resources.ResourceLocation forceGroupId(final Object forceGroup) {
        ensure();
        if (!ok || forceGroup == null || oRegistry == null) return null;
        try {
            final Object rl = mRegistryGetKey.invoke(oRegistry, forceGroup);
            return rl instanceof net.minecraft.resources.ResourceLocation r ? r : null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * 力组 → 稳定回退 id（官方注册名取不到时使用）。
     *
     * <p>⚠ 颜色一致性铁律：同一力组的所有力（合力/点力、任意多个力源）必须共用同一个
     * id，否则会渲染成不同颜色。因此回退 id 由【力组自身的可读名】派生（稳定），
     * 绝不能用 identityHashCode 之类每实例不同的值。
     */
    public static net.minecraft.resources.ResourceLocation forceGroupFallbackId(final Object forceGroup) {
        try {
            String name = null;
            final Object comp = forceGroup.getClass().getMethod("name").invoke(forceGroup);
            if (comp instanceof net.minecraft.network.chat.Component c) {
                name = c.getString();
            } else if (comp != null) {
                name = String.valueOf(comp);
            }
            if (name == null || name.isEmpty()) name = "unknown";

            final String slug = name.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_+|_+$", "");
            return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                    "cryptand", "group_" + (slug.isEmpty() ? "unknown" : slug));
        } catch (final Throwable t) {
            return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("cryptand", "group_unknown");
        }
    }

    /** 力组 → 该组记录的点力列表（Ljava/util/List; 可能为空）。 */
    @SuppressWarnings("unchecked")
    public static List<Object> recordedPointForces(final Object queuedGroup) {
        ensure();
        if (!ok || queuedGroup == null) return List.of();
        try {
            final Object list = mGetRecordedPointForces.invoke(queuedGroup);
            return list instanceof List<?> l ? (List<Object>) l : List.of();
        } catch (final Throwable t) {
            return List.of();
        }
    }

    /** PointForce → 作用点（结构局部坐标）。 */
    public static Vector3dc point(final Object pointForce) {
        ensure();
        if (!ok || pointForce == null || !cPointForce.isInstance(pointForce)) return null;
        try {
            final Object v = mPoint.invoke(pointForce);
            return v instanceof Vector3dc vc ? vc : null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /** PointForce → 力矢量（结构局部坐标）。 */
    public static Vector3dc force(final Object pointForce) {
        ensure();
        if (!ok || pointForce == null || !cPointForce.isInstance(pointForce)) return null;
        try {
            final Object v = mForce.invoke(pointForce);
            return v instanceof Vector3dc vc ? vc : null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /**
     * 质量追踪器：按【实际对象类】探测多候选 getter。
     *
     * <p>⚠ 2026-09-14 修复：原先把方法取自固定的 {@code MassTracker} 类，而
     * {@code getMassTracker()} 的实际返回类型可能是 {@code MassData}/{@code MergedMassTracker}
     * → {@code Method.invoke} 抛 "object is not an instance of declaring class" 被吞掉
     * → 质量恒 0 → 重力样本不产出（用户实测："力中心只渲染了一个、且没有箭头"）。
     */
    private static Object massTracker(final Object subLevel) {
        if (subLevel == null) return null;
        for (final String name : new String[]{"getMassTracker", "getMassData", "massTracker"}) {
            try {
                final Object t = subLevel.getClass().getMethod(name).invoke(subLevel);
                if (t != null) return t;
            } catch (final Throwable ignored) {
                // 试下一个候选名
            }
        }
        return null;
    }

    /** 在目标对象【实际类】上按候选名调用无参 getter（首个非 null 结果胜出）。 */
    private static Object invokeAny(final Object target, final String... names) {
        if (target == null) return null;
        for (final String name : names) {
            try {
                final Object v = target.getClass().getMethod(name).invoke(target);
                if (v != null) return v;
            } catch (final Throwable ignored) {
                // 试下一个候选名
            }
        }
        return null;
    }

    /** 结构质心（结构局部坐标；未知 → null）。 */
    public static Vector3dc centerOfMass(final Object subLevel) {
        ensure();
        if (!ok || subLevel == null || !cSubLevel.isInstance(subLevel)) return null;
        final Object tracker = massTracker(subLevel);
        if (tracker == null) return null;
        final Object com = invokeAny(tracker, "getCenterOfMass", "centerOfMass", "getCenterOfMassLocal");
        return com instanceof Vector3dc vc ? vc : null;
    }

    /** 结构质量 kg（未知 → 0）。 */
    public static double mass(final Object subLevel) {
        ensure();
        if (!ok || subLevel == null || !cSubLevel.isInstance(subLevel)) return 0.0;
        final Object tracker = massTracker(subLevel);
        if (tracker == null) return 0.0;
        final Object m = invokeAny(tracker, "getMass", "getTotalMass", "mass", "getMassKg");
        return m instanceof Number n ? n.doubleValue() : 0.0;
    }

    /** 结构逻辑位姿（Pose3d；失败 → null）。 */
    public static Object logicalPose(final Object subLevel) {
        ensure();
        if (!ok || subLevel == null || !cSubLevel.isInstance(subLevel)) return null;
        try {
            return mLogicalPose.invoke(subLevel);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 结构位姿点（世界坐标；Pose3d.position()；失败 → null）。 */
    public static double[] posePosition(final Object pose) {
        if (pose == null) return null;
        try {
            final Object p = pose.getClass().getMethod("position").invoke(pose);
            if (p instanceof Vector3dc vc) return new double[]{vc.x(), vc.y(), vc.z()};
            return null;
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 局部点 → 世界点（pose.asVector3d 变换）。 */
    public static double[] toWorld(final Object pose, final double x, final double y, final double z) {
        ensure();
        if (!ok || pose == null) return new double[]{x, y, z};
        try {
            final Object out = mTransformPosition.invoke(pose, new org.joml.Vector3d(x, y, z));
            if (out instanceof Vector3dc vc) return new double[]{vc.x(), vc.y(), vc.z()};
            return new double[]{x, y, z};
        } catch (final Throwable t) {
            return new double[]{x, y, z};
        }
    }

    /** 局方向 → 世界方向（仅旋转）。 */
    public static double[] toWorldDir(final Object pose, final double x, final double y, final double z) {
        ensure();
        if (!ok || pose == null) return new double[]{x, y, z};
        try {
            final Object out = mTransformNormal.invoke(pose, new org.joml.Vector3d(x, y, z));
            if (out instanceof Vector3dc vc) return new double[]{vc.x(), vc.y(), vc.z()};
            return new double[]{x, y, z};
        } catch (final Throwable t) {
            return new double[]{x, y, z};
        }
    }

    /** 结构唯一标识的稳定哈希（仅作客户端分组 key）。 */
    public static int structureKey(final Object subLevel) {
        ensure();
        if (!ok || subLevel == null || !cSubLevel.isInstance(subLevel)) return 0;
        try {
            final Object uuid = mGetUniqueId.invoke(subLevel);
            return uuid == null ? 0 : uuid.hashCode();
        } catch (final Throwable t) {
            return 0;
        }
    }

    /** 维度重力矢量（世界坐标 m/s²；sable 未装或失败 → 默认 (0,-9.81,0)）。 */
    public static double[] gravity(final net.minecraft.world.level.Level level) {
        ensure();
        if (!ok || mGetGravity == null || level == null) return new double[]{0.0, -9.81, 0.0};
        try {
            final Object g = mGetGravity.invoke(null, level);
            if (g instanceof Vector3dc vc) return new double[]{vc.x(), vc.y(), vc.z()};
            return new double[]{0.0, -9.81, 0.0};
        } catch (final Throwable t) {
            return new double[]{0.0, -9.81, 0.0};
        }
    }

    /** 力组 Map 的 entry 遍历（返回 Map&lt;Object,Object&gt; 视图；失败 → 空 Map）。 */
    @SuppressWarnings("unchecked")
    public static Map<Object, Object> asMap(final Object mapLike) {
        return mapLike instanceof Map<?, ?> m ? (Map<Object, Object>) m : Map.of();
    }
}
