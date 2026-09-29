/**
 * ===== 导线右键空气清除绑定（2026-08-15）=====
 *
 * 拦截 WireItem.use（Architectury InteractionEvent.RIGHT_CLICK_ITEM 事件，
 * 右键空气时触发）：
 *   - 手持导线右键空气 → 清除导线绑定（起点 CONNECTION_DATA 组件 + 客户端
 *     会话 CLIENT_WINDING_POS/TERMINAL + 服务端 PENDING/PENDING_TURNS）。
 *   - 返回 pass（不消耗物品；原版 use 逻辑不执行）。
 *   - 原版是 shift+右键才清；用户要求右键空气即清。
 *
 * 客户端/服务端都拦截（右键空气双端触发）：客户端清物品组件+客户端状态，
 * 服务端清放置会话。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin.interaction;

import com.hdf.cryptand.neoforge.powergrid.network.wire.CryptandWirePlacement;
import dev.architectury.event.CompoundEventResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = WireItem.class, remap = false)
public abstract class WireItemUseMixin {

    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private static void cryptand$takeoverUse(Player user, InteractionHand hand,
                                             CallbackInfoReturnable<CompoundEventResult<ItemStack>> cir) {
        try {
            CryptandWirePlacement.clearBindingByAir(user, hand);
        } catch (Throwable ignored) {
        }
    }
}
