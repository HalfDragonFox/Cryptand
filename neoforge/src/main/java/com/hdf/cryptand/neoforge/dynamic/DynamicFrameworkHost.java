package com.hdf.cryptand.neoforge.dynamic;

import com.hdf.cryptand.dynamic.api.DynamicApi;
import com.hdf.cryptand.dynamic.api.LoadWindow;
import com.hdf.cryptand.dynamic.core.DynamicFramework;
import com.hdf.cryptand.neoforge.dynamic.ui.DynamicUiCore;
import com.hdf.cryptand.neoforge.soc.ui.plugin.UiContext;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ===== 动态框架宿主门面（neoforge 侧装配，2026-09-29）=====
 *
 * <p>把通用框架与具体核心装起来：UI 核心 + mod 内嵌来源 + 目录来源 + 项目多线程调度。</p>
 *
 * <p><b>低侵入约束</b>（用户要求"对原版尽可能少的影响，防止 mod 过量冲突"）：</p>
 * <ul>
 *   <li>本类<b>不引用任何 {@code net.minecraft.client.*}</b>；客户端相关由调用方注入
 *       （{@link ClientDynamicHost} 是唯一引用客户端类的门面）；</li>
 *   <li>不注册 MC 事件、不碰 MC 注册表、不改类加载器、不使用 mixin —— 纯"旁挂"层；</li>
 *   <li>只读写自己的目录 {@code cryptand/dynamic}（以及兼容扫描 {@code cryptand/dyui}）；
 *       对其他 mod 的 jar 只<b>读</b>不写，提取产物落在 {@code <root>/<modid>/} 隔离目录；</li>
 *   <li>可通过 {@link #shutdown()} 完全拆除（线程池关闭、面板注销、类加载器关闭）⇒ 关掉即零痕迹。</li>
 * </ul>
 */
public final class DynamicFrameworkHost {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile DynamicFramework framework;
    private static volatile CryptandDynamicScheduler scheduler;

    private DynamicFrameworkHost() {
    }

    /** 初始化（幂等）。{@code mainExecutor} 由客户端注入（服务端可传 server::execute）。 */
    public static synchronized DynamicFramework init(Path gameDir, Supplier<UiContext> uiContext,
                                                     Consumer<Runnable> mainExecutor) {
        if (framework != null) {
            return framework;
        }
        final boolean dynamicOn = com.hdf.cryptand.neoforge.dynamic.config.ConfigDynamic.enabled();
        final CryptandDynamicScheduler sched = new CryptandDynamicScheduler();
        if (mainExecutor != null) {
            sched.setMainExecutor(mainExecutor);
            // 同一个主线程执行器交给通用任务门面 Tasks（用户 2026-09-29："框架提供各种库工具和实现，
            // 外部核心只需要像 python 跑 ai 一样简单"）。两者共用一套派发，避免出现
            // "动态调度器注入了、Tasks 没注入"这种谁会踩到全看调用路径的模糊状态。
            com.hdf.cryptand.dynamic.task.Tasks.installMainExecutor(mainExecutor);
            LOGGER.info("[dynamic] 主线程派发器已注入：Tasks 与动态调度器共用同一执行器");
        } else {
            // 不兜底：明确说出来。服务端/离线路径可以同线程直跑，但那是"有意为之"，不是"忘了注入"。
            LOGGER.warn("[dynamic] 未注入主线程派发器：Tasks.mainThread 将同线程直跑（仅服务端/离线测试可接受）");
        }
        scheduler = sched;
        final Path root = gameDir.resolve(DynamicApi.ROOT_DIR);
        final Path legacy = gameDir.resolve(DynamicApi.LEGACY_UI_DIR);
        final DynamicFramework.Builder b = DynamicFramework.builder()
                .root(root)
                .phase(LoadWindow.CLIENT_SETUP)
                .scheduler(sched)
                .logger(msg -> LOGGER.info("[dynamic] {}", msg));

        // 用户定案：总闸关 ⇒ 只剩基础动态核心，所有具体核心直接失效
        if (dynamicOn) {
            int granted = 0;
            final java.util.List<com.hdf.cryptand.dynamic.api.DynamicCoreSpi<?>> candidates =
                    new java.util.ArrayList<>();
            candidates.add(new DynamicUiCore(uiContext));
            candidates.add(new com.hdf.cryptand.neoforge.dynamic.mixin.DynamicMixinCore());
            // 第二个具体核心（验伪抽象通用性）：把插件自带的 LSS 文本变成样式表
            candidates.add(new com.hdf.cryptand.neoforge.dynamic.style.DynamicStyleCore());
            for (final com.hdf.cryptand.dynamic.api.DynamicCoreSpi<?> c : candidates) {
                // 本 mod 自带核心（com.hdf.cryptand.*）默认授权；第三方核心需配置白名单
                final boolean builtin = c.getClass().getName().startsWith("com.hdf.cryptand.");
                if (com.hdf.cryptand.neoforge.dynamic.config.ConfigDynamic.coreAllowed(c.id(), builtin)) {
                    b.core(c);
                    granted++;
                } else {
                    LOGGER.info("[dynamic] 第三方核心 {} 未授权（dynamic.toml → allowThirdPartyCores），跳过", c.id());
                }
            }
            // 来源：读其他 mod 的 jar 提取内嵌包涉及执行他人代码 ⇒ 默认关闭
            if (com.hdf.cryptand.neoforge.dynamic.config.ConfigDynamic.modEmbeddedEnabled()) {
                b.source(new ModEmbeddedSource());
            }
            b.source(new DynamicRootSource(legacy));
            LOGGER.info("[dynamic] 总闸开启：已授权 {} 个具体核心", granted);
        } else {
            LOGGER.info("[dynamic] 总闸关闭（dynamic.toml → enableDynamicFramework）：只剩基础动态核心，具体核心已失效");
        }
        final DynamicFramework f = b.build();
        framework = f;
        LOGGER.info("[dynamic] 框架就绪：root={}", root);
        return f;
    }

    public static boolean ready() {
        return framework != null;
    }

    public static DynamicFramework framework() {
        return framework;
    }

    public static CryptandDynamicScheduler scheduler() {
        return scheduler;
    }

    /** 完全拆除（关停时调用）：卸载全部条目 + 关核心 + 关线程池。 */
    public static synchronized void shutdown() {
        final DynamicFramework f = framework;
        framework = null;
        scheduler = null;
        if (f != null) {
            try {
                f.shutdown();
            } catch (Throwable t) {
                LOGGER.warn("[dynamic] 关停异常：{}", t.toString());
            }
        }
    }
}
