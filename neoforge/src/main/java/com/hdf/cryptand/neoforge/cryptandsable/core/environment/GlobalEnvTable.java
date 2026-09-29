package com.hdf.cryptand.neoforge.cryptandsable.core.environment;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局固有环境表（GlobalEnvTable）—— 环境数据唯一权威存储（2026-09-01 V2 架构）。
 *
 * <p><b>环境数据为全局固有数据</b>：主世界/下界/末地/太空/水域带/雾区/气密室等，
 * 每项为 {@link MediaProperties}（重力矢量/介质密度/气压/粘滞阻力/氧气/辐射）。
 * 物理结构只存 {@link EnvId} 索引；计算时查表改变结构参数。
 *
 * <p><b>更新即生效</b>：环境表更新 → 所有引用该 EnvId 的结构立即按新参数表现
 * （改环境=改效果），无需逐结构更新。
 *
 * <p><b>纯固有数据</b>：表条目不可变（写入后替换整条目）；线程安全（并发读）；
 * 退出世界不丢弃（全局固有），但 EnvId 解析缓存（按结构）在进入/退出时刷新。
 */
public final class GlobalEnvTable {

    private static final GlobalEnvTable INSTANCE = new GlobalEnvTable();

    /** 条目列表（index = EnvId；条目不可变）。 */
    private final List<MediaProperties> entries = new ArrayList<>();

    /** 环境键（维度/区域名称）→ 条目索引映射（命名查找）。 */
    private final Map<String, Integer> indexByName = new ConcurrentHashMap<>();

    private GlobalEnvTable() {
        // 内建默认（[0] OVERWORLD_GROUND 起，顺序与 EnvId 常量一致）
        this.register("overworld", MediaProperties.DEFAULT_GROUND);
        this.register("ocean_water", MediaProperties.DEFAULT_WATER);
        this.register("nether", new MediaProperties(
                MediaType.GROUND, new Vector3d(0.0, -9.81, 0.0),
                2.0, 0.20, 0.002, 101325.0, false));
        this.register("end", new MediaProperties(
                MediaType.SPACE, new Vector3d(0.0, -3.0, 0.0),
                0.2, 0.02, 0.0002, 10000.0, true));
        this.register("space", MediaProperties.DEFAULT_SPACE);
        this.register("high_atmosphere", MediaProperties.DEFAULT_ATMOSPHERE);
    }

    public static GlobalEnvTable instance() {
        return INSTANCE;
    }

    /** 条目总数（EnvId 上限）。 */
    public synchronized int size() {
        return this.entries.size();
    }

    /** 按 EnvId 取环境参数（无效返回 null）。 */
    public synchronized MediaProperties get(final int envId) {
        if (envId < 0 || envId >= this.entries.size()) return null;
        return this.entries.get(envId);
    }

    /** 按名称（维度/区域）取 EnvId；未注册返回 -1。 */
    public synchronized int find(final String name) {
        Integer idx = this.indexByName.get(name);
        return idx != null ? idx : -1;
    }

    /** 注册/更新条目（name = 维度/区域键；返回 EnvId）。 */
    public synchronized int register(final String name, final MediaProperties props) {
        Integer existing = this.indexByName.get(name);
        if (existing != null) {
            this.entries.set(existing, props); // 覆盖（更新即生效）
            return existing;
        }
        int id = this.entries.size();
        this.entries.add(props);
        this.indexByName.put(name, id);
        return id;
    }

    /** 按维度键查表（dimension 资源键 → EnvId；无则返回默认）。 */
    public int resolveDimension(final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension) {
        if (dimension == null) return EnvId.DEFAULT;
        String key = dimension.location().toString();
        int id = this.find(key);
        if (id >= 0) return id;
        // 内建映射
        if (dimension == net.minecraft.world.level.Level.NETHER) return EnvId.NETHER;
        if (dimension == net.minecraft.world.level.Level.END) return EnvId.END;
        return EnvId.DEFAULT;
    }
}
