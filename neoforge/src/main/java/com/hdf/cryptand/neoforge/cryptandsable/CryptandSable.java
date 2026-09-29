package com.hdf.cryptand.neoforge.cryptandsable;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.api.physics.BodyParams;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.core.allocator.SableBatchScheduler;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.EngineDispatcher;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.EngineManager;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.OfficialRapierEngine;
import com.hdf.cryptand.neoforge.cryptandsable.core.chamber.ChamberManager;
import com.hdf.cryptand.neoforge.cryptandsable.core.collision.Contact;
import com.hdf.cryptand.neoforge.cryptandsable.core.collision.SableCollisionDetector;
import com.hdf.cryptand.neoforge.cryptandsable.core.destruction.DestructionEvent;
import com.hdf.cryptand.neoforge.cryptandsable.core.destruction.SableDestructionReporter;
import com.hdf.cryptand.neoforge.cryptandsable.core.engine.CoreEngine;
import com.hdf.cryptand.neoforge.cryptandsable.core.entity.PhysicalStructure;
import com.hdf.cryptand.neoforge.cryptandsable.core.entity.StructureRegistry;
import com.hdf.cryptand.neoforge.cryptandsable.core.environment.EnvId;
import com.hdf.cryptand.neoforge.cryptandsable.core.environment.MediaProperties;
import com.hdf.cryptand.neoforge.cryptandsable.core.mass.MassCalculator;
import com.hdf.cryptand.neoforge.cryptandsable.core.simulator.RigidBodyState;
import com.hdf.cryptand.neoforge.cryptandsable.core.simulator.SableSimulator;
import com.hdf.cryptand.neoforge.cryptandsable.core.worker.SableSimulationContext;
import com.hdf.cryptand.neoforge.cryptandsable.core.worker.SableWorker;
import com.hdf.cryptand.neoforge.cryptandsable.network.SableSubLevelRenderPayload;
import com.hdf.cryptand.neoforge.cryptandsable.persistence.CryptandSubLevelPersistence;
import com.hdf.cryptand.neoforge.cryptandsable.server.SableServerBridge;
import com.hdf.cryptand.neoforge.cryptandsable.server.SableSnapshotStore;
import com.hdf.cryptand.neoforge.cryptandsable.server.SableWorldEventHook;
import com.hdf.cryptand.neoforge.cryptandsable.server.WorldChunkUploader;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * CryptandSable —— 完全替换 Sable 的异步物理核心门面（对外总控）。
 *
 * <p>职责（组合 worker/仿真/消息）：
 * <ul>
 *   <li>对外提供主线程可调用的唯一入口：启动/停止（世界 load/unload）</li>
 *   <li>接收心跳（主线程每 tick）→ 驱动核心步进</li>
 *   <li>接收结构导入/移除/交互命令 → 由 worker 执行</li>
 *   <li>出站消息（位姿快照/破坏/事件）→ 主线程轮询消费</li>
 * </ul>
 *
 * <p>主线程只接触本类；核心内部（worker/仿真/环境）对主线程不可见（数据归属矩阵）。
 * 对本类来说：simulation 全在 worker 线程，主线程只是收发消息。
 */
public final class CryptandSable {
    private static final CryptandSable INSTANCE = new CryptandSable();

    private final SableWorker worker = new SableWorker();
    private final SableServerBridge bridge =
            new SableServerBridge(this);

    /** 服务端桥访问器（渲染请求回复 handlePoseRequest 用）。 */
    public SableServerBridge bridge() {
        return this.bridge;
    }

    /**
     * ★ 2026-09-01 pull 渲染：向某玩家补发全部亚层渲染数据（大缓存）。
     * 客户端请求的 cacheVersion 与服务端不一致（结构新增/移除/变化）时调用。
     */
    public void resendRenderDataTo(net.minecraft.server.level.ServerPlayer sp) {
        try {
            // MOVED 全表 → 构建 render payload（复用 CryptandSubLevelApi 的构建逻辑）
            for (final java.util.Map.Entry<java.util.UUID,
                    java.util.List<CryptandSubLevelApi.PlacedBlockSnapshot>> e
                    : CryptandSubLevelApi
                            .allMoved().entrySet()) {
                final double[] anchor = CryptandSubLevelApi
                        .projectionAnchor(e.getKey());
                final int rt = CryptandSubLevelApi
                        .subLevelRuntimeId(e.getKey());
                final java.util.List<CryptandSubLevelApi.PlacedBlockSnapshot> snaps =
                        e.getValue();
                if (snaps == null || snaps.isEmpty()) continue;
                // 构建 payload（与 broadcastRenderSync 同逻辑）
                final SableSubLevelRenderPayload payload =
                        CryptandSubLevelApi
                                .buildRenderPayload(e.getKey(), rt, snaps);
                if (payload == null) continue;
                sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        payload));
            }
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] resendRenderDataTo failed: {}", t.toString());
        }
    }

    /** V2 批处理核心引擎（结构注册 + 批处理调度 + 引擎交互）。 */
    private final CoreEngine coreEngine = new CoreEngine(
            new StructureRegistry(),
            new SableBatchScheduler(),
            EngineManager.instance());
    private volatile boolean started = false;
    private volatile boolean installed = false;
    private volatile net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
            currentDimension = net.minecraft.world.level.Level.OVERWORLD;

    /** ★ 2026-09-01 官方 Rapier 物理引擎（用户："搬原版sable物理核心来适配"）。 */
    private final OfficialRapierEngine
            officialEngine = new OfficialRapierEngine();

    /** 官方引擎是否启用（config 开关；start 后确定）。 */
    private volatile boolean useOfficialEngine = false;

    /** ★ 2026-09-01 世界静态体素上传器（官方引擎模式；结构落地/世界碰撞）。 */
    private final WorldChunkUploader
            worldUploader = new WorldChunkUploader(officialEngine);

    /** 世界静态体素上传器访问器（CryptandSubLevelApi 物理化时上传周围世界 section）。 */
    public WorldChunkUploader worldUploader() {
        return this.worldUploader;
    }

    /** 官方引擎是否启用。 */
    public boolean isOfficialEngine() {
        return this.useOfficialEngine;
    }

    /** 官方引擎访问器（部分低层逻辑用）。 */
    public OfficialRapierEngine officialEngine() {
        return this.officialEngine;
    }

    private CryptandSable() {
        // 接线：worker 每收到心跳 → 执行批量步进（高频小步进自收敛）
        worker.setStepCallback(substepIndex -> {
            // ★ 2026-09-06 【每空间独立四阶段管道】不再每 tick 串行 step()：
            //   空间调度由主线程每 tick 驱动（tickScheduledSpaces + rebalanceSpaces），
            //   空间任务经线程分配器普通线程（NORMAL）执行；与 Rust 交互时 JNI 同步等待。
            if (useOfficialEngine) {
                try {
                    // 触发一次空间队列推进（多数空间任务已由主线程调度；此处兜底）。
                    // 位姿发布回调已由空间任务阶段4触发（publishSpacePoses）。
                    return;
                } catch (Throwable t) {
                    CryptandNeoForge.WAF_LOGGER.warn(
                            "[CryptandSable] official engine step failed, fallback: {}",
                            t.toString());
                    useOfficialEngine = false;
                }
            }
            SableMessages.Heartbeat hb = worker.heartbeat();
            int steps = hb.advanceSteps();
            if (steps <= 0) return;
            SableSimulationContext ctx = worker.simulation();
            // 环境重力/介质密度：从 ctx 环境快照取（主线程预解析，worker 消费）
            Vector3d gravity = new Vector3d(ctx.environment().gravity());
            SableSimulator.stepBatch(ctx, steps, gravity);

            // 碰撞/破坏：以本 tick 最终接触检测产破坏列表（增量下行）
            java.util.List<Contact> contacts = SableCollisionDetector.detect(ctx);
            java.util.List<DestructionEvent> destructions =
                    SableDestructionReporter.buildList(ctx, contacts);
            for (DestructionEvent d : destructions) {
                worker.emit(d);
            }

            // step 后产出位姿快照 → 出站
            publishPoseSnapshots(ctx);
        });
    }

    public static CryptandSable instance() {
        return INSTANCE;
    }

    /** 服务端位姿快照镜像（bridge 内；null=核心未启动/未绑定）。 */
    public SableSnapshotStore snapshots() {
        return this.bridge.snapshotStore();
    }

    /** 给定线程是否即物理核心 worker（mixin/外部判定用）。 */
    public static boolean isWorkerThread(Thread t) {
        return INSTANCE.worker.isWorkerThread() || t == INSTANCE.worker.currentThread();
    }

    /**
     * 安装生命周期挂钩（每个服务端只装一次；空 sable mod 壳 + core 接管）。
     *
     * <p>挂载事件：
     * <ul>
     *   <li>LevelEvent.Load（主世界）→ {@link #start()}</li>
     *   <li>LevelEvent.Unload（主世界）→ {@link #stop()}</li>
     *   <li>ServerTickEvent.Post → 心跳（每 tick 推进预算）</li>
     * </ul>
     */
    public synchronized void install() {
        if (installed) return;
        // ★ 2026-09-06 【核心总开关 · 完全关闭语义】关闭 → 静默 return（双保险；外层
        //   installCryptandSable 已按 enableCryptandSableCore 拦截至此——任何路径都不打印）。
        if (!ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return;
        }
        installed = true;

        // ★ 2026-09-05 【核心功能早初始化】线程分配器（工程统一分配器
        //   ThreadDispatchers/ThreadDispatcher，pure Java zero-MC）在 MC 启动加载阶段
        //   就初始化——电路仿真器/物理运算等所有请求线程的核心功能直接复用，
        //   分配器 Worker 池/虚拟线程池立即可用（懒初始化提前触发）。
        //   物理 STEP 用独占高优先级（TaskMode.PHYSICS_HIGH），IO/普通用 NORMAL（虚拟线程）。
        try {
            com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers.get();
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] thread dispatcher initialized (pool={}, maxVt/worker={})",
                    com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers.defaultThreads(),
                    com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers.defaultMaxVirtualThreads());
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] thread dispatcher init failed: {}", t.toString());
        }

        // ★ 2026-09-05 世界事件实时增量更新 hook（官方 chunk 事件驱动等价）：
        //   方块破坏/放置/区块加载 → 官方引擎 onWorldBlockChanged/onWorldChunkLoaded
        //   → 陪体增量 diff（add/removeShapeColliderAt）。先注册（单例），
        //   引擎启动后 bind（LevelEvent.Load）→ 停止后 unbind。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                SableWorldEventHook.INSTANCE);

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Load ev) -> {
                    if (ev.getLevel() instanceof net.minecraft.server.level.ServerLevel sl
                            && sl.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                        currentDimension = sl.dimension();
                        // 预解析当前维度环境 → 快照给 worker（主线程读 Level，worker 只消费）
                        final MediaProperties
                                mp = com.hdf.cryptand.neoforge.cryptandsable.core.environment
                                        .DimensionEnvConfig.get(sl.dimension());
                        worker.simulation().setEnvironment(new com.hdf.cryptand.neoforge.cryptandsable
                                .core.environment.EnvironmentSnapshot(mp, sl.getGameTime()));
                        // ★ 2026-09-05 介质密度/重力 → 官方引擎（结构创建时写入
                        //   PhysicalizedData，供升力/浮力缩放；主线程解析 → 纯数据进核心）
                        if (mp != null) {
                            final org.joml.Vector3dc g = mp.gravity();
                            final double gMag = g == null ? 9.81
                                    : Math.sqrt(g.x() * g.x() + g.y() * g.y() + g.z() * g.z());
                            officialEngine.setEnvironmentMedium(mp.mediumDensity(), gMag);
                        }
                        bridge.bind(sl, this);
                        // ★ 2026-09-05 引擎绑定到世界事件 hook（引擎 start 后调用）
                        SableWorldEventHook
                                .INSTANCE.bind(officialEngine);
                        // ★ 2026-09-05 【ECS · 世界绑定】创建/复用该世界的核心数据类
                        //   （PhysicsWorldData，每世界一个 → 世界隔离）
                        try {
                            officialEngine.bindWorld(sl.dimension());
                        } catch (final Throwable ignored) {
                        }
                        // 持久化：世界加载 → 恢复已物理化亚层（sqlite/sable 后端）
                        try {
                            CryptandSubLevelPersistence
                                    .instance().onWorldLoad(sl);
                        } catch (Throwable t) {
                            CryptandNeoForge.WAF_LOGGER.warn(
                                    "[CryptandSable] persistence onWorldLoad failed: {}", t.toString());
                        }
                        start();
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[CryptandSable] physics core started for {}", sl.dimension());
                    }
                });

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.level.LevelEvent.Unload ev) -> {
                    if (ev.getLevel() instanceof net.minecraft.server.level.ServerLevel sl
                            && sl.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
                        stop();
                        bridge.clear();
                        // ★ 2026-09-05 世界事件 hook 解绑（引擎停止，防事件指向空引擎）
                        SableWorldEventHook
                                .INSTANCE.unbind();
                        // 持久化：世界卸载 → flush + close（保证落盘）
                        try {
                            CryptandSubLevelPersistence
                                    .instance().onWorldUnload(sl);
                        } catch (Throwable t) {
                            CryptandNeoForge.WAF_LOGGER.warn(
                                    "[CryptandSable] persistence onWorldUnload failed: {}", t.toString());
                        }
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[CryptandSable] physics core stopped");
                    }
                });

        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                (net.neoforged.neoforge.event.tick.ServerTickEvent.Post ev) -> {
                    // 心跳：主线程推进预算（默认 4 小步/tick = 80Hz 模拟）+ 消费出站
                    if (started) {
                        onServerTick(ev.getServer().getTickCount(), 4, 0L);
                        bridge.tick(ev.getServer().getTickCount());
                    }
                });
    }

    /** 世界加载时启动核心。 */
    public synchronized void start() {
        if (started) return;
        // ★ 2026-09-06 【核心总开关】关闭 → 不启动 worker/引擎（install 已拒绝装钩子；
        //   此处兜底防任何残留/外部调用）。
        if (!ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return;
        }
        started = true;
        worker.start();
        // ★ 2026-09-02 动态多引擎分发（配置开启）：替换单活引擎的底层获取接口；
        //   未开启 → 完全走原有单活逻辑（官方引擎 + 默认 f64 引擎），原样不动。
        final boolean useDispatch = ConfigCryptandSable.ENABLE_SABLE_ENGINE_DISPATCH.get();
        if (useDispatch) {
            useOfficialEngine = false;
            try {
                this.coreEngine.startDynamic(0.0, -9.81, 0.0, 0.10);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] dynamic engine dispatch enabled (replaces single "
                                + "engine backend)");
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] dynamic engine dispatch start failed: {}", t.toString());
            }
            return;
        }
        // ★ 2026-09-01 官方 Rapier 引擎优先（完整6DOF/碰撞）；失败回退自研
        final boolean cfgOff = ConfigCryptandSable.ENABLE_SABLE_OFFICIAL_ENGINE.get();
        if (cfgOff) {
            final int timeout = ConfigCryptandSable.SABLE_COLLECT_TIMEOUT.get();
            final int sendInterval = ConfigCryptandSable.SABLE_COLLECT_SEND_INTERVAL_STEPS.get();
            final int timeoutSteps = ConfigCryptandSable.SABLE_COLLECT_TIMEOUT_STEPS.get();
            officialEngine.setCollectTimeoutMax(timeout);
            officialEngine.setCollectSendIntervalSteps(sendInterval);
            officialEngine.setCollectTimeoutSteps(timeoutSteps);
            final boolean ok = officialEngine.start(0.0, -9.81, 0.0);
            if (ok) {
                useOfficialEngine = true;
                // ★ 2026-09-06 空间任务阶段4 完成后回调（发布位姿快照）——
                //   空间任务在分配器普通线程执行，回调在本引擎线程（同步等待完成）；
                //   只做 worker.emit（消息池），不直接发布网络包。
                try {
                    officialEngine.setSpacePoseCallback(this::publishSpacePoses);
                } catch (final Throwable ignored) {
                }
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] official Rapier engine enabled (6DOF+collision, "
                                + "timeout={}, sendInterval={}, timeoutSteps={})",
                        timeout, sendInterval, timeoutSteps);
                // ★ 2026-09-02 多物理空间（MultiScenePhysics）自检：
                //   多 scene + 并行批量步进验证。开关 -Dcryptand.multiscene.selftest=true
                //   （后台线程执行，不影响主路径）。
                if (Boolean.getBoolean("cryptand.multiscene.selftest")) {
                    final Thread st = new Thread(() -> {
                        try {
                            com.hdf.cryptand.neoforge.cryptandsable.core.backend.official
                                    .MultiScenePhysics.selfTest(3, 2, 40, 40.0);
                        } catch (final Throwable t) {
                            CryptandNeoForge.WAF_LOGGER.warn(
                                    "[MultiScene] selfTest failed", t);
                        }
                    }, "cryptand-multiscene-selftest");
                    st.setDaemon(true);
                    st.start();
                }
            } else {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] official Rapier engine failed to start; "
                                + "falling back to self simulator");
            }
        } else {
            useOfficialEngine = false;
        }
        // V2：加载默认 f64 引擎（DLL 自动加载；纯 Java 仿真兜底）
        try {
            this.coreEngine.start(0.0, -9.81, 0.0, 0.10);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] engine start failed: {}", t.toString());
        }
    }

    /** 世界卸载时停止核心。 */
    public synchronized void stop() {
        if (!started) return;
        started = false;
        useOfficialEngine = false;
        // ★ 2026-09-02 冻结修复：顺序反转——【先停 worker】→【再释放引擎场景】。
        //   原来先 dispose 场景再停 worker：worker 若仍携心跳执行 step →
        //   requireScene 抛异常/竞态 → 重进不启动（冻结）；且 dispose 与驻留 native 调用
        //   并发有挂死风险。worker 先停（Join 等线程退出）保证此后无任何 native 调用。
        worker.stop();
        worker.simulation().clear();
        try {
            officialEngine.stop();
        } catch (Throwable ignored) {
        }
        // V2：卸载引擎（DLL 卸载前释放 native 资源）
        try {
            this.coreEngine.stop();
        } catch (Throwable ignored) {
        }
    }

    public boolean isStarted() {
        return started;
    }

    /** 当前物理核心所在维度键（供环境查询/维度切换）。 */
    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> currentDimension() {
        return currentDimension;
    }

    /** 主线程预分配 runtime id（供注册 API 同步返回给调用方）。 */
    public int allocateRuntimeId() {
        return worker.simulation().allocateRuntimeId();
    }

    /** ★ 2026-09-06 【核心门面 · 收集结果】主线程完成世界方块收集（读 Level 是主线程
     *  职责）后，【只向核心投递结果】——核心（空间任务）负责把扫描块上传陪体并解冻。
     *  对齐电路仿真核心：主线程只 post，数据转发到空间任务全部经核心。
     *  ★ 2026-09-07 【特征】：feature = 物理空间 UUID 拷贝；核心据此识别消息所属周期
     *  （接收对象在操作表中 + 特征匹配 + WAIT_REPLY 阶段才有效；否则丢弃）。 */
    public void postCollectResult(final int runtimeId, final long feature, boolean collected,
                                  final java.util.Map<com.hdf.cryptand.neoforge.cryptandsable
                                          .core.backend.official.SectionKey, int[]> scanned) {
        if (!started || !useOfficialEngine || officialEngine == null) return;
        try {
            officialEngine.onCollectResult(runtimeId, feature, collected, scanned);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] postCollectResult failed rt={}: {}", runtimeId, t.toString());
        }
    }

    /** ★ 2026-09-05 【核心门面 · 空间级扫描结果（打包请求的单条回复）】
     *  主线程对空间扫描请求包内所有区域统一收集完成 → 只投递核心（批量受理+解冻）。
     *  @param feature 空间 UUID 拷贝（回传自请求包；核心校验）
     *  @param ts      请求包时间戳（直接时间记录；回传；核心据此识别新旧）
     *  @param all     Map<runtimeId, Map<SectionKey,int[]>> 各成员扫描结果 */
    public void postSpaceScanResult(final int spaceId, final long feature, final long ts,
                                    final java.util.Map<Integer, java.util.Map<
                                            com.hdf.cryptand.neoforge.cryptandsable
                                                    .core.backend.official.SectionKey, int[]>> all) {
        if (!started || !useOfficialEngine || officialEngine == null) return;
        try {
            officialEngine.onSpaceScanResult(spaceId, feature, ts, all);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] postSpaceScanResult failed space={}: {}", spaceId, t.toString());
        }
    }

    /** 主线程每 tick 发送心跳（带推进预算）。 */
    public void onServerTick(long serverTick, int advanceSteps, long budgetNanos) {
        if (!started) return;
        worker.onHeartbeat(new SableMessages.Heartbeat(serverTick, advanceSteps, budgetNanos));
        // ★ 2026-09-06 【每 tick 空间调度（用户定案）】:
        //   ① 空间合并/拆分再平衡（类似电路仿真 rebuild；纯 Java 数据判定）
        //   ② 全部已加载空间加入操作列表 → 提交空间任务（分配器普通线程）
        if (useOfficialEngine && officialEngine != null) {
            try {
                // ★ 2026-09-05 【一世界一空间】周期 BFS 扫描/空间合并拆分已放弃置区
                //   （scanSpacesPeriodic/rebalanceSpaces 弃置；不再调用）
                // ★ 2026-09-06 空间任务操作列表驱动（世界唯一空间每 tick 调度）
                officialEngine.tickScheduledSpaces();
            } catch (final Throwable t) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] space schedule failed: {}", t.toString());
            }
        }
    }

    /** 结构导入：把一块空间注册为物理体（主线程调用，worker 异步处理）。 */
    public void importBody(SableMessages.BodyImport imp) {
        if (!started) return;
        final boolean official = useOfficialEngine;
        worker.post(() -> {
            SableSimulationContext ctx = worker.simulation();
            int runtimeId = imp.runtimeId() > 0 ? imp.runtimeId() : ctx.allocateRuntimeId();
            int sceneId = imp.sceneId() > 0 ? imp.sceneId() : ctx.allocateSceneId();

            // ★ 2026-09-02 动态引擎分发：按坐标把该结构 scene 绑定到目标引擎（未开启 → no-op）
            EngineDispatcher dsp =
                    this.coreEngine.engineManager().activeDispatcher();
            if (dsp != null) {
                dsp.bindScene(sceneId, imp.px(), imp.py(), imp.pz());
            }

            // ★ 2026-09-01 官方 Rapier 引擎路径：位姿/体素上传原生场景（完整6DOF）。
            if (official) {
                try {
                    final double[] poseV = {imp.px(), imp.py(), imp.pz(),
                            imp.qx(), imp.qy(), imp.qz(), imp.qw()};
                    final int[] bounds = new int[]{imp.boundMinX(), imp.boundMinY(),
                            imp.boundMinZ(), imp.boundMaxX(), imp.boundMaxY(), imp.boundMaxZ()};
                    // ★ 2026-09-04 【全 shape】废弃旧体素管线：一律动态 shape 刚体 + 每格完整
                    //   box collider（主世界坐标，无 far）。mass=0 空结构 → 自动删除。
                    final int created = officialEngine.createStructureShapeBody(runtimeId,
                            poseV, buildOfficialSections(imp), bounds);
                    if (created < 0) {
                        // ★ 2026-09-04 【mass=0 自动删除】→ 不注册占位、不进 bodies（自动删除）。
                        officialEngine.removeBody(runtimeId);
                        return;
                    }
                } catch (Throwable t) {
                    CryptandNeoForge.WAF_LOGGER.warn(
                            "[CryptandSable] official createBody failed {}: {}", runtimeId, t.toString());
                }
                // 注册占位状态（status/兼容读；官方引擎位姿经发布快照，不从这里积分）
                registerPlaceholderBody(ctx, imp, runtimeId, sceneId);
                return;
            }

            RigidBodyState body = new RigidBodyState(runtimeId, sceneId);
            body.position.set(imp.px(), imp.py(), imp.pz());
            body.orientation.set(imp.qx(), imp.qy(), imp.qz(), imp.qw()).normalize();

            // 刚/柔统一：params（真实参数）优先；null → 按 typeFlags 判断
            BodyParams bp = imp.params();
            if (bp == null) {
                bp = imp.typeFlags() == 1
                        ? BodyParams
                                .soft(8, 0, 0.2, 4.0) // MVP 默认软参数
                        : BodyParams.rigid(0);
            }
            body.setParams(bp);

            // 核心自算质量/质心/惯量（C5）
            if (imp.voxelDensity() != null) {
                int sx = imp.boundMaxX() - imp.boundMinX() + 1;
                int sy = imp.boundMaxY() - imp.boundMinY() + 1;
                int sz = imp.boundMaxZ() - imp.boundMinZ() + 1;
                if ((long) sx * sy * sz == imp.voxelDensity().length) {
                    double[] density = new double[imp.voxelDensity().length];
                    for (int i = 0; i < density.length; i++) {
                        density[i] = (imp.voxelDensity()[i] >> 16) & 0xFFFF; // 高 16 位密度系数
                    }
                    MassCalculator.MassResult r = MassCalculator.calculate(density, Math.max(1, sx), Math.max(1, sy), Math.max(1, sz));
                    if (r.totalMass() > 0) {
                        Matrix3d inertia = new Matrix3d(r.inertiaTensor());
                        body.setMassProperties(r.totalMass(), inertia);
                        // ⚠ 2026-08-31 修复"物理化后消失"：centerOfMass 是【局部质心】
                        // （相对包围盒原点），把它加到【世界位置】会错位 2~3 格 ——
                        // 保持 anchor 世界位置（初始装载位置），不加局部质心。
                    }
                    // 气室聚合（C10）：按结构非空体素聚合气室/浮力单元
                    ChamberManager
                            .attachBodyChambers(runtimeId, density, Math.max(1, sx), Math.max(1, sy), Math.max(1, sz));
                }
            } else {
                body.setMassProperties(1.0, new Matrix3d());
            }

            // ⚠ 2026-08-31 修复"物理化后消失"：装配体初始【休眠】（静置在原位，
            // 不被重力立即拉走）；玩家/交互唤醒后才物理运动。
            body.setSleeping(true);

            ctx.registerBody(runtimeId, sceneId, body);

            // V2 ECS：登记物理结构数据包（StructureRegistry），关联 envId（默认主世界陆地）
            // + 刷新环境缓存（EnvId 解析结果；进入世界时解析）
            PhysicalStructure structure = new PhysicalStructure(runtimeId, sceneId);
            structure.position().set(body.position);
            structure.orientation().set(body.orientation);
            if (body.mass() > 0) {
                // MVP：惯量近似为标量球惯量（引擎内精确惯量后续接入）
                Matrix3d approx = new Matrix3d().identity().scale(body.mass() * 0.4);
                structure.setMassProperties(body.mass(), approx);
            }
            structure.setEnvId(EnvId.DEFAULT);
            ctx.registerStructure(structure);
        });
    }

    /** 移除结构。 */
    public void removeBody(SableMessages.BodyRemove rem) {
        if (!started) return;
        // ★ 2026-09-04 【删除后仍在扫描】官方引擎路径必须同步清除原生体 + allActiveBodies
        //   + shape 陪体——否则残留的 allActiveBodies 会持续发收集查询 → uploadSection 永更新。
        //   （worker.simulation().removeBody 只清 Java context，不碰官方引擎数据结构。）
        if (useOfficialEngine && officialEngine != null) {
            try {
                officialEngine.removeBody(rem.runtimeId());
            } catch (final Throwable ignored) {
            }
        }
        // ★ 2026-09-04 清上传器每结构缓存（sectionCache + 扫描方块表）——删除后
        //   不再有旧数据可被 storeScannedBlocks/materialize 复用（防残留扫描）。
        try {
            if (worldUploader() != null) {
                worldUploader().clearSectionCache(rem.runtimeId());
                worldUploader().clearScannedBlocks(rem.runtimeId());
            }
        } catch (final Throwable ignored) {
        }
        worker.post(() -> worker.simulation().removeBody(rem.runtimeId()));
    }

    /** 交互（力/冲量/破坏改质量）。 */
    public void interact(SableMessages.Interaction act) {
        if (!started) return;
        worker.post(() -> {
            RigidBodyState body = worker.simulation().getBody(act.runtimeId());
            if (body == null) return;
            switch (act.kind()) {
                case APPLY_FORCE -> body.applyForce(new org.joml.Vector3d(act.fx(), act.fy(), act.fz()));
                case APPLY_IMPULSE -> body.applyImpulse(new org.joml.Vector3d(act.fx(), act.fy(), act.fz()));
                case APPLY_TORQUE -> body.applyTorque(new org.joml.Vector3d(act.fx(), act.fy(), act.fz()));
                case WAKE_UP -> body.setSleeping(false);
                default -> { /* BLOCK_SET/REMOVE: TODO 结构密度更新 */ }
            }
        });
    }

    /** 主线程每 tick 消费出站消息（位姿快照/破坏/事件）。 */
    public List<Object> drainOutbound() {
        List<Object> out = new ArrayList<>();
        worker.drainOutbound(out);
        return out;
    }

    /**
     * ★ 应用物理激活集合（主线程应答查询后调用，2026-09-01）。
     *
     * <p>物理激活取决于附近区块加载/强制加载：活跃集合内的体唤醒（setSleeping(false)），
     * 集合外的体休眠（setSleeping(true)）。休眠体在 step 积分中被跳过（不前进）；
     * 附近无玩家/区块卸载 → 自动休眠省算力；玩家接近（区块加载）→ 唤醒恢复物理。
     *
     * <p>同时写入地表支撑（groundY）：唤醒后体贴地不穿透（防直接掉出世界）。
     *
     * @param activeRuntimeIds 应保持活跃（步进）的 runtimeId 集合
     * @param groundYs         runtimeId → 地形最高实心方块顶 Y（NaN=无/不贴地）
     */
    public void applyActivation(final java.util.Set<Integer> activeRuntimeIds,
                                final java.util.Map<Integer, Double> groundYs) {
        if (!started || activeRuntimeIds == null) return;
        worker.post(() -> {
            final SableSimulationContext ctx = worker.simulation();
            for (final java.util.Map.Entry<Integer, RigidBodyState> e : ctx.bodies().entrySet()) {
                final RigidBodyState b = e.getValue();
                if (b == null || b.isRemoved()) continue;
                try {
                    final boolean active = activeRuntimeIds.contains(b.runtimeId);
                    // 地表支撑：活跃体所在列地面写入（后续 step 贴地）
                    if (groundYs != null && groundYs.containsKey(b.runtimeId)) {
                        final double gy = groundYs.get(b.runtimeId);
                        if (Double.isNaN(gy)) b.setGroundY(Double.NaN);
                        else b.setGroundY(gy);
                    }
                    // 仅在状态变化时切换，避免每 tick 无谓写
                    if (active && b.isSleeping()) b.setSleeping(false);
                    else if (!active && !b.isSleeping()) b.setSleeping(true);
                } catch (final Throwable ignored) {
                    // 单体设置失败不影响其余
                }
            }
        });
    }

    // ===== 兼容查询（SableCompat 用；经 worker 线程执行保证线程安全） =====

    /** 查找包含某世界坐标的已注册物理体（最近质心法；MVP 简化）。 */
    public int lookupBodyAt(final int x, final int y, final int z) {
        return queryOnWorker(() -> {
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            for (RigidBodyState b : worker.simulation().bodies().values()) {
                if (b.isRemoved()) continue;
                double dx = b.position.x - x, dy = b.position.y - y, dz = b.position.z - z;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestDist) { bestDist = d; best = b.runtimeId; }
            }
            return best;
        });
    }

    /** 查询刚体质量。 */
    public double queryMass(final int runtimeId) {
        return queryOnWorker(() -> {
            RigidBodyState b = worker.simulation().getBody(runtimeId);
            return b != null ? b.mass() : 0.0;
        });
    }

    /** 查询刚体位置（写入 dest3）。 */
    public boolean queryPosition(final int runtimeId, final double[] dest3) {
        return queryOnWorker(() -> {
            RigidBodyState b = worker.simulation().getBody(runtimeId);
            if (b == null || dest3 == null || dest3.length < 3) return false;
            dest3[0] = b.position.x; dest3[1] = b.position.y; dest3[2] = b.position.z;
            return true;
        });
    }

    /** 查询刚体是否为柔体。 */
    public boolean queryIsSoft(final int runtimeId) {
        return queryOnWorker(() -> {
            RigidBodyState b = worker.simulation().getBody(runtimeId);
            return b != null && b.isSoft();
        });
    }

    /** 查询刚体线速度（写入 dest3；体不存在返回 false）。 */
    public boolean queryVelocity(final int runtimeId, final double[] dest3) {
        return queryOnWorker(() -> {
            RigidBodyState b = worker.simulation().getBody(runtimeId);
            if (b == null || dest3 == null || dest3.length < 3) return false;
            dest3[0] = b.linearVelocity.x; dest3[1] = b.linearVelocity.y; dest3[2] = b.linearVelocity.z;
            return true;
        });
    }

    /** 查询与给定世界空间包围盒相交的所有活跃物理体（兼容层 getAllIntersecting 使用）。 */
    public java.util.List<Integer> intersectingBodies(final double x0, final double y0, final double z0,
                                                      final double x1, final double y1, final double z1) {
        return queryOnWorker(() -> {
            java.util.List<Integer> hits = new ArrayList<>();
            for (RigidBodyState b : worker.simulation().bodies().values()) {
                if (b.isRemoved()) continue;
                // 质心为中心的粗半径盒（MVP：按质量粗估结构半尺寸）
                final double r = 1.5 + Math.cbrt(Math.max(0.0, b.mass())) * 0.25;
                final double hx = b.position.x, hy = b.position.y, hz = b.position.z;
                if (hx + r >= x0 && hx - r <= x1 && hy + r >= y0 && hy - r <= y1 && hz + r >= z0 && hz - r <= z1) {
                    hits.add(b.runtimeId);
                }
            }
            return hits;
        });
    }

    // ===== 内部 =====

    /** 在 worker 线程执行只读查询并返回结果（主线程调用，短暂阻塞等 worker）。 */
    private <T> T queryOnWorker(SupplierQuery<T> q) {
        if (Thread.currentThread() == workerThreadRef()) {
            return q.query();
        }
        java.util.concurrent.CompletableFuture<T> fut = new java.util.concurrent.CompletableFuture<>();
        worker.post(() -> {
            try {
                fut.complete(q.query());
            } catch (Throwable t) {
                fut.completeExceptionally(t);
            }
        });
        try {
            return fut.get(100, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取 worker 线程引用（供 isWorkerThread 判定）。 */
    private Thread workerThreadRef() {
        // 通过 SableWorker 的公开访问器（新增）获取
        return worker.currentThread();
    }

    @FunctionalInterface
    private interface SupplierQuery<T> {
        T query();
    }

    private void publishPoseSnapshots(SableSimulationContext ctx) {
        for (RigidBodyState b : ctx.bodies().values()) {
            if (b.isRemoved()) continue;
            worker.emit(new SableMessages.PoseSnapshot(
                    b.runtimeId, b.sceneId,
                    b.position.x, b.position.y, b.position.z,
                    b.orientation.x, b.orientation.y, b.orientation.z, b.orientation.w,
                    b.linearVelocity.x, b.linearVelocity.y, b.linearVelocity.z,
                    b.angularVelocity.x, b.angularVelocity.y, b.angularVelocity.z
            ));
        }
    }

    // ===== ★ 2026-09-01 官方 Rapier 引擎辅助 =====

    /** ★ 2026-09-06 【空间任务阶段4 回调：发布空间位姿】空间任务完成 getPoseBatch
     *  （d.pose 已是投影/主世界坐标）→ 本回调为空间内全部结构发布 PoseSnapshot
     *  （渲染镜像/广播/激活查询消费）。空间id 传入；从引擎空间成员读取 d.pose。 */
    private void publishSpacePoses(final int spaceId) {
        try {
            final OfficialRapierEngine eng =
                    officialEngine;
            final java.util.List<Integer> mems = eng.spaceMembers(spaceId);
            if (mems.isEmpty()) return;
            for (final int rt : mems) {
                final double[] d = eng.physicalizedData(rt).pose;
                final double[] store = d;
                if (store == null) continue;
                if (store[0] == 0 && store[1] == 0 && store[2] == 0) continue;   // 未更新
                worker.emit(new SableMessages.PoseSnapshot(
                        rt, 0 /*sceneId*/, store[0], store[1], store[2],
                        store[3], store[4], store[5], store[6],
                        0, 0, 0, 0, 0, 0));
            }
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] publishSpacePoses failed: {}", t.toString());
        }
    }

    /** 官方引擎路径：注册占位 RigidBodyState（status/激活查询/bodies() 兼容读）。 */
    private void registerPlaceholderBody(SableSimulationContext ctx, SableMessages.BodyImport imp,
                                         int runtimeId, int sceneId) {
        try {
            final RigidBodyState body = new RigidBodyState(runtimeId, sceneId);
            body.position.set(imp.px(), imp.py(), imp.pz());
            body.orientation.set(imp.qx(), imp.qy(), imp.qz(), imp.qw()).normalize();
            body.setSleeping(false);
            ctx.registerBody(runtimeId, sceneId, body);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] registerPlaceholderBody failed {}", t.toString());
        }
    }

    /**
     * 官方引擎路径：把 BodyImport.voxelDensity（y 最快序 index=(yi*sz+zi)*sx+xi）
     * 转成官方 Rapier addChunk 需要的 16x16x16 section 列表
     * （x 最快序 index = x + (z<<4) + (y<<8)，值 = packBlockState(CORNER, colliderID+1)）。
     * MVP：非空体素 → colliderID=1（硬方块 CORNER 邻域）。
     */
    private java.util.List<com.hdf.cryptand.neoforge.cryptandsable.core.backend.official
            .OfficialRapierEngine.SectionUpload> buildOfficialSections(SableMessages.BodyImport imp) {
        final java.util.List<com.hdf.cryptand.neoforge.cryptandsable.core.backend.official
                .OfficialRapierEngine.SectionUpload> sections = new java.util.ArrayList<>();
        if (imp == null || imp.voxelDensity() == null) return sections;
        final int sx = imp.boundMaxX() - imp.boundMinX() + 1;
        final int sy = imp.boundMaxY() - imp.boundMinY() + 1;
        final int sz = imp.boundMaxZ() - imp.boundMinZ() + 1;
        final int[] density = imp.voxelDensity();
        if ((long) sx * sy * sz != density.length) return sections;

        // ★ 2026-09-01 编码修正：官方 packBlockState(state, colliderID) =
        //   ((int) state.byteRepresentation()) | (colliderID << 16)
        //   —— byteRepresentation = enum ordinal（EMPTY=0, FACE=1, EDGE=2, CORNER=3, INTERIOR=4）
        //   ⚠ 不是 debugColor（0xeb6c0b 等 24 位值）！之前误用 debugColor → 低 16 位
        //   越界（0xb6c0b）→ Rust ALL_VOXEL_PHYSICS_STATES[voxel_state_id] 数组越界 panic。
        final int CORNER_ORDINAL = 3;   // VoxelNeighborhoodState.CORNER.ordinal()
        final int numSX = (sx + 15) >> 4, numSY = (sy + 15) >> 4, numSZ = (sz + 15) >> 4;
        for (int sectionY = 0; sectionY < numSY; sectionY++) {
            for (int sectionZ = 0; sectionZ < numSZ; sectionZ++) {
                for (int sectionX = 0; sectionX < numSX; sectionX++) {
                    final int[] chunk = new int[4096];
                    boolean any = false;
                    for (int by = 0; by < 16; by++) {
                        final int ly = sectionY * 16 + by;
                        if (ly >= sy) continue;
                        for (int bz = 0; bz < 16; bz++) {
                            final int lz = sectionZ * 16 + bz;
                            if (lz >= sz) continue;
                            for (int bx = 0; bx < 16; bx++) {
                                final int lx = sectionX * 16 + bx;
                                if (lx >= sx) continue;
                                final int idx = (ly * sz + lz) * sx + lx;
                                if (idx < 0 || idx >= density.length) continue;
                                if (density[idx] == 0) continue;
                                // 官方编码：CORNER(ordinal=3) | (colliderHandle+1)<<16
                                // ★ 2026-09-05 高 16 位 = 结构方块材质（buildImport 已写
                                //   materialId；缺失 → 默认 1）
                                final int mat = (density[idx] >> 16) & 0xFFFF;
                                chunk[bx + (bz << 4) + (by << 8)] =
                                        CORNER_ORDINAL | ((mat != 0 ? mat : 1) << 16);
                                any = true;
                            }
                        }
                    }
                    if (any) {
                        // ★ 2026-09-06 方块坐标全程主世界（先算质心；body 原点才 far）
                        final int secX = (imp.boundMinX() >> 4) + sectionX;
                        final int secY = (imp.boundMinY() >> 4) + sectionY;
                        final int secZ = (imp.boundMinZ() >> 4) + sectionZ;
                        sections.add(new com.hdf.cryptand.neoforge.cryptandsable.core.backend.official
                                .OfficialRapierEngine.SectionUpload(secX, secY, secZ, chunk));
                    }
                }
            }
        }
        return sections;
    }
}