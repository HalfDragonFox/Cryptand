/**
 * ===== railway 功能 Mixin 子管理（2026-08-24） =====
 *
 * 铁路电气化（CEE 联动）Mixin：仅 CEE 支持开启时注入
 * （与 RailwayRegistry.shouldRegister() 同一开关，防止未注册内容+注入并存）。
 *
 * ⚠ 2026-08-24 启动崩溃根因修复：Mixin 插件运行于 Minecraft Bootstrap
 * 【之前】——绝不能在插件里引用 RailwayRegistry 等含静态
 * DeferredRegister.create(Registries.*) 字段的类：类加载即触发
 * BuiltInRegistries.<clinit> → "Not bootstrapped" → ExceptionInInitializerError
 * → Bootstrap 阶段 NoClassDefFoundError 崩溃。
 * 插件只允许读取配置/FMLLoader（轻量，零注册表访问）。
 */

package com.hdf.cryptand.neoforge.railway.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

public final class RailwayMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 与 RailwayRegistry.shouldRegister() 同语义（配置未就绪默认 true=启用），
        // 但只读配置，不加载 Registry 类（Bootstrap 前禁止注册表访问）。
        boolean apply;
        try {
            apply = com.hdf.cryptand.neoforge.core.config.ConfigLoad
                    .ENABLE_CEE_SUPPORT.get();
        } catch (Throwable t) {
            apply = true;
        }
        String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        System.out.println("[Cryptand] RailMixin " + shortName
                + " → " + (apply ? "INJECTED" : "SKIPPED") + " (ceeSupport=" + apply + ")");
        return apply;
    }
}
