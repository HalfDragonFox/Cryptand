/**
 * ===== 官方 Rapier 引擎适配层（OfficialRapierEngine，2026-09-01） =====
 *
 * 用官方 sable_rapier 的 Rapier3D JNI（Rust Rapier 引擎）替代自研 SableSimulator
 * 作为物理核心。官方提供完整 6DOF 刚体（平移+旋转）、体素碰撞（含世界静态碰撞）、
 * 冲量/力/约束、碰撞事件——正是自研核心缺的（用户："物理不完整，只有上下，左右
 * 无法偏移，而且没有碰撞，没有实体"）。
 *
 * 【设计】不搬官方 PhysicsPipeline（深度耦合 ServerSubLevel/LevelAccelerator/
 * SubLevelPhysicsSystem）。本引擎：
 *  - 持有原生场景（long handle）
 *  - createBody(runtimeId, pose, sections) → createSubLevel + addChunk(global=false,id)
 *  - addWorldChunk(...) → addChunk(global=true)（静态世界方块）
 *  - step() → CryptandRapierNative.tick(scene,1/20)（预步）→ CryptandRapierNative.step(scene,1/20)
 *    （先 tick 后 step 是官方同款顺序；tick 主要触发宽阶段/更新，step 结算）
 *  - getPose → double[7]
 *  - applyForce/applyImpulse/wakeUp/teleport 门面
 *
 * 线程：供 SableWorker（异步 worker 线程）调用；所有 native 调用串行于 worker。
 * 主线程只经 PoseSnapshot 只读镜像（与自研核心一致，不直接访问 native）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.backend.official;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.core.material.BlockPhysicsProps;
import com.hdf.cryptand.neoforge.cryptandsable.core.material.BlockPhysicsTable;
import com.hdf.cryptand.neoforge.cryptandsable.server.WorldChunkUploader;

/**
 * 官方 Rapier 引擎适配层（单场景 多刚体）。
 */
public final class OfficialRapierEngine {

    /** 与官方一致：重力 9.81 向下；universalDrag 用官方默认（0.0 附近，后续调）。 */
    private static final double DEFAULT_UNIVERSAL_DRAG = 0.0;

    // ===== ★ 2026-09-02 远处物理基准（对齐原版 sable：物理在世界极远处模拟，主世界仅投影） =====
    //  仅平移 X/Z（Y 保持主世界高度语义）。取值 < 2^21（packKey 22 位 section 编码安全）
    //  且远超主世界真实区块范围 → 物理与主世界区块/坐标彻底解耦。
    //  native 入参（pose/chunk/teleport）+FAR；native 出参（getPose）-FAR。
    public static final long PHYS_FAR_XZ = 1_500_000L;
    public static final double PHYS_FAR_XZ_D = 1_500_000.0;

    /** ★ 2026-09-06 【无 far 恒等化】物理坐标 = 主世界坐标（物理化坐标直接入物理空间，
     *  零转换；保留签名兼容历史调用）。 */
    public static double farX(double wx) { return wx; }
    public static double farZ(double wz) { return wz; }
    public static int farSectionX(int sx) { return sx; }
    public static int farSectionZ(int sz) { return sz; }
    public static int farBlockX(int bx) { return bx; }
    public static int farBlockZ(int bz) { return bz; }
    public static double toMainWorldX(double fx) { return fx; }
    public static double toMainWorldZ(double fz) { return fz; }
    public static int toMainWorldBlockX(int fx) { return fx; }
    public static int toMainWorldBlockZ(int fz) { return fz; }
    public static int toMainWorldSectionX(int fx) { return fx; }
    public static int toMainWorldSectionZ(int fz) { return fz; }
    private static int farSX(int sx) { return farSectionX(sx); }
    private static int farSZ(int sz) { return farSectionZ(sz); }
    private static int farBX(int bx) { return farBlockX(bx); }
    private static int farBZ(int bz) { return farBlockZ(bz); }

    private OfficialRapierScene scene;

    /** ★ 2026-09-02 冻结修复门闸：start 后 false；stop dispose 后 true。
     *  step 等 native 触点在 disposed 后直接安全返回（不抛/不碰已释放 handle）。 */
    private volatile boolean disposed = true;

    /** ★ 2026-09-03 物理计算开关（/cryptand sable physics off）：
     *  false=冻结所有积分（结构保持位姿不动，仍发收集查询保持黄框/收集）。 */
    private volatile boolean physicsStepEnabled = true;

    /** ★ 2026-09-03 原生 shape 直接碰撞模式（structure/ground 都用主世界坐标 shape 体，
     *  无 far 换算；getPose/removeBody 按它分流）。start() 时从配置读取。 */
    private volatile boolean shapeCollision = false;

    /** ★ 2026-09-05 【凹形组合】轴合并开关（true=唯一适配 compound 轴合并大 box；
     *  false=走弃置旧体素管线，仅调试/回退）。start() 时读取。 */
    private volatile boolean mergeAxisAligned = true;

    /** ★ 2026-09-05 【凹形组合】每合并体单轴最大边长（格；<=0 不限）。start() 时读取。 */
    private volatile int mergeMaxBox = 32;

    /** ★ 2026-09-05 当前维度环境介质（主线程 LevelEvent.Load 解析 → 纯数据进核心；
     *  结构创建时写入 PhysicalizedData，供升力/浮力缩放）。 */
    private volatile double envMediumDensity = 1.225;
    private volatile double envGravityMag = 9.81;

    /** ★ 2026-09-05 主线程设置维度环境介质密度 [kg/m³] 与重力大小 |g| [m/s²]。 */
    public synchronized void setEnvironmentMedium(final double mediumDensity,
                                                  final double gravityMag) {
        this.envMediumDensity = mediumDensity;
        this.envGravityMag = gravityMag;
    }

    /** 物理计算是否开启（命令/诊断用）。 */
    public boolean isPhysicsStepEnabled() {
        return this.physicsStepEnabled;
    }

    /** 设置物理计算开关（/cryptand sable physics on/off）。 */
    public void setPhysicsStepEnabled(final boolean enable) {
        this.physicsStepEnabled = enable;
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] physics step {} by command",
                enable ? "ENABLED" : "DISABLED (freeze)");
    }

    /** ★ 默认实体方块 collider handle（注册于 start；写入 chunk <<16 = handle+1）。 */
    private int defaultColliderHandle = -1;

    // ===== ★ 2026-09-04 ECS 化：所有平行状态集合【收敛进 PhysicalizedData】，= =====
    //    引擎只遍历 dataList（物理化数据列表一次遍历），不再维护额外检测集合。

    /** 物理化数据列表（ECS：一次遍历 ALL；每个结构一个实例；含冻结/收集/陪体/附挂状态）。 */
    private final java.util.Map<Integer, PhysicalizedData> physicalized = new java.util.LinkedHashMap<>();

    /** ★ 2026-09-05 【核心数据类（ECS · 每世界一个）】封装结构化数据表（uuid → 结构）、
     *  物理空间表（uuid → 空间）、绿色碰撞全局表。ECS 计算（GreenSpaceComputer）只接收
     *  本对象纯计算。引擎 int 快表（physicalized/spaces）为 native 运行时，UUID 表为其
     *  ECS 权威视图（注册/注销随结构/空间创建删除同步）。 */
    private PhysicsWorldData worldData = null;

    /** ★ 2026-09-05 【每世界核心数据类表】世界隔离：LevelEvent.Load 绑定世界 →
     *  bindWorld 创建/复用该世界的 PhysicsWorldData。当前引擎单活绑定主世界。 */
    private final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            PhysicsWorldData> worldDataByLevel = new java.util.concurrent.ConcurrentHashMap<>();

    /** ★ 2026-09-04 global 兼容层专用：已挂载 global 的世界 static section。
     *  仅 addWorldChunk/removeWorldChunk/clearWorldSections 旧路径使用（shape 模式不往 global 挂）；
     *  不参与收集循环遍历（ECS 检测集合已在 PhysicalizedData）。 */
    private final java.util.Set<SectionKey> worldSections = new java.util.HashSet<>();

    /** ★ 2026-09-06 【异步 JNI 跟踪】已投递的异步 dispose/export 任务（stop 时 join 等待
     *  完成，防 DLL 卸载前未执行导致 UAF）。 */
    private final java.util.concurrent.CopyOnWriteArrayList<
            java.util.concurrent.CompletableFuture<Void>> pendingJniTasks =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 取某结构物理化数据（不存在 → 自动建）。 */
    public synchronized PhysicalizedData physicalizedData(final int runtimeId) {
        return physicalized.computeIfAbsent(runtimeId, PhysicalizedData::new);
    }

    // ===== ★ 2026-09-05 【多物理空间（用户定案 2026-09-05）】 =====
    //  每个物理化结构必须属于一个物理空间（space/桶）。space = 一个原点（主世界坐标）
    //  + 一组结构成员（同 space 局部坐标共用同一原点，f32 精度无忧）。
    //  BFS 分配：新结构加入 → 在其【局部世界坐标】（spacePose）BFS 扫描该 space 的
    //  全部成员（同一空间表内），距离 < BUCKET_RADIUS 的并入；被并入者再向外扫描；
    //  直到搜不到为止（连通分量）。未被任何已存在 space 找中的 → 【单开新 space】
    //  （原点=其主世界坐标，局部=0,0,0）。
    //  ⚠ BFS 只读各 PhysicalizedData（空间表），【不读 Level】——可在异步线程执行。
    //  【注】所有 space 共享一个物理场景（单 scene）；space 划分是坐标换基 + 成员集合，
    //  用于 f32 精度。空间成员 = 已物理化 active 结构集合。

    /** ★ 2026-09-07 【物理空间周期子阶段（用户定案状态机）】
     *  <pre>
     *   NORMAL（普通表）→ 进入操作表 → SEND_QUERY（发消息给主线程）
     *     → WAIT_REPLY（等待回复；扫描世界消息有效）
     *     → PRE_PROCESS（前处理：重建/陪体上传预备）
     *     → SEND_ENGINE（发送到引擎 step）→ WAIT_ENGINE（等待引擎；扫描更新无效）
     *     → POST_PROCESS（后处理：坐标转换/读位姿）
     *     → COMPLETE（周期完成 → 核心移回普通表 → NORMAL）
     *     → FAILED（超时失败 → 核心移回普通表 → NORMAL）
     *  </pre> */
    public enum SpaceCycleStage {
        NORMAL,        // 普通表（未操作）
        SEND_QUERY,    // 操作表：发消息给主线程（收集查询）
        WAIT_REPLY,    // 操作表：等待主线程回复（扫描世界消息有效）
        PRE_PROCESS,   // 操作表：前处理（重建/陪体上传预备）
        SEND_ENGINE,   // 操作表：发送到引擎（step）
        WAIT_ENGINE,   // 操作表：等待引擎（扫描更新无效）
        POST_PROCESS,  // 操作表：后处理（坐标转换/读位姿）
        COMPLETE,      // 操作表：周期完成（发完成消息 → 核心移回普通表）
        FAILED         // 操作表：超时失败（发失败请求 → 核心移回普通表）
    }

    /** ★ 物理空间（固定坐标原点 origin + 扫描中心 scanCenter + A×B×C 立体矩形 + 成员列表 + 独立 native 场景）。
     *  ★ 2026-09-05 【ECS】改包可见（非 private）：核心数据类（PhysicsWorldData.spaceTable）
     *  与 ECS 计算器（GreenSpaceComputer）按空间 uuid 访问。 */
    static final class PhysicalSpace {
        final int id;
        /** ★ 2026-09-07 【空间 UUID 身份（稳定特征）】物理空间唯一标识。消息路由
         *  （routeSpaceMessage）用它识别该消息属于哪个物理空间周期；跨 tick 稳定，
         *  不随合并/拆分/重建变化（用户："特征可以是物理空间的uuid拷贝"）。
         *  核心目的是保证同一时间只有一个线程操作该物理空间。 */
        final java.util.UUID uuid = java.util.UUID.randomUUID();
        /** ★ 2026-09-06 【固定坐标原点（0 点）】绑定 seed 初始位置，【永不漂移】。
         *  所有坐标换算（toLocalFor/toWorldFor/projectBatchPose/getPose/rebuild）都以它
         *  为基准——即使 seed（原点结构）移动/更换，其他静止结构的绝对坐标 = 本地+origin
         *  恒定不变。⚠ 绝对禁止随 seed 更新。 */
        final double[] origin = new double[3];
        /** ★ 2026-09-06 【扫描中心（随 seed 移动）】BFS 矩形判定（contains/扩展/合并）
         *  以它为矩形中心——矩形随 seed 移动，但【不影响坐标换算】。 */
        final double[] scanCenter = new double[3];
        final java.util.Set<Integer> members = new java.util.HashSet<>();

        /** ★ 2026-09-06 【原点物理化结构（seed）】空间的一定有一个 seed（原点结构）。
         *  空间矩形（scanCenter）随 seed 移动；seed=0 时未定（新空间自动选）。 */
        int seed = 0;

        /** ★ 2026-09-06 【矩形半宽 A/B/C（格）】空间覆盖的 A×B×C 立体矩形，以 seed
         *  为中心（即可扫描四方 ± halfX/Y/Z）。初始 = 8×8×8（2026-09-05 用户：1024→8）；
         *  BFS 发现边缘外成员 → 矩形扩展；成员超远 → 拆分。 */
        double halfX = 8.0;
        double halfY = 8.0;
        double halfZ = 8.0;

        /** ★ 2026-09-05 【完全分桶 · 加载计数】已加载的物理化结构数量（chunk 事件维护）。
         *  =0 → 空间整体未加载 → 跳过计算/更新（用户定案）。 */
        int loadedCount = 0;

        /** ★ 2026-09-05 【每空间独立 native 场景（用户：禁止任何物理空间共享）】
         *  该空间的独立 Rapier scene handle（0=未创建；阶段1 创建）。
         *  ⚠ 同一 scene 只能属于一个空间（禁止 handle 共享）。
         *  所有空间 native 调用（创建体/上传/step/getPoseBatch）都走此 handle。 */
        long sceneHandle = 0L;

        /** ★ 2026-09-05 该空间是否已被调度任务处理过（开始计算时置位；调试用）。 */
        boolean hasStepped = false;

        /** ★ 2026-09-05 该空间上次完成一轮四阶段的时间（调度队列统计/调试）。 */
        long lastPipelineNanos = 0L;

        /** ★ 2026-09-06 【合并/拆分重建标记】空间关系变化（成员增删/合并/拆分）→ 置位；
         *  空间任务阶段2 对该空间做【重建】（重挂成员/陪体）。类似电路仿真 rebuild。 */
        volatile boolean dirty = false;

        /** ★ 2026-09-06 【全量重建标记】空间关系变化（拆分/合并/迁移）→ 置位：
         *  阶段2 重建时【先清空该空间场景结构体（removeSubLevel）】再全部重挂
         *  （迁移后无残留）。普通 dirty（成员更新）不置此位。 */
        volatile boolean dirtyRebuild = false;

        /** ★ 2026-09-05 该空间是否在调度队列/处理中（防重复提交）。 */
        volatile boolean queued = false;

        /** ★ 2026-09-07 【周期子阶段】当前状态机阶段（NORMAL=普通表）。 */
        volatile SpaceCycleStage stage = SpaceCycleStage.NORMAL;

        /** ★ 2026-09-07 【操作特征 opSeq】每次进入操作表分配递增序号；
         *  所有发给主线程/回传核心的消息携带它，核心据此识别周期（旧周期消息丢弃）。 */
        volatile long opSeq = 0L;

        /** ★ 2026-09-07 【当前子阶段超时截止】超过 → 操作失败（FAILED）移回普通表。 */
        volatile long stageDeadlineNanos = 0L;

        /** ★ 2026-09-07 【WAIT_REPLY 是否已收到主线程回复】回复到达置位，等待线程据此推进。 */
        volatile boolean replyArrived = false;

        /** ★ 2026-09-05 【最近一次空间扫描回复时间戳（直接时间记录）】主线程回传携带；
         *  用于识别请求包新旧（迟到旧包丢弃）。0=尚未收到。 */
        volatile long lastScanReplyTs = 0L;

        /** ★ 2026-09-07 【周期任务是否正在运行】（区分"在操作表等待"与"任务执行中"）。
         *  用 AtomicBoolean CAS 保证【同一时间只有一个线程操作该物理空间】——无需加锁：
         *  只有 CAS 成功（false→true）的线程才提交空间任务；任务结束 CAS 回 false。
         *  ⚠ 不加锁：锁会引发死锁；单任务独占由 CAS 保证（消息机制即同步机制）。 */
        final java.util.concurrent.atomic.AtomicBoolean taskRunning =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        /** ★ 2026-09-07 【特征令牌】= 空间 UUID 拷贝（用户："特征可以是物理空间的uuid拷贝"）。
         *  所有发给主线程/回传核心的消息携带它；核心据此识别消息属于哪个物理空间周期。
         *  空间生命周期内恒定（不随合并/拆分/重建变化）。 */
        long feature() {
            return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        }

        /** ★ 2026-09-06 【BFS 扫描节流】上次从 seed 全量 BFS 重建矩形的 nanoTime
         *  （配置 SABLE_SPACE_BFS_INTERVAL_SEC；到期才重新扫——用户：不需要一直扫）。 */
        volatile long lastBfsNanos = 0L;

        /** 该成员是否在空间矩形内（以 scanCenter 为中心 ± half；origin 固定不影响判定）。 */
        boolean contains(final double x, final double y, final double z) {
            return Math.abs(x - scanCenter[0]) <= halfX
                    && Math.abs(y - scanCenter[1]) <= halfY
                    && Math.abs(z - scanCenter[2]) <= halfZ;
        }

        PhysicalSpace(final int id) {
            this.id = id;
        }

        /** ★ 2026-09-05 回溯：loadedCount 是否为 0（空间完全未加载）。 */
        boolean fullyUnloaded() {
            return loadedCount <= 0;
        }
    }

    /** 物理空间表：spaceId → Space（ConcurrentHashMap：无锁读 + 原子 put/remove）。 */
    private final java.util.Map<Integer, PhysicalSpace> spaces = new java.util.concurrent.ConcurrentHashMap<>();

    /** ★ 2026-09-07 【操作列表（每 tick 分配）】活跃空间从"普通表(spaces)"移动到"操作列表"。
     *  无锁并发队列（ConcurrentLinkedQueue）：主线程 enqueue / 任务线程 poll，无需互斥。
     *  ★ 用户："不加锁，消息机制本身就是同步机制；架构做好无需锁，锁会引发死锁"。 */
    private final java.util.Queue<Integer> spaceOpQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** ★ 2026-09-07 【操作表成员】当前处于"操作周期"的空间 id 集合（无锁 set）。
     *  消息路由据此判定"接收对象在操作列表中"才有效；空间在周期期间保留在此。 */
    private final java.util.Set<Integer> spaceTasksInflight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** ★ 2026-09-06 是否启用空间任务调度（默认 true；调试可关走旧 step()）。 */
    private volatile boolean spaceTaskEnabled = true;

    /** ★ 2026-09-06 【空间任务阶段4 位姿回调】空间任务完成一次 getPoseBatch 后触发
     *  （发布 PoseSnapshot / 位姿镜像；主线程注册）。可 null（不回调）。 */
    private java.util.function.Consumer<Integer> spacePoseCallback;

    /** 注册空间位姿回调（主线程；publishSpacePoses 用）。 */
    public void setSpacePoseCallback(final java.util.function.Consumer<Integer> cb) {
        this.spacePoseCallback = cb;
    }

    /** ★ 2026-09-06 【每空间收集查询池】收集查询按 spaceId 分组入队（空间任务阶段2 消费）。
     *  ⚠ 与旧全局 pendingCollectQueries 并存——空间任务路径用 per-space 池。 */
    private final java.util.Map<Integer, java.util.Map<Integer, Object>> spaceCollectQueries
            = new java.util.HashMap<>();

    /** 空间任务阶段2：取出该空间待消费的收集查询（消费后清空）。 */
    private java.util.List<Object> drainSpaceCollectQueries(final int spaceId) {
        final java.util.Map<Integer, Object> pool;
        synchronized (spaceCollectQueries) {
            pool = spaceCollectQueries.remove(spaceId);
            if (pool == null) return java.util.Collections.emptyList();
        }
        return new java.util.ArrayList<>(pool.values());
    }

    /** 主线程：收集查询按空间入队（覆盖消息池语义）。 */
    private void putSpaceCollectQuery(final int spaceId, final Object query) {
        if (spaceId <= 0 || query == null) return;
        synchronized (spaceCollectQueries) {
            spaceCollectQueries.computeIfAbsent(spaceId, k -> new java.util.HashMap<>())
                    .put(((SableMessages.WorldCollectQuery)
                            query).runtimeId(), query);
        }
    }

    /** 下一 space id（从 1 起；内部自建空间用 1..N）。 */
    private int nextSpaceId = 1;

    /** 当前"活跃"空间（最近一次 BFS 的种子空间；供 toLocal/toWorld 兼容——修复期逐步
     *  迁移调用点，最终每个结构用自己的 spaceId）。 */
    private transient int currentSpaceId = 0;

    /** 空间半径（结构中心距 < 该值 → 同空间；格）。默认 1024（结构间 BFS 联动范围）。
     *  ⚠ 2026-09-05：仅历史注释参考——实际并入走空间矩形（half 默认 8×8×8）+ Rust AABB。 */
    private static final double BUCKET_RADIUS = 1024.0;

    /** 是否启用分桶（f32 才启用；f64 可关——f64 大坐标无精度问题）。
     *  ★ 用户："精度不用混合精度，保证 f32 的性能，主要是需要完全分桶" → 恒开。 */
    private volatile boolean bucketEnabled = true;

    /** 设置桶开关（调试/与 f64 兼容；start() 读配置）。 */
    public void setBucketEnabled(final boolean en) {
        this.bucketEnabled = en;
    }

    /** 桶是否已建立（任何空间已分配过种子）。 */
    public boolean hasBucket() {
        return !spaces.isEmpty();
    }

    /** 桶原点（主世界 double[]；未建则 null）。⚠ 兼容旧接口：返回当前活跃空间原点。 */
    public double[] bucketOriginCopy() {
        if (spaces.isEmpty()) return null;
        final PhysicalSpace sp = spaces.get(currentSpaceId);
        if (sp == null) return null;
        return new double[]{sp.origin[0], sp.origin[1], sp.origin[2]};
    }

    /** ★ 2026-09-06 【对齐 sable】主世界坐标 → 局部坐标（= 世界 − 所属空间原点；sable
     *  plot.toLocal 等价；方块/扫描/陪体/质心都用局部小值）。旧恒等版废弃。 */
    private double toLocal(final double wx) {
        PhysicalSpace cur = spaces.get(currentSpaceId);
        if (cur == null) cur = spaces.isEmpty() ? null : spaces.values().iterator().next();
        return cur != null ? wx - cur.origin[0] : wx;
    }

    /** ★ 2026-09-06 【对齐 sable】局部坐标 → 主世界坐标（= 局部 + 所属空间原点；sable
     *  plot.toGlobal 等价；body pose 显示/投影回主世界用）。 */
    private double toWorld(final double lx) {
        PhysicalSpace cur = spaces.get(currentSpaceId);
        if (cur == null) cur = spaces.isEmpty() ? null : spaces.values().iterator().next();
        return cur != null ? lx + cur.origin[0] : lx;
    }

    /** ★ 按结构所属空间换基（用 structure.spaceId 的唯一正确路径）。
     *  ⚠ 2026-09-05 修复：origin 是 [3] 数组，必须按 axis 取 axis 分量——
     *  旧实现三轴都读 origin[0]（X 原点），导致 Y/Z 被错加 X 原点（1999997）
     *  → 还原位姿 Y≈+1999997 → 世界地形扫描错区 → 结构自由落体飞走。 */
    /** ★ 2026-09-06 【对齐 sable】主世界 → 局部（世界 − 所属空间原点；axis 按分量）。 */
    private double toLocalFor(final int runtimeId, final double wx, final int axis) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        final PhysicalSpace sp = d != null && d.spaceId != 0 ? spaces.get(d.spaceId) : null;
        if (sp == null) return wx;
        return wx - sp.origin[axis];
    }

    /** ★ 2026-09-06 【对齐 sable】局部 → 主世界（局部 + 所属空间原点；axis 按分量）。 */
    private double toWorldFor(final int runtimeId, final double lx, final int axis) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        final PhysicalSpace sp = d != null && d.spaceId != 0 ? spaces.get(d.spaceId) : null;
        if (sp == null) return lx;
        return lx + sp.origin[axis];
    }

    /** ★ 2026-09-05 【加载计数维护】主线程 chunk 事件：chunk 加载 → 空间内所有该 chunk
     *  成员结构计数 +1（空间 loadedCount 反映"已加载的物理化结构数量"）。 */
    public synchronized void onSpaceChunkLoaded(final int chunkX, final int chunkZ) {
        for (final PhysicalSpace sp : spaces.values()) {
            boolean touched = false;
            for (final int mem : sp.members) {
                final PhysicalizedData d = physicalized.get(mem);
                if (d == null) continue;
                if (((long) Math.floor(d.pose[0]) >> 4) == chunkX
                        && ((long) Math.floor(d.pose[2]) >> 4) == chunkZ) {
                    sp.loadedCount++;
                    touched = true;
                }
            }
            if (touched) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] SPACE load space={} chunk=({},{}) loadedCount={}",
                        sp.id, chunkX, chunkZ, sp.loadedCount);
            }
        }
    }

    /** ★ 2026-09-05 主线程 chunk 卸载：空间内该 chunk 成员结构计数 −1。 */
    public synchronized void onSpaceChunkUnloaded(final int chunkX, final int chunkZ) {
        for (final PhysicalSpace sp : spaces.values()) {
            boolean touched = false;
            for (final int mem : sp.members) {
                final PhysicalizedData d = physicalized.get(mem);
                if (d == null) continue;
                if (((long) Math.floor(d.pose[0]) >> 4) == chunkX
                        && ((long) Math.floor(d.pose[2]) >> 4) == chunkZ) {
                    if (sp.loadedCount > 0) sp.loadedCount--;
                    touched = true;
                }
            }
            if (touched) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] SPACE unload space={} chunk=({},{}) loadedCount={}",
                        sp.id, chunkX, chunkZ, sp.loadedCount);
            }
        }
    }

    /** ★ 2026-09-05 空间是否完全未加载（成员所在 chunk 列无一个已加载）→ 更新跳过。 */
    private boolean isSpaceFullyUnloaded(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        return sp != null && sp.fullyUnloaded();
    }

    /** ★ 2026-09-05 空间是否完全未加载（按结构所属空间）。 */
    public synchronized boolean isStructureSpaceFullyUnloaded(final int runtimeId) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        if (d == null) return false;
        return isSpaceFullyUnloaded(d.spaceId);
    }

    /** ★ 2026-09-05 空间内成员数（调试）。 */
    public synchronized int spaceMemberCount(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        return sp == null ? 0 : sp.members.size();
    }

    /** ★ 2026-09-05 遍历某空间成员（供空间级批量计算；只读关系表）。 */
    public synchronized java.util.List<Integer> spaceMembers(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null) return java.util.Collections.emptyList();
        return new java.util.ArrayList<>(sp.members);
    }

    /** ★ 2026-09-06 【OBJ 导出（每空间一个）】该空间内全部结构 + 陪体 ids → Rust exportObj
     *  写入 OBJ 文件（world 坐标；可视化验证碰撞模型是否正确建模）。
     *  ★ 2026-09-06 【每空间独立场景】导出从该空间场景拉取。 */
    public synchronized void exportSpaceObj(final int spaceId, final String path) {
        final PhysicalSpace spE = spaces.get(spaceId);
        final long scene = spE != null && spE.sceneHandle != 0L ? spE.sceneHandle : requireScene();
        final java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (final int mem : spaceMembers(spaceId)) {
            ids.add(mem);   // 结构 dynamic
            final PhysicalizedData d = physicalized.get(mem);
            final int vid = d != null ? d.shapeCompanionId : 0;
            if (vid != 0) ids.add(vid);   // 该结构陪体 fixed
        }
        if (ids.isEmpty()) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] exportSpaceObj space={} no members -> skipped", spaceId);
            return;
        }
        final int[] idArr = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) idArr[i] = ids.get(i);
        // ★ 2026-09-06 【铁律：主线程只做 Level 交互，JNI 全异步】exportObj 投递到
        //   分配器普通线程执行（导出是重 JNI，主线程命令不阻塞）。
        try {
            com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                    .submitGeneric(
                            () -> {
                                try {
                                    CryptandRapierNative.exportObj(scene, idArr, path);
                                } catch (final Throwable t) {
                                    CryptandNeoForge.WAF_LOGGER.warn(
                                            "[CryptandSable] exportSpaceObj async failed: {}",
                                            t.toString());
                                }
                            },
                            com.hdf.cryptand.circuitsimulation.compute.TaskMode.NORMAL);
        } catch (final Throwable ignored) {
        }
    }

    /** ★ 2026-09-06 【OBJ 导出（全部空间）】遍历所有空间各导出一个 OBJ（run/obj_export/*.obj）。 */
    public synchronized void exportAllSpaceObj(final java.io.File dir) {
        if (!dir.exists()) dir.mkdirs();
        for (final Integer spaceId : spaces.keySet()) {
            final String path = new java.io.File(dir, "space_" + spaceId + ".obj").getAbsolutePath();
            exportSpaceObj(spaceId, path);
        }
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] exportAllSpaceObj done -> {}", dir.getAbsolutePath());
    }

    /** ★ 2026-09-05 某结构所属空间 id（0=未分配；持久化用）。 */
    public synchronized int spaceIdForRuntime(final int runtimeId) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        return d != null ? d.spaceId : 0;
    }

    // ===== ★ 2026-09-05 【每空间独立 native 场景（用户：禁止任何物理空间共享）】 =====
    //  每个空间 = 一个独立 createSubLevel/step/getPoseBatch 的 native scene handle。
    //  空间 handle = 创建空间时 initialize()；销毁 = 空间成员清空/停止时 dispose()。
    //  与旧的单场景 this.scene 兼容：新空间场景创建后 handle 独立；

    /** ★ 【阶段1】创建该空间的独立 native 场景（若已创建则复用）；返回 handle。 */
    public synchronized long spaceSceneHandle(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null) return 0L;
        if (sp.sceneHandle != 0L) return sp.sceneHandle;
        try {
            // 每个空间独立场景（重力一致）；场景参数从默认模板复制
            final long h = CryptandRapierNative.initialize(0.0, -9.81, 0.0, DEFAULT_UNIVERSAL_DRAG);
            // ★ 场景的默认体素碰撞器（每个空间独立注册——Rust voxel_collider_map
            //   是全局字典（只读），但 collider 实例属于各 scene，注册全场景安全）。
            final int ch = CryptandRapierNative.newVoxelCollider(0.6, 1.0, 0.1, false, null);
            CryptandRapierNative.addVoxelColliderBox(ch, new double[]{0.0, 0.0, 0.0, 1.0, 1.0, 1.0});
            sp.sceneHandle = h;
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] SPACE scene created id={} handle={} (per-space native)",
                    spaceId, h);
            return h;
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] SPACE scene create failed id={}: {}", spaceId, t.toString());
            return 0L;
        }
    }

    /** ★ 【阶段1】空间是否已有 native 场景缓存（true=复用）。 */
    public synchronized boolean hasSpaceScene(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        return sp != null && sp.sceneHandle != 0L;
    }

    /** ★ 空间 native 场景 handle（未创建 = 0；调用方需先 spaceSceneHandle）。 */
    public synchronized long spaceSceneAt(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        return sp != null ? sp.sceneHandle : 0L;
    }

    /** ★ 【阶段1/清理】销毁空间独立场景（成员清空/停止时；幂等）。 */
    public synchronized void destroySpaceScene(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null || sp.sceneHandle == 0L) return;
        final long h = sp.sceneHandle;
        sp.sceneHandle = 0L;
        // ★ 2026-09-06 【铁律：主线程只做 Level 交互，JNI 全异步】dispose 投递到
        //   分配器普通线程执行（主线程只清 Java 状态）；避免与空间任务并发 dispose
        //   同一 native scene（Rust dispose 幂等）。future 记入 pendingJniTasks，
        //   stop() 时 join 等待完成（防 DLL 卸载前未执行）。
        try {
            final java.util.concurrent.CompletableFuture<Void> f =
                    com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                            .submitGeneric(
                                    () -> {
                                        try {
                                            CryptandRapierNative.dispose(h);
                                        } catch (final Throwable ignored) {
                                        }
                                    },
                                    com.hdf.cryptand.circuitsimulation.compute.TaskMode.NORMAL);
            pendingJniTasks.add(f);
            f.whenComplete((v, t) -> pendingJniTasks.remove(f));
        } catch (final Throwable ignored) {
        }
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] SPACE scene disposed id={} (per-space native, async)", spaceId);
    }

    /** ★ 【每空间】该空间是否含任何已物理化（active）成员（阶段3 前判断）。 */
    public synchronized boolean spaceHasActiveMembers(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null) return false;
        for (final int mem : sp.members) {
            final PhysicalizedData d = physicalized.get(mem);
            if (d != null && d.active) return true;
        }
        return false;
    }

    /** ★ 2026-09-06 【防穿透】该空间是否存在【冻结成员】（pending/awaitFirst = 等待首次
     *  收集/陪体；collect 完成前不计算）。冻结期 step 会导致结构在无地面陪体时自由落体穿透。
     *  ★ 2026-09-06 【竞态修复：不依赖 active】createStructureShapeBody 的 createSubLevel
     *  在方法开头（body 已注册 native），而 data.active=true 在方法末尾——若此处要求
     *  d.active，空间管道在【body 已建、active 未置】窗口内检查会跳过该 pending 结构 →
     *  误判空间无冻结 → step 积分新 body → 结构自由落体（穿透/飞走）。改为：pending/
     *  awaitFirst 无论 active 与否一律算冻结（removeBody 会清 pending → 无永久冻结风险）。 */
    public synchronized boolean spaceHasFrozenMembers(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null || sp.members.isEmpty()) return false;
        for (final int mem : sp.members) {
            final PhysicalizedData d = physicalized.get(mem);
            if (d == null) continue;
            if (d.pending || d.awaitFirst) return true;
        }
        return false;
    }

    /** ★ 2026-09-05 某空间原点（主世界 double[]；不存在则 null；持久化用）。 */
    public synchronized double[] spaceOriginCopy(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null) return null;
        return new double[]{sp.origin[0], sp.origin[1], sp.origin[2]};
    }

    /** ---- 弃置区（2026-09-05 一世界一空间：黄框显示放弃置区；无调用方，保留）----
     * ★ 2026-09-05 【物理空间范围（黄框调试）】空间框 = 全部成员【绿色扫描区】的并集
     *  {minX,minY,minZ,maxX,maxY,maxZ}（世界坐标）；无成员/未分桶 → null。
     *  单结构时 = 该结构绿框（黄框=绿框）；多结构合并时 = 各成员绿框并集。线程安全。 */
    public synchronized double[] spaceRectFor(final int runtimeId) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        final PhysicalSpace sp = d != null && d.spaceId != 0 ? spaces.get(d.spaceId) : null;
        if (sp == null || sp.members.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        // 快照拷贝：与该空间 worker 并发改 members 时避免 CME
        for (final int mem : new java.util.ArrayList<>(sp.members)) {
            final PhysicalizedData dm = physicalized.get(mem);
            if (dm == null || !dm.active) continue;
            final int[] gb = greenBoxOf(dm);
            if (gb == null) continue;
            if (gb[0] < minX) minX = gb[0];
            if (gb[1] < minY) minY = gb[1];
            if (gb[2] < minZ) minZ = gb[2];
            if (gb[3] > maxX) maxX = gb[3];
            if (gb[4] > maxY) maxY = gb[4];
            if (gb[5] > maxZ) maxZ = gb[5];
        }
        if (minX == Integer.MAX_VALUE) return null;
        return new double[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** ★ 2026-09-05 【关系表恢复（一世界一空间）】世界加载时恢复空间关系：忽略存档
     *  spaceId（历史多空间残留），全部结构归入【世界唯一物理空间】；无空间 → 创建
     *  （原点=保存值）。纯记录；不读 chunk/不触碰 Level。 */
    public synchronized void restoreSpaceRelation(final int runtimeId, final int spaceId,
                                                  final double ox, final double oy, final double oz) {
        if (!bucketEnabled || runtimeId <= 0) return;
        final PhysicalizedData d = physicalized.get(runtimeId);
        if (d == null) return;
        PhysicalSpace sp = spaces.isEmpty() ? null : spaces.values().iterator().next();
        if (sp == null) {
            sp = new PhysicalSpace(1);
            if (nextSpaceId <= 1) nextSpaceId = 2;
            // ★ 2026-09-06 【sable 同构·整数 plot 原点】origin = 整数块网格基准
            //   （sable plotPos），【不是质心】。用 floor(质心) 保证与块局部化基准一致：
            //   块局部 = 主世界块 − origin(整)；body 原点 = origin(整) + 局部质心 → 主世界。
            sp.origin[0] = Math.floor(ox);
            sp.origin[1] = Math.floor(oy);
            sp.origin[2] = Math.floor(oz);
            sp.scanCenter[0] = Math.floor(ox);
            sp.scanCenter[1] = Math.floor(oy);
            sp.scanCenter[2] = Math.floor(oz);
            // ★ 2026-09-05 恢复的空间初始 loadedCount≥1（已恢复 ⇒ chunk 加载中；防 =0 卡死）
            if (sp.loadedCount <= 0) {
                sp.loadedCount = 1;
            }
            spaces.put(sp.id, sp);
            if (this.worldData != null) {
                this.worldData.spaceTable.put(sp.uuid, sp);
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] SPACE RESTORED id={} origin=({},{},{}) seed rt={} loadedCount={}",
                    sp.id, ox, oy, oz, runtimeId, sp.loadedCount);
        }
        sp.members.add(runtimeId);
        d.spaceId = sp.id;
        currentSpaceId = sp.id;
        // ★ 2026-09-06 恢复时：空间 seed（若未定）设为该结构（原点结构）
        if (sp.seed == 0) sp.seed = runtimeId;
        sp.dirty = true;
    }
    /** ★ 2026-09-05 【一世界一物理空间（用户定案 2026-09-05）】
     *  整个世界只有一个物理空间：新结构加入该唯一空间（无则创建，seed=首个结构、
     *  origin=其质心，坐标换算基准固定）。不再动态单开/合并/拆分空间
     *  （黄框显示与合并拆分代码已放弃置区）。 */
    public synchronized int assignSpace(final int runtimeId) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        if (d == null) return 0;
        if (!bucketEnabled) {
            d.spaceId = 0;
            return 0;
        }
        if (d.spaceId != 0 && spaces.containsKey(d.spaceId)) {
            return d.spaceId;   // 已分配
        }
        PhysicalSpace sp = spaces.isEmpty() ? null : spaces.values().iterator().next();
        if (sp == null) {
            return createSpaceFor(d);   // 首个结构 → 创建世界唯一空间
        }
        sp.members.add(d.runtimeId);
        d.spaceId = sp.id;
        sp.dirty = true;
        sp.dirtyRebuild = true;   // 新成员 → 空间重建挂载
        return sp.id;
    }

    /** 创建【世界唯一物理空间】（seed=首个结构；origin=scanCenter=其质心）。
     *  ★ 2026-09-06 【sable 同构·整数 plot 原点】origin = 整数块网格基准（sable plotPos），
     *  不是质心：floor(seed 质心)。块局部 = 主世界块 − origin(整)；body 原点 = origin(整)
     *  + 局部质心 → 合成主世界（零点五偏差消除）。 */
    private int createSpaceFor(final PhysicalizedData d) {
        final int sid = nextSpaceId++;
        final PhysicalSpace sp = new PhysicalSpace(sid);
        sp.origin[0] = Math.floor(d.pose[0]);
        sp.origin[1] = Math.floor(d.pose[1]);
        sp.origin[2] = Math.floor(d.pose[2]);
        sp.scanCenter[0] = Math.floor(d.pose[0]);
        sp.scanCenter[1] = Math.floor(d.pose[1]);
        sp.scanCenter[2] = Math.floor(d.pose[2]);
        sp.seed = d.runtimeId;
        sp.members.add(d.runtimeId);
        if (sp.loadedCount <= 0) sp.loadedCount = 1;
        spaces.put(sid, sp);
        // ★ 2026-09-05 【ECS】注册空间到核心数据类物理空间表（uuid 索引）
        if (this.worldData != null) {
            this.worldData.spaceTable.put(sp.uuid, sp);
        }
        d.spaceId = sid;
        currentSpaceId = sid;
        sp.dirty = true;
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] SPACE NEW id={} uuid={} origin=({},{},{}) seed rt={} (own space, total={})",
                sid, sp.uuid, sp.origin[0], sp.origin[1], sp.origin[2], d.runtimeId, spaces.size());
        return sid;
    }

    /** ---- 弃置区（2026-09-05 一世界一空间：合并拆分/黄框放弃置区；无调用方，保留）----
     * ★ 2026-09-05 【绿色扫描区（物理结构扫描区域）】结构当前世界 AABB =
     *  当前位姿（d.pose，readSpacePoses 每轮更新）± (自身包围盒半尺寸 + radius)。
     *  跟随结构移动；与红框（世界扫描区）同形（结构边缘外扩 radius）。
     *  @return {minX,minY,minZ,maxX,maxY,maxZ}；bounds 未就绪 → null */
    private int[] greenBoxOf(final PhysicalizedData d) {
        final int[] lb = d.localBounds;
        if (lb == null || lb.length < 6 || lb[3] < lb[0]) return null;
        final int r = ConfigCryptandSable.SABLE_WORLD_COLLISION_RADIUS.get();
        final double hx = (lb[3] - lb[0] + 1) * 0.5 + r;
        final double hy = (lb[4] - lb[1] + 1) * 0.5 + r;
        final double hz = (lb[5] - lb[2] + 1) * 0.5 + r;
        return new int[]{
                (int) Math.floor(d.pose[0] - hx), (int) Math.floor(d.pose[1] - hy),
                (int) Math.floor(d.pose[2] - hz),
                (int) Math.floor(d.pose[0] + hx), (int) Math.floor(d.pose[1] + hy),
                (int) Math.floor(d.pose[2] + hz)};
    }

    /** ---- 弃置区（2026-09-05 一世界一空间：合并拆分放弃置区；无调用方，保留）----
     * ★ 2026-09-05 【ECS · 提交单结构绿框足迹到全局表】结构计算完成后调用
     *  （readSpacePoses 每轮步进后）。足迹 = 当前位姿 ± (半尺寸+radius)。 */
    private void commitGreenFootprint(final PhysicalizedData d) {
        if (this.worldData == null || d == null || !d.active) return;
        final int[] gb = greenBoxOf(d);
        if (gb == null) return;
        final java.util.UUID spaceUuid = d.spaceId != 0 && spaces.containsKey(d.spaceId)
                ? spaces.get(d.spaceId).uuid : null;
        this.worldData.greenTable.commit(d.uuid, spaceUuid,
                gb[0], gb[1], gb[2], gb[3], gb[4], gb[5]);
    }

    /** ---- 弃置区（2026-09-05 一世界一空间：合并拆分放弃置区；无调用方，保留）----
     * ★ 2026-09-05 【ECS · 全量提交足迹（每 tick 后处理兜底）】rebalanceSpaces 前调用，
     *  确保全局表覆盖全部活动结构（补漏计算完成提交）。 */
    private void commitAllGreenFootprints() {
        if (this.worldData == null) return;
        for (final PhysicalizedData d : physicalized.values()) {
            commitGreenFootprint(d);
        }
    }

    /** ---- 弃置区（2026-09-05 ECS 化：并查集聚类已移入 GreenSpaceComputer）---- */
    /** 两轴对齐盒（int[6] minX..maxX）是否相交/相接。 */
    private static boolean boxesOverlap(final int[] a, final int[] b) {
        return a != null && b != null
                && a[0] <= b[3] && a[3] >= b[0]
                && a[1] <= b[4] && a[4] >= b[1]
                && a[2] <= b[5] && a[5] >= b[2];
    }

    private static int findRoot(final int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static void unionRoots(final int[] p, int a, int b) {
        final int ra = findRoot(p, a), rb = findRoot(p, b);
        if (ra != rb) p[ra] = rb;
    }

    // ===== ★ 2026-09-06 【空间合并/拆分再平衡（类似电路仿真 rebuild）】 =====
    //  每 tick 判定：结构移动（pose 变化）可能导致两空间成员互相靠近（应合并）、
    //  或一空间成员远离（应拆分到另一空间/单开）。纯 Java 数据判定（读 pose/spacePose），
    //  不改 native——只更新关系表 + 置 dirty → 空间任务阶段2 重建。

    /** ★ 2026-09-05 【分桶并发守卫（用户：分桶遍历所有空间，正在计算中的怎么办）】
     *  空间是否【正在被空间任务计算】——若是，分桶【必须跳过】该空间，不能改其
     *  members/spaceId（否则与 compute 线程读 HashSet 并发 → 崩溃/数据不一致，且
     *  迁移正在 step 的空间成员 → native 脱节）。
     *  判定用已有无锁状态：taskRunning（任务执行中）/ spaceTasksInflight（操作表）/
     *  stage≠NORMAL（周期进行中）。回普通表后下一 tick 再参与合并/拆分/加入。
     *  ⚠ 符合架构：不加锁，操作表空间由空间任务 CAS 独占，分桶只碰普通表空间。 */
    private boolean spaceInOperation(final PhysicalSpace sp) {
        if (sp == null) return true;
        if (sp.taskRunning.get()) return true;
        if (spaceTasksInflight.contains(sp.id)) return true;
        return sp.stage != SpaceCycleStage.NORMAL;
    }

    /** ★ 2026-09-06 【矩形模型 · 空间再平衡】
     *  合并 = 两空间矩形（以各自 seed 为中心 ± half）相交/相接 → 合并（成员归大者）。
     *  拆分 = 成员离空间 seed 超过 SABLE_SPACE_SPLIT_DIST（宽限值）→ 单开新空间；
     *  超扫描步长但未超宽限 → 保留原空间计算（用户：允许超扫描距离但加入计算）。
     *  原点结构（seed）移动 → seed 就是空间 origin（rebuildSpaceRect 同步）。
     *  纯 Java 数据判定；不触碰 Level/native。@return 变化（合并/拆分）次数
     *
     *  ★ 2026-09-05 【空间检测 v2（用户定案）：绿框重叠聚类】全量重聚类：
     *  每个结构有【绿色扫描区】（结构边缘外扩 radius）；绿框 AABB 重叠 → 同空间
     *  （合并），不重叠（分开）→ 拆分。union-find 分连通分量 → 每分量一个物理空间，
     *  种子 = 遍历表第一个模型（组内插入序首个；其已有空间被复用，否则新开）。
     *  ★ 2026-09-05 【弃置区（一世界一空间：合并拆分放弃置区）】不再被调用
     *   （CryptandSable 已移除调用点）；保留代码备查。 */
    public synchronized int rebalanceSpaces() {
        if (!bucketEnabled) return 0;
        if (this.worldData == null) return 0;
        int changes = 0;
        // ★ 2026-09-05 【ECS】提交全部结构绿框足迹到全局表（每 tick 后处理；结构计算完成后
        //   readSpacePoses 已提交，此处全量兜底覆盖）
        commitAllGreenFootprints();
        // ★ 2026-09-05 【ECS 纯计算】绿框重叠聚类 → 簇（每簇 = 应共享同一物理空间的结构的
        //   uuid 列表；GreenSpaceComputer 只读核心数据类，不碰 native/MC）
        final java.util.List<GreenSpaceComputer.Cluster> clusters =
                GreenSpaceComputer.cluster(this.worldData);
        if (clusters.isEmpty()) return 0;
        // 引擎应用侧：遍历表 = physicalized（LinkedHashMap 插入序）；跳过正在计算空间的成员
        final java.util.Map<java.util.UUID, PhysicalizedData> activeByUuid =
                new java.util.LinkedHashMap<>();
        for (final PhysicalizedData d : physicalized.values()) {
            if (d == null || !d.active) continue;
            if (d.spaceId != 0) {
                final PhysicalSpace sp = spaces.get(d.spaceId);
                // sp==null（陈旧 spaceId，空间已回收）→ 不跳过，参与聚类重新分配
                if (sp != null && spaceInOperation(sp)) continue;
            }
            activeByUuid.put(d.uuid, d);
        }
        for (final GreenSpaceComputer.Cluster cluster : clusters) {
            // 簇内成员（uuid 回查 runtimeId 快表）；种子 = 簇内遍历序第一个
            final java.util.List<PhysicalizedData> members = new java.util.ArrayList<>();
            for (final java.util.UUID su : cluster.structureUuids()) {
                final PhysicalizedData d = activeByUuid.get(su);
                if (d != null) members.add(d);
            }
            if (members.isEmpty()) continue;
            // 目标空间：优先复用种子（第一个）已有空间；否则新开
            int targetId = 0;
            for (final PhysicalizedData d : members) {
                if (d.spaceId != 0 && spaces.containsKey(d.spaceId)) {
                    targetId = d.spaceId;
                    break;
                }
            }
            final PhysicalSpace target;
            if (targetId == 0) {
                // 新开空间（返回 space id）→ 取回空间对象
                targetId = createSpaceFor(members.get(0));
                target = spaces.get(targetId);
                changes++;
            } else {
                target = spaces.get(targetId);
            }
            if (target == null) continue;   // 防御：空间不存在
            // 组内成员全部并入 target（合并）
            for (final PhysicalizedData d : members) {
                if (d.spaceId == targetId) continue;
                final PhysicalSpace old = d.spaceId != 0 ? spaces.get(d.spaceId) : null;
                if (old != null && old != target) old.members.remove(d.runtimeId);
                d.spaceId = targetId;
                target.members.add(d.runtimeId);
                target.dirty = true;
                target.dirtyRebuild = true;
                changes++;
            }
        }
        // 清理：spaceId 与所属空间不匹配的成员移出（拆分语义：绿框不重叠 → 分量分离）；
        // 空空间回收销毁场景 + 注销核心数据类物理空间表
        for (final PhysicalSpace sp : new java.util.ArrayList<>(spaces.values())) {
            for (final int mem : new java.util.ArrayList<>(sp.members)) {
                final PhysicalizedData d = physicalized.get(mem);
                if (d == null || d.spaceId != sp.id) {
                    sp.members.remove(mem);
                    sp.dirty = true;
                    sp.dirtyRebuild = true;
                    changes++;
                }
            }
            if (sp.members.isEmpty()) {
                spaces.remove(sp.id);
                if (this.worldData != null) {
                    this.worldData.spaceTable.remove(sp.uuid);
                }
                destroySpaceScene(sp.id);
                changes++;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] SPACE CLEANUP {} (empty, ECS clustering)", sp.id);
            }
        }
        return changes;
    }

    /** ---- 弃置区（2026-09-05 空间检测 v2 绿框聚类取代；无调用方，保留）----
     * 两空间矩形是否相交/相接（以各自【扫描中心 scanCenter】为中心 ± half；AABB 相交判定）。
     *  ⚠ 旧判定（2026-09-04 起合并优先走 Rust 物理碰撞，本方法仅场景未建/查询失败时回退）。 */
    private boolean rectsOverlap(final PhysicalSpace a, final PhysicalSpace b) {
        if (a.members.isEmpty() || b.members.isEmpty()) return false;
        final double aMinX = a.scanCenter[0] - a.halfX, aMaxX = a.scanCenter[0] + a.halfX;
        final double aMinY = a.scanCenter[1] - a.halfY, aMaxY = a.scanCenter[1] + a.halfY;
        final double aMinZ = a.scanCenter[2] - a.halfZ, aMaxZ = a.scanCenter[2] + a.halfZ;
        final double bMinX = b.scanCenter[0] - b.halfX, bMaxX = b.scanCenter[0] + b.halfX;
        final double bMinY = b.scanCenter[1] - b.halfY, bMaxY = b.scanCenter[1] + b.halfY;
        final double bMinZ = b.scanCenter[2] - b.halfZ, bMaxZ = b.scanCenter[2] + b.halfZ;
        return aMinX <= bMaxX && aMaxX >= bMinX
                && aMinY <= bMaxY && aMaxY >= bMinY
                && aMinZ <= bMaxZ && aMaxZ >= bMinZ;
    }

    /** ---- 弃置区（2026-09-05 空间检测 v2 绿框聚类取代；无调用方，保留）----
     * ★ 2026-09-04 【走 Rust 物理碰撞检测空间重叠（用户定案）】两空间是否重叠：
     *  优先用【真实结构 AABB 相交】判定（Rust broad-phase queryAabbIntersecting，可靠），
     *  替代旧的 scanCenter±half=1024 巨型矩形（误合并根因；2026-09-05 半宽默认已改 8）。
     *  ① 空间 a 成员的世界 AABB（localBounds 世界块坐标）转成 b 空间局部坐标
     *    （−b.origin）→ 查询 b 场景 → 命中 b 成员 → 重叠。
     *  ② 任一空间场景未建（sceneHandle==0）→ 回退 rectsOverlap（矩形近似）。
     *  ③ 查询异常 → 回退 rectsOverlap。 */
    private boolean spacesOverlap(final PhysicalSpace a, final PhysicalSpace b) {
        if (a == null || b == null || a.members.isEmpty() || b.members.isEmpty()) return false;
        final long sceneB = b.sceneHandle;
        final long sceneA = a.sceneHandle;
        if (sceneB == 0L || sceneA == 0L) return rectsOverlap(a, b);
        try {
            return spacesOverlapRust(a, b, sceneB);
        } catch (final Throwable t) {
            return rectsOverlap(a, b);
        }
    }

    /** ★ 2026-09-04 【Rust AABB 相交查询】空间 a 的每个成员世界 AABB（localBounds）
     *  → 转 b 局部坐标 → queryAabbIntersecting(b.scene) → 命中属于 b 的成员 → 重叠。
     *  ⚠ 只用成员结构真实 AABB（Rust collider 世界 AABB），不用巨型扫描矩形。 */
    private boolean spacesOverlapRust(final PhysicalSpace a, final PhysicalSpace b,
                                      final long sceneB) {
        final int[] out = new int[64];
        for (final int mem : a.members) {
            final PhysicalizedData d = physicalized.get(mem);
            if (d == null || !d.active) continue;
            final int[] lb = d.localBounds;
            if (lb == null || lb[3] < lb[0]) continue;   // bounds 未初始化
            // 世界 AABB（localBounds 世界块坐标）→ b 局部坐标（−b.origin）
            final double minX = lb[0] - b.origin[0];
            final double minY = lb[1] - b.origin[1];
            final double minZ = lb[2] - b.origin[2];
            final double maxX = lb[3] + 1 - b.origin[0];
            final double maxY = lb[4] + 1 - b.origin[1];
            final double maxZ = lb[5] + 1 - b.origin[2];
            final int n = CryptandRapierNative.queryAabbIntersecting(sceneB,
                    minX, minY, minZ, maxX, maxY, maxZ, out);
            for (int i = 0; i < n; i++) {
                if (b.members.contains(out[i])) return true;   // 命中 b 成员 → 重叠
            }
        }
        return false;
    }

    /** ---- 弃置区（2026-09-05 空间检测 v2 绿框聚类取代；无调用方，保留）----
     * ★ 2026-09-06 【矩形 BFS 加入】把 runtimeId 并入其【矩形扫描范围内】的已有空间：
     *  从 seed 出发 BFS 扫描 A×B×C 矩形（contains 判定）；新成员在矩形边缘 → 扩展矩形
     *  直到无新成员（用户：BFS 建立物理化结构的矩形区域）。
     *  ⚠ 只读物理化数据；返回加入的空间 id（0=未加入）。 */
    private int bfsJoinSpace(final int runtimeId, final PhysicalizedData d) {
        final double wx = d.pose[0], wy = d.pose[1], wz = d.pose[2];
        for (final PhysicalSpace sp : spaces.values()) {
            if (sp.members.isEmpty()) continue;
            // ★ 2026-09-05 分桶守卫：目标空间正在计算 → 跳过（下一 tick 回普通表再并入）
            if (spaceInOperation(sp)) continue;
            // 该结构当前是否在【空间矩形】内（以 seed 原点为中心 ± half）
            if (!sp.contains(wx, wy, wz)) continue;
            // BFS 扩散：从该结构向外扫描矩形内的其他未分配结构，全部并入
            final int countBefore = sp.members.size();
            bfsExpandRect(sp, d, wx, wy, wz);
            if (sp.members.size() > countBefore) {
                sp.dirty = true;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] SPACE JOIN rt={} -> space={} (members {} -> {})",
                        runtimeId, sp.id, countBefore, sp.members.size());
            }
            return sp.id;
        }
        return 0;
    }

    /** ---- 弃置区（2026-09-05 空间检测 v2 绿框聚类取代；无调用方，保留）----
     * ★ 2026-09-06 【矩形 BFS 扩展到成员】从指定成员出发，把空间矩形内的其他物理化
     *  结构并入（BFS）；发现边缘外成员 → 扩展矩形（half 增大），直到覆盖全部链。 */
    private void bfsExpandRect(final PhysicalSpace sp, final PhysicalizedData from,
                               final double fx, final double fy, final double fz) {
        final java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        final java.util.Set<Integer> seen = new java.util.HashSet<>();
        queue.add(from.runtimeId);
        seen.add(from.runtimeId);
        while (!queue.isEmpty()) {
            final int cur = queue.poll();
            final PhysicalizedData cd = physicalized.get(cur);
            if (cd == null) continue;
            final double cx = cd.pose[0], cy = cd.pose[1], cz = cd.pose[2];
            for (final PhysicalizedData od : physicalized.values()) {
                if (od == cd || seen.contains(od.runtimeId)) continue;
                if (!od.active) continue;
                // ★ 2026-09-05 分桶守卫：od 所属空间正在计算 → 不从其拉出/并回（避免与
                //   空间任务并发改 members；等下一 tick 再并入）
                if (od.spaceId != 0 && od.spaceId != sp.id) {
                    final PhysicalSpace odSp = spaces.get(od.spaceId);
                    if (spaceInOperation(odSp)) continue;
                }
                final double dx = od.pose[0] - cx;
                final double dy = od.pose[1] - cy;
                final double dz = od.pose[2] - cz;
                final double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist <= scanStep()) {
                    // 相邻（在扫描步长内）→ 并入；若超出矩形 → 先扩展矩形（半宽默认 8：
                    //   新成员在 8 格外即扩展；2026-09-05 用户 1024→8）
                    if (!sp.contains(od.pose[0], od.pose[1], od.pose[2])) {
                        expandSpaceRect(sp, od);
                    }
                    sp.members.add(od.runtimeId);
                    od.spaceId = sp.id;
                    seen.add(od.runtimeId);
                    queue.add(od.runtimeId);
                }
            }
        }
    }

    /** 扩展空间矩形以覆盖 od：半宽 = |od - origin| 各轴取 max（原半宽、成员偏移）。
     *  半宽默认 8（2026-09-05 用户 1024→8）；新成员在 8 格外 → 扩展矩形覆盖它。 */
    private void expandSpaceRect(final PhysicalSpace sp, final PhysicalizedData od) {
        // ★ 2026-09-06 矩形扩展基于【扫描中心 scanCenter】（矩形随 seed 移动）；
        //   不触碰固定坐标原点 origin。
        final double dx = Math.abs(od.pose[0] - sp.scanCenter[0]);
        final double dy = Math.abs(od.pose[1] - sp.scanCenter[1]);
        final double dz = Math.abs(od.pose[2] - sp.scanCenter[2]);
        sp.halfX = Math.max(sp.halfX, dx);
        sp.halfY = Math.max(sp.halfY, dy);
        sp.halfZ = Math.max(sp.halfZ, dz);
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] SPACE rect expanded id={} half=({},{},{}) (add rt={})",
                sp.id, sp.halfX, sp.halfY, sp.halfZ, od.runtimeId);
    }

    /** ★ 2026-09-06 【矩形 BFS 全量建立（用户：坐标更新后 BFS 建立矩形区域）】
     *  对空间从 seed 出发重新 BFS：确保 seed 及矩形内的全部成员入列，
     *  并把矩形随 seed 新的移动位置重新定位（origin ← seed 当前位姿）。
     *  ⚠ 节流：SABLE_SPACE_BFS_INTERVAL_SEC 内不重复扫描（用户：不需要一直扫）。
     *  ---- 弃置区（2026-09-05 空间检测 v2 绿框聚类取代；rebalanceSpaces 全量重聚类）---- */
    public synchronized void rebuildSpaceRect(final int spaceId) {
        // 弃置：空间归属现由 rebalanceSpaces() 按绿框重叠聚类维护，无需 BFS 矩形扩展。
        //   （保留 POST_PROCESS 调用点安全——no-op）
    }

    /** ★ 2026-09-06 【BFS 节流】是否到该空间周期扫描时间（配置 SABLE_SPACE_BFS_INTERVAL_SEC）。
     *  0 = 禁用周期扫描（仅成员变动/首建时扫）。 */
    private boolean bfsDue(final PhysicalSpace sp, final long nowNanos) {
        final double intervalSec = ConfigCryptandSable.SABLE_SPACE_BFS_INTERVAL_SEC.get();
        if (intervalSec <= 0) return false;
        return nowNanos - sp.lastBfsNanos >= (long) (intervalSec * 1_000_000_000.0);
    }

    /** ---- 弃置区（2026-09-05 空间检测 v2 绿框聚类取代；scanSpacesPeriodic no-op）----
     * ★ 2026-09-06 【周期 BFS 扫描全部空间】不再使用：空间归属由 rebalanceSpaces() 按绿框
     *  重叠全量重聚类维护（每 tick 主线程调用），无需周期 BFS。保留签名兼容调用方。 */
    public synchronized int scanSpacesPeriodic() {
        return 0;   // 弃置 no-op
    }

    /** 成员间允许的连接间距（格）——从配置读取（用户：A边正前方512格扫描到）。 */
    private static double scanStep() {
        return ConfigCryptandSable.SABLE_SPACE_SCAN_STEP.get();
    }

    /**
     * ★ 2026-09-06 【多空间版】入桶：新结构单开独立物理空间（assignSpace）；
     *  合并/拆分由 rebalanceSpaces() 按绿框重叠聚类完成（不再 BFS 扩展矩形）。
     *  ⚠ 只读物理化数据，异步线程可执行。返回 true。
     */
    public synchronized boolean ensureInBucket(final int runtimeId) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        if (d == null) return true;
        assignSpace(runtimeId);
        return true;
    }

    /** ★ 2026-09-05 入桶（可传初始 pose——createStructureShapeBody 时 data.pose 未记录）：
     *  分配空间（并入/新建）。⚠ 若 data.pose 尚未记录，先写入种子 pose 供 BFS 距离判定。 */
    public synchronized boolean ensureInBucketFiltered(final int runtimeId,
                                                       final double[] seedPose) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        if (d == null) return true;
        // 种子 pose 先写入 data.pose（BFS 距离判定依据；createStructureShapeBody 时未记录）
        if (d.pose[0] == 0 && d.pose[1] == 0 && d.pose[2] == 0
                && seedPose != null && seedPose.length >= 3) {
            d.pose[0] = seedPose[0];
            d.pose[1] = seedPose[1];
            d.pose[2] = seedPose[2];
        }
        return ensureInBucket(runtimeId);
    }

    /** ★ 2026-09-05 动态重居中：某个 space 的成员（局部坐标）离其空间原点超过
     *  BUCKET_RECENTER 阈值 → 平移场景 + 该空间原点更新。⚠ 修正过方向：平移量 =
     *  旧原点 − 新原点（l' = l + (o − n)）。多个空间各自独立重居中（各自原点）。 */
    private static final double BUCKET_RECENTER = 200_000.0;

    private synchronized void maybeRecenter() {
        // ★ 2026-09-05【多空间模型取消全局 recenter】recenterScene 会平移【场景内全部
        //   body】——但多空间模型下各空间 body 局部坐标系【独立】：一个空间成员离差超限
        //   → 平移全部 body → 其他空间 body 被加到错误坐标 → 全部爆炸（日志铁证：
        //   recenter id=1 dx=(-9E21) → pose rt=8 (新空间3) 立即 Y=3.6E22 → 正反馈）。
        //   ⚠ recenter 无法按空间分离（native 单场景）→ 多空间下必须禁用。
        //   每个结构独立空间 + 局部坐标恒小（f32 无忧），无需重居中。
        return;
        /* 单空间残留逻辑（保留注释供参考）：
        if (!bucketEnabled || spaces.isEmpty()) return;
        for (final PhysicalSpace sp : spaces.values()) {
            if (Double.isNaN(sp.origin[0])) continue;
            double farDx = 0, farDy = 0, farDz = 0;
            double farAbs = 0;
            for (final int mem : sp.members) {
                final PhysicalizedData d = physicalized.get(mem);
                if (d == null || !d.active) continue;
                final double dx = d.pose[0] - sp.origin[0];
                final double dy = d.pose[1] - sp.origin[1];
                final double dz = d.pose[2] - sp.origin[2];
                final double abs = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                if (abs > farAbs) {
                    farAbs = abs;
                    farDx = dx;
                    farDy = dy;
                    farDz = dz;
                }
            }
            if (farAbs > BUCKET_RECENTER) {
                // 新原点指向离差最大的成员位置（取整）
                final double nx = Math.floor(sp.origin[0] + farDx);
                final double ny = Math.floor(sp.origin[1] + farDy);
                final double nz = Math.floor(sp.origin[2] + farDz);
                final double dx = sp.origin[0] - nx;   // 旧 − 新（正确方向）
                final double dy = sp.origin[1] - ny;
                final double dz = sp.origin[2] - nz;
                try {
                    final long scene = requireScene();
                    final int n = CryptandRapierNative.recenterScene(scene, dx, dy, dz);
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[CryptandSable] SPACE recenter id={} dx=({},{},{}) movedBodies={} "
                                    + "newOrigin=({},{},{})",
                            sp.id, dx, dy, dz, n, nx, ny, nz);
                } catch (final Throwable ignored) {
                }
                sp.origin[0] = nx;
                sp.origin[1] = ny;
                sp.origin[2] = nz;
            }
        }
        */
    }

    /**
     * ★ 2026-09-02 【key 直接坐标化】废弃 packKey 位域压缩（负坐标/有符号边界易错、
     *  round-trip 不匹配导致坐标污染）；Map<SectionKey, int[]> 直接记录 (secX,secY,secZ)。
     */

    /**
     * ★ 2026-09-02 扫描世界块写入该结构 PhysicalizedData 的【扫描表】（隔离）。
     *  由 WorldChunkUploader 收集时调用。
     *  ★ 2026-09-05 【替换语义】不再 putAll 累加：每轮收集用最新扫描表【整体替换】——
     *  （原 putAll 导致旧位置 section 残留 → syncShapeCompanion 的 wanted 含旧位置
     *  → 旧位置 box 永不删 → 结构弹飞后陪体/黄框留在旧位置 = "陪体未更新"根因）。
     */
    public synchronized void storeScannedBlocks(final int runtimeId,
                                                final java.util.Map<SectionKey, int[]> sections) {
        if (sections == null) return;
        final PhysicalizedData d = physicalizedData(runtimeId);
        d.scannedBlocks.clear();
        d.scannedBlocks.putAll(sections);
        // ★ 2026-09-05 登记扫描区域（clip 红框＝结构 bounds 边缘 ± radius）：
        //   chunk 加载补扫时按此区域【补扫全部 y-section】（含表内缺失的 —— 未加载
        //   区块加载后也能补上，"只扫已有"永不补扫描区域缺块）。
        final WorldChunkUploader wu =
                CryptandSable.instance().worldUploader();
        if (wu != null) {
            final int[] clip = wu.clipBoundsSnapshot();
            if (clip != null) {
                d.scanMinX = clip[0]; d.scanMinY = clip[1]; d.scanMinZ = clip[2];
                d.scanMaxX = clip[3]; d.scanMaxY = clip[4]; d.scanMaxZ = clip[5];
                d.scanRangeSet = true;
            }
        }
    }

    /** ★ 2026-09-07 【核心消息路由：收集回复消费】主线程收集完成（读 Level 后）→ 投递到
     *  核心；核心【消息路由校验】——只有接收对象（物理空间）在操作表中 + 特征（UUID）匹配
     *  + 子阶段匹配才有效（用户："世界扫描等只有接收对象在操作列表中时有效，消息对象不在
     *  操作列表则此消息无效"；"等待回复时扫描世界消息有效，其他阶段都无效并丢弃"）。
     *  ★ 2026-09-07 【无锁】：spaceTasksInflight 为无锁并发 set；stage/feature 为 volatile 读。
     *  @return true=消息有效已受理；false=无效丢弃 */
    public boolean routeSpaceMessage(final int spaceId, final long feature,
                                     final int msgType, final Object payload) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null) return false;
        // ① 接收对象必须在操作列表中（普通表空间的消息无效）
        if (!spaceTasksInflight.contains(spaceId)) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] route DROP space={} msg={} (not in op-table)",
                    spaceId, msgType);
            return false;
        }
        // ② 特征（UUID 拷贝）必须匹配当前空间周期
        if (feature != sp.feature()) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] route DROP space={} msg={} feature={} != {} (stale cycle)",
                    spaceId, msgType, feature, sp.feature());
            return false;
        }
        // ③ 子阶段有效性：收集回复只在 WAIT_REPLY 有效；其他阶段丢弃
        final SpaceCycleStage st = sp.stage;
        if (msgType == MSG_COLLECT_REPLY || msgType == MSG_SPACE_SCAN_REPLY) {
            if (st != SpaceCycleStage.WAIT_REPLY) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] route DROP space={} collectReply (stage={}, not WAIT_REPLY)",
                        spaceId, st);
                return false;
            }
        } else if (msgType == MSG_SCAN_UPDATE) {
            // 扫描更新：等待引擎时无效（引擎计算中不允许改陪体）；其他阶段有效
            if (st == SpaceCycleStage.SEND_ENGINE || st == SpaceCycleStage.WAIT_ENGINE
                    || st == SpaceCycleStage.POST_PROCESS) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] route DROP space={} scanUpdate (stage={}, engine phase)",
                        spaceId, st);
                return false;
            }
        }
        // ④ 受理：投递到空间周期任务（经核心→入队→CAS 独占线程处理）
        applyRoutedMessage(spaceId, msgType, payload);
        return true;
    }

    /** 消息受理：写数据 + 置 dirty/解冻 + 置 replyArrived + 重新入队（空间任务消费）。 */
    private void applyRoutedMessage(final int spaceId, final int msgType, final Object payload) {
        if (msgType == MSG_COLLECT_REPLY) {
            final Object[] a = (Object[]) payload;
            final int runtimeId = (Integer) a[0];
            final boolean collected = (Boolean) a[1];
            @SuppressWarnings("unchecked")
            final java.util.Map<SectionKey, int[]> scanned =
                    (java.util.Map<SectionKey, int[]>) a[2];
            final PhysicalizedData d = physicalized.get(runtimeId);
            if (d == null) return;
            if (scanned != null && !scanned.isEmpty()) {
                d.scannedBlocks.clear();
                d.scannedBlocks.putAll(scanned);
            }
            final PhysicalSpace sp = spaces.get(spaceId);
            if (sp != null) {
                sp.dirty = true;
                sp.replyArrived = true;   // 等待线程据此推进 WAIT_REPLY → PRE_PROCESS
            }
            if (collected) {
                markCollisionReady(runtimeId);
            } else {
                markCollisionReadyOrTimeout(runtimeId, false);
            }
            enqueueSpaceTick(spaceId);   // 重新入队推进周期
        } else if (msgType == MSG_SPACE_SCAN_REPLY) {
            // ★ 2026-09-05 【空间级扫描回复（打包请求的单条回复）】payload =
            //   Object[]{timestamp, Map<runtimeId, Map<SectionKey,int[]>> scanned}
            final Object[] a = (Object[]) payload;
            final long ts = (Long) a[0];
            @SuppressWarnings("unchecked")
            final java.util.Map<Integer, java.util.Map<SectionKey, int[]>> all =
                    (java.util.Map<Integer, java.util.Map<SectionKey, int[]>>) a[1];
            final PhysicalSpace sp = spaces.get(spaceId);
            if (sp != null) {
                // ★ 2026-09-05 【时间戳校验（直接时间记录）】回传时间戳必须 ≥ 当前请求
                //   （旧请求包的迟到回复 → 丢弃；特征不匹配已在上层 route 拦截）
                sp.lastScanReplyTs = ts;
                sp.replyArrived = true;   // 等待线程据此推进 WAIT_REPLY → PRE_PROCESS
            }
            if (all != null) {
                for (final java.util.Map.Entry<Integer, java.util.Map<SectionKey, int[]>> e
                        : all.entrySet()) {
                    final int rt = e.getKey();
                    final java.util.Map<SectionKey, int[]> scanned = e.getValue();
                    final PhysicalizedData d = physicalized.get(rt);
                    if (d == null) continue;
                    if (scanned != null && !scanned.isEmpty()) {
                        d.scannedBlocks.clear();
                        d.scannedBlocks.putAll(scanned);
                    }
                    // 该成员扫描结果 → 解冻（收集完成）
                    markCollisionReady(rt);
                }
                if (sp != null) sp.dirty = true;
            }
            enqueueSpaceTick(spaceId);   // 重新入队推进周期
        } else if (msgType == MSG_SCAN_UPDATE) {
            // 扫描更新：主线程世界事件（worldBlockChanged/chunkLoaded）→ 标记 dirty，
            // 由空间任务 PRE_PROCESS 上传陪体
            final Object[] a = (Object[]) payload;
            final int runtimeId = (Integer) a[0];
            markSpaceDirty(runtimeId);
            enqueueSpaceTick(spaceId);
        }
    }

    /** 收集回复消息类型。 */
    public static final int MSG_COLLECT_REPLY = 1;
    /** 世界扫描更新消息类型（方块变化/chunk 加载）。 */
    public static final int MSG_SCAN_UPDATE = 2;
    /** ★ 2026-09-05 空间级扫描回复消息类型（打包请求的单条回复）。 */
    public static final int MSG_SPACE_SCAN_REPLY = 3;

    /**
     * ★ 2026-09-06 【核心门面 · 收集结果】主线程收集完成 → 投递核心；核心经
     *  routeSpaceMessage 路由（操作表 + UUID 特征 + WAIT_REPLY 阶段）校验后受理。
     *  @param feature 物理空间 UUID 特征（主线程从查询携带回传；核心校验）
     */
    public void onCollectResult(final int runtimeId, final long feature, boolean collected,
                                final java.util.Map<SectionKey, int[]> scanned) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        if (d == null) return;
        final int spaceId = d.spaceId;
        if (spaceId == 0) return;
        routeSpaceMessage(spaceId, feature, MSG_COLLECT_REPLY,
                new Object[]{runtimeId, collected, scanned});
    }

    /** ★ 2026-09-05 【核心门面 · 空间级扫描结果（打包请求的单条回复）】
     *  主线程对空间扫描请求（SpaceScanRequest）包内所有区域统一收集完成 → 投递核心。
     *  经 routeSpaceMessage（操作表 + UUID 特征 + 时间戳 + WAIT_REPLY）校验后批量受理：
     *  写所有成员 scannedBlocks + 解冻。
     *  @param spaceId  物理空间 id
     *  @param feature  空间 UUID 拷贝（回传自请求包；核心校验）
     *  @param ts       请求包时间戳（直接时间记录；回传；核心据此识别新旧）
     *  @param all      Map<runtimeId, Map<SectionKey,int[]>> 各成员扫描结果 */
    public void onSpaceScanResult(final int spaceId, final long feature, final long ts,
                                  final java.util.Map<Integer, java.util.Map<SectionKey, int[]>> all) {
        if (spaceId == 0) return;
        routeSpaceMessage(spaceId, feature, MSG_SPACE_SCAN_REPLY,
                new Object[]{ts, all});
    }

    /**
     * ★ 2026-09-05 世界单方块变化（破坏/放置）实时增量更新（官方 handleBlockChange 的
     * shape 等价）：对每个活跃结构，若该 section 在其扫描范围内 → 从世界【重扫该
     * section】更新扫描表（不依赖旧表含该格）→ 增量 diff 同步陪体。
     *  事件本身只给坐标；重扫保证任何情况（旧表缺失/过期）都拿到最新世界状态。
     */
    public synchronized void onWorldBlockChanged(final net.minecraft.server.level.ServerLevel level,
                                                 final int x, final int y, final int z,
                                                 final boolean oldSolid, final boolean newSolid) {
        // ★ 2026-09-06 【物理坐标】扫描表用物理坐标（far）；读 Level 用主世界 section
        // ★ 2026-09-06 【无 far】扫描表主世界 key（物理坐标=主世界，零转换）
        final SectionKey k = new SectionKey(x >> 4, y >> 4, z >> 4);
        boolean any = false;
        for (final PhysicalizedData d : physicalized.values()) {
            if (!d.active || d.shapeCompanionId == 0) continue;
            if (d.scannedBlocks.isEmpty()) continue;
            // 只处理扫描表【已含该 section】的结构（结构覆盖该格；越界忽略，下一轮收集补）
            if (!d.scannedBlocks.containsKey(k)) continue;
            final int[] fresh = scanWorldSection(level, x >> 4, y >> 4, z >> 4);
            if (fresh != null) {
                d.scannedBlocks.put(k, fresh);
                any = true;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] worldBlockChanged ({},{},{}) -> rt={} rescan sec={} boxSet={}",
                        x, y, z, d.runtimeId, k, d.companionBoxes.size());
                // ★ 2026-09-06 【串行化】主线程不直接调 native materialize —— 只更新
                //   数据；上传陪体由空间任务（pipelineLock 内）阶段2 统一做。
                // ★ 2026-09-07 【消息路由】通知核心：扫描更新经 routeSpaceMessage
                //   （操作表 + UUID 特征 + 子阶段校验；引擎阶段无效丢弃）。
                postScanUpdate(d.runtimeId);
            }
        }
        if (!any) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] worldBlockChanged ({},{},{}) -> no active structure scans sec={}",
                    x, y, z, k);
        }
    }

    /**
     * ★ 2026-09-05 chunk 加载（官方 handleChunkSectionAddition 的 shape 等价）：对每个
     * 活跃结构，若其扫描范围（data.scannedBlocks 最近 section）覆盖该 chunk → 重扫
     * 该 chunk 的 16 节 section（用 WorldChunkUploader 现有逻辑），storeScannedBlocks 后
     * 增量同步。简化：只处理【已经在该结构扫描表内】的 section（新加载 chunk 若不在
     * 范围内 → 下一轮全量收集）。
     */
    public synchronized void onWorldChunkLoaded(final net.minecraft.server.level.ServerLevel level,
                                                final net.minecraft.world.level.ChunkPos chunkPos) {
        for (final PhysicalizedData d : physicalized.values()) {
            if (!d.active || d.shapeCompanionId == 0) continue;
            if (d.scannedBlocks.isEmpty()) continue;
            try {
                if (rescanChunkForData(level, chunkPos, d)) {
                    // ★ 2026-09-06 【串行化】只更新数据（不直接调 native materialize）；
                    //   由空间任务（pipelineLock 内）阶段2 统一上传。
                    // ★ 2026-09-07 【消息路由】通知核心：扫描更新经 routeSpaceMessage
                    //   （操作表 + UUID 特征 + 子阶段校验；引擎阶段无效丢弃）。
                    postScanUpdate(d.runtimeId);
                }
            } catch (final Throwable ignored) {
            }
        }
    }

    /** ★ 2026-09-06 【串行化辅助】主线程更新扫描数据后，标记所属空间 dirty，由空间
     *  任务（持 pipelineLock）阶段2 统一上传陪体。纯数据标记（不触碰 native）。 */
    private void markSpaceDirty(final int runtimeId) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        if (d == null || d.spaceId == 0) return;
        final PhysicalSpace sp = spaces.get(d.spaceId);
        if (sp != null) sp.dirty = true;
    }

    /** ★ 2026-09-07 【主线程世界事件 → 核心路由（扫描更新）】主线程检测到方块变化/
     *  chunk 加载后，重扫数据已更新（scannedBlocks），【通知核心路由】：只有接收对象
     *  （物理空间）在操作表中 + 特征（UUID）匹配 + 子阶段有效（引擎阶段 SEND_ENGINE/
     *  WAIT_ENGINE/POST_PROCESS 无效丢弃）才受理（用户架构第5条）；否则丢弃——
     *  数据由下一轮全量收集（SEND_QUERY→WAIT_REPLY）兜底同步。无锁（并发集合+volatile）。 */
    private void postScanUpdate(final int runtimeId) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        if (d == null || d.spaceId == 0) return;
        final PhysicalSpace sp = spaces.get(d.spaceId);
        if (sp == null) return;
        routeSpaceMessage(d.spaceId, sp.feature(), MSG_SCAN_UPDATE,
                new Object[]{runtimeId});
    }

    /**
     * ★ 2026-09-05 chunk 卸载（原 handleChunkSectionRemoval 对应）——【不更新陪体】。
     * 用户定案（2026-09-05）：chunk 卸载后陪体 box 保留更稳定。scannedBlocks 数据
     * 在 PhysicalizedData 内不消失（box 只是其镜像）；若卸载即删，结构在视野边缘会
     * 突然失去支撑倒塌；且下一轮全量收集（uploadAroundBounds）对未加载区块跳过，
     * 保留旧 box 是结构稳定的最简单路径。chunk 重新加载时由 onWorldChunkLoaded
     * 重扫该 section → syncShapeCompanion diff 无变化 → no-op。
     */
    public synchronized void onWorldChunkUnloaded(final net.minecraft.world.level.ChunkPos chunkPos) {
        // no-op：卸载不更新陪体（box 保留，结构保持原有支撑）
    }

    /**
     * ★ 2026-09-05 【补扫重写】chunk 加载时：对该 chunk 与结构扫描区域（clip 红框）交集内的
     * 【全部 y-section】重扫（不在扫描表内的也扫——未加载期间缺席的 section 加载后补上；
     * section 无方块写"空气占位"数组入表，保证扫描表 = 该区域已加载的全部方块状态，
     * 差异检测只基于【已扫描部分】）。随结构移动 diff 同步陪体。
     */
    private boolean rescanChunkForData(final net.minecraft.server.level.ServerLevel level,
                                       final net.minecraft.world.level.ChunkPos chunkPos,
                                       final PhysicalizedData d) {
        // 扫描区域未登记（尚未物理化/未收集）→ 只重扫扫描表已有 section（旧行为兜底）
        if (!d.scanRangeSet) {
            return rescanExistingSections(level, chunkPos, d);
        }
        final int chunkMinX = chunkPos.x << 4, chunkMaxX = chunkMinX + 15;
        final int chunkMinZ = chunkPos.z << 4, chunkMaxZ = chunkMinZ + 15;
        // chunk 与扫描区域（clip）无交集 → 该 chunk 不在结构扫描范围内 → 不动
        if (chunkMaxX < d.scanMinX || chunkMinX > d.scanMaxX
                || chunkMaxZ < d.scanMinZ || chunkMinZ > d.scanMaxZ) {
            return false;
        }
        boolean touched = false;
        // 该 chunk 在扫描区域内的 y-section 全集
        final int y0 = d.scanMinY >> 4, y1 = d.scanMaxY >> 4;
        for (int sy = y0; sy <= y1; sy++) {
            // ★ 2026-09-06 【无 far】扫描表主世界 key
            final SectionKey k = new SectionKey(chunkPos.x, sy, chunkPos.z);
            final int[] fresh = scanWorldSection(level, k.x(), k.y(), k.z());
            if (fresh == null) continue;   // 未加载/越界 → 跳过（等下次）
            if (fresh.length == 4096) {
                boolean allZero = true;
                for (final int v : fresh) if (v != 0) { allZero = false; break; }
                if (allZero) {
                    // 空气 section：扫描表【没有】则不加入（空 = 无方块 = 无可挂 box；
                    // 但"已扫描标记"通过 materialize 指纹变化体现）——仍标记 touched
                    // 避免下一轮差分为零（表不变 = 陪体不变）。
                    d.scannedBlocks.put(k, fresh);   // 空气占位，真实代表"此处无方块"
                } else {
                    d.scannedBlocks.put(k, fresh);
                }
                touched = true;
            }
        }
        // 清理：扫描区域内但该 chunk 已卸载的数据若存在该 chunk 外？——无（y 只在本 chunk）
        return touched;
    }

    /** 旧行为：只重扫扫描表已有的 section（y 取扫描表实际值）。 */
    private boolean rescanExistingSections(final net.minecraft.server.level.ServerLevel level,
                                           final net.minecraft.world.level.ChunkPos chunkPos,
                                           final PhysicalizedData d) {
        boolean touched = false;
        for (final java.util.Map.Entry<SectionKey, int[]> e : d.scannedBlocks.entrySet()) {
            final SectionKey k = e.getKey();   // 主世界
            if (k.x() != chunkPos.x || k.z() != chunkPos.z) continue;
            final int[] fresh = scanWorldSection(level, k.x(), k.y(), k.z());
            if (fresh != null) {
                d.scannedBlocks.put(k, fresh);
                touched = true;
            }
        }
        return touched;
    }

    /** 扫描一个世界 section（返回 4096 voxel；null=越界/未加载）。装配器方块跳过。 */
    private int[] scanWorldSection(final net.minecraft.server.level.ServerLevel level,
                                   final int secX, final int secY,
                                   final int secZ) {
        final net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(secX, secZ);
        if (!level.isLoaded(cp.getWorldPosition())) return null;
        final net.minecraft.world.level.chunk.LevelChunk chunk =
                level.getChunk(secX, secZ);
        if (chunk == null) return null;
        final int sectionIndex = level.getSectionIndexFromSectionY(secY);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) return null;
        final net.minecraft.world.level.chunk.LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section == null) return null;
        final int[] data = new int[4096];
        if (section.hasOnlyAir()) return data;
        final int baseX = secX << 4, baseY = secY << 4, baseZ = secZ << 4;
        for (int bx = 0; bx < 16; bx++) {
            for (int bz = 0; bz < 16; bz++) {
                for (int by = 0; by < 16; by++) {
                    final int wx = baseX + bx, wy = baseY + by, wz = baseZ + bz;
                    final net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(wx, wy, wz);
                    final net.minecraft.world.level.block.state.BlockState state =
                            level.getBlockState(pos);
                    if (state.isAir()) continue;
                    // 装配器方块排除（物理化装置，不属于世界地形）
                    final net.minecraft.resources.ResourceLocation rl =
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK
                                    .getKey(state.getBlock());
                    if (rl != null && rl.getNamespace().equals("simulated")
                            && rl.getPath().equals("physics_assembler")) continue;
                    if (!state.getCollisionShape(level, pos).isEmpty()) {
                        // ★ 2026-09-05 【材质感知】高 16 位 = materialId（compound 路线仅判非零）
                        final int matId = com.hdf.cryptand.neoforge.cryptandsable.core.material
                                .BlockPhysicsTable.idOf(state);
                        data[bx + (bz << 4) + (by << 8)] = matId << 16 | 3;
                    }
                }
            }
        }
        return data;
    }

    /**
     * ★ 2026-09-02 【构建虚拟物理结构体】：从该结构 PhysicalizedData 提取
     *  （结构自身块 + 扫描块）挂载原生——计算（step）基于这些数据参与碰撞。
     *
     *  【挂载方式（2026-09-02 修正）】：扫描块已由 uploadSection 按 uploadMountBody
     *  挂载（global=true → world octree；ground 跨体配对的正解）——本方法【不再重复挂载】
     *  （原实现用旧 packKey 解码 asLong key → 坐标错位；且同批块双挂污染）。
     *  仅做：①更新 data.pose（实时物理位姿）②数据记录 ③调试日志。
     */
    public synchronized void attachScannedBlocks(final int runtimeId) {
        final PhysicalizedData data = physicalized.get(runtimeId);
        if (data == null || data.scannedBlocks.isEmpty()) return;
        // ★ 2026-09-02 不再重复 addChunk（uploadSection 已按 uploadMountBody 挂载）。
        //   data.scannedBlocks 仅作为【虚拟物理结构体数据源】（后续计算/重建用）。
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] attachScannedBlocks rt={} ({} scanned, {} structure, mass={}, "
                        + "localBounds=[{},{},{}..{},{},{}])",
                runtimeId, data.scannedBlocks.size(), data.structureBlocks.size(), data.mass,
                data.localBounds[0], data.localBounds[1], data.localBounds[2],
                data.localBounds[3], data.localBounds[4], data.localBounds[5]);
    }

    /** ★ 2026-09-02 该结构物理化数据统计（调试）：[structureSec, structureVoxels, scannedSec]。 */
    public synchronized int[] bodyVoxelDataStats(final int runtimeId) {
        final PhysicalizedData data = physicalized.get(runtimeId);
        if (data == null) return new int[]{0, 0, 0};
        return new int[]{data.structureBlocks.size(), data.nonEmptyVoxels(), data.scannedBlocks.size()};
    }

    /** ★ 2026-09-01 【首次收集等待集】——第一次物理化必须等到收集完成后才解冻；
     *  此类体永不因超时解冻（用户："注意第一次物理化必须等到收集后才能解冻物理计算"）。
     *  ★ 2026-09-04 ECS 化：改为 PhysicalizedData.awaitFirst/pending 字段（不再单独 Set）。 */

    // ===== ★ 2026-09-01 按【计算次数】调度的收集循环（用户设计） =====
    //  - sendIntervalSteps: 每 N 次计算发一次查询（如 3）
    //  - publishTimeoutSteps: 发送后等待 M 次计算仍未收到 → 再发一次（重置等待，循环）
    //  - 首次物理化：必须收到（awaitFirst 永不放弃）；后续更新：超过总超时 → 停
    //  - 主线程"空列表"也算收到（周围无方块 → markCollisionReady）

    /** ★ 2026-09-01 异步收集超时次数上限（配置：1=每次计算都必须接收；0=无限）。 */
    private volatile int collectTimeoutMax = 1;

    /** 发送间隔（计算次数）：每 N 次计算发了查询后跳 N 次再发。 */
    private volatile int collectSendIntervalSteps = 3;

    /** 超时（计算次数）：发送后等待 N 次仍未收到 → 再发（默认 15 = 100计算/20tick*3）。 */
    private volatile int collectTimeoutSteps = 15;

    // ★ 2026-09-04 ECS 化：各体计数器收敛进 PhysicalizedData（sendCounter/waitCounter/timeoutTotal）
    //   （原 collectSendCounters/collectWaitCounters/collectTimeoutTotal 引擎级 Map 已删除）

    /** ★ 出站世界收集查询队列（worker emit；主线程消费）。
     *  ★ 2026-09-05 【扫描消息池（用户定案）】：Map<runtimeId, WorldCollectQuery>——
     *  每物理化结构【最多一条】未消费查询；新的 put 覆盖旧的（强制替换），
     *  防挤压（worker 每计算步都 emit；消费慢 ⇒ 积压旧坐标 ⇒ 用旧位置扫描）。 */
    private final java.util.Map<Integer, Object> pendingCollectQueries = new java.util.LinkedHashMap<>();

    /** ★ 2026-09-05 【空间级扫描请求池（用户定案：按物理空间打包）】
     *  Map<spaceId, SpaceScanRequest>：每空间【最多一条】未消费的空间扫描请求。
     *  状态机 SEND_QUERY 阶段把该空间所有需扫描区域打包成一个请求（带 feature=空间
     *  UUID + timestamp=直接时间记录）放入；主线程 drain 后对包内所有区域统一收集，
     *  单条回传 SpaceScanResult（带同一特征）。新请求 put 覆盖旧（防挤压）。 */
    private final java.util.Map<Integer, com.hdf.cryptand.neoforge.cryptandsable.api.message
            .SableMessages.SpaceScanRequest> pendingSpaceScanRequests =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** ★ 2026-09-02 调试：碰撞探针计数器（每 50 次计算打一次）。 */
    private int collisionProbeCounter = 0;

    /** 收集超时上限设置（配置的值；启动时由 CryptandSable 设置）。 */
    public synchronized void setCollectTimeoutMax(int max) {
        // max 语义：总超时次数上限（1=每次必须；0=无限）——结合 sendInterval/timeoutSteps
        this.collectTimeoutMax = max;
    }

    /** 收集超时上限（配置值）。 */
    public synchronized int collectTimeoutMax() {
        return this.collectTimeoutMax;
    }

    /** 发送间隔（计算次数）设置。 */
    public synchronized void setCollectSendIntervalSteps(int n) {
        this.collectSendIntervalSteps = Math.max(1, n);
    }

    /** 超时（计算次数）设置：等待 N 次未收到再发。 */
    public synchronized void setCollectTimeoutSteps(int n) {
        this.collectTimeoutSteps = Math.max(1, n);
    }

    /** ★ 2026-09-05 【ECS · 世界绑定】LevelEvent.Load 时绑定世界 → 创建/复用该世界的
     *  核心数据类（PhysicsWorldData，每世界一个 → 世界隔离）。当前引擎单活绑定主世界，
     *  表里留多世界条目供后续扩展。 */
    public synchronized PhysicsWorldData bindWorld(
            final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> key) {
        PhysicsWorldData wd = key != null ? this.worldDataByLevel.get(key) : null;
        if (wd == null) {
            // 世界 uuid：维度 key location 稳定哈希（跨重启稳定；防碰撞用完整 UUID）
            final java.util.UUID worldUuid = key != null
                    ? java.util.UUID.nameUUIDFromBytes(key.location().toString().getBytes(
                            java.nio.charset.StandardCharsets.UTF_8))
                    : java.util.UUID.randomUUID();
            wd = new PhysicsWorldData(worldUuid);
            if (key != null) this.worldDataByLevel.put(key, wd);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] ECS worldData bound key={} uuid={}",
                    key, worldUuid);
        }
        this.worldData = wd;
        return wd;
    }

    /** 当前核心数据类（未绑定 → null）。 */
    public synchronized PhysicsWorldData worldData() {
        return this.worldData;
    }

    /** 启动（创建场景）。失败 → false（回退自研模拟器）。 */
    public synchronized boolean start(final double gravityX, final double gravityY, final double gravityZ) {
        try {
            // ★ 2026-09-05 【ECS】未绑定世界时兜底建核心数据类（单世界/测试）
            if (this.worldData == null) {
                this.worldData = bindWorld(net.minecraft.world.level.Level.OVERWORLD);
            }
            // ★ 2026-09-03 原生 shape 直接碰撞模式（构造后、所有体创建前读取）
            this.shapeCollision = ConfigCryptandSable.SABLE_SHAPE_COLLISION.get();
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] OfficialRapierEngine shapeCollision={} (native shape direct)",
                    this.shapeCollision);
            // ★ 2026-09-05 【凹形组合】轴合并开关/每合并体上限（compound 轴合并大 box）
            this.mergeAxisAligned = ConfigCryptandSable.SABLE_MERGE_AXIS_ALIGNED.get();
            this.mergeMaxBox = ConfigCryptandSable.SABLE_MERGE_MAX_BOX.get();
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] OfficialRapierEngine mergeAxisAligned={} mergeMaxBox={} " +
                            "(concave compound)",
                    this.mergeAxisAligned, this.mergeMaxBox);
            // 静态块已 loadLibrary；首次 initialize 可能抛 UnsatisfiedLinkError
            final long handle = CryptandRapierNative.initialize(gravityX, gravityY, gravityZ, DEFAULT_UNIVERSAL_DRAG);
            this.scene = new OfficialRapierScene(handle);
            this.disposed = false;
            // ★ 注册默认硬方块 collider（friction/volume/restitution/isFluid/callback；
            //   callback=null 无接触事件）。必须注册——addChunk 的 <<16 引用的 handle
            //   若未注册 → Rust panic（EXCEPTION_UNCAUGHT_CXX_EXCEPTION 崩溃）。
            this.defaultColliderHandle = CryptandRapierNative.newVoxelCollider(
                    0.6, 1.0, 0.1, false, null);
            // 单位立方体（全硬块）——官方 addBox 坐标 0..1
            CryptandRapierNative.addVoxelColliderBox(this.defaultColliderHandle,
                    new double[]{0.0, 0.0, 0.0, 1.0, 1.0, 1.0});
            // ★ 2026-09-04 应用 shape 缓存上限 + 最小批量删除数（Rust 侧按此淘汰）。
            final long cacheLimit = ConfigCryptandSable.SABLE_SHAPE_CACHE_LIMIT.get();
            final long minEvict = ConfigCryptandSable.SABLE_SHAPE_CACHE_MIN_EVICT.get();
            if (cacheLimit >= -1) {
                CryptandRapierNative.setShapeCacheLimit(handle, cacheLimit, minEvict);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] shapeCache limit={} minEvict={} (config applied)",
                        cacheLimit, minEvict);
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] OfficialRapierEngine started (scene={}, g=({},{},{}), collider={})",
                    handle, gravityX, gravityY, gravityZ, this.defaultColliderHandle);
            return true;
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.error(
                    "[CryptandSable] OfficialRapierEngine failed to start: {}", t.toString());
            this.scene = null;
            return false;
        }
    }

    /** 默认实体方块 collider handle（-1 = 未注册）。 */
    public int defaultColliderHandle() {
        return this.defaultColliderHandle;
    }

    public synchronized boolean isStarted() {
        return this.scene != null;
    }

    public synchronized void stop() {
        // ★ 2026-09-06 【铁律：JNI 全异步】清除 global ground 残留（main_level_chunks
        //   只增不清的累积数据）——removeChunk 是 JNI，投递异步线程执行（主线程只清
        //   Java 集合）。shape 模式 worldSections 恒空（不往 global 挂）→ no-op。
        try {
            if (!worldSections.isEmpty()) {
                final java.util.List<SectionKey> gk =
                        new java.util.ArrayList<>(worldSections);
                worldSections.clear();
                final long gScene = this.scene != null ? this.scene.handle() : 0L;
                if (gScene != 0L) {
                    try {
                        final java.util.concurrent.CompletableFuture<Void> f =
                                com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                                        .submitGeneric(
                                                () -> {
                                                    try {
                                                        for (final SectionKey key : gk) {
                                                            CryptandRapierNative.removeChunk(
                                                                    gScene, key.x(),
                                                                    key.y(), key.z(), true);
                                                        }
                                                    } catch (final Throwable ignored) {
                                                    }
                                                },
                                                com.hdf.cryptand.circuitsimulation.compute.TaskMode.NORMAL);
                        pendingJniTasks.add(f);
                        f.whenComplete((v, t) -> pendingJniTasks.remove(f));
                    } catch (final Throwable ignored) {
                    }
                }
            }
        } catch (final Throwable ignored) {
        }
        // ★ 2026-09-06 【每空间独立场景清理】停止时销毁全部空间独立 native 场景
        //   （防止重启后旧 handle 残留/双场景共享）。空间任务若在处理中会因 disposed
        //   提前退出（stepSpaceScene 检查 disposed）。
        try {
            for (final PhysicalSpace sp : spaces.values()) {
                destroySpaceScene(sp.id);
            }
            spaces.clear();
            spaceOpQueue.clear();
            spaceTasksInflight.clear();
            // ★ 2026-09-06 【铁律：JNI 全异步】等待异步 dispose 全部完成
            //   （防 DLL 卸载/场景销毁前 native 资源未释放）。
            for (final java.util.concurrent.CompletableFuture<Void> f :
                    new java.util.ArrayList<>(pendingJniTasks)) {
                try {
                    f.get(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (final Throwable ignored) {
                }
            }
            pendingJniTasks.clear();
        } catch (final Throwable ignored) {
        }
        if (this.scene != null) {
            // ★ 2026-09-06 【铁律：JNI 全异步】默认全局场景 dispose 也投递异步线程执行
            //   （主线程只做 Java 状态清理）；随后 join 等待完成（防 DLL 卸载前未释放）。
            final long h = this.scene.handle();
            this.scene = null;
            try {
                final java.util.concurrent.CompletableFuture<Void> f =
                        com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                                .submitGeneric(
                                        () -> {
                                            try {
                                                CryptandRapierNative.dispose(h);
                                            } catch (final Throwable ignored) {
                                            }
                                        },
                                        com.hdf.cryptand.circuitsimulation.compute.TaskMode.NORMAL);
                pendingJniTasks.add(f);
                f.whenComplete((v, t) -> pendingJniTasks.remove(f));
            } catch (final Throwable ignored) {
            }
        }
        // ★ 2026-09-06 【铁律：JNI 全异步】再次 join 等待全局场景 dispose 完成
        //   （防 DLL 卸载/重新 start 前 native 资源未释放）。
        for (final java.util.concurrent.CompletableFuture<Void> f :
                new java.util.ArrayList<>(pendingJniTasks)) {
            try {
                f.get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (final Throwable ignored) {
            }
        }
        pendingJniTasks.clear();
        // ★ 2026-09-05 【ECS】世界卸载：清空核心数据类（结构化表/空间表/绿色全局表）
        if (this.worldData != null) {
            this.worldData.structuredTable.clear();
            this.worldData.spaceTable.clear();
            this.worldData.greenTable.clear();
            this.worldData = null;
        }
        this.disposed = true;
    }

    private long requireScene() {
        if (this.scene == null) {
            throw new IllegalStateException("OfficialRapierEngine not started");
        }
        return this.scene.handle();
    }

    /** ★ 2026-09-05 【批量位姿访问】当前 scene handle（0=未启动；供 getPoseBatch 用）。 */
    public synchronized long sceneHandleForBatch() {
        if (this.scene == null || this.disposed) return 0L;
        return this.scene.handle();
    }

    /** ★ 2026-09-05 【批量位姿投影】native 批量原始（空间局部）坐标 → 投影（主世界）坐标。
     *  与 getPose() 相同的还原规则（加所属空间原点；未分桶=原样）。供
     *  getPoseBatch 消费方（PoseSnapshot/渲染）使用——确保所有消费方都是投影坐标。 */
    public synchronized double[] projectBatchPose(final int runtimeId, final double[] raw) {
        if (raw == null || !hasBucket()) return raw;
        final double wx = toWorldFor(runtimeId, raw[0], 0);
        final double wy = toWorldFor(runtimeId, raw[1], 1);
        final double wz = toWorldFor(runtimeId, raw[2], 2);
        final double[] out = new double[7];
        out[0] = wx; out[1] = wy; out[2] = wz;
        for (int i = 3; i < 7; i++) out[i] = raw[i];
        return out;
    }

    /**
     * 创建物理体（★ 2026-09-04 【全 shape】废弃体素管线：本方法兼容保留签名，
     * 内部直接转发 createStructureShapeBody（动态 shape 刚体 + 每格 box collider）。）
     *
     * @param runtimeId  自定义 runtime id（官方 CryptandRapierNative.getID 用）
     * @param pose       位姿 [x,y,z,qx,qy,qz,qw]（初始【主世界坐标】）
     * @param sections   section 列表（{secX,secY,secZ, int[4096]}）；null → 空体（仅质点）
     * @param bounds     局部 bounds（minX..maxX 六值；null → 由 sections 推算）
     */
    public synchronized void createBody(final int runtimeId, final double[] pose,
                                        final java.util.List<SectionUpload> sections,
                                        final int[] bounds) {
        createStructureShapeBody(runtimeId, pose, sections, bounds);
    }

    /** 统计 section 中非空体素数（质量近似；★ 已不用于 mass——质量=方块数）。 */
    private static int countVoxels(final java.util.List<SectionUpload> sections) {
        if (sections == null) return 1;
        int n = 0;
        for (final SectionUpload sec : sections) {
            for (final int v : sec.chunk()) {
                if (v != 0) n++;
            }
        }
        return Math.max(1, n);
    }

    /** 体素 section 上传（secX/Y/Z = section 坐标；chunk = 4096 int[] xzy 序）。 */
    public record SectionUpload(int secX, int secY, int secZ, int[] chunk) {
    }

    public synchronized void removeBody(final int runtimeId) {
        // ★ 2026-09-05 ECS 化：allActiveBodies 已删——active 状态在 data（删除时由后面 physicalized.remove 清理）。
        // ★ 2026-09-04 删除后不再扫描：清该结构【ECS 数据实例】里的全部收集调度状态。
        //   （原 collisionPending/collisionAwaitFirst/collect*Counters 已收敛进 PhysicalizedData）
        final PhysicalizedData d0 = physicalized.get(runtimeId);
        // ★ 2026-09-05 【ECS】注销结构：结构化数据表 + 绿色全局表（幂等）
        if (this.worldData != null && d0 != null) {
            this.worldData.structuredTable.remove(d0.uuid);
            this.worldData.greenTable.remove(d0.uuid);
        }
        // ★ 2026-09-06 【每空间独立场景】删除从【所属空间场景】执行（结构挂在空间 scene）。
        final int sid0 = d0 != null ? d0.spaceId : 0;
        final long scene = sid0 != 0 && spaces.get(sid0) != null
                && spaces.get(sid0).sceneHandle != 0L
                ? spaces.get(sid0).sceneHandle : requireScene();
        if (d0 != null) {
            d0.pending = false;
            d0.awaitFirst = false;
            d0.sendCounter = 0;
            d0.waitCounter = 0;
            d0.timeoutTotal = 0;
        }
        // ★ 2026-09-05 扫描消息池：删除该体的未消费查询（每体一条；直接 remove）。
        pendingCollectQueries.remove(runtimeId);
        // ★ 2026-09-03 shape 模式：结构是 shape 刚体 → removeShapeBody + 清理 shape 陪体
        // ★ 2026-09-04 【全 shape】voxel 分支已删除（结构一律 shape 刚体）。
        // ★ 2026-09-05 多空间：从所属空间移除成员。
        // ★ 2026-09-05 【分桶守卫】空间正在计算（任务执行中/操作表）→ 不直接改 members
        //   （与空间任务读 HashSet 并发 → CME）。native 已异步删除 + physicalized.remove
        //   已做 → members 残留 id 无害（空间任务遍历时 get()==null 自动跳过/清理）；
        //   等空间回普通表后由 rebalance/空间任务清理。
        if (d0 != null && d0.spaceId != 0) {
            final PhysicalSpace sp = spaces.get(d0.spaceId);
            if (sp != null) {
                if (!spaceInOperation(sp)) {
                    sp.members.remove(runtimeId);
                }
                sp.dirty = true;
            }
            d0.spaceId = 0;
        }
        if (this.shapeCollision) {
            final Integer gid = d0 != null && d0.shapeCompanionId != 0
                    ? d0.shapeCompanionId : null;
            physicalized.remove(runtimeId);
            // ★ 2026-09-06 【铁律：主线程只做 Level 交互，JNI 全异步】JNI 删除
            //   （removeShapeBody 结构体 + 陪体）投递到分配器普通线程执行；主线程只做
            //   Java 状态清理（physicalized 已移除 → 空间任务不再引用）。
            final long delScene = scene;
            final int delRt = runtimeId;
            final int delGid = gid != null ? gid : 0;
            try {
                com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                        .submitGeneric(
                                () -> {
                                    try {
                                        if (delGid != 0) {
                                            CryptandRapierNative.removeShapeBody(delScene, delGid);
                                        }
                                        CryptandRapierNative.removeShapeBody(delScene, delRt);
                                    } catch (final Throwable ignored) {
                                    }
                                },
                                com.hdf.cryptand.circuitsimulation.compute.TaskMode.NORMAL);
            } catch (final Throwable ignored) {
            }
        }
    }

    /** 位姿查询（写入 store[7]；★ 从 native 远处坐标还原为主世界坐标）。
     *  2026-09-03：shape 模式用主世界坐标 → 不还原（减 FAR 会把真实坐标移走 150 万）。
     *  ★ 2026-09-05 分桶：native 存的是【桶局部坐标】→ 加桶原点还原主世界。
     *  ★ 2026-09-06 【每空间独立场景】从所属空间场景查询。 */
    public synchronized void getPose(final int runtimeId, final double[] store) {
        final PhysicalizedData dGP = physicalized.get(runtimeId);
        final PhysicalSpace spGP = dGP != null && dGP.spaceId != 0 ? spaces.get(dGP.spaceId) : null;
        final long scene = spGP != null && spGP.sceneHandle != 0L ? spGP.sceneHandle : requireScene();
        CryptandRapierNative.getPose(scene, runtimeId, store);
        // ★ 2026-09-06 【无 far】native 直接主世界坐标（物理坐标=主世界0级，不转换）
    }

    /** ★ 2026-09-06 【物理坐标（同主世界）】拉取当前物理位姿（无 far；用户定案：物理
     *  坐标=主世界0级，不需要转换，真实坐标概念删除）。供 info 指令显示。 */
    public synchronized void getPhysicsPose(final int runtimeId, final double[] store) {
        final PhysicalizedData dGP = physicalized.get(runtimeId);
        final PhysicalSpace spGP = dGP != null && dGP.spaceId != 0 ? spaces.get(dGP.spaceId) : null;
        final long scene = spGP != null && spGP.sceneHandle != 0L ? spGP.sceneHandle : requireScene();
        CryptandRapierNative.getPose(scene, runtimeId, store);
    }

    /** 预步 + 结算（官方同款：tick → step；一次游戏 tick 级物理步。 */
    public synchronized void step() {
        // ★ 2026-09-02 冻结修复：dispose 后不再触碰原生（step 返回 no-op，
        //   避免重进前旧 worker 残留调用 requireScene 抛异常/踩已释放 handle）。
        if (this.disposed) return;
        // ★ 2026-09-03 /cryptand sable physics off：冻结积分（结构保持位姿），
        //   但仍发收集查询（黄框/收集持续刷新；防结构冻结后收集死锁）。
        if (!this.physicsStepEnabled) {
            emitCollectQueries();
            return;
        }
        final long scene = requireScene();
        // ★ 2026-09-01 初次收集完成前不计算（防结构 free-fall 穿透）：
        //   物理化时先把 runtimeId 加入 collisionPending（冻结），
        //   WorldChunkUploader 收集完成后 markCollisionReady 解除。
        //   ⚠ 时序死锁修复：冻结时也必须发收集查询（否则主线程无查询可消费 → 永不收集
        //   → 永不解冻 → "冻结不动了"）。查询发出 → 主线程收集 → markCollisionReady。
        // ★ 2026-09-04 ECS 化：pending 状态在 PhysicalizedData.pending（遍历 dataList）。
        if (hasPending()) {
            emitCollectQueries();   // ★ 冻结也发查询（防死锁）
            return;
        }
        CryptandRapierNative.tick(scene, 1.0 / 20.0);
        for (int i = 0; i < 4; i++) {   // 官方 physicsTick substeps=4
            CryptandRapierNative.step(scene, 1.0 / 20.0 / 4.0);
        }
        // ★ 2026-09-02 调试：每 50 次计算打印碰撞事件（结构碰到世界块 → clearCollisions 有值）
        if (++collisionProbeCounter >= 50) {
            collisionProbeCounter = 0;
            try {
                final double[] col = CryptandRapierNative.clearCollisions(scene);
                if (col != null && col.length > 0) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[CryptandSable] collisions count={} first=(a={},b={},force={})",
                            col.length / 15, (int) col[0], (int) col[1], col[2]);
                } else {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[CryptandSable] collisions NONE (probe)");
                }
            } catch (final Throwable ignored) {
            }
        }
        // ★ 2026-09-01 异步收集循环：每次计算后发收集查询（主线程收集回传）。
        // ★ 2026-09-02 掉穿不再 teleport 兜底（用户：物理天然实现、绝不 tp）；
        //   结构落地由【静态陪体】body↔body 碰撞天然接住。
        emitCollectQueries();
    }

    /** ★ 2026-09-04 ECS 化：是否任一结构在等待首次收集（遍历 dataList）。 */
    private synchronized boolean hasPending() {
        for (final PhysicalizedData d : physicalized.values()) {
            if (d.pending) return true;
        }
        return false;
    }

    // ===== ★ 2026-09-06 【每空间独立 native + 四阶段管道 + 操作列表驱动】 =====
    //  用户定案：每 tick 把需要计算的物理空间从列表移动到操作列表，把任务计算发送给
    //  线程分配器（普通线程 NORMAL，不用独占）；与 Rust 交互时 Java 线程保持等待
    //  直到返回数据（JNI 同步调用 = 发送后阻塞等待）。

    /** ★【单空间移入操作表并提交】空间【被分配任务】时从普通表移入操作表
     *  （用户：当被分配任务的物理空间需要移入操作表）；已在处理 → 跳过。
     *  ★ 2026-09-07 【无锁】用并发集合 + CAS（taskRunning.compareAndSet）代替锁——
     *  用户："不加锁，消息机制本身就是同步机制；架构做好无需锁，锁会引发死锁"。 */
    public void enqueueSpaceTick(final int spaceId) {
        if (!spaceTaskEnabled || disposed) return;
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null || sp.members.isEmpty()) return;
        // 进入操作表（记录特征 UUID）；已在 → 仅补队（回复到达后重触发）
        spaceTasksInflight.add(spaceId);
        if (sp.stage == SpaceCycleStage.NORMAL) {
            sp.stage = SpaceCycleStage.SEND_QUERY;   // 进入周期：先发消息给主线程
            sp.stageDeadlineNanos = System.nanoTime() + spaceOpTimeoutNanos();
        }
        sp.queued = true;
        spaceOpQueue.add(spaceId);
        submitSpaceTaskIfIdle();
    }

    /** ★ 主线程每 tick：【普通表 → 操作表】分配（对齐电路仿真核心 NetlistOperation 模式）。
     *  ★ 2026-09-07 【超时检查】操作表中超时空间 → FAILED 移回普通表（丢弃后续数据）。
     *  ★ 2026-09-07 【无锁】并发集合遍历 + CAS 提交；无 spaceQueueLock。 */
    public void tickScheduledSpaces() {
        if (!spaceTaskEnabled || disposed) return;
        // ① 操作表超时检查（stageDeadlineNanos 已过 → 操作失败）
        checkSpaceOpTimeouts();
        // ② 普通表 → 操作表：把需要计算的空间移入操作表
        for (final PhysicalSpace sp : spaces.values()) {
            if (sp.members.isEmpty()) continue;
            if (sp.fullyUnloaded()) continue;
            if (spaceTasksInflight.contains(sp.id)) continue;
            spaceTasksInflight.add(sp.id);
            sp.stage = SpaceCycleStage.SEND_QUERY;
            sp.stageDeadlineNanos = System.nanoTime() + spaceOpTimeoutNanos();
            sp.queued = true;
            spaceOpQueue.add(sp.id);
        }
        // ③ 对操作表中仍等待推进的空间补队（回复已到但未提交；防漏）
        for (final PhysicalSpace sp : spaces.values()) {
            if (!spaceTasksInflight.contains(sp.id)) continue;
            if (sp.taskRunning.get()) continue;
            if (sp.stage == SpaceCycleStage.NORMAL) continue;
            if (!sp.queued) {
                sp.queued = true;
                spaceOpQueue.add(sp.id);
            }
        }
        submitSpaceTaskIfIdle();
    }

    /** ★ 2026-09-07 【操作表超时检查】空间周期内任一子阶段超时 → 标记 FAILED 并移回普通表
     *  （用户："可配置超时；超过超时则向核心发送操作失败请求移动回普通表，失败后数据被传入就丢弃"）。
     *  ★ 无锁：仅 volatile stage/stageDeadlineNanos 读 + 并发集合 remove。 */
    private void checkSpaceOpTimeouts() {
        final long timeout = spaceOpTimeoutNanos();
        if (timeout <= 0) return;
        final long now = System.nanoTime();
        for (final int sid : new java.util.ArrayList<>(spaceTasksInflight)) {
            final PhysicalSpace sp = spaces.get(sid);
            if (sp == null) continue;
            final SpaceCycleStage st = sp.stage;
            if (st == SpaceCycleStage.NORMAL) continue;
            if (now - sp.stageDeadlineNanos > timeout) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] SPACE op TIMEOUT space={} stage={} -> FAILED (moved back to normal)",
                        sid, st);
                // 丢弃该空间挂起消息（per-space 池）
                synchronized (spaceCollectQueries) {
                    spaceCollectQueries.remove(sid);
                }
                spaceTasksInflight.remove(sid);
                sp.stage = SpaceCycleStage.NORMAL;
                sp.replyArrived = false;
                sp.queued = false;
            }
        }
    }

    /** 空间操作周期超时（纳秒）；配置 SABLE_SPACE_OP_TIMEOUT_MS（0=禁用）。 */
    private long spaceOpTimeoutNanos() {
        final long ms = ConfigCryptandSable.SABLE_SPACE_OP_TIMEOUT_MS.get();
        return ms <= 0 ? 0L : ms * 1_000_000L;
    }

    /**
     * 从操作列表取下一个空间 → 若无任务在跑则提交空间任务（NORMAL 普通线程）。
     * ★ 2026-09-07 【无锁 CAS 单任务独占】同一空间同一时间只有一个周期任务线程操作：
     *  用 sp.taskRunning.compareAndSet(false,true) 抢占（失败 → 放弃本轮，已在跑）；
     *  任务完成 whenComplete 中 CAS 回 false。native scene 单线程访问由 CAS 保证
     *  （无需 pipelineLock / spaceQueueLock；锁会引发死锁）。
     */
    private void submitSpaceTaskIfIdle() {
        while (!spaceOpQueue.isEmpty()) {
            final Integer sid = spaceOpQueue.poll();
            if (sid == null) break;
            final PhysicalSpace sp = spaces.get(sid);
            if (sp == null || sp.members.isEmpty()) continue;
            if (!spaceTasksInflight.contains(sid)) continue;   // 已移出操作表
            if (sp.stage == SpaceCycleStage.NORMAL) continue;
            // CAS 抢占：只有抢占成功的线程提交任务（单任务独占，无锁）
            if (!sp.taskRunning.compareAndSet(false, true)) continue;
            sp.queued = false;
            final int spaceId = sid;
            try {
                com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers
                        .submitGeneric(
                                () -> runSpacePipeline(spaceId),
                                com.hdf.cryptand.circuitsimulation.compute.TaskMode.NORMAL)
                        .whenComplete((v, t) -> {
                            sp.taskRunning.set(false);
                            if (t != null) {
                                CryptandNeoForge.WAF_LOGGER.warn(
                                        "[CryptandSable] SPACE task failed space={}: {}",
                                        spaceId, t.toString());
                            }
                            // ★ 周期完成/失败 → 移回普通表（由 runSpacePipeline 内完成处理）
                            //   失败时也回普通表（下一 tick 重新判定）
                            final SpaceCycleStage st2 = sp.stage;
                            if (st2 == SpaceCycleStage.COMPLETE || st2 == SpaceCycleStage.FAILED) {
                                spaceTasksInflight.remove(spaceId);
                                sp.stage = SpaceCycleStage.NORMAL;
                                sp.replyArrived = false;
                                sp.queued = false;
                            }
                        });
            } catch (final Throwable t) {
                sp.taskRunning.set(false);
            }
        }
    }

    /**
     * ★ 2026-09-07 【空间周期状态机（用户定案）】运行在 ThreadDispatchers 普通线程。
     *  子阶段：SEND_QUERY（发消息给主线程，带特征 UUID）→ WAIT_REPLY（等待回复）
     *  → PRE_PROCESS（前处理：重建/陪体上传预备）→ SEND_ENGINE（发送引擎 step）
     *  → WAIT_ENGINE（等待引擎；扫描更新无效）→ POST_PROCESS（后处理：坐标转换/读位姿）
     *  → COMPLETE（周期完成 → 核心移回普通表）。
     *  ★ 2026-09-07 【无锁】：同一空间同一时间只有一个周期任务线程操作——由
     *    submitSpaceTaskIfIdle 的 taskRunning.compareAndSet CAS 独占保证（无需锁）。
     *    每次任务推进到"等待点"（WAIT_REPLY 未收到回复）即返回，等主线程回复经
     *    routeSpaceMessage 置 replyArrived + enqueueSpaceTick 重新入队推进。
     */
    public void runSpacePipeline(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null || sp.members.isEmpty()) return;
        try {
            // 状态机推进（CAS 已保证单任务独占；无锁）
            for (int guard = 0; guard < 64; guard++) {
                final SpaceCycleStage st = sp.stage;
                switch (st) {
                    case SEND_QUERY: {
                        // ① 发消息给主线程（空间级打包扫描请求，带 feature=空间 UUID + 时间戳）
                        //   → 等单条回复。2026-09-05 用户定案：按物理空间打包所有扫描区域，
                        //   替代逐成员 WorldCollectQuery（一次打包、一次收集、一次回复）。
                        final int emitted = emitSpaceScanRequest(spaceId);
                        if (emitted <= 0) {
                            // ★ 2026-09-04 修复：空间全部成员已解冻 → 无冻结成员 → 无查询
                            //   可发。若仍进 WAIT_REPLY 会【死等一个永远不会来的回复】→
                            //   10s 超时循环 → SEND_ENGINE 永不执行 → 引擎从不 step →
                            //   所有物理结构不动（本 bug 根因）。无查询时直接推进
                            //   PRE_PROCESS → SEND_ENGINE（每 tick step 保持仿真）。
                            sp.stage = SpaceCycleStage.PRE_PROCESS;
                            break;
                        }
                        sp.stage = SpaceCycleStage.WAIT_REPLY;
                        sp.stageDeadlineNanos = System.nanoTime() + spaceOpTimeoutNanos();
                        return;   // 等主线程回复（到达 → routeSpaceMessage → enqueueSpaceTick）
                    }
                    case WAIT_REPLY: {
                        if (!sp.replyArrived) {
                            return;   // 还没回复 → 结束本轮（超时由 tickScheduledSpaces 检查）
                        }
                        sp.replyArrived = false;
                        sp.stage = SpaceCycleStage.PRE_PROCESS;
                        break;
                    }
                    case PRE_PROCESS: {
                        long scene = spaceSceneHandle(spaceId);
                        if (scene == 0L) return;   // 无 scene → 下轮重试
                        // ★ 2026-09-05 【清理失效成员】主线程 removeBody 在空间计算时
                        //   不直接改 members（分桶守卫）→ 这里由空间任务线程（独占该空间）
                        //   统一清理已删（physicalized 已无）成员，保持集合干净。
                        for (final int mem : new java.util.ArrayList<>(sp.members)) {
                            final PhysicalizedData dm = physicalized.get(mem);
                            if (dm == null) {
                                sp.members.remove(mem);
                            }
                        }
                        // 前处理：重建 / 陪体上传 / 质心检测（原阶段2）
                        if (sp.dirtyRebuild) {
                            rebuildSpaceMembers(spaceId);
                            sp.dirtyRebuild = false;
                        }
                        if (sp.dirty) {
                            for (final int mem : new java.util.ArrayList<>(sp.members)) {
                                final PhysicalizedData dMem = physicalized.get(mem);
                                if (dMem == null || !dMem.active) continue;
                                if (dMem.scannedBlocks == null || dMem.scannedBlocks.isEmpty()) continue;
                                try {
                                    // ★ 2026-09-05 【凹形组合】陪体 = 轴合并大 box（compound）
                                    //   ——世界扫描块在核心轴合并成最大 box 集，一次 native
                                    //   建 fixed 刚体 + box collider。替代体素 diff 路线。
                                    materializeCompoundCompanion(mem);
                                } catch (final Throwable ignored) {
                                }
                            }
                            sp.dirty = false;
                        }
                        for (final int mem : new java.util.ArrayList<>(sp.members)) {
                            final PhysicalizedData dMem = physicalized.get(mem);
                            if (dMem == null || !dMem.active) continue;
                            if (!dMem.comChanged()) continue;
                            try {
                                CryptandNeoForge.WAF_LOGGER.info(
                                        "[CryptandSable] SPACE com-change rebuild rt={} space={} com=({},{},{}) -> ({},{},{})",
                                        mem, spaceId, dMem.lastComX, dMem.lastComY, dMem.lastComZ,
                                        dMem.comWorldX(), dMem.comWorldY(), dMem.comWorldZ());
                                rebuildMemberBody(scene, mem, dMem);
                            } catch (final Throwable ignored) {
                            }
                        }
                        final java.util.List<Object> collectQueries =
                                drainSpaceCollectQueries(spaceId);
                        if (!collectQueries.isEmpty()) {
                            CryptandNeoForge.WAF_LOGGER.info(
                                    "[CryptandSable] SPACE phase2 collectQueries={} space={}",
                                    collectQueries.size(), spaceId);
                        }
                        // 仍有冻结成员（收集未完成）→ 回 SEND_QUERY 继续收（防穿透）
                        if (spaceHasFrozenMembers(spaceId)) {
                            sp.stage = SpaceCycleStage.SEND_QUERY;
                            break;
                        }
                        sp.stage = SpaceCycleStage.SEND_ENGINE;
                        break;
                    }
                    case SEND_ENGINE: {
                        if (sp.loadedCount <= 0) {
                            sp.stage = SpaceCycleStage.COMPLETE;
                            break;
                        }
                        stepSpaceScene(spaceId);   // JNI 同步等待返回
                        sp.hasStepped = true;
                        sp.lastPipelineNanos = System.nanoTime();
                        sp.stage = SpaceCycleStage.WAIT_ENGINE;
                        break;
                    }
                    case WAIT_ENGINE: {
                        // 引擎同步返回 → 后处理（扫描更新在此阶段无效）
                        sp.stage = SpaceCycleStage.POST_PROCESS;
                        break;
                    }
                    case POST_PROCESS: {
                        readSpacePoses(spaceId);   // 坐标转换（相对 → 主世界）
                        try {
                            rebuildSpaceRect(spaceId);
                        } catch (final Throwable ignored) {
                        }
                        // 位姿发布回调（主线程 PoseSnapshot 广播/镜像）
                        final java.util.function.Consumer<Integer> cb = this.spacePoseCallback;
                        if (cb != null) {
                            try {
                                cb.accept(spaceId);
                            } catch (final Throwable ignored) {
                            }
                        }
                        sp.stage = SpaceCycleStage.COMPLETE;
                        break;
                    }
                    case COMPLETE: {
                        // 周期完成 → whenComplete 中按 COMPLETE 移回普通表
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[CryptandSable] SPACE cycle COMPLETE space={} (moved back to normal)",
                                spaceId);
                        return;
                    }
                    case FAILED:
                        CryptandNeoForge.WAF_LOGGER.warn(
                                "[CryptandSable] SPACE cycle FAILED space={} (moved back to normal)",
                                spaceId);
                        return;
                    default:
                        return;
                }
            }
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] SPACE pipeline failed space={}: {}", spaceId, t.toString());
        }
    }

    /** ★ 空间任务完成后：为该空间【仍冻结（等待首次收集）】的成员发收集查询。
     *  已解冻结构【不再发】——否则每轮收集 → onCollectResult 置 dirty → 空间任务
     *  又触发 → 无限收集循环（且反复 materialize 陪体）。世界地形增量由世界事件驱动。 */
    private int emitCollectQueriesForSpace(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null || sp.members.isEmpty()) return 0;
        int emitted = 0;
        for (final int mem : sp.members) {
            final PhysicalizedData d = physicalized.get(mem);
            if (d == null || !d.active) continue;
            if (!d.pending && !d.awaitFirst) continue;   // 已解冻 → 无需再收集
            // 位置：优先实时位姿（d.pose 投影）；本地半尺寸（结构 bounds）
            final double cx = d.pose[0], cy = d.pose[1], cz = d.pose[2];
            if (cx == 0 && cy == 0 && cz == 0) continue;
            final int halfX = Math.max(1, (d.localBounds[3] - d.localBounds[0]) / 2 + 1);
            final int halfY = Math.max(1, (d.localBounds[4] - d.localBounds[1]) / 2 + 1);
            final int halfZ = Math.max(1, (d.localBounds[5] - d.localBounds[2]) / 2 + 1);
            final int minX = (int) Math.floor(cx) - halfX;
            final int minY = (int) Math.floor(cy) - halfY;
            final int minZ = (int) Math.floor(cz) - halfZ;
            final int maxX = (int) Math.floor(cx) + halfX;
            final int maxY = (int) Math.floor(cy) + halfY;
            final int maxZ = (int) Math.floor(cz) + halfZ;
            final int radius = ConfigCryptandSable.SABLE_WORLD_COLLISION_RADIUS.get();
            // ★ 2026-09-07 特征 = 物理空间 UUID 拷贝（sp.feature()）——主线程回传时据此
            //   识别所属周期；空间移回普通表/超时后到达的消息特征不匹配 → 丢弃。
            final SableMessages.WorldCollectQuery q =
                    new SableMessages.WorldCollectQuery(
                            mem, minX, minY, minZ, maxX, maxY, maxZ, cx, cy, cz, radius,
                            sp.feature());
            pendingCollectQueries.put(mem, q);   // 全局池（主线程 drain 消费）
            putSpaceCollectQuery(spaceId, q);    // per-space 池（下一轮任务阶段2）
            emitted++;
        }
        return emitted;
    }

    /** ★ 2026-09-05 【空间级打包扫描请求（用户定案：按物理空间打包）】
     *  把该空间【所有需要扫描的区域】打包成一个 SpaceScanRequest（带特征 = 空间 UUID
     *  拷贝 + 直接时间记录 timestamp）放入空间级请求池；主线程 drain 后对包内所有区域
     *  统一收集，单条回传（带同一特征）。替代逐成员 WorldCollectQuery（一次打包、一次回复）。
     *  已解冻成员不打包（无需收集）；世界地形增量由世界事件驱动。
     *  @return 打包的区域数（0 = 无区域需收集） */
    private int emitSpaceScanRequest(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null || sp.members.isEmpty()) return 0;
        final java.util.List<com.hdf.cryptand.neoforge.cryptandsable.api.message
                .SableMessages.ScanRegion> regions = new java.util.ArrayList<>();
        for (final int mem : sp.members) {
            final PhysicalizedData d = physicalized.get(mem);
            if (d == null || !d.active) continue;
            if (!d.pending && !d.awaitFirst) continue;   // 已解冻 → 无需收集
            // ★ 2026-09-06 【扫描用主世界包围盒（不动点）】不要用物理位姿 d.pose——
            //   结构弹飞/漂移后 pose 错误 → 扫描中心跟着错 → 永远扫不到世界。
            //   以 localBounds（主世界，物理化时确定）±half + radius 为扫描区域，
            //   面积固定，覆盖结构周围地形。
            final int lb0 = d.localBounds[0], lb1 = d.localBounds[1], lb2 = d.localBounds[2];
            final int lb3 = d.localBounds[3], lb4 = d.localBounds[4], lb5 = d.localBounds[5];
            final int halfX = Math.max(1, (lb3 - lb0) / 2 + 1);
            final int halfY = Math.max(1, (lb4 - lb1) / 2 + 1);
            final int halfZ = Math.max(1, (lb5 - lb2) / 2 + 1);
            // 中心 = 主世界包围盒中心（不动；结构未动前扫描覆盖全结构 + 周边）
            final double ccx = (lb0 + lb3 + 1) * 0.5;
            final double ccy = (lb1 + lb4 + 1) * 0.5;
            final double ccz = (lb2 + lb5 + 1) * 0.5;
            final int minX = lb0 - 4, minY = lb1 - 4, minZ = lb2 - 4;
            final int maxX = lb3 + 4, maxY = lb4 + 4, maxZ = lb5 + 4;
            final int radius = ConfigCryptandSable.SABLE_WORLD_COLLISION_RADIUS.get();
            regions.add(new com.hdf.cryptand.neoforge.cryptandsable.api.message
                    .SableMessages.ScanRegion(mem, minX, minY, minZ, maxX, maxY, maxZ,
                            ccx, ccy, ccz, radius));
        }
        if (regions.isEmpty()) return 0;
        // 特征 = 空间 UUID 拷贝 + 直接时间记录（nanoTime；核心据此识别请求包新旧/过期）
        final long ts = System.nanoTime();
        final SableMessages.SpaceScanRequest req =
                new SableMessages.SpaceScanRequest(
                        spaceId, sp.feature(), ts, regions);
        pendingSpaceScanRequests.put(spaceId, req);   // 每空间一条（覆盖旧）
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] SPACE scan-request space={} regions={} ts={} (packaged)",
                spaceId, regions.size(), ts);
        return regions.size();
    }

    /** ★ 2026-09-05 【空间级扫描请求出站（主线程 drain）】取走全部空间级请求（每空间一条，
     *  消费后清空）。主线程对每包的 regions 统一收集 → 单条回传。 */
    public java.util.List<com.hdf.cryptand.neoforge.cryptandsable.api.message
            .SableMessages.SpaceScanRequest> drainSpaceScanRequests() {
        if (pendingSpaceScanRequests.isEmpty()) return java.util.Collections.emptyList();
        final java.util.List<com.hdf.cryptand.neoforge.cryptandsable.api.message
                .SableMessages.SpaceScanRequest> out =
                new java.util.ArrayList<>(pendingSpaceScanRequests.values());
        pendingSpaceScanRequests.clear();
        return out;
    }

    /** ★ 阶段2重建：该空间成员全部重新挂载到其独立 scene（关系变化后调）。
     *  纯 native 挂载（createShapeBody/removeShapeBody）——空间任务线程执行。
     *  ★ 2026-09-06 【拆分/合并迁移】dirty 空间的成员 body 必须在该空间场景内唯一：
     *  重建时先【清空该空间场景全部 structure body（removeSubLevel）】，再按成员
     *  列表【全部重建】——迁移后无残留（旧空间场景里已删、新空间场景重建）。
     *  ★ 2026-09-06 【synchronized 防 getPose 竞态】：rebuild 会 remove/createSubLevel
     *  （结构性修改 native），必须与主线程 getPose 等互斥——否则主线程在 rebuild
     *  删除后查询该 id → Rust rigid_bodies Index panic（崩溃根因）。 */
    private synchronized void rebuildSpaceMembers(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null) return;
        final long scene = sp.sceneHandle;
        if (scene == 0L) return;
        try {
            // ① 清空该空间场景全部动态结构 body（仅该空间；陪体 fixed 保留由 materialize 管理）
            final java.util.List<Integer> existing = new java.util.ArrayList<>(sp.members);
            try {
                final int[] cnt = new int[1];
                final double[] store = new double[Math.max(64, (physicalized.size() + 64) * 8)];
                CryptandRapierNative.getPoseBatch(scene, store, cnt);
                final int n = Math.min(cnt[0], store.length / 8);
                final java.util.List<Integer> toRemove = new java.util.ArrayList<>();
                for (int i = 0; i < n; i++) {
                    final int id = (int) store[i * 8];
                    // 仅移除【结构 body】（正区；陪体 shapeCompanionId 负区保留）
                    if (id > 0) toRemove.add(id);
                }
                for (final int id : toRemove) {
                    if (!sp.members.contains(id)) {
                        // 不在本空间成员 → 不属于本空间（拆出）→ 删除其在这个旧场景的 body
                        try {
                            CryptandRapierNative.removeShapeBody(scene, id);
                        } catch (final Throwable ignored) {
                        }
                    }
                }
            } catch (final Throwable ignored) {
            }
            // ② 重建全部成员（补建；已存在的跳过——但拆出/合并的成员在新空间无 body → 重建）
            for (final int mem : new java.util.ArrayList<>(sp.members)) {
                final PhysicalizedData d = physicalized.get(mem);
                if (d == null || !d.active) continue;
                try {
                    // 每次重建都重新挂（remove 幂等；若为新成员此步创建）
                    if (sp.dirtyRebuild) {
                        try {
                            CryptandRapierNative.removeShapeBody(scene, mem);
                        } catch (final Throwable ignored) {
                        }
                    }
                    rebuildMemberBody(scene, mem, d);
                    // ★ 2026-09-06 【陪体迁移】结构迁到新场景后，旧场景的陪体（负 id）
                    //   不会自动跟过来 → 新场景无地面 → 结构持续下坠（“飞走”）。
                    //   重置 rebuildStamp 强制下一轮 materialize 在新场景重建陪体
                    //   （materializeScannedBlocks 里 rebuildStamp==-1 时不受节流/指纹限制）。
                    d.rebuildStamp = -1L;
                    d.scanFingerprint = -1L;
                } catch (final Throwable ignored) {
                }
            }
            sp.dirtyRebuild = false;
            // ★ 2026-09-06 【串行化】重建完成后置 dirty：同一管道内阶段2 紧随其后
            //   的 dirty 分支会把该空间成员陪体上传到【新场景】（修复合并后无地面下坠）。
            sp.dirty = true;
        } catch (final Throwable ignored) {
        }
    }

    /** 重建单个结构 body 到指定 scene（createSubLevel + setMass/bounds + addChunk）。
     *  ★ synchronized：结构性 native 修改，与主线程 getPose 互斥（防竞态崩溃）。
     *  ★ 2026-09-06 【防 tp 回原点】body 原点使用【当前物理位姿】（d.pose 投影 − origin），
     *  而非初始 localBounds 中心——否则每次 rebuild 结构被移回初始位置。 */
    private synchronized void rebuildMemberBody(final long scene, final int runtimeId, final PhysicalizedData d) {
        final PhysicalSpace sp = spaces.get(d.spaceId);
        if (sp == null) return;
        // ★ 2026-09-05 【材质感知 + 重量】从结构自身方块表（SectionKey → int[4096]）取材质表
        //   （高 16 位 = materialId）→ 同材质轴合并 + 逐方块质量加权（原版 MassTracker）。
        final java.util.Map<Long, Integer> solidMat =
                CompoundShapeMerger.solidMat(d.structureBlocks);
        if (solidMat.isEmpty()) return;
        // ★ 2026-09-06 【sable 同构】局部化：块坐标 − floor(origin)（origin 可能非整质心；
        //   块级基准用整数部分；body 原点另用完整 origin+com（double））
        //   ★ structureBlocks(Map) 的 SectionKey = secX（主世界 section，起点 boundMinX>>4），
        //     块 = secX<<4 + bx 缺 foX = boundMinX&15 偏移 → 这里【补 foX】
        final double ox = sp.origin[0], oy = sp.origin[1], oz = sp.origin[2];
        final double oxF = Math.floor(ox), oyF = Math.floor(oy), ozF = Math.floor(oz);
        final int foX = (d.localBounds[0] & 15), foY = (d.localBounds[1] & 15),
                foZ = (d.localBounds[2] & 15);
        final java.util.Map<Long, Integer> localSolid = new java.util.HashMap<>();
        for (final java.util.Map.Entry<Long, Integer> e : solidMat.entrySet()) {
            int x = (int) ((e.getKey() >> 42) & 0x1FFFFF);
            int y = (int) ((e.getKey() >> 21) & 0x1FFFFF);
            int z = (int) (e.getKey() & 0x1FFFFF);
            if (x >= 0x100000) x -= 0x200000;
            if (y >= 0x100000) y -= 0x200000;
            if (z >= 0x100000) z -= 0x200000;
            x += foX; y += foY; z += foZ;
            localSolid.put(CompoundShapeMerger.packPos(
                    (long) (x - oxF), (long) (y - oyF), (long) (z - ozF)), e.getValue());
        }
        final java.util.List<CompoundShapeMerger.MergedBox> mboxes =
                CompoundShapeMerger.mergeMat(localSolid, this.mergeMaxBox);
        if (mboxes.isEmpty()) return;
        final CompoundShapeMerger.MassResult mr = CompoundShapeMerger.computeMassProperties(
                localSolid, id -> physProps(id).mass(), id -> physProps(id).liftStrength(),
                id -> physProps(id).volume());
        final double massV = Math.max(1.0, mr.mass());
        // ★ 2026-09-06 【sable 同构】body 原点 = 主世界（origin + 局部质心）
        final double comX = mr.comX(), comY = mr.comY(), comZ = mr.comZ();
        final double[] nPose = new double[]{ox + comX, oy + comY, oz + comZ,
                d.pose[3], d.pose[4], d.pose[5], d.pose[6]};
        final double[] halfAndCenter = new double[mboxes.size() * 6];
        for (int i = 0; i < mboxes.size(); i++) {
            final int[] b = mboxes.get(i).box();
            halfAndCenter[i * 6] = (b[3] - b[0] + 1) * 0.5;
            halfAndCenter[i * 6 + 1] = (b[4] - b[1] + 1) * 0.5;
            halfAndCenter[i * 6 + 2] = (b[5] - b[2] + 1) * 0.5;
            // ★ 2026-09-06 【sable 同构】局部盒心 − 局部质心（相对 body）
            halfAndCenter[i * 6 + 3] = (b[0] + b[3] + 1) * 0.5 - comX;
            halfAndCenter[i * 6 + 4] = (b[1] + b[4] + 1) * 0.5 - comY;
            halfAndCenter[i * 6 + 5] = (b[2] + b[5] + 1) * 0.5 - comZ;
        }
        // 结构级综合摩擦/弹性（box 体积加权摩擦 + max 弹性）
        final double[] fr = CompoundShapeMerger.compositeSurfaceProps(mboxes,
                id -> physProps(id).friction(), id -> physProps(id).restitution());
        CryptandRapierNative.createCompoundShapeBody(scene, runtimeId, 0, massV, mboxes.size(),
                halfAndCenter, boxCoeffs(mboxes), fr[0], fr[1], nPose);
        try {
            CryptandRapierNative.setMassProperties(scene, runtimeId, massV,
                    new double[]{0, 0, 0}, mr.inertia());
        } catch (final Throwable ignored) {
        }
        // 记录材质/重量/三种力数据
        d.massProps = mr;
        d.mass = massV;
        d.friction = fr[0];
        d.restitution = fr[1];
        d.liftX = mr.liftX();
        d.liftY = mr.liftY();
        d.liftZ = mr.liftZ();
        d.totalLift = mr.totalLift();
        d.buoyX = mr.buoyX();
        d.buoyY = mr.buoyY();
        d.buoyZ = mr.buoyZ();
        d.totalVolume = mr.totalVolume();
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] SPACE rebuild rt={} -> space={} scene={} boxes={} com=({},{},{}) " +
                        "fr={}/{} lift={} buoyVol={} (compound remount)",
                runtimeId, spaceIdForRuntime(runtimeId), scene, mboxes.size(),
                comX, comY, comZ, fr[0], fr[1], mr.totalLift(), mr.totalVolume());
        // ★ 2026-09-06 重建后更新质心基准（world；供质心变化检测）
        d.lastComX = d.comWorldX();
        d.lastComY = d.comWorldY();
        d.lastComZ = d.comWorldZ();
    }

    /** ★ 阶段3：空间场景计算（tick + 4×step；静态陪体不参与积分——Rapier 天然）。
     *  ★ synchronized：native step，与主线程 getPose 互斥（防同场景并发）。 */
    private synchronized void stepSpaceScene(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null || sp.sceneHandle == 0L) return;
        try {
            final long scene = sp.sceneHandle;
            // ★ 2026-09-05 【三种力】step 前对每个空间成员施加升力/浮力（作用各自中心）
            for (final Integer mid : sp.members) {
                applyLiftAndBuoyancy(scene, physicalized.get(mid));
            }
            CryptandRapierNative.tick(scene, 1.0 / 20.0);
            for (int i = 0; i < 4; i++) {
                CryptandRapierNative.step(scene, 1.0 / 20.0 / 4.0);
            }
            // 碰撞探测（每 50 次；调试）
            if (++collisionProbeCounter >= 50) {
                collisionProbeCounter = 0;
                try {
                    final double[] col = CryptandRapierNative.clearCollisions(scene);
                    if (col != null && col.length > 0) {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[CryptandSable] SPACE collisions space={} count={} first=(a={},b={})",
                                spaceId, col.length / 15, (int) col[0], (int) col[1]);
                    }
                } catch (final Throwable ignored) {
                }
            }
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] SPACE step failed space={}: {}", spaceId, t.toString());
        }
    }

    /** ★ 阶段4：批量拉取该空间全部 body 位姿（一次 native 调用）→ Java 投影转换。
     *  ★ synchronized：读该空间场景（getPoseBatch），与主线程 getPose 同场景互斥。 */
    private synchronized void readSpacePoses(final int spaceId) {
        final PhysicalSpace sp = spaces.get(spaceId);
        if (sp == null || sp.sceneHandle == 0L) return;
        try {
            final long scene = sp.sceneHandle;
            final int[] cnt = new int[1];
            final double[] store = new double[Math.max(64, (sp.members.size() + 64) * 8)];
            CryptandRapierNative.getPoseBatch(scene, store, cnt);
            final int n = Math.min(cnt[0], store.length / 8);
            for (int i = 0; i < n; i++) {
                final int base = i * 8;
                final int id = (int) store[base];
                final PhysicalizedData d = physicalized.get(id);
                if (d == null) continue;
                // ★ 2026-09-06 【无 far】native 即主世界坐标（不转换）
                d.pose[0] = store[base + 1];
                d.pose[1] = store[base + 2];
                d.pose[2] = store[base + 3];
                d.pose[3] = store[base + 4];
                d.pose[4] = store[base + 5];
                d.pose[5] = store[base + 6];
                d.pose[6] = store[base + 7];
                // ★ 2026-09-05 【一世界一空间】合并拆分已放弃置区 → 不再提交绿框足迹
                //   （commitGreenFootprint 弃置；无聚类消费方）
            }
            // 空间整理：清理 empty 空间（如拆分后）；
            if (sp.members.isEmpty()) {
                destroySpaceScene(spaceId);
            }
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] SPACE getPoseBatch failed space={}: {}", spaceId, t.toString());
        }
    }

    /**
     * ★ 2026-09-02 mixin 冻结钩子：freeze 模式下替换 step() 主体——
     *  只发收集查询（冻结分支：allActiveBodies 持续发）不积分 → 结构不动 + 高亮可收集。
     */
    public synchronized void debugFreezeTick() {
        emitCollectQueries();   // 冻结分支（allActiveBodies 持续发）
    }

    /**
     * ★ 2026-09-01 出站收集查询：worker step 后按各 pending 体发 WorldCollectQuery。
     * 主线程每 tick 消费（SableServerBridge）→ WorldChunkUploader 收集 → markCollisionReady。
     * 查询带该体当前世界位置（冻结时取投影锚/初始区域；有快照取向快照）。
     */
    /**
     * ★ 2026-09-01 出站收集查询调度（按计算次数，用户设计）：
     *   - 每体【sendIntervalSteps】次计算发一次查询（首次立发）
     *   - 发送后【等待 timeoutSteps】次计算未收到 → 再发（重置等待，循环）
     *   - 主线程"空列表"也算收到（markCollisionReady 解冻）
     *   - 首次（awaitFirst）永不放弃；后续超总上限 → 停
     */
    private synchronized void emitCollectQueries() {
        // ★ 2026-09-05 分桶：每轮检测桶内结构离差 → 超阈值动态重居中（recenterScene）。
        maybeRecenter();
        // ★ 2026-09-05 【按物理空间批量拉位姿（用户定案）】物理计算完成后，一次 native
        //   调用返回场景全部 body 位姿 → 按空间分组更新该空间下所有结构的数据表
        //   （d.pose=投影/主世界坐标、d.spacePose=空间局部坐标、d.lastPose=速度差分基准）。
        //   ★ 替代原先【逐体 getPose】——每次 JNI 调用遍历场景。批量 = 每轮 1 次 JNI。
        final java.util.Map<Integer, double[]> batch = new java.util.HashMap<>();
        try {
            final long scene = requireScene();
            // 预分配：body 数×8（id + 7 分量）；余量 64 防 native 新 body 插入。
            final int est = physicalized.size() + 64;
            final int[] cnt = new int[1];
            final double[] store = new double[Math.max(64, est * 8)];
            CryptandRapierNative.getPoseBatch(scene, store, cnt);
            final int n = Math.min(cnt[0], store.length / 8);
            for (int i = 0; i < n; i++) {
                final int base = i * 8;
                final int id = (int) store[base];
                final double[] p = new double[7];
                System.arraycopy(store, base + 1, p, 0, 7);
                batch.put(id, p);
            }
        } catch (final Throwable ignored) {
            // 批量失败（如场景未就绪）→ 后续逐体 getPose 兜底（低概率）
        }
        // ★ 2026-09-04 ECS 化：一次遍历 physicalized 数据列表（不再维护独立集合）。
        final int radius = ConfigCryptandSable.SABLE_WORLD_COLLISION_RADIUS.get();
        final int sendInterval = Math.max(1, collectSendIntervalSteps);
        final int timeout = Math.max(1, collectTimeoutSteps);
        // ★ 2026-09-05 速度动态收集配置
        final boolean dyn = ConfigCryptandSable.SABLE_COLLECT_DYNAMIC_INTERVAL.get();
        final double fastSpeed = ConfigCryptandSable.SABLE_COLLECT_FAST_SPEED.get();
        final int slowMult = Math.max(1, ConfigCryptandSable.SABLE_COLLECT_SLOW_MULT.get());

        final java.util.List<Integer> toRemove = new java.util.ArrayList<>();
        final boolean anyPending = hasPending();
        for (final PhysicalizedData d : physicalized.values()) {
            final int rt = d.runtimeId;
            if (!d.active) continue;   // 未物理化/空结构 → 跳过
            // ★ 2026-09-05 【完全分桶 · 空间级跳过】该结构所属空间整体未加载
            //   （成员所在 chunk 列无一个已加载）→ 跳过该结构本轮更新（扫描/位姿/收集
            //   全部不发——空间不在世界视野，无需更新；加载后由 chunk 事件恢复）。
            if (isSpaceFullyUnloaded(d.spaceId)) {
                continue;
            }
            // ★ 2026-09-04 ECS：pending/awaitFirst/sendCounter/waitCounter/timeoutTotal 都在 data
            //   （原 collisionPending/collisionAwaitFirst/collect*Counters 引擎级集合已删）

            // ★ 2026-09-05 动态间隔：读实时位姿差分速度 → 快用基础间隔，慢用基础×慢倍数。
            //   ★ 无论 dyn 开关，都拉实时位姿写回 data.pose（materialize 移动检测基准）+
            //   更新速度缓存（dyn=false 时仅更新 pose/速度，间隔用固定值）。
            double[] ps = batch.get(rt);
            boolean pOk = ps != null && (ps[0] != 0 || ps[1] != 0 || ps[2] != 0);
            if (!pOk) {
                // ★ 2026-09-05 兜底：batch 缺该体（native 刚插入/被移除）→ 逐体拉一次
                try {
                    final double[] single = new double[7];
                    getPose(rt, single);
                    if (single[0] != 0 || single[1] != 0 || single[2] != 0) {
                        ps = single;
                        pOk = true;
                    }
                } catch (final Throwable ignored) {
                }
            }
            if (pOk) {
                // ★ 2026-09-05 【投影还原（用户：坐标必须是投影坐标非亚空间）】
                //   batch 是 native 原始【空间局部】坐标（桶局部）——写任何数据表之前
                //   必须 + 所属空间原点还原为【投影/主世界坐标】。⚠ 此投影与 getPose()
                //   完全一致（逐体 getPose 内部也做 toWorldFor）——两条路径统一。
                final double[] proj = projectBatchPose(rt, ps);
                double dx = proj[0] - d.lastPose[0];
                double dy = proj[1] - d.lastPose[1];
                double dz = proj[2] - d.lastPose[2];
                final double dist2 = dx * dx + dy * dy + dz * dz;
                // 每轮 = 1 次物理计算 ≈ 1/20 s（worker 步长）；速度 = 位移差×20 块/秒
                final double spd = Math.sqrt(dist2) * 20.0;
                // 指数平滑（α=0.5）：快速响应且抗尖峰
                d.speed2 = d.speed2 * 0.5 + spd * 0.5;
                System.arraycopy(proj, 0, d.lastPose, 0, 7);
                // ★ 2026-09-05 同步实时投影位姿到 data.pose（materialize 移动检测基准）
                System.arraycopy(proj, 0, d.pose, 0, Math.min(7, d.pose.length));
                // ★ 2026-09-05 【空间表长期维护】更新该结构空间内局部坐标（spacePose）：
                //   Java 空间表只记录“关系 + 局部坐标”不存碰撞数据（内存小、长期保持）。
                final PhysicalSpace sp = spaces.get(d.spaceId);
                if (sp != null) {
                    // ★ 2026-09-05 【绝对坐标】proj 即主世界绝对坐标（不再减空间原点）
                    d.spacePose[0] = proj[0];
                    d.spacePose[1] = proj[1];
                    d.spacePose[2] = proj[2];
                }
            }
            final int effInterval;
            if (dyn) {
                effInterval = d.speed2 > fastSpeed
                        ? sendInterval
                        : Math.max(1, sendInterval * slowMult);
                if (d.sendCounter % 40 == 0) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[CryptandSable] dynInterval rt={} speed={} interval={}",
                            rt, d.speed2, effInterval);
                }
            } else {
                effInterval = sendInterval;
            }

            // 发送间隔：累计到 effInterval 才发（第一次立发：计数器从 0 起 → merge 后=1）
            d.sendCounter++;
            boolean shouldSend = d.sendCounter >= effInterval;
            if (shouldSend) d.sendCounter = 0;

            // ★ 2026-09-05 【世界卡住修复】仅【冻结/首次】发收集查询——世界地形由
            //   onWorldChunkLoaded/onWorldBlockChanged（chunk 事件）+ 首次收集上传；
            //   解冻后【不再发】——否则 worker 每步发 → 主线程每 tick 全量
            //   collectWorldAtBounds（扫描 chunk + addWorldChunk）→ 主线程饱和
            //   → Chunk source 主线程执行器 FATAL → 世界卡死。
            final boolean frozen = d.pending || d.awaitFirst;
            if (!frozen) {
                continue;   // 解冻后：不发收集查询（增量由世界事件驱动）
            }

            // 超时处理（仅在将要发送时检查等待是否已超）
            boolean reSend = false;
            final boolean first = d.awaitFirst;
            if (anyPending || first) {
                // 冻结期（pending）或首次：正常发查询（不作超时检查；awaitFirst 永不放弃）
            } else if (shouldSend) {
                final int waitSoFar = d.waitCounter + (d.sendCounter == 0 ? 0 : 1);
                if (waitSoFar >= timeout) {
                    final int total = d.timeoutTotal + 1;
                    final int maxTotal = collectTimeoutMax;
                    if (maxTotal > 0 && total >= maxTotal) {
                        toRemove.add(rt);
                        continue;
                    }
                    d.timeoutTotal = total;
                    d.waitCounter = 0;
                    reSend = true;
                }
            }
            if (!shouldSend && !reSend) continue;
            //（等待计数器不做额外 +1；同原逻辑——超时看 waitCounter）
            // ★ 位置 = 结构【当前主世界物理位姿】（getPose 拉实时；不是 MOVED 初始！
            //   用户："收集坐标需要是实际物理结构在主世界的坐标"）
            //   bounds/范围 = 结构【初始半尺寸】（尺寸不变；旋转时用半径扩展）
            double cx = 0, cy = 0, cz = 0;
            double halfX = 0.5, halfY = 0.5, halfZ = 0.5;   // 半尺寸（初始时 1 块）
            boolean havePos = false;
            final java.util.UUID subId = CryptandSubLevelApi
                    .subLevelIdForRuntime(rt);
            // ① 当前物理位姿（官方引擎内部最新）
            try {
                final double[] poseStore = new double[7];
                getPose(rt, poseStore);
                if (poseStore[0] != 0 || poseStore[1] != 0 || poseStore[2] != 0) {
                    cx = poseStore[0]; cy = poseStore[1]; cz = poseStore[2];
                    havePos = true;
                }
            } catch (final Throwable ignored) {
            }
            // ② 半尺寸：MOVED 初始包围盒算（结构尺寸不变）
            if (subId != null) {
                final java.util.List<
                        CryptandSubLevelApi.PlacedBlockSnapshot> snaps =
                        CryptandSubLevelApi
                                .movedBlocks(subId);
                if (snaps != null && !snaps.isEmpty()) {
                    int bMinX = Integer.MAX_VALUE, bMinY = Integer.MAX_VALUE, bMinZ = Integer.MAX_VALUE;
                    int bMaxX = Integer.MIN_VALUE, bMaxY = Integer.MIN_VALUE, bMaxZ = Integer.MIN_VALUE;
                    for (final CryptandSubLevelApi.PlacedBlockSnapshot s
                            : snaps) {
                        if (s == null || s.worldPos() == null) continue;
                        final net.minecraft.core.BlockPos p = s.worldPos();
                        bMinX = Math.min(bMinX, p.getX());
                        bMinY = Math.min(bMinY, p.getY());
                        bMinZ = Math.min(bMinZ, p.getZ());
                        bMaxX = Math.max(bMaxX, p.getX());
                        bMaxY = Math.max(bMaxY, p.getY());
                        bMaxZ = Math.max(bMaxZ, p.getZ());
                    }
                    if (bMinX != Integer.MAX_VALUE) {
                        halfX = (bMaxX - bMinX + 1) * 0.5;
                        halfY = (bMaxY - bMinY + 1) * 0.5;
                        halfZ = (bMaxZ - bMinZ + 1) * 0.5;
                    }
                }
            }
            // ③ 无当前位姿（初始/冻结）→ 投影锚/MOVED 首块
            if (!havePos) {
                if (subId != null) {
                    final double[] anchor = CryptandSubLevelApi
                            .projectionAnchor(subId);
                    if (anchor != null && anchor.length >= 3) {
                        cx = anchor[0]; cy = anchor[1]; cz = anchor[2];
                        havePos = true;
                    } else {
                        final java.util.List<
                                CryptandSubLevelApi.PlacedBlockSnapshot> snaps =
                                CryptandSubLevelApi
                                        .movedBlocks(subId);
                        if (snaps != null && !snaps.isEmpty()) {
                            final net.minecraft.core.BlockPos p = snaps.get(0).worldPos();
                            if (p != null) {
                                cx = p.getX() + 0.5; cy = p.getY() + 0.5; cz = p.getZ() + 0.5;
                                havePos = true;
                            }
                        }
                    }
                }
            }
            // ④ 发送用 bounds = 当前位姿 ± (halfSize + 收集padding由主线程半径扩)
            final int minX = (int) Math.floor(cx - halfX);
            final int minY = (int) Math.floor(cy - halfY);
            final int minZ = (int) Math.floor(cz - halfZ);
            final int maxX = (int) Math.floor(cx + halfX);
            final int maxY = (int) Math.floor(cy + halfY);
            final int maxZ = (int) Math.floor(cz + halfZ);
            // ★ 2026-09-01 调试日志：发送收集查询（当前物理位姿 + 范围）
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] emitCollectQuery rt={} pos=({},{},{}) range=[{},{},{}..{},{},{}] "
                            + "half=({},{},{}) radius={} sendCount={} first={} wait={} totalTimeout={}",
                    rt, cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ,
                    halfX, halfY, halfZ, radius, shouldSend ? sendInterval : 0, first,
                    d.waitCounter, d.timeoutTotal);
            // ★ 2026-09-05 【扫描消息池】put 覆盖（每体最多一条未消费；强制替换）：
            //   worker 每计算步 emit → 主线程未消费前再 emit ⇒ 旧查询被新查询替换。
            // ★ 2026-09-07 特征 = 所属物理空间 UUID 拷贝（d.spaceId→sp.feature()）：
            //   主线程回传时核心据此校验所属周期；空间不在操作表/超时后到达 → 丢弃。
            final long feat = d.spaceId != 0
                    ? (spaces.get(d.spaceId) != null ? spaces.get(d.spaceId).feature() : 0L)
                    : 0L;
            final SableMessages.WorldCollectQuery q =
                    new SableMessages.WorldCollectQuery(
                            rt, minX, minY, minZ, maxX, maxY, maxZ, cx, cy, cz, radius, feat);
            pendingCollectQueries.put(rt, q);
            // ★ 2026-09-06 【每空间收集查询池】空间任务路径：按空间入队（阶段2 消费）
            putSpaceCollectQuery(d.spaceId, q);
        }
        // ★ 2026-09-05 【空间组批量推送】按物理空间把已发送查询分组（同空间结构一起提交，
        //   Rust 按空间批量处理/更新——空间组查询以空间号为键，主线程消费时能识别空间组）。
        for (final PhysicalSpace sp : spaces.values()) {
            final java.util.List<Object> group = new java.util.ArrayList<>();
            for (final int mem : sp.members) {
                final Object q = pendingCollectQueries.get(mem);
                if (q != null) group.add(q);
            }
            if (!group.isEmpty()) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] SPACE batch rt={} (space={} members={}, totalQuery={})",
                        group.size(), sp.id, sp.members.size(), pendingCollectQueries.size());
            }
        }
        for (final int rt : toRemove) {
            final PhysicalizedData d = physicalized.get(rt);
            if (d != null) {
                d.pending = false;
                d.awaitFirst = false;
                d.sendCounter = 0;
                d.waitCounter = 0;
                d.timeoutTotal = 0;
            }
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] collect total-timeout for rt={}; structure continues "
                            + "without world collision", rt);
        }
        // ★ 2026-09-06 【结构级陪体（用户：rapier 允许重叠，无需合并）】每结构独立陪体；
        //   扫描表变化（世界事件/收集）由 onWorldBlockChanged/collectWorldAtBounds 触发
        //   materializeScannedBlocks（结构级）；此处不再做空间级后处理。
    }

    /** ★ 2026-09-05 扫描消息池：单条收集查询（每体最多一条；强制替换后取走消费）。
     *  主线程每 tick 消费一次：取走该结构最新查询 → 收集 → 需要时重新 emit。 */
    public synchronized java.util.List<Object> drainCollectQueries() {
        if (pendingCollectQueries.isEmpty()) return java.util.Collections.emptyList();
        final java.util.List<Object> out = new java.util.ArrayList<>(pendingCollectQueries.values());
        pendingCollectQueries.clear();
        return out;
    }

    /** 物理体冻结（等待首次世界碰撞收集；collect 完成前不参与计算）。 */
    public synchronized void markCollisionPending(int runtimeId) {
        final PhysicalizedData d = physicalizedData(runtimeId);
        // ★ 2026-09-06 【竞态修复：物理化即刻 active】physicalize 主线程先调本方法、
        //   后异步 importBody → createStructureShapeBody。若不置 active=true，则在
        //   createSubLevel（native body 已建）与 data.active=true（方法末尾）之间的窗口内，
        //   spaceHasFrozenMembers 会因 !d.active 跳过该结构 → 空间误判无冻结 → step 积分
        //   新 body → 自由落体。置 active 后冻结检查立即生效（pending 已由本方法置 true）。
        d.active = true;
        d.pending = true;
        // ★ 2026-09-01 首次物理化：必须等到收集完成才解冻，永不因超时解除
        //   （用户："第一次物理化必须等到收集后才能解冻物理计算"）。
        d.awaitFirst = true;
        d.sendCounter = 0;
        d.waitCounter = 0;
        d.timeoutTotal = 0;
    }

    /** ★ 2026-09-05 【不动修复】世界收集完成 → 解冻（不再空扫描阻塞）。世界地形由
     *  uploadSection→addWorldChunk(global=true) 上传 main_level_chunks——结构 body 与
     *  世界体素碰撞由官方 dispatcher 处理。scannedBlocks 仅记录（供 diff/调试）；
     *  空扫描 ≠ 无地形（可能是周围全空气/已在平台上方）→ 解冻让结构自然下落。 */
    public synchronized void markCollisionReady(int runtimeId) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        if (d != null) {
            d.pending = false;
            d.awaitFirst = false;
            d.sendCounter = 0;
            d.waitCounter = 0;
            d.timeoutTotal = 0;
        }
        // ★ 2026-09-02 运算（step）开始前：从该结构扫描表【生成】ground 碰撞（幂等补漏）。
        // ★ 2026-09-06 【串行化】不再直接调 native materialize —— 置所属空间 dirty，
        //   由空间任务（pipelineLock 内）阶段2 在 step 前统一上传陪体。
        markSpaceDirty(runtimeId);
    }

    /**
     * ★ 2026-09-05 【凹形组合·陪体（compound 轴合并大 box）——唯一适配】
     * 世界陪体 = fixed 刚体 + 轴合并大 box collider 集（一次 createCompoundShapeBody）。
     * 相邻实心块贪心合并成最大 box（mergeMaxBox 上限），房间/门洞自然断开；排除结构
     * 自身占用格。Rapier 原生 box↔box 碰撞——不靠体素检测参数（无需 setCenterOfMass/
     * setLocalBounds/octree 三件套）。
     *
     * 触发：空间任务 PRE_PROCESS 对每个 dirty 成员调用；扫描指纹未变 → 跳过；500ms
     * 节流（世界方块事件频繁 → 延迟重建，防主线程被反复全量重建淹没）。
     *
     * @return 合并 box 数（0=无变化/无数据；-1=陪体创建失败）
     */
    public synchronized int materializeCompoundCompanion(final int runtimeId) {
        final PhysicalizedData data = physicalized.get(runtimeId);
        if (data == null || data.scannedBlocks == null || data.scannedBlocks.isEmpty()) {
            return 0;
        }
        // 扫描指纹变化检测（无变化 → 保持原陪体）
        final long scanFP = scanFingerprint(data.scannedBlocks);
        if (data.rebuildStamp != -1L && data.scanFingerprint == scanFP) {
            return 0;
        }
        // 重建节流（500ms）
        final long nowN = System.nanoTime();
        if (data.rebuildStamp != -1L && nowN - data.lastRebuildNanos < 500_000_000L) {
            return 0;
        }
        // 每空间独立场景；场景未建 → 跳过（等空间任务阶段1 创建后下轮重试）
        final PhysicalSpace spM = data.spaceId != 0 ? spaces.get(data.spaceId) : null;
        if (data.spaceId != 0 && (spM == null || spM.sceneHandle == 0L)) {
            return 0;
        }
        final long scM = spM != null && spM.sceneHandle != 0L ? spM.sceneHandle : requireScene();
        // 陪体 id（每结构独立）
        final int vid = data.shapeCompanionId != 0
                ? data.shapeCompanionId : --shapeCompanionIdCounter;
        data.shapeCompanionId = vid;
        // 实心块（世界坐标）→ 排除结构自身占用格
        final java.util.Set<Long> self = structureSolidSet(data);
        // ★ 2026-09-05 【材质感知】扫描表 → 材质表（packPos → materialId），排除结构自身格
        final java.util.Map<Long, Integer> scannedMat =
                CompoundShapeMerger.solidMat(data.scannedBlocks);
        final java.util.Map<Long, Integer> world = new java.util.HashMap<>();
        for (final java.util.Map.Entry<Long, Integer> e : scannedMat.entrySet()) {
            if (self.contains(e.getKey())) continue;
            world.put(e.getKey(), e.getValue());
        }
        if (world.isEmpty()) return 0;
        // ★ 2026-09-06 【sable 同构】陪体 body 原点 = 空间 origin（主世界）；盒心 = 局部
        //   （主世界盒心 − origin；toLocalFor 计算）；native 碰撞 = body(主世界) + 局部 = 主世界
        final double oxC = spM != null ? spM.origin[0] : 0;
        final double oyC = spM != null ? spM.origin[1] : 0;
        final double ozC = spM != null ? spM.origin[2] : 0;
        final java.util.List<CompoundShapeMerger.MergedBox> mboxes =
                CompoundShapeMerger.mergeMat(world, this.mergeMaxBox);
        if (mboxes.isEmpty()) return 0;
        final double[] halfAndCenter = new double[mboxes.size() * 6];
        for (int i = 0; i < mboxes.size(); i++) {
            final int[] b = mboxes.get(i).box();
            halfAndCenter[i * 6] = (b[3] - b[0] + 1) * 0.5;
            halfAndCenter[i * 6 + 1] = (b[4] - b[1] + 1) * 0.5;
            halfAndCenter[i * 6 + 2] = (b[5] - b[2] + 1) * 0.5;
            // ★ 2026-09-06 【sable 同构】盒心 = 主世界盒心 − origin（局部，相对 body 原点）
            halfAndCenter[i * 6 + 3] = (b[0] + b[3] + 1) * 0.5 - oxC;
            halfAndCenter[i * 6 + 4] = (b[1] + b[4] + 1) * 0.5 - oyC;
            halfAndCenter[i * 6 + 5] = (b[2] + b[5] + 1) * 0.5 - ozC;
        }
        // 重建：删旧陪体（removeShapeBody 配对 createCompoundShapeBody）→ 重建
        if (data.rebuildStamp != -1L) {
            try {
                CryptandRapierNative.removeShapeBody(scM, vid);
            } catch (final Throwable ignored) {
            }
        }
        try {
            // 结构级综合摩擦/弹性（box 体积加权摩擦 + max 弹性）——世界地形材质保真
            final double[] fr = CompoundShapeMerger.compositeSurfaceProps(mboxes,
                    id -> physProps(id).friction(), id -> physProps(id).restitution());
            // fixed 刚体 + density 0 box 集（fixed 无质量概念；登记 shape 缓存）
            // ★ 2026-09-05 每 box 材质系数（per-box friction/restitution，native 扩展）
            // ★ 2026-09-06 【sable 同构】陪体 body 原点 = 空间 origin（主世界）；盒心局部
            CryptandRapierNative.createCompoundShapeBody(scM, vid, 1, 0.0, mboxes.size(),
                    halfAndCenter, boxCoeffs(mboxes), fr[0], fr[1],
                    new double[]{oxC, oyC, ozC, 0, 0, 0, 1});
            data.friction = fr[0];
            data.restitution = fr[1];
        } catch (final Throwable t) {
            return -1;
        }
        data.rebuildStamp = System.currentTimeMillis();
        data.scanFingerprint = scanFP;
        data.lastRebuildNanos = nowN;
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] materializeCompoundCompanion rt={} -> companion={} boxes={} " +
                        "solidBlocks={} (compound axis-merged, maxBox={})",
                runtimeId, vid, mboxes.size(), world.size(), this.mergeMaxBox);
        return mboxes.size();
    }


    /**
     * ---- 弃置区（凹形组合统一后不再使用；SABLE_MERGE_AXIS_ALIGNED=false 时可能回退）----
     * ★ 2026-09-05 【空间级后处理】体素陪体路线：空间内全部成员扫描块并集 → Rust 上传。
     * 已被 materializeCompoundCompanion（轴合并大 box）取代。
     */
    public synchronized int materializeScannedBlocks(final int runtimeId) {
        final PhysicalizedData data = physicalized.get(runtimeId);
        if (data == null || data.scannedBlocks.isEmpty()) return 0;
        // ★ 2026-09-03 原生 shape 直接碰撞：把扫描世界方块构建为【每结构独立】固定体素陪体。
        if (ConfigCryptandSable.SABLE_SHAPE_COLLISION.get()) {
            // ★ 2026-09-06 【结构级陪体（用户：rapier 允许重叠，不需要合并）】
            //   每个结构独立陪体（fixed voxel LevelCollider）+ 独立 scannedBlocks；
            //   结构间重叠区块 rapier 天然处理（独立 chunk_map/octree 求交）。
            final long scanFP = scanFingerprint(data.scannedBlocks);
            final boolean scanChanged = data.rebuildStamp != -1L
                    && data.scanFingerprint != scanFP;
            // ★ 2026-09-05 【重建节流】500ms 内不重复全量重建（世界方块事件频繁 → 合并
            //   延迟重建——防主线程被反复 4000 块重建淹没冻结/等待变长）。
            if (data.rebuildStamp != -1L && !scanChanged) {
                return 0;   // 扫描未变 → 跳过（保持原陪体）
            }
            final long nowN = System.nanoTime();
            if (data.rebuildStamp != -1L && nowN - data.lastRebuildNanos < 500_000_000L) {
                return 0;   // 节流窗口内 → 跳过（下轮/事件后重试）
            }
            // ★ 2026-09-06 【每空间独立场景】陪体挂到【所属空间场景】（禁止跨空间共享）
            final PhysicalSpace spM = data.spaceId != 0 ? spaces.get(data.spaceId) : null;
            // ★ 2026-09-06 【串行化·防错场景】已分空间但场景未创建（阶段1 未跑/空间迁移
            //   间隙）→ 跳过（不 fallback 全局场景！否则陪体挂到全局场景，结构在空间场景
            //   计算 → 无地面 → 持续下坠“飞走”）。等空间任务阶段1 创建后下一轮 dirty 重试。
            if (data.spaceId != 0 && (spM == null || spM.sceneHandle == 0L)) {
                return 0;
            }
            final long scM = spM != null && spM.sceneHandle != 0L ? spM.sceneHandle : requireScene();
            // ★ 2026-09-06 结构级陪体 id（每结构独立；不复用空间共享——重叠交给 rapier）
            final Integer vid = data.shapeCompanionId != 0
                    ? data.shapeCompanionId : --shapeCompanionIdCounter;
            data.shapeCompanionId = vid;
            // ★ 2026-09-06 重建：删旧陪体（removeSubLevel 配对 createSubLevel）→ 重建
            if (data.rebuildStamp != -1L) {
                try {
                    CryptandRapierNative.removeSubLevel(scM, vid);
                } catch (final Throwable ignored) {
                }
            }
            try {
                CryptandRapierNative.createSubLevel(scM, vid,
                        new double[]{0, 0, 0, 0, 0, 0, 1}, true);
            } catch (final Throwable t) {
                return -1;
            }
            final int added = uploadCompanionSections(vid, data.scannedBlocks, scM);
            data.rebuildStamp = System.currentTimeMillis();
            data.scanFingerprint = scanFP;
            data.lastRebuildNanos = nowN;
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] materializeCompanion rt={} -> companionBody={} "
                            + "sections={} boxSet={} (voxel, per-structure)",
                    runtimeId, vid, added, data.companionBoxes.size());
            return added;
        }
        // ★ 2026-09-04 【全 shape】voxel 分支已删除（shape 模式下恒走上方 shape 分支）。
        return 0;
    }

    /** ---- 弃置区（体素陪体上传；compound 取代，仅供排查）----
     * ★ 2026-09-06 【结构级陪体上传】该结构扫描块（局部 section）→ Rust
     *  addChunk(global=false id=陪体)；并 setCenterOfMass/setLocalBounds（Rust 必需）。
     *  ★ 2026-09-06 加 scene 参数：【每空间独立场景】陪体挂到空间场景。 */
    private int uploadCompanionSections(final int vid,
                                        final java.util.Map<SectionKey, int[]> scanned,
                                        final long scene) {
        // ★ 2026-09-05 该结构空间原点（局部 section 基准）
        int oSecX = 0, oSecY = 0, oSecZ = 0;
        for (final PhysicalizedData d : physicalized.values()) {
            if (d.shapeCompanionId == vid) {
                final PhysicalSpace sp = spaces.get(d.spaceId);
                if (sp != null) {
                    oSecX = (int) Math.floor(sp.origin[0]) >> 4;
                    oSecY = (int) Math.floor(sp.origin[1]) >> 4;
                    oSecZ = (int) Math.floor(sp.origin[2]) >> 4;
                }
                break;
            }
        }
        int added = 0;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final java.util.Map.Entry<SectionKey, int[]> e : scanned.entrySet()) {
            final int[] c = e.getValue();
            if (c == null) continue;
            final int lx = e.getKey().x() - oSecX;
            final int ly = e.getKey().y() - oSecY;
            final int lz = e.getKey().z() - oSecZ;
            try {
                CryptandRapierNative.addChunk(scene, lx, ly, lz, c, false, vid);
                added++;
            } catch (final Throwable ignored) {
            }
            // 局部块范围（setLocalBounds 用；含 (lx<<4)+bx）
            final int sx = lx << 4, sy = ly << 4, sz = lz << 4;
            for (int by = 0; by < 16; by++) {
                for (int bz = 0; bz < 16; bz++) {
                    for (int bx = 0; bx < 16; bx++) {
                        if (c[bx + (bz << 4) + (by << 8)] != 0) {
                            if (sx + bx < minX) minX = sx + bx;
                            if (sy + by < minY) minY = sy + by;
                            if (sz + bz < minZ) minZ = sz + bz;
                            if (sx + bx > maxX) maxX = sx + bx;
                            if (sy + by > maxY) maxY = sy + by;
                            if (sz + bz > maxZ) maxZ = sz + bz;
                        }
                    }
                }
            }
        }
        // ★ 2026-09-06 ❗陪体必须 setCenterOfMass/setLocalBounds（world_vs_world 需要）
        if (minX != Integer.MAX_VALUE) {
            final int comX = (minX + maxX + 1) >> 1;
            final int comY = (minY + maxY + 1) >> 1;
            final int comZ = (minZ + maxZ + 1) >> 1;
            try {
                CryptandRapierNative.setCenterOfMass(scene, vid, comX, comY, comZ);
                CryptandRapierNative.setLocalBounds(scene, vid,
                        minX, minY, minZ, maxX, maxY, maxZ);
            } catch (final Throwable ignored) {
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] companion com=({},{},{}) lb=({},{},{})..({},{},{})",
                    comX, comY, comZ, minX, minY, minZ, maxX, maxY, maxZ);
        }
        return added;
    }

    /** 扫描表指纹：section 数 + 各 section 非零格计数混合（变化快速检测；碰撞用全量 box 集）。 */
    private static long scanFingerprint(final java.util.Map<SectionKey, int[]> scanned) {
        long fp = 0;
        if (scanned == null) return fp;
        for (final java.util.Map.Entry<SectionKey, int[]> e : scanned.entrySet()) {
            final int[] c = e.getValue();
            if (c == null) continue;
            long n = 0;
            for (final int v : c) {
                if (v != 0) n++;
            }
            fp = fp * 31 + (e.getKey().x() * 73856093L
                    + e.getKey().y() * 19349663L + e.getKey().z() * 83492791L) + n;
        }
        return fp;
    }

    /** ---- 弃置区（每格 1×1×1 box 增量陪体；compound 轴合并大 box 取代，仅供排查）----
     * ★ 2026-09-05 增量同步 shape 陪体：diff 当前扫描表 vs 已挂载 box 集，只增删变化。
     *  （官方 addChunk/removeChunk/changeBlock 的 shape 等价：单 box 粒度增量。）
     *  ★ 2026-09-06 【每空间独立场景】陪体操作挂到所属空间场景。 */
    public synchronized void syncShapeCompanion(final int runtimeId) {
        final PhysicalizedData data = physicalized.get(runtimeId);
        if (data == null || data.shapeCompanionId == 0) return;
        final PhysicalSpace spD = data.spaceId != 0 ? spaces.get(data.spaceId) : null;
        final long scene = spD != null && spD.sceneHandle != 0L ? spD.sceneHandle : requireScene();
        final int vid = data.shapeCompanionId;
        final java.util.List<long[]> blocks = solidBlocks(data.scannedBlocks);
        final java.util.Set<Long> self = structureSolidSet(data);
        final java.util.Set<Long> wanted = new java.util.HashSet<>();
        final int cap = 200_000;
        for (final long[] b : blocks) {
            final long key = packPos(b[0], b[1], b[2]);
            if (self.contains(key)) continue;
            wanted.add(key);
            if (wanted.size() >= cap) break;
        }
        // 删除集：companionBoxes - wanted；新增集：wanted - companionBoxes
        final java.util.List<Long> toRemove = new java.util.ArrayList<>();
        for (final Long key : data.companionBoxes) {
            if (!wanted.contains(key)) toRemove.add(key);
        }
        final java.util.List<Long> toAdd = new java.util.ArrayList<>();
        for (final Long key : wanted) {
            if (!data.companionBoxes.contains(key)) toAdd.add(key);
        }
        // ★ 2026-09-05 用户定案：【diff 只要有一个方块变化就更新】，保证完全最新。
        //   不再按 diff 大小阈值回退全量重建——逐个 add/removeShapeColliderAt 增量，
        //   任何单格变化都立即反映到 Rust 物理空间（最新优先于批量效率）。
        if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] syncShapeCompanion rt={} delta add={} remove={} (boxSet {} -> {})",
                    runtimeId, toAdd.size(), toRemove.size(),
                    data.companionBoxes.size(), data.companionBoxes.size() + toAdd.size() - toRemove.size());
        }
        final double[] box = new double[]{0.5, 0.5, 0.5};
        final double fx = 0.6, re = 0.0;
        // 新增
        for (final Long key : toAdd) {
            final long k = key;
            // ★ 2026-09-06 21 位有符号补码（方块主世界坐标，可负）
            int x = (int) ((k >> 42) & 0x1FFFFF);
            int y = (int) ((k >> 21) & 0x1FFFFF);
            int z = (int) (k & 0x1FFFFF);
            if (x >= 0x100000) x -= 0x200000;
            if (y >= 0x100000) y -= 0x200000;
            if (z >= 0x100000) z -= 0x200000;
            // ★ 2026-09-06 陪体在 far 处生成：X/Z 盒心 far；Y 主世界
            try {
                CryptandRapierNative.addShapeColliderAt(scene, vid, 2, box, fx, re,
                        toLocalFor(runtimeId, x, 0) + 0.5,
                        toLocalFor(runtimeId, y, 1) + 0.5,
                        toLocalFor(runtimeId, z, 2) + 0.5);
            } catch (final Throwable ignored) {
            }
            data.companionBoxes.add(key);
        }
        // 删除
        for (final Long key : toRemove) {
            final long k = key;
            // ★ 2026-09-06 21 位有符号补码
            int x = (int) ((k >> 42) & 0x1FFFFF);
            int y = (int) ((k >> 21) & 0x1FFFFF);
            int z = (int) (k & 0x1FFFFF);
            if (x >= 0x100000) x -= 0x200000;
            if (y >= 0x100000) y -= 0x200000;
            if (z >= 0x100000) z -= 0x200000;
            try {
                CryptandRapierNative.removeShapeColliderAt(scene, vid,
                        toLocalFor(runtimeId, x, 0) + 0.5,
                        toLocalFor(runtimeId, y, 1) + 0.5,
                        toLocalFor(runtimeId, z, 2) + 0.5);
            } catch (final Throwable ignored) {
            }
            data.companionBoxes.remove(key);
        }
    }

    /** section 体素数据是否含实心（非全 0）。 */
    private static boolean hasNonZero(final int[] a) {
        if (a == null) return false;
        for (final int v : a) {
            if (v != 0) return true;
        }
        return false;
    }

    /** 收集完成回调（主线程 collect 成功后调用；附带该体的世界位置/半径以消除超时）。 */
    public synchronized void markCollisionReadyOrTimeout(int runtimeId, boolean collected) {
        if (collected) {
            markCollisionReady(runtimeId);
        } else {
            final PhysicalizedData d = physicalized.get(runtimeId);
            if (d != null && d.awaitFirst) {
                // 首次收集未完成：必须继续等（永不因超时解冻）；等待计数由 emit 调度处理
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] first collect not done for rt={} (await; "
                                + "wait={})", runtimeId, d.waitCounter);
            }
            // 后续收集更新：等待计数（emit 调度中累计；此处仅触发一轮重试）
            // 超过总上限 → 从 pending 移除（在 emit 中处理）
        }
    }

    /** ★ 2026-09-06 【每空间场景 handle 助手】取结构所属空间场景（无 → 全局场景）。 */
    private long sceneFor(final int runtimeId) {
        final PhysicalizedData d = physicalized.get(runtimeId);
        final PhysicalSpace sp = d != null && d.spaceId != 0 ? spaces.get(d.spaceId) : null;
        return sp != null && sp.sceneHandle != 0L ? sp.sceneHandle : requireScene();
    }

    /** 施加力（world 坐标；rel = 相对质心偏移）。 */
    public synchronized void applyForce(final int runtimeId, final double[] rel,
                                        final double[] force) {
        CryptandRapierNative.applyForce(sceneFor(runtimeId), runtimeId, rel[0], rel[1], rel[2],
                force[0], force[1], force[2], true);
    }

    public synchronized void applyImpulse(final int runtimeId, final double[] impulse) {
        CryptandRapierNative.applyForce(sceneFor(runtimeId), runtimeId, 0, 0, 0,
                impulse[0], impulse[1], impulse[2], true);
    }

    public synchronized void applyTorque(final int runtimeId, final double[] torque) {
        CryptandRapierNative.applyForceAndTorque(sceneFor(runtimeId), runtimeId, 0, 0, 0,
                torque[0], torque[1], torque[2], true);
    }

    public synchronized void wakeUp(final int runtimeId) {
        CryptandRapierNative.wakeUpObject(sceneFor(runtimeId), runtimeId);
    }

    public synchronized void teleport(final int runtimeId, final double[] pose) {
        final long scene = sceneFor(runtimeId);
        // ★ 2026-09-04 【全 shape】shape 体用主世界坐标 → 不 far 换算（直接传主世界位姿）。
        // ★ 2026-09-05 多空间：主世界 → 所属空间局部（native 存局部；每结构独立空间）。
        CryptandRapierNative.teleportObject(scene, runtimeId,
                toLocalFor(runtimeId, pose[0], 0), toLocalFor(runtimeId, pose[1], 1),
                toLocalFor(runtimeId, pose[2], 2),
                pose[3], pose[4], pose[5], pose[6]);
    }

    /** ★ 2026-09-05 【对齐官方】世界静态体素上传（global=true → main_level_chunks+
     *  octree_chunks）。§ 必须用【局部 section】（主世界 section − 所属空间原点 section）
     *  ——官方 dispatch 查询用局部 st>>4 与 body pose 一致。 */
    public synchronized void addWorldChunk(final int x, final int y, final int z, final int[] chunk) {
        final long scene = requireScene();
        CryptandRapierNative.addChunk(scene, x, y, z, chunk, true, -1);
        worldSections.add(new SectionKey(x, y, z));
    }

    /** ★ 2026-09-05 【对齐官方】结构自身体素挂载（global=false id=结构）→ own chunk_map+
     *  octree。§ 用局部 section。 */
    public synchronized void addChunkToBody(final int runtimeId,
                                             final int x, final int y, final int z,
                                             final int[] chunk) {
        // ★ 2026-09-06 【每空间独立场景】结构体素挂到所属空间场景。
        final PhysicalizedData dCB = physicalized.get(runtimeId);
        final PhysicalSpace spCB = dCB != null && dCB.spaceId != 0 ? spaces.get(dCB.spaceId) : null;
        final long scene = spCB != null && spCB.sceneHandle != 0L ? spCB.sceneHandle : requireScene();
        CryptandRapierNative.addChunk(scene, x, y, z, chunk, false, runtimeId);
    }

    /** ★ 2026-09-05 【对齐官方】结构更新局部 bounds（体素模型 setLocalBounds 由
     *  初始化链设置；此处兼容保留，no-op）。 */
    public synchronized void updateBodyBounds(final int runtimeId, final int[] bounds) {
        // voxel 模型 bounds 在创建链已设（setLocalBounds）；移动时无需更新 bounds
    }

    /** 世界静态体素块移除（global=true）。 */
    public synchronized void removeWorldChunk(final int x, final int y, final int z) {
        final long scene = requireScene();
        CryptandRapierNative.removeChunk(scene, x, y, z, true);
        worldSections.remove(new SectionKey(x, y, z));
    }

    /** ★ 2026-09-02 清除全部 global 世界 section（main_level_chunks 残留；逐节 removeWorldChunk）。 */
    public synchronized void clearWorldSections() {
        final java.util.List<SectionKey> keys = new java.util.ArrayList<>(worldSections);
        for (final SectionKey key : keys) {
            try {
                CryptandRapierNative.removeChunk(requireScene(), key.x(), key.y(),
                        key.z(), true);
            } catch (final Throwable ignored) {
            }
        }
        final int n = worldSections.size();
        worldSections.clear();
        if (n > 0) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] clearWorldSections removed {} global sections", n);
        }
    }

    // ===== ★ 2026-09-01 方案B：临时【虚拟世界体】（用户："收集后建立成临时虚拟物理结构
    //        然后交给引擎去做碰撞检测"） =====
    //  - 备用 ID 区（负 < -1000000，不与 runtimeId 撞）
    //  - createSubLevel + addChunk(global=false) 挂它 → 复用官方【体 vs 体】碰撞
    //  - setMassProperties(质量巨大) → 结构撞不动它 → 视作静态（不动）
    //  - bounds 大包围盒 → local AABB 覆盖收集区域

    /** ★ 2026-09-04 【全 shape】静态陪体相关集合已废弃（shape 陪体在 PhysicalizedData）。 */
    private static int staticBodyIdCounter = -10_000_000;

    /** ★ 备用虚拟世界体 id（负值区；★ 保留兼容——旧调用方 nextStaticWorldBodyId）。 */
    public static int nextStaticWorldBodyId() {
        return staticBodyIdCounter--;
    }

    /** ★ 2026-09-04 【全 shape】体素陪体已废弃：保留签名 no-op（shape 陪体由
     *  ensureShapeCompanion 创建；本方法仅返回 -1）。 */
    public synchronized int ensureStaticCompanion(final int runtimeId, final int[] bounds) {
        return -1;
    }

    // ★ 2026-09-04 【全 shape】voxel 静态陪体集合（staticWorldBodies/staticCompanions/
    //   companionSections）已废弃删除——shape 陪体在 PhysicalizedData（shapeCompanionId）。

    /** ★ 2026-09-04 【全 shape】虚拟世界体已废弃：保留签名 no-op。 */
    public synchronized void createStaticWorldBody(final int virtualId, final int[] bounds,
                                                   final double[] structurePose) {
        // voxel static world body removed (shape mode)
    }

    /** ★ 2026-09-04 【全 shape】虚拟体 finalize 已废弃：保留签名 no-op。 */
    public synchronized void finalizeStaticWorldBody(final int virtualId, final int[] bounds) {
        // voxel static world body removed (shape mode)
    }

    // ★ 2026-09-03 移除 pinStaticWorldBodies：静态陪体走真静态物理通道
    //   （createStaticWorldBody → createSubLevel(..., is_static=true)），
    //   不再用 teleport 回原点 + 清零速度的"伪装静态"兜底。is_static=true
    //   的 fixed body 不受重力/不积分，天然固定——无需每 tick 拉回。

    /** 碰撞事件（若有）：返回双数组（15 一组；MVP 暂不消费）。 */
    public synchronized double[] clearCollisions() {
        final long scene = requireScene();
        return CryptandRapierNative.clearCollisions(scene);
    }

    // ===== ★ 2026-09-02 形状刚体 / 多刚体类型 / 柔体（shapes.rs 新增 native） =====
    // bodyType: 0=dynamic 1=fixed(真静态) 2=kinematic-position 3=kinematic-velocity
    // shapeType: 0=ball[radius] 1=capsule[halfHeight,radius] 2=box[halfX,halfY,halfZ]
    //            3=convex hull[nv, (x,y,z)*nv]
    // 用途：圆形/多边形结构（球·柱·多面体/网格体）、NC 多刚体类型、柔体（粒子+距离弹簧）。
    // 位姿为主世界坐标（无需 far 换算：对 shape 体我们以主世界小坐标模拟，量级不会碰
    // 2^21 边界；与场景 ground 交互的 shape 体如需体素碰撞可用 addShapeBody 直接身体素）。
    public synchronized void createShapeBody(final int runtimeId, final int bodyType,
                                             final double mass, final int shapeType,
                                             final double[] params, final double friction,
                                             final double restitution, final double[] pose) {
        final long scene = requireScene();
        CryptandRapierNative.createShapeBody(scene, runtimeId, bodyType, mass, shapeType, params,
                friction, restitution, pose);
    }

    /** ★ 2026-09-05 【官方初始化链】设置结构 body 的质心（局部坐标，相对 body 原点）。
     *  官方 createSubLevel 后必须调——find_collision_pairs / AABB 依赖 center_of_mass。 */
    public synchronized void setStructureCenterOfMass(final int runtimeId,
                                                      final double cx, final double cy,
                                                      final double cz) {
        final long scene = requireScene();
        CryptandRapierNative.setCenterOfMass(scene, runtimeId, cx, cy, cz);
    }

    /** ★ 2026-09-05 【官方初始化链】设置结构 body 的局部 bounds（块坐标，局部=
     *  主世界 bounds − 空间原点）。官方 createSubLevel 后必须调——setLocalBounds
     *  会构建结构体 octree（find_collision_pairs 遍历用；None → panic/穿透）。 */
    public synchronized void setStructureLocalBounds(final int runtimeId,
                                                     final int minX, final int minY,
                                                     final int minZ, final int maxX,
                                                     final int maxY, final int maxZ) {
        final long scene = requireScene();
        CryptandRapierNative.setLocalBounds(scene, runtimeId,
                minX, minY, minZ, maxX, maxY, maxZ);
    }

    public synchronized void createTrimeshShapeBody(final int runtimeId, final int bodyType,
                                                    final double mass, final double[] vertices,
                                                    final int[] indices, final double friction,
                                                    final double restitution, final double[] pose) {
        final long scene = requireScene();
        CryptandRapierNative.createTrimeshShapeBody(scene, runtimeId, bodyType, mass, vertices, indices,
                friction, restitution, pose);
    }

    /** 移除任意形状刚体（含 createBox/createShapeBody/createTrimeshShapeBody）。 */
    public synchronized void removeShapeBody(final int runtimeId) {
        final long scene = requireScene();
        CryptandRapierNative.removeShapeBody(scene, runtimeId);
    }

    /** 给已有 shape 刚体追加组合 collider（多边形/圆形结构拼合；无质量贡献）。 */
    public synchronized void addShapeCollider(final int runtimeId, final int shapeType,
                                              final double[] params, final double friction,
                                              final double restitution) {
        final long scene = requireScene();
        CryptandRapierNative.addShapeCollider(scene, runtimeId, shapeType, params, friction, restitution);
    }

    /** 给已有 shape 刚体在 (px,py,pz) 偏移处追加 collider（每格完整方块；无质量贡献）。 */
    public synchronized void addShapeColliderAt(final int runtimeId, final int shapeType,
                                                final double[] params, final double friction,
                                                final double restitution,
                                                final double px, final double py, final double pz) {
        final long scene = requireScene();
        CryptandRapierNative.addShapeColliderAt(scene, runtimeId, shapeType, params, friction, restitution,
                px, py, pz);
    }

    // ===== ★ 2026-09-03 【原生 shape 直接碰撞】结构体/地面构建器 =====
    // 用户定案："走原生 shape 直接碰撞，就像真正世界那种，而不是检测参数；物理空间 =
    //   创建一个真实世界/房间，里面加入各种形状的物理结构然后做碰撞"。
    //   - 结构 = dynamic 刚体 + 每格一个完整 1x1x1 box collider（addShapeColliderAt@方块中心）
    //   - 世界/陪体 = fixed 刚体（trimesh）静态承接
    //   - shape 体全部用【主世界小坐标】（无 far 换算）。Rapier 原生 box↔trimesh 直接碰撞，
    //     相邻贴合/静止由引擎处理——不靠体素检测参数（prediction/配对半径/octree 对齐）。

    /** 收集 SectionUpload 列表的实心方块【真实世界坐标】（[x,y,z]）。
     *  buildOfficialSections 的 secX=(boundMinX>>4)+secIdx → secX*16+bx 与真实世界坐标
     *  差 boundMinX mod 16 → 传入 minBounds（结构 boundMinX/Y/Z）补回偏移，否则 shape
     *  collider 与视觉方块错位。minBounds==null → 偏移 0（含推断 fallback）。 */
    private static java.util.List<long[]> solidBlocks(final java.util.List<SectionUpload> sections,
                                                      final int[] minBounds) {
        final java.util.List<long[]> out = new java.util.ArrayList<>();
        if (sections == null) return out;
        final int foX = minBounds != null && minBounds.length >= 1 ? (minBounds[0] & 15) : 0;
        final int foY = minBounds != null && minBounds.length >= 2 ? (minBounds[1] & 15) : 0;
        final int foZ = minBounds != null && minBounds.length >= 3 ? (minBounds[2] & 15) : 0;
        for (final SectionUpload sec : sections) {
            final int[] c = sec.chunk();
            if (c == null) continue;
            final int sx = (sec.secX() << 4) + foX, sy = (sec.secY() << 4) + foY;
            final int sz = (sec.secZ() << 4) + foZ;
            for (int by = 0; by < 16; by++) {
                for (int bz = 0; bz < 16; bz++) {
                    for (int bx = 0; bx < 16; bx++) {
                        if (c[bx + (bz << 4) + (by << 8)] != 0) {
                            out.add(new long[]{sx + bx, sy + by, sz + bz});
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 收集扫描表（SectionKey, int[]）的实心方块世界坐标（[x,y,z]）。 */
    private static java.util.List<long[]> solidBlocks(
            final java.util.Map<SectionKey, int[]> scanned) {
        final java.util.List<long[]> out = new java.util.ArrayList<>();
        if (scanned == null) return out;
        for (final java.util.Map.Entry<SectionKey, int[]> e : scanned.entrySet()) {
            final int[] c = e.getValue();
            if (c == null) continue;
            final SectionKey k = e.getKey();
            final int sx = k.x() << 4, sy = k.y() << 4, sz = k.z() << 4;
            for (int by = 0; by < 16; by++) {
                for (int bz = 0; bz < 16; bz++) {
                    for (int bx = 0; bx < 16; bx++) {
                        if (c[bx + (bz << 4) + (by << 8)] != 0) {
                            out.add(new long[]{sx + bx, sy + by, sz + bz});
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 从 sections 推算局部 bounds（六值 minX..maxX；与 createBody 同基准——主世界块坐标）。 */
    private static int[] inferBounds(final java.util.List<SectionUpload> sections) {
        if (sections == null || sections.isEmpty()) return new int[]{0, 0, 0, 0, 0, 0};
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final SectionUpload sec : sections) {
            final int[] c = sec.chunk();
            if (c == null) continue;
            final int sx = sec.secX() << 4, sy = sec.secY() << 4, sz = sec.secZ() << 4;
            for (int by = 0; by < 16; by++) {
                for (int bz = 0; bz < 16; bz++) {
                    for (int bx = 0; bx < 16; bx++) {
                        if (c[bx + (bz << 4) + (by << 8)] != 0) {
                            final int wx = sx + bx, wy = sy + by, wz = sz + bz;
                            if (wx < minX) minX = wx;
                            if (wy < minY) minY = wy;
                            if (wz < minZ) minZ = wz;
                            if (wx > maxX) maxX = wx;
                            if (wy > maxY) maxY = wy;
                            if (wz > maxZ) maxZ = wz;
                        }
                    }
                }
            }
        }
        return minX == Integer.MAX_VALUE ? new int[]{0, 0, 0, 0, 0, 0}
                : new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * ★ 2026-09-05 【对齐官方】原生体素管线：创建动态结构体 = createSubLevel（动态
     *  LevelCollider 体素世界碰撞器，查 marten voxel）+ addChunk(global=false, id) 挂载
     *  结构自身的体素数据。官方 dispatcher（SableDispatcher）负责体素接触生成——
     *  结构自身体素与结构体一体，不另建 box collider（对齐官方 "结构=子层体素" 模型）。
     *
     * @param runtimeId 与 createBody 相同自定义 runtime id
     * @param pose      位姿 [x,y,z,qx,qy,qz,qw]（【主世界坐标】
     * @param sections  结构 section 列表（与 createBody 同源；null → 空体）
     * @param bounds    局部 bounds（minX..maxX 六值；null → 由 sections 推算）
     * @return 挂载的 section 数量
     */
    public synchronized int createStructureShapeBody(final int runtimeId, final double[] pose,
                                                     final java.util.List<SectionUpload> sections,
                                                     final int[] bounds) {
        // ★ 2026-09-05 多空间：先确保该结构分配到物理空间（并入/新建）；后续用它所属
        //   空间原点做局部坐标。空间基于【该结构主世界位置】（pose 参数，data.pose 未记录）。
        ensureInBucketFiltered(runtimeId, pose);
        // ★ 2026-09-06 【每空间独立场景】结构必须挂到【所属空间】的独立 native 场景
        //   （同空间结构同 handle；禁止跨空间共享）。空间场景未建 → 此时创建（阶段1 语义）。
        final PhysicalizedData dSpace = physicalized.get(runtimeId);
        final int sid0 = dSpace != null ? dSpace.spaceId : 0;
        final long scene = sid0 != 0 ? spaceSceneHandle(sid0) : requireScene();
        if (scene == 0L) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] createStructureShapeBody id={} no space scene (sid={})",
                    runtimeId, sid0);
            return 0;
        }
        final double[] nPose0 = pose != null && pose.length >= 7
                ? new double[]{pose[0], pose[1], pose[2], pose[3], pose[4], pose[5], pose[6]}
                : new double[]{0, 0, 0, 0, 0, 0, 1};
        // ★ 2026-09-06 【先主世界算质心】bounds 保持主世界（sections 主世界）；质心由方块算出
        final int[] effBounds = (bounds != null && bounds.length == 6) ? bounds
                : inferBounds(sections);
        // 真实世界坐标修正：buildOfficialSections 的 secX=(boundMinX>>4)+secIdx，
        //   方块真实世界坐标需补 (boundMinX & 15)。
        final java.util.List<long[]> blocks = solidBlocks(sections, effBounds);
        // ★ 2026-09-04 【质量为 0 自动删除】无实心方块（mass=0/空结构）→ 不创建刚体，
        //   返回 -1 通知调用方清理。
        if (blocks.isEmpty()) {
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] createStructureShapeBody id={} EMPTY (mass=0, no solid " +
                            "blocks); will auto-remove", runtimeId);
            return -1;
        }
        // ★ 2026-09-06 【先主世界算质心】body 原点 = 主世界几何/com（保持旋转不变）
        final double comX0 = (effBounds[0] + effBounds[3] + 1) * 0.5;
        final double comY0 = (effBounds[1] + effBounds[4] + 1) * 0.5;
        final double comZ0 = (effBounds[2] + effBounds[5] + 1) * 0.5;
        // ★ 2026-09-06 【物理坐标=物理化坐标，无转换】body 原点 = 主世界质心（com）
        final double[] nPose = new double[]{comX0, comY0, comZ0,
                nPose0[3], nPose0[4], nPose0[5], nPose0[6]};
        // ★ 2026-09-06 far 后的 body 原点（buildStructureCompound 会覆盖为精确质心 far）
        final int blockCount2 = Math.max(1, blocks.size());
        final double massV = blockCount2;
        final int added;
        // ★ 2026-09-05 【凹形组合】唯一适配 = shapeCollision && mergeAxisAligned（默认）。
        //   其余（SABLE_SHAPE_COLLISION=false / SABLE_MERGE_AXIS_ALIGNED=false）= 弃置
        //   旧体素管线（仅调试/回退）。
        if (this.shapeCollision && this.mergeAxisAligned) {
            // ★ 2026-09-06 【材质感知 + 重量】主世界方块算质心 → buildStructureCompound 内部
            //   far 化精确质心（覆盖 nPose 前三位）
            added = buildStructureCompound(scene, runtimeId, nPose, sections, effBounds);
        } else {
            // ---- 弃置区：旧体素管线（仅调试/回退）----
            added = buildStructureVoxel(scene, runtimeId, nPose, sections, blocks,
                    effBounds, massV, comX0, comY0, comZ0);
        }
        // ★ 2026-09-02 记录活动体（ECS：physicalizedData 即数据列表；active=true）。
        final PhysicalizedData data = physicalizedData(runtimeId);
        // ★ 2026-09-05 【ECS】注册结构到核心数据类结构化数据表（uuid 索引；幂等覆盖）
        if (this.worldData != null) {
            this.worldData.structuredTable.put(data.uuid, data);
        }
        data.active = true;
        // ★ 2026-09-06 【物理坐标=物理化坐标】data.pose = nPose（主世界；无转换）
        data.pose[0] = nPose[0];
        data.pose[1] = nPose[1];
        data.pose[2] = nPose[2];
        data.pose[3] = nPose[3];
        data.pose[4] = nPose[4];
        data.pose[5] = nPose[5];
        data.pose[6] = nPose[6];
        if (effBounds != null) {
            for (int i = 0; i < 6; i++) data.localBounds[i] = effBounds[i];
            data.anchor[0] = (effBounds[0] + effBounds[3] + 1) * 0.5;
            data.anchor[1] = (effBounds[1] + effBounds[4] + 1) * 0.5;
            data.anchor[2] = (effBounds[2] + effBounds[5] + 1) * 0.5;
            // ★ 2026-09-06 质心基准（far；localBounds far → comWorldX far，一致对比）
            data.lastComX = data.comWorldX();
            data.lastComY = data.comWorldY();
            data.lastComZ = data.comWorldZ();
        }
        if (sections != null) {
            for (final SectionUpload sec : sections) {
                data.structureBlocks.put(new SectionKey(sec.secX(), sec.secY(), sec.secZ()),
                        sec.chunk());
            }
        }
        data.active = true;
        // ★ 2026-09-05 质量 = 逐方块加权质量（compound 已设 massProps；voxel 回退方块数）
        data.mass = data.massProps != null
                ? Math.max(1.0, data.massProps.mass())
                : Math.max(1.0, blocks.size());
        // ★ 2026-09-05 环境介质/重力（主线程维度解析 → 纯数据；升力/浮力缩放）
        data.mediumDensity = this.envMediumDensity;
        data.gravityMag = this.envGravityMag;
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] OfficialRapierEngine.createStructureShapeBody id={} " +
                        "pose=({},{},{}) sections={} blocks={} mass={} ({})",
                runtimeId, nPose[0], nPose[1], nPose[2], added, blocks.size(), data.mass,
                (this.shapeCollision && this.mergeAxisAligned)
                        ? "compound axis-merged boxes"
                        : "DEPRECATED voxel pipeline");
        return added;
    }

    /**
     * ★ 2026-09-05 【凹形组合（compound 轴合并大 box）——唯一适配】结构本体构建：
     * 实心块（世界坐标）→ 材质感知轴合并（同材质相邻块才合并，材质保真到 box 级）→
     * createCompoundShapeBody（一次 JNI 建 dynamic 刚体 + density 0 box collider 集）→
     * setMassProperties（质量/惯量 = 逐方块质量加权，参考原版 MassTracker，相对 body 原点=质心）。
     * 结构级综合摩擦/弹性（box 体积加权摩擦 + max 弹性）→ native per-body 系数。
     * 升力/浮力中心随质量汇总算出（供三种力施加）。
     *
     * @return 合并 box 数
     */
    /** 材质 → 物理属性（materialId → BlockPhysicsProps；未注册 → 默认）。 */
    private static BlockPhysicsProps physProps(
            final int materialId) {
        return BlockPhysicsTable
                .propsOf(materialId);
    }

    /** ★ 2026-09-05 每 box 摩擦/弹性数组 [friction, restitution]*count（材质感知 per-box
     *  系数，native createCompoundShapeBody 扩展；空 → null 回退 body 级综合系数）。 */
    private static double[] boxCoeffs(final java.util.List<CompoundShapeMerger.MergedBox> mboxes) {
        if (mboxes == null || mboxes.isEmpty()) return null;
        final double[] c = new double[mboxes.size() * 2];
        for (int i = 0; i < mboxes.size(); i++) {
            final BlockPhysicsProps p =
                    physProps(mboxes.get(i).materialId());
            c[i * 2] = p.friction();
            c[i * 2 + 1] = p.restitution();
        }
        return c;
    }

    /** ★ 2026-09-05 【三种力施加】参考原版 FloatingBlockController.applyLift：
     *  - 质心（基础）：body 原点 = 质量质心，重力/推力由 Rapier 统一
     *  - 升力：F = up_local·totalLift（结构【局部上】= 姿态旋转 (0,1,0)，对齐原版
     *    localGravity 反方向），作用点 liftCenter → 力矩 = (liftCenter − com) × F
     *  - 浮力：F = up_world·totalVolume（简化 ρg=1；浮力永远指向重力反方向），
     *    作用点 buoyCenter → 力矩同理（局部力臂按当前姿态旋转）
     *  每 tick step 前对每个空间成员施加（applyForceAndTorque；力作用质心 + 力矩）。 */
    private void applyLiftAndBuoyancy(final long scene, final PhysicalizedData d) {
        if (d == null) return;
        final double cx = d.massProps != null ? d.massProps.comX() : d.comWorldX();
        final double cy = d.massProps != null ? d.massProps.comY() : d.comWorldY();
        final double cz = d.massProps != null ? d.massProps.comZ() : d.comWorldZ();
        final double qx = d.pose[3], qy = d.pose[4], qz = d.pose[5], qw = d.pose[6];
        // ★ 2026-09-05 环境缩放：升力/浮力 × |g|；浮力用扣除空气后的有效介质密度
        //   （空气下 = 0 → 结构不因空气浮力飘起；水中 ≈999 → 按阿基米德排水）
        final double g = d.gravityMag > 0 ? d.gravityMag : 9.81;
        final double rhoEff = Math.max(0.0, d.mediumDensity - 1.225);
        // 结构【局部上】→ 世界（姿态四元数旋转；原版 localGravity 反方向）
        final double[] upLocal = qrot(new double[]{0, 1, 0}, qx, qy, qz, qw);
        // 升力（局部上 × totalLift × g；作用 liftCenter，力臂 = q 旋转 (liftCenter − com)）
        if (d.totalLift > 0 && !Double.isNaN(d.liftX)) {
            final double fx = upLocal[0] * d.totalLift * g;
            final double fy = upLocal[1] * d.totalLift * g;
            final double fz = upLocal[2] * d.totalLift * g;
            final double[] r = qrot(new double[]{d.liftX - cx, d.liftY - cy, d.liftZ - cz},
                    qx, qy, qz, qw);
            final double tx = r[1] * fz - r[2] * fy;
            final double ty = r[2] * fx - r[0] * fz;
            final double tz = r[0] * fy - r[1] * fx;
            try {
                CryptandRapierNative.applyForceAndTorque(scene, d.runtimeId, fx, fy, fz, tx, ty, tz, true);
            } catch (final Throwable ignored) {
            }
        }
        // 浮力（世界 up × totalVolume × ρ_eff × g；力臂按姿态旋转）
        if (d.totalVolume > 0 && rhoEff > 0 && !Double.isNaN(d.buoyX)) {
            final double lift = d.totalVolume * rhoEff * g;
            final double fx = 0, fy = lift, fz = 0;
            final double[] r = qrot(new double[]{d.buoyX - cx, d.buoyY - cy, d.buoyZ - cz},
                    qx, qy, qz, qw);
            final double tx = r[1] * fz - r[2] * fy;
            final double ty = r[2] * fx - r[0] * fz;
            final double tz = r[0] * fy - r[1] * fx;
            try {
                CryptandRapierNative.applyForceAndTorque(scene, d.runtimeId, fx, fy, fz, tx, ty, tz, true);
            } catch (final Throwable ignored) {
            }
        }
    }

    /** ★ 2026-09-05 单位四元数 (qx,qy,qz,qw) 旋转向量 v（joml 等价，避免额外依赖）。 */
    private static double[] qrot(final double[] v, final double qx, final double qy,
                                 final double qz, final double qw) {
        // t = 2 * cross(q.xyz, v)
        final double tx = 2 * (qy * v[2] - qz * v[1]);
        final double ty = 2 * (qz * v[0] - qx * v[2]);
        final double tz = 2 * (qx * v[1] - qy * v[0]);
        // v' = v + qw*t + cross(q.xyz, t)
        return new double[]{
                v[0] + qw * tx + (qy * tz - qz * ty),
                v[1] + qw * ty + (qz * tx - qx * tz),
                v[2] + qw * tz + (qx * ty - qy * tx)
        };
    }

    private int buildStructureCompound(final long scene, final int runtimeId,
                                       final double[] nPose,
                                       final java.util.List<SectionUpload> sections,
                                       final int[] effBounds) {
        final PhysicalizedData dSc = physicalized.get(runtimeId);
        final PhysicalSpace spSc = dSc != null ? spaces.get(dSc.spaceId) : null;
        // ★ 2026-09-06 【sable 同构】空间原点（主世界；局部 = 主世界 − origin）
        final double ox = spSc != null ? spSc.origin[0] : 0;
        final double oy = spSc != null ? spSc.origin[1] : 0;
        final double oz = spSc != null ? spSc.origin[2] : 0;
        // ★ 2026-09-05 【材质感知】实心块材质表（packPos 世界坐标 → materialId）→ 同材质轴合并
        final java.util.Map<Long, Integer> solidMat =
                CompoundShapeMerger.solidMat(sections, effBounds);
        if (solidMat.isEmpty()) return 0;
        // ★ 2026-09-06 【sable 同构】局部化：仅 X/Z（origin 按 axis；Y 原点通常 0 亦减）
        //   —— 用 floor(origin)：origin 可能为质心（13.5），块级基准取整数部分
        final double oxF = Math.floor(ox), oyF = Math.floor(oy), ozF = Math.floor(oz);
        final java.util.Map<Long, Integer> localSolid = new java.util.HashMap<>();
        for (final java.util.Map.Entry<Long, Integer> e : solidMat.entrySet()) {
            int x = (int) ((e.getKey() >> 42) & 0x1FFFFF);
            int y = (int) ((e.getKey() >> 21) & 0x1FFFFF);
            int z = (int) (e.getKey() & 0x1FFFFF);
            if (x >= 0x100000) x -= 0x200000;
            if (y >= 0x100000) y -= 0x200000;
            if (z >= 0x100000) z -= 0x200000;
            localSolid.put(CompoundShapeMerger.packPos(
                    (long) (x - oxF), (long) (y - oyF), (long) (z - ozF)), e.getValue());
        }
        final java.util.List<CompoundShapeMerger.MergedBox> mboxes =
                CompoundShapeMerger.mergeMat(localSolid, this.mergeMaxBox);
        if (mboxes.isEmpty()) return 0;
        // ★ 2026-09-05 【重量】逐方块质量加权 → 质量/质心/惯量/升力中心/浮力中心（原版 MassTracker）
        //   —— 注意 solidMat 已是局部（减 origin），但材质系数只看 materialId（与坐标无关），
        //      质心为【局部】坐标（sable MassTracker 局部质心）
        final CompoundShapeMerger.MassResult mr = CompoundShapeMerger.computeMassProperties(
                localSolid, id -> physProps(id).mass(), id -> physProps(id).liftStrength(),
                id -> physProps(id).volume());
        final double massV = Math.max(1.0, mr.mass());
        // ★ 2026-09-06 【sable 同构】局部质心 → body 原点 = 主世界（origin + 局部质心；
        //   sable logicalPose 主世界 anchor 等价）
        final double comX = mr.comX(), comY = mr.comY(), comZ = mr.comZ();
        nPose[0] = ox + comX;
        nPose[1] = oy + comY;
        nPose[2] = oz + comZ;
        final double[] halfAndCenter = new double[mboxes.size() * 6];
        for (int i = 0; i < mboxes.size(); i++) {
            final int[] b = mboxes.get(i).box();
            halfAndCenter[i * 6] = (b[3] - b[0] + 1) * 0.5;
            halfAndCenter[i * 6 + 1] = (b[4] - b[1] + 1) * 0.5;
            halfAndCenter[i * 6 + 2] = (b[5] - b[2] + 1) * 0.5;
            // ★ 2026-09-06 【sable 同构】collider 相对 body 原点：局部盒心 − 局部质心
            //   （box 已是局部坐标；不再减 origin）
            halfAndCenter[i * 6 + 3] = (b[0] + b[3] + 1) * 0.5 - comX;
            halfAndCenter[i * 6 + 4] = (b[1] + b[4] + 1) * 0.5 - comY;
            halfAndCenter[i * 6 + 5] = (b[2] + b[5] + 1) * 0.5 - comZ;
        }
        // 结构级综合摩擦/弹性（box 体积加权摩擦 + max 弹性）
        final double[] fr = CompoundShapeMerger.compositeSurfaceProps(mboxes,
                id -> physProps(id).friction(), id -> physProps(id).restitution());
        // dynamic 刚体 + density 0 box 集；mass 由 setMassProperties 提供（覆盖 additional）。
        // ★ 2026-09-05 每 box 材质系数（per-box friction/restitution，native 扩展）
        CryptandRapierNative.createCompoundShapeBody(scene, runtimeId, 0, massV, mboxes.size(),
                halfAndCenter, boxCoeffs(mboxes), fr[0], fr[1], nPose);
        // 质量 + 惯量（逐方块合成，相对 body 原点=质心；质量在原点）
        try {
            CryptandRapierNative.setMassProperties(scene, runtimeId, massV,
                    new double[]{0, 0, 0}, mr.inertia());
        } catch (final Throwable ignored) {
        }
        // 记录材质/重量/三种力数据（供空间重建与升力/浮力施加）
        if (dSc != null) {
            dSc.massProps = mr;
            dSc.mass = massV;
            dSc.friction = fr[0];
            dSc.restitution = fr[1];
            dSc.liftX = mr.liftX();
            dSc.liftY = mr.liftY();
            dSc.liftZ = mr.liftZ();
            dSc.totalLift = mr.totalLift();
            dSc.buoyX = mr.buoyX();
            dSc.buoyY = mr.buoyY();
            dSc.buoyZ = mr.buoyZ();
            dSc.totalVolume = mr.totalVolume();
        }
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] structure compound rt={} mass={} boxes={} com=({},{},{}) " +
                        "fr={}/{} lift={} buoyVol={} (axis-merged, maxBox={})",
                runtimeId, massV, mboxes.size(), comX, comY, comZ,
                fr[0], fr[1], mr.totalLift(), mr.totalVolume(), this.mergeMaxBox);
        return mboxes.size();
    }

    /**
     * ---- 弃置区（SABLE_MERGE_AXIS_ALIGNED=false 时仅调试/回退）----
     * 旧官方体素管线：createSubLevel（动态 LevelCollider 体素碰撞器）+ setCenterOfMass/
     * setLocalBounds + addChunk(global=false, id) 挂结构自身体素。体素路线已弃置
     * （内存灾难 + 依赖 com/bounds/octree 三件套），保留仅供排查。
     */
    private int buildStructureVoxel(final long scene, final int runtimeId,
                                    final double[] nPose,
                                    final java.util.List<SectionUpload> sections,
                                    final java.util.List<long[]> blocks,
                                    final int[] effBounds, final double massV,
                                    final double comX, final double comY,
                                    final double comZ) {
        CryptandRapierNative.createSubLevel(scene, runtimeId, nPose, false);
        final double[] comLocal = new double[]{comX, comY, comZ};
        final double[] inertiaLocal = new double[]{
                massV, 0, 0, 0, massV, 0, 0, 0, massV};
        try {
            CryptandRapierNative.setMassProperties(scene, runtimeId, massV, comLocal, inertiaLocal);
        } catch (final Throwable ignored) {
        }
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] (DEPRECATED voxel) createStructureShapeBody id={} mass={} com=({},{},{})",
                runtimeId, massV, comX, comY, comZ);
        final PhysicalizedData dataInit = physicalized.get(runtimeId);
        final PhysicalSpace sp0 = dataInit != null ? spaces.get(dataInit.spaceId) : null;
        final double ox0 = sp0 != null ? sp0.origin[0] : 0;
        final double oy0 = sp0 != null ? sp0.origin[1] : 0;
        final double oz0 = sp0 != null ? sp0.origin[2] : 0;
        final int lbMinX = effBounds[0] - (int) Math.floor(ox0);
        final int lbMinY = effBounds[1] - (int) Math.floor(oy0);
        final int lbMinZ = effBounds[2] - (int) Math.floor(oz0);
        final int lbMaxX = effBounds[3] - (int) Math.floor(ox0);
        final int lbMaxY = effBounds[4] - (int) Math.floor(oy0);
        final int lbMaxZ = effBounds[5] - (int) Math.floor(oz0);
        final double comVX = (lbMinX + lbMaxX + 1) * 0.5;
        final double comVY = (lbMinY + lbMaxY + 1) * 0.5;
        final double comVZ = (lbMinZ + lbMaxZ + 1) * 0.5;
        CryptandRapierNative.setCenterOfMass(scene, runtimeId, comVX, comVY, comVZ);
        CryptandRapierNative.setLocalBounds(scene, runtimeId,
                lbMinX, lbMinY, lbMinZ, lbMaxX, lbMaxY, lbMaxZ);
        int added = 0;
        final int[] secOffset = new int[]{-((int) Math.floor(ox0) >> 4),
                -((int) Math.floor(oy0) >> 4), -((int) Math.floor(oz0) >> 4)};
        if (sections != null) {
            for (final SectionUpload sec : sections) {
                if (sec.chunk() == null) continue;
                try {
                    CryptandRapierNative.addChunk(scene,
                            sec.secX() + secOffset[0], sec.secY() + secOffset[1],
                            sec.secZ() + secOffset[2], sec.chunk(), false, runtimeId);
                    added++;
                } catch (final Throwable ignored) {
                }
            }
        }
        if (added == 0) {
            final java.util.Map<SectionKey, int[]> secMap = sectionsToMap(sections);
            for (final java.util.Map.Entry<SectionKey, int[]> e : secMap.entrySet()) {
                try {
                    CryptandRapierNative.addChunk(scene,
                            e.getKey().x() + secOffset[0], e.getKey().y() + secOffset[1],
                            e.getKey().z() + secOffset[2], e.getValue(), false, runtimeId);
                    added++;
                } catch (final Throwable ignored) {
                }
            }
        }
        return added;
    }

    /** sections → Map<SectionKey, int[]>（供 addChunk 挂载；跨段去重）。 */
    private static java.util.Map<SectionKey, int[]> sectionsToMap(
            final java.util.List<SectionUpload> sections) {
        final java.util.Map<SectionKey, int[]> map = new java.util.LinkedHashMap<>();
        if (sections == null) return map;
        for (final SectionUpload sec : sections) {
            if (sec.chunk() == null) continue;
            map.put(new SectionKey(sec.secX(), sec.secY(), sec.secZ()), sec.chunk());
        }
        return map;
    }

    /** ★ 2026-09-06 【结构级固定体素陪体（分配 id）】陪体=固定物理结构（fixed voxel），
     *  普通结构=动态。每结构【独立】陪体（rapier 允许重叠，不合并）。构建统一走
     *  【materializeScannedBlocks】；本方法仅【分配】该结构独立陪体 id。
     *  @return 陪体 virtualId（-11000000 区；-1=无数据） */
    public synchronized int ensureShapeCompanion(final int runtimeId) {
        final PhysicalizedData data = physicalized.get(runtimeId);
        if (data == null) return -1;
        if (data.shapeCompanionId != 0) return data.shapeCompanionId;
        final int vid = --shapeCompanionIdCounter;
        data.shapeCompanionId = vid;
        return vid;
    }

    /** ★ 2026-09-06 【陪体已建？】结构陪体是否已 createSubLevel（addChunkToBody 前置条件）。
     *  ---- 弃置区（compound 取代；陪体由 materializeCompoundCompanion 管理）---- */
    public synchronized boolean isCompanionBodyCreated(final int runtimeId) {
        final PhysicalizedData data = physicalized.get(runtimeId);
        if (data == null) return false;
        return data.rebuildStamp != -1L;   // 已 materialize 过（rebuildStamp 记录）
    }

    /** ★ 2026-09-06 【陪体建体】为结构陪体 createSubLevel(fixed)（上传数据前置条件）。
     *  幂等：已 materialize 过 → 跳过。
     *  ---- 弃置区（compound 取代；陪体由 materializeCompoundCompanion 建 fixed+box）---- */
    public synchronized void ensureCompanionBodyCreated(final int runtimeId) {
        final PhysicalizedData data = physicalized.get(runtimeId);
        if (data == null) return;
        final int vid = data.shapeCompanionId != 0 ? data.shapeCompanionId : ensureShapeCompanion(runtimeId);
        if (data.rebuildStamp != -1L) return;   // 已建过
        // ★ 2026-09-06 【每空间独立场景】陪体创建挂到所属空间场景。
        final PhysicalSpace spEC = data.spaceId != 0 ? spaces.get(data.spaceId) : null;
        final long sceneEC = spEC != null && spEC.sceneHandle != 0L ? spEC.sceneHandle : requireScene();
        try {
            CryptandRapierNative.createSubLevel(sceneEC, vid,
                    new double[]{0, 0, 0, 0, 0, 0, 1}, true);
        } catch (final Throwable ignored) {
        }
    }

    /** ★ 结构自身占用格（真实世界坐标 pack set：structureBlocks 用 buildOfficialSections
     *  坐标 → 补 data.localBounds 的（&15）偏移还原真实世界）。 */
    private java.util.Set<Long> structureSolidSet(final PhysicalizedData data) {
        final java.util.Set<Long> out = new java.util.HashSet<>();
        if (data == null || data.structureBlocks == null || data.structureBlocks.isEmpty()) {
            return out;
        }
        final int foX = (data.localBounds[0] & 15);
        final int foY = (data.localBounds[1] & 15);
        final int foZ = (data.localBounds[2] & 15);
        for (final java.util.Map.Entry<SectionKey, int[]> e : data.structureBlocks.entrySet()) {
            final int[] c = e.getValue();
            if (c == null) continue;
            final SectionKey k = e.getKey();
            final int sx = (k.x() << 4) + foX, sy = (k.y() << 4) + foY, sz = (k.z() << 4) + foZ;
            for (int by = 0; by < 16; by++) {
                for (int bz = 0; bz < 16; bz++) {
                    for (int bx = 0; bx < 16; bx++) {
                        if (c[bx + (bz << 4) + (by << 8)] != 0) {
                            out.add(packPos(sx + bx, sy + by, sz + bz));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** (x,y,z) → long：每轴 21 位（±~1048575 范围足够主世界坐标）。 */
    private static long packPos(final long x, final long y, final long z) {
        return ((x & 0x1FFFFF) << 42) | ((y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /** 每个结构一个 shape 陪体 id（-11000000 区，避开 voxel 陪体负 id 与结构正 id）。
     *  ★ 2026-09-04 ECS 化（修复）：从 PhysicalizedData.shapeCompanionId 读；无 → 用【单调
     *  递减计数器】分配唯一新 id 并写回。⚠ 不能用 physicalized.size()（size 不变 → 重建/多结构
     *  分配到同一 id → 陪体互相覆盖 → 第二个结构冻结——实测 shapeCompanionBody 全为 -11000010）。 */
    private int shapeCompanionIdCounter = -11_000_000;

    private int ensureShapeCompanionId(final int runtimeId) {
        final PhysicalizedData d = physicalizedData(runtimeId);
        if (d.shapeCompanionId != 0) return d.shapeCompanionId;
        final int next = --shapeCompanionIdCounter;
        d.shapeCompanionId = next;
        return next;
    }

    /** 柔体粒子 = 小型 dynamic 球体刚体。 */
    public synchronized void createParticleBody(final int runtimeId, final double radius,
                                                final double mass, final double[] pose) {
        final long scene = requireScene();
        CryptandRapierNative.createParticleBody(scene, runtimeId, radius, mass, pose);
    }

    /** 柔体边 / 距离弹簧约束（布料、链条）：返回 joint handle。 */
    public synchronized long linkBodiesSpring(final int idA, final int idB,
                                              final double[] localAnchorA,
                                              final double[] localAnchorB,
                                              final double frequency, final double dampingRatio) {
        final long scene = requireScene();
        return CryptandRapierNative.linkBodiesSpring(scene, idA, idB, localAnchorA, localAnchorB,
                frequency, dampingRatio);
    }

    // ===== ★ 2026-09-04 shape 陪体缓存上限（Rust 侧淘汰） =====

    /** 设置 shape 缓存上限（limit>=0 个数；-1=不限；0=禁用）+ 最小批量删除数（minEvict）。
     *  缩小 → Rust 立即执行一次淘汰；扩大/相等 → 只扩大不删。 */
    public synchronized void setShapeCacheLimit(final long limit, final long minEvict) {
        final long scene = requireScene();
        CryptandRapierNative.setShapeCacheLimit(scene, limit, minEvict);
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] shapeCache limit={} minEvict={} (applied)", limit, minEvict);
    }

    /** 当前 shape 缓存上限。 */
    public synchronized long getShapeCacheLimit() {
        return CryptandRapierNative.getShapeCacheLimit(requireScene());
    }

    /** 当前最小批量删除数。 */
    public synchronized long getShapeCacheMinEvict() {
        return CryptandRapierNative.getShapeCacheMinEvict(requireScene());
    }

    /** 强制一次缓存淘汰（权重低+footprint 小优先；至少删 minEvict 个）。返回删除数。 */
    public synchronized long evictShapeCache() {
        return CryptandRapierNative.evictShapeCache(requireScene());
    }
}
