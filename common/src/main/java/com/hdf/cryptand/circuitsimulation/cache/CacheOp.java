package com.hdf.cryptand.circuitsimulation.cache;

import com.hdf.cryptand.circuitsimulation.netop.NetOpKind;

/**
 * 缓存操作消息（2026-08-22 用户架构：外部 → 核心）。
 * <p>
 * 外部（MC 主线程 / EDA 前端）通过本消息向核心提交网络操作：
 * <ul>
 *   <li>{@link #cacheName()}：目标网络世界实例名（"mc-overworld" / "eda-scene-1"）——
 *       决定路由到哪个 {@link NetworkWorld}（网络数据世界）；</li>
 *   <li>{@link #networkKey()}：网络引用或名称（网络操作记录表主键，可为网络
 *       对象 / 网络 ID / 世界坐标等任意稳定对象）；</li>
 *   <li>{@link #kind()}：操作类型（DESTROY / SPLIT_MERGE / REBUILD / SOLVE）；</li>
 *   <li>{@link #data()}：附加数据（设备方块 / 导线 / 参数等，可 null）。</li>
 * </ul>
 * 由 {@link NetworkWorldManager#submit(CacheOp)} 路由到对应世界 →
 * {@link NetworkWorld#submit} → 异步交互管理类（核心）按网络键串行处理。
 */
public record CacheOp(String cacheName, Object networkKey, NetOpKind kind, Object data) {

    /** 便捷工厂 */
    public static CacheOp of(String cacheName, Object networkKey, NetOpKind kind, Object data) {
        return new CacheOp(cacheName, networkKey, kind, data);
    }

    /** 便捷工厂（无附加数据） */
    public static CacheOp of(String cacheName, Object networkKey, NetOpKind kind) {
        return new CacheOp(cacheName, networkKey, kind, null);
    }

    @Override
    public String toString() {
        return "CacheOp{" + cacheName + "/" + networkKey + " " + kind
                + (data != null ? " data=" + data : "") + "}";
    }
}
