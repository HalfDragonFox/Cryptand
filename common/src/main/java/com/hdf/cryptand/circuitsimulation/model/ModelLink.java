package com.hdf.cryptand.circuitsimulation.model;

/**
 * 实际模型（MC 方块实体）↔ 复合元件 双向绑定（2026-08-13 用户要求）。
 * <p>
 * 实际模型与引擎复合元件互持引用、互发消息，完全解耦：
 *   - 【实际模型破坏 → 引擎】{@link #notifyModelDestroyed()}：MC 方块被破坏时，
 *     实际模型通过绑定通知引擎侧——adapter 据此定位该元件所属网络、请求重建
 *     （重建后该方块 BE 不存在 → 元件自然不再建模 → 等效删除此元件）。
 *   - 【元件被移除 → 实际模型】{@link #notifyCompositeRemoved()}：复合元件被
 *     移除出网络（设备被破坏/网络重建后不再含它）→ 通知实际模型清理
 *     （温度模型/虚拟快照/爆炸效果等）。
 * <p>
 * 绑定：构建时 {@code composite.bindModel(link)}（adapter 侧把实际模型包装成
 * 接收器传入）。反向（实际模型 → 复合元件）由 adapter 的注册表（pos → 绑定）
 * 在破坏时定位并回调 {@link CompositeElement#onModelDestroyed()}。
 */
public interface ModelLink {

    /** 实际模型被破坏 → 引擎侧应删除此元件并请求网络重建（由 adapter 定位网络）。 */
    default void notifyModelDestroyed() {}

    /** 元件被移除出网络（重建后不再存在）→ 实际模型清理（温度/虚拟/效果）。 */
    default void notifyCompositeRemoved() {}
}
