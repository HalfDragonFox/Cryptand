package com.hdf.cryptand.neoforge.powergrid.device.motor;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCurrent;
import com.hdf.cryptand.neoforge.powergrid.state.MotorStateStore;
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

    /* ===== 解析结果缓存（2026-09-13 性能）：电机 tick 每 tick 都要读写 avgSpeed/
     *  generatedSpeed/load 等字段，若每次都沿继承链 getDeclaredField + setAccessible，
     *  十万级设备时是纯浪费（记忆库"十万级设备零开销"要求）。Field/Method 对象
     *  本身线程安全（setAccessible 后并发 get/set 没问题），故按 类名#字段名 缓存。===== */
    private static final Object MISS = new Object();
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> FIELD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Object> METHOD_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 解析实例字段（沿继承链）；失败 null。结果进 FIELD_CACHE（MISS 表示确认不存在）。 */
    public static java.lang.reflect.Field findField(Class<?> cls, String name) {
        if (cls == null || name == null) return null;
        String key = cls.getName() + '#' + name;
        Object c = FIELD_CACHE.get(key);
        if (c == null) {
            java.lang.reflect.Field f = null;
            for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
                try {
                    f = k.getDeclaredField(name);
                    f.setAccessible(true);
                    break;
                } catch (NoSuchFieldException ignored) {
                } catch (Throwable ignored) {
                    break;
                }
            }
            c = (f == null) ? MISS : (Object) f;
            FIELD_CACHE.put(key, c);
        }
        return c == MISS ? null : (java.lang.reflect.Field) c;
    }

    /** 解析无参/带参方法（类层次 → 接口层次 → 公开方法）；失败 null。结果进 METHOD_CACHE。 */
    public static java.lang.reflect.Method findMethodCached(
            Class<?> cls, String name, Class<?>... params) {
        if (cls == null || name == null) return null;
        StringBuilder sb = new StringBuilder(cls.getName()).append('#').append(name);
        for (Class<?> p : params) sb.append(',').append(p == null ? "null" : p.getName());
        String key = sb.toString();
        Object c = METHOD_CACHE.get(key);
        if (c == null) {
            java.lang.reflect.Method m = findNumberMethod(cls, name, params);
            if (m != null) {
                try {
                    m.setAccessible(true);
                } catch (Throwable ignored) {
                }
            }
            c = (m == null) ? MISS : (Object) m;
            METHOD_CACHE.put(key, c);
        }
        return c == MISS ? null : (java.lang.reflect.Method) c;
    }

    /** 反射读实例字段（沿继承链）；失败 null。 */
    public static Object getField(Object obj, String name) {
        if (obj == null) return null;
        try {
            java.lang.reflect.Field f = findField(obj.getClass(), name);
            return f == null ? null : f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 反射写实例字段（沿继承链；字段不存在 → 静默跳过）。 */
    public static void setField(Object obj, String name, Object val) {
        if (obj == null) return;
        try {
            java.lang.reflect.Field f = findField(obj.getClass(), name);
            if (f != null) f.set(obj, val);
        } catch (Throwable ignored) {
        }
    }

    /* ============================================================================
     * 原版电机语义（2026-09-12 用户："原版三种电机效果和原版类似，即开即停，
     * 并且输出固定应力以及转速，不会有反馈，总之参考原版代码"）
     *
     * 原版 PowerGrid 三种电机的机械输出（ElectricMotor/ConstantSpeed/Servo 共用）：
     *   tick    : avgSpeed += calculateSpeed(V²/R, torque()) × signum(coil.current())
     *   lazyTick: newSpeed = clamp(avgSpeed/5, ±maxRPM) → generatedSpeed/generatedSU
     *   应力     : torque() = BlockStressValues.getCapacity(block) × torqueForStress
     *              —— 固定值，不随负载/转速变化（无反馈）
     *   电气侧   : 线圈就是一个固定电阻（builder.connect(resistance(), …)），
     *              不产生 EMF、不随负载改变阻抗
     *
     * 自管模式下原版 coil.potentialDifference() 不可用（原版时域已禁用）→ 用
     * 引擎的【设备电流 I】代入同一公式：P = I²·R（纯电阻负载上与 V²/R 等价）。
     * 这样公式、限幅、即开即停（无惯性积分）、固定应力全部与原版一致。
     * ========================================================================= */

    /** 原版换算常数（ElectricMotorBlockEntity.CONVERSION_CONSTANT = 60π/2） */
    public static final double CONVERSION_CONSTANT = 60 * Math.PI / 2.0;

    /** 自管引擎的设备电流（A；未建模/无记录 → 0） */
    public static double deviceCurrent(BlockEntity be) {
        try {
            if (be == null) return 0;
            double i = com.hdf.cryptand.neoforge.powergrid.state.DeviceCurrent
                    .read(be.getBlockPos());
            return Double.isFinite(i) ? i : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /* ============================================================================
     * ⚠⚠ 2026-09-13 根因修复："原版电机转轴不转，但自定义的会转"
     *
     * 真因：resistance() / resistance(String) 是接口 IElectricEntity 的
     *   【default 方法】（默认体 = ResistanceValues.get(block[, id])），三个原版电机
     *   类都没有覆写它。而旧实现用
     *       c.getDeclaredMethod(name)  沿 getSuperclass() 上溯
     *   —— getDeclaredMethod 【只返回本类声明的方法，不返回继承/接口方法】，
     *   且沿 superclass 上溯【永不进入接口】⇒ resistance() 永远找不到 ⇒ 返回 0。
     *   后果：vanillaSpeedRpm 里 r = 1e-9 ⇒ P = I²·1e-9 ≈ 0 ⇒ rpm ≡ 0 ⇒ 转轴不转。
     *   （torque() 是各电机类自己声明的 public 方法 → 能找到，所以只有电阻踩雷。）
     *
     * 修复：方法解析改为 类层次 → 接口层次（含 default） → 公开方法兜底。
     * ========================================================================== */

    /** 解析数值方法：类层次 → 接口层次（含 default 方法）→ 公开方法兜底；失败 null。 */
    public static java.lang.reflect.Method findNumberMethod(
            Class<?> cls, String name, Class<?>... params) {
        if (cls == null) return null;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            java.lang.reflect.Method m = findInInterfaces(c.getInterfaces(), name, params);
            if (m != null) return m;
        }
        try {
            return cls.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static java.lang.reflect.Method findInInterfaces(
            Class<?>[] ifaces, String name, Class<?>... params) {
        if (ifaces == null) return null;
        for (Class<?> i : ifaces) {
            try {
                return i.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
            java.lang.reflect.Method m = findInInterfaces(i.getInterfaces(), name, params);
            if (m != null) return m;
        }
        return null;
    }

    /** 反射调用无参数值方法（resistance() / torque() / getValue()…）；失败 0 */
    public static double callNumber(Object obj, String name) {
        if (obj == null) return 0;
        try {
            java.lang.reflect.Method m = findMethodCached(obj.getClass(), name);
            if (m == null) return 0;
            m.setAccessible(true);
            Object v = m.invoke(obj);
            return v instanceof Number n ? n.doubleValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 反射调用【带一个 String 参数】的数值方法（resistance("idle")/("on")…）；失败 0 */
    public static double callNumberStr(Object obj, String name, String arg) {
        if (obj == null) return 0;
        try {
            java.lang.reflect.Method m = findMethodCached(obj.getClass(), name, String.class);
            if (m == null) return 0;
            m.setAccessible(true);
            Object v = m.invoke(obj, arg);
            return v instanceof Number n ? n.doubleValue() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 反射读 int 字段（currentAngle / movingTicks…）；失败 0 */
    public static int readInt(Object obj, String name) {
        Object v = getField(obj, name);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /** 反射读 float 字段（avgSpeed / generatedSU…）；失败 0 */
    public static float readFloat(Object obj, String name) {
        Object v = getField(obj, name);
        return v instanceof Number n ? n.floatValue() : 0f;
    }

    /** 反射累加 float 字段（avgSpeed += d）；失败忽略 */
    public static void addFloat(Object obj, String name, double d) {
        float cur = readFloat(obj, name);
        setField(obj, name, (float) (cur + d));
    }

    /** 原版转速公式（RPM）：speed = P / torque × 60π/2 × sign(I)，P = I²·R */
    public static double vanillaSpeedRpm(BlockEntity be, double resistance, double torque) {
        try {
            if (be == null || !(torque > 0)) return 0;
            double i = deviceCurrent(be);
            if (i == 0) return 0;
            double r = resistance > 0 ? resistance : 1e-9;
            double p = i * i * r;
            return p / torque * CONVERSION_CONSTANT * Math.signum(i);
        } catch (Throwable ignored) {
            return 0;
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
            double[] s = com.hdf.cryptand.neoforge.powergrid.state.MotorStateStore.get(pos);
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
            // ⚠ 2026-09-13：omega 有限即可（stress 允许 NaN = 引擎不提供应力）
            if (!Double.isFinite(omega)) return;
            BlockEntity be = (BlockEntity) self;
            float sign = omega >= 0 ? 1f : -1f;
            float rpm = (float) (Math.abs(omega) * 60.0 / (2.0 * Math.PI)) * sign;
            // ① 转速字段（每种电机只写它有的字段；反射不存在自动跳过）
            //   ⚠ avgSpeed 必须写 rpm×5：原版语义是"累加器，lazyTick 时 /5 后清零"，
            //     写 rpm 会让 lazyTick 折算成 rpm/5（实测"转轴几乎不转"）。
            setField(self, "avgSpeed", rpm * 5f);
            setField(self, "generatedSpeed", rpm);
            // ② 应力【默认不写】：用户"输出固定应力以及转速，不会有反馈"——
            //    原版电机的应力由原版 torque()/Create BlockStressValues 提供，
            //    引擎下发 NaN（不提供）时保持原值；恒速电机的 generatedSU 由它
            //    自己的 lazyTick 按 avgSpeed 折算。只有引擎确有应力值才写。
            if (Double.isFinite(stress)) {
                // 应力（2026-09-13 用户："应力和转速都通过每次计算发送"）——
                // 引擎每轮算出并下发；16384 限制由组装器实现（模型仅纯计算），
                // 写前饱和。只写 generatedSU（= 电机输出的应力容量；恒速电机
                // getGeneratedSpeed 的"是否通电"判据 + Create 应力显示都用它，
                // 与其 lazyTick 折算结果一致）；⚠ 不写 load —— load 是 Create
                // 网络侧的【负载消耗】，应由网络真实计算，写它会让应力表显示错值。
                setField(self, "generatedSU", (float) Math.min(stress, 16384.0));
            }
            // ② ⚠ 2026-09-12 用户："温度不再使用原版路径，全部使用自管"——
            //    不再把自管温度写回原版 ThermalBehaviour。读取方（护目镜/手持与方块
            //    温度计/过热判定）统一直接读 DeviceThermalStore，写回只会制造
            //    第二份温度状态并污染原版字段。
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

    /** pos 版清理（真拆除时方块/BE 已缺失，仅 pos 可用；2026-08-30 审计 C16：
     *  原 syncCleanup 无调用方 → LAST_SYNC 泄漏 + 同位置重放新设备首次
     *  syncCreate 可能误判"值未变"跳过 Create 网络传播。由
     *  NetworkDestructionDetector 真拆除路径调用）。 */
    public static void syncCleanupPos(BlockPos pos) {
        if (pos == null) return;
        try { LAST_SYNC.remove(pos.asLong()); } catch (Throwable ignored) { }
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
            CryptandNeoForge.WAF_LOGGER.info(
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
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[{}] exception: null", tag);
            } else {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[{}] exception {}: {}",
                        tag, t.getClass().getName(),
                        String.valueOf(t.getMessage()), t);
            }
        } catch (Throwable ignored) {
        }
    }
}
