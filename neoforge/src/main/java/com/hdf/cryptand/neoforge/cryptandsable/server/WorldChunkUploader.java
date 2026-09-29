/**
 * ===== 世界静态体素上传器（WorldChunkUploader，2026-09-01） =====
 *
 * 官方 Sable 的关键行为：除亚层结构自身的动态方体外，世界（主世界）的静态方块
 * 也作为【静态碰撞体】上传给 Rapier（addChunk global=true）——结构物理化后
 * 能【落地/被世界方块挡住】，不会穿透地面坠入虚空（用户："物理化后就消失了"）。
 *
 * 本类（服务端，主线程）：
 *  - 维护【已上传的世界 chunk section 集合】（以 section pos 为键）
 *  - uploadSection(level, sectionPos)：构建 16x16x16 int[]（VoxelNeighborhoodState
 *    邻域判定 + 方块 collider）→ OfficialRapierEngine.addWorldChunk
 *  - removeSection(sectionPos)：removeChunk(global=true)
 *  - 增量：结构物理化时上传【其周围局部范围】的世界 section（MVP：结构包围盒
 *    外扩 2 格内的 section），防无限上传。
 *
 * 邻域判定 VoxelNeighborhoodState.getState（Corner/Edge/Face/Interior/Empty）：
 * 简化 MVP：FullBlock&Solid&NeighborSolid → INTERIOR(4)；非满块 → CORNER(3)；
 * 全向实心 → EDGE(2)/FACE(1) 由 4 方向统计；空 → EMPTY(0)。
 */
package com.hdf.cryptand.neoforge.cryptandsable.server;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.OfficialRapierEngine;
import com.hdf.cryptand.neoforge.cryptandsable.core.backend.official.SectionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.HashSet;
import java.util.Set;

public final class WorldChunkUploader {

    /** 已上传的世界 section（section 坐标 → true；防重复上传）。 */
    private final Set<SectionKey> uploadedSections = new HashSet<>();

    /** ★ 2026-09-02 官方方式：世界 chunk 由【结构 runtimeId】挂载（挂到结构 body 自身）。
     *  key = runtimeId（结构）；无则 global。每个结构收集自己周围的世界块。 */
    private final java.util.Map<Integer, Set<SectionKey>> bodySections = new java.util.HashMap<>();

    /** ★ 2026-09-02 【每结构独立缓存】收集的世界 section 数据：
     *  key = 结构 runtimeId → Map<SectionKey, int[4096] 块数据>。
     *  物理化时收集 → 存入该结构缓存 → 挂到该结构 body。 */
    private final java.util.Map<Integer, java.util.Map<SectionKey, int[]>> perBodySections = new java.util.HashMap<>();

    /** ★ 2026-09-02 某结构收集的 section 数据缓存（构建后挂载；收集时填充）。 */
    public java.util.Map<SectionKey, int[]> sectionCache(int runtimeId) {
        return perBodySections.computeIfAbsent(runtimeId, k -> new java.util.HashMap<>());
    }

    /** ★ 2026-09-05 【变化检测跳过】每结构上次成功收集的 clip bounds [minX,minY,minZ,maxX,maxY,maxZ]
     *  （已含 radius 外扩）与理论 section 数。结构未移动（bounds 未变）且缓存已全量 → 跳过
     *  全量重扫（防超大结构每 tick 全量读 Level 卡主线程）。世界方块变化由事件路径
     *  （onWorldBlockChanged/onWorldChunkLoaded）增量覆盖，不会因跳过丢失。 */
    private final java.util.Map<Integer, long[]> lastScanClipByBody = new java.util.HashMap<>();
    private final java.util.Map<Integer, Integer> lastScanSectionCountByBody = new java.util.HashMap<>();

    /** ★ 2026-09-05 计算 clip 范围内理论 section 总数（uploadAroundBounds 遍历全集；含未加载）。 */
    public static int sectionCountInBounds(int minX, int minY, int minZ,
                                           int maxX, int maxY, int maxZ) {
        final int sx0 = SectionPos.blockToSectionCoord(minX);
        final int sx1 = SectionPos.blockToSectionCoord(maxX);
        final int sy0 = SectionPos.blockToSectionCoord(minY);
        final int sy1 = SectionPos.blockToSectionCoord(maxY);
        final int sz0 = SectionPos.blockToSectionCoord(minZ);
        final int sz1 = SectionPos.blockToSectionCoord(maxZ);
        return (sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1);
    }

    /** ★ 2026-09-05 变化检测：clip（外扩后）与上次一致 且 缓存已含该 clip 全部理论 section
     *  → true（可跳过全量重扫，直接回传缓存）。首次/移动（bounds 变）→ false（需重扫）。
     *  ★ 2026-09-05 【暂时取消主线程扫描缓存（用户定案）】恒 false —— 每轮全量重扫：
     *   只有放下一瞬间有黄框/陪体（缓存跳过导致静止后不再扫描/陪体不跟随 → 穿透）。
     *   牺牲性能换正确性（结构静止也持续重扫世界方块，陪体/黄框始终跟随）。 */
    public synchronized boolean canSkipRescan(final int runtimeId,
                                             final int minX, final int minY, final int minZ,
                                             final int maxX, final int maxY, final int maxZ) {
        return false;   // ★ 2026-09-05 暂时禁用（取消主线程扫描缓存）
    }

    /** ★ 2026-09-05 记录一次成功收集（变化检测用）：clip + 该 clip 理论 section 数。 */
    public synchronized void recordScanDone(final int runtimeId,
                                            final int minX, final int minY, final int minZ,
                                            final int maxX, final int maxY, final int maxZ) {
        lastScanClipByBody.put(runtimeId, new long[]{minX, minY, minZ, maxX, maxY, maxZ});
        lastScanSectionCountByBody.put(runtimeId,
                sectionCountInBounds(minX, minY, minZ, maxX, maxY, maxZ));
    }

    /** ★ 2026-09-02 【每结构独立】最近扫描的实心方块列表（供打印/调试）。 */
    private final java.util.Map<Integer, java.util.List<long[]>> scannedBlocksPerBody = new java.util.HashMap<>();

    /** ★ 2026-09-02 某结构最近扫描的方块列表（不消耗）。 */
    public synchronized java.util.List<long[]> scannedBlocks(int runtimeId) {
        return new java.util.ArrayList<>(scannedBlocksPerBody.getOrDefault(runtimeId, java.util.Collections.emptyList()));
    }

    /** ★ 2026-09-02 清除某结构扫描列表。 */
    public synchronized void clearScannedBlocks(int runtimeId) {
        scannedBlocksPerBody.remove(runtimeId);
    }

    /** ★ 2026-09-02 记录扫描到的一个实心方块（按结构）。 */
    public synchronized void recordScannedBlock(int runtimeId, int bx, int by, int bz) {
        final java.util.List<long[]> list = scannedBlocksPerBody.computeIfAbsent(
                runtimeId, k -> new java.util.ArrayList<>());
        if (list.size() < 512) {
            list.add(new long[]{bx, by, bz});
        }
    }

    /** ★ 2026-09-02 清除某结构的收集缓存（结构移除时调用）。 */
    public synchronized void clearSectionCache(int runtimeId) {
        perBodySections.remove(runtimeId);
        bodySections.remove(runtimeId);
    }

    /** ★ 2026-09-02 该结构已缓存的 section 数与方块数（调试）。 */
    public synchronized int[] sectionCacheStats(int runtimeId) {
        final java.util.Map<SectionKey, int[]> m = perBodySections.get(runtimeId);
        if (m == null) return new int[]{0, 0};
        int blocks = 0;
        for (final int[] d : m.values()) {
            for (final int v : d) if (v != 0) blocks++;
        }
        return new int[]{m.size(), blocks};
    }

    private OfficialRapierEngine engine;

    public WorldChunkUploader(OfficialRapierEngine engine) {
        this.engine = engine;
    }

    /** 绑定引擎（引擎重建时重设）。 */
    public void bind(OfficialRapierEngine engine) {
        this.engine = engine;
        this.uploadedSections.clear();
        this.bodySections.clear();
        this.perBodySections.clear();
        // ★ 2026-09-02 复位挂载目标：防跨世界泄漏旧 mount/目标（残留 -1 = global 已禁用）
        this.uploadMountBody = -1;
        this.uploadTargetBodyId = -1;
    }

    /** 引擎是否可用。 */
    public boolean available() {
        return engine != null && engine.isStarted();
    }

    /** ★ 2026-09-02 上传目标结构 runtimeId（-1 = global 世界端）。由 SableServerBridge 收集时设置。 */
    private int uploadTargetBodyId = -1;

    /** ★ 2026-09-02 挂载方式：≥0 挂结构 body（addChunkToBody）；-1 = global（world octree，ground 跨体碰撞）。 */
    private int uploadMountBody = -1;

    /** ★ 2026-09-04 扫描裁剪范围（红框；结构 ± radius；-1=不裁剪）。只记录/上传框内方块。 */
    private int clipMinX = -1, clipMinY = -1, clipMinZ = -1;
    private int clipMaxX = -1, clipMaxY = -1, clipMaxZ = -1;

    /** ★ 2026-09-05 【弃置区（绝对坐标）】空间原点 section 偏移字段——Rust 直接用主世界
     *  坐标，originSec 恒 0（保留 setter 兼容调用；不再有实际偏移作用）。 */
    private int originSecX = 0, originSecY = 0, originSecZ = 0;

    /** ★ 2026-09-05 【弃置区（绝对坐标）】设置空间原点 section 偏移——恒 0 兼容保留。 */
    public synchronized void setSpaceOriginSectionOffset(final int ox, final int oy, final int oz) {
        this.originSecX = 0; this.originSecY = 0; this.originSecZ = 0;
    }

    /** ★ 2026-09-05 【未加载保护】每轮收集成功扫描到的 section 集合（"需要更新的方块区域"）。
     *  uploadSection 成功读到 chunk 后加入；未加载/越界不加入 → 这些 section 【缓存不更新
     *  保持原状】（旧值保留，不因未加载而清空导致支撑丢失）。 */
    private final java.util.Set<SectionKey> scannedThisRound = new HashSet<>();

    /** ★ 2026-09-05 开始新一轮收集：重置"本轮已扫描区域"标记（不触碰 sectionCache——旧值保留）。 */
    public synchronized void beginScanRound() {
        scannedThisRound.clear();
    }

    /** ★ 2026-09-05 本轮已成功扫描的 section 集合（"已更新区域"；供日志/调试）。 */
    public synchronized java.util.Set<SectionKey> scannedThisRound() {
        return new HashSet<>(scannedThisRound);
    }

    /** ★ 2026-09-05 本轮已扫描的 section 数（调试日志）。 */
    public synchronized int scannedThisRoundCount() {
        return scannedThisRound.size();
    }

    /** ★ 2026-09-05 【区域清理】删除缓存中【不在 clip 范围】的 section（结构移动后旧位置
     *  不残留）；clip 范围内但未加载的 section 保留（旧值不动）。-1(不裁剪) 时不清理。 */
    public synchronized void pruneSectionCacheOutOfClip(final int runtimeId) {
        if (clipMinX == -1) return;
        final java.util.Map<SectionKey, int[]> m = perBodySections.get(runtimeId);
        if (m == null || m.isEmpty()) return;
        final java.util.Iterator<java.util.Map.Entry<SectionKey, int[]>> it = m.entrySet().iterator();
        while (it.hasNext()) {
            final SectionKey k = it.next().getKey();
            if (!sectionInClip(k)) it.remove();
        }
    }

    /** section 与 clip 范围是否有交集（以方块范围判断：clip 裁剪在方块粒度）。 */
    private boolean sectionInClip(final SectionKey k) {
        if (clipMinX == -1) return true;
        final int sx0 = k.x() << 4, sx1 = sx0 + 15;
        final int sy0 = k.y() << 4, sy1 = sy0 + 15;
        final int sz0 = k.z() << 4, sz1 = sz0 + 15;
        return sx1 >= clipMinX && sx0 <= clipMaxX
                && sy1 >= clipMinY && sy0 <= clipMaxY
                && sz1 >= clipMinZ && sz0 <= clipMaxZ;
    }

    /** ★ 设置扫描裁剪范围（红框 = 结构中心 ± radius；-1 不裁剪）。 */
    public synchronized void setClipBounds(final int minX, final int minY, final int minZ,
                                           final int maxX, final int maxY, final int maxZ) {
        this.clipMinX = minX; this.clipMinY = minY; this.clipMinZ = minZ;
        this.clipMaxX = maxX; this.clipMaxY = maxY; this.clipMaxZ = maxZ;
    }

    /** ★ 2026-09-05 当前裁剪范围快照 [minX,minY,minZ,maxX,maxY,maxZ]；未设置 → null。 */
    public synchronized int[] clipBoundsSnapshot() {
        if (clipMinX == -1) return null;
        return new int[]{clipMinX, clipMinY, clipMinZ, clipMaxX, clipMaxY, clipMaxZ};
    }

    /** ★ 是否在扫描裁剪范围内（红框内）。 */
    private boolean inClip(final int x, final int y, final int z) {
        if (clipMinX == -1) return true;   // 不裁剪
        return x >= clipMinX && x <= clipMaxX
                && y >= clipMinY && y <= clipMaxY
                && z >= clipMinZ && z <= clipMaxZ;
    }

    /** ★ 2026-09-02 调试：最近上传的碰撞方块世界坐标（供服务端粒子高亮）。 */
    private final Set<Long> debugHitBlocks = new HashSet<>();
    private long debugHitStamp = 0;

    /** ★ 2026-09-02 调试：最近一轮收集的【实心方块列表】（收集完成后打印）。 */
    private final java.util.List<long[]> lastCollectedBlocks = new java.util.ArrayList<>();

    /** ★ 调试：记录一个碰撞方块（世界坐标 → 高亮用）。 */
    public synchronized void debugMarkHit(int bx, int by, int bz) {
        long k = ((long) bx & 0x3FFFFFF) | (((long) by & 0x3FFFFFF) << 26) | (((long) bz & 0x3FFFFFF) << 52);
        debugHitBlocks.add(k);
        debugHitStamp = System.nanoTime();
        // ★ 2026-09-02 收集列表（上限保护）
        if (lastCollectedBlocks.size() < 512) {
            lastCollectedBlocks.add(new long[]{bx, by, bz});
        }
    }

    /** ★ 调试：取最近一轮收集的实心方块列表（不消耗）。 */
    public synchronized java.util.List<long[]> lastCollectedBlocks() {
        return new java.util.ArrayList<>(lastCollectedBlocks);
    }

    /** ★ 调试：清除最近一轮收集列表。 */
    public synchronized void clearLastCollectedBlocks() {
        lastCollectedBlocks.clear();
    }

    /** ★ 调试：取高亮方块列表（消耗；返回后清空）。 */
    public synchronized long[] debugTakeHits() {
        long[] arr = new long[debugHitBlocks.size()];
        int i = 0;
        for (long v : debugHitBlocks) arr[i++] = v;
        debugHitBlocks.clear();
        return arr;
    }

    /** ★ 2026-09-02 mixin 调用：记录命中方块（世界坐标 → 高亮用）。 */
    public void cryptand$debugMarkHit(int bx, int by, int bz) {
        debugMarkHit(bx, by, bz);
    }

    /** ★ 调试：最近高亮时间戳（毫秒）。 */
    public synchronized long debugLastHitMillis() {
        return debugHitStamp / 1_000_000L;
    }

    /** 设置上传目标结构（本轮收集挂载目标；-1=global 旧行为）。 */
    public synchronized void setUploadTargetBody(int runtimeId) {
        this.uploadTargetBodyId = runtimeId;
    }

    /** 设置挂载目标（≥0 挂结构 body；-1 挂 global world octree；shape 模式恒 -1 仅缓存）。 */
    public synchronized void setUploadMountBody(int runtimeId) {
        this.uploadMountBody = runtimeId;
    }

    /** ★ 2026-09-01 已上传（含空标记）的 section 数（调试）。 */
    public int uploadedSectionCount() {
        return uploadedSections.size();
    }

    /** 调试：某 section 是否已上传。 */
    public boolean isSectionUploaded(SectionPos sp) {
        return sp != null && uploadedSections.contains(
                new SectionKey(sp.x(), sp.y(), sp.z()));
    }

    // ===== ★ 2026-09-01 方案B：临时虚拟世界体（聚合静态碰撞） =====

    /** 当前虚拟世界体 id（首次收集创建，-1=未创建）。 */
    private int staticWorldBodyId = -1;

    /** 当前虚拟体 bounds（所有收集 section 的覆盖范围，供 createStaticWorldBody）。 */
    private int bodyMinX = 0, bodyMinY = 0, bodyMinZ = 0, bodyMaxX = 0, bodyMaxY = 0, bodyMaxZ = 0;

    /** ★ 2026-09-01 收集是否已 finalize（octree 重建后置 true；未收集=虚拟体未建）。 */
    private boolean finalized = false;
    /** ⚠ 上次 finalize 的 bounds（若本次收集扩展了 bounds → 需重新 finalize 重建 octree）。 */
    private int lastFinMinX = 0, lastFinMinY = 0, lastFinMinZ = 0,
                lastFinMaxX = 0, lastFinMaxY = 0, lastFinMaxZ = 0;

    /** ★ 2026-09-01 收集完成 finalize：虚拟体用【最终】bounds 重建 octree。
     * 幂等：bounds 未变（无新上架 section 扩展范围）则跳过。 */
    public synchronized void finalizeCollection() {
        if (staticWorldBodyId == -1 || !available()) return;
        if (finalized
                && lastFinMinX == bodyMinX && lastFinMinY == bodyMinY && lastFinMinZ == bodyMinZ
                && lastFinMaxX == bodyMaxX && lastFinMaxY == bodyMaxY && lastFinMaxZ == bodyMaxZ) {
            return;   // 无变化
        }
        final int[] fin = {bodyMinX, bodyMinY, bodyMinZ, bodyMaxX, bodyMaxY, bodyMaxZ};
        engine.finalizeStaticWorldBody(staticWorldBodyId, fin);
        lastFinMinX = bodyMinX; lastFinMinY = bodyMinY; lastFinMinZ = bodyMinZ;
        lastFinMaxX = bodyMaxX; lastFinMaxY = bodyMaxY; lastFinMaxZ = bodyMaxZ;
        finalized = true;
    }

    /** ★ 确保虚拟世界体已创建（幂等）：创建时收集期间首次调用。 */
    private void ensureStaticWorldBody() {
        if (staticWorldBodyId != -1) return;
        staticWorldBodyId = com.hdf.cryptand.neoforge.cryptandsable.core.backend.official
                .OfficialRapierEngine.nextStaticWorldBodyId();
        // structurePose=null → 退化远处原点（老方案 B 单例 ground，已非主路径）。
        engine.createStaticWorldBody(staticWorldBodyId,
                new int[]{bodyMinX, bodyMinY, bodyMinZ, bodyMaxX, bodyMaxY, bodyMaxZ}, null);
    }

    /** 虚拟世界体 id（-1=未创建）。 */
    public int staticWorldBodyId() {
        return staticWorldBodyId;
    }

    /**
     * ★ 2026-09-02 收集世界 section：数据【按结构独立缓存】（perBodySections[runtimeId]），
     * 【挂载方式】= cacheBodyId ≥0 时挂结构 body（global=false id=结构）；
     *   cacheBodyId=-1 时挂 global（world octree，ground 跨体碰撞——用户需求的主世界地面）。
     *   缓存归属：cacheBodyId（≥0）；-1 → 不缓存（shared uploadedSections 旧路径）。
     *
     * @param level        服务端世界
     * @param sectionPos   section 坐标（16³）
     * @param cacheBodyId  缓存归属结构 runtimeId（≥0 挂结构；-1 global）
     */
    public void uploadSection(ServerLevel level, SectionPos sectionPos, int cacheBodyId) {
        if (!available() || level == null || sectionPos == null) return;
        // ★ 2026-09-03 直接记录准确坐标（弃 packKey 位域：负坐标/掩码易污染）
        // ★ 2026-09-06 【方块坐标全程主世界】扫描表主世界（读 Level 同坐标；陪体在
        //   materialize 时 far 化生成）——先算质心再 far，避免 double-far/负数解码错误。
        final SectionKey key = new SectionKey(sectionPos.x(), sectionPos.y(), sectionPos.z());
        final boolean isBody = cacheBodyId >= 0;
        // ★ 2026-09-03 放弃增量扫描：不做去重提前 return —— 每次扫描无条件全量遍历
        //   范围内所有 section（数据表/扫描方块全量返回，消除增量漏扫误差）。
        //   Rust addChunk 同 key HashMap insert 覆盖 → 重复上传幂等（无累积）。
        //   （原 perBodySections.containsKey / uploadedSections.contains 去重已移除）

        try {
            // ★ 2026-09-01 越界修复：先查该列已加载（未加载 getChunk/getBlockState 抛异常）
            if (!level.isLoaded(
                    new BlockPos(sectionPos.minBlockX(), level.getMinBuildHeight(),
                            sectionPos.minBlockZ()))) {
                // ★ 2026-09-05 【未加载保护】未加载 → 不加入 scannedThisRound（= 标记该
                //   section"本轮未更新"）→ 缓存旧值保留（storeScannedBlocks 不再整体清空，
                //   只更新本轮扫到的；未加载的保持原状，不因收集时区块未加载而丢失支撑）。
                return;   // 未加载 → 跳过（下次查询再收）
            }

            // ★ 2026-09-05 该 section 已加载 → 标记"本轮已更新区域"（成功读到 chunk 即算）
            scannedThisRound.add(key);

            final LevelChunk chunk = level.getChunk(sectionPos.x(), sectionPos.z());
            if (chunk == null) return;
            // ⚠ MC LevelChunk.getSection(y) 的 y 是【内部 section 索引】（0 起），
            //   不是 section 坐标（SectionPos.y() 可为负）！用官方转换
            //   getSectionIndexFromSectionY(sectionY)（LevelHeightAccessor）。
            final int sectionIndex = level.getSectionIndexFromSectionY(sectionPos.y());
            if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount()) {
                // 越界 section（世界底部以下/顶部以上）→ 空素跳过（无方块）
                if (isBody) sectionCache(cacheBodyId).put(key, new int[4096]);
                else uploadedSections.add(key);
                return;
            }
            final LevelChunkSection section = chunk.getSection(sectionIndex);
            if (section == null || section.hasOnlyAir()) {
                // 全空 section 不上传（Rust 侧默认 empty）
                if (isBody) sectionCache(cacheBodyId).put(key, new int[4096]);
                else uploadedSections.add(key);   // 记空（changeBlock 时忽略）
                return;
            }

            // ★ 2026-09-01 虚拟体 bounds 扩展（创建虚拟体前算好覆盖范围）
            bodyMinX = Math.min(bodyMinX, sectionPos.minBlockX());
            bodyMinY = Math.min(bodyMinY, sectionPos.minBlockY());
            bodyMinZ = Math.min(bodyMinZ, sectionPos.minBlockZ());
            bodyMaxX = Math.max(bodyMaxX, sectionPos.minBlockX() + 15);
            bodyMaxY = Math.max(bodyMaxY, sectionPos.minBlockY() + 15);
            bodyMaxZ = Math.max(bodyMaxZ, sectionPos.minBlockZ() + 15);

            final int[] data = new int[4096];
            boolean any = false;
            final int baseX = sectionPos.minBlockX();
            final int baseY = sectionPos.minBlockY();
            final int baseZ = sectionPos.minBlockZ();

            // ★ 2026-09-05 【性能优化】用 LevelChunkSection 局部坐标直读方块状态
            //   （section.getBlockState(bx,by,bz) 直接走 PalettedContainer，无 BlockPos
            //   分配 + 无 Level 全局查找），超大结构 section 数多时大幅降主线程开销。
            //   仅在通过 inClip 裁剪后才构造世界 BlockPos（供 neighborhood 邻居判定）。
            for (int bx = 0; bx < 16; bx++) {
                for (int bz = 0; bz < 16; bz++) {
                    for (int by = 0; by < 16; by++) {
                        final int wx = baseX + bx, wy = baseY + by, wz = baseZ + bz;
                        // ★ 2026-09-04 扫描裁剪：红框（结构 ± radius）外的方块不记录/不上传
                        //   （黄框/陪体都严格限制在红框内）。
                        if (!inClip(wx, wy, wz)) continue;
                        final BlockState state = section.getBlockState(bx, by, bz);
                        if (state.isAir()) continue;
                        // ★ 2026-09-04 【"停在半空"根因修复】物理化后模拟的【装配器方块】
                        //   （simulated:physics_assembler）仍留在世界且带碰撞形状 → 扫描时把
                        //   它当实心扫进陪体 → 结构落上去被顶在 assembler 顶面悬空停住。
                        //   装配器应作为【物理化装置】被排除（不属于世界静态地形）。
                        if (isAssemblerBlock(state)) continue;
                        final BlockPos pos = new BlockPos(wx, wy, wz);
                        if (!state.getCollisionShape(level, pos).isEmpty()) {
                            final int nv = neighborhood(level, pos);
                            // ★ 2026-09-05 【对齐官方】取消 Interior(4) 降级——
                            //   官方 dispatcher 的 can_ignore_collision 特意忽略 Interior
                            //   （内部格不参与碰撞=正确优化）。原降级为 Corner(3) 是旧
                            //   "兜底补丁"，导致内部格参与碰撞（结构悬空/不稳）。
                            // ★ 2026-09-05 【材质感知】高 16 位 = materialId（BlockState →
                            //   方块物理属性表；compound 路线本不看高 16 位，材质用于合并/重量/力）。
                            final int index = bx + (bz << 4) + (by << 8);
                            final int matId = com.hdf.cryptand.neoforge.cryptandsable.core.material
                                    .BlockPhysicsTable.idOf(state);
                            data[index] = nv | (matId << 16);
                            any = true;
                            // ★ 2026-09-02 记录该结构扫描到的实心方块（打印用）
                            if (isBody) {
                                recordScannedBlock(cacheBodyId, pos.getX(), pos.getY(), pos.getZ());
                            }
                        }
                    }
                }
            }

            if (any) {
                // ★ 2026-09-06 【铁律：主线程只做 Level 交互，JNI 全异步】不再在主线程
                //   直接 addWorldChunk / addChunkToBody（JNI）——只把数据写进 sectionCache
                //   （纯 Java 缓存）；JNI 上传统一由核心（空间任务阶段2 materializeScannedBlocks，
                //   pipelineLock 串行）完成。消除主线程 JNI 与空间任务的并发访问同一 scene。
                //   日志仅记录已扫到的实心 section（nonZero 统计，供调试）。
                CryptandNeoForge.WAF_LOGGER.info(
                        "[CryptandSable] uploadSection CACHED {} -> target {} (nonZero={})",
                        sectionPos, uploadMountBody, countNonZero(data));
            }
            if (isBody) sectionCache(cacheBodyId).put(key, data);
            else uploadedSections.add(key);
        } catch (final Throwable t) {
            // 单 section 收集失败：不阻断（下次查询重试）
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[CryptandSable] uploadSection({}) failed: {}",
                    sectionPos, t.toString());
        }
    }

    /** 统计 data 中非零数（调试）。 */
    private static int countNonZero(int[] data) {
        int n = 0;
        for (final int v : data) {
            if (v != 0) n++;
        }
        return n;
    }

    /**
     * 移除一个世界 section（global=true）。
     *
     * @param level      (unused；仅签名一致性)
     * @param sectionPos section 坐标
     */
    public void removeSection(ServerLevel level, SectionPos sectionPos) {
        if (!available() || sectionPos == null) return;
        final SectionKey key = new SectionKey(sectionPos.x(), sectionPos.y(), sectionPos.z());
        if (!uploadedSections.contains(key)) return;
        engine.removeWorldChunk(sectionPos.x(), sectionPos.y(), sectionPos.z());
        uploadedSections.remove(key);
    }

    /**
     * ★ 2026-09-02 官方方式收集（重写）：以结构包围盒为 plot 中心，水平外扩 radius；
     *  竖直只扫结构附近（minY-4 .. maxY+4，不扫地下 16 格/高空）——防止 2 块结构扫
     *  20 万块（§32×§32×§32 立方体）。挂目标 = 结构 body（uploadTargetBodyId ≥0 →
     *  addChunkToBody；否则 global）。
     */
    public void uploadAroundBounds(ServerLevel level,
                                   int minX, int minY, int minZ,
                                   int maxX, int maxY, int maxZ, int radius) {
        if (!available() || level == null) return;
        if (radius < 0) radius = 0;
        // 水平：结构包围盒外扩 radius（section 粒度，超长结构两端不漏）
        final int padX0 = SectionPos.blockToSectionCoord(minX - radius);
        final int padX1 = SectionPos.blockToSectionCoord(maxX + radius);
        final int padZ0 = SectionPos.blockToSectionCoord(minZ - radius);
        final int padZ1 = SectionPos.blockToSectionCoord(maxZ + radius);
        // ★ 竖直：只扫结构附近（minY-radius .. maxY+radius 格 → section）；
        //   ★ 2026-09-05 边缘外扩（用户定案）：与水平一致用 radius，但上限 16 格
        //   （防大 radius 扫到世界底部/全深度；结构通常几格高）。
        final int vertPad = Math.min(radius, 16);
        final int padY0 = SectionPos.blockToSectionCoord(minY - vertPad);
        final int padY1 = SectionPos.blockToSectionCoord(maxY + vertPad);
        // ★ 2026-09-03 移除 MAX_SECTIONS 采样步进：用户要求"扫描必须全量"——
        //   采样 stepX/stepZ 跳步导致网格状漏扫（截图中黄框稀疏网格）。
        //   未加载区块由 uploadSection 的 level.isLoaded 检查天然跳过（非采样）。
        for (int sx = padX0; sx <= padX1; sx++) {
            for (int sz = padZ0; sz <= padZ1; sz++) {
                for (int sy = padY0; sy <= padY1; sy++) {
                    uploadSection(level, SectionPos.of(sx, sy, sz), uploadTargetBodyId);
                }
            }
        }
    }

    /**
     * ★ 2026-09-01 以锚点为中心收集（圆形；供异步收集循环 SableServerBridge 用）。
     * 收集结构当前世界位置（cx,cy,cz）周围 radius 半径内的世界 section。
     */
    public void uploadAroundAnchor(ServerLevel level, double cx, double cy, double cz, int radius) {
        if (!available() || level == null) return;
        if (radius < 0) radius = 0;
        final double r2 = (double) radius * (double) radius;
        final int minY = (int) Math.floor(cy) - radius;
        final int maxY = (int) Math.floor(cy) + radius;
        final int padX0 = SectionPos.blockToSectionCoord((int) Math.floor(cx) - radius);
        final int padX1 = SectionPos.blockToSectionCoord((int) Math.floor(cx) + radius);
        final int padZ0 = SectionPos.blockToSectionCoord((int) Math.floor(cz) - radius);
        final int padZ1 = SectionPos.blockToSectionCoord((int) Math.floor(cz) + radius);
        final int padY0 = SectionPos.blockToSectionCoord(minY);
        final int padY1 = SectionPos.blockToSectionCoord(maxY);
        for (int sx = padX0; sx <= padX1; sx++) {
            for (int sz = padZ0; sz <= padZ1; sz++) {
                final double secCx = SectionPos.sectionToBlockCoord(sx) + 8;
                final double secCz = SectionPos.sectionToBlockCoord(sz) + 8;
                final double dx = secCx - cx;
                final double dz = secCz - cz;
                if (dx * dx + dz * dz > r2 + 128) continue;   // +128 容忍（section 半宽）
                for (int sy = padY0; sy <= padY1; sy++) {
                    uploadSection(level, SectionPos.of(sx, sy, sz), uploadTargetBodyId);
                }
            }
        }
    }

    /**
     * 结构物理化时上传其周围的世界 section（★ 2026-09-01 改为【圆形区域】：
     * 以结构区域中心为圆心、配置半径 r 内；水平 dx²+dz² ≤ r²；竖直 ±r 内 section）。
     */
    public void uploadAroundBox(ServerLevel level, int minX, int minY, int minZ,
                                int maxX, int maxY, int maxZ) {
        if (!available() || level == null) return;
        // 配置半径（格）；0 则仅结构紧邻
        int radius = ConfigCryptandSable.SABLE_WORLD_COLLISION_RADIUS.get();
        if (radius < 0) radius = 0;
        // 结构区域（格）中心
        final double cx = (minX + maxX + 1) * 0.5;
        final double cy = (minY + maxY + 1) * 0.5;
        final double cz = (minZ + maxZ + 1) * 0.5;
        final double r2 = (double) radius * (double) radius;

        // 竖直范围（±r，section 粒度）
        final int padY0 = SectionPos.blockToSectionCoord(minY - radius);
        final int padY1 = SectionPos.blockToSectionCoord(maxY + radius);
        // 水平边界（半径外扩后 section 粒度）
        final int padX0 = SectionPos.blockToSectionCoord(minX - radius);
        final int padX1 = SectionPos.blockToSectionCoord(maxX + radius);
        final int padZ0 = SectionPos.blockToSectionCoord(minZ - radius);
        final int padZ1 = SectionPos.blockToSectionCoord(maxZ + radius);

        for (int sx = padX0; sx <= padX1; sx++) {
            for (int sz = padZ0; sz <= padZ1; sz++) {
                // 水平圆形判定：以结构中心锚点 → section 中心
                final double secCx = (SectionPos.sectionToBlockCoord(sx) + 8);
                final double secCz = (SectionPos.sectionToBlockCoord(sz) + 8);
                final double dx = secCx - cx;
                final double dz = secCz - cz;
                if (dx * dx + dz * dz > r2 + 128) continue;   // +128 容忍（section 半宽）
                for (int sy = padY0; sy <= padY1; sy++) {
                    uploadSection(level, SectionPos.of(sx, sy, sz), uploadTargetBodyId);
                }
            }
        }
    }

    /**
     * 邻域状态（★ 2026-09-02 移植官方 VoxelNeighborhoodState.getState 算法）：
     *  - 非实心 → EMPTY(0)
     *  - 不满块/液体→ CORNER(3)
     *  - 全 6 邻向【实心+满块】→ INTERIOR(4)（极严格——官方 can_ignore_collision
     *    对 Interior 忽略碰撞，过度使用会导致世界↔结构碰撞被屏蔽——沉底根因！）
     *  - 只有 1 轴双向实心 → EDGE(2)
     *  - 无轴双向（角）→ CORNER(3)；否则 FACE(1)
     */
    private static int neighborhood(ServerLevel level, BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) return 0; // EMPTY
        if (!state.isCollisionShapeFullBlock(level, pos)) return 3; // 不满块 → CORNER
        boolean allSolid = true;
        boolean cornerSolid = true;
        int bothSidesCount = 0;
        for (final Direction.Axis axis : Direction.Axis.VALUES) {
            final BlockPos nPos = pos.relative(Direction.get(Direction.AxisDirection.NEGATIVE, axis));
            final BlockPos pPos = pos.relative(Direction.get(Direction.AxisDirection.POSITIVE, axis));
            final boolean negativeSolid = isSolidFull(level, nPos);
            final boolean positiveSolid = isSolidFull(level, pPos);
            if (!negativeSolid || !positiveSolid) allSolid = false;
            if (negativeSolid && positiveSolid) {
                cornerSolid = false;
                bothSidesCount++;
            }
        }
        if (allSolid) return 4;            // INTERIOR（全向满块实心）
        if (bothSidesCount == 1) return 2; // EDGE（恰一轴双向）
        if (cornerSolid) return 3;         // CORNER（无轴双向）
        return 1;                           // FACE
    }

    /** ★ 2026-09-04 是否为模拟装配器方块（simulated:physics_assembler）——物理化装置，
     * 物理化后仍留在世界带碰撞形状 → 扫描必须排除（防结构被它顶到半空停住）。 */
    private static boolean isAssemblerBlock(final BlockState state) {
        if (state == null) return false;
        final net.minecraft.resources.ResourceLocation rl =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (rl == null) return false;
        final String ns = rl.getNamespace();
        final String path = rl.getPath();
        // simulated 的物理装配器（含未来前缀前缀）
        if (ns.equals("simulated") && path.equals("physics_assembler")) return true;
        return false;
    }

    /** 邻块：实心且满块（官方 isSolid+isFullBlock）。越界/未加载 → false。 */
    private static boolean isSolidFull(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        final BlockState s = level.getBlockState(pos);
        return !s.isAir() && !s.getCollisionShape(level, pos).isEmpty()
                && s.isCollisionShapeFullBlock(level, pos);
    }
}
