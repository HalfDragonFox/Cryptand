package com.hdf.cryptand.neoforge.cryptandsable.api.message;

/**
 * CryptandSable 消息协议：主线程 ⇄ 核心 的双向增量命令类型。
 *
 * <p>主线程与物理核心之间完全通过消息列表（增量通知）通信，二者不共享可变物理状态。
 * 本枚举定义命令分类；具体负载见各消息 record。
 */
public enum SableCommandType {
    // ===== 上行：主线程 Server → 核心 =====
    /** 结构导入：把一块空间+方块结构注册为刚体/柔体。 */
    BODY_IMPORT,
    /** 结构移除。 */
    BODY_REMOVE,
    /** 心跳 + 推进预算（主线程控制每 tick 推几个物理步/ms）。 */
    HEARTBEAT,
    /** 玩家/世界交互命令（放置/破坏/推/施加力）。 */
    INTERACTION,
    /** 查询请求（姿态/质量/环境）。 */
    QUERY,

    // ===== 下行：核心 → 主线程 Server =====
    /** 位姿快照（核心→Server 只读镜像，增量下行）。 */
    POSE_SNAPSHOT,
    /** 破坏列表（结构被打破 → 交付给主线程应用）。 */
    DESTRUCTION_LIST,
    /** 物理结果（质量变化/质心/事件回调）。 */
    RESULT,
    /** 碰撞/特效/声音/粒子事件（核心→Server→Client 表现）。 */
    EFFECT_EVENT
}