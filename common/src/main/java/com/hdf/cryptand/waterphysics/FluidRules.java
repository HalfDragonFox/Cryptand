package com.hdf.cryptand.waterphysics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 流体规则表（纯 Java 的数据化规则表）。
 *
 * <p>键是方块/流体的字符串 id（例如 "minecraft:oak_fence"、"minecraft:water"），
 * 由 neoforge 侧翻译层把 Block/Fluid 转成 id —— common 不认识任何 MC 注册表。
 */
public final class FluidRules {

    /** 默认可通过（水可以流进这一格）。 */
    public static final int PASS_DEFAULT = 1;
    /** 永不可通过。 */
    public static final int PASS_NEVER = 0;
    /** 强制可通过（覆盖方块自身的判断）。 */
    public static final int PASS_FORCE = 2;

    private final Map<String, Integer> passable = new HashMap<>();
    private final Set<String> destroyableByFluids = new HashSet<>();

    /** 设置某个方块 id 的可通过性。 */
    public FluidRules setPassable(final String blockId, final int value) {
        passable.put(blockId, value);
        return this;
    }

    /** 标记某方块会被流体冲毁。 */
    public FluidRules setDestroyable(final String blockId) {
        destroyableByFluids.add(blockId);
        return this;
    }

    /** 查询可通过性（未登记 → {@link #PASS_DEFAULT}）。 */
    public int passable(final String blockId) {
        return passable.getOrDefault(blockId, PASS_DEFAULT);
    }

    /** 该方块是否允许水流入。 */
    public boolean isPassable(final String blockId) {
        return passable(blockId) != PASS_NEVER;
    }

    public boolean isDestroyable(final String blockId) {
        return destroyableByFluids.contains(blockId);
    }

    public int size() {
        return passable.size();
    }

    /**
     * 由格子种类给出的默认可通过性（未登记 id 时使用）。
     * 固体默认不可通过；其余默认可通过。
     */
    public static boolean passableByKind(final int kind) {
        return kind != FluidCellKind.SOLID;
    }
}
