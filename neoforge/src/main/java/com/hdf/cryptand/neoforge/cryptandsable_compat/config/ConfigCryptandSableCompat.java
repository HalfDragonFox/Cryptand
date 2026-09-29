package com.hdf.cryptand.neoforge.cryptandsable_compat.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * CryptandSable 兼容子包配置门面（2026-09-02 从 ConfigSable 拆出：/cryptandsable_compat 兼容）。
 *
 * <p>对应代码子包 {@code com.hdf.cryptand.neoforge.cryptandsable_compat}（官方 Sable 兼容
 * 转接）。统一读写 {@code config/cryptand/cryptand-sable-compat.toml}——文件路径由
 * {@link ConfigLoad} 总管理（配置域 "cryptandsable-compat"），本类只提供按 key 的读取 API，
 * 供类加载阶段的 mixin/plugin 与运行期使用（ModConfigSpec 未 build 时也能读）。
 *
 * <p>语义化常量（spec 已加载）走 {@link ConfigLoad}（保留存量引用兼容）；
 * 新代码/类加载阶段推荐用本类的 {@link #bool}/{@link #integer}/{@link #real}/{@link #string}。
 */
public final class ConfigCryptandSableCompat
        implements CryptandConfigSpec {

    /** 配置域 id（ConfigLoad.domainFileName 映射到 cryptand-sable-compat.toml）。 */
    public static final String DOMAIN = "cryptandsable-compat";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigCryptandSableCompat INSTANCE = new ConfigCryptandSableCompat();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "cryptand-sable-compat.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    /** 子包自注册：把本配置注册到 core 注册器（core 只提供注册接口，不收集）。 */
    public static void register() {
        CryptandRegistries
                .registerConfig(DOMAIN, "cryptand-sable-compat.toml", SPEC);
    }

    /** 本域字段构建器（build 前定义，见 ConfigLoad 2026-08-15 崩溃教训：build 必须放全部字段之后）。 */
    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    // =====================================================================
    //  CryptandSable 兼容配置（cryptand/cryptand-sable-compat.toml，2026-09-01）
    // =====================================================================

    /**
     * 原版 Sable 兼容开关（2026-09-01 架构：CryptandSable 独立核心 + 兼容子包转接）。
     * <p>
     * 官方 Sable 作为惰性 mod 被 FML 加载（类进 ModuleLayer、@Mod 构造被 ASM 清空
     * 不初始化）。其他 mod（Create: Aeronautics / Simulated / CEE 等）的 mixin 与
     * 运行时可能强转 Entity 等到官方 mixinterface（EntityMovementExtension /
     * SubLevelContainerHolder 等）——官方 mixins 已 ban，须由兼容子包
     * {@code cryptandsable_compat.mixin.sable} 注入这些官方接口并把调用转接到
     * CryptandSable 自有 API，否则 ClassCastException 崩溃。
     * <p>
     * true  → 注入官方混入接口并转接到 CryptandSable API（推荐；装官方 sable 必备）
     * false → 不注入（仅当运行环境确定无其他 mod 强转官方接口时使用）
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_COMPAT = CK
            .comment("原版Sable兼容(注入官方mixinterface并转接到CryptandSable API)",
                     "true: 兼容子包注入EntityMovementExtension/SubLevelContainerHolder",
                     "      等官方接口,强转不崩,调用转接CryptandSable核心(推荐,装官方",
                     "      sable必备)",
                     "false: 不注入官方接口(仅无其他mod强转官方接口时用)",
                     "需要重启游戏生效")
            .define("enableSableCompat", true);

    private ConfigCryptandSableCompat() {
    }

    /** 本域 Spec（ConfigLoad 聚合注册）。 */
    public static final ModConfigSpec SPEC = CK.build();
}