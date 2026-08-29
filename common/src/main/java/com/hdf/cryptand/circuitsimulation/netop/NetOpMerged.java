package com.hdf.cryptand.circuitsimulation.netop;

/**
 * 整合后的网络操作集（2026-08-16 用户架构）。
 * <p>
 * 异步交互管理类发送网表相关操作到分配器前，对相关网络消息缓冲区的消息进行整合：
 *   - 网络拆分、合并等类似操作整合为一条（{@link #splitMerge}，取最后一条 data）；
 *   - 重建整合为一条（{@link #rebuild}）；
 *   - 求解整合为一条（{@link #solve}）。
 * 网表相关操作类按优先级执行：网络拆合 &gt; 重建 &gt; 求解。
 */
public final class NetOpMerged {

    /** 是否需要网络内容破坏（设备拆除/导线剪切，已整合为一条） */
    public boolean destroy;
    /** 是否需要电气设备列表包增量更新（已整合为一条） */
    public boolean deviceDelta;
    /** 是否需要网络拆分/合并（同类操作已整合为一条） */
    public boolean splitMerge;
    /** 是否需要网络重建（已整合为一条） */
    public boolean rebuild;
    /** 是否需要网络求解（已整合为一条） */
    public boolean solve;

    /** 破坏操作附加数据（取缓冲中最后一条：BlockPos 或 WireEdge 等） */
    public Object destroyData;
    /** 设备增量操作附加数据（取缓冲中最后一条：GridMessage.DevicePackage） */
    public Object deviceDeltaData;
    /** 拆合操作附加数据（取缓冲中最后一条） */
    public Object splitMergeData;
    /** 重建操作附加数据（取缓冲中最后一条） */
    public Object rebuildData;
    /** 求解操作附加数据（取缓冲中最后一条） */
    public Object solveData;

    /** 是否无任何待执行操作 */
    public boolean isEmpty() {
        return !destroy && !deviceDelta && !splitMerge && !rebuild && !solve;
    }

    /** 是否包含某类操作 */
    public boolean has(NetOpKind kind) {
        return switch (kind) {
            case DESTROY -> destroy;
            case DEVICE_DELTA -> deviceDelta;
            case SPLIT_MERGE -> splitMerge;
            case REBUILD -> rebuild;
            case SOLVE -> solve;
        };
    }

    @Override
    public String toString() {
        return "NetOpMerged{destroy=" + destroy
                + ", deviceDelta=" + deviceDelta
                + ", splitMerge=" + splitMerge
                + ", rebuild=" + rebuild + ", solve=" + solve + "}";
    }
}
