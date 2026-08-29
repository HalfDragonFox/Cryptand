/**
 * ===== 引擎消息总线（2026-08-15 完全异步架构的核心通道） =====
 *
 * 用户设计："消息传输时传输方块引用或者位置，然后在主线程统一处理，
 * 然后 BE 记录组装器 id 或者引用，和消息一起发送处理。"
 *
 * 本总线 = 【异步线程 → 主线程】的通用消息通道：
 *   - 消息 {@link Msg} 携带：
 *       pos        —— 位置（坐标记录，必带）
 *       be         —— 方块引用（引用传输，可 null = 后台仅知坐标）
 *       composite  —— 组装器引用（BE 记录/绑定反查，随消息一起发送；可 null）
 *       data       —— 类型相关附加数据（导线边列表/原因/网络等）
 *   - 异步线程（求解/推进）只 {@link #post} 消息，【绝不碰 level/BE】；
 *   - 主线程每 tick {@link #process} 统一消费：按类型分发执行世界副作用
 *     （破坏方块/爆炸/导线移除/网络失效）——这些只能主线程做。
 *
 * 与 {@link DestructionQueue} 的关系：销毁执行逻辑（破坏/爆炸/导线烧毁）
 * 复用其方法（本总线是通用入口，DestructionQueue 保留兼容）。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.concurrent.ConcurrentHashMap;

public final class EngineBus {

    /** 消息类型（可扩展：销毁设备/销毁导线/网络失效/参数变化……） */
    public enum Type {
        /** 过热/过载销毁设备：data = reason(String) */
        DESTROY_DEVICE,
        /** 导线段统一烧毁：data = Object[]{segmentKey, List<WireEdge>, reason} */
        DESTROY_WIRE,
        /** 网络失效重建：data = ElectricalNetwork（null = 全局拓扑） */
        INVALIDATE_NETWORK,
        /** 引擎虚拟转速 → BE 应用（2026-08-15 机电引擎化）：
         *  data = double[]{radS, rpm}；BE 用速度差→force 控制实际转子跟随 */
        ROTOR_SPEED
    }

    /** 消息：pos + be(引用) + composite(组装器引用) + data */
    public static final class Msg {
        public final Type type;
        public final BlockPos pos;
        public final BlockEntity be;
        public final CompositeElement composite;
        public final Object data;

        Msg(Type type, BlockPos pos, BlockEntity be, CompositeElement composite, Object data) {
            this.type = type;
            this.pos = pos;
            this.be = be;
            this.composite = composite;
            this.data = data;
        }

        @Override
        public String toString() {
            return "EngineBus.Msg{" + type + " pos=" + pos + " be="
                    + (be == null ? "null" : be.getClass().getSimpleName())
                    + " composite=" + (composite == null ? "null"
                    : composite.getClass().getSimpleName()) + "}";
        }
    }

    /** ⚠ 2026-08-24 只保留最新：key = 类型+坐标 → put 覆盖旧消息。
     *  异步 100Hz 发 ROTOR_SPEED vs 主线程 20Hz 消费，若队列堆积，
     *  主线程可能消费【旧转速】覆盖刚写入的最新值（BE 永远滞后一步）。
     *  覆盖式 = 任何时刻队列里只有每设备【最新状态】，旧的一律丢弃。 */
    private static final ConcurrentHashMap<String, Msg> QUEUE = new ConcurrentHashMap<>();

    private EngineBus() {
    }

    /** key：类型 + 坐标（同类型同位置只留最新一条） */
    private static String keyOf(Type type, BlockPos pos) {
        return type.name() + '@' + (pos == null ? "-" : pos.asLong());
    }

    /** 异步线程发消息（线程安全，绝不碰 level/BE 内部方法） */
    public static void post(Type type, BlockPos pos, BlockEntity be,
                            CompositeElement composite, Object data) {
        QUEUE.put(keyOf(type, pos),
                new Msg(type, pos, be, composite, data));
    }

    /** 便捷：仅 pos + 类型（坐标记录方式） */
    public static void post(Type type, BlockPos pos) {
        QUEUE.put(keyOf(type, pos), new Msg(type, pos, null, null, null));
    }

    /** 队列大小（诊断） */
    public static int size() {
        return QUEUE.size();
    }

    /**
     * 主线程统一消费处理（每 tick 调）：按类型分发执行世界副作用。
     * 所有处理都发生在主线程（level/BE 安全）。
     */
    public static void process(Level level) {
        // 主线程更新接口：所有活跃 BeBridge 的 serverTick（每 tick 必跑，队列空也要）
        try {
            com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser.tickAll(level);
        } catch (Throwable ignored) {
        }
        if (level == null || QUEUE.isEmpty()) return;
        // 2026-08-24 总线诊断（节流 5s）：ROTOR_SPEED 消息是否真的到达主线程/消费
        // ——若计数=0 → 异步 listener/post 链路断；>0 → 在 dispatch 中定位。
        long nowB = System.currentTimeMillis();
        if (nowB - BUS_DBG_LAST >= 5000) {
            BUS_DBG_LAST = nowB;
            int rot = 0, other = 0;
            for (var ee : QUEUE.entrySet()) {
                if (ee.getValue().type == Type.ROTOR_SPEED) rot++;
                else other++;
            }
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[BusDbg] queue={} rotor={} other={} lvl={}",
                    QUEUE.size(), rot, other,
                    level == null ? "null" : level.dimension());
        }
        // 迭代移除式消费：处理期间异步 put 的新值留下轮（保证每设备每轮只应用最新）。
        var it = QUEUE.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            it.remove();
            try {
                dispatch(level, e.getValue());
            } catch (Throwable ignored) {
                // 单条消息失败不影响其余
            }
        }
    }

    /** 类型分发 */
    private static void dispatch(Level level, Msg m) {
        switch (m.type) {
            case DESTROY_DEVICE -> {
                if (m.pos == null) break;
                // 复用销毁执行器（破坏方块 + 爆炸 + 清理温度/虚拟）
                DestructionQueue.destroyModel(level, m.pos);
                // 组装器引用（BE 记录/绑定反查）：通知移除清理（温度/虚拟/注销）
                CompositeElement ce = m.composite;
                if (ce == null) {
                    DeviceBinding db = DeviceBinding.forPos(m.pos);
                    if (db != null) ce = db.assemble();
                }
                if (ce != null) {
                    try {
                        ce.notifyRemoved();
                    } catch (Throwable ignored) {
                    }
                }
            }
            case DESTROY_WIRE -> {
                Object[] arr = (Object[]) m.data;
                if (arr == null || arr.length < 3) break;
                DestructionQueue.requestWire(String.valueOf(arr[0]),
                        (java.util.List<WireEdge>) arr[1], String.valueOf(arr[2]));
            }
            case INVALIDATE_NETWORK -> {
                Object n = m.data;
                if (n instanceof org.patryk3211.powergrid.electricity.sim.ElectricalNetwork net) {
                    CryptandTopologyManager.get().markNetworkChanged(net);
                } else {
                    CryptandTopologyManager.get().markTopologyChanged();
                }
            }
            case ROTOR_SPEED -> {
                // 引擎虚拟转速+应力 → BE（经 BeBridge 抽象；2026-08-24 架构：
                // 组装器只面向 BE 基类编程，具体 mod 只实现 Bridge 子类）。
                double[] sd = (double[]) m.data;
                if (sd == null || sd.length < 1) break;
                BlockEntity be = m.be;
                if (be == null && m.pos != null) be = level.getBlockEntity(m.pos);
                if (be == null) {
                    // 2026-08-24 诊断：引擎推进了（listener 触达）但 BE 解析为空
                    // → 无法应用转速（可能消息 pos 非主机/维度错/未加载）。
                    long nowM = System.currentTimeMillis();
                    if (nowM - ROTOR_MISS_LAST >= 5000) {
                        ROTOR_MISS_LAST = nowM;
                        com.hdf.cryptand.neoforge.CryptandNeoForge
                                .WAF_LOGGER.warn(
                                "[RotorDbgMiss] pos={} beType={} lvl={}",
                                m.pos, m.be == null ? "null"
                                        : m.be.getClass().getSimpleName(),
                                level == null ? "null" : level.dimension());
                    }
                    break;
                }
                try {
                    double omega = sd[0];
                    double stressD = (sd.length >= 3) ? sd[2] : 0;
                    double powerW = 0, emfV = 0, tempC = 25;
                    try {
                        if (m.composite instanceof com.hdf.cryptand.circuitsimulation.model
                                .composite.ElectroMachineModel em0) {
                            powerW = em0.lastOutputPowerW;
                            emfV = Math.abs(em0.emfConstant) * Math.abs(omega);
                            if (em0.thermal() != null) {
                                tempC = em0.thermal().tempCelsius();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    // ===== 2026-08-26 用户架构：组装器只给【绑定的接口 BE】发消息 =====
                    // 唯一入口 = be instanceof ICryptandCircuitBe（mixin @Implements
                    // 注入接口身份）；且桥协议必须匹配 ROTOR 才发——绝不把电机罗
                    // 盘消息发给功率设备（BE 侧特化解析器按协议处理）。
                    if (be instanceof com.hdf.cryptand.neoforge.powergrid.device
                            .ICryptandCircuitBe icc) {
                        com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser bridge =
                                com.hdf.cryptand.neoforge.powergrid.device.motor
                                        .BeMessageParser.cache(be);
                        if (bridge == null
                                || bridge.protocol()
                                != com.hdf.cryptand.neoforge.powergrid.device.motor
                                .BeMessageParser.Protocol.ROTOR) {
                            // 接口身份存在但未绑定转子桥（非电机设备）：不发送
                            break;
                        }
                        icc.cryptandOnEngineMessage(
                                new com.hdf.cryptand.neoforge.powergrid.device.motor
                                        .BeMessage(
                                        omega < 0 ? -1 : 1,
                                        Math.abs(omega) * 60.0 / (2.0 * Math.PI),
                                        stressD,
                                        tempC));
                    } else {
                        // 未绑定接口（未知来源 BE）：记录后丢弃（不再走 BeBridge.of
                        // 兜底——组装器只与绑定接口交互，2026-08-26 用户）
                        long nowN = System.currentTimeMillis();
                        if (nowN - ROTOR_NOROTOR_LAST >= 5000) {
                            ROTOR_NOROTOR_LAST = nowN;
                            StringBuilder ifs = new StringBuilder();
                            for (Class<?> ci : be.getClass().getInterfaces()) {
                                if (ifs.length() > 0) ifs.append(',');
                                ifs.append(ci.getSimpleName());
                            }
                            com.hdf.cryptand.neoforge.CryptandNeoForge
                                    .WAF_LOGGER.warn(
                                    "[RotorDbgNoRotor] pos={} beCls={} ifaces={}",
                                    be.getBlockPos(),
                                    be.getClass().getSimpleName(), ifs);
                        }
                        break;
                    }
                    // 跨重建状态持久（断线重建后惯性滑行不归零，2026-08-24；
                    // 温度持久 2026-08-25：引擎 ThermalModel 温度跨重建保留）
                    try {
                        if (m.pos != null && m.composite instanceof
                                com.hdf.cryptand.circuitsimulation.model.composite
                                        .ElectroMachineModel em2) {
                            double st = (sd.length >= 3) ? sd[2] : em2.lastStressSU;
                            com.hdf.cryptand.neoforge.powergrid.adapter
                                    .MotorStateStore.put(m.pos, omega,
                                    Math.abs(em2.emfConstant) * Math.abs(omega), st);
                        }
                    } catch (Throwable ignored) {
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 世界切换/关闭 → 清空（防跨世界 pos 串扰） */
    public static void clearAll() {
        QUEUE.clear();
    }

    /** [RotorDbg] 诊断节流（2026-08-24） */
    private static volatile long ROTOR_DBG_LAST;
    /** [RotorDbgMiss] 诊断节流（2026-08-24） */
    private static volatile long ROTOR_MISS_LAST;
    /** [RotorDbgNoRotor] 诊断节流（2026-08-24） */
    private static volatile long ROTOR_NOROTOR_LAST;
    /** [BusDbg] 诊断节流（2026-08-24） */
    private static volatile long BUS_DBG_LAST;

    /** 反射遍历 BE（含父类）字段找 IRotor 实现；找不到 null（2026-08-24 兼容） */
    private static Object findRotorField(Object obj) {
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

    /** 反射读实例字段（沿继承链）；失败 null */
    private static Object reflectField(Object obj, String name) {
        try {
            Class<?> c = obj.getClass();
            while (c != null) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
