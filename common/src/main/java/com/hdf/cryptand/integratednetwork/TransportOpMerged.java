package com.hdf.cryptand.integratednetwork;

/**
 * 整合后的传输网络操作集（2026-08-26 集成网络核心，对应 netop 的 NetOpMerged）。
 * <p>
 * 传输操作类（{@link TransportOperation}）执行前对缓冲消息整合：
 *   - 破坏类操作整合为一条（{@link #destroy}，取最后一条 data）；
 *   - 拓扑变更整合为一条（{@link #topology}，取最后一条 data）；
 *   - 路由重算整合为一条（{@link #route}）；
 *   - 推进流动整合为一条（{@link #tick}，帧数 {@link #tickFrames}【累加】——
 *     主线程每个 tick 发一帧请求，批量到达后一次执行多帧，避免逐 tick 浪费）。
 */
public final class TransportOpMerged {

    /** 是否网络拆合（已整合为一条） */
    public boolean splitMerge;
    /** 是否拓扑破坏（已整合为一条） */
    public boolean destroy;
    /** 是否拓扑变更（已整合为一条） */
    public boolean topology;
    /** 是否变化上报（已整合为一条） */
    public boolean report;
    /** 是否重算路由（已整合为一条） */
    public boolean route;
    /** 是否传输请求（已整合为一条） */
    public boolean transfer;
    /** 是否推进流动（已整合为一条） */
    public boolean tick;

    /** 网络拆合附加数据（TransportSplitMerge） */
    public Object splitMergeData;
    /** 拓扑破坏附加数据（取最后一条 TransportChange 或 List） */
    public Object destroyData;
    /** 拓扑变更附加数据（取最后一条 TransportChange 或 List） */
    public Object topologyData;
    /** 变化上报附加数据（取最后一条 NetworkReport 或 List） */
    public Object reportData;
    /** 传输请求附加数据（TransportTransferRequest） */
    public Object transferData;
    /** 推进流动帧数（缓冲中多条 TICK 的帧数累加；至少 0） */
    public int tickFrames;

    /** 是否无任何待执行操作 */
    public boolean isEmpty() {
        return !splitMerge && !destroy && !topology && !report && !route && !transfer && !tick;
    }

    /** 是否包含某类操作 */
    public boolean has(TransportOpKind kind) {
        return switch (kind) {
            case SPLIT_MERGE -> splitMerge;
            case DESTROY -> destroy;
            case TOPOLOGY -> topology;
            case REPORT -> report;
            case ROUTE -> route;
            case TRANSFER -> transfer;
            case TICK -> tick;
        };
    }

    @Override
    public String toString() {
        return "TransportOpMerged{splitMerge=" + splitMerge + ", destroy=" + destroy
                + ", topology=" + topology + ", report=" + report + ", route=" + route
                + ", transfer=" + transfer + ", tick=" + tick + ", tickFrames=" + tickFrames + "}";
    }
}