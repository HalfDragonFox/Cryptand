package com.hdf.cryptand.neoforge.dynamic.config;

import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * ===== 动态资源框架配置域（dynamic.toml，2026-09-29）=====
 *
 * <p>用户定案：「安全相关的必须要配置开启，并且具体核心也需要配置开启才允许加载到框架中」。</p>
 *
 * <ul>
 *   <li>{@code enableDynamicFramework} —— <b>动态资源加载总开关</b>（默认 true）：关闭后只剩基础动态核心，
 *       <b>所有具体核心直接失效</b>（不注册核心、不扫来源目录）；</li>
 *   <li>{@code allowThirdPartyCores} —— <b>第三方核心白名单</b>（默认空）：本 mod 自带核心
 *       （`com.hdf.cryptand.*`）默认授权，其他 mod 的核心必须显式列入；</li>
 *   <li>{@code enableModEmbeddedSource} —— 是否允许从其他 mod 的内嵌目录提取动态包（涉及执行他人代码，默认 false）；</li>
 *   <li>{@code enableAdhocPluginLoad} —— 是否允许 `plugin load &lt;jar&gt;` 临时加载任意本地 jar（调试用，默认 false）。</li>
 * </ul>
 */
public final class ConfigDynamic implements CryptandConfigSpec {

    public static final String DOMAIN = "dynamic";

    public static final ConfigDynamic INSTANCE = new ConfigDynamic();

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public String fileName() {
        return "dynamic.toml";
    }

    @Override
    public ModConfigSpec spec() {
        return SPEC;
    }

    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "dynamic.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /** 动态资源加载总开关（默认 true：本 mod 的具体核心已验证）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_DYNAMIC_FRAMEWORK = CK
            .comment("动态资源加载总开关（默认 true）",
                    "true : 基础动态核心 + 本 mod 的具体核心正常工作（第三方核心另需 allowThirdPartyCores 授权）",
                    "false: 只剩基础动态核心（框架本身），**所有具体核心直接失效**——不注册核心、不扫来源目录",
                    "安全能力（临时加载 / mod 内嵌提取）不受本开关影响，各自另有开关")
            .define("enableDynamicFramework", true);

    /** 第三方核心白名单（本 mod 核心默认授权，无需列出）。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOW_THIRD_PARTY_CORES = CK
            .comment("允许注册的【第三方】核心 id 白名单（默认空）",
                    "本 mod 自带的核心（com.hdf.cryptand.*）默认授权，不需要写在这里",
                    "其他 mod 提供的核心必须显式列入，例如 [\"othermod:audio\"]（安全默认：空）")
            .defineListAllowEmpty("allowThirdPartyCores", List.of(), o -> o instanceof String);

    /** 是否允许从其他 mod 内嵌目录提取动态包。 */
    public static final ModConfigSpec.BooleanValue ENABLE_MOD_EMBEDDED_SOURCE = CK
            .comment("允许从其他 mod 的内嵌目录提取动态包（默认 false）",
                    "true : 扫描 <mod jar>/META-INF/cryptand-dynamic/<coreId>/*.jar 并提取执行（涉及执行他人代码）",
                    "false(默认): 不读其他 mod 的 jar，只加载 cryptand/dynamic 目录")
            .define("enableModEmbeddedSource", false);

    /** 是否允许临时加载任意 jar。 */
    public static final ModConfigSpec.BooleanValue ENABLE_ADHOC_PLUGIN_LOAD = CK
            .comment("允许 plugin load <jar 路径> 临时加载任意本地 jar（默认 false）",
                    "true : 框架接受任意本地 jar 作为调试用插件（不写入 cryptand/dynamic 目录）",
                    "false(默认): plugin load 只提示开关位置；正式分发请保持 false",
                    "安全提示：该能力会执行任意本地代码，仅本地调试使用")
            .define("enableAdhocPluginLoad", false);

    public static final ModConfigSpec SPEC = CK.build();

    // ==================== 运行期读取 ====================

    /** 总闸（运行期读取；配置未加载时回退默认 false）。 */
    public static boolean enabled() {
        try {
            return ENABLE_DYNAMIC_FRAMEWORK.get();
        } catch (Throwable t) {
            return ConfigLoad.preloadBoolean(DOMAIN, "enableDynamicFramework", false);
        }
    }

    /**
     * 该核心是否被授权注册。
     *
     * @param coreId  核心 id
     * @param builtin 是否本 mod 自带（包名 {@code com.hdf.cryptand.*}）—— 自带核心默认授权
     */
    public static boolean coreAllowed(String coreId, boolean builtin) {
        if (builtin) {
            return true;   // 本 mod 已验证的核心：默认开启
        }
        if (coreId == null) {
            return false;
        }
        try {
            for (final Object o : ALLOW_THIRD_PARTY_CORES.get()) {
                if (coreId.equals(String.valueOf(o))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否允许从 mod 内嵌提取。 */
    public static boolean modEmbeddedEnabled() {
        try {
            return ENABLE_MOD_EMBEDDED_SOURCE.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否允许临时加载任意 jar。 */
    public static boolean adhocLoadEnabled() {
        try {
            return ENABLE_ADHOC_PLUGIN_LOAD.get();
        } catch (Throwable t) {
            return false;
        }
    }
}
