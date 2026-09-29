package com.hdf.cryptand.integratednetwork;

/**
 * 传输监听器（2026-08-26 集成网络核心）：核心结果回调。
 * <p>
 * 在【核心线程】上调用（普通模式虚拟线程 / 独占模式平台线程）。回调内只做
 * 事件收集 / 加锁入队（绝不做 MC 主线程调用）；平台层随后在主线程 pre-tick
 * 汇总并应用副作用（生成实体、写容器、渲染等）。这符合核心铁律：
 * 核心线程永不碰主线程对象。
 * <p>
 * <b>职责分离（2026-09 根因修复）</b>：两个回调语义不同，平台层不可混用——
 * <ul>
 *   <li>{@link #onFrame}：<b>普通流动帧</b>事件（到达/送达/丢弃），每个 TICK 帧
 *       都触发；平台层不据此读取执行表（否则同一张表会被反复投递）；</li>
 *   <li>{@link #onTransferSettled}：<b>传输结算</b>通知——仅在 {@code executeTransfer}
 *       产出新执行表时触发一次，平台层据此把表交给主线程执行 IO。</li>
 * </ul>
 */
public interface TransportListener {

    /**
     * 一帧仿真完成（核心线程调用；<b>普通 TICK 帧也会触发</b>）。
     *
     * @param key    传输网键
     * @param frame  帧结果（{@code frame.events} 本帧全部到达/送达/丢弃事件）
     */
    void onFrame(Object key, TransportResult frame);

    /**
     * 传输结算完成（核心线程调用；<b>仅 {@code executeTransfer} 产出执行表时触发一次</b>）。
     * 平台层在此把执行表投递给主线程执行 IO（P0-2 根因：此前平台层在
     * {@link #onFrame} 里读「最近执行表」→ 普通帧反复投递同一张表 → 同一批
     * 物品反复 take/写/回池、归属统计错乱）。
     *
     * @param key   传输网键
     * @param table 本次结算产出的执行表（主线程据此对容器直接 IO）
     */
    default void onTransferSettled(Object key, TransferExecutionTable table) {
    }
}