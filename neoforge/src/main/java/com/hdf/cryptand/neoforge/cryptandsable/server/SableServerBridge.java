package com.hdf.cryptand.neoforge.cryptandsable.server;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.api.CryptandSubLevelApi;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.api.physics.BodyParams;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.OfficialRapierEngine;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.SectionKey;
import com.hdf.cryptand.neoforge.cryptandsable.core.destruction.DestructionEvent;
import com.hdf.cryptand.neoforge.cryptandsable.network.SablePoseResponsePayload;
import com.hdf.cryptand.neoforge.cryptandsable.network.SableSubLevelPosePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 服务器桥（SableServerBridge）—— 主线程(Server) 与核心之间的双向翻译。
 *
 * <p>职责（数据归属矩阵：主线程 Server 侧壳）：
 * <ul>
 *   <li>【采集】把 world 里的方块结构转成 {@link SableMessages.BodyImport} → 核心</li>
 *   <li>【写回】消费核心出站（位姿快照 → 只读镜像；破坏列表 → 应用破坏）</li>
 *   <li>【心跳】驱动 {@link SableServerHeartbeat} → 核心心跳</li>
 * </ul>
 */
public final class SableServerBridge implements SableServerBridgeAccess {
    private CryptandSable core;
    private final SableServerHeartbeat heartbeat = new SableServerHeartbeat();
    private final SableSnapshotStore snapshots = new SableSnapshotStore();
    private volatile ServerLevel level;

    public SableServerBridge(CryptandSable core) {
        this.core = core;
    }

    /** 世界进入：绑定 level。 */
    public void bind(ServerLevel level, CryptandSable core) {
        this.level = level;
        this.core = core;
        this.snapshots.clear();
    }

    /** ★ 2026-09-02 mixin 高亮访问：当前核心。 */
    @Override
    public CryptandSable bridgeCore() {
        return this.core;
    }

    /** ★ 2026-09-02 mixin 高亮访问：当前世界。 */
    @Override
    public net.minecraft.server.level.ServerLevel bridgeLevel() {
        return this.level;
    }

    /** 位姿快照镜像访问（服务端碰撞/破坏按 runtimeId 查最新位姿）。 */
    public SableSnapshotStore snapshotStore() {
        return this.snapshots;
    }

    // ===== ★ 2026-09-01 pull 渲染：请求/回复 + 服务端限流 =====

    /** 服务端全局缓存版本（亚层 add/remove/变化时递增；客户端对比决定是否补发大缓存）。 */
    private static final java.util.concurrent.atomic.AtomicInteger CACHE_VERSION =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 缓存版本递增（亚层物理化/拆卸/变化时调用）。 */
    public static void bumpCacheVersion() {
        CACHE_VERSION.incrementAndGet();
    }

    /** 当前缓存版本。 */
    public static int cacheVersion() {
        return CACHE_VERSION.get();
    }

    /** 服务端限流：上一秒窗口内的回复计数（窗口毫秒戳）。 */
    private long lastPoseReplyWindowMs = 0L;
    private int poseRepliesThisWindow = 0;

    /** 服务端每秒最大位姿回复次数（配置；0 = 不限/每请求都回）。 */
    private static int serverMaxRepliesPerSecond() {
        try {
            return ConfigCryptandSable.SABLE_POSE_SERVER_MAX_PER_SEC.get();
        } catch (final Throwable t) {
            return 0; // 不限
        }
    }

    /**
     * 服务端处理客户端渲染位姿请求（pull）：回复该玩家【全部运动体】的最新位姿。
     * 服务端限流：每秒最大回复次数（超过 → 跳过直到下一秒）。
     * 附带当前缓存版本；若与请求版本不一致 → 先补发大缓存（亚层渲染数据）。
     */
    public static void handlePoseRequest(
            net.neoforged.neoforge.network.handling.IPayloadContext context) {
        try {
            if (!(context.player() instanceof net.minecraft.server.level.ServerPlayer sp)) {
                return;
            }
            final CryptandSable cs =
                    CryptandSable.instance();
            if (cs == null || !cs.isStarted() || !cs.isOfficialEngine()) return;
            final SableServerBridge bridge =
                    cs.bridge();
            // 从网络上下文取请求 payload（IPayloadContext 不直接带 payload →
            // 由 payload handler 传入；这里从 playPayload 上下文无法取 → 处理在
            // SablePoseRequestPayload.handle 中 enqueueWork 后调用本方法，无 payload。
            // ⚠ 无法拿到 cacheVersion（handler 里 payload 可用）——重构：handler 直连 bridge.
            final int reqCacheVersion = lastReqCacheVersion.get();
            bridge.replyPose(sp, reqCacheVersion);
        } catch (final Throwable ignored) {
            // 请求处理失败忽略
        }
    }

    /** 最近一次请求的 cacheVersion（handler 传入）。 */
    private static final java.util.concurrent.atomic.AtomicInteger lastReqCacheVersion =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 设置最近请求缓存版本（payload handler 调用）。 */
    public static void setLastReqCacheVersion(int v) {
        lastReqCacheVersion.set(v);
    }

    /** 回复位姿（服务端限流；附带当前缓存版本；版本不一致 → 补发大缓存更新）。 */
    public synchronized void replyPose(net.minecraft.server.level.ServerPlayer sp, int reqCacheVersion) {
        final int maxPerSec = serverMaxRepliesPerSecond();
        if (maxPerSec > 0) {
            final long now = System.currentTimeMillis();
            if (now - lastPoseReplyWindowMs >= 1000L) {
                lastPoseReplyWindowMs = now;
                poseRepliesThisWindow = 0;
            }
            if (poseRepliesThisWindow >= maxPerSec) {
                return; // 超过每秒上限 → 跳过（下一秒恢复）
            }
            poseRepliesThisWindow++;
        }
        // ★ 2026-09-01 缓存版本不一致（结构新增/移除/变化）→ 先补发该玩家全部亚层大缓存
        //   （SableSubLevelRenderPayload），再回复位姿。否则仅回复位姿（必要数据）。
        final int serverVer = cacheVersion();
        if (reqCacheVersion != serverVer) {
            try {
                // 全量刷新该玩家渲染缓存（亚层方块数据）
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] pose req cacheVersion {} != {} -> resend render payloads",
                        reqCacheVersion, serverVer);
                final CryptandSable cs =
                        CryptandSable.instance();
                if (cs != null) {
                    // 遍历 MOVED 所有亚层 → broadcastRenderSync（发送到指定玩家）
                    cs.resendRenderDataTo(sp);
                }
            } catch (final Throwable ignored) {
                // 补发失败：仍回复位姿（下次请求再补）
            }
        }
        // 收集全部运动体（snapshots 有最新位姿；无位置则在 MOVED 表）
        final java.util.List<Integer> ids = new java.util.ArrayList<>();
        final java.util.List<Double> poses = new java.util.ArrayList<>();
        for (final SableMessages.PoseSnapshot ps
                : snapshots.all()) {
            ids.add(ps.runtimeId());
            poses.add(ps.px());
            poses.add(ps.py());
            poses.add(ps.pz());
            poses.add(ps.qx());
            poses.add(ps.qy());
            poses.add(ps.qz());
            poses.add(ps.qw());
        }
        if (ids.isEmpty()) {
            // 无运动体：仍回复（带版本；客户端确认版本一致）
            try {
                sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                        new SablePoseResponsePayload(
                                serverVer, new int[0], new double[0])));
            } catch (final Throwable ignored) {
            }
            return;
        }
        final int[] idArr = new int[ids.size()];
        final double[] poseArr = new double[poses.size()];
        for (int i = 0; i < idArr.length; i++) idArr[i] = ids.get(i);
        for (int i = 0; i < poseArr.length; i++) poseArr[i] = poses.get(i);
        try {
            sp.connection.send(new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(
                    new SablePoseResponsePayload(
                            serverVer, idArr, poseArr)));
        } catch (final Throwable ignored) {
            // 发送失败忽略
        }
    }

    /** 每游戏 tick（主线程）：心跳 + 消费出站消息。 */
    public void tick(long serverTick) {
        if (core == null || !core.isStarted()) return;

        // 1) 心跳（推进预算：用上一步实际耗时自适应；开始默认为目标）
        SableServerHeartbeat.SableBudget b = heartbeat.tick(serverTick, lastPhysNanos);
        core.onServerTick(b.serverTick(), b.steps(), b.budgetNanos());

        // 2) 消费出站：位姿→只读快照镜像 + 广播客户端；破坏→应用列表；
        //    激活查询→应答（物理激活取决于附近区块加载/强制加载，2026-09-01）。
        for (Object msg : core.drainOutbound()) {
            if (msg instanceof SableMessages.PoseSnapshot ps) {
                snapshots.put(ps);
                broadcastPose(ps);
            } else if (msg instanceof SableMessages.ChunkActivationQuery q) {
                handleActivationQuery(q);
            } else if (msg instanceof DestructionEvent de) {
                applyDestruction(de);
            }
        }

        // 3) ★ 2026-09-01 异步世界碰撞收集：worker step 后发查询 → 主线程收集回传。
        //    物理体冻结期间每 tick 收集（防 free-fall）；收集成功 markCollisionReady。
        //    ★ 2026-09-06 【电路仿真核心模式】主线程只与核心交互：收集查询从核心取，
        //    收集完成后【只调 core.postCollectResult】——上传/材料化/解冻由核心（空间任务）
        //    完成，数据转发经核心。
        if (core.isOfficialEngine()) {
            try {
                OfficialRapierEngine eng =
                        core.officialEngine();
                // ★ 2026-09-05 【空间级打包请求（用户定案）】优先 drain 空间级扫描请求：
                //   对包内所有区域统一收集 → 单条回传（带空间 UUID 特征 + 时间戳）。
                final java.util.List<SableMessages.SpaceScanRequest> spaceReqs =
                        eng.drainSpaceScanRequests();
                if (!spaceReqs.isEmpty()) {
                    for (final SableMessages.SpaceScanRequest req : spaceReqs) {
                        collectSpaceScanRequest(req);
                    }
                } else {
                    // 兼容回退：逐成员查询（旧路径）
                    final java.util.List<Object> queries = eng.drainCollectQueries();
                    for (final Object qo : queries) {
                        if (!(qo instanceof SableMessages.WorldCollectQuery q)) continue;
                        // 用该体当前世界位置收集（worker 发查询时 cx/cy/cz 固定 0 → 用快照）
                        final SableMessages.PoseSnapshot ps = snapshots.get(q.runtimeId());
                        final double px = ps != null ? ps.px() : q.cx();
                        final double py = ps != null ? ps.py() : q.cy();
                        final double pz = ps != null ? ps.pz() : q.cz();
                        final int radius = ConfigCryptandSable.SABLE_WORLD_COLLISION_RADIUS.get();
                        // ★ 2026-09-01 调试日志：收到收集查询
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[CryptandSable] received collect query rt={} bounds=[{},{},{}..{},{},{}] "
                                        + "anchor=({},{},{}) radius={}",
                                q.runtimeId(), q.minX(), q.minY(), q.minZ(),
                                q.maxX(), q.maxY(), q.maxZ(), px, py, pz, radius);
                        // ★ 2026-09-01 包围盒收集优先（超长/超窄结构全覆盖）；否则中心点回退
                        final boolean collected = (q.maxX() >= q.minX() && q.maxY() >= q.minY()
                                && (q.maxX() - q.minX() > 0 || q.maxY() - q.minY() > 0
                                    || q.maxZ() - q.minZ() > 0))
                                ? collectWorldAtBounds(q, radius)
                                : collectWorldAt(px, py, pz, radius);
                        // ★ 2026-09-06 收集结果【只投递核心】：核心决定上传/解冻
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[CryptandSable] collect result rt={} collected={} -> postCollectResult",
                                q.runtimeId(), collected);
                        // ★ 2026-09-06 扫描表由核心在 onCollectResult 内统一维护（消息池语义）；
                        //   主线程收集文件缓存的扫描表经 core.postCollectResult 传入（已有 storeScannedBlocks 路径保留）
                        // ★ 2026-09-07 回传特征（物理空间 UUID 拷贝）→ 核心路由校验
                        core.postCollectResult(q.runtimeId(), q.feature(), collected,
                                CryptandSable.instance()
                                        .worldUploader().sectionCache(q.runtimeId()));
                    }
                }
            } catch (final Throwable ignored) {
                // 收集失败不阻断主体（可下一 tick 重试）
            }
        }

        // 4) ★ 2026-09-01 实体粘附：站在结构上的实体随结构移动（被带走）。
        //    在消费完位姿后运行（用最新快照变换实体局部坐标）。
        if (level != null) {
            SableEntityStickHandler.tick(level);
        }
        // ★ 2026-09-02 高亮 debug 由 mixin（SableServerBridgeHighlightMixin）处理：
        //    仅 debugHighlight=true 时 mixin 应用 → 关闭时此处零开销。
    }

    /**
     * ★ 2026-09-01 世界收集（主线程）：以该体世界位置为中心、radius 半径内上传世界 section
     * （WorldChunkUploader.uploadAroundAnchor 圆形）。成功 → true。
     */
    private boolean collectWorldAt(double px, double py, double pz, int radius) {
        if (level == null) return false;
        try {
            final CryptandSable cs2 = core;
            final int bx = (int) Math.floor(px), by = (int) Math.floor(py), bz = (int) Math.floor(pz);
            final int rt = -1;   // anchor 回退路径无可信 runtimeId；shape 模式下不做缓存归属
            final boolean shapeMode = ConfigCryptandSable.SABLE_SHAPE_COLLISION.get();
            if (shapeMode) {
                // ★ 2026-09-04 shape 模式（统一为陪体）：anchor 回退不建 voxel 陪体/global
                //   ground ——只当"世界方块是否存在"的探测（结构承接统一走 bounds 路径，
                //   经 storeScannedBlocks → ensureShapeCompanion；此处 gather 供紧邻落地）。
                //   也设红框裁剪（±radius 内才记录，黄框不超红框）。
                cs2.worldUploader().setClipBounds(
                        bx - radius, by - radius, bz - radius,
                        bx + radius, by + radius, bz + radius);
                cs2.worldUploader().setUploadTargetBody(-1);
                cs2.worldUploader().setUploadMountBody(-1);
                cs2.worldUploader().uploadAroundAnchor(level, bx, by, bz, radius);
                return true;
            }
            // 结构包围盒退化为中心点附近（用±半径扩展）
            cs2.worldUploader().uploadAroundAnchor(level, bx, by, bz, radius);
            // ★ 2026-09-01 收集完成 → finalize（重建 octree；bounds 变了才重建）
            cs2.worldUploader().finalizeCollection();
            return true;
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] world collect failed: {}", t.toString());
            return false;
        }
    }

    /** ★ 2026-09-05 【空间级打包收集（用户定案：按物理空间打包）】
     *  对空间扫描请求（SpaceScanRequest）包内【所有区域】统一收集：逐个 region 走
     *  collectWorldAtBounds（复用逐成员逻辑），收集结果缓存到各成员 sectionCache；
     *  全部完成后组装 Map<runtimeId, sectionCache> → 单条回传核心
     *  （postSpaceScanResult，带空间 UUID 特征 + 时间戳；核心批量受理+解冻）。 */
    private void collectSpaceScanRequest(final SableMessages.SpaceScanRequest req) {
        if (level == null) return;
        final java.util.Map<Integer, java.util.Map<
                SectionKey, int[]>> all =
                new java.util.HashMap<>();
        final int radius = ConfigCryptandSable.SABLE_WORLD_COLLISION_RADIUS.get();
        // ★ 2026-09-05 【性能优化】序号遍历替代 regions().indexOf(r)（O(n²)→O(n)）；
        //   region 多时（超大结构拆多成员）显著降主线程开销。
        int regionIndex = 0;
        for (final SableMessages.ScanRegion r : req.regions()) {
            regionIndex++;
            try {
                final SableMessages.WorldCollectQuery q = new SableMessages.WorldCollectQuery(
                        r.runtimeId(), r.minX(), r.minY(), r.minZ(),
                        r.maxX(), r.maxY(), r.maxZ(), r.cx(), r.cy(), r.cz(), r.radius(), req.feature());
                final boolean collected = (q.maxX() >= q.minX() && q.maxY() >= q.minY()
                        && (q.maxX() - q.minX() > 0 || q.maxY() - q.minY() > 0
                            || q.maxZ() - q.minZ() > 0))
                        ? collectWorldAtBounds(q, radius)
                        : collectWorldAt(q.cx(), q.cy(), q.cz(), radius);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] space-scan collect rt={} collected={} (packaged, region {}/{})",
                        r.runtimeId(), collected, regionIndex, req.regions().size());
                // 收集结果（sectionCache 累积缓存）→ 组装回传
                final java.util.Map<SectionKey,
                        int[]> cached =
                        CryptandSable.instance()
                                .worldUploader().sectionCache(r.runtimeId());
                if (cached != null && !cached.isEmpty()) {
                    all.put(r.runtimeId(), cached);
                }
            } catch (final Throwable t) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] space-scan collect failed rt={}: {}", r.runtimeId(), t.toString());
            }
        }
        // 单条回传核心（空间 UUID 特征 + 时间戳；核心批量受理+解冻）
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] space-scan result space={} regions={} collected={} -> postSpaceScanResult ts={}",
                req.spaceId(), req.regions().size(), all.size(), req.timestamp());
        core.postSpaceScanResult(req.spaceId(), req.feature(), req.timestamp(), all);
    }

    /**
     * ★ 2026-09-01 世界收集（主线程，按结构包围盒）：覆盖超长/超窄/多形态结构。
     * 成功（无异常）→ true（包括"空列表=周围无方块"）。
     */
    private boolean collectWorldAtBounds(SableMessages.WorldCollectQuery q, int radius) {
        if (level == null) return false;
        try {
            // ★ 2026-09-04 【全 shape】只扫描缓存 → materializeScannedBlocks → ensureShapeCompanion
            //   （fixed 体素陪体）承接；★ 2026-09-05 用户定案：禁止 global；
            //   陪体 = 固定物理结构（fixed voxel），世界地形上传到陪体（addChunkToBody）。
            final OfficialRapierEngine eng =
                    core.officialEngine();
            core.worldUploader().setUploadTargetBody(q.runtimeId());   // 缓存归属
            // ★ 2026-09-05 【禁止 global】先建陪体（fixed voxel）→ 上传目标 = 陪体 id
            //   （addChunk global=false id=陪体）——不再 addWorldChunk(global=true)。
            final int companionId = eng.ensureShapeCompanion(q.runtimeId());
            core.worldUploader().setUploadMountBody(companionId);
            // ★ 2026-09-06 【铁律：主线程只做 Level 交互，JNI 全异步】不再在主线程
            //   调 ensureCompanionBodyCreated（JNI createSubLevel）——陪体创建/上传由
            //   核心（空间任务阶段2 materializeScannedBlocks）统一完成；主线程只读 Level
            //   写 sectionCache（纯 Java 缓存），结果经 core.postCollectResult 投递核心。
            // ★ 2026-09-05 【未加载保护（用户定案）】不再清空 sectionCache！
            //   原 clearSectionCache 每轮清空 → 未 loaded 区块 section 不写入 → storeScannedBlocks
            //   （替换语义）时从扫描表消失 → 陪体 box 被删 → 结构失去支撑/出错。
            //   新行为：缓存【累积保留】；本轮 uploadSection 只【覆盖已加载 section】；
            //   未加载 section 旧值保留（保持原状）。区域由 scannedThisRound 标记。
            core.worldUploader().beginScanRound();
            // ★ 2026-09-04 扫描裁剪：只扫【红框 = 结构包围盒边缘 ± radius】内的方块
            //   （黄框/陪体严格在红框内，不超过红框）。
            //   ★ 2026-09-05 边缘外扩（用户定案）：clip = 结构 bounds 边缘外扩 radius
            //   （不规则形状——L 形/长条/非中心——统一由 bounds 边缘覆盖，不再中心 ± r
            //   导致远处部分漏扫）。
            final int cxL = q.minX() - radius, cyL = q.minY() - radius, czL = q.minZ() - radius;
            final int cxH = q.maxX() + radius, cyH = q.maxY() + radius, czH = q.maxZ() + radius;
            // ★ 2026-09-05 【暂时取消主线程扫描缓存（用户定案）】不再 canSkipRescan 跳过：
            //   每轮全量重扫（放下一瞬间后有黄框/陪体持续更新；修复结构静止后不再被扫描/
            //   陪体不跟随 → 穿透）。
            core.worldUploader().setClipBounds(cxL, cyL, czL, cxH, cyH, czH);
            // ★ 2026-09-05 【绝对坐标】Rust 直接用主世界坐标 → section 无原点偏移（恒 0；
            //   setSpaceOriginSectionOffset 已弃置）。
            core.worldUploader().setSpaceOriginSectionOffset(0, 0, 0);
            core.worldUploader().uploadAroundBounds(level,
                    q.minX(), q.minY(), q.minZ(),
                    q.maxX(), q.maxY(), q.maxZ(), radius);
            // ★ 2026-09-05 区域清理：删缓存中【clip 范围外】的 section（结构移动后旧位置
            //   不残留）；clip 内但未加载的保留（旧值不动）。
            core.worldUploader().pruneSectionCacheOutOfClip(q.runtimeId());
            // ★ 2026-09-05 记录本次成功收集（变化检测：下次 clip 相同 + 缓存全量 → 跳过）。
            core.worldUploader().recordScanDone(q.runtimeId(),
                    cxL, cyL, czL, cxH, cyH, czH);
            // ★ 2026-09-06 【转发经核心】主线程不再直接 storeScannedBlocks/materializeScannedBlocks
            //   （native 写入）——扫描结果经 core.postCollectResult 投递，核心（空间任务）
            //   完成存储+上传陪体+解冻（电路仿真核心模式：数据转发必须经过核心）。
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] collectWorldAtBounds(shape) rt={} scanned={} scannedThisRound={} "
                            + "bounds=[{},{},{}..{},{},{}] radius={} (delegated to core)",
                    q.runtimeId(),
                    core.worldUploader().sectionCache(q.runtimeId()).size(),
                    core.worldUploader().scannedThisRoundCount(),
                    q.minX(), q.minY(), q.minZ(), q.maxX(), q.maxY(), q.maxZ(), radius);
            return true;
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] world collect(bounds) failed: {}", t.toString());
            return false;
        }
    }

    /** ★ 2026-09-02 调试：格式化方块列表（（x,y,z）逗号串；超 64 个截断）。 */
    private static String formatBlocks(final java.util.List<long[]> blocks) {
        final int max = 64;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < blocks.size(); i++) {
            if (i >= max) {
                sb.append(", ...").append(blocks.size() - max).append(" more");
                break;
            }
            if (i > 0) sb.append(", ");
            final long[] b = blocks.get(i);
            sb.append('(').append(b[0]).append(',').append(b[1]).append(',').append(b[2]).append(')');
        }
        return sb.toString();
    }

    /** 应答物理激活查询：判定各体所在区块是否加载/强制加载 → 唤醒或休眠；同时采样地表支撑。 */
    private void handleActivationQuery(SableMessages.ChunkActivationQuery q) {        if (level == null) return;
        try {
            final java.util.Set<Integer> active = CryptandSubLevelApi
                    .resolveActiveBodies(level, q.runtimeIds(), q.xs(), q.ys(), q.zs());
            final java.util.Map<Integer, Double> groundYs = CryptandSubLevelApi
                    .resolveGroundYs(level, q.runtimeIds(), q.xs(), q.ys(), q.zs());
            core.applyActivation(active, groundYs);
        } catch (final Throwable t) {
            // 激活判定失败不阻断主体
        }
    }

    /** 位姿快照 → 客户端渲染（亚层物理运动跟随；每 tick 广播最新位姿）。
     *  ★ 2026-09-05 顺带携带该结构所属物理空间范围（scanCenter±half，绿框调试）。 */
    private void broadcastPose(SableMessages.PoseSnapshot ps) {
        if (level == null || ps.runtimeId() <= 0) return;
        try {
            double spx = 0, spy = 0, spz = 0, shx = 0, shy = 0, shz = 0;
            // ★ 2026-09-05 【一世界一空间】黄框显示已放弃置区：不再从 spaceRectFor
            //   填充空间并集框（spaceRectFor 弃置；payload 字段恒 0，客户端不渲染）
            final SableSubLevelPosePayload payload =
                    new SableSubLevelPosePayload(
                            ps.runtimeId(),
                            ps.px(), ps.py(), ps.pz(),
                            ps.qx(), ps.qy(), ps.qz(), ps.qw(),
                            spx, spy, spz, shx, shy, shz);
            final net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet =
                    new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload);
            for (final net.minecraft.server.level.ServerPlayer player : level.players()) {
                player.connection.send(packet);
            }
        } catch (Throwable t) {
            // 位姿广播失败不阻断主体（忽略）
        }
    }

    private volatile long lastPhysNanos = 0L;

    /** 记录上一步物理耗时（供心跳自适应；留接口给测量/诊断）。 */
    public void reportPhysNanos(long nanos) {
        this.lastPhysNanos = nanos;
    }

    private void applyDestruction(DestructionEvent de) {
        if (level == null) return;
        // 从局部坐标 + 快照位姿换算世界方块位（MVP：用质心近似）
        SableMessages.PoseSnapshot ps = snapshots.get(de.bodyId());
        if (ps == null) return;
        BlockPos worldPos = new BlockPos(
                (int) Math.floor(ps.px() + de.localX()),
                (int) Math.floor(ps.py() + de.localY()),
                (int) Math.floor(ps.pz() + de.localZ()));
        // 破坏应用：把该方块设为空气（MVP 直接落世界；后续细化到结构内部方块）
        if (level.isLoaded(worldPos)) {
            level.setBlockAndUpdate(worldPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        }
    }

    /** 采集：把世界某个 box 的方块结构转成 BodyImport（采样密度 MVP：每方块质量=密度系数）。 */
    public int importStructure(ServerLevel level, BlockPos min, BlockPos max) {
        if (core == null || !core.isStarted()) return 0;
        int sx = max.getX() - min.getX() + 1;
        int sy = max.getY() - min.getY() + 1;
        int sz = max.getZ() - min.getZ() + 1;
        if (sx * sy * sz <= 0 || sx * sy * sz > 4096 * 8) return 0; // 防巨大结构

        int[] density = new int[sx * sy * sz];
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    BlockState st = level.getBlockState(new BlockPos(min.getX() + x, min.getY() + y, min.getZ() + z));
                    int d = st.isAir() ? 0 : 1000; // 密度 1000 缺陷编码（高 16 位）
                    density[x + z * sx + y * (sx * sz)] = (d << 16) & 0xFFFF0000;
                }
            }
        }
        double cx = (min.getX() + max.getX()) * 0.5;
        double cy = (min.getY() + max.getY()) * 0.5;
        double cz = (min.getZ() + max.getZ()) * 0.5;
        return com.hdf.cryptand.neoforge.cryptandsable.api.registration
                .CryptandSableRegistration.importBlockRigidBody(
                        cx, cy, cz, 0, 0, 0, 1, // 位置 + 单位朝向
                        min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ(),
                        density,
                        BodyParams.rigid(0));
    }

    /** 最近快照（只读）。 */
    public SableMessages.PoseSnapshot snapshot(int runtimeId) {
        return snapshots.get(runtimeId);
    }

    /** 清理（世界卸载）。 */
    public void clear() {
        snapshots.clear();
        level = null;
    }
}