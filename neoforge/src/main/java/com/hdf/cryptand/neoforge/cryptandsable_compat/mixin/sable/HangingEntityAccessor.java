package com.hdf.cryptand.neoforge.cryptandsable_compat.mixin.sable;

import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * HangingEntity Accessor —— 提取 protected {@code calculateSupportBox()}。
 *
 * <p>官方 sable 的 {@code SubLevelAssemblyHelper.moveOtherStuff} 需要遍历悬挂实体的支撑盒
 * 以判断是否需要随装配迁移；该方法为 protected，本 accessor 以 {@code @Invoker} 提权，
 * 使 CryptandSable 同款实现可调用（并由 aeronautics 的 SubLevelAssemblyHelperMixin 在
 * moveOtherStuff TAIL 捕获 {@code BoundedBitVolume3i volume} 局部变量）。
 */
@Mixin(HangingEntity.class)
public interface HangingEntityAccessor {

    @Invoker("calculateSupportBox")
    AABB cryptandsable$calculateSupportBox();
}