package com.hdf.cryptand.engine;

import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;

/**
 * ===== 绑定接口（2026-08-30 用户：引擎仅提供绑定接口类） =====
 *
 * 组装器的【绑定接口】：虚拟元件 ↔ 实际模型的双向绑定——引擎不关心绑定对象
 * 具体是什么（MC 的 BE 模型 / 网页按钮 / 其他平台控件——由平台实现）：
 *   - MC 平台：实现为 DeviceBinding（BE ↔ 元件——破坏/移除互发消息 + 温度/快照）
 *   - 其他平台（如网页）：实现为对应控件绑定（按钮/滑块 ↔ 元件参数）
 *
 * 引擎仅定义接口（onBound/onUnbound——引擎侧生命周期回调），平台实现绑定。
 */
public interface Binding {

    /** 元件已绑定（引擎在组装完成时调用——平台执行实际绑定）；
     *  绑定为 null（不绑定）时引擎不调用本方法。 */
    void onBound(CompositeElement element);

    /** 元件已解除绑定（引擎在元件移除时调用——平台清理） */
    void onUnbound(CompositeElement element);

    /**
     * 【消息接口——绑定器必须实现】（2026-08-30 用户："绑定器与组装器之间
     *  可以进行消息交互；求解完成一种后直接向绑定器发送消息；绑定器必须
     *  要有消息接口"）：引擎求解/网络操作完成 → 直接向绑定器发送消息
     *  （SOLVE_DONE/NETWORK_REBUILT/PARAM_CHANGED/NETWORK_INVALIDATED）。
     *  平台实现处理消息（MC：更新 BE 状态/护目镜；网页：刷新控件）。
     */
    void onMessage(EngineMessage message);

    /**
     * 【加载状态——绑定增加一个 bool 表示是否加载（2026-09-11 用户）】：
     *   - 创建 BE 电气设备（真实放置）→ 默认【true（已加载）】；
     *   - 区块加载 → 平台对该区块内全部电气设备的绑定批量置 true（「开启」）；
     *   - 区块卸载 → 批量置 false（「关闭」）。
     * <p>未加载 ≠ 已移除：电路拓扑与虚拟模型【完整保留、不删除】（保证区块卸载
     * 时电路仍旧保留计算），仅标记可用性供引擎/平台判断。
     */
    default void setLoaded(boolean loaded) {
    }

    /** 当前是否已加载（区块加载状态；默认 true）。 */
    default boolean isLoaded() {
        return true;
    }
}
