package com.hdf.cryptand.neoforge.cryptandsable.api.environment;

import com.hdf.cryptand.neoforge.cryptandsable.core.environment.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;

/**
 * 环境注册专属 API（CryptandSableEnvironmentApi）。
 *
 * <p>供其他 mod 注册【不同世界 / 局部环境 / 群系 / 方块(原版 Tag)】的环境参数。
 * 所有注册项均含<b>默认兜底</b>：未注册 → 默认使用主世界（地球）参数
 * （维度默认 = {@link MediaProperties#DEFAULT_GROUND}）。
 *
 * <p>分层优先级（低→高）：维度全局 → 群系 → 空间区域 → 方块 Tag。
 * 只依赖接口不依赖实现；解析与消费由 core/environment 完成。
 */
public final class CryptandSableEnvironmentApi {
    private CryptandSableEnvironmentApi() {
    }

    // ===== 维度全局层 =====

    /** 注册/覆盖某维度的全局环境属性。未注册维度 → 默认主世界（地球）参数。 */
    public static MediaProperties registerDimension(ResourceKey<Level> dimension, MediaProperties props) {
        return DimensionEnvConfig.register(dimension, props);
    }

    /** 查询维度全局环境（未注册 → 主世界默认）。 */
    public static MediaProperties getDimension(ResourceKey<Level> dimension) {
        return DimensionEnvConfig.get(dimension);
    }

    // ===== 群系层 =====

    /** 注册群系级环境覆盖。未注册群系 → 沿用维度全局。 */
    public static void registerBiome(Biome biome, MediaProperties props) {
        BiomeEnvConfig.register(biome, props);
    }

    /** 注销群系覆盖（回归维度全局）。 */
    public static void unregisterBiome(Biome biome) {
        BiomeEnvConfig.unregister(biome);
    }

    // ===== 空间区域层 =====

    /**
     * 注册一个空间区域覆盖（水域带/太空舱/高辐射区等）。
     *
     * @param dimension 所属维度键；null = 所有维度通用
     * @param minX..maxZ 世界坐标包围盒（端点含）
     */
    public static void registerRegion(ResourceKey<Level> dimension,
                                      int minX, int minY, int minZ,
                                      int maxX, int maxY, int maxZ,
                                      MediaProperties props) {
        EnvironmentResolverBridge.add(new EnvironmentRegion(
                dimension, minX, minY, minZ, maxX, maxY, maxZ, props));
    }

    /** 注销一个区域（同参数匹配）。 */
    public static void unregisterRegion(ResourceKey<Level> dimension,
                                        int minX, int minY, int minZ,
                                        int maxX, int maxY, int maxZ,
                                        MediaProperties props) {
        EnvironmentResolverBridge.remove(new EnvironmentRegion(
                dimension, minX, minY, minZ, maxX, maxY, maxZ, props));
    }

    // ===== 方块层（原版 Tag 批量注册） =====

    /**
     * 注册一个原版 Block Tag → 介质属性（批量省内存）。
     * 例如 {@code BlockTags.WATER} 表示"所有水方块都是水体介质"。
     * 未命中任何 tag → 沿用上层环境。
     */
    public static void registerBlockTag(TagKey<Block> tag, MediaProperties props) {
        BlockEnvFlag.registerTag(tag, props);
    }

    /** 注销方块 Tag 覆盖。 */
    public static void unregisterBlockTag(TagKey<Block> tag) {
        BlockEnvFlag.unregisterTag(tag);
    }

    // ===== 查询 =====

    /** 解析某位置最终环境（四层合成，未注册兜底主世界默认）。 */
    public static MediaProperties resolve(ServerLevel level, BlockPos pos) {
        return EnvironmentResolver.resolve(level, pos);
    }

    /** 内部桥：转发区域注册到 core 解析器。 */
    private static final class EnvironmentResolverBridge {
        private static void add(EnvironmentRegion r) {
            EnvironmentResolver.addRegion(r);
        }

        private static void remove(EnvironmentRegion r) {
            EnvironmentResolver.removeRegion(r);
        }
    }
}