package com.hdf.cryptand.integratednetwork;

/**
 * 传输网络操作执行器（2026-08-26 集成网络核心；平台层/内置实现）。
 * <p>
 * 传输操作类（{@link TransportOperation}）把整合后的操作交给本接口执行——
 * 核心类不依赖具体传输实现：内置的 {@link CoreTransportExecutor} 在纯虚拟图上
 * 跑通用物流/无线电流动；将来平台也可以实现本接口（如对接实际管道方块 / 无线电
 * 部件，把 MC 世界映射成虚拟图）。
 * <p>
 * 约定（对应 netop.NetOpExecutor）：
 *   - 本接口方法在分配器分配的线程 C 上调用（普通模式为虚拟线程）；
 *   - 同一传输网的操作已被传输操作类串行锁定（缓冲 + 记录表），实现方无需再加锁，
 *     但内部仍需保证线程安全（可能与其他传输网的操作并发执行）。
 */
public interface TransportExecutor {

    /**
     * 执行拓扑破坏（移除节点/边）。
     *
     * @param key  传输网键
     * @param data 单条 {@link TransportChange} 或 {@code List<TransportChange>}（仅认可 REMOVE_*）
     * @return true = 已处理
     */
    boolean executeDestroy(Object key, Object data);

    /**
     * 执行拓扑变更（新增/移除节点、边）。
     *
     * @param key  传输网键
     * @param data 单条 {@link TransportChange} 或 {@code List<TransportChange>}
     * @return true = 已处理
     */
    boolean executeTopology(Object key, Object data);

    /**
     * 执行路由重算（可选显式触发；拓扑脏时 TICK 会自动重算）。
     *
     * @param key  传输网键
     * @param data 附加数据（可 null）
     */
    void executeRoute(Object key, Object data);

    /**
     * 执行流动推进。
     *
     * @param key  传输网键
     * @param data Integer 帧数（null 视为 1）；可传入经合并累加的批量帧数
     */
    void executeTick(Object key, Object data);
}