/**
 * ===== 导线放置接管（2026-08-13 一步到位：放置直接写自管图，不创建原版实体） =====
 *
 * 拦截 WireItem.useOn（PowerGrid 导线右键放置入口）：
 *   - 自管模式（PowerGridWireConverter.isEnabled()）→ 交给 CryptandWirePlacement
 *     处理（记录起点/写自管图），cancel 原版放置 → 不创建 BaseWireEntity 实体、
 *     不进 transmissionLines/原版网络。
 *   - 非自管模式 → 放行原版放置。
 *
 * 客户端/服务端都拦截（客户端记录起点 tag 供预览；服务端执行写图 + 消耗）。
 * 电源线（Cord）在 CryptandWirePlacement 内放行原版（不转换）。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.CryptandWirePlacement;
import dev.architectury.event.EventResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WireItem.class, remap = false)
public abstract class WireItemUseOnMixin {

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private static void cryptand$takeoverUseOn(Player player, InteractionHand hand,
                                               BlockPos blockPos, Direction direction,
                                               CallbackInfoReturnable<EventResult> cir) {
        try {
            Level level = player != null ? player.level() : null;
            // 自管放置（PowerGrid 接管）→ 一律拦截转 CryptandWirePlacement
            if (CryptandWirePlacement.selfManaged(level))
                cir.setReturnValue(CryptandWirePlacement.handleUseOn(player, hand, blockPos, direction));
// M4（2026-08-22）：即使 PowerGrid 接管关闭，只要【目标方块是接线端子】
            //（任意注册表端子：接触网/受电弓/CEE 等）也拦截 → Cryptand 导线直连
            // 端子即全自管（写入自管 WireNetwork）。非端子目标 → 放行原版 PowerGrid 放置。
            else if (level != null
                    && com.hdf.cryptand.neoforge.powergrid.device.terminal.WireTerminals
                            .isTerminal(level, blockPos, level.getBlockState(blockPos)))
                cir.setReturnValue(CryptandWirePlacement.handleUseOn(player, hand, blockPos, direction));
        } catch (Throwable ignored) {
        }
    }
}
