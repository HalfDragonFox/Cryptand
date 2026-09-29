package com.hdf.cryptand.neoforge.core.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigCore —— 核心/通用（modid 通用）配置（2026-09-02 按子包拆分）。
 * <p>持有本域全部配置字段定义（ModConfigSpec）与 SPEC；文件路径由 ConfigLoad 总管理
 * （域 DOMAIN="core"）。读取门面 bool/integer/real/string → ConfigLoad.readXxx(DOMAIN, key, def)。
 */
public final class ConfigCore
        implements CryptandConfigSpec {

    /** 配置域 id（ConfigLoad.domainFileName 映射）。 */
    public static final String DOMAIN = "core";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigCore INSTANCE = new ConfigCore();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "common.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    /** 子包自注册：把本配置注册到 core 注册器（core 只提供注册接口，不收集）。 */
    public static void register() {
        CryptandRegistries
                .registerConfig(DOMAIN, "common.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue CONFIG_VERSIONS = CK
            .comment("配置文件版本")
            .defineInRange("configVersions", 1, 1, Integer.MAX_VALUE);

    /**
     * 子包/mixin 注册诊断（core 注册能力自检——2026-08-30 由 debug 域迁入本域）：
     * config 加载后 core 打印一次已注册子包列表（含启用状态）与各 mixin 面
     * 注册/注入状况。
     */
    public static final ModConfigSpec.BooleanValue DEBUG_SUBPACKAGE_DUMP = CK
            .comment("子包注册诊断",
                     "true: config加载后core打印一次已注册子包列表(含启用状态)",
                     "     与各mixin面注册/注入状况(recordMixin决策表)",
                     "false: 关闭(默认), 不打印")
            .define("debugSubpackageDump", false);

    /** 本域 Spec（ConfigLoad 聚合注册）。
     *  ⚠ 2026-08-30 用户架构：core 仅保留注册 lib 功能——原 core 域的
     *  enableGameInput（→ gameinput 子包 gameinput.toml）与 enableSimServer
     *  （→ simserver 子包 simserver.toml）已迁出；本域仅保留 configVersions
     *  （配置版本标记，注册 lib 语义）与 enableSableSupport（跨 5 个子包消费的
     *  总开关——放 core 才能保证任一子包删除后仍稳定提供）。 */
    public static final ModConfigSpec SPEC = CK.build();

    private ConfigCore() {
    }
}