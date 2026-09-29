package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser;
import com.hdf.cryptand.neoforge.powergrid.network.DestructionQueue;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ===== 通用电气设备 BE 接口（2026-08-26 用户：只有通用抽象数据接口） =====
 *
 * 【间接继承方案】原版电气设备 BE 经 Mixin `@Implements ICryptandCircuitBe` →
 * 运行时 `be instanceof ICryptandCircuitBe` 成立（无需修改原版继承链）→
 * 组装器/EngineBus 只面向本接口编程。接口只提供【通用数据收发】
 * （Object 任意数据体——各设备家族数据用【自定义数据类】直接强转操作），
 * 具体业务由各设备家族的 【BeMessageParser 解析器子类】处理：
 *
 * <pre>
 *   ICryptandCircuitBe（通用抽象数据接口 = 类型身份 + 双向数据通路）
 *     └─ BeMessageParser（基解析器：注册表/缓存/消息框架）
 *         └─ PowerGridMotorParser（电机解析器扩展）
 *             ├─ PowerGridElectricMotorParser       普通电机
 *             ├─ PowerGridConstantSpeedMotorParser  恒速电机
 *             └─ PowerGridServoMotorParser          伺服电机
 * </pre>
 *
 * 调用链（电机例）：
 *   引擎(异步) → EngineBus.post(ROTOR_SPEED) → 主线程 process
 *     → be instanceof ICryptandCircuitBe → cryptandOnEngineMessage(数据类强转)
 *     → mixin 实现层 → 电机解析器（PowerGridMotorParser.onMessage 写字段/温度/同步）
 *
 * ⚠ 接口方法全部 default（通用兜底委托解析器）；【电机 mixin 显式覆写转发】；
 *  新增模组设备 = 各自 mixin @Implements 本接口即可（一行接入）。
 * ⚠ 消息队列为 1（覆盖式）：同 pos 同类型新消息必然覆盖旧消息。
 */
public interface ICryptandCircuitBe {

    /* ==================== 引擎→BE：通用数据通路 ==================== */

    /**
     * 引擎下发通用数据（Object 任意数据体；各设备家族【自定义数据类】强转——如
     * MotorStateData = [方向, 转速, 应力, 温度]，组装器与 BE 已知结构直接强转）。
     * 默认实现：委托 {@link BeMessageParser#cache} 取解析器 →
     * {@link BeMessageParser#onEngineMessage(Object)}。
     */
    /**
     * ===== 【爆炸消息处理】2026-09-12 用户设计 =====
     *
     * 用户："发送爆炸消息到主线程，主线程根据方块信息交给具体的 BE 类进行处理
     * （通过消息函数内部处理，默认处理为爆炸+破坏此方块，可以被继承方便进行扩展操作）"
     *
     * 链路：引擎/过热检测 → {@code EngineBus.post(Type.EXPLODE, pos, be, …)} →
     * 主线程 process 按 pos 取 BE → {@code be instanceof ICryptandCircuitBe} →
     * 调用本函数【在 BE 内部处理】。
     *
     * 默认实现 = **爆炸 + 破坏该方块**（{@code DestructionQueue.destroyModel}：
     * 先 destroyBlock 拆除 → 再 explode 出效果 → 清理温度/状态存储 + 导线悬空检测）。
     * 具体设备/mixin 可【覆写本函数扩展】：换爆炸威力、掉落物品、连锁反应、
     * 只破不炸（创造设备）等，都无需改动引擎侧。
     *
     * @param level 世界（由主线程传入）
     * @param pos   方块位置
     * @param data  附加数据（如原因字符串），可为 null
     */
    default void cryptandOnExplode(Object level, Object pos, Object data) {
        // ===== 【通用层】爆炸（2026-09-13）：统一前置（创造设备保护 / 统计 / 连锁
        //   拦截 / 分区规则）→ 转交【具体层】。
        try {
            cryptandHandleExplode(level, pos, data);
        } catch (Throwable ignored) {
        }
    }

    /** 【具体层】爆炸处理（具体 BE / 设备家族覆写）：默认 = 解析器优先 → 否则销毁 */
    default void cryptandHandleExplode(Object level, Object pos, Object data) {
        // ① 【消息转发到具体 BE 类处理】：交给该 BE 的解析器（BeMessageParser 子类，
        //    按设备家族实现 onExplode——覆写即可扩展爆炸表现，无需改引擎）
        try {
            BeMessageParser p = BeMessageParser.cache(cryptandSelf());
            if (p != null && p.onExplode(level, pos, data)) {
                return; // 具体 BE 已处理
            }
        } catch (Throwable ignored) {
        }
        // ② 默认处理：爆炸 + 破坏此方块（先 destroyBlock 拆除 → 再 explode 出效果，
        //    并清理温度/状态存储 + 触发导线悬空检测）
        try {
            if (level instanceof net.minecraft.world.level.Level lv
                    && pos instanceof net.minecraft.core.BlockPos bp) {
                com.hdf.cryptand.neoforge.powergrid.network.DestructionQueue
                        .destroyModel(lv, bp);
            }
        } catch (Throwable ignored) {
        }
    }

    default void cryptandOnEngineMessage(Object data) {
        // ===== 【通用层】2026-09-13 用户："消息先经过通用接口再进入具体消息处理，
        //   方便扩展与拦截" =====
        // 所有引擎消息的第一入口：这里做统一的前置处理（null 防御 / 日志节流 /
        // 权限与开关过滤 / 统计 / 兜底），然后转交【具体层】。
        // 具体设备不需要碰这一层；想拦截某类消息也只改这里一处。
        try {
            if (data == null) return;
            cryptandHandleMessage(data);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 【通用工具】2026-09-13 用户："不再使用静态工具类，
     * 而是直接集成进相关类" ====================
     * 下列 default 方法把原 MotorTakeoverBase 的静态工具能力并入 BE 基类接口：
     * 设备/家族代码直接 cryptandReadFloat("avgSpeed") 即可，不必再 import 工具类。
     * 迁移分三步（当前为第 1 步）：接口提供同名 default（内部暂委托静态工具类）
     * → 切换调用点 → 删除静态工具类。每一步行为不变、可编译。 */

    /** BE 自身引用（平台实现：mixin 层返回 (Object) this） */
    default Object cryptandField(String name) {
        return com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase
                .getField(cryptandSelf(), name);
    }

    /** 反射写字段（沿继承链） */
    default void cryptandSetField(String name, Object value) {
        com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase
                .setField(cryptandSelf(), name, value);
    }

    /** 反射读 float 字段（avgSpeed / generatedSU…） */
    default float cryptandReadFloat(String name) {
        return com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase
                .readFloat(cryptandSelf(), name);
    }

    /** 反射读 int 字段（currentAngle / movingTicks…） */
    default int cryptandReadInt(String name) {
        return com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase
                .readInt(cryptandSelf(), name);
    }

    /** 反射调用无参数值方法（resistance() / torque()…） */
    default double cryptandCallNumber(String name) {
        return com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase
                .callNumber(cryptandSelf(), name);
    }

    /** 自管引擎的设备电流（A；未建模 0） */
    default double cryptandDeviceCurrent() {
        try {
            if (cryptandSelf() instanceof net.minecraft.world.level.block.entity
                    .BlockEntity be) {
                return com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase
                        .deviceCurrent(be);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * ===== 【具体层】消息处理（设备家族 / 具体 BE 覆写）=====
     * 默认实现：转发给 BE 的解析器（{@link BeMessageParser}）。
     * 通用电机等家族可在子接口里覆写（如 ICryptandMotorBE 处理 ROTOR 协议），
     * 具体设备再覆写实现自己的语义。
     */
    default void cryptandHandleMessage(Object data) {
        try {
            BeMessageParser p = BeMessageParser.cache(cryptandSelf());
            if (p != null) p.onEngineMessage(data);
        } catch (Throwable ignored) {
        }
    }

    /* ==================== BE→引擎：BE 每 tick 主动发送（2026-08-26 用户） ==================== */

    /**
     * BE → 组装器：数据由 BE 侧【每 tick 主动上报】（Object 任意数据体；各设备
     * 家族自定义数据类强转）。默认实现：委托解析器 {@link BeMessageParser#sendToEngine}
     * （输入槽，同 pos 覆盖式——队列恒为 1）。
     */
    default void cryptandSendToEngine(Object data) {
        try {
            BeMessageParser p = BeMessageParser.cache(cryptandSelf());
            if (p != null) p.sendToEngine(data);
        } catch (Throwable ignored) {
        }
    }

    /**
     * BE 每 tick 主动发【空心跳】（保活）：组装器收到空包 = BE 还活着 → 回发
     * 引擎状态（{@link #cryptandOnEngineMessage(Object)} 方向）；双向空包心跳。
     * 默认实现：委托解析器 {@link BeMessageParser#sendHeartbeat()}。
     */
    default void cryptandSendHeartbeat() {
        try {
            BeMessageParser p = BeMessageParser.cache(cryptandSelf());
            if (p != null) p.sendHeartbeat();
        } catch (Throwable ignored) {
        }
    }

    /* ==================== 工具 ==================== */

    /** 强转自身为 BlockEntity（运行时目标类一定是 BE；失败 null）。 */
    default BlockEntity cryptandSelf() {
        try {
            return (BlockEntity) (Object) this;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
