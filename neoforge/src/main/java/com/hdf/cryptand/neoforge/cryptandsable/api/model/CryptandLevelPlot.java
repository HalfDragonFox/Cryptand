package com.hdf.cryptand.neoforge.cryptandsable.api.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 核心自有亚层 plot（CryptandLevelPlot）—— 完全独立于官方 sable 的类型。
 *
 * <p>替代官方 {@code LevelPlot/ClientLevelPlot} 的核心自有实现：轻量 chunk map
 * （global ChunkPos → LevelChunk），客户端/服务端共用。渲染从亚层 chunk 读方块需真
 * LevelChunk（物理化时 fillServerPlotChunks / 客户端 fillPlotChunks 写入）。
 *
 * <p>坐标语义（与空壳版一致）：
 * <ul>
 *   <li>plotPos —— 亚层网格原点的【plot 坐标】（单位 = 2^logSize chunk）</li>
 *   <li>toLocal(global) —— 世界 chunk → plot 内局部索引（0..2^logSize-1）</li>
 *   <li>meshChunkPos(local) —— 局部索引 → 亚层网格全局 chunk 坐标（Origin = plotPos &lt;&lt; logSize）</li>
 * </ul>
 */
public class CryptandLevelPlot {

    public final ChunkPos plotPos;
    protected final int logSize;
    private final @NotNull Level level;
    private final @NotNull CryptandSubLevel subLevel;
    private final Map<ChunkPos, LevelChunk> chunks = new ConcurrentHashMap<>();
    @Nullable
    protected CryptandBounds3i localBounds = null;

    public CryptandLevelPlot(final @NotNull Level level, final ChunkPos plotPos,
                             final int logSize, final @NotNull CryptandSubLevel subLevel) {
        this.level = level;
        this.plotPos = plotPos;
        this.logSize = logSize;
        this.subLevel = subLevel;
    }

    public ChunkPos getPlotPos() {
        return this.plotPos;
    }

    public Level getLevel() {
        return this.level;
    }

    public int getLogSize() {
        return this.logSize;
    }

    public CryptandBounds3i getBoundingBox() {
        return this.localBounds != null ? this.localBounds : CryptandBounds3i.EMPTY;
    }

    public void setBoundingBox(final CryptandBounds3i bounds) {
        if (this.localBounds == null) {
            this.localBounds = new CryptandBounds3i(bounds);
        } else {
            this.localBounds.set(bounds);
        }
    }

    public @Nullable LevelChunk getChunk(final ChunkPos pos) {
        return this.chunks.get(pos);
    }

    public void setChunk(final ChunkPos pos, final LevelChunk chunk) {
        if (pos != null && chunk != null) {
            this.chunks.put(pos, chunk);
        }
    }

    public @Nullable LevelChunk newEmptyChunk(final ChunkPos pos) {
        if (pos == null) {
            return null;
        }
        final LevelChunk existing = this.chunks.get(pos);
        if (existing != null) {
            return existing;
        }
        final LevelChunkSection[] sections = new LevelChunkSection[level.getSectionsCount()];
        for (int i = 0; i < sections.length; i++) {
            sections[i] = new LevelChunkSection(
                    this.level.registryAccess().registryOrThrow(Registries.BIOME));
        }
        // LevelChunk 自身坐标 = 亚层网格全局坐标（mesh chunk）；getBlockState 按此索引
        final ChunkPos meshPos = this.meshChunkPos(pos);
        final LevelChunk chunk = new LevelChunk(this.level, meshPos, UpgradeData.EMPTY,
                new LevelChunkTicks<>(), new LevelChunkTicks<>(), 0L, sections, null, null);
        this.chunks.put(pos, chunk);
        return chunk;
    }

    public ChunkPos toLocal(final ChunkPos global) {
        final int s = 1 << this.logSize;
        return new ChunkPos(Math.floorMod(global.x, s), Math.floorMod(global.z, s));
    }

    /** 局部 → 全局（plot 原点 chunk × 2^logSize + local）。 */
    public ChunkPos toGlobal(final ChunkPos local) {
        final int s = 1 << this.logSize;
        return new ChunkPos((this.plotPos.x << this.logSize) + Math.floorMod(local.x, s),
                (this.plotPos.z << this.logSize) + Math.floorMod(local.z, s));
    }

    /** 亚层网格 chunk 坐标（局部索引 → 亚层网格的全局 chunk 坐标；Origin = plotPos << logSize）。 */
    public ChunkPos meshChunkPos(final ChunkPos local) {
        return new ChunkPos((this.plotPos.x << this.logSize) + local.x,
                (this.plotPos.z << this.logSize) + local.z);
    }

    public boolean contains(final double x, final double z) {
        final int logBlockSize = this.logSize + SectionPos.SECTION_BITS;
        return x >= (this.plotPos.x << logBlockSize) && x < ((this.plotPos.x + 1) << logBlockSize)
                && z >= (this.plotPos.z << logBlockSize) && z < ((this.plotPos.z + 1) << logBlockSize);
    }

    public boolean contains(final net.minecraft.world.phys.Vec3 point) {
        return this.contains(point.x(), point.z());
    }

    public boolean contains(final Vector3dc point) {
        return this.contains(point.x(), point.z());
    }

    public Collection<ChunkPos> getLoadedChunkPositions() {
        return Collections.unmodifiableSet(this.chunks.keySet());
    }

    public CryptandSubLevel getSubLevel() {
        return this.subLevel;
    }

    /** 该 plot 的中心块（全局坐标）。 */
    public BlockPos getCenterBlock() {
        final int logBlockSize = this.logSize + SectionPos.SECTION_BITS;
        final int half = 1 << (logBlockSize - 1);
        return new BlockPos((this.plotPos.x << logBlockSize) + half, 0,
                (this.plotPos.z << logBlockSize) + half);
    }

    /** 该 plot 的中心 chunk（全局坐标）。 */
    public ChunkPos getCenterChunk() {
        final int half = 1 << this.logSize;
        return new ChunkPos((this.plotPos.x << this.logSize) + (half / 2),
                (this.plotPos.z << this.logSize) + (half / 2));
    }
}