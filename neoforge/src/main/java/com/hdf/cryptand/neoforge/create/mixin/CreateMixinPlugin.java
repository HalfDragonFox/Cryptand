/**
 * ===== create 功能 Mixin 子管理（2026-08-24） =====
 *
 * Create（机械动力）集成 Mixin：受 enableCreateStressLimit 配置控制
 * （复用 CryptandMixinPlugin 的 TOML 读取）。
 */

package com.hdf.cryptand.neoforge.create.mixin;

import com.hdf.cryptand.neoforge.mixin.CryptandMixinPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

public final class CreateMixinPlugin implements IMixinConfigPlugin {

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
        boolean apply;
        try {
            apply = CryptandMixinPlugin.isCreateStressLimit();
        } catch (Throwable t) {
            apply = true;
        }
        String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        System.out.println("[Cryptand] CreateMixin " + shortName
                + " → " + (apply ? "INJECTED" : "SKIPPED")
                + " (createStressLimit=" + apply + ")");
        return apply;
    }
}
