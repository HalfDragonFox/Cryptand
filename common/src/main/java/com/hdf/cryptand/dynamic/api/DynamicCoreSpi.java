package com.hdf.cryptand.dynamic.api;

import java.util.Set;

/**
 * ===== 具体核心 SPI（2026-09-29 定案）=====
 *
 * <p>基础动态核心只提供框架与接口；"这类资源怎么装配"由具体核心实现
 * （UI 核心 → LdlibPanels；asset 核心 → LSS/贴图）。</p>
 *
 * <p>核心注册时提供 {@link #plan()}，框架按它调度该核心的并行策略。</p>
 *
 * @param <T> 该核心的插件主类类型
 */
public interface DynamicCoreSpi<T extends DynamicPlugin> {

    /** 核心 id（唯一；默认目录名同此）。 */
    String id();

    /** 声明的后缀（用于快速索引与文档；真正分派看 {@link #canHandle}）。 */
    Set<String> suffixes();

    /** 该核心在统一根下的目录名（默认 = id）。 */
    default String folder() {
        return id();
    }

    /** 核心的 API 版本（与插件双向协商）。 */
    default int apiVersion() {
        return 1;
    }

    /**
     * 能否处理该来源。默认实现 = 后缀匹配（核心可覆写：读 jar 内元数据判定）。
     * <b>后缀是 1:N</b> —— 多个核心可同时命中，框架按注册顺序 + 配置决定消费语义。
     */
    default boolean canHandle(Source src, FormatProbe probe) {
        return suffixes().contains(probe.suffix());
    }

    /** 该核心可被加载的<b>最早阶段</b>（框架在更早阶段拒绝加载并给出明确错误）。 */
    default LoadWindow window() {
        return LoadWindow.CLIENT_SETUP;
    }

    /** 执行策略（核心注册时提供；默认并行 IO、串行 LOAD、主线程 ATTACH）。 */
    default ExecutorPlan plan() {
        return ExecutorPlan.DEFAULT;
    }

    // ===== 核心生命周期钩子（全部 default：零侵入已有核心；需要自己生命周期的核心才覆写）=====

    /** 核心注册完成后的回调（可在此登记自己的来源/目录/监听）。 */
    default void onRegistered(FrameworkContext ctx) {
    }

    /** 客户端逐 tick（音频通道、动画等需要每帧推进的核心）。离线环境下不会被调用。 */
    default void onClientTick() {
    }

    /** 与 MC 资源重载联动（资源包路线；我们的 hot reload 走 {@code DynamicFramework} 自己的路径）。 */
    default void onResourceReload() {
    }

    /** 客户端/服务器退出时的清理（框架会先 detach 全部插件再调用）。 */
    default void onShutdown() {
    }

    /** 装配（具体核心在这里把插件接进自己的世界；UI 核心在此注册面板）。 */
    void attach(T plugin, CoreHost host);

    /** 拆卸（只做与 attach 对称的清理；宿主负责登记回收与类加载器关闭）。 */
    void detach(T plugin, CoreHost host);

    /** 主类类型（用于 ServiceLoader 定位与类型校验）。 */
    Class<T> pluginType();
}
