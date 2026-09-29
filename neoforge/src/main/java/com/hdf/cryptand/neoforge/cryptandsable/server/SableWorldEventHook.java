package com.hdf.cryptand.neoforge.cryptandsable.server;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.OfficialRapierEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * ★ 2026-09-05 世界事件实时增量更新（参考官方 sable 的 chunk 事件驱动机制）：
 * 官方 RapierPhysicsPipeline 监听世界 chunk section 增删/单方块变化 → 实时
 * addChunk/removeChunk/changeBlock 增量更新物理世界。
 * <p>
 * 我们（shape 路线）等价：世界方块变化 → 通知官方引擎重扫受影响 section →
 * syncShapeCompanion 增量 diff（addShapeColliderAt/removeShapeColliderAt）。
 * 玩家在结构旁挖/放方块、区块加载 → 陪体实时跟随，无需等下一轮全量收集。
 */
public final class SableWorldEventHook {

    /** 服务器级（全局单例，由 SableServerBridge 或事件总线注册）。 */
    public static final SableWorldEventHook INSTANCE = new SableWorldEventHook();

    private SableWorldEventHook() {
    }

    /** 当前引擎（由 ServerLifecycle hook 注入；null = 未启动）。 */
    private OfficialRapierEngine engine;

    public void bind(final OfficialRapierEngine eng) {
        this.engine = eng;
    }

    public void unbind() {
        this.engine = null;
    }

    private OfficialRapierEngine eng() {
        return this.engine;
    }

    /**
     * ★ 方块被破坏 → 该格从世界移除 → 陪体 box 增量移除。
     */
    @SubscribeEvent
    public void onBlockBreak(final BlockEvent.BreakEvent event) {
        final OfficialRapierEngine e = eng();
        if (e == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final BlockPos pos = event.getPos();
        try {
            e.onWorldBlockChanged(level, pos.getX(), pos.getY(), pos.getZ(), true, false);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn("[CryptandSable] onBlockBreak hook failed: {}", t.toString());
        }
    }

    /**
     * ★ 方块被放置 → 该格加入世界 → 陪体 box 增量添加。
     */
    @SubscribeEvent
    public void onBlockPlaced(final BlockEvent.EntityPlaceEvent event) {
        final OfficialRapierEngine e = eng();
        if (e == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final BlockPos pos = event.getPos();
        // 方块放置后世界状态已更新（getBlockState 反映新方块）
        try {
            e.onWorldBlockChanged(level, pos.getX(), pos.getY(), pos.getZ(), false, true);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn("[CryptandSable] onBlockPlaced hook failed: {}", t.toString());
        }
    }

    /**
     * ★ 区块加载（新增世界碰撞可用）→ 重扫活跃结构附近该区块的 section → 增量同步。
     * 只处理【服务器端】; null chunk 或客户端忽略。
     */
    @SubscribeEvent
    public void onChunkLoad(final ChunkEvent.Load event) {
        final OfficialRapierEngine e = eng();
        if (e == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final net.minecraft.world.level.chunk.ChunkAccess chunk = event.getChunk();
        if (chunk == null) return;
        try {
            // ★ 2026-09-05 空间加载计数（空间表 loadedCount +1）
            e.onSpaceChunkLoaded(chunk.getPos().x, chunk.getPos().z);
            e.onWorldChunkLoaded(level, chunk.getPos());
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn("[CryptandSable] onChunkLoad hook failed: {}", t.toString());
        }
    }

    /**
     * ★ 区块卸载（原官方 handleChunkSectionRemoval 对应）——【不更新陪体】（box 保留支撑）。
     * 但【更新空间加载计数】（loadedCount −1；=0 时空间整体跳过计算）。
     * 2026-09-05 用户定案：chunk 卸载后陪体 box【保留】更稳定（scannedBlocks 数据在
     * PhysicalizedData 内不消失，box 只是其镜像；卸载即删会让结构突然失去支撑而倒塌，
     * 且下一轮全量收集也扫不到未加载区块）。chunk 重新加载时 onChunkLoad 重扫该
     * section → syncShapeCompanion diff 无变化 → no-op。onUnload 只维护空间 loadedCount。
     */
    @SubscribeEvent
    public void onChunkUnload(final net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        final OfficialRapierEngine e = eng();
        if (e == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final net.minecraft.world.level.chunk.ChunkAccess chunk = event.getChunk();
        if (chunk == null) return;
        try {
            // ★ 2026-09-05 空间加载计数（loadedCount −1；=0 → 空间跳过计算）
            e.onSpaceChunkUnloaded(chunk.getPos().x, chunk.getPos().z);
        } catch (final Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn("[CryptandSable] onChunkUnload hook failed: {}", t.toString());
        }
    }
}
