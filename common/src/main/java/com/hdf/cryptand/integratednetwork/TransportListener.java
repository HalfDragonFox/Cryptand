package com.hdf.cryptand.integratednetwork;

/**
 * 传输监听器（2026-08-26 集成网络核心）：核心每帧结果回调。
 * <p>
 * 在【核心线程】上调用（普通模式虚拟线程 / 独占模式平台线程）。回调内只做
 * 事件收集 / 加锁入队（绝不做 MC 主线程调用）；平台层随后在主线程 pre-tick
 * 汇总并应用副作用（生成实体、写容器、渲染等）。这符合核心铁律：
 * 核心线程永不碰主线程对象。
 */
public interface TransportListener {

    /**
     * 一帧仿真完成（核心线程调用）。
     *
     * @param key    传输网键
     * @param frame  帧结果（{@code frame.events} 本帧全部到达/送达/丢弃事件）
     */
    void onFrame(Object key, TransportResult frame);
}