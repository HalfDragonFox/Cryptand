/**
 * ===== Create 多线程化 - KineticBlockEntity 访问器 =====
 *
 * 目标类：com.simibubi.create.content.kinetics.base.KineticBlockEntity
 *
 * 暴露 KineticBlockEntity 的 overStressed 字段的 setter。
 * 供本模组的其他功能（如齿轮应力可视化）使用。
 */

package com.hdf.cryptand.neoforge.mixin.create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KineticBlockEntity.class)
public interface KineticBlockEntityAccessorMixin {
    @Accessor("overStressed")
    void setOverStressed(boolean overStressed);
}
