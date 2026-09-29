package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Map;

/**
 * 维度全局环境配置（DimensionEnvConfig）。
 *
 * <p>按 {@link ResourceKey}{@code <Level>}（ServerLevel 维度）为键的环境参数定义。
 * 主世界 / 下界 / 末地提供内建默认；其他维度可由其他 mod 通过
 * {@link #register(ResourceKey, MediaProperties)} 注册自定义环境（如 CEE 天空、宇宙维度）。
 *
 * <p>本类是"维度全局"层，位于分层解析的最底层（优先级最低），被群系/区域/方块逐层覆盖。
 */
public final class DimensionEnvConfig {
    private static final Map<ResourceKey<Level>, MediaProperties> DIMENSIONS = new HashMap<>();

    private DimensionEnvConfig() {
    }

    /** 注册/覆盖某维度的全局环境属性。返回自身以便链式调用。 */
    public static MediaProperties register(ResourceKey<Level> key, MediaProperties properties) {
        DIMENSIONS.put(key, properties);
        return properties;
    }

    /** 查询维度全局环境；未注册时根据维度键推断内建默认。 */
    public static MediaProperties get(ResourceKey<Level> key) {
        if (DIMENSIONS.containsKey(key)) {
            return DIMENSIONS.get(key);
        }
        // 主世界 = 陆地；下界 = 高温空气（更大气压、可呼吸弱）；末地 = 低重力空气
        if (key == Level.OVERWORLD) {
            return MediaProperties.DEFAULT_GROUND;
        }
        if (key == Level.NETHER) {
            return new MediaProperties(
                    MediaType.ATMOSPHERE,
                    new Vector3d(0.0, -9.81, 0.0),
                    2.0,
                    0.10,
                    0.001,
                    150000.0,
                    false
            );
        }
        if (key == Level.END) {
            return new MediaProperties(
                    MediaType.ATMOSPHERE,
                    new Vector3d(0.0, -3.2, 0.0),
                    0.5,
                    0.03,
                    0.0002,
                    20000.0,
                    true
            );
        }
        // 未知维度：默认陆地，可被其他 mod 覆盖
        return MediaProperties.DEFAULT_GROUND;
    }

    /** 是否已有显式注册（区别于推断默认）。 */
    public static boolean isRegistered(ResourceKey<Level> key) {
        return DIMENSIONS.containsKey(key);
    }

    /** 清除全部注册（世界卸载/重载时调用）。 */
    public static void clear() {
        DIMENSIONS.clear();
    }
}