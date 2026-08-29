package com.hdf.cryptand.circuitsimulation.netop;

/**
 * 网络操作类型（2026-08-16 用户架构：网络操作记录表 + 网表相关操作类）。
 * <p>
 * 优先级（高 → 低）：{@link #SPLIT_MERGE} &gt; {@link #REBUILD} &gt; {@link #SOLVE}。
 * 网表相关操作类按此优先级执行整合后的操作；重建时若检测到需要网络拆合，
 * 则重建完成后执行拆合操作。
 */
public enum NetOpKind {

    /** 网络内容破坏（设备方块拆除/导线剪切）：异步移除指定位置的端子/导线（最高优先） */
    DESTROY,

    /** 电气设备列表包增量更新（2026-08-23 用户协议：删除/变更/新增组装器列表；
     *  设备增量必须先于重建应用 → 执行顺序 destroy > DEVICE_DELTA > splitMerge >
     *  rebuild > solve） */
    DEVICE_DELTA,

    /** 网络拆分/合并等结构性操作（次优先） */
    SPLIT_MERGE,

    /** 网络重建（次优先） */
    REBUILD,

    /** 网络求解（最低优先） */
    SOLVE
}
