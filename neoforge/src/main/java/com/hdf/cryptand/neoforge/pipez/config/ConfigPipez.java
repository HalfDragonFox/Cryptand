package com.hdf.cryptand.neoforge.pipez.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigPipez —— Pipez（管道）联动支持/接管配置（2026-08-30 按子包拆分，
 * 与 CEE / PowerGrid 同模式）。
 *
 * 用户架构（2026-08-30）：核心引擎为单独支持（与 MC 无关）；
 * 与 MC 有关的每个 mod 联动为【单独子包 + 单独开关】——本开关为 false
 * 只关闭【Pipez 联动】（管道接管），不影响核心引擎与其他子包。
 */
public final class ConfigPipez implements CryptandConfigSpec {

    /** 配置域 id。 */
    public static final String DOMAIN = "pipez";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigPipez INSTANCE = new ConfigPipez();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "pipez.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    /** 子包自注册：把本配置注册到 core 注册器。 */
    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "pipez.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /**
     * Pipez（管道）接管开关（cryptand/pipez.toml#enablePipezSupport）。
     * true  → 启用管道接管（管道设备接入自管网络——PipezTakeover +
     *         PipezDeviceAdapter + Mixin 注入生效）；
     * false → 关闭【Pipez 联动】（Mixin 逻辑短路、接管不生效、管道不接入
     *         自管网络）——核心引擎与其他子包不受影响。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_PIPEZ_SUPPORT = CK
            .comment("管道(Pipez)联动支持/接管开关",
                     "true: 启用管道接管（管道设备接入自管网络）",
                     "false: 关闭管道联动（不影响核心引擎与其他子包）")
            .define("enablePipezSupport", true);

    public static final ModConfigSpec SPEC = CK.build();
}
