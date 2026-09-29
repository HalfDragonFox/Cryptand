package com.hdf.cryptand.neoforge.cryptandsable.api.message;

import com.hdf.cryptand.neoforge.cryptandsable.api.physics.BodyParams;
import org.joml.Vector3dc;

/**
 * 消息负载记录（纯数据，不可变）。
 *
 * <p>所有消息均为不可变数据快照，方便 worker/主线程经队列传递而不共享对象状态。
 * 对齐 C12（批量发送：同 scene 多体操作打包）、C13（心跳携带推进预算）。
 */
public final class SableMessages {

    private SableMessages() {}

    /**
     * 心跳消息：主线程每 tick 发送，携带推进预算。
     *
     * <p>推进预算 = 本 tick 核心应推进多少个物理小步（substeps）。主线程按帧预算动态调节：
     * 卡顿时降步保帧、空闲时冲步补算。核心心跳接收器据此驱动核心时钟（C13 / C3）。
     */
    public record Heartbeat(
            long serverTick,
            int advanceSteps,        // 本 tick 推进步数（>0 推进，==0 只看不推）
            long budgetNanos         // 推进预算上限（毫秒/纳秒，0 = 不限）
    ) {}

    /**
     * 结构导入消息：把一块空间（网格密度 + 局部bounds）注册为一个物理体。
     *
     * <p>主线程只采集"该结构在哪、由什么方块构成"；质量/质心/惯量由核心计算（C5）。
     * voxelDensity 为按局部坐标展开的方块密度数组 int[]（每个 int 编码方块质量/碰撞贡献，
     * 语义可扩展：低 16 位碰撞体 id、高 16 位密度系数）。
     */
    public record BodyImport(
            int runtimeId,            // 主线程预分配或核心分配（见分配策略）
            int sceneId,              // 结构所属 scene（多结构独立 scene -> 可并行）
            double px, double py, double pz,   // 初始位置
            double qx, double qy, double qz, double qw, // 初始朝向
            int boundMinX, int boundMinY, int boundMinZ,
            int boundMaxX, int boundMaxY, int boundMaxZ,
            int[] voxelDensity,       // 局部块密度数据
            int typeFlags,            // 刚体/柔体/是否随动（预留：气室/升力）
            BodyParams params
            // 物理体参数（刚/柔统一，含粒子数/约束/刚度）；null=按 typeFlags 默认
    ) {}

    /** 结构移除消息。 */
    public record BodyRemove(
            int runtimeId,
            int sceneId
    ) {}

    /**
     * 交互消息：主线程玩家/世界动作转成核心命令。
     *
     * <p>例如：放置新方块（改结构质量）、破坏某块、对体施加冲量/力矩、局部推。
     */
    public record Interaction(
            int runtimeId,
            int sceneId,
            InteractionKind kind,
            double localX, double localY, double localZ,   // 局部坐标（质心系）
            double fx, double fy, double fz,               // 力/冲量分量
            Vector3dc extra                                // 扩展（可复用对象，不可变视）
    ) {}

    /** 交互种类。 */
    public enum InteractionKind {
        APPLY_FORCE,      // 施加力
        APPLY_IMPULSE,    // 施加冲量
        APPLY_TORQUE,     // 施加力矩
        BLOCK_SET,        // 放块改质量
        BLOCK_REMOVE,     // 破块改质量
        WAKE_UP           // 唤醒
    }

    /**
     * 位姿快照（下行）：核心 step 后产出的物理结果，供主线程写回只读镜像。
     */
    public record PoseSnapshot(
            int runtimeId,
            int sceneId,
            double px, double py, double pz,
            double qx, double qy, double qz, double qw,
            double vx, double vy, double vz,         // 线速度 [m/s]
            double wx, double wy, double wz          // 角速度 [rad/s]
    ) {}

    /**
     * 世界碰撞收集查询（worker→主线程，2026-09-01 异步收集循环 v2）。
     *
     * <p>物理体每次计算后向主线程发起：以结构【包围盒(bounds)】为基准、radius 半径内
     * 收集世界方块（静态碰撞体）——超长/超窄/多形态结构全覆盖（不止中心圆形）。
     * 主线程收集后经 OfficialRapierEngine addWorldChunk 上传并 markCollisionReady 解冻。
     *
     * @param runtimeId 物理体 runtime id
     * @param minX..maxZ 结构包围盒（世界格坐标；未知道时 cx/cz 为中心、min=max）
     * @param cx/cy/cz  结构当前世界位置（体位置；bounds 缺失时回退）
     * @param radius    收集半径（格；0=仅结构包围盒紧邻）
     * @param feature   操作特征（空间周期 opSeq；核心据此识别所属周期，过期消息丢弃）
     */
    public record WorldCollectQuery(
            int runtimeId,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            double cx, double cy, double cz,
            int radius,
            long feature
    ) {}

    /** 查询请求/响应。 */
    public record Query(
            int queryId,
            QueryKind kind,
            double[] result                 // 响应载荷（如质心、质量）
    ) {
        public enum QueryKind { CENTER_OF_MASS, MASS, POSE, ENV_AT, INVALID }
    }

    /**
     * ★ 2026-09-05 【空间级扫描请求（用户定案：按物理空间打包）】
     * 核心（空间任务）→ 主线程：把物理空间中【所有需要扫描的区域打包成一个请求】，
     * 替代逐成员的 WorldCollectQuery。前处理（SEND_QUERY）阶段构建本包发送；主线程
     * 对包内所有区域统一收集后，单条回传 SpaceScanResult（带同一特征）。
     *
     * <p>特征 = 空间 UUID 拷贝（feature）+ 直接时间记录（timestamp）：
     *  - feature：空间 UUID 拷贝（跨 tick 稳定），核心据此识别消息属于哪个空间周期
     *  - timestamp：请求包发出时刻（System.nanoTime），核心据此识别请求包新旧、
     *    防旧包迟到（时间戳不匹配 → 过期丢弃）。
     *
     * @param spaceId   物理空间 id（接收对象；必须在操作表中消息才有效）
     * @param feature   空间 UUID 拷贝（操作特征）
     * @param timestamp 请求包发出时刻（直接时间记录；nanoTime）
     * @param regions   该空间所有需要扫描的区域（每成员一个）
     */
    public record SpaceScanRequest(
            int spaceId,
            long feature,
            long timestamp,
            java.util.List<ScanRegion> regions
    ) {
        public int count() {
            return regions != null ? regions.size() : 0;
        }
    }

    /** 空间级扫描请求内的单个区域（对应一个成员/结构）。 */
    public record ScanRegion(
            int runtimeId,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            double cx, double cy, double cz,
            int radius
    ) {}

    /**
     * 区块激活查询（worker→主线程，2026-09-01）。
     *
     * <p>物理激活取决于"附近区块是否加载/强制加载"。worker 物理线程不能直接读
     * ServerLevel（线程隔离），故【每隔几次计算】把需要判定激活的物理体（runtimeId
     * + 当前世界位置）打包成查询上行；主线程应答（查询其所在区块是否 block-ticking
     * 范围/强制加载），回调激活集合决定体休眠/唤醒。
     *
     * <p>平行数组（x/y/z 与 runtimeIds 同序）避免每 tick 分配 record 列表。
     */
    public record ChunkActivationQuery(
            int[] runtimeIds,
            double[] xs, double[] ys, double[] zs
    ) {
        public int count() {
            return runtimeIds != null ? runtimeIds.length : 0;
        }
    }
}