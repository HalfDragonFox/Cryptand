/**
 * ===== BlockEntityType 工厂访问器（2026-09-13 寄生式接管 POC）=====
 *
 * 背景（javap 1.21.1 实测）：BlockEntityType.factory 是 private final；
 * 存档加载路径 LevelChunk.promotePendingBlockEntity 会经
 * BlockEntityType.create(pos, state) 使用它 ⇒ 只用 mixin 改方块侧无法覆盖
 * 【从存档加载】的旧 BE。
 *
 * 本 Accessor 用 @Mutable 去掉 final 限制，把 factory 换成产出 Cryptand 自有
 * BE 子类的 lambda —— 注册 id 不变，故旧存档 / 配方 / 其它 mod 引用全部照旧，
 * 只是实例类型换成我们的子类（见 CryptandElectricMotorBE）。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockEntityType.class)
public interface BlockEntityTypeAccessor {

    /** 替换 BE 工厂（@Mutable 解除 final；调用方负责早于世界加载） */
    @Mutable
    @Accessor("factory")
    void cryptand$setFactory(BlockEntityType.BlockEntitySupplier<?> supplier);
}
