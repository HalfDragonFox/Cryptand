/**
 * ===== sable（航空学物理亚层）Mixin 子管理（2026-08-24） =====
 *
 * 仅安装 sable（物理化核心）mod 时注入（目标类不存在时跳过安全）。
 * ⚠ 类加载阶段 FMLLoader.getLoadingModList()（PREPARE 早于 FML 初始化）。
 */

package com.hdf.cryptand.neoforge.cee.mixin.sable;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.Set;

public final class SableMixinPlugin implements IMixinConfigPlugin {

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
        boolean apply = false;
        try {
            apply = net.neoforged.fml.loading.FMLLoader
                    .getLoadingModList().getModFileById("sable") != null;
        } catch (Throwable ignored) {
        }
        String shortName = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        System.out.println("[Cryptand] SableMixin " + shortName
                + " → " + (apply ? "INJECTED" : "SKIPPED") + " (sable=" + apply + ")");
        return apply;
    }
}
