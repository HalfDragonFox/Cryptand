package com.hdf.cryptand.neoforge.powergrid.device.motor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * BE 最基础基类（2026-08-24 用户架构 · 纯消息框架）：
 * <p>
 * 【本基类仅提供三件事】，具体业务全部在子类：
 * <ol>
 *   <li><b>接收消息框架</b>：{@link #onEngineMessage(BeMessage)}（final 模板：
 *      记录 + 诊断 → 交给子类 {@link #onMessage} 处理（消息=头+体，子类按 type
 *      解释 body，不区分具体电气设备））；</li>
 *   <li><b>发送消息框架</b>：{@link #sendToEngine(BeMessage)}（BE → 组装器：
 *      写入输入槽，组装器侧 {@link #pollInput(BlockPos)} 读）；</li>
 *   <li><b>主线程更新接口</b>：{@link #serverTick(Level)}（默认空；子类覆写做
 *      周期性回读/上报；主线程每 tick 由 {@link #tickAll(Level)} 遍历调用）。</li>
 * </ol>
 * 组装器/EngineBus 只与本类交互；具体 mod 只需继承子类实现 onMessage/serverTick 等。
 * 线程约定：桥实例方法只允许主线程调用（内部访问 BE，BE 线程不安全）。
 */
public abstract class BeMessageParser {

    // ===== 固定协议索引（各设备家族约定顺序；子类按此强转） =====
    /** 电机（引擎→BE）：[方向(+1/-1), 转速(rpm), 应力(SU)] */
    public static final int MOT_DIR = 0, MOT_RPM = 1, MOT_STRESS = 2, MOT_TEMP = 3;
    /** 热/功率设备（引擎→BE）：[功率 W]；BE→引擎：{[温度 °C]} */
    public static final int PWR_W = 0, TH_TEMP = 0;
    /** 开关/接地棒（引擎→BE）：[使能 on?0:1? 1=on] */
    public static final int SW_ON = 0;
    /** 灯泡（引擎→BE）：[开?1:0, 亮度功率 W] */
    public static final int LAMP_ON = 0, LAMP_POWER = 1;

    /**
     * 协议族（2026-08-26 用户架构：组装器只和绑定的 BE 接口类交互）：
     * 特化子类声明自己解析哪套协议，发送侧按下发消息的协议族路由——
     * 绝不把 [powerW] 广播给电机桥，也绝不把 4 元素电机消息发给功率设备桥。
     * {@link BeMessageParser} 本身仅提供协议常量/解析辅助/注册表（工具类）。
     */
    public enum Protocol {
        /** 不参与引擎消息（纯被动设备） */
        NONE,
        /** 机电转子协议：引擎→BE [方向, rpm, 应力, 温度] */
        ROTOR,
        /** 热/功率协议：引擎→BE [powerW] */
        POWER
    }

    /** 本桥（特化解析器）解析的协议族；子类必须覆写。 */
    public Protocol protocol() { return Protocol.NONE; }

    /** 实际 BE（主线程限定） */
    protected final BlockEntity be;
    protected final BlockPos pos;

    /** 最近一次引擎下发值（诊断） */
    protected float lastRpm = Float.NaN;
    protected float lastStress = Float.NaN;
    protected double lastPowerW = Double.NaN;

    /** 诊断节流（5s） */
    private static volatile long DBG_LAST;

    /** 活跃桥注册表（pos → bridge；onEngineMessage 首次到达即注册） */
    private static final java.util.concurrent.ConcurrentHashMap<BlockPos, BeMessageParser> ACTIVE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** BE → 组装器输入槽（主线程写，组装器异步读后移除） */
    private static final java.util.concurrent.ConcurrentHashMap<BlockPos, BeMessage> INPUT =
            new java.util.concurrent.ConcurrentHashMap<>();

    protected BeMessageParser(BlockEntity be) {
        this.be = be;
        this.pos = be == null ? null : be.getBlockPos();
    }

    // ==================== 主线程更新接口 ====================

    /**
     * 主线程每 tick 更新（默认空）。子类覆写：回读 BE 实际状态（转速/温度/功率）
     * → {@link #sendToEngine} 上报；或把引擎状态刷入 BE（安全写入策略由子类定）。
     */
    public void serverTick(net.minecraft.world.level.Level level) { /* no-op */ }

    /** 主线程遍历全部活跃桥并调用 serverTick（EngineBus.process 每 tick 调用）。 */
    public static void tickAll(net.minecraft.world.level.Level level) {
        if (level == null || ACTIVE.isEmpty()) return;
        for (BeMessageParser b : ACTIVE.values()) {
            try { b.serverTick(level); } catch (Throwable ignored) { }
        }
    }

    /** 桥失效（设备移除/卸载）：从注册表与输入槽清理。 */
    public static void unregister(BlockPos pos) {
        if (pos == null) return;
        ACTIVE.remove(pos);
        INPUT.remove(pos);
    }

    /**
     * 获取/复用桥（发送侧与接收侧共享实例）：ACTIVE 命中直接返回；miss →
     * {@link #of(BlockEntity)} 建桥并注册（未注册前 serverTick 也会被遍历到）。
     * 主线程调用。
     */
    public static BeMessageParser cache(BlockEntity be) {
        if (be == null) return null;
        BlockPos bp = be.getBlockPos();
        BeMessageParser b = ACTIVE.get(bp);
        if (b != null) return b;
        b = of(be);
        if (b != null) ACTIVE.put(bp.immutable(), b);
        return b;
    }

    // ==================== 引擎 → BE：应用状态（子类按设备实现） ====================

    /**
     * 【统一消息处理】（final 模板方法）：引擎下发【Object 数据体】→ 本基类。
     * 基类统一做：记录 + 5s 节流诊断（[RotorDbg] 引擎目标 vs BE 实际回读）→
     * 再委托 {@link #onMessage(Object)}（特化子类按固定协议解释数据）。
     * 2026-08-26 用户架构：接口层只收发 Object；专门处理全部在下层桥。
     */
    public final void onEngineMessage(Object msg) {
        if (msg == null) return;
        if (pos != null) ACTIVE.put(pos.immutable(), this); // 首次到达即注册（主线程更新）
        // 通用记录（不假设设备类型；诊断打印整体 msg）
        lastRpm = Float.NaN;
        lastStress = Float.NaN;
        lastPowerW = Double.NaN;
        long now = System.currentTimeMillis();
        if (now - DBG_LAST >= 5000) {
            DBG_LAST = now;
            try {
                float act = currentRadS() * 60.0f / (float) (2.0 * Math.PI);
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[RotorDbg] pos={} bridge={} msg={} actualGen={}",
                        pos, describe(), msg,
                        String.format("%.0f", act));
            } catch (Throwable ignored) {
            }
        }
        try {
            onMessage(msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【具体 BE 子类覆写】——如何处理收到的【Object 数据体】：特化解析器按
     * 固定协议解释（电机 = 数字数组/BeMessage，强转后用 BeMessage.of/num 解析）。
     * 默认 no-op；第三方 API 缺陷需自行规避（如 PowerGrid applyNewSpeed
     * NPE——见其子类注释）。 */
    protected void onMessage(Object msg) { /* no-op */ }

    // ==================== 发送消息框架：BE → 组装器（2026-08-26：BE 每 tick 主动） ====================

    /**
     * BE 侧每 tick 主动上报（主线程 serverTick → 本方法）：
     * 数据体可为【空】（null / 空 BeMessage）= 心跳——组装器收到后必然回发
     * 引擎状态（{@link #pollOutput} 那边响应），双向保活。
     * 入 INPUT 槽（覆盖式，队列恒为 1）。public：接口 cryptandSendToEngine 调用。
     */
    public final void sendToEngine(Object msg) {
        if (pos == null) return;
        INPUT.put(pos.immutable(), msg == null ? new BeMessage() : BeMessage.of(msg));
    }

    /** 发送空心跳（无数据也主动报——保活；组装器按"收到"处理并回发）。
     *  public：接口 ICryptandCircuitBe.cryptandSendHeartbeat 调用（跨包）。 */
    public final void sendHeartbeat() {
        sendToEngine(null);
    }

    /** 组装器侧：取走该 pos 的最新 BE 上报消息（无 → null；空 BeMessage = 心跳）。 */
    public static BeMessage pollInput(BlockPos pos) {
        return pos == null ? null : INPUT.remove(pos);
    }

    // ==================== BE → 引擎：回读实际状态 ====================

    /** BE 当前转子转速（rad/s；无转子 → 0）。 */
    public float currentRadS() { return 0; }

    /** BE 当前温度（°C；无温度模型 → 25）。 */
    public double temperatureC() { return 25; }

    /** 向 BE 注入热量（J；无温度模型 → no-op）。 */
    public void addHeat(double joules) { /* no-op */ }

    /** BE 当前有功功率（W；引擎诊断/功率表；未知 → NaN）。 */
    public double powerConsumedW() { return Double.NaN; }

    // ==================== 引擎 → BE：通用设备状态 ====================

    /** 下发计算功率（W）到 BE（加热器/灯等；默认 no-op + 记录）。 */
    public void applyPowerState(double powerW) { lastPowerW = powerW; }

    /** 下发使能状态（开关/接地棒等默认 no-op）。 */
    public void setEnabled(boolean on) { /* no-op */ }

    /** 当前使能状态（默认 true）。 */
    public boolean isEnabled() { return true; }

    /** 桥类型描述（诊断）。 */
    public String describe() { return be == null ? "null" : be.getClass().getSimpleName(); }

    public BlockPos pos() { return pos; }
    public BlockEntity blockEntity() { return be; }

    // ==================== 工厂：按 BE 类型返回桥（主线程） ====================

    /**
     * 按 BE 实际类型返回对应 mod 的桥；不认识 → null（调用方记录 NoRotor）。
     * 新增 mod/设备：在此加一行 + 新增一个子类即可；组装器/EngineBus 不用改。
     */
    public static BeMessageParser of(BlockEntity be) {
        if (be == null) return null;
        String fqn = be.getClass().getName();
        // 1) PowerGrid 真转子（IRotor）→ 速度差 force 跟随
        if (be instanceof org.patryk3211.powergrid.electricity.sim.special.IRotor
                r) {
            return new IRotorMotorParser(be, r);
        }
        // 2) PowerGrid（交错动力）三种电机 → 中间层 + 三个具体子类（继承）
        if (be instanceof org.patryk3211.powergrid.kinetics.motor
                .ConstantSpeedMotorBlockEntity) {
            return new PowerGridConstantSpeedMotorParser(be);
        }
        if (be instanceof org.patryk3211.powergrid.kinetics.servo
                .ServoBlockEntity) {
            return new PowerGridServoMotorParser(be);
        }
        if (be instanceof org.patryk3211.powergrid.kinetics.motor
                .ElectricMotorBlockEntity) {
            return new PowerGridElectricMotorParser(be);
        }
        // 3) CEE（george_vi）电机：反射类名匹配（未装 CEE 也不类加载错误）
        if (be.getClass().getName().equals(
                "com.george_vi.electroenergetics.content"
                        + ".electric_motor.ElectricMotorBlockEntity")) {
            return new CeeMotorParser(be);
        }
        // 4) CEE 通用设备块（DeviceBlock / ElectricalDeviceBlock，反射名）
        if (fqn.equals("com.george_vi.electroenergetics.devices"
                + ".device.DeviceBlock")
                || fqn.equals("com.george_vi.electroenergetics.foundation"
                + ".device.ElectricalDeviceBlock")
                || fqn.equals("com.george_vi.electroenergetics"
                + ".foundation.device.ElectricalDeviceBlock")) {
            return new CeeDeviceParser(be);
        }
        // 5) PowerGrid 带 ThermalBehaviour 的设备（加热器/电阻/灯泡等）
        Object tb = reflectField(be, "thermalBehaviour");
        if (tb instanceof org.patryk3211.powergrid.electricity
                .base.ThermalBehaviour) {
            return new ThermalDeviceParser(be, tb);
        }
        // 6) 自家（Cryptand）设备：DeviceThermalStore 有温度记录
        try {
            if (com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore
                    .contains(be.getBlockPos())) {
                return new CryptandDeviceParser(be);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 反射读 BE（沿继承链）实例字段；失败 null。 */
    protected static Object reflectField(Object obj, String name) {
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

    /** 反射遍历 BE（含父类）字段找 IRotor 实现；找不到 null。 */
    protected static Object findRotorField(Object obj) {
        if (obj == null) return null;
        try {
            Class<?> c = obj.getClass();
            java.util.Set<String> seen = new java.util.HashSet<>();
            while (c != null && c != Object.class) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    try {
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                            continue;
                        if (!seen.add(f.getName())) continue;
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v instanceof org.patryk3211.powergrid.electricity
                                .sim.special.IRotor) {
                            return v;
                        }
                    } catch (Throwable ignored) {
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
