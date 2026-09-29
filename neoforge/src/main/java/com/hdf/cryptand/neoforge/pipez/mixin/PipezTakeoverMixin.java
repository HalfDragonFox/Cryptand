package com.hdf.cryptand.neoforge.pipez.mixin;

import com.hdf.cryptand.neoforge.pipez.PipezTakeover;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pipez 完全接管 Mixin（2026-08-29 P3；2026-08-29 修复注册时机）：
 * <p>
 * 在 {@code PipeLogicTileEntity.tick()} HEAD：
 * <ul>
 *   <li>【懒注册】首帧起用 {@code this} 调 {@link PipezTakeover#ensureRegistered(BlockEntity)}
 *       ——BE 此刻必然已进入 world（getLevel/getBlockPos 可用），彻底替代
 *       EntityPlaceEvent 里 getBlockEntity(pos) 拿不到 BE 的问题（devices=0 根因）；</li>
 *   <li>【取消原版】已接管/注册成功 → {@code ci.cancel()}，原版自插管逻辑完全停用，
 *       传输移交 INC；注册失败（非服务端/异常）→ 不接管，原版照常，不破坏管道。</li>
 * </ul>
 * 被接管的管道不再自 tick；未接管（非 Pipez / 未登记）不干预 → 无副作用。
 */
@Mixin(de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity.class)
public abstract class PipezTakeoverMixin {

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cryptand$takeoverTick(CallbackInfo ci) {
        // ⚠ 2026-08-30 子包开关（用户：与 MC 有关的 mod 联动为单独子包+单独开关）：
        // enablePipezSupport=false（或 pipez 未加载）→ 本联动关闭——不接管、
        // 原版照常（核心引擎与其他子包不受影响）。
        if (!com.hdf.cryptand.neoforge.pipez.PipezModule.shouldLoad()) return;
        // ⚠ 经 (Object) 桥接：Mixin 生成的代理类静态类型与 BlockEntity 无继承关系，
        // 直接 this instanceof BlockEntity 会被 javac 判为“不兼容的类型”。
        if (((Object) this) instanceof BlockEntity be
                && PipezTakeover.ensureRegistered(be)) {
            ci.cancel(); // 已接管或本次注册成功 → 停用原版自 tick（完全接管）
        }
    }
}