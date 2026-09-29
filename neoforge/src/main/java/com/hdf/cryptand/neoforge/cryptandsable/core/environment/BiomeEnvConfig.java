package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

import net.minecraft.world.level.biome.Biome;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群系级环境配置（BiomeEnvConfig）。
 *
 * <p>按 {@link Biome} 为键的环境覆盖项，位于分层解析的"群系层"。
 * 由 mod 注册（如海洋群系=浓水介质、末地群系=真空/低重力、沙漠=低含水）。
 * 未注册的群系返回 null（跳过该层）。
 */
public final class BiomeEnvConfig {
    /** identity-map：用 Holder/builtIn 注册的 Biome 引用做 key。 */
    private static final Map<Biome, MediaProperties> REGISTRY = new ConcurrentHashMap<>();

    private BiomeEnvConfig() {
    }

    /** 注册群系级环境覆盖。 */
    public static void register(Biome biome, MediaProperties props) {
        REGISTRY.put(biome, props);
    }

    /** 查询群系级覆盖；未注册返回 null。 */
    public static MediaProperties get(Biome biome) {
        return REGISTRY.get(biome);
    }

    /** 注销。 */
    public static void unregister(Biome biome) {
        REGISTRY.remove(biome);
    }

    public static void clear() {
        REGISTRY.clear();
    }
}