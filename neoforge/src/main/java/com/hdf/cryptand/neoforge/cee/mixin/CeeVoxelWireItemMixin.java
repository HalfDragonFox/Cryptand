/**
 * ===== CEE 线盘 → 自管放置拦截（2026-08-22 用户：CEE 导线无法连交错电网端子） =====
 *
 * CEE 线盘（VoxelWireItem）在自管模式下拦截 useOn → 转 CryptandWirePlacement
 * 自管放置（两击连自管网，端子= 统一注册表：PowerGrid/CEE/接触网/受电弓）：
 *   - 客户端：记录起点（CONNECTION_DATA）→ 阻止 CEE 原版 VoxelWireNodePlacement
 *   - 服务端：PENDING 会话 → 放行后写自管 WireNetwork
 * 取消后不再走 CEE 原版线网（VoxelWireConnection / InfrastructureSavedData）。
 * CEE 未装/关闭 → CryptandMixinPlugin 跳过本 mixin（目标类不存在安全）。
 */
package com.hdf.cryptand.neoforge.cee.mixin;

import com.george_vi.electroenergetics.content.voxel_wire.VoxelWireItem;
import com.hdf.cryptand.neoforge.cee.CeeTerminalSupport;
import com.hdf.cryptand.neoforge.powergrid.network.wire.CryptandWirePlacement;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import dev.architectury.event.EventResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = VoxelWireItem.class, remap = false)
public abstract class CeeVoxelWireItemMixin {

    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true, remap = false)
    private void cryptand$takeoverUseOn(UseOnContext context,
                                        CallbackInfoReturnable<InteractionResult> cir) {
        try {
            if (context == null || context.getPlayer() == null) return;
            // 自管模式（PowerGrid 接管 + CEE 支持）：CEE 线盘 → 自管放置
            if (!PowerGridWireConverter
                    .isEnabled()) return;
            if (!CeeTerminalSupport.ceeEnabled()) return;
            EventResult r = CryptandWirePlacement.handleUseOn(
                    context.getPlayer(), context.getHand(),
                    context.getClickedPos(), context.getClickedFace());
            cir.setReturnValue(r.asMinecraft());
        } catch (Throwable ignored) {
        }
    }
}
