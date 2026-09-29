package com.hdf.cryptand.neoforge.simserver.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigSimserver —— 本地内核仿真 HTTP 接口配置（2026-08-30 用户架构：所有配置
 * 放到相关子包——原 core 域 common.toml#enableSimServer 迁入 simserver 子包）。
 */
public final class ConfigSimserver implements CryptandConfigSpec {

    public static final String DOMAIN = "simserver";

    public static final ConfigSimserver INSTANCE = new ConfigSimserver();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "simserver.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "simserver.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /**
     * 本地内核仿真 HTTP 接口开关（simserver.toml#enableSimServer）。
     * true → 服务器启停时启动/停止仿真 HTTP 接口；false → 不启动（核心引擎与其他
     * 子包不受影响）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SIM_SERVER = CK
            .comment("本地内核仿真 HTTP 接口开关",
                     "true(默认): 服务器启停时启动/停止仿真 HTTP 接口",
                     "false: 不启动仿真接口(核心引擎与其他子包不受影响)")
            .define("enableSimServer", true);

    public static final ModConfigSpec SPEC = CK.build();
}
