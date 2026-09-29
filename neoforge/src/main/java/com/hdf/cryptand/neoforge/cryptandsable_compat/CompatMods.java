package com.hdf.cryptand.neoforge.cryptandsable_compat;

import net.neoforged.fml.ModList;

/**
 * 相关第三方 mod 名称（CompatMods）—— cryptandsable_compat 兼容层面向的 mod 集合。
 *
 * <p>这些 mod 会按其自身编译视角调用 sable 接口；兼容层需针对每个 mod 建立对应 mixin /
 * 拦截，把这些调用桥进 CryptandSable 语义面，避免 ClassCastException / NoSuchMethodError。
 *
 * <p>判定运行是否加载：{@link #isLoaded(String)}（ModList）。未加载对应 mod 时，
 * 相关兼容 mixin 通过 minVersion/条件跳过，不影响其它 mod。
 */
public final class CompatMods {

    // ===== 航空学 / 物理子层生态 =====
    /** Create Aeronautics（航空学主体）。 */
    public static final String AERONAUTICS = "aeronautics";
    /** Create Simulated（装配器/弹簧/绳索 等）。 */
    public static final String SIMULATED = "simulated";
    /** Create Offroad（矿石钻头等）。 */
    public static final String OFFROAD = "offroad";
    /** Sable Schematic API（蓝图兼容，mod id: sable_schematic_api）。 */
    public static final String SABLE_SCHEMATIC_API = "sable_schematic_api";

    // ===== 官方 sable 生态配套 =====
    /** Sable Companion（API 面，通常由 CEE/aeronautics 内嵌）。 */
    public static final String SABLE_COMPANION = "sablecompanion";
    /** Veil（渲染/网络，被官方 sable jarJar 内嵌，编译期转接包）。 */
    public static final String VEIL = "veil";

    // ===== 其他重依赖 mod（可能以 sable 视角调用） =====
    /** Create Electro-Energetics（CEE）。 */
    public static final String CEE = "electroenergetics";
    /** Create（底座）。 */
    public static final String CREATE = "create";
    /** Create Power Grid（多线程电网）。 */
    public static final String POWER_GRID = "powergrid";

    private CompatMods() {
    }

    /** 某 mod 是否在当前环境加载。 */
    public static boolean isLoaded(final String modId) {
        try {
            return ModList.get() != null && ModList.get().isLoaded(modId);
        } catch (final Throwable t) {
            return false;
        }
    }

    /** AERONAUTICS 是否加载。 */
    public static boolean isAeronautics() {
        return isLoaded(AERONAUTICS);
    }

    /** SIMULATED 是否加载。 */
    public static boolean isSimulated() {
        return isLoaded(SIMULATED);
    }

    /** OFFROAD 是否加载。 */
    public static boolean isOffroad() {
        return isLoaded(OFFROAD);
    }

    /** SABLE_SCHEMATIC_API 是否加载。 */
    public static boolean isSableSchematicApi() {
        return isLoaded(SABLE_SCHEMATIC_API);
    }
}