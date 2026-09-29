package com.hdf.cryptand.neoforge.waterphysics.mixin;

import com.hdf.cryptand.neoforge.core.registry.MixinGateRegistry;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * waterphysics 子包的 mixin 配置插件。
 *
 * <p>⚠ 运行于 Minecraft Bootstrap <b>之前</b>：本类只允许引用常量与不含静态
 * DeferredRegister/BuiltInRegistries 的类，否则会 ExceptionInInitializerError
 * （Not bootstrapped）导致启动静默崩溃。
 *
 * <p>子包开关的门控由入口的 mixinConfigs() 负责（关闭子包时整份配置不加载），
 * 这里只服从全局白/黑名单。
 */
public final class WaterphysicsMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(final String mixinPackage) {
    }

    /** 返回 null = 不生成 refmap（与 CeeMixinPlugin 一致）。 */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass,
                         final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass,
                          final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return !MixinGateRegistry.isDenied(mixinClassName);
    }
}
