package com.hdf.cryptand.neoforge.cryptandsable.api.model;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 核心自有亚层（CryptandSubLevel）—— 完全独立于官方 sable 的数据模型。
 *
 * <p>替代官方 {@code dev.ryanhcode.sable.sublevel.SubLevel/ServerSubLevel/ClientSubLevel}
 * （核心物理引擎零官方依赖）。核心只管【自己的数据面】：
 * <ul>
 *   <li>{@link #uniqueId} —— 全局亚层 id（world/重启稳定，持久化主键）</li>
 *   <li>{@link #runtimeId} —— 核心物理体 runtimeId（位姿快照/渲染跟随索引）</li>
 *   <li>{@link #anchor} —— 结构锚点（首块/几何中心，渲染与物理位姿同基准）</li>
 *   <li>{@link #plot} —— 亚层 plot（方块 chunk 缓冲，渲染/碰撞读）</li>
 * </ul>
 *
 * <p>工厂：{@link #create(ServerLevel, BlockPos, ChunkPos, int)} 创建服务端亚层
 * （不依赖任何官方容器/官方类——等价于官方 createWithAnchor 的自有实现）。
 */
public class CryptandSubLevel {

    private final UUID uniqueId;
    private int runtimeId;
    private final BlockPos anchor;
    @Nullable
    protected CryptandLevelPlot plot;
    protected final Level level;

    public CryptandSubLevel(final Level level, final BlockPos anchor) {
        this.level = level;
        this.uniqueId = UUID.randomUUID();
        this.anchor = anchor;
        this.runtimeId = 0;
    }

    /**
     * 服务端亚层工厂（等价官方 createWithAnchor 的自有实现）。
     *
     * @param level   服务端世界
     * @param anchor  结构锚点（世界坐标；渲染/物理位姿共用基准）
     * @param plotPos plot 原点 chunk（亚层网格网格原点；= anchor chunk >> logSize）
     * @param logSize plot 边长 log2（方块缓冲网格大小）
     * @return 新亚层（已带空 plot）
     */
    public static @NotNull CryptandServerSubLevel create(final ServerLevel level,
                                                         final BlockPos anchor,
                                                         final ChunkPos plotPos,
                                                         final int logSize) {
        final CryptandServerSubLevel sub = new CryptandServerSubLevel(level, anchor);
        final CryptandLevelPlot plot = new CryptandLevelPlot(level, plotPos, logSize, sub);
        sub.plot = plot;
        return sub;
    }

    public UUID getUniqueId() {
        return this.uniqueId;
    }

    public int getRuntimeId() {
        return this.runtimeId;
    }

    public CryptandSubLevel setRuntimeId(final int runtimeId) {
        this.runtimeId = runtimeId;
        return this;
    }

    /** 结构锚点（渲染 anchor = 结构首块；物理投影基准 = 几何中心，见 PROJECTION_ANCHOR）。 */
    public BlockPos getAnchor() {
        return this.anchor;
    }

    /** 亚层所在世界（服务端 = ServerLevel；客户端 = ClientLevel）。 */
    public Level getLevel() {
        return this.level;
    }

    @Nullable
    public CryptandLevelPlot getPlot() {
        return this.plot;
    }

    public CryptandSubLevel setPlot(@Nullable final CryptandLevelPlot plot) {
        this.plot = plot;
        return this;
    }

    /** 位姿位置（JOML 写引用；渲染/交互读）。自有时刻以 {@code anchor} 为静态基准。 */
    public org.joml.Vector3d logicalPose() {
        final org.joml.Vector3d v = new org.joml.Vector3d();
        if (this.anchor != null) {
            v.set((this.anchor.getX() & ~31) + 0.5, this.anchor.getY() + 0.5, (this.anchor.getZ() & ~31) + 0.5);
        }
        return v;
    }

    @Override
    public String toString() {
        return "CryptandSubLevel{" + this.uniqueId + ", runtime=" + this.runtimeId
                + ", anchor=" + this.anchor + '}';
    }
}