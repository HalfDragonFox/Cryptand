/**
 * ===== Mixin 配置插件（CryptandMixinPlugin，2026-09-07 子包隔离瘦身） =====
 *
 * 历史职责：类加载阶段经各子包 Config 类判定 mixin 注入。2026-09-07 起各子包
 * mixin 判定迁回各自 json/plugin（powergrid → powergrid.mixin.PowergridMixinPlugin，
 * 挂在 cryptand.powergrid.mixins.json；cee/create/aeronautics/sable 等早已各自
 * 独立 json+plugin）——本类只保留 core 通用门面：
 *   - MixinGateRegistry 白/黑名单 gate（对所有子包 plugin 之外的 core mixin）
 *   - 常驻放行（其余 mixin）
 * 本类不再 import 任何子包类 → 删除任一子包目录不影响本类编译。
 */
package com.hdf.cryptand.neoforge.mixin;

import com.hdf.cryptand.neoforge.core.module.SubpackageRegistry;
import com.hdf.cryptand.neoforge.core.registry.MixinGateRegistry;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class CryptandMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // ★ 2026-09-06 【核心门控注册表】子包初始化的白/黑名单（黑名单硬禁 / 白名单硬放行，
        //   先于本 plugin 的一切条件；MC 加载完成后核心 clear()——见 MixinGateRegistry）
        if (MixinGateRegistry.isDenied(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, false);
            return false;
        }
        if (MixinGateRegistry.isAllowed(mixinClassName)) {
            SubpackageRegistry.recordMixin(mixinClassName, true);
            return true;
        }
        // 其余 Mixin 常驻
        SubpackageRegistry.recordMixin(mixinClassName, true);
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
