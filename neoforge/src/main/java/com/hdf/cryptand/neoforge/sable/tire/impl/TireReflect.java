/**
 * ===== 轮胎拟真反射访问器（框架内部，2026-09-14） =====
 *
 * <p>offroad / sable 均为 runtimeOnly（无编译期依赖）→ 全部反射；未装时全部降级
 * （返回 NaN / null / false），不影响 Cryptand 启动。
 *
 * <p>用到的官方成员（证据：WheelMountBlockEntity.sable$physicsTick 源码）：
 * <pre>
 *   字段：extension(double) / touchingFriction(double) / queuedForce(Vector3d)
 *         / queuedForcePos(Vector3d) / forceTotal(ForceTotal)
 *   方法：getSpeed()(Create RPM) / getBlockState() / getHeldItem() / getBlockPos()
 *   offroad：OffroadDataComponents.TIRE（静态字段）→ ItemStack.get(component) → TireLike.radius()
 *   sable：Sable.HELPER（静态字段）→ getVelocity(Level, Vector3dc, Vector3d)
 *         ForceTotal.applyImpulseAtPoint(ServerSubLevel, Vector3dc, Vector3dc)
 *         Pose3d.transformNormalInverse(Vector3dc)
 * </pre>
 */
package com.hdf.cryptand.neoforge.sable.tire.impl;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class TireReflect {

    private TireReflect() {
    }

    private static volatile boolean init = false;
    private static volatile boolean ok = false;

    private static Class<?> cWheel;
    private static Field fExtension;
    private static Field fTouchingFriction;
    private static Field fQueuedForce;
    private static Field fQueuedForcePos;
    private static Field fForceTotal;
    private static Method mGetSpeed;
    private static Method mGetBlockState;
    private static Method mGetHeldItem;
    private static Method mGetBlockPos;

    private static Method mStackGetComponent;
    private static Method mTireRadius;
    private static Object oTireComponent;

    private static Method mGetVelocity;
    private static Method mApplyImpulseAtPoint;
    private static Method mTransformNormalInverse;
    private static Object oSableHelper;

    public static synchronized void ensure() {
        if (init) return;
        init = true;
        try {
            cWheel = Class.forName("dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity");
            fExtension = findField(cWheel, "extension");
            fTouchingFriction = findField(cWheel, "touchingFriction");
            fQueuedForce = findField(cWheel, "queuedForce");
            fQueuedForcePos = findField(cWheel, "queuedForcePos");
            fForceTotal = findField(cWheel, "forceTotal");
            mGetSpeed = cWheel.getMethod("getSpeed");
            mGetBlockState = cWheel.getMethod("getBlockState");
            mGetHeldItem = cWheel.getMethod("getHeldItem");
            mGetBlockPos = cWheel.getMethod("getBlockPos");
        } catch (final Throwable t) {
            ok = false;
            return;
        }

        try {
            final Class<?> cComponents = Class.forName("dev.ryanhcode.offroad.index.OffroadDataComponents");
            oTireComponent = cComponents.getField("TIRE").get(null);
            mStackGetComponent = Class.forName("net.minecraft.world.item.ItemStack")
                    .getMethod("get", Class.forName("net.minecraft.core.component.DataComponentType"));
        } catch (final Throwable ignored) {
            oTireComponent = null;
        }

        try {
            final Class<?> cSable = Class.forName("dev.ryanhcode.sable.Sable");
            oSableHelper = cSable.getField("HELPER").get(null);
            // companion 接口：getVelocity(Level, Vector3dc, Vector3d)
            mGetVelocity = oSableHelper.getClass().getMethod("getVelocity",
                    Class.forName("net.minecraft.world.level.Level"), Vector3dc.class, Vector3d.class);

            final Class<?> cForceTotal = Class.forName("dev.ryanhcode.sable.api.physics.force.ForceTotal");
            mApplyImpulseAtPoint = cForceTotal.getMethod("applyImpulseAtPoint",
                    Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel"),
                    Vector3dc.class, Vector3dc.class);

            final Class<?> cPose = Class.forName("dev.ryanhcode.sable.companion.math.Pose3d");
            mTransformNormalInverse = cPose.getMethod("transformNormalInverse", Vector3dc.class);
        } catch (final Throwable ignored) {
            // 单项失败 → 对应功能降级
        }

        ok = true;
    }

    public static boolean available() {
        ensure();
        return ok;
    }

    private static Field findField(final Class<?> c, final String name) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null) {
            try {
                final Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (final NoSuchFieldException ignored) {
                cur = cur.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    // ===== 读取 =====

    public static double extension(final Object wheel) {
        ensure();
        try {
            return fExtension.getDouble(wheel);
        } catch (final Throwable t) {
            return Double.NaN;
        }
    }

    public static double touchingFriction(final Object wheel) {
        ensure();
        try {
            return fTouchingFriction.getDouble(wheel);
        } catch (final Throwable t) {
            return 1.0;
        }
    }

    public static Vector3dc queuedForce(final Object wheel) {
        ensure();
        try {
            final Object v = fQueuedForce.get(wheel);
            return v instanceof Vector3dc d ? d : null;
        } catch (final Throwable t) {
            return null;
        }
    }

    public static Vector3dc queuedForcePos(final Object wheel) {
        ensure();
        try {
            final Object v = fQueuedForcePos.get(wheel);
            return v instanceof Vector3dc d ? d : null;
        } catch (final Throwable t) {
            return null;
        }
    }

    public static Object forceTotal(final Object wheel) {
        ensure();
        try {
            return fForceTotal.get(wheel);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** Create 动能网络转速（RPM；Create 语义）。 */
    public static double speedRpm(final Object wheel) {
        ensure();
        try {
            final Object s = mGetSpeed.invoke(wheel);
            return s instanceof Number n ? n.doubleValue() : 0.0;
        } catch (final Throwable t) {
            return 0.0;
        }
    }

    public static Object blockState(final Object wheel) {
        ensure();
        try {
            return mGetBlockState.invoke(wheel);
        } catch (final Throwable t) {
            return null;
        }
    }

    public static Object blockPos(final Object wheel) {
        ensure();
        try {
            return mGetBlockPos.invoke(wheel);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 轮胎半径（m；无轮胎/读取失败 → 0）。 */
    public static float tireRadius(final Object wheel) {
        ensure();
        if (oTireComponent == null || mStackGetComponent == null) return 0.0f;
        try {
            final Object stack = mGetHeldItem.invoke(wheel);
            if (stack == null) return 0.0f;
            final Object tire = mStackGetComponent.invoke(stack, oTireComponent);
            if (tire == null) return 0.0f;
            if (mTireRadius == null || mTireRadius.getDeclaringClass() != tire.getClass()) {
                mTireRadius = tire.getClass().getMethod("radius");
            }
            final Object r = mTireRadius.invoke(tire);
            return r instanceof Number n ? n.floatValue() : 0.0f;
        } catch (final Throwable t) {
            return 0.0f;
        }
    }

    /** 结构速度（世界坐标，sable 内部单位 = m/tick）。 */
    public static Vector3d velocity(final Object level, final double x, final double y, final double z) {
        ensure();
        if (mGetVelocity == null || oSableHelper == null) return null;
        try {
            final Vector3d dest = new Vector3d();
            final Object out = mGetVelocity.invoke(oSableHelper, level, new Vector3d(x, y, z), dest);
            return out instanceof Vector3d d ? d : dest;
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 局方向 → 局方向（pose.transformNormalInverse，原地）。 */
    public static Vector3d transformNormalInverse(final Object pose, final Vector3d v) {
        ensure();
        if (mTransformNormalInverse == null || pose == null) return v;
        try {
            final Object out = mTransformNormalInverse.invoke(pose, v);
            return out instanceof Vector3d d ? d : v;
        } catch (final Throwable t) {
            return v;
        }
    }

    // ===== 辅助：BE 周边信息（全部反射，缺项 → 降级） =====

    /** BE 所在 Level（BlockEntity#getLevel）。 */
    public static Object level(final Object wheel) {
        try {
            return wheel.getClass().getMethod("getLevel").invoke(wheel);
        } catch (final Throwable t) {
            return null;
        }
    }

    private static Object oFacingProperty;

    /** 方块朝向（WheelMountBlock.HORIZONTAL_FACING 属性值）。 */
    public static Object facing(final Object state) {
        if (state == null) return null;
        try {
            if (oFacingProperty == null) {
                final Class<?> cBlock = Class.forName(
                        "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlock");
                oFacingProperty = cBlock.getField("HORIZONTAL_FACING").get(null);
            }
            return state.getClass()
                    .getMethod("getValue", Class.forName("net.minecraft.world.level.block.state.properties.Property"))
                    .invoke(state, oFacingProperty);
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 朝向是否沿 X 轴（官方 kineticSpeed 符号依赖它）。 */
    public static boolean isXFacing(final Object state) {
        try {
            final Object f = facing(state);
            if (f == null) return false;
            final Object axis = f.getClass().getMethod("getAxis").invoke(f);
            return axis != null && "X".equals(String.valueOf(axis));
        } catch (final Throwable t) {
            return false;
        }
    }

    /** 轮心（世界坐标）：blockPos.relative(facing).getCenter()（复刻官方）。 */
    public static double[] wheelCenterWorld(final Object state, final Object blockPos) {
        try {
            final Object f = facing(state);
            if (f == null || blockPos == null) return null;
            final Object rel = blockPos.getClass()
                    .getMethod("relative", Class.forName("net.minecraft.core.Direction"))
                    .invoke(blockPos, f);
            final Object center = rel.getClass().getMethod("getCenter").invoke(rel);
            return new double[]{
                    ((Number) center.getClass().getMethod("x").invoke(center)).doubleValue(),
                    ((Number) center.getClass().getMethod("y").invoke(center)).doubleValue(),
                    ((Number) center.getClass().getMethod("z").invoke(center)).doubleValue()};
        } catch (final Throwable t) {
            return null;
        }
    }

    /** 轮子追击偏航角（chasingYaw 字段，官方 getRotatedWheelAxis 用它）。 */
    public static double chasingYaw(final Object wheel) {
        ensure();
        try {
            return findField(cWheel, "chasingYaw").getDouble(wheel);
        } catch (final Throwable t) {
            return 0.0;
        }
    }

    /** 悬挂强度滑块值（SuspensionStrengthValueBehaviour#getValue）。 */
    public static double suspensionStrength(final Object wheel) {
        ensure();
        try {
            final Object behaviour = findField(cWheel, "strength").get(wheel);
            if (behaviour == null) return 0.0;
            final Object v = behaviour.getClass().getMethod("getValue").invoke(behaviour);
            return v instanceof Number n ? n.doubleValue() : 0.0;
        } catch (final Throwable t) {
            return 0.0;
        }
    }

    /** 红石刹车强度 0..1（官方：轮子上方信号 / 15）。 */
    public static double redstoneBrake(final Object level, final Object blockPos) {
        if (level == null || blockPos == null) return 0.0;
        try {
            final Object above = blockPos.getClass().getMethod("above").invoke(blockPos);
            final Object sig = level.getClass()
                    .getMethod("getSignal", Class.forName("net.minecraft.core.BlockPos"),
                            Class.forName("net.minecraft.core.Direction"))
                    .invoke(level, above, Class.forName("net.minecraft.core.Direction").getField("UP").get(null));
            return sig instanceof Number n ? n.doubleValue() / 15.0 : 0.0;
        } catch (final Throwable t) {
            return 0.0;
        }
    }

    /** BlockPos → long key（功率缓存索引；失败 → 0）。 */
    public static long posKey(final Object blockPos) {
        if (blockPos == null) return 0L;
        try {
            final Object v = blockPos.getClass().getMethod("asLong").invoke(blockPos);
            return v instanceof Number n ? n.longValue() : 0L;
        } catch (final Throwable t) {
            return 0L;
        }
    }

    /** 追加一条点冲量（局部坐标）到该轮所属结构的力账本。 */
    public static boolean applyImpulseAtPoint(final Object forceTotal, final Object subLevel,
                                              final Vector3dc pos, final Vector3dc impulse) {
        ensure();
        if (mApplyImpulseAtPoint == null || forceTotal == null || subLevel == null) return false;
        try {
            mApplyImpulseAtPoint.invoke(forceTotal, subLevel, pos, impulse);
            return true;
        } catch (final Throwable t) {
            return false;
        }
    }
}
