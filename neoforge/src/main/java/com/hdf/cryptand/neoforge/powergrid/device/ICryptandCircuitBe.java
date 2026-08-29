package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser;
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
    default void cryptandOnEngineMessage(Object data) {
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
