package com.hdf.cryptand.neoforge.cee.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigCee —— CEE（Create-Electro-Energetics）铁路电气化支持/接管配置（2026-09-02 按子包拆分）。
 * <p>持有本域全部配置字段定义（ModConfigSpec）与 SPEC；文件路径由 ConfigLoad 总管理
 * （域 DOMAIN="cee"）。读取门面 bool/integer/real/string → ConfigLoad.readXxx(DOMAIN, key, def)。
 */
public final class ConfigCee
        implements CryptandConfigSpec {

    /** 配置域 id（ConfigLoad.domainFileName 映射）。 */
    public static final String DOMAIN = "cee";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigCee INSTANCE = new ConfigCee();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "cee.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    /** 子包自注册：把本配置注册到 core 注册器（core 只提供注册接口，不收集）。 */
    public static void register() {
        CryptandRegistries
                .registerConfig(DOMAIN, "cee.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /*
     * =====================================================================
     *  CEE（Create-Electro-Energetics）支持/接管配置（cryptand/cee.toml）
     * =====================================================================
     */

    /**
     * CEE（Create-Electro-Energetics）受电弓/接触网内容 + 系统接管开关
     * （2026-08-22 用户要求："添加此mod支持，如果开启支持则替换该模组系统，
     *  并且该模组端子也能和交错电网混合，毕竟是自管的"）。
     * <p>
     * true  → 启用 Cryptand 自带的 CEE 铁路电气化内容（受电弓/接触网方块，
     *         始终由 Cryptand 自管 WireNetwork 供电）；并开启 CEE 兼容层：
     *         用 Cryptand 仿真核心【替换/接管】CEE 的电力系统，CEE 的端子
     *         （受电弓/电缆端子等）接入 Cryptand 自管交错电网统一求解。
     * false → 完全不加载 CEE 相关内容（含受电弓/接触网方块与接管 Mixin）。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_CEE_SUPPORT = CK
            .comment("CEE(Create-Electro-Energetics)支持/接管开关",
                     "true: 启用受电弓/接触网内容 + 用自研核心替换/接管CEE电力系统,",
                     "      CEE端子接入Cryptand自管交错电网",
                     "false: 完全不加载CEE相关内容",
                     "需要重启游戏生效")
            .define("enableCeeSupport", true);

    /** 本域 Spec（ConfigLoad 聚合注册）。 */
    public static final ModConfigSpec SPEC = CK.build();

    private ConfigCee() {
    }
}