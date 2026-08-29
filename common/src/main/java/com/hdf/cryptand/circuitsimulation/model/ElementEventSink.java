package com.hdf.cryptand.circuitsimulation.model;

/**
 * 元件/网络 → 实际模型（MC 方块实体）事件接收接口（2026-08-12 用户要求）。
 * <p>
 * 实际模型只需实现本接口【接收】事件（过热/爆炸/重置），完全解耦——不知道
 * 引擎内部（MNA/相量/网络结构）。引擎在计算时发现状态（温度超限）→ 调事件；
 * 具体反应（破坏方块/爆炸/音效/视觉）由实际模型自己决定。
 * <p>
 * 绑定：复合元件构造后 {@code composite.bindEvents(sink)}（构建时由实际模型
 * 包装成接收器传入）。引擎更新时检测到过热 → {@link #onOverheated()}；明确
 * 销毁指令 → {@link #onExplode()}；网络重建/世界初始化 → {@link #onReset()}。
 */
public interface ElementEventSink {

    /** 过热（温度 ≥ 最高安全温度）。实际模型可决定如何响应（立即/延迟爆炸）。 */
    default void onOverheated() {}

    /** 爆炸（过热未处理/明确销毁指令）。实际模型应销毁方块 + 爆炸效果。 */
    default void onExplode() {}

    /** 重置（网络重建/世界初始化）。实际模型可同步视觉状态（可选）。 */
    default void onReset() {}
}
