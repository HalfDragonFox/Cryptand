package com.hdf.cryptand.neoforge.cryptandsable.api;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.CryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.api.message.SableMessages;
import com.hdf.cryptand.neoforge.cryptandsable.api.model.*;
import com.hdf.cryptand.neoforge.cryptandsable.api.physics.BodyParams;
import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableClientRenderModule;
import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableSubLevelRenderData;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.OfficialRapierEngine;
import com.hdf.cryptand.neoforge.cryptandsable.network.SableSubLevelRenderPayload;
import com.hdf.cryptand.neoforge.cryptandsable.persistence.CryptandSubLevelPersistence;
import com.hdf.cryptand.neoforge.cryptandsable.persistence.PersistedBlock;
import com.hdf.cryptand.neoforge.cryptandsable.persistence.PersistedSubLevel;
import com.hdf.cryptand.neoforge.cryptandsable.server.SableDebugScanStore;
import com.hdf.cryptand.neoforge.cryptandsable.server.SableServerBridge;
import com.hdf.cryptand.neoforge.cryptandsable.server.WorldChunkUploader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * CryptandSable 专属亚层 API —— 物理化装载 / 拆卸 / 强制加载的统一入口。
 *
 * <p>给上层（模拟/航空装配器、蓝图工具、以及本项目各 mixin 接管点）提供明确、可复用的
 * 装配语义，避免直接操作内部容器/消息的琐碎细节：
 * <ul>
 *   <li>{@link #physicalize(ServerLevel, BlockPos, Iterable, CryptandBounds3i)} —— 物理化装载：
 *       采样方块 → 创建真实亚层（带 plot、登记进容器）→ 异步注册核心物理体（importBody）。</li>
 *   <li>{@link #dephysicalize(ServerLevel, CryptandServerSubLevel)} —— 拆卸：把亚层按 UUID 移出容器，
 *       并从核心移除对应物理体（removeBody）。</li>
 *   <li>{@link #forceLoadSubLevel(ServerLevel, CryptandSubLevel)} —— 强制加载：确保亚层 plot
 *       覆盖的 chunk 被加载（tick ticket），拆卸/回写前必须先加载。</li>
 * </ul>
 *
 * <p><b>2026-08-31 用户：所有 API 基本是消息驱动通知 + 大规模并发 + 天然线程安全分隔。</b>
 * 本类全部操作经 {@link SubLevelCommandBus}（keyed serial executor）：按【亚层 uuid】投递
 * ——同亚层串行（状态不交错），不同亚层并行（大规模并发互不阻塞）；结果经
 * {@link SubLevelEvents} 通知（ASSEMBLED/DISASSEMBLED/...）。
 *
 * <p>全部方法线程安全：物理体注册/移除经 {@link CryptandSable} 异步 worker 排队，主线程不阻塞。
 * 兼容桩（{@code SubLevelAssemblyHelper.assembleBlocks/moveBlocks}）与各 mod mixin 接管点均
 * 委托本 API，保证"专属 API —— 一套实现，处处可调"。
 */
public final class CryptandSubLevelApi {

    private CryptandSubLevelApi() {
    }

    /** 总线（消息投递中心；按亚层 uuid 分隔）。 */
    private static final SubLevelCommandBus BUS = SubLevelCommandBus.instance();

    // ===== 物理化装载（消息驱动） =====

    /**
     * 物理化装载：把世界中的一块结构注册为物理亚层（参考官方 sable assembleBlocks 语义）。
     * 主入口：接收【世界坐标列表】，锚点/包围盒可由坐标自动推断，也可显式指定。
     *
     * <p>实现：经 {@link SubLevelCommandBus#dispatch} 按 subLevelId 串行创建（同步返回亚层，
     * 保证 mixin/上层拿到结果）；创建完成后 emit ASSEMBLED。
     *
     * @param level  服务端世界
     * @param anchor 结构锚点（质心近似位置，决定亚层创建/plot 定位）
     * @param blocks 结构方块坐标集合（世界坐标）
     * @param bounds 结构包围盒（block 坐标，全轴倍增）；可为 null（由 blocks 推算）
     * @return 已登记亚层；失败（level 非服务端 / container 缺失 / 无方块）→ null
     */
    public static @Nullable CryptandServerSubLevel physicalize(final ServerLevel level, final BlockPos anchor,
                                                       final Iterable<BlockPos> blocks,
                                                       final @Nullable CryptandBounds3i bounds) {
        if (level == null || level.isClientSide() || anchor == null) {
            return null;
        }
        // ★ 2026-09-06 【核心总开关】关闭 → 物理化直接拒绝（不创建亚层/刚体）。
        if (!ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return null;
        }
        // 先收集（主线程读世界方块）；创建经总线串行（key = 预生成亚层位置）
        final List<BlockPos> collected = new ArrayList<>();
        if (blocks != null) {
            for (final BlockPos b : blocks) {
                if (b != null) collected.add(b);
            }
        }
        if (collected.isEmpty()) {
            return null;
        }
        final CryptandBounds3i box = bounds != null ? bounds : inferBounds(collected);
        final String key = "pre-phys:" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
        final CryptandServerSubLevel[] out = new CryptandServerSubLevel[1];
        BUS.dispatch(key, () -> out[0] = physicalize0(level, anchor, collected, box));
        // 通知（命令执行完成后，仍按亚层 uuid 发事件）
        if (out[0] != null) {
            SubLevelEvents.emit(SubLevelEvents.Kind.ASSEMBLED, out[0].getUniqueId(),
                    out[0].getRuntimeId(), out[0]);
        }
        return out[0];
    }

    /** 便捷重载：仅给定坐标列表，自动以列表首个方块为锚、按列表推包围盒。 */
    public static @Nullable CryptandServerSubLevel physicalize(final ServerLevel level, final List<BlockPos> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return null;
        }
        return physicalize(level, blocks.get(0), blocks, null);
    }

    /** 消息版：物理化装载（异步投递，不阻塞调用方；完成 emit ASSEMBLED）。 */
    public static void postPhysicalize(final ServerLevel level, final BlockPos anchor,
                                       final Iterable<BlockPos> blocks,
                                       final @Nullable CryptandBounds3i bounds) {
        if (level == null || level.isClientSide() || anchor == null) {
            return;
        }
        // ★ 2026-09-06 【核心总开关】关闭 → 物理化直接拒绝（不创建亚层/刚体）。
        if (!ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return;
        }
        final List<BlockPos> collected = new ArrayList<>();
        if (blocks != null) {
            for (final BlockPos b : blocks) {
                if (b != null) collected.add(b);
            }
        }
        if (collected.isEmpty()) {
            return;
        }
        final CryptandBounds3i box = bounds != null ? bounds : inferBounds(collected);
        final String key = "phys:" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
        BUS.post(key, () -> {
            final CryptandServerSubLevel sub = physicalize0(level, anchor, collected, box);
            if (sub != null) {
                SubLevelEvents.emit(SubLevelEvents.Kind.ASSEMBLED, sub.getUniqueId(),
                        sub.getRuntimeId(), sub);
            }
        });
    }

    /** 坐标列表 → 物理化核心实现。 */
    private static @Nullable CryptandServerSubLevel physicalize0(final ServerLevel level, final BlockPos anchor,
                                                         final Iterable<BlockPos> blocks,
                                                         final @Nullable CryptandBounds3i bounds) {
        if (level == null || level.isClientSide() || anchor == null) {
            return null;
        }
        // ★ 2026-09-06 【核心总开关】关闭 → 物理化直接拒绝（双保险，防 restore 等旁路）。
        if (!ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return null;
        }
        // ★ 2026-09-03 清空旧黄框（扫描结果随结构生命周期重置）由 debug mixin
        //   （SableScanStoreLifecycleMixin @Inject physicalize0 HEAD）注入——不侵入本方法。
        final List<BlockPos> collected = new ArrayList<>();
        if (blocks != null) {
            for (final BlockPos b : blocks) {
                if (b != null) collected.add(b);
            }
        }
        if (collected.isEmpty()) {
            return null;
        }
        final CryptandBounds3i box = bounds != null ? bounds : inferBounds(collected);

        final CryptandPose3d pose = new CryptandPose3d();
        pose.position().set(anchor.getX() + 0.5, anchor.getY() + 0.5, anchor.getZ() + 0.5);
        final CryptandServerSubLevel subLevel = CryptandServerSubLevel.createWithAnchor(level, box, anchor);
        // 记录精确结构包围盒：locator 按它命中（避免同 chunk 多个独立装配体共享 plot 而交错）
        try {
            final CryptandLevelPlot plot = subLevel.getPlot();
            if (plot != null && box != null) {
                plot.setBoundingBox(new CryptandBounds3i(box));
                // 2026-09-01：方块编入亚层 chunk（碰撞经 LevelAccelerator.getChunk → 亚层拦截读它）。
                fillServerPlotChunks(plot, level, collected);
            }
        } catch (final Throwable ignored) {
            // localBounds 记录失败不影响装载
        }

        final CryptandSubLevelContainer container = CryptandSubLevelContainer.getContainer(level);
        // 登记进容器：getContaining/getSubLevel 后续按 plot / UUID 取回
        container.getSubLevelMap().put(subLevel.getUniqueId(), subLevel);

        // 预分配核心 runtimeId → 亚层（位姿快照/渲染跟随按它索引）
        final int runtimeId = CryptandSable.instance().allocateRuntimeId();
        subLevel.setRuntimeId(runtimeId);

        // ★ 2026-09-01 冻结（首次世界碰撞收集完成前不计算——防 free-fall）：
        //   importBody 异步 worker 处理，需【先】标记 pending（主线程同步），
        //   防 worker 在收集完成前 start step → 结构自由落体穿透。
        final CryptandSable cs =
                CryptandSable.instance();
        final boolean official = cs != null && cs.isOfficialEngine();
        if (official) {
            cs.officialEngine().markCollisionPending(runtimeId);
        }

        // 异步注册核心物理体（worker 线程执行体素采样/质量/气室；主线程不阻塞）
        CryptandSable.instance().importBody(buildImport(level, anchor, collected, box, runtimeId));
        // 装配器关联登记（getContaining(be) 精确命中：独立消息，不混用）
        registerAssociation(anchor, subLevel.getUniqueId());

        // ★ 2026-09-01 物理化=搬移模式：从主世界"拿起"方块 → 亚层显示 + MOVED 填充
        //   （MOVED 同时是碰撞/交互/还原的数据源：feedCollectShapes 服务端读它）。
        try {
            moveBlocksIntoSubLevel(level, subLevel.getUniqueId(), collected);
        } catch (final Throwable ignored) {
            // 搬移失败：主世界方块保留（可手动清理），核心体仍生效
        }

        // ★ 2026-09-02 scan 覆盖修复（用户："修 scan 覆盖"）：解冻前【同步预上传】结构
        //   包围盒周围（含正下方）的世界地形 → native ground（FAR 远处，OfficialRapierEngine
        //   自动平移）。异步收集循环的查询中心可能取 0/错位 → 首个 ground 漏传/错位 → 结构
        //   解冻即掉穿（且越掉越深越扫不到）。此处主线程按真实结构包围盒直传，保证结构脚下有地。
        final int spanX = box.maxX() - box.minX();
        final int spanY = box.maxY() - box.minY();
        final int spanZ = box.maxZ() - box.minZ();
        // ★ 2026-09-03 原生 shape 直接碰撞下跳过体素同步预上传（无用：shape 结构不碰体素
        //   陪体；承接统一由异步收集 → storeScannedBlocks → ensureShapeCompanion(fixed+box)）。
        if (!ConfigCryptandSable.SABLE_SHAPE_COLLISION.get()
                && cs != null && cs.worldUploader() != null
                && cs.worldUploader().available()
                && spanX <= 96 && spanY <= 96 && spanZ <= 96) {
            try {
                final WorldChunkUploader uploader =
                        cs.worldUploader();
                // ★ 2026-09-02 挂到该结构【静态陪体】（不再用 global ground）：动态结构↔陪体 碰撞，四周拖住。
                final OfficialRapierEngine eng =
                        cs.officialEngine();
                // ★ 2026-09-03 穿透根因修复：陪体 bounds 必须覆盖【全部上传的扫面 section】
                //   （结构盒 ± max(radius,4)）。原 bounds=结构盒(1-2格) → 世界 ground 的
                //   local 坐标越界/为负 → Rust SubLevelOctree 无界写错位（幻影）→ octree
                //   找到的 block 在 chunk_map 为空 → 0 manifold → 结构穿落。
                final int pad = Math.max(
                        ConfigCryptandSable.SABLE_WORLD_COLLISION_RADIUS.get(), 4)
                        + 16;   // +16 吸收 section 取整外扩（扫面范围四舍五入到一个整 section）
                final int[] expBox = new int[]{box.minX() - pad, box.minY() - pad, box.minZ() - pad,
                        box.maxX() + pad, box.maxY() + pad, box.maxZ() + pad};
                final int companionId = eng.ensureStaticCompanion(runtimeId, expBox);
                uploader.setUploadTargetBody(-1);          // 缓存归属 shared（去重）
                uploader.setUploadMountBody(companionId);  // 挂载静态陪体
                uploader.uploadAroundBox(level,
                        box.minX(), box.minY(), box.minZ(),
                        box.maxX(), box.maxY(), box.maxZ());
                eng.finalizeStaticWorldBody(companionId, expBox);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] sync ground pre-upload rt={} box=[{},{},{}..{},{},{}] "
                                + "expBox=[{},{},{}..{},{},{}] (scan-fix)",
                        runtimeId, box.minX(), box.minY(), box.minZ(),
                        box.maxX(), box.maxY(), box.maxZ(),
                        expBox[0], expBox[1], expBox[2], expBox[3], expBox[4], expBox[5]);
            } catch (final Throwable ignoredSync) {
                // 同步预上传失败不阻断（异步查询后续重试）
            }
        }

        // ★ 2026-09-01 异步世界碰撞收集（worker → 查询 → 主线程 → 收集 → 解冻）：
        //   importBody 前已【冻结】（pending）；worker 每 step 后发 WorldCollectQuery；
        //   主线程 SableServerBridge 消费 → WorldChunkUploader.uploadAroundAnchor →
        //   markCollisionReady 解冻。超时（配置 collectTimeout）后停止，不再阻塞。
        //   （uploadAroundBox 同步收集已废弃：只为结构落地用周围世界 section 覆盖。）
        CryptandNeoForge.WAF_LOGGER.info(
                "[CryptandSable] physicalize created sub={} runtimeId={} anchor={} blocks={} bounds=[{}..{}]",
                subLevel.getUniqueId(), runtimeId, anchor, collected.size(),
                box.minX() + "," + box.minY() + "," + box.minZ(),
                box.maxX() + "," + box.maxY() + "," + box.maxZ());
        // ★ 2026-09-01 pull 渲染：亚层新增 → 缓存版本递增（客户端下次请求补发大缓存）
        try {
            SableServerBridge.bumpCacheVersion();
        } catch (final Throwable ignored) {
        }
        return subLevel;
    }

    /** 把物理化方块编入服务端亚层 plot chunk（碰撞 LevelAccelerator.getChunk → 亚层拦截读它）。 */
    private static void fillServerPlotChunks(final CryptandLevelPlot plot, final ServerLevel level,
                                             final List<BlockPos> blocks) {
        if (plot == null || level == null || blocks == null || blocks.isEmpty()) return;
        try {
            for (final BlockPos b : blocks) {
                if (b == null) continue;
                final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(b);
                if (state.isAir()) continue;
                final net.minecraft.world.level.ChunkPos globalChunkPos = new net.minecraft.world.level.ChunkPos(b);
                final net.minecraft.world.level.ChunkPos localChunkPos = plot.toLocal(globalChunkPos);
                final net.minecraft.world.level.ChunkPos meshChunkPos = plot.meshChunkPos(localChunkPos);
                net.minecraft.world.level.chunk.LevelChunk chunk = plot.getChunk(localChunkPos);
                if (chunk == null) {
                    chunk = plot.newEmptyChunk(localChunkPos);
                }
                if (chunk != null) {
                    final net.minecraft.core.BlockPos meshPos = new net.minecraft.core.BlockPos(
                            meshChunkPos.getMinBlockX() + (b.getX() & 15),
                            b.getY(),
                            meshChunkPos.getMinBlockZ() + (b.getZ() & 15));
                    if (!chunk.getBlockState(meshPos).equals(state)) {
                        chunk.setBlockState(meshPos, state, false);
                    }
                }
            }
        } catch (final Throwable ignored) {
            // 编入失败不影响物理化主体（核心维度照常）
        }
    }

    /** 由 blocks 推算包围盒（含坐标全轴倍增）。 */
    private static CryptandBounds3i inferBounds(final List<BlockPos> blocks) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final BlockPos b : blocks) {
            minX = Math.min(minX, b.getX()); minY = Math.min(minY, b.getY()); minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX()); maxY = Math.max(maxY, b.getY()); maxZ = Math.max(maxZ, b.getZ());
        }
        final CryptandBounds3i out =
                new CryptandBounds3i(minX, minY, minZ, maxX, maxY, maxZ);
        return out;
    }

    /** 构造核心 BodyImport（体素密度采样 + 默认刚体参数）。 */
    private static SableMessages.BodyImport buildImport(final ServerLevel level, final BlockPos anchor,
                                                        final List<BlockPos> blocks, final CryptandBounds3i box,
                                                        final int runtimeId) {
        final int sx = box.maxX() - box.minX() + 1;
        final int sy = box.maxY() - box.minY() + 1;
        final int sz = box.maxZ() - box.minZ() + 1;
        final int[] density = new int[Math.max(1, sx * sy * sz)];
        for (final BlockPos b : blocks) {
            int xi = b.getX() - box.minX(), yi = b.getY() - box.minY(), zi = b.getZ() - box.minZ();
            int idx = Math.max(0, Math.min(density.length - 1, (yi * sz + zi) * sx + xi));
            final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(b);
            if (!state.isAir()) {
                // ★ 2026-09-05 高 16 位 = materialId（材质注册表索引；密度/摩擦/弹性/升力/浮力
                //   由 BlockPhysicsTable.propsOf(id) 提供）——结构自身方块接入材质感知合并+重量。
                final int matId = com.hdf.cryptand.neoforge.cryptandsable.core.material
                        .BlockPhysicsTable.idOf(state);
                density[idx] = matId << 16;
            }
        }
        return new SableMessages.BodyImport(
                runtimeId, 0,
                // 2026-08-31 修"偏移不定"：初始位姿 = 结构包围盒几何中心（非模拟锚）
                // —— 与渲染数据 anchor（结构首块）同源同基准；模拟锚若不同不再错位。
                (box.minX() + box.maxX() + 1) * 0.5,
                (box.minY() + box.maxY() + 1) * 0.5,
                (box.minZ() + box.maxZ() + 1) * 0.5,
                0, 0, 0, 1,
                box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ(),
                density, 0, BodyParams.rigid(0));
    }

    // ===== 拆卸 =====

    /**
     * 拆卸：把已物理化的亚层从容器移除，并从核心移除对应物理体。
     * 同时把被搬走的方块还原回世界（视觉"放回"）。
     *
     * @param level    服务端世界
     * @param subLevel 待拆卸亚层（可为 null/已移除 → no-op）
     * @return 是否确实移除了一个已登记亚层
     */
    public static boolean dephysicalize(final ServerLevel level, @Nullable final CryptandSubLevel subLevel) {
        if (level == null || subLevel == null) {
            return false;
        }
        final UUID subId = subLevel.getUniqueId();
        final boolean[] removed = new boolean[1];
        BUS.dispatch("sub:" + subId, () -> removed[0] = dephysicalize0(level, subLevel));
        if (removed[0]) {
            SubLevelEvents.emit(SubLevelEvents.Kind.DISASSEMBLED, subId, subLevel.getRuntimeId(), null);
        }
        return removed[0];
    }

    /** 消息版：拆卸（异步投递；完成 emit DISASSEMBLED）。 */
    public static void postDisassemble(final ServerLevel level, @Nullable final CryptandSubLevel subLevel) {
        if (level == null || subLevel == null) {
            return;
        }
        final UUID subId = subLevel.getUniqueId();
        BUS.post("sub:" + subId, () -> {
            final boolean r = dephysicalize0(level, subLevel);
            if (r) {
                SubLevelEvents.emit(SubLevelEvents.Kind.DISASSEMBLED, subId, subLevel.getRuntimeId(), null);
            }
        });
    }

    /** 拆卸核心（总线上下文：同亚层串行）。 */
    private static boolean dephysicalize0(final ServerLevel level, final CryptandSubLevel subLevel) {
        boolean removed = false;
        try {
            // ★ 2026-09-03 清空黄框由 debug mixin（SableScanStoreLifecycleMixin）注入。
            final CryptandSubLevelContainer container = CryptandSubLevelContainer.getContainer(level);
            final Object prior = container.getSubLevelMap().remove(subLevel.getUniqueId());
            removed = prior != null;
        } catch (final Throwable t) {
            // 容器移除失败不阻断核心移除
        }
        // 还原被搬走的方块（视觉放回世界）
        restoreBlocks(level, subLevel.getUniqueId());
        final int runtimeId = subLevel.getRuntimeId();
        if (runtimeId > 0) {
            CryptandSable.instance().removeBody(new SableMessages.BodyRemove(runtimeId, 0));
        }
        // 清装配器关联（避免残留命中）
        removeAssociation(subLevel.getUniqueId());
        // 持久化删除（拆卸落盘）
        try {
            CryptandSubLevelPersistence
                    .instance().delete(level, subLevel.getUniqueId());
        } catch (final Throwable ignored) {
        }
        // ★ 2026-09-01 pull 渲染：亚层移除 → 缓存版本递增（客户端下次请求补发/清缓存）
        try {
            SableServerBridge.bumpCacheVersion();
        } catch (final Throwable ignored) {
        }
        // ★ 2026-09-04 删除物理化结构 → 直接清空黄框扫描缓存表（防残留黄框不消失）。
        try {
            SableDebugScanStore.clearAll();
        } catch (final Throwable ignored) {
        }
        return removed;
    }

    /**
     * 专属拆卸入口：按亚层 uuid 移除 + 还原方块 + 移除核心体。
     * 供 mixin 接管点/上层调用（与 {@link #physicalize} 对称）。
     *
     * @return 还原回世界的方块数（>=0）
     */
    public static int disassembleSubLevel(final ServerLevel level, @Nullable final CryptandSubLevel subLevel) {
        if (level == null || subLevel == null) {
            return 0;
        }
        final UUID subId = subLevel.getUniqueId();
        final int[] restored = new int[1];
        BUS.dispatch("sub:" + subId, () -> restored[0] = disassemble0(level, subLevel));
        if (restored[0] > 0) {
            SubLevelEvents.emit(SubLevelEvents.Kind.DISASSEMBLED, subId, subLevel.getRuntimeId(), null);
        }
        return restored[0];
    }

    /** 拆卸核心（总线上下文）。 */
    private static int disassemble0(final ServerLevel level, final CryptandSubLevel subLevel) {
        try {
            // ★ 2026-09-03 清空黄框由 debug mixin（SableScanStoreLifecycleMixin）注入。
            final CryptandSubLevelContainer container = CryptandSubLevelContainer.getContainer(level);
            container.getSubLevelMap().remove(subLevel.getUniqueId());
        } catch (final Throwable t) {
            // 容器移除失败不阻断还原
        }
        final int restored = restoreBlocks(level, subLevel.getUniqueId());
        final int runtimeId = subLevel.getRuntimeId();
        if (runtimeId > 0) {
            CryptandSable.instance().removeBody(new SableMessages.BodyRemove(runtimeId, 0));
        }
        // 清装配器关联（避免残留命中）
        removeAssociation(subLevel.getUniqueId());
        // 持久化删除（拆卸落盘）
        try {
            CryptandSubLevelPersistence
                    .instance().delete(level, subLevel.getUniqueId());
        } catch (final Throwable ignored) {
        }
        return restored;
    }

    /** 便捷：按定位（锚坐标）找到并拆卸该处的唯一亚层。 */
    public static boolean dephysicalizeAt(final ServerLevel level, final double x, final double z) {
        final CryptandSubLevel sub = com.hdf.cryptand.neoforge.cryptandsable_compat
                .CryptandSubLevelLocator.containingOwn(level, x, z);
        return sub instanceof final CryptandServerSubLevel ssl
                ? dephysicalize(level, ssl)
                : false;
    }

    /**
     * 全部亚层清除（2026-08-31 用户：/sablebp sublevels remove_all 无法全部删除）。
     * 官方命令只删官方的目录条目（我们存的文件格式/路径不同 → missing）；
     * 本 API 按我们的数据面彻底清理：容器登记 + 装配器关联 + MOVED + 核心物理体 +
     * 持久化文件 + 客户端渲染，一次清空。
     *
     * @return 清除的亚层数量
     */
    /**
     * 全部亚层清除（2026-09-01 强化：强制删除所有）。
     * 之前只遍历容器 map → 若某亚层仅在 MOVED/持久化中有记录而容器缺失
     * （世界重载恢复失败/importBody 失败），removeAll 删不掉；且持久化后端若
     * backend 未创建（早期/异常）个别 delete 静默失败 → 世界重载又从持久化恢复。
     *
     * <p>现在强制三重清理：①容器 map + ②全部静态表（MOVED/RENDER_ANCHOR/
     * PROJECTION_ANCHOR/SUB_LEVEL_RUNTIME/ASSOCIATION）+ ③持久化 clearAll
     * （含后端异常时的文件级兜底 wipe）；核心体异步移除 + 客户端渲染移除广播。
     *
     * @return 清除的亚层数量（容器内 + MOVED 内去重）
     */
    public static int removeAllSubLevels(final ServerLevel level) {
        if (level == null) {
            return 0;
        }
        final int[] removed = new int[1];
        BUS.dispatch("remove-all:" + level.dimension(), () -> {
            try {
                final CryptandSubLevelContainer container = CryptandSubLevelContainer.getContainer(level);
                // 收集全部 uuid：容器 map + MOVED 表并集（容器缺失但搬移记录在的也清）
                final java.util.LinkedHashSet<UUID> uuids = new java.util.LinkedHashSet<>();
                for (final CryptandSubLevel s : container.getSubLevelMap().values()) {
                    if (s != null) uuids.add(s.getUniqueId());
                }
                uuids.addAll(MOVED.keySet());
                // 静态表里的 uuid 也一并纳入（即使不在 MOVED/容器）
                uuids.addAll(SUB_LEVEL_RUNTIME.keySet());
                uuids.addAll(PROJECTION_ANCHOR.keySet());

                int n = 0;
                for (final UUID uuid : uuids) {
                    try {
                        // 1) 移出容器（若有）
                        container.getSubLevelMap().remove(uuid);
                        // 2) 清关联/渲染锚/投影锚/runtime 表
                        removeAssociation(uuid);
                        RENDER_ANCHOR.remove(uuid);
                        PROJECTION_ANCHOR.remove(uuid);
                        SUB_LEVEL_RUNTIME.remove(uuid);
                        // 3) 清 MOVED（方块按需还原：默认不还原=生成器批量清场）
                        clearMovedBlocks(uuid);
                        // 4) 移除核心体（若容器里有 runtimeId；静态表优先兜底）
                        int runtimeId = 0;
                        if (container.getSubLevelMap().get(uuid) instanceof CryptandSubLevel ssl) {
                            runtimeId = ssl.getRuntimeId();
                        }
                        if (runtimeId <= 0) {
                            final Integer ri = SUB_LEVEL_RUNTIME.remove(uuid);
                            if (ri != null) runtimeId = ri;
                        }
                        if (runtimeId > 0) {
                            CryptandSable.instance().removeBody(new SableMessages.BodyRemove(runtimeId, 0));
                        }
                        // 5) 客户端渲染移除广播
                        try {
                            broadcastRenderRemove(level, uuid);
                        } catch (Throwable ignored) {
                        }
                        n++;
                    } catch (Throwable t) {
                        CryptandNeoForge.WAF_LOGGER.warn(
                                "[CryptandSable] removeAll sub {} failed: {}", uuid, t.toString());
                    }
                }

                // 6) ★ 持久化强制清空（后端 delete 可能静默失败/backend 未创建；clearAll 兜底文件级）
                try {
                    CryptandSubLevelPersistence
                            .instance().clearAll(level);
                } catch (Throwable t) {
                    CryptandNeoForge.WAF_LOGGER.warn(
                            "[CryptandSable] removeAll persistence clearAll failed: {}", t.toString());
                }

                // 7) ★ 2026-09-04 指令删除后直接清空黄框扫描缓存（防残留黄框不消失）
                try {
                    SableDebugScanStore.clearAll();
                } catch (Throwable ignored) {
                }

                removed[0] = n;
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] removeAllSubLevels removed={} (force)", n);
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[CryptandSable] removeAllSubLevels failed: {}", t.toString());
            }
        });
        return removed[0];
    }

    // ===== 强制加载 =====

    /**
     * 强制加载：确保亚层 plot 覆盖的 chunk 被强制加载（保证拆卸/回写/物理读时数据在内存）。
     * 对每个覆盖 chunk 向服务端申请 ticket（10 秒自动续期，理论 "force" 语义：首次强保证读）。
     *
     * <p>简单实现：立即 {@code getChunk} 强制加载该范围（同步加载 chunk），并把已加载 chunk
     * 交由 Minecraft chunk 管理继续持有。返回已确保加载的 chunk 数量。
     */
    public static int forceLoadSubLevel(final ServerLevel level, @Nullable final CryptandSubLevel subLevel) {
        if (level == null || subLevel == null) {
            return 0;
        }
        final CryptandLevelPlot plot = subLevel.getPlot();
        if (plot == null) {
            return 0;
        }
        // plot 覆盖 block 范围: [plotPos << (logSize+4), (plotPos+1) << (logSize+4))
        // 对应 chunk 范围: [plotPos << logSize, (plotPos+1) << logSize)
        final int log = Math.max(0, plot.getLogSize());
        final int chunkMinX = plot.plotPos.x << log;
        final int chunkMaxX = ((plot.plotPos.x + 1) << log) - 1;
        final int chunkMinZ = plot.plotPos.z << log;
        final int chunkMaxZ = ((plot.plotPos.z + 1) << log) - 1;
        int loaded = 0;
        for (int cx = chunkMinX; cx <= chunkMaxX; cx++) {
            for (int cz = chunkMinZ; cz <= chunkMaxZ; cz++) {
                try {
                    final LevelChunk chunk = level.getChunk(cx, cz);
                    if (chunk != null) loaded++;
                } catch (final Throwable ignored) {
                    // 单 chunk 加载失败不阻断其余
                }
            }
        }
        return loaded;
    }

    /** 便捷：按 UUID 取容器内亚层（核心自有容器）。 */
    public static @Nullable CryptandSubLevel getSubLevel(final ServerLevel level, final UUID uuid) {
        if (level == null || uuid == null) return null;
        return CryptandSubLevelContainer.getContainer(level).getSubLevel(uuid);
    }

    // ===== 方块搬移（物理化装卸的视觉承载） =====

    /** 已搬移方块的恢复记录（亚层 uuid → 原方块状态+位置）。 */
    private static final java.util.Map<UUID, java.util.List<PlacedBlockSnapshot>> MOVED =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 装配器关联表（2026-08-31 用户："组装器是独立发送的消息，不要用同一个"）。
     * 每个物理化由【装配器位置 → 亚层 uuid】精确登记；getContaining(BlockEntity) 的
     * mixin 接管按该表精确命中——不经过 broad plot 兜底（避免"别处装配器命中同一亚层
     * → 误拆卸 → 消失/掉落物"的串扰）。
     */
    private static final java.util.Map<BlockPos, UUID> ASSOCIATION =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 登记装配器关联（物理化装载时；anchor=装配器/锚点位置）。 */
    private static void registerAssociation(final BlockPos anchor, final UUID subLevelId) {
        if (anchor != null && subLevelId != null) {
            ASSOCIATION.put(anchor, subLevelId);
        }
    }

    /** 按装配器位置查询关联亚层（mixin 接管用；无 → null）。 */
    public static @Nullable UUID associatedSubLevel(final BlockPos anchor) {
        return anchor == null ? null : ASSOCIATION.get(anchor);
    }

    /** 亚层是否仍登记在容器（mixin 幂等拆卸判断用）。 */
    public static boolean isRegistered(final ServerLevel level, final UUID subLevelId) {
        if (level == null || subLevelId == null) return false;
        try {
            return CryptandSubLevelContainer.getContainer(level).getSubLevelMap().containsKey(subLevelId);
        } catch (final Throwable t) {
            return false;
        }
    }

    /** 移除装配器关联（拆卸时；按亚层 uuid 清关联）。 */
    private static void removeAssociation(final UUID subLevelId) {
        if (subLevelId == null) return;
        ASSOCIATION.entrySet().removeIf(e -> e.getValue().equals(subLevelId));
        RENDER_ANCHOR.remove(subLevelId);
    }

    /**
     * 渲染/物理统一锚（uuid → 结构首块世界坐标）。
     * 2026-08-31 修"物理化后偏移不定"：物理体初始位姿 + 渲染数据 anchor + 方块偏移
     * 必须共用同一个基准块——否则（模拟 anchor ≠ 结构首块时）偏移量每次不定。
     */
    private static final java.util.Map<UUID, BlockPos> RENDER_ANCHOR =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 亚层投射基准（uuid → 初始结构包围盒【浮点几何中心】，2026-09-01）。
     * 与 buildImport 初始位姿（box 中心 (min+max+1)*0.5）、渲染 anchor 同基准；
     * 碰撞/破坏投影用。⚠ 必须浮点：整数 >>1 与 (min+max+1)*0.5 相差 0.5 → 投影错位 1 格
     * （shape 停在初始位置附近 → 物理结构移动后玩家穿透）。
     */
    private static final java.util.Map<UUID, double[]> PROJECTION_ANCHOR =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 亚层 runtimeId 表（uuid → runtimeId；2026-09-01 碰撞投影查 pose 用）。 */
    private static final java.util.Map<UUID, Integer> SUB_LEVEL_RUNTIME =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 投影基准（初始结构包围盒浮点中心 [cx,cy,cz]）；无 → null（静止）。 */
    public static @Nullable double[] projectionAnchor(final UUID subLevelId) {
        return subLevelId == null ? null : PROJECTION_ANCHOR.get(subLevelId);
    }

    /** 亚层 runtimeId（碰撞投影查 pose 用）；无 → 0。 */
    public static int subLevelRuntimeId(final UUID subLevelId) {
        if (subLevelId == null) return 0;
        final Integer v = SUB_LEVEL_RUNTIME.get(subLevelId);
        return v != null ? v : 0;
    }

    /** ★ 2026-09-01 runtimeId 反查亚层 uuid（收集查询定位用）；无 → null。 */
    public static @Nullable UUID subLevelIdForRuntime(final int runtimeId) {
        for (final java.util.Map.Entry<UUID, Integer> e : SUB_LEVEL_RUNTIME.entrySet()) {
            if (e.getValue() == runtimeId) return e.getKey();
        }
        return null;
    }

    /** ★ 2026-09-01 某亚层的 MOVED 快照列表（收集查询回退位置用）；无 → null。 */
    public static @Nullable java.util.List<PlacedBlockSnapshot> movedBlocks(final UUID subLevelId) {
        return subLevelId == null ? null : MOVED.get(subLevelId);
    }

    /** 单个被搬走方块的快照（用于拆卸时还原回世界）。 */
    public record PlacedBlockSnapshot(BlockPos worldPos, net.minecraft.world.level.block.state.BlockState state,
                                      net.minecraft.nbt.CompoundTag blockEntityTag) {
    }

    /**
     * 把结构方块从世界"拿起"（搬进亚层语义，世界位置清空，拆卸可还原）。
     * 兼容桩 moveBlocks 的专属实现：不依赖任何官方 chunk holder，直接操作 ServerLevel。
     *
     * <p>拿起成功后【广播亚层渲染同步消息】——客户端渲染模块据此绘制亚层方块
     * （参考原版 sable 渲染：方块从世界消失，由亚层渲染器显示）。
     *
     * @return 被搬走的方块快照列表（供 {@link #restoreBlocks} 还原）
     */
    public static java.util.List<PlacedBlockSnapshot> moveBlocksIntoSubLevel(
            final ServerLevel level, final UUID subLevelId, final Iterable<BlockPos> blocks) {
        final java.util.List<PlacedBlockSnapshot> snapshots = new ArrayList<>();
        if (level == null || blocks == null) return snapshots;
        final net.minecraft.world.level.block.state.BlockState airState =
                net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        final List<BlockPos> collected = new java.util.ArrayList<>();
        for (final BlockPos b : blocks) {
            if (b == null) continue;
            try {
                final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(b);
                if (state.isAir()) continue;
                net.minecraft.nbt.CompoundTag tag = null;
                final net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(b);
                if (be != null) {
                    tag = be.saveWithFullMetadata(level.registryAccess());
                }
                snapshots.add(new PlacedBlockSnapshot(b, state, tag));
                collected.add(b);
                // 世界位置置空（视觉"拿起"）；方块数据留存亚层内存。
                // ★ 2026-09-01 卡顿修复：UPDATE_CLIENTS 而非 UPDATE_NONE——对照官方
                //   moveBlocks 最后 sendBlockUpdated(...,airState,3)。
                //   UPDATE_NONE(=0) 不向客户端发任何方块更新包 → 客户端区块仍渲染旧方块
                //   → 玩家看到"原位置方块一直生成/残留 + 与亚层重叠"（服务端其实已清）。
                //   UPDATE_CLIENTS(=2)：服务端清空 + 仅广播该位置变空气给客户端；
                //   不触发邻居方块更新/光照重算（保留卡顿优化），视觉正确。
                level.removeBlockEntity(b);
                level.setBlock(b, airState, net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            } catch (final Throwable t) {
                // 单方块搬移失败不阻断其余
            }
        }
        if (!snapshots.isEmpty()) {
            MOVED.put(subLevelId, snapshots);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] moveBlocksIntoSubLevel moved={} blocks (world cleared, MOVED filled)",
                    snapshots.size());
        } else {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] moveBlocksIntoSubLevel: 0 blocks to move (world already cleared?)");
        }
        // 广播渲染同步：客户端按 anchor + 方块列表绘制（位姿跟随按 runtimeId 索引）
        if (!snapshots.isEmpty()) {
            int runtimeId = 0;
            try {
                final CryptandSubLevel sub = getSubLevel(level, subLevelId);
                if (sub != null) runtimeId = sub.getRuntimeId();
            } catch (final Throwable ignored) {
            }
            // 登记投射基准 + runtimeId（碰撞/破坏/渲染投影共用）
            if (runtimeId > 0) {
                SUB_LEVEL_RUNTIME.put(subLevelId, runtimeId);
            }
            broadcastRenderSync(level, subLevelId, runtimeId, snapshots);
            // 持久化：把已搬移方块落盘（sqlite/sable 后端，按配置）
            try {
                // anchor 用结构包围盒中心（与物理体初始位姿/渲染数据同基准）
                BlockPos cMin = null, cMax = null;
                for (final PlacedBlockSnapshot s : snapshots) {
                    if (cMin == null) { cMin = s.worldPos(); cMax = s.worldPos(); }
                    final BlockPos p = s.worldPos();
                    cMin = new BlockPos(Math.min(cMin.getX(), p.getX()), Math.min(cMin.getY(), p.getY()), Math.min(cMin.getZ(), p.getZ()));
                    cMax = new BlockPos(Math.max(cMax.getX(), p.getX()), Math.max(cMax.getY(), p.getY()), Math.max(cMax.getZ(), p.getZ()));
                }
                final BlockPos center = cMin != null && cMax != null
                        ? new BlockPos((cMin.getX() + cMax.getX() + 1) >> 1,
                        (cMin.getY() + cMax.getY() + 1) >> 1,
                        (cMin.getZ() + cMax.getZ() + 1) >> 1)
                        : snapshots.get(0).worldPos();
                // ★ 2026-09-01 浮点几何中心（与 buildImport 初始位姿 (min+max+1)*0.5 一致；
                //   整数 >>1 与浮点中心差 0.5 → 投影错位穿模）
                PROJECTION_ANCHOR.put(subLevelId, new double[]{
                        (cMin.getX() + cMax.getX() + 1) * 0.5,
                        (cMin.getY() + cMax.getY() + 1) * 0.5,
                        (cMin.getZ() + cMax.getZ() + 1) * 0.5});
                CryptandSubLevelPersistence
                        .instance().saveAsync(level, buildPersisted(subLevelId, runtimeId, center, snapshots));
            } catch (final Throwable ignored2) {
            }
        }
        return snapshots;
    }

    /** 还原某亚层被搬走的方块（拆卸时放回世界）。 */
    public static int restoreBlocks(final ServerLevel level, final UUID subLevelId) {
        final java.util.List<PlacedBlockSnapshot> snaps = MOVED.remove(subLevelId);
        if (snaps == null) return 0;
        int restored = 0;
        for (final PlacedBlockSnapshot s : snaps) {
            try {
                level.setBlock(s.worldPos(), s.state(), net.minecraft.world.level.block.Block.UPDATE_ALL);
                if (s.blockEntityTag() != null) {
                    final net.minecraft.world.level.block.entity.BlockEntity be =
                            level.getBlockEntity(s.worldPos());
                    if (be != null) {
                        be.loadWithComponents(s.blockEntityTag(), level.registryAccess());
                    }
                }
                restored++;
            } catch (final Throwable ignored) {
                // 单块还原失败继续
            }
        }
        // 通知客户端渲染移除（方块回世界，亚层不再显示）
        if (restored > 0) {
            broadcastRenderRemove(level, subLevelId);
        }
        return restored;
    }

    /** 查询某亚层已搬走的方块数量（0 = 未搬）。 */
    public static int movedBlockCount(final UUID subLevelId) {
        final java.util.List<PlacedBlockSnapshot> snaps = MOVED.get(subLevelId);
        return snaps != null ? snaps.size() : 0;
    }

    /**
     * 实体碰撞补充：把 MOVED 表中与 AABB 相交的亚层方块 shape 喂给 consumer。
     * 服务端权威，客户端无 MOVED → 无操作。参数用 BlockGetter（CollisionGetter 是其子类）。
     * ★ 2026-09-01 世界投射：方块位置按亚层实时物理位姿投影（同 feedCollectShapes）。
     */
    public static void feedCollisionShapes(final net.minecraft.world.level.BlockGetter blockGetter,
                                           final net.minecraft.world.phys.AABB box,
                                           final java.util.function.BiConsumer<
                                                   net.minecraft.world.phys.shapes.VoxelShape,
                                                   BlockPos> consumer) {
        if (box == null || consumer == null) return;
        for (final java.util.Map.Entry<UUID, java.util.List<PlacedBlockSnapshot>> e : MOVED.entrySet()) {
            final UUID subId = e.getKey();
            final double[] anchor = projectionAnchor(subId);
            final SableMessages.PoseSnapshot pose =
                    CryptandSable.instance() != null && subLevelRuntimeId(subId) > 0
                            ? CryptandSable.instance().snapshots().get(subLevelRuntimeId(subId))
                            : null;
            for (final PlacedBlockSnapshot s : e.getValue()) {
                if (s == null || s.state() == null) continue;
                final BlockPos initPos = s.worldPos();
                if (initPos == null) continue;
                final BlockPos pos = SableSubLevelProjection.projectBlock(pose, anchor, initPos);
                final net.minecraft.world.phys.AABB blockBox = new net.minecraft.world.phys.AABB(
                        pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                if (!blockBox.intersects(box)) continue;
                try {
                    net.minecraft.world.phys.shapes.VoxelShape shape =
                            s.state().getCollisionShape(blockGetter, initPos);
                    if (shape != null && !shape.isEmpty()) {
                        final double[] off = SableSubLevelProjection.projectOffset(pose, anchor, initPos);
                        consumer.accept(shape.move(off[0], off[1], off[2]), pos);
                    }
                } catch (final Throwable ignored) {
                    // 单方块 shape 失败继续
                }
            }
        }
    }

    /**
     * 实体碰撞补充（List 版本）：把 MOVED 表中与 AABB 相交的亚层方块 shape 收集到 shapes 列表。
     * 由 {@code EntityCollisionMixin}（@Inject Entity.collectColliders）调用；服务端权威。
     *
     * ★ 2026-09-01 世界投射：方块位置按亚层实时物理位姿投影（虚拟核心位姿 → 实际世界），
     * 否则玩家按静态 worldPos 交互（无法交互/穿透）。投影经 SableSubLevelProjection（一致）。
     *
     * ★ 2026-09-01 客户端预测：客户端无 MOVED，但需客户端也显示碰撞箱/F3+B + 预测碰撞 →
     * 客户端走 SableClientRenderModule 的亚层渲染数据（payload blocks + 位姿快照）构建 shape。
     */
    public static void feedCollectShapes(final net.minecraft.world.level.BlockGetter blockGetter,
                                         final net.minecraft.world.phys.AABB box,
                                         final java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes) {
        if (box == null || shapes == null) return;
        // 客户端：用渲染数据（MOVED 无；客户端镜像有）
        if (blockGetter instanceof final net.minecraft.world.level.Level lv && lv.isClientSide()) {
            feedClientShapes(blockGetter, box, shapes);
            return;
        }
        for (final java.util.Map.Entry<UUID, java.util.List<PlacedBlockSnapshot>> e : MOVED.entrySet()) {
            final UUID subId = e.getKey();
            final double[] anchor = projectionAnchor(subId);
            final SableMessages.PoseSnapshot pose =
                    CryptandSable.instance() != null && subLevelRuntimeId(subId) > 0
                            ? CryptandSable.instance().snapshots().get(subLevelRuntimeId(subId))
                            : null;
            for (final PlacedBlockSnapshot s : e.getValue()) {
                if (s == null || s.state() == null) continue;
                final BlockPos initPos = s.worldPos();
                if (initPos == null) continue;
                // 投影到物理位姿下的世界位置
                final BlockPos pos = SableSubLevelProjection.projectBlock(pose, anchor, initPos);
                final net.minecraft.world.phys.AABB blockBox = new net.minecraft.world.phys.AABB(
                        pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                if (!blockBox.intersects(box)) continue;
                try {
                    net.minecraft.world.phys.shapes.VoxelShape shape =
                            s.state().getCollisionShape(blockGetter, initPos);
                    if (shape != null && !shape.isEmpty()) {
                        // 平移 shape 到投影后位置（VoxelShape.move）
                        final double[] off = SableSubLevelProjection.projectOffset(pose, anchor, initPos);
                        shapes.add(shape.move(off[0], off[1], off[2]));
                    }
                } catch (final Throwable ignored) {
                    // 单方块 shape 失败继续
                }
            }
        }
    }

    /**
     * 实体"脚底是否被亚层结构支撑"（2026-09-02 方案A：站在物理结构上 = 着地）。
     * 与 feedCollectShapes 同一投影（SableSubLevelProjection），按实时位姿把结构方块
     * 投到世界，检查是否有方块刚好在实体脚底（feet 底沿探测盒）。
     * 服务端读 MOVED；客户端读 SableClientRenderModule 渲染镜像。
     */
    public static boolean subLevelSupportsFeet(final net.minecraft.world.level.BlockGetter blockGetter,
                                               final net.minecraft.world.entity.Entity entity) {
        if (entity == null) return false;
        try {
            final net.minecraft.world.phys.AABB box = entity.getBoundingBox();
            if (box == null) return false;
            final double feetY = box.minY;
            // 脚底探测盒：XZ 微缩 + [feetY-0.01, feetY]（站立时块顶沿恰在此区间）
            final net.minecraft.world.phys.AABB feetBox = new net.minecraft.world.phys.AABB(
                    box.minX + 0.05, feetY - 0.01, box.minZ + 0.05,
                    box.maxX - 0.05, feetY, box.maxZ - 0.05);
            if (blockGetter instanceof final net.minecraft.world.level.Level lv && lv.isClientSide()) {
                return subLevelSupportsFeetClient(blockGetter, feetBox);
            }
            // 服务端：走 MOVED 投影
            for (final java.util.Map.Entry<UUID, java.util.List<PlacedBlockSnapshot>> e : MOVED.entrySet()) {
                final UUID subId = e.getKey();
                if (subId == null) continue;
                final double[] anchor = projectionAnchor(subId);
                final SableMessages.PoseSnapshot pose = subLevelRuntimeId(subId) > 0
                        ? CryptandSable.instance().snapshots()
                                .get(subLevelRuntimeId(subId))
                        : null;
                for (final PlacedBlockSnapshot s : e.getValue()) {
                    if (s == null || s.worldPos() == null) continue;
                    final BlockPos initPos = s.worldPos();
                    final BlockPos pos = SableSubLevelProjection.projectBlock(pose, anchor, initPos);
                    final net.minecraft.world.phys.AABB blockBox = new net.minecraft.world.phys.AABB(
                            pos.getX(), pos.getY(), pos.getZ(),
                            pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                    if (blockBox.intersects(feetBox)) return true;
                }
            }
            return false;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /** 客户端脚底支撑：遍历渲染镜像亚层方块（anchor + dx/dy/dz），投影后判定。 */
    private static boolean subLevelSupportsFeetClient(final net.minecraft.world.level.BlockGetter blockGetter,
                                                      final net.minecraft.world.phys.AABB feetBox) {
        try {
            final SableClientRenderModule module =
                    SableClientRenderModule.INSTANCE;
            for (final SableSubLevelRenderData data
                    : module.allSubLevels()) {
                if (data == null || data.blocks() == null || data.blocks().isEmpty()) continue;
                final SableMessages.PoseSnapshot pose = module.pose(data.runtimeId());
                final net.minecraft.core.BlockPos anchor = data.anchor();
                if (anchor == null) continue;
                for (final SableSubLevelRenderData.RenderBlock rb
                        : data.blocks()) {
                    try {
                        final int sid = rb.stateId();
                        if (sid <= 0) continue;
                        final net.minecraft.core.BlockPos initPos = new net.minecraft.core.BlockPos(
                                anchor.getX() + rb.dx(), anchor.getY() + rb.dy(), anchor.getZ() + rb.dz());
                        final net.minecraft.core.BlockPos pos =
                                SableSubLevelProjection.projectBlock(pose, anchor, initPos);
                        final net.minecraft.world.phys.AABB blockBox = new net.minecraft.world.phys.AABB(
                                pos.getX(), pos.getY(), pos.getZ(),
                                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                        if (blockBox.intersects(feetBox)) return true;
                    } catch (final Throwable ignored) {
                        // 单块失败继续
                    }
                }
            }
        } catch (final Throwable ignored) {
            // 支撑检测失败不影响
        }
        return false;
    }

    /** 客户端碰撞 shape 来源：SableClientRenderModule 的亚层渲染数据（payload blocks + 位姿快照）。 */
    private static void feedClientShapes(final net.minecraft.world.level.BlockGetter blockGetter,
                                         final net.minecraft.world.phys.AABB box,
                                         final java.util.List<net.minecraft.world.phys.shapes.VoxelShape> shapes) {
        try {
            final SableClientRenderModule module =
                    SableClientRenderModule.INSTANCE;
            for (final SableSubLevelRenderData data
                    : module.allSubLevels()) {
                if (data == null || data.blocks() == null || data.blocks().isEmpty()) continue;
                final SableMessages.PoseSnapshot pose =
                        module.pose(data.runtimeId());
                final net.minecraft.core.BlockPos anchor = data.anchor();
                for (final SableSubLevelRenderData.RenderBlock rb
                        : data.blocks()) {
                    try {
                        final int sid = rb.stateId();
                        if (sid <= 0) continue;
                        final net.minecraft.world.level.block.state.BlockState state =
                                net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(sid);
                        if (state == null || state.isAir()) continue;
                        final int wx = anchor.getX() + rb.dx();
                        final int wy = anchor.getY() + rb.dy();
                        final int wz = anchor.getZ() + rb.dz();
                        final net.minecraft.core.BlockPos initPos = new net.minecraft.core.BlockPos(wx, wy, wz);
                        // 位姿投影（与服务端一致）
                        final net.minecraft.core.BlockPos pos =
                                SableSubLevelProjection.projectBlock(pose, anchor, initPos);
                        final net.minecraft.world.phys.AABB blockBox = new net.minecraft.world.phys.AABB(
                                pos.getX(), pos.getY(), pos.getZ(),
                                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                        if (!blockBox.intersects(box)) continue;
                        final net.minecraft.world.phys.shapes.VoxelShape shape =
                                state.getCollisionShape(blockGetter, initPos);
                        if (shape != null && !shape.isEmpty()) {
                            final double[] off = SableSubLevelProjection.projectOffset(pose, anchor, initPos);
                            shapes.add(shape.move(off[0], off[1], off[2]));
                        }
                    } catch (final Throwable ignored) {
                        // 单方块失败继续
                    }
                }
            }
        } catch (final Throwable ignored) {
            // 客户端亚层 shape 收集失败不阻断
        }
    }

    public static void clearMovedBlocks(final UUID subLevelId) {
        MOVED.remove(subLevelId);
    }

    /** 实体粘附命中结果（SableEntityStickHandler 用）。 */
    public record StickHit(UUID subLevelId, int runtimeId, double[] anchor) {
    }

    /**
     * ★ 实体粘附检测（2026-09-01 用户："实体为单独的粘性物理，能被实际物理化结构带走"）：
     * 实体 AABB 与某亚层【投影方块】相交（含底部接触）→ 返回该亚层粘附命中；
     * 无命中 → null。服务端权威（读 MOVED + 位姿快照投影）。
     */
    public static @Nullable StickHit findStickHit(final net.minecraft.world.level.BlockGetter blockGetter,
                                                  final net.minecraft.world.phys.AABB box) {
        if (box == null) return null;
        for (final java.util.Map.Entry<UUID, java.util.List<PlacedBlockSnapshot>> e : MOVED.entrySet()) {
            final UUID subId = e.getKey();
            final int rt = subLevelRuntimeId(subId);
            if (rt <= 0) continue;
            final double[] anchor = projectionAnchor(subId);
            if (anchor == null || anchor.length < 3) continue;
            final SableMessages.PoseSnapshot pose =
                    CryptandSable.instance() != null ? CryptandSable.instance().snapshots().get(rt) : null;
            for (final PlacedBlockSnapshot s : e.getValue()) {
                if (s == null || s.state() == null) continue;
                final BlockPos initPos = s.worldPos();
                if (initPos == null) continue;
                final BlockPos pos = SableSubLevelProjection.projectBlock(pose, anchor, initPos);
                final net.minecraft.world.phys.AABB blockBox = new net.minecraft.world.phys.AABB(
                        pos.getX(), pos.getY(), pos.getZ(),
                        pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
                // 相交（或实体底部 y 处于方块顶面±0.5 → 站上面）
                if (blockBox.intersects(box)
                        || (box.minY <= blockBox.maxY + 0.25 && box.minY >= blockBox.minY - 0.25
                        && box.intersects(new net.minecraft.world.phys.AABB(
                        pos.getX(), pos.getY() - 0.6, pos.getZ(),
                        pos.getX() + 1, pos.getY() + 1.2, pos.getZ() + 1)))) {
                    return new StickHit(subId, rt, anchor);
                }
            }
        }
        return null;
    }

    /**
     * ★ 导出指定物理化结构的完整信息（2026-09-01 诊断命令）。
     *
     * <p>用户需求："物理结构已经飞了但渲染没跟上" —— 需要一把完整导出该结构
     * 的所有状态（主世界坐标/物理位姿/MOVED/容器/渲染锚/持久化），对照判断
     * 渲染与物理是否不同步。
     *
     * @param level 服务端世界
     * @param uuid  亚层 uuid；null = 导出全部
     * @return 多行诊断文本（= 无 uuid 匹配/无结构时单行说明）
     */
    public static java.util.List<String> dumpStructureInfo(final ServerLevel level,
                                                           final @Nullable java.util.UUID uuid) {
        final java.util.List<String> lines = new java.util.ArrayList<>();
        try {
            final CryptandSubLevelContainer container = CryptandSubLevelContainer.getContainer(level);

            // 收集要导出的 uuid 集合：指定 或 全部（容器 + MOVED + 静态表并集）
            final java.util.LinkedHashSet<UUID> uuids = new java.util.LinkedHashSet<>();
            if (uuid != null) {
                uuids.add(uuid);
            } else {
                for (final CryptandSubLevel s : container.getSubLevelMap().values()) {
                    if (s != null) uuids.add(s.getUniqueId());
                }
                uuids.addAll(MOVED.keySet());
                uuids.addAll(SUB_LEVEL_RUNTIME.keySet());
                uuids.addAll(PROJECTION_ANCHOR.keySet());
            }

            for (final UUID id : uuids) {
                lines.add("==== 物理化结构 " + id + " ====");
                // ① 容器登记
                final boolean inContainer = container.getSubLevelMap().containsKey(id);
                lines.add("  容器登记=" + inContainer
                        + (inContainer
                        ? " (containerSize=" + container.getSubLevelMap().size() + ")"
                        : ""));
                // ② 装配器关联
                final BlockPos assocAnchor = ASSOCIATION.entrySet().stream()
                        .filter(e -> e.getValue().equals(id))
                        .map(java.util.Map.Entry::getKey)
                        .findFirst().orElse(null);
                lines.add("  装配器关联=" + (assocAnchor != null
                        ? assocAnchor.toShortString() : "无"));
                // ③ 渲染锚 / 投影锚 / runtimeId
                final BlockPos renderAnchor = RENDER_ANCHOR.get(id);
                final double[] projAnchor = PROJECTION_ANCHOR.get(id);
                final Integer runtimeId = SUB_LEVEL_RUNTIME.get(id);
                lines.add("  渲染锚=" + (renderAnchor != null ? renderAnchor.toShortString() : "无")
                        + " 投影锚(初始包围盒中心)=" + (projAnchor != null
                        ? String.format("(%.1f,%.1f,%.1f)", projAnchor[0], projAnchor[1], projAnchor[2])
                        : "无")
                        + " runtimeId=" + runtimeId);
                // ④ MOVED 方块快照（主世界原始坐标 + 数量 + 具体方块列表）
                final java.util.List<PlacedBlockSnapshot> snaps = MOVED.get(id);
                lines.add("  MOVED方块数=" + (snaps != null ? snaps.size() : 0));
                if (snaps != null && !snaps.isEmpty()) {
                    int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                    int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                    BlockPos first = null;
                    for (final PlacedBlockSnapshot s : snaps) {
                        if (s == null || s.worldPos() == null) continue;
                        final BlockPos p = s.worldPos();
                        if (first == null) first = p;
                        minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
                        maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
                    }
                    lines.add("    首块世界坐标=" + (first != null ? first.toShortString() : "?"));
                    lines.add("    主世界包围盒=[" + minX + "," + minY + "," + minZ + ".."
                            + maxX + "," + maxY + "," + maxZ + "]");
                    // ★ 2026-09-01 具体方块明细（用户："info需要打印网络里面物理化的具体方块"）
                    lines.add("    【方块明细】");
                    // 当前物理姿势（供投影；null=静止初始位置）
                    final int detailRt = runtimeId != null ? runtimeId : 0;
                    final SableMessages.PoseSnapshot detailPs =
                            detailRt > 0 && CryptandSable.instance() != null
                                    ? CryptandSable.instance().snapshots().get(detailRt)
                                    : null;
                    for (final PlacedBlockSnapshot s : snaps) {
                        if (s == null || s.worldPos() == null || s.state() == null) continue;
                        // 投影后的世界位置（当前物理位姿下）——与碰撞/渲染投影一致
                        final BlockPos proj = SableSubLevelProjection.projectBlock(
                                detailPs, projAnchor, s.worldPos());
                        lines.add(String.format("      (x=%d,y=%d,z=%d) %s -> 投影(%d,%d,%d)",
                                s.worldPos().getX(), s.worldPos().getY(), s.worldPos().getZ(),
                                s.state().toString(),
                                proj.getX(), proj.getY(), proj.getZ()));
                    }
                }
                // ⑤ 物理位姿（核心快照镜像；null=核心未同步/未启动）
                final int rt = runtimeId != null ? runtimeId : 0;
                if (rt > 0) {
                    final SableMessages.PoseSnapshot ps =
                            CryptandSable.instance() != null
                                    ? CryptandSable.instance().snapshots().get(rt)
                                    : null;
                    if (ps != null) {
                        // ★ 2026-09-06 【无 far：物理坐标=主世界坐标】物理化坐标直接入
                        //   物理空间（0,0,0→0,0,0；10,5,20→10,5,20），零转换。
                        lines.add(String.format("  物理位姿=xyz(%.2f,%.2f,%.2f) q(%.2f,%.2f,%.2f,%.2f) v(%.2f,%.2f,%.2f)",
                                ps.px(), ps.py(), ps.pz(),
                                ps.qx(), ps.qy(), ps.qz(), ps.qw(),
                                ps.vx(), ps.vy(), ps.vz()));
                        // 【物理坐标】= 结构虚拟中心（= 主世界 /= 物理空间坐标，零转换）
                        lines.add(String.format("  物理坐标(主世界/物理空间,零转换)=xyz(%.2f,%.2f,%.2f)",
                                ps.px(), ps.py(), ps.pz()));
                        // 渲染 anchor（=物理初始位姿基准）与物理当前位置对比 → 判断"飞了"
                        if (projAnchor != null) {
                            final double dx = ps.px() - projAnchor[0];
                            final double dy = ps.py() - projAnchor[1];
                            final double dz = ps.pz() - projAnchor[2];
                            lines.add(String.format(
                                    "  相对初始锚偏移=xyz(%.2f,%.2f,%.2f) %s",
                                    dx, dy, dz,
                                    (Math.abs(dx) + Math.abs(dy) + Math.abs(dz)) > 0.5
                                            ? "★ 物理已偏离初始位置" : "（静止在初始位置）"));
                        }
                    } else {
                        lines.add("  物理位姿=null（核心未同步快照）");
                    }
                }
                // ⑥ 持久化状态
                try {
                    final PersistedSubLevel stored =
                            CryptandSubLevelPersistence
                                    .instance().load(level, id);
                    lines.add("  持久化=" + (stored != null
                            ? "存在(blocks=" + (stored.blocks() != null ? stored.blocks().size() : 0)
                            + ", anchor=" + (stored.anchor() != null ? stored.anchor().toShortString() : "?") + ")"
                            : "无"));
                } catch (final Throwable t) {
                    lines.add("  持久化查询失败=" + t);
                }
            }
            if (container.getSubLevelMap().isEmpty() && uuids.isEmpty()) {
                lines.add("（无已登记物理化结构）");
            }
        } catch (final Throwable t) {
            lines.add("dumpStructureInfo 失败=" + t);
        }
        return lines;
    }

    /** 列出当前世界全部已物理化亚层 uuid（诊断/遍历用）。 */
    public static java.util.List<java.util.UUID> allSubLevelIds(final ServerLevel level) {
        final java.util.LinkedHashSet<UUID> uuids = new java.util.LinkedHashSet<>();
        try {
            final CryptandSubLevelContainer container = CryptandSubLevelContainer.getContainer(level);
            for (final CryptandSubLevel s : container.getSubLevelMap().values()) {
                if (s != null) uuids.add(s.getUniqueId());
            }
            uuids.addAll(MOVED.keySet());
            uuids.addAll(SUB_LEVEL_RUNTIME.keySet());
            uuids.addAll(PROJECTION_ANCHOR.keySet());
        } catch (final Throwable ignored) {
        }
        return new java.util.ArrayList<>(uuids);
    }

    /**
     * 所有已物理化亚层（uuid → 投影锚点浮点中心 [cx,cy,cz]）。
     * 供激活判定（附近区块加载 → 唤醒物理）遍历。
     */
    public static java.util.Map<UUID, double[]> allProjectionAnchors() {
        return java.util.Collections.unmodifiableMap(new java.util.HashMap<>(PROJECTION_ANCHOR));
    }

    /**
     * ★ 物理激活判定（主线程应答，2026-09-01）：
     * 物理激活取决于【附近区块是否加载/强制加载】——即 anchor 所在区块处于
     * block-ticking 范围（玩家附近）或亚层容器范围内。活跃 → 唤醒物理；
     * 否则休眠（远离/卸载 → 不步进，节省算力）。
     *
     * <p>调用方：{@code SableServerBridge.tick} 每次收到 {@code ChunkActivationQuery}
     * 时调用；查询由物理 worker 每 N 次心跳发起（用户：异步线程定时向主线程查询）。
     *
     * @param level 服务端世界
     * @param runtimeIds       待判定 runtimeId 列表
     * @param xs, ys, zs      每个结构当前世界位置（与 runtimeIds 同序）
     * @return 活跃（应唤醒/保持步进）的 runtimeId 集合
     */
    public static java.util.Set<Integer> resolveActiveBodies(
            final ServerLevel level, final int[] runtimeIds,
            final double[] xs, final double[] ys, final double[] zs) {
        final java.util.Set<Integer> active = new java.util.HashSet<>();
        if (level == null || runtimeIds == null) return active;
        try {
            // ★ 核心自研激活判定（替代官方 PhysicsChunkTicketManager；零官方依赖）：
            //   物理激活 = 该 chunk 处于【已加载状态】（玩家附近/强制加载范围）——
            //   即 chunk 已在内存且可被区块 tick（block ticking range 或已加载）。
            for (int i = 0; i < runtimeIds.length; i++) {
                final int cx = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(xs[i]));
                final int cz = net.minecraft.core.SectionPos.blockToSectionCoord((int) Math.floor(zs[i]));
                if (isChunkLoadedEnoughSelf(level, cx, cz)) {
                    active.add(runtimeIds[i]);
                }
            }
        } catch (final Throwable t) {
            // 判定失败：保守全部活跃（不因区块查询失败冻结物理）
            for (final int id : runtimeIds) active.add(id);
        }
        return active;
    }

    /**
     * 核心自研区块足载判定（替代官方 PhysicsChunkTicketManager.isChunkLoadedEnough）：
     * 区块已加载（{@link ServerLevel#isLoaded}）或在玩家区块 tick 范围
     * （{@code DistanceManager.inBlockTickingRange}）→ 视为可驱动物理。
     */
    private static boolean isChunkLoadedEnoughSelf(final ServerLevel level, final int x, final int z) {
        try {
            if (level.isLoaded(new net.minecraft.core.BlockPos(x << 4, level.getMinBuildHeight(), z << 4))) {
                return true;
            }
            final net.minecraft.server.level.DistanceManager dm =
                    level.getChunkSource().chunkMap.getDistanceManager();
            return dm.inBlockTickingRange(net.minecraft.world.level.ChunkPos.asLong(x, z));
        } catch (final Throwable t) {
            return true; // 保守：查询失败视作已加载
        }
    }

    /**
     * ★ 地表支撑采样（主线程，2026-09-01 v3 最终修复）：返回每个 runtimeId **贴地时
     * 结构中心应保持的 y**（= 世界实心方块顶面 + 结构半高），供物理体贴地。
     *
     * <p>v1 用 Heightmap.MOTION_BLOCKING（最高实心方块顶）→ "头上一放方块结构跑上去"：
     * 头顶方块被高度图当"地面"。v2 改为从结构中心列向下扫描（头顶方块不再影响），
     * 但返回"顶面 y"与模拟器比较"中心 y"基准不一致 → 结构掉 1 格（草方块掉出包围盒）。
     * v3：groundY = 扫描到的世界实心块顶面 + halfHeight（结构半高 = (maxY-minY+1)/2，
     * 由 MOVED 包围盒推算）—— center=groundY 时结构底面恰好贴世界地面。
     *
     * @param ys 各 runtimeId 当前 y（结构中心；扫描基准；null 则回退高度图）
     */
    public static java.util.Map<Integer, Double> resolveGroundYs(
            final ServerLevel level, final int[] runtimeIds,
            final double[] xs, final double[] ys, final double[] zs) {
        final java.util.Map<Integer, Double> out = new java.util.HashMap<>();
        if (level == null || runtimeIds == null) return out;
        try {
            for (int i = 0; i < runtimeIds.length; i++) {
                final int bx = (int) Math.floor(xs[i]);
                final int bz = (int) Math.floor(zs[i]);
                final boolean useY = ys != null && ys.length > i;
                if (!level.isLoaded(new net.minecraft.core.BlockPos(bx, level.getMinBuildHeight(), bz))) {
                    out.put(runtimeIds[i], Double.NaN);
                    continue;
                }
                // 结构半高（盒 center → 底面的距离；MOVED 包围盒推算；默认 0.5）
                final double halfH = subLevelHalfHeight(runtimeIds[i]);
                double groundCenter = Double.NaN;
                if (useY) {
                    final int fromY = (int) Math.floor(ys[i]);
                    final int minScan = Math.max(level.getMinBuildHeight() + 1, fromY - 32);
                    for (int y = fromY - 1; y >= minScan; y--) {
                        final net.minecraft.core.BlockPos p = new net.minecraft.core.BlockPos(bx, y, bz);
                        final net.minecraft.world.level.block.state.BlockState bs = level.getBlockState(p);
                        if (bs.isSolid() && bs.isCollisionShapeFullBlock(level, p)) {
                            groundCenter = y + 1.0 + halfH;   // 顶面 + 半高 = 贴地时中心 y
                            break;
                        }
                    }
                }
                if (Double.isNaN(groundCenter)) {
                    // 回退：heightmap（无 ys 参数/未知时）
                    final int top = level.getHeight(
                            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
                    if (top >= level.getMinBuildHeight()) groundCenter = top + 0.5 + halfH;
                }
                out.put(runtimeIds[i], Double.isNaN(groundCenter) ? Double.NaN : groundCenter);
            }
        } catch (final Throwable t) {
            // 采样失败 → NaN（不贴地，保持原行为）
        }
        return out;
    }

    /** 由 runtimeId 反查亚层 MOVED 包围盒，返回结构半高（(maxY-minY+1)/2.0；无 → 0.5）。 */
    private static double subLevelHalfHeight(final int runtimeId) {
        try {
            for (final java.util.Map.Entry<UUID, Integer> e : SUB_LEVEL_RUNTIME.entrySet()) {
                if (e.getValue() != runtimeId) continue;
                final java.util.List<PlacedBlockSnapshot> snaps = MOVED.get(e.getKey());
                if (snaps == null || snaps.isEmpty()) return 0.5;
                int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
                for (final PlacedBlockSnapshot s : snaps) {
                    if (s == null || s.worldPos() == null) continue;
                    minY = Math.min(minY, s.worldPos().getY());
                    maxY = Math.max(maxY, s.worldPos().getY());
                }
                if (minY == Integer.MAX_VALUE) return 0.5;
                return (maxY - minY + 1) * 0.5;
            }
        } catch (final Throwable ignored) {
            // 查询失败 → 默认半高
        }
        return 0.5;
    }

    // ===== 渲染同步（S2C） =====

    /** ★ 2026-09-01 pull 渲染：全部 MOVED 条目（resendRenderDataTo 全量补发用）。 */
    public static java.util.Map<UUID, java.util.List<PlacedBlockSnapshot>> allMoved() {
        return MOVED;
    }

    /** 构造亚层渲染 payload（方块编码 stateId+偏移；anchor=结构包围盒中心——与物理体初始位姿同基准）。 */
    public static SableSubLevelRenderPayload
    buildRenderPayload(final UUID subLevelId, final int runtimeId,
                       final java.util.List<PlacedBlockSnapshot> snapshots) {
        if (snapshots.isEmpty()) return null;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final PlacedBlockSnapshot s : snapshots) {
            final BlockPos b = s.worldPos();
            minX = Math.min(minX, b.getX()); minY = Math.min(minY, b.getY()); minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX()); maxY = Math.max(maxY, b.getY()); maxZ = Math.max(maxZ, b.getZ());
        }
        // 统一锚 = 结构包围盒中心（渲染偏移/物理体初始位姿同基准 → 不再偏移不定）
        final int anchorX = (int) Math.floor((minX + maxX + 1) * 0.5);
        final int anchorY = (int) Math.floor((minY + maxY + 1) * 0.5);
        final int anchorZ = (int) Math.floor((minZ + maxZ + 1) * 0.5);
        final List<Integer> blockData = new ArrayList<>(snapshots.size() * 4);
        for (final PlacedBlockSnapshot s : snapshots) {
            final BlockPos b = s.worldPos();
            final int stateId = net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY
                    .getId(s.state());
            blockData.add(stateId);
            blockData.add(b.getX() - anchorX);
            blockData.add(b.getY() - anchorY);
            blockData.add(b.getZ() - anchorZ);
        }
        return new SableSubLevelRenderPayload(
                subLevelId, runtimeId,
                anchorX, anchorY, anchorZ,
                minX, minY, minZ, maxX, maxY, maxZ,
                blockData, false);
    }

    /** 广播亚层渲染 payload（物理化装载）。 */
    private static void broadcastRenderSync(final ServerLevel level, final UUID subLevelId,
                                            final int runtimeId,
                                            final java.util.List<PlacedBlockSnapshot> snapshots) {
        try {
            final SableSubLevelRenderPayload payload =
                    buildRenderPayload(subLevelId, runtimeId, snapshots);
            if (payload == null) return;
            final net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet =
                    new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload);
            for (final net.minecraft.server.level.ServerPlayer player : level.players()) {
                player.connection.send(packet);
            }
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] render sync failed for {}: {}", subLevelId, t.toString());
        }
    }

    /** 广播亚层渲染移除（拆卸）。 */
    private static void broadcastRenderRemove(final ServerLevel level, final UUID subLevelId) {
        try {
            final SableSubLevelRenderPayload payload =
                    new SableSubLevelRenderPayload(
                            subLevelId, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                            java.util.Collections.emptyList(), true);
            final net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket packet =
                    new net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket(payload);
            for (final net.minecraft.server.level.ServerPlayer player : level.players()) {
                player.connection.send(packet);
            }
        } catch (final Throwable t) {
            // 渲染移除失败不阻断拆卸
        }
    }

    // ===== 持久化桥接 =====

    /** MOVED 快照 → 持久化数据（用于保存到 sqlite/sable 后端）。
     *  ★ 2026-09-05 携带空间关系（spaceId/origin——从引擎空间表查询；恢复时重建关系）。 */
    private static PersistedSubLevel
    buildPersisted(final UUID subLevelId, final int runtimeId, final BlockPos anchor,
                   final java.util.List<PlacedBlockSnapshot> snapshots) {
        final java.util.List<PersistedBlock> blocks =
                new ArrayList<>(snapshots.size());
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (final PlacedBlockSnapshot s : snapshots) {
            final BlockPos p = s.worldPos();
            blocks.add(new PersistedBlock(
                    p, s.state(), s.blockEntityTag()));
            minX = Math.min(minX, p.getX()); minY = Math.min(minY, p.getY()); minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX()); maxY = Math.max(maxY, p.getY()); maxZ = Math.max(maxZ, p.getZ());
        }
        // ★ 2026-09-05 空间关系（从引擎空间表查询；保存为 -1 表示未分配）
        int spaceId = -1;
        double sox = 0, soy = 0, soz = 0;
        try {
            final OfficialRapierEngine eng =
                    CryptandSable.instance().officialEngine();
            if (eng != null) {
                final int sid = eng.spaceIdForRuntime(runtimeId);
                if (sid != 0) {
                    spaceId = sid;
                    final double[] so = eng.spaceOriginCopy(sid);
                    if (so != null) {
                        sox = so[0]; soy = so[1]; soz = so[2];
                    }
                }
            }
        } catch (final Throwable ignored) {
        }
        return new PersistedSubLevel(
                subLevelId, runtimeId, anchor,
                minX, minY, minZ, maxX, maxY, maxZ, blocks,
                spaceId, sox, soy, soz);
    }

    /**
     * 世界加载恢复：从持久化数据重建亚层（登记容器 + 回填 MOVED + 重新注册核心体 +
     * 广播渲染同步）。由 {@code CryptandSubLevelPersistence.onWorldLoad} 调用。
     *
     * @return 是否恢复成功
     */
    public static boolean restorePersisted(final ServerLevel level,
                                           final PersistedSubLevel p) {
        if (level == null || p == null || p.blocks() == null || p.blocks().isEmpty()) {
            return false;
        }
        // ★ 2026-09-06 【核心总开关】关闭 → 不恢复已物理化亚层（无核心驱动）；存档保留，
        //   重新开启核心后下次世界加载再恢复。
        if (!ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return false;
        }
        try {
            final CryptandBounds3i box = new CryptandBounds3i(
                    p.boundMinX(), p.boundMinY(), p.boundMinZ(),
                    p.boundMaxX(), p.boundMaxY(), p.boundMaxZ());
            final CryptandPose3d pose = new CryptandPose3d();
            pose.position().set(p.anchor().getX() + 0.5, p.anchor().getY() + 0.5, p.anchor().getZ() + 0.5);
            final CryptandServerSubLevel subLevel = CryptandServerSubLevel.createWithAnchor(level, box, p.anchor());
            final CryptandLevelPlot plot = subLevel.getPlot();
            if (plot != null) {
                plot.setBoundingBox(new CryptandBounds3i(box));
            }
            final CryptandSubLevelContainer container = CryptandSubLevelContainer.getContainer(level);
            container.getSubLevelMap().put(p.subLevelId(), subLevel);
            // ★ 2026-09-02 幽灵重复结构根因修复：恢复时【重新分配唯一 runtimeId】，
            //   不信任存档 p.runtimeId()——历史存档若存了重复 runtimeId，重载后多结构
            //   共享同一 native 刚体 + 同一 SUB_LEVEL_RUNTIME 映射 → 投影/位姿互相串扰。
            final int rt = CryptandSable.instance().allocateRuntimeId();
            subLevel.setRuntimeId(rt);
            // 装配器关联恢复（重进后装配器仍能精确命中本亚层）
            registerAssociation(p.anchor(), p.subLevelId());
            // ★ 2026-09-01 恢复碰撞投影表：runtimeId + 投影锚（否则 feedCollectShapes
            //   subLevelRuntimeId=0 → pose=null → shape 停在初始位置 → 穿模）。
            SUB_LEVEL_RUNTIME.put(p.subLevelId(), rt);
            // float 几何中心（与 buildImport 初始位姿同基准：(min+max+1)*0.5）
            PROJECTION_ANCHOR.put(p.subLevelId(), new double[]{
                    (p.boundMinX() + p.boundMaxX() + 1) * 0.5,
                    (p.boundMinY() + p.boundMaxY() + 1) * 0.5,
                    (p.boundMinZ() + p.boundMaxZ() + 1) * 0.5});

            // 回填 MOVED（拆卸可还原）
            final java.util.List<PlacedBlockSnapshot> snaps = new ArrayList<>(p.blocks().size());
            for (final PersistedBlock pb : p.blocks()) {
                snaps.add(new PlacedBlockSnapshot(pb.worldPos(), pb.state(), pb.blockEntityTag()));
            }
            MOVED.put(p.subLevelId(), snaps);

            // 重新注册核心体（voxel 密度从 MOVED 重建；用重新分配的唯一 runtimeId）
            CryptandSable.instance().importBody(buildImport(level, p.anchor(), posListOf(snaps), box, rt));
            // ★ 2026-09-05 恢复空间关系（持久化中保存的 spaceId/origin；重建空间表）
            if (p.spaceId() >= 1) {
                try {
                    OfficialRapierEngine eng =
                            CryptandSable.instance().officialEngine();
                    if (eng != null) {
                        eng.restoreSpaceRelation(rt, p.spaceId(),
                                p.spaceOriginX(), p.spaceOriginY(), p.spaceOriginZ());
                    }
                } catch (final Throwable ignored) {
                }
            }
            // 广播渲染同步（客户端重进世界后显示）
            broadcastRenderSync(level, p.subLevelId(), rt, snaps);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[CryptandSable] restored sub={} runtimeId={} blocks={}",
                    p.subLevelId(), rt, snaps.size());
            return true;
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] restore failed for {}: {}", p.subLevelId(), t.toString());
            return false;
        }
    }

    /** PlacedBlockSnapshot → BlockPos 列表（恢复核心体密度采样用）。 */
    private static List<BlockPos> posListOf(final java.util.List<PlacedBlockSnapshot> snaps) {
        final List<BlockPos> out = new ArrayList<>(snaps.size());
        for (final PlacedBlockSnapshot s : snaps) {
            out.add(s.worldPos());
        }
        return out;
    }
}