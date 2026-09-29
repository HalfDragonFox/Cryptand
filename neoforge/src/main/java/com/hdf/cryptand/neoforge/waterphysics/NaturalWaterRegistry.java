package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.waterphysics.NaturalWaterScanner;
import com.hdf.cryptand.waterphysics.NaturalWaterSources;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>自然水源的 MC 翻译层</b> —— 把一个 chunk 翻译成 common 的「列命中表 + 水源探针」，
 * 交给 {@link NaturalWaterScanner#register} 做登记决策（纯 Java 的判据全在 common）。
 *
 * <p>只在 {@code ChunkEvent.Load}（区块首次被看到）时调用一次；不参与每 tick 热路径。
 *
 * <p>两个 MC 事实在 {@code ChunkEvent.Load} 上已经核实（javap 反编译 1.21.1 +
 * NeoForge 21.1.231 的 {@code ChunkStatusTasks.method_60553}）：
 * <ul>
 *   <li>事件在主线程抛（{@code full} 阶段用 {@code mainThreadMailBox} 执行）；</li>
 *   <li>抛出时 {@code LevelChunk} 已经由 {@code ProtoChunk} 构造完成
 *       （{@code runPostLoad()} / {@code setLoaded(true)} 都跑过了），因此
 *       <b>世界生成（含 FEATURES 阶段的海洋/河流水体）已经写进方块状态</b> ——
 *       此刻扫到的水就是「世界生成时就在」的水。</li>
 * </ul>
 *
 * <p>群系判定按 <b>(x,z) 列</b>做（一次 {@code getBiome} 一列，共 256 次/chunk），
 * 判定高度用海平面：水系群系在地表那一层就是海洋/河流。绝不逐格查群系。
 */
public final class NaturalWaterRegistry {

    /** 调色板级过滤：这一 section 里有没有「流体方块」（没有就不必逐格扫，纯石头段特别多）。 */
    private static final Predicate<BlockState> MAY_HOLD_FLUID =
            state -> state.getBlock() instanceof LiquidBlock;

    private NaturalWaterRegistry() {
    }

    /**
     * 首次登记这个 chunk 里所有「群系命中且还没看过」的 section。
     *
     * @param biomeIds 配置里的群系白名单（资源名，如 {@code minecraft:ocean}）
     * @return true = 至少登记了一个 section（调用方据此把 SavedData 标脏）
     */
    public static boolean registerChunk(final ServerLevel level, final LevelChunk chunk,
                                        final NaturalWaterSources sources,
                                        final List<? extends String> biomeIds) {
        final Set<String> wanted = wantedBiomes(biomeIds);
        if (wanted.isEmpty()) {
            return false;   // 白名单为空 = 不登记任何自然水源
        }
        final byte[] columnHit = new byte[NaturalWaterScanner.COLUMNS];
        final ChunkPos chunkPos = chunk.getPos();
        final int originX = chunkPos.getMinBlockX();
        final int originZ = chunkPos.getMinBlockZ();
        // 判定高度取海平面（下界/末地这类没海水世界的值同样合法，只是列表里的群系不会命中）
        final int biomeY = Math.max(level.getMinBuildHeight(),
                Math.min(level.getMaxBuildHeight() - 1, level.getSeaLevel()));
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean anyHit = false;
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                pos.set(originX + localX, biomeY, originZ + localZ);
                final byte hit = wanted.contains(biomeId(level, pos))
                        ? NaturalWaterScanner.COLUMN_HIT
                        : NaturalWaterScanner.COLUMN_MISS;
                columnHit[NaturalWaterScanner.columnIndex(localX, localZ)] = hit;
                anyHit |= hit == NaturalWaterScanner.COLUMN_HIT;
            }
        }
        if (!anyHit) {
            // 整个 chunk 的群系都不在列表里：一条都不登记（不标脏、不占存档体积）
            return false;
        }

        boolean registered = false;
        for (int sectionY = chunk.getMinSection(); sectionY <= chunk.getMaxSection(); sectionY++) {
            final long sectionKey = SectionCursor.key(chunkPos.x, sectionY, chunkPos.z);
            if (sources.isRegistered(sectionKey)) {
                continue;   // ★ 已经看过这个 section：之后（玩家/其它 mod）放的水一律不补登记
            }
            final LevelChunkSection section =
                    chunk.getSection(chunk.getSectionIndexFromSectionY(sectionY));
            if (section == null || section.hasOnlyAir() || !section.maybeHas(MAY_HOLD_FLUID)) {
                // 空段（或整段没有任何流体方块）：没有水源格，但只要群系命中仍然要记住「看过了」——
                // 否则玩家之后在这里放的水又会被当成生成时就在的（高空海洋 section 就是这种）。
                // ★ maybeHas 是调色板级判断（常数时间）：把大量纯石头 section 的 4096 次逐格读省掉。
                if (NaturalWaterScanner.register(sources, sectionKey, columnHit, (lx, ly, lz) -> false)) {
                    registered = true;
                }
                continue;
            }
            if (NaturalWaterScanner.register(sources, sectionKey, columnHit,
                    (lx, ly, lz) -> isSourceWater(section.getBlockState(lx, ly, lz)))) {
                registered = true;
            }
        }
        return registered;
    }

    /** 这一格是不是「水源方块」（水方块本身且 {@code LEVEL == 0}）—— waterlogged 方块不算。 */
    private static boolean isSourceWater(final BlockState state) {
        if (!(state.getBlock() instanceof LiquidBlock)) {
            return false;
        }
        final FluidState fluid = state.getFluidState();
        return fluid.is(FluidTags.WATER) && fluid.isSource();
    }

    /** 该坐标的群系资源名（无 key 时返回空串，一律不命中）。 */
    private static String biomeId(final LevelReader level, final BlockPos pos) {
        return level.getBiome(pos).unwrapKey().map(key -> key.location().toString()).orElse("");
    }

    // 白名单集合缓存：配置值只在内容变了才重建（每 chunk 一次 get() 的代价也一并省掉）
    private static List<String> cachedBiomeIds = List.of();
    private static Set<String> cachedWanted = Set.of();

    private static Set<String> wantedBiomes(final List<? extends String> biomeIds) {
        if (!biomeIds.equals(cachedBiomeIds)) {
            final Set<String> set = new HashSet<>();
            for (final String id : biomeIds) {
                if (id != null && !id.isBlank()) {
                    set.add(id.trim());
                }
            }
            cachedBiomeIds = List.copyOf(biomeIds);
            cachedWanted = set;
        }
        return cachedWanted;
    }
}
