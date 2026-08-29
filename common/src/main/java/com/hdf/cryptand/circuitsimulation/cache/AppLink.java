package com.hdf.cryptand.circuitsimulation.cache;

/**
 * 应用对话接口（2026-08-22 用户架构：网络世界通过对话与具体对象交互）。
 * <p>
 * 表示一个【具体对象】的对话（EDA 前端 / MC 客户端）——由平台层实现本接口
 * （SPI），核心只定义对话协议：缓存就绪/变化/释放时回调应用，应用据此
 * 与其对象（EDA 画布 / MC 世界）同步。核心保持通用（不依赖任何平台）。
 * <p>
 * 通过 {@link NetworkWorld}（实例）绑定：实例 = 一个对话 + 一个缓存数据世界。
 */
public interface AppLink {

    /** 平台标识（"mc" / "eda" / 任意）——诊断/路由用 */
    String platform();

    /** 缓存数据已创建并绑定（对话建立）。实例创建后回调，应用可初始化其对象。 */
    default void onCacheReady(NetworkWorld world) {
    }

    /** 缓存数据结构变化（版本变化，版本去重由实例处理）。应用可据此同步其对象
     *  （如 MC 客户端图同步 / EDA 画布刷新 / 存档）。 */
    default void onCacheChanged(NetworkWorld world, long version) {
    }

    /** 缓存数据被释放（实例销毁）。应用清理其对象。 */
    default void onCacheDisposed(NetworkWorld world) {
    }
}