package com.hdf.cryptand.dynamic.api;

/**
 * ===== 框架对外约定的唯一集中点（2026-09-29 用户定案：其他 mod 必须走我们的 API）=====
 *
 * <p>第三方<b>只依赖 api 包</b>（api 不依赖 core），所有路径/常量/版本判定都从这里取，
 * 不得硬编码字符串 —— 这样宿主改约定时第三方不会静默失效。</p>
 */
public final class DynamicApi {

    private DynamicApi() {
    }

    /** 当前 API 版本（第三方实现按此声明 {@code apiVersion()}）。 */
    public static final int VERSION = 1;

    /** 宿主支持的 apiVersion 闭区间（不匹配 ⇒ 拒绝，且不加载任何类）。 */
    public static final int MIN_API = 1;
    public static final int MAX_API = 1;

    /** ServiceLoader 目录。 */
    public static final String SERVICES_DIR = "META-INF/services/";

    /** 插件元数据（免类加载读取；由注解处理器生成）。 */
    public static final String PLUGIN_META = "META-INF/cryptand-dynamic/plugin.properties";

    /** 核心元数据（免类加载读取）。 */
    public static final String CORE_META = "META-INF/cryptand-dynamic/core.properties";

    /** mod 内嵌动态包的目录前缀（其后是 {@code <coreId>/}）。 */
    public static final String MOD_EMBED_DIR = "META-INF/cryptand-dynamic/";

    /** 统一根（相对游戏目录）：{@code <GAMEDIR>/cryptand/dynamic/}。 */
    public static final String ROOT_DIR = "cryptand/dynamic";

    /** 历史 UI 目录（迁移期兼容扫描，命中时打日志提示迁移）。 */
    public static final String LEGACY_UI_DIR = "cryptand/dyui";

    /** 某 SPI 的 services 文件路径。 */
    public static String servicePath(Class<?> spi) {
        return SERVICES_DIR + spi.getName();
    }

    /** 宿主是否支持该 API 版本。 */
    public static boolean supports(int apiVersion) {
        return apiVersion >= MIN_API && apiVersion <= MAX_API;
    }

    /** 版本不兼容时的统一报错文案。 */
    public static String incompatible(int apiVersion) {
        return "API 版本不兼容：插件声明 " + apiVersion + "，宿主支持 [" + MIN_API + "," + MAX_API
                + "]（请使用 DynamicApi.VERSION 对齐后重编）";
    }
}
