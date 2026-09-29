package com.hdf.cryptand.neoforge.dynamic.mixin;

import com.hdf.cryptand.dynamic.api.CoreHost;
import com.hdf.cryptand.dynamic.api.DynamicApi;
import com.hdf.cryptand.dynamic.api.DynamicCoreSpi;
import com.hdf.cryptand.dynamic.api.FormatProbe;
import com.hdf.cryptand.dynamic.api.LoadWindow;
import com.hdf.cryptand.dynamic.api.Source;

import java.util.Set;

/**
 * ===== mixin 具体核心（2026-09-29 用户定案）=====
 *
 * <p>职责：把插件的"modid + mixin 白名单"注册进 {@link MixinCoreRegistry}。核心本身不加载 mixin 类
 * （mixin 类必须在 Mixin 的 classloader 视野内 ⇒ 只能来自 mod 的类路径），只做<b>门控管理</b>。</p>
 *
 * <p>加载窗口 = {@link LoadWindow#MOD_CONSTRUCT}：门控表越早建立越有效（已加载的类无法再撤销注入）。</p>
 */
public final class DynamicMixinCore implements DynamicCoreSpi<MixinGatePlugin> {

    @Override
    public String id() {
        return "mixin";
    }

    @Override
    public Set<String> suffixes() {
        return Set.of(".jar");
    }

    @Override
    public Class<MixinGatePlugin> pluginType() {
        return MixinGatePlugin.class;
    }

    @Override
    public LoadWindow window() {
        return LoadWindow.MOD_CONSTRUCT;
    }

    @Override
    public boolean canHandle(Source src, FormatProbe probe) {
        return probe.zip() && probe.hasEntry(DynamicApi.servicePath(MixinGatePlugin.class));
    }

    @Override
    public void attach(MixinGatePlugin plugin, CoreHost host) {
        final String modId = plugin.mixinModId();
        MixinCoreRegistry.registerModId(modId);
        for (final String mixin : plugin.allowedMixins()) {
            MixinCoreRegistry.allow(modId, mixin);
        }
        host.own("mixin-mod:" + modId, () -> MixinCoreRegistry.unregisterModId(modId));
        host.warn("[mixin] 已接管 modid=" + modId + " 的 mixin 门控（白名单 "
                + MixinCoreRegistry.allowedCount(modId) + " 条）");
    }

    @Override
    public void detach(MixinGatePlugin plugin, CoreHost host) {
        plugin.unload();
    }
}
