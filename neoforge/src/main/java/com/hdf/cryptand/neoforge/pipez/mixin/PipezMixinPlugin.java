package com.hdf.cryptand.neoforge.pipez.mixin;

import net.neoforged.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Pipez 接管 Mixin 配置（2026-08-29 P3）：仅在装有 Pipez 时注入。
 * ⚠ 类加载阶段 ModList.get() 为 null → 用 FMLLoader.getLoadingModList()。
 */
public final class PipezMixinPlugin implements IMixinConfigPlugin {

    private static final boolean LOADED = isLoaded();

    private static boolean isLoaded() {
        try {
            return FMLLoader.getLoadingModList().getModFileById("pipez") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String s = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        return LOADED;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}