package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 方块级环境标记（BlockEnvFlag）。
 *
 * <p>最高优先级的覆盖层：某些方块/流体本身就是介质（水、熔岩、气态方块"fluid for buoyancy"），
 * 使该位置的介质属性由方块决定。扩展官方 level.rs:87 "should be treated as a fluid for buoyancy"
 * 的方块级语义。
 *
 * <p><b>注册只用【原版 Tag】批量方式</b>（2026-08-31 用户定稿）：{@link #registerTag(TagKey, MediaProperties)}
 * 一个 tag 一条 entry 覆盖整个 tag 集（如 {@code BlockTags.WATER} = 所有水方块都是水体介质），
 * 省内存、天然随原版 tag 更新。解析用 {@code BlockState.is(tag)}（内部 registry frozen hash set）。
 * 多属性重叠时【最后注册的 Tag】优先。全部未命中 → null（上层默认兜底主世界参数）。
 */
public final class BlockEnvFlag {
    /** TagKey → 介质属性；按注册顺序，后者优先。 */
    private static final List<Map.Entry<TagKey<Block>, MediaProperties>> TAG_REGISTRY = new ArrayList<>();

    private BlockEnvFlag() {
    }

    /**
     * 注册一个原版 Block Tag → 介质属性覆盖。
     * 例如 {@code BlockTags.WATER}（水方块）、{@code BlockTags.REPLACEABLE} 等。
     */
    public static void registerTag(TagKey<Block> tag, MediaProperties props) {
        if (tag != null && props != null) {
            synchronized (TAG_REGISTRY) {
                for (int i = 0; i < TAG_REGISTRY.size(); i++) {
                    if (TAG_REGISTRY.get(i).getKey().equals(tag)) {
                        TAG_REGISTRY.set(i, Map.entry(tag, props)); // 覆盖
                        return;
                    }
                }
                TAG_REGISTRY.add(Map.entry(tag, props));
            }
        }
    }

    /** 注销某 Tag 的覆盖（回归上层默认）。 */
    public static void unregisterTag(TagKey<Block> tag) {
        synchronized (TAG_REGISTRY) {
            TAG_REGISTRY.removeIf(e -> e.getKey().equals(tag));
        }
    }

    /** 是否已有该 Tag 的覆盖。 */
    public static boolean isTagRegistered(TagKey<Block> tag) {
        synchronized (TAG_REGISTRY) {
            for (Map.Entry<TagKey<Block>, MediaProperties> e : TAG_REGISTRY) {
                if (e.getKey().equals(tag)) return true;
            }
        }
        return false;
    }

    /** 清空（世界卸载/重载时重置为默认）。 */
    public static void clear() {
        synchronized (TAG_REGISTRY) {
            TAG_REGISTRY.clear();
        }
    }

    /**
     * 解析：遍历原版 Tag，取最后命中的介质属性；全部未命中 → null（上层默认）。
     * 无 Level 时返回 null（跳过该层）。
     */
    public static MediaProperties resolveAt(BlockGetter level, BlockPos pos) {
        if (level == null) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        MediaProperties matched = null;
        synchronized (TAG_REGISTRY) {
            for (Map.Entry<TagKey<Block>, MediaProperties> e : TAG_REGISTRY) {
                if (state.is(e.getKey())) {
                    matched = e.getValue(); // 后注册的 Tag 覆盖先前的
                }
            }
        }
        return matched;
    }
}