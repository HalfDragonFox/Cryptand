package com.hdf.cryptand.neoforge.powergrid.device.motor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ===== 电机完全接管【静态工具类】（PowerGrid 0.6.0.1 三种电机 Mixin 共用） =====
 *
 * 三个电机 Mixin（普通 ElectricMotor / 恒速 ConstantSpeed / 伺服 Servo）调用本类
 * 静态方法：
 *   - {@link #applyFromStore(Object)}：从异步引擎状态（MotorStateStore）应用值
 *     （消息缺失时兜底；幂等，零计算）
 *   - {@link #applyValues(Object, double, double, double)}：写引擎权威值
 *     ω/应力/温度 → 反射写字段 + thermalBehaviour.setTemperature + Create 同步
 *   - {@link #syncCreate(BlockEntity)}：触发 Create 网络传播 + 抑制启动闪烁
 *
 * ⚠【核心原则】本类【不计算】任何物理量：转速/应力/温度全部来自异步引擎
 * 结果（BeBridge 消息 或 MotorStateStore），主线程只做"读值→写字段→触发
 * Create 同步"，符合 2026-08-25 用户："计算已经异步计算完毕，转速、应力、
 * 温度直接接受覆写即可，不需要主线程再计算"。
 *
 * ⚠ 为什么是静态工具类而非 mixin 基类：mixin 类继承的父类必须出现在目标类
 * 的继承链中（SparkleParent 规则），普通 Object 基类无法嫁接 → 静态工具类
 * 最安全（三个 mixin 各自独立，无继承冲突）。
 *
 * 唯一写入点约定：字段写入只发生在本类 + BeBridge（PowerGridMotorBeBridge
 * 收到引擎消息时）；两者幂等，同一值重复写无副作用。
 */
public final class MotorTakeoverBase {

    private MotorTakeoverBase() {
    }

    /* ==================== 反射字段读写（字段存在才操作） ==================== */

    /** 反射读实例字段（沿继承链）；失败 null。 */
    public static Object getField(Object obj, String name) {
        if (obj == null) return null;
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                } catch (Throwable ignored) {
                    return null;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 反射写实例字段（沿继承链；字段不存在 → 静默跳过）。 */
    public static void setField(Object obj, String name, Object val) {
        if (obj == null) return;
        try {
            Class<?> c = obj.getClass();
            while (c != null && c != Object.class) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(obj, val);
                    return;
                } catch (NoSuchFieldException ignored) {
                    c = c.getSuperclass();
                } catch (Throwable ignored) {
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 引擎状态应用（唯一写入点，零计算） ==================== */

    /**
     * 兜底应用：从异步引擎状态存储（MotorStateStore{ω, emf, stress[, temp]}）
     * 应用值到 BE。无引擎状态（未建模/未在引擎）→ 不干预（保持原值）。
     */
    public static void applyFromStore(Object self) {
        try {
            BlockEntity be = (BlockEntity) self;
            BlockPos pos = be.getBlockPos();
            double[] s = com.hdf.cryptand.neoforge.powergrid.adapter
                    .MotorStateStore.get(pos);
            if (s == null || s.length < 1) return;
            double temp = s.length >= 4 ? s[3] : Double.NaN;
            applyValues(self, s[0], s.length > 2 ? s[2] : 0, temp);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 写入引擎权威值：
     *   - avgSpeed ← 引擎 rpm（带方向；原版累积字段，语义由 lazyTick 覆写接管）
     *   - generatedSpeed ← 引擎 rpm（带方向；普通/伺服）
     *   - generatedSU ← 引擎应力（恒速）
     *   - load ← 引擎应力
     *   - thermalBehaviour.setTemperature ← 引擎温度（NaN 跳过）
     * 最后由 {@link #syncCreate(BlockEntity)} 触发 Create 网络传播。
     */
    public static void applyValues(Object self, double omega, double stress,
                                   double tempC) {
        try {
            // ⚠ 2026-08-26 NaN 防线：rpm/stress 非有限 → 不写（避免污染 Create 网络）
            if (!Double.isFinite(omega) || !Double.isFinite(stress)) return;
            // ⚠ 2026-08-28 用户：16384 限制由【具体电机组装器】实现（模型仅纯计算）——
            // 写 Create generatedSU/load 前饱和为 Create 满刻度
            stress = Math.min(stress, 16384.0);
            BlockEntity be = (BlockEntity) self;
            float sign = omega >= 0 ? 1f : -1f;
            float rpm = (float) (Math.abs(omega) * 60.0 / (2.0 * Math.PI)) * sign;
            // ① 原版字段（每种电机只写它有的字段；反射不存在自动跳过）
            setField(self, "avgSpeed", rpm);
            setField(self, "generatedSpeed", rpm);
            setField(self, "generatedSU", (float) stress);
            setField(self, "load", (float) stress);
            // ② 温度写回原版 ThermalBehaviour（护目镜/温度计显示一致）
            if (Double.isFinite(tempC)) {
                Object tb = getField(self, "thermalBehaviour");
                if (tb instanceof org.patryk3211.powergrid.electricity
                        .base.ThermalBehaviour th) {
                    th.setTemperature((float) tempC);
                }
            }
            // ③ Create 网络同步（存在网络才传播；内部规避 NPE）
            syncCreate(be);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 触发 Create 网络同步：updateGeneratedRotation（speed 变化 → applyNewSpeed
     * → onSpeedChanged → 网络传播）+ 抑制启动方向闪烁误报。
     * ⚠ 2026-08-26 B 方案：脏标记——值未变（转速/应力相同）跳过网络传播
     *   （十万级设备时每 tick 全量传播是最大开销；稳态大量设备转速恒定 → 跳过
     *   updateGeneratedRotation，仅真变化才触发网络扩散）。
     *   变化阈值：rpm 差 > 1e-3（浮点噪声）或 应力差 > 0.5。
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, float[]> LAST_SYNC =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final float RPM_EPS = 1e-3f, STRESS_EPS = 0.5f;

    public static void syncCreate(BlockEntity be) {
        try {
            var g = (com.simibubi.create.content.kinetics.base
                    .GeneratingKineticBlockEntity) be;
            long key = be.getBlockPos().asLong();
            float rpm = 0, stress = 0;
            try {
                Object gs = getField(be, "generatedSpeed");
                if (gs instanceof Number n) rpm = n.floatValue();
                Object ld = getField(be, "load");
                if (ld instanceof Number n) stress = n.floatValue();
            } catch (Throwable ignored) {
            }
            float[] last = LAST_SYNC.get(key);
            if (last != null
                    && Math.abs(last[0] - rpm) < RPM_EPS
                    && Math.abs(last[1] - stress) < STRESS_EPS) {
                // 值未变 → 跳过网络传播（稳态零开销）
                return;
            }
            LAST_SYNC.put(key, new float[]{rpm, stress});
            g.updateGeneratedRotation();
            // 引擎值是权威 → 启动方向提示粒子（flicker）是原版误报 → 抑制
            try {
                var acc = (org.patryk3211.powergrid.mixin
                        .KineticBlockEntityAccessor) g;
                acc.setFlickerTally(0);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    /** 清理（方块移除时应调，防 map 泄漏）——调用方在 BE.removeMixin。 */
    public static void syncCleanup(BlockEntity be) {
        if (be == null) return;
        try { LAST_SYNC.remove(be.getBlockPos().asLong()); } catch (Throwable ignored) { }
    }

    /* ==================== 诊断（节流 5s） ==================== */

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> DBG =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static void diag(Object self, String tag, float rpm, float load) {
        try {
            String key = tag + '@' + self.getClass().getSimpleName();
            long now = System.currentTimeMillis();
            Long last = DBG.get(key);
            if (last != null && now - last < 5000) return;
            DBG.put(key, now);
            BlockPos p = ((BlockEntity) self).getBlockPos();
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[{}] pos={} rpm={} load={}",
                    tag, p, String.format("%.1f", rpm),
                    String.format("%.0f", load));
        } catch (Throwable ignored) {
        }
    }

    /** 异常打印（节流 5s/位置）：catch 块统一入口，避免每 tick 刷屏。 */
    public static void logErr(String tag, Throwable t) {
        try {
            String key = "err@" + tag;
            long now = System.currentTimeMillis();
            Long last = DBG.get(key);
            if (last != null && now - last < 5000) return;
            DBG.put(key, now);
            if (t == null) {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                        "[{}] exception: null", tag);
            } else {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                        "[{}] exception {}: {}",
                        tag, t.getClass().getName(),
                        String.valueOf(t.getMessage()), t);
            }
        } catch (Throwable ignored) {
        }
    }
}
