package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 环境空间区域（EnvironmentRegion）。
 *
 * <p>在某维度内划定的空间包围盒，用于覆盖该区域的介质属性（如水域带、太空舱、雾区、
 * 高辐射区等）。优先级高于群系/维度全局，低于方块级。
 *
 * <p>按 {@link ResourceKey}{@code <Level>} 维度键绑定（多世界各自区域隔离）。
 */
public final class EnvironmentRegion {
    /** 所属维度键；null 表示适用于【所有维度】（全局区域）。 */
    private final ResourceKey<Level> dimension;
    private final int minX, minY, minZ;
    private final int maxX, maxY, maxZ;
    private final MediaProperties properties;

    public EnvironmentRegion(ResourceKey<Level> dimension, int minX, int minY, int minZ,
                             int maxX, int maxY, int maxZ, MediaProperties properties) {
        this.dimension = dimension;
        this.minX = Math.min(minX, maxX);
        this.minY = Math.min(minY, maxY);
        this.minZ = Math.min(minZ, maxZ);
        this.maxX = Math.max(minX, maxX);
        this.maxY = Math.max(minY, maxY);
        this.maxZ = Math.max(minZ, maxZ);
        this.properties = properties;
    }

    /** 所属维度键；null = 全局区域（所有维度都覆盖）。 */
    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** 该区域是否适用于给定维度（null 维度键匹配一切）。 */
    public boolean appliesTo(ResourceKey<Level> dim) {
        return dimension == null || dimension.equals(dim);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    public MediaProperties properties() {
        return properties;
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }
}