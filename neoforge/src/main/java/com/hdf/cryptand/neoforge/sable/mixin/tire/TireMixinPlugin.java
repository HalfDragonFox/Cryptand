/**
 * ===== 轮胎拟真 Mixin 门控插件（2026-09-14） =====
 *
 * <p>⚠ 铁律（见 ai_memory/repo/mixin-plugin-bootstrap-registry.md）：本插件在
 * Minecraft Bootstrap **之前**运行 —— 只允许读 FMLLoader 的 mod 列表与常量字符串，
 * **绝不能**引用任何会触发 BuiltInRegistries 静态初始化的类（try/catch 也救不回
 * "Not bootstrapped" 的坏类）。
 *
 * <p>门控条件：offroad 已加载（目标类存在）。运行时的配置开关由 Hook 内部判定。
 */
package com.hdf.cryptand.neoforge.sable.mixin.tire;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class TireMixinPlugin implements IMixinConfigPlugin {

    private static volatile Boolean offroadLoaded = null;

    /** 只读 FMLLoader（Bootstrap 前安全）。 */
    private static boolean offroadLoaded() {
        final Boolean cached = offroadLoaded;
        if (cached != null) return cached;
        boolean found = false;
        try {
            for (final Object m : net.neoforged.fml.loading.FMLLoader.getLoadingModList().getMods()) {
                final String id = String.valueOf(m.getClass().getMethod("getModId").invoke(m));
                if ("offroad".equals(id)) {
                    found = true;
                    break;
                }
            }
        } catch (final Throwable ignored) {
            found = false;
        }
        offroadLoaded = found;
        return found;
    }

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        return offroadLoaded();
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final org.objectweb.asm.tree.ClassNode targetClass,
                         final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final org.objectweb.asm.tree.ClassNode targetClass,
                          final String mixinClassName, final IMixinInfo mixinInfo) {
    }
}
