package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 环境分层解析器（EnvironmentResolver）。
 *
 * <p>单一职责：给定 "维度 + 世界坐标" 产出该位置的最终 {@link MediaProperties}。
 * 分层优先级（由低到高）：
 * <ol>
 *   <li>维度全局 {@link DimensionEnvConfig}（最底层默认）</li>
 *   <li>群系级 {@link BiomeEnvConfig}（未注册则跳过）</li>
 *   <li>空间区域 {@link EnvironmentRegion}（命中第一个包含该点的区域）</li>
 *   <li>方块级 {@link BlockEnvFlag}（如流体方块即介质，最高优先）</li>
 * </ol>
 * 本类为纯函数式、线程安全（region 列表为 CopyOnWrite），供核心 worker 线程调用；
 * 不触碰 Level 状态，仅依赖已注册的配置快照。
 */
public final class EnvironmentResolver {
    private static final List<EnvironmentRegion> REGIONS = new CopyOnWriteArrayList<>();

    private EnvironmentResolver() {
    }

    /** 注册/注销一个空间区域覆盖。 */
    public static void addRegion(EnvironmentRegion region) {
        REGIONS.add(region);
    }

    public static void removeRegion(EnvironmentRegion region) {
        REGIONS.remove(region);
    }

    public static void clearRegions() {
        REGIONS.clear();
    }

    /**
     * 解析某位置的环境属性（四层合成；未注册兜底维度默认 → 最终主世界）。
     *
     * @param level 服务器世界（用于查维度键+群系；null 则跳过群系层）
     * @param pos   世界坐标
     */
    public static MediaProperties resolve(ServerLevel level, BlockPos pos) {
        if (level == null) {
            return resolveNoBiome(null, pos);
        }
        MediaProperties props = DimensionEnvConfig.get(level.dimension());

        // 群系级覆盖
        Biome biome = level.getBiome(pos).value();
        MediaProperties biomeProps = BiomeEnvConfig.get(biome);
        if (biomeProps != null) {
            props = merge(props, biomeProps);
        }

        // 空间区域覆盖（命中第一个，且按维度键过滤）
        for (EnvironmentRegion region : REGIONS) {
            if (region.appliesTo(level.dimension()) && region.contains(pos)) {
                props = merge(props, region.properties());
                break;
            }
        }

        // 方块级覆盖
        MediaProperties blockProps = BlockEnvFlag.resolveAt(level, pos);
        if (blockProps != null) {
            props = merge(props, blockProps);
        }

        return props;
    }

    /**
     * 轻量解析（不查群系、不查方块）：维度全局 + 空间区域。
     *
     * @param dimension 维度键；null 视为匹配所有区域的 null 维度（全局区域）
     * @param pos       世界坐标
     */
    public static MediaProperties resolveNoBiome(ResourceKey<Level> dimension, BlockPos pos) {
        MediaProperties props = dimension != null
                ? DimensionEnvConfig.get(dimension)
                : MediaProperties.DEFAULT_GROUND;
        for (EnvironmentRegion region : REGIONS) {
            if (region.appliesTo(dimension) && region.contains(pos)) {
                props = merge(props, region.properties());
                break;
            }
        }
        return props;
    }

    /** 属性叠加：override 的非缺省字段覆盖 base。 */
    private static MediaProperties merge(MediaProperties base, MediaProperties override) {
        return base.with(override);
    }
}