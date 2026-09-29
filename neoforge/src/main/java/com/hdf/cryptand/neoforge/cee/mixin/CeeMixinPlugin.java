/**
 * ===== cee 功能 Mixin 子管理（2026-08-24） =====
 *
 * CEE（Create-Electro-Energetics）联动 Mixin：CEE 支持开启 + CEE mod 已装才注入。
 */

package com.hdf.cryptand.neoforge.cee.mixin;

import com.hdf.cryptand.neoforge.cee.CeeTerminalSupport;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class CeeMixinPlugin implements IMixinConfigPlugin {

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
            apply = CeeTerminalSupport.ceeEnabled() && CeeTerminalSupport.ceeModLoaded();
        } catch (Throwable t) {
            apply = false;
        }
        String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        return apply;
    }
}
