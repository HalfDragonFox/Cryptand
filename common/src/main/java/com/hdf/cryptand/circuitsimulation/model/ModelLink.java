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

    /**
     * ===== 持久化信息接口（2026-09-15 用户）=====
     * <p>
     * 用户原话："保存时需要组装器记住绑定的数据的相关信息，可以给绑定接口添加
     * 信息接口，用于保存时返回必要信息。"
     * <p>
     * 分工：**组装器最清楚自己建了什么、要恢复什么** —— 它在创建本绑定（link）时
     * 就把该设备的必要数据写进来；保存时由编解码器通过
     * {@code composite.modelLink().persistInfo()} 原样取走，恢复时原样交回
     * {@link #restoreInfo(String)}，由组装器据此重建模型与运行时状态。
     * <p>
     * 这样 NetworkStructureCodec 就【不需要认识每一种复合模型】—— 它只存
     * "类名 + 展开元件 + 这段黑盒信息"，任何设备类型都能跨会话往返。
     * <p>
     * 约定（2026-09-15 用户："保存可以设置 Map 或者其他的类型的数组，然后保存时
     * 根据 Map 的名称 + 值做 KV 保存"）：
     *   - 返回 null / 空 Map = 无需额外信息（基础元件、纯被动元件）；
     *   - 编解码器按【名称 → 值】逐条做 KV 保存，**不做任何格式约定**，
     *     也就没有解析歧义；值的可用类型：
     *     {@code Boolean} / {@code Double}·{@code Float} / 其它 {@code Number}
     *     （按 long 存） / {@code CharSequence}（按字符串存） / {@code null}；
     *     其余类型按 {@code String.valueOf} 兜底为字符串；
     *   - 必须能跨会话稳定往返：不要放对象引用、内存地址、随机数。
     */
    default java.util.Map<String, Object> persistInfo() { return null; }

    /** 恢复时交回 {@link #persistInfo()} 当时保存的内容（默认忽略）。 */
    default void restoreInfo(java.util.Map<String, Object> info) { }
}
