/**
 * ===== create 功能 Mixin 子管理（2026-08-24） =====
 *
 * Create（机械动力）集成 Mixin：受 enableCreateStressLimit 配置控制
 * （复用 CryptandMixinPlugin 的 TOML 读取）。
 */

package com.hdf.cryptand.neoforge.create.mixin;

import com.hdf.cryptand.neoforge.create.config.ConfigCreate;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

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
        // 门控（与其它子包的 mixin 插件同款）：开关取自本子包配置
        // create.toml#enableCreateStressLimit。
        // ⚠ 兜底必须是 false：Mixin 插件运行在【类加载期】，若此时 ModConfigSpec 还没加载，
        //   旧实现兜底 true 就等于"配置里关了、齿轮照样被 destroyBlock 销毁"
        //   （用户报的"确定关闭了吗，现在还是有"）。破坏性功能的兜底只能是"不注入"。
        try {
            if (ConfigCreate.SPEC.isLoaded()) {
                return ConfigCreate.ENABLE_CREATE_STRESS_LIMIT.get();
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}
