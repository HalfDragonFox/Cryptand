package com.hdf.cryptand.neoforge.cryptandsable.api.model;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/**
 * 核心自有服务端亚层（CryptandServerSubLevel）—— 与官方 {@code ServerSubLevel} 语义平行。
 *
 * <p>服务端专用：含 plot（方块 chunk 缓冲）+ 核心物理体 runtimeId。见 {@link CryptandSubLevel#create}。
 */
public class CryptandServerSubLevel extends CryptandSubLevel {

    /**
     * 亚层网格原点偏移（plot 网格索引偏移，2026-09-01 用户:"实际方块进入亚层应该
     * 实际位置在世界极远处，和sable一致"）。
     *
     * <p>官方 sable 的亚层 plot 网格使用【大偏移坐标】（plotPos 远离主世界可加载范围），
     * 使亚层 mesh chunk（实际方块写入的 slice）与主世界真实区块【物理隔离】——
     * 主世界 LevelRenderer/碰撞永远不会当作真实区块处理。我们自本次起同样使用
     * 固定大偏移：plot 网格原点 = PLOT_ORIGIN << (logSize+SECTION_BITS)。
     *
     * <p>取值：7 级 log（官方 DEFAULT_LOG_SIZE_LENGTH=7）→ 单 plot=2^(7+4)=2048 块；
     * 原点偏移 30000000 块级（世界边界之外 → "极远处"），plot 网格索引 ≈ 14648。
     */
    public static final int SUB_LEVEL_PLOT_ORIGIN = 1 << 20;

    public CryptandServerSubLevel(final ServerLevel level, final BlockPos anchor) {
        super(level, anchor);
    }

    public ServerLevel serverLevel() {
        return this.level instanceof final ServerLevel sl ? sl : null;
    }

    /**
     * bounds 感知工厂（等价空壳版 {@code ServerSubLevel.createWithAnchor} 的自有实现）：
     * 按结构包围盒计算 plot 尺寸/位置，保证 {@link CryptandLevelPlot#contains} 覆盖整个装配结构。
     *
     * <p>官方 contains 语义：{@code logBlockSize = logSize + SECTION_BITS}，plotPos 为
     * “单位块宽 = 1<<logBlockSize 的 grid 索引”，命中范围 {@code [plotPos.x<<logBlockSize, ...)}。
     *
     * <p>★ 2026-09-01 极远处域：plot 网格坐标加 {@link #SUB_LEVEL_PLOT_ORIGIN} 大偏移
     * （与主世界真实区块隔离；亚层 chunk 坐标在 30000km 之外）。
     *
     * @param level  服务端世界
     * @param anchor 结构锚点（渲染/物理位姿共用基准）
     * @param bounds 结构包围盒（block 坐标）；null → 退化锚点单块 plot
     * @return 新服务端亚层（已带空 plot）
     */
    public static CryptandServerSubLevel createWithAnchor(final ServerLevel level,
                                                          @Nullable final CryptandBounds3i bounds,
                                                          final BlockPos anchor) {
        final CryptandServerSubLevel subLevel = new CryptandServerSubLevel(level, anchor);
        if (bounds == null || bounds.minX() > bounds.maxX() || bounds.minZ() > bounds.maxZ()) {
            final ChunkPos chunk = new ChunkPos(anchor);
            subLevel.setPlot(new CryptandLevelPlot(level, chunk, 0, subLevel));
            return subLevel;
        }
        final long minX = bounds.minX(), maxX = bounds.maxX();
        final long minZ = bounds.minZ(), maxZ = bounds.maxZ();
        final long spanX = Math.max(1, maxX - minX + 1);
        final long spanZ = Math.max(1, maxZ - minZ + 1);
        int logSize = 0;
        while ((1L << (logSize + SectionPos.SECTION_BITS)) < Math.max(spanX, spanZ)) {
            logSize++;
        }
        final long unit = 1L << (logSize + SectionPos.SECTION_BITS);
        final int plotX = (int) Math.floorDiv(minX, unit) + SUB_LEVEL_PLOT_ORIGIN;
        final int plotZ = (int) Math.floorDiv(minZ, unit) + SUB_LEVEL_PLOT_ORIGIN;
        subLevel.setPlot(new CryptandLevelPlot(level, new ChunkPos(plotX, plotZ), logSize, subLevel));
        return subLevel;
    }
}