package com.hdf.cryptand.neoforge.cryptandsable.client.render;

import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandBounds3i;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandLevelPlot;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandSubLevel;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.CryptandSubLevelContainer;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.network.SablePoseRequestPayload;
import com.hdf.cryptand.neoforge.cryptandsable.network.SableSubLevelRenderPayload;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端渲染模块（SableClientRenderModule）。
 *
 * <p>缓存核心位姿快照（只读镜像）+ 亚层物理化方块数据，供渲染线程插值/绘制物理体与结构。
 * 纯消费：不做物理计算（数据归属矩阵 Client=表现镜像）。
 */
public final class SableClientRenderModule {
    /** 全局单例（客户端事件/payload handler 统一走它）。 */
    public static final SableClientRenderModule INSTANCE = new SableClientRenderModule();

    private final Map<Integer, SableMessages.PoseSnapshot> poseCache = new ConcurrentHashMap<>();

    /** ★ 2026-09-05 【弃置区（一世界一空间：黄框显示放弃置区）】物理空间并集框缓存
     *  （runtimeId → {minX,minY,minZ,maxX,maxY,maxZ}，黄框调试）。服务端不再广播
     *  （broadcastPose spx..shz 恒 0）；保留字段供 clear 清理兼容。 */
    private final Map<Integer, double[]> spaceRectCache = new ConcurrentHashMap<>();

    /** 亚层渲染数据（subLevelId → 方块数据+锚）。 */
    private final Map<java.util.UUID, SableSubLevelRenderData> subLevelCache = new ConcurrentHashMap<>();

    /** ★ 2026-09-01 渲染插值：各体【上一帧】位姿（prev）——渲染按 partialTick 插值。 */
    private final Map<Integer, SableMessages.PoseSnapshot> prevPoseCache = new ConcurrentHashMap<>();

    /** ★ 插值启用标志（渲染线程每次取 pose 前用 partialTick 拉取）。 */
    private volatile boolean interpEnabled = true;

    /** ★ 2026-09-01 pull 渲染：客户端已载入缓存版本（服务端回复带回；请求时携带）。 */
    private volatile int serverCacheVersion = 0;

    /** 记录服务端缓存版本（客户端下次请求携带；不一致 → 服务端补发大缓存）。 */
    public void setServerCacheVersion(int v) {
        this.serverCacheVersion = v;
    }

    /** 当前缓存版本（请求携带）。 */
    public int serverCacheVersion() {
        return this.serverCacheVersion;
    }

    // ===== ★ 2026-09-01 pull 渲染：客户端主动请求（限流） =====

    /** 客户端限流：上一秒窗口内的请求计数（窗口毫秒戳）。 */
    private long lastPoseReqWindowMs = 0L;
    private int poseReqThisWindow = 0;

    /**
     * 客户端渲染每帧调用：向服务端请求位姿（C2S SablePoseRequestPayload）。
     * 客户端限流：每秒最大请求次数（配置 poseClientMaxPerSecond；超过 → 跳过直到下一秒）。
     * 由渲染线程（onRenderLevel）调用；发送走 net.minecraft.client.Minecraft.connection。
     */
    public void requestPosesFromServer() {
        try {
            final int maxPerSec = ConfigCryptandSable.SABLE_POSE_CLIENT_MAX_PER_SEC.get();
            final long now = System.currentTimeMillis();
            if (now - lastPoseReqWindowMs >= 1000L) {
                lastPoseReqWindowMs = now;
                poseReqThisWindow = 0;
            }
            if (maxPerSec > 0 && poseReqThisWindow >= maxPerSec) {
                return; // 超过每秒上限 → 跳过
            }
            poseReqThisWindow++;
            final net.minecraft.client.multiplayer.ClientPacketListener conn =
                    net.minecraft.client.Minecraft.getInstance().getConnection();
            if (conn == null) return;
            conn.send(new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                    new SablePoseRequestPayload(
                            serverCacheVersion)));
        } catch (final Throwable ignored) {
            // 请求发送失败忽略
        }
    }

    /** 更新某体渲染位姿（来自核心同步）。 */
    public void onPoseSnapshot(SableMessages.PoseSnapshot ps) {
        // ★ 2026-09-01 插值：旧位姿 → prev；新到位姿 → curr
        final SableMessages.PoseSnapshot old = poseCache.put(ps.runtimeId(), ps);
        if (old != null) {
            prevPoseCache.put(ps.runtimeId(), old);
        }
    }

    /** ★ 2026-09-01 渲染插值位姿：curr = 最新快照；prev = 上次；按 partial 插值。 */
    public SableMessages.PoseSnapshot interpPose(int runtimeId, float partial) {
        final SableMessages.PoseSnapshot curr = poseCache.get(runtimeId);
        if (curr == null) return null;
        if (!interpEnabled) return curr;
        final SableMessages.PoseSnapshot prev = prevPoseCache.get(runtimeId);
        if (prev == null) return curr;
        // 位置 lerp + 四元数 slerp（角度不大时组件 lerp 亦可；用 slerp 更稳）
        final double px = prev.px() + (curr.px() - prev.px()) * partial;
        final double py = prev.py() + (curr.py() - prev.py()) * partial;
        final double pz = prev.pz() + (curr.pz() - prev.pz()) * partial;
        final org.joml.Quaterniond qp = new org.joml.Quaterniond(
                prev.qx(), prev.qy(), prev.qz(), prev.qw());
        final org.joml.Quaterniond qc = new org.joml.Quaterniond(
                curr.qx(), curr.qy(), curr.qz(), curr.qw());
        qp.normalize(); qc.normalize();
        final org.joml.Quaterniond qi = qp.slerp(qc, partial);
        return new SableMessages.PoseSnapshot(
                curr.runtimeId(), curr.sceneId(),
                px, py, pz,
                qi.x, qi.y, qi.z, qi.w,
                curr.vx(), curr.vy(), curr.vz(),
                curr.wx(), curr.wy(), curr.wz());
    }

    /** 取某体渲染位姿（渲染线程读；null=尚未同步）。 */
    public SableMessages.PoseSnapshot pose(int runtimeId) {
        return poseCache.get(runtimeId);
    }

    /** 【弃置区（2026-09-05 一世界一空间：黄框显示放弃置区）】记录物理空间并集框
     *  （黄框调试；服务端广播随 PoseSnapshot 携带 min/max）。无调用方，保留。 */
    public void setSpaceRect(int runtimeId, double minX, double minY, double minZ,
                             double maxX, double maxY, double maxZ) {
        if (runtimeId <= 0) return;
        spaceRectCache.put(runtimeId,
                new double[]{minX, minY, minZ, maxX, maxY, maxZ});
    }

    /** 【弃置区（2026-09-05 一世界一空间：黄框显示放弃置区）】取物理空间并集框
     *  （渲染线程读；null=无空间/未同步）。无调用方，保留。 */
    public double[] spaceRect(int runtimeId) {
        return spaceRectCache.get(runtimeId);
    }

    /** 渲染用全部位姿。 */
    public Iterable<SableMessages.PoseSnapshot> allPoses() {
        return poseCache.values();
    }

    // ===== 亚层渲染数据 =====

    /** 待处理 payload 队列（主线程 tick 处理，避免 payload handler 阻塞主线程卡顿）。 */
    private final java.util.concurrent.ConcurrentLinkedQueue<SableSubLevelRenderPayload> pendingPayloads =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** 每客户端 tick 处理（由事件订阅调用；确保渲染线程空闲时批处理）。 */
    public void tick() {
        SableSubLevelRenderPayload p;
        int budget = 4;
        while (budget-- > 0 && (p = pendingPayloads.poll()) != null) {
            processPayload(p);
        }
    }

    /** 入队 payload（payload handler 快速返回；tick 处理）。 */
    public void onSubLevelRenderData(SableSubLevelRenderPayload payload) {
        if (payload == null) return;
        pendingPayloads.add(payload);
    }

    /** 实际处理（主线程 tick；装载/卸载渲染缓存 + 客户端亚层登记）。 */
    private void processPayload(SableSubLevelRenderPayload payload) {
        if (payload.removed()) {
            subLevelCache.remove(payload.subLevelId());
            removeClientSubLevel(payload.subLevelId());
            return;
        }
        SableSubLevelRenderData data = SableSubLevelRenderData.from(payload);
        subLevelCache.put(payload.subLevelId(), data);
        // 客户端亚层：创建/更新 ClientSubLevel 登记到 ClientSubLevelContainer（官方渲染 dispatcher 遍历它）
        ensureClientSubLevel(payload, data);
    }

    /** 创建/更新客户端亚层（登记到核心自有容器；自研渲染 dispatcher 遍历）。 */
    private void ensureClientSubLevel(SableSubLevelRenderPayload payload, SableSubLevelRenderData data) {
        try {
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return;
            final CryptandSubLevelContainer container =
                    CryptandSubLevelContainer.getContainer(mc.level);
            CryptandSubLevel sub =
                    container.getSubLevel(payload.subLevelId());
            if (sub == null) {
                // 创建：位姿位置 = anchor + 0.5（与物理体初始位姿同基准）
                sub = new CryptandSubLevel(mc.level, data.anchor());
                container.registerSubLevel(payload.subLevelId(), sub);
            }
            final CryptandBounds3i bbox =
                    new CryptandBounds3i(
                            payload.boundMinX(), payload.boundMinY(), payload.boundMinZ(),
                            payload.boundMaxX(), payload.boundMaxY(), payload.boundMaxZ());
            // 确保亚层有 plot（plotPos 对齐 anchor 的 chunk 网格原点；
            // 亚层 mesh 网格 = plotPos << logSize chunks，与 renderData 的 RenderSection 原点匹配）
            if (sub.getPlot() == null) {
                final int logSize = 7;
                final net.minecraft.world.level.ChunkPos anchorChunk =
                        new net.minecraft.world.level.ChunkPos(data.anchor().getX() >> 4, data.anchor().getZ() >> 4);
                final net.minecraft.world.level.ChunkPos plotPos =
                        new net.minecraft.world.level.ChunkPos(anchorChunk.x >> logSize, anchorChunk.z >> logSize);
                sub.setPlot(new CryptandLevelPlot(
                        mc.level, plotPos, logSize, sub));
            }
            if (sub.getPlot() != null) {
                sub.getPlot().setBoundingBox(bbox);
                // 2026-09-01：方块编入亚层 chunk 缓冲（渲染经 plot 读亚层方块）。
                fillPlotChunks(sub.getPlot(), data, payload);
            }
        } catch (final Throwable ignored) {
            // 客户端亚层创建失败不影响渲染缓存
        }
    }

    /** 把渲染数据方块编入亚层 plot chunk 缓冲。 */
    private void fillPlotChunks(final CryptandLevelPlot plot,
                                final SableSubLevelRenderData data,
                                final SableSubLevelRenderPayload payload) {
        if (plot == null || data == null) return;
        try {
            final net.minecraft.world.level.Level level = plot.getLevel();
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (level == null || mc.level == null) return;
            for (final SableSubLevelRenderData.RenderBlock block : data.blocks()) {
                if (block.stateId() <= 0) continue;
                final net.minecraft.world.level.block.state.BlockState state =
                        net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(block.stateId());
                if (state == null || state.isAir()) continue;
                // 全局方块位置 = anchor + 偏移（亚层方块以主世界坐标存储）
                final net.minecraft.core.BlockPos worldPos = new net.minecraft.core.BlockPos(
                        data.anchor().getX() + block.dx(),
                        data.anchor().getY() + block.dy(),
                        data.anchor().getZ() + block.dz());
                // 亚层网格：局部索引（相对 plot 网格）→ chunk 内的局部偏移（16 为模）
                final net.minecraft.world.level.ChunkPos globalChunkPos = new net.minecraft.world.level.ChunkPos(worldPos);
                final net.minecraft.world.level.ChunkPos localChunkPos = plot.toLocal(globalChunkPos);
                // LevelChunk 自身坐标 = 亚层网格全局坐标（保证内部 section 索引一致）
                final net.minecraft.world.level.ChunkPos meshChunkPos = plot.meshChunkPos(localChunkPos);
                net.minecraft.world.level.chunk.LevelChunk chunk = plot.getChunk(localChunkPos);
                if (chunk == null) {
                    chunk = plot.newEmptyChunk(localChunkPos);
                }
                if (chunk != null) {
                    // 写入坐标 = mesh chunk 起点 + 方块在 chunk 内偏移（y 不取模）
                    final net.minecraft.core.BlockPos meshPos = new net.minecraft.core.BlockPos(
                            meshChunkPos.getMinBlockX() + (worldPos.getX() & 15),
                            worldPos.getY(),
                            meshChunkPos.getMinBlockZ() + (worldPos.getZ() & 15));
                    if (!chunk.getBlockState(meshPos).equals(state)) {
                        chunk.setBlockState(meshPos, state, false);
                    }
                }
            }
        } catch (final Throwable ignored) {
            // 方块编入失败不影响渲染缓存（自研渲染仍可用）
        }
    }

    /** 移除客户端亚层（卸载）。 */
    private void removeClientSubLevel(java.util.UUID id) {
        try {
            final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.level == null) return;
            CryptandSubLevelContainer
                    .getContainer(mc.level).unregisterSubLevel(id);
        } catch (final Throwable ignored) {
            // 移除失败不影响
        }
    }

    /** 渲染器取用（按亚层 id）。 */
    public SableSubLevelRenderData subLevel(java.util.UUID id) {
        return id == null ? null : subLevelCache.get(id);
    }

    /** 渲染器遍历全部亚层渲染数据。 */
    public Iterable<SableSubLevelRenderData> allSubLevels() {
        return subLevelCache.values();
    }

    /** 亚层渲染数据数量（诊断/探针用）。 */
    public int subLevelCount() {
        return subLevelCache.size();
    }

    public void clear() {
        poseCache.clear();
        spaceRectCache.clear();
        subLevelCache.clear();
    }
}