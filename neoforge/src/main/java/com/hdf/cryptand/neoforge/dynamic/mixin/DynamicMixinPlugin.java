package com.hdf.cryptand.neoforge.dynamic.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * ===== 通用 mixin 门控 plugin（2026-09-29）=====
 *
 * <p>用法：某个 mod 的 mixins.json 里写 {@code "plugin": "<子类全名>"}；子类实现 {@link #modId()}
 * 返回自己的 modid ⇒ 即"向 mixin 核心申请注册"。</p>
 *
 * <p>判定：</p>
 * <ul>
 *   <li>modid <b>未注册</b> ⇒ 返回 {@code true}（<b>完全不干预</b>，不会干扰任何未选择的 mod）；</li>
 *   <li>modid <b>已注册</b> ⇒ <b>默认白名单</b>：只有列入白名单的 mixin 才注入。</li>
 * </ul>
 *
 * <p><b>硬约束</b>：本方法在类加载期被调用（可能早于 mod 构造）⇒ 只能依赖注册表/常量，不能触碰
 * 需要 Bootstrap 的注册表类（项目既有铁律，见 `mixin-plugin-bootstrap-registry` 记忆）。</p>
 */
public abstract class DynamicMixinPlugin implements IMixinConfigPlugin {

    /** 本 config 所属 modid（子类返回常量字符串）。 */
    protected abstract String modId();

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
        final String id = modId();
        if (!MixinCoreRegistry.isRegistered(id)) {
            return true;   // 未注册：不干预（保护其他 mod）
        }
        return MixinCoreRegistry.isAllowed(id, mixinClassName);   // 已注册：默认白名单
    }
}
