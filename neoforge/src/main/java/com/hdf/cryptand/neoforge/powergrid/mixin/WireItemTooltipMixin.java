/**
 * ===== 导线 tooltip 追加（2026-08-22 用户需求） =====
 *
 * 原版线缆描述调整：
 *   - "最大电流" → "额定电流"：lang 覆盖 powergrid.tooltip.current.max；
 *   - 本 Mixin 追加一行【电阻: xxx mΩ/1m】（每米毫欧，来自 SaggingWireType
 *     .resistancePerMeter）——让导线 hover 直观看到每米电阻。
 *
 * 仅对本 mod 已登记的三类导线（copper/iron/golden，SaggingWireRegistry）
 * 追加；未登记的原版/其他导线不追加。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.patryk3211.powergrid.electricity.wire.WireItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = WireItem.class, remap = false)
public abstract class WireItemTooltipMixin {

    @Inject(method = "tooltip", at = @At("RETURN"), remap = false)
    private static void cryptand$appendWireRatedTooltip(ItemStack stack,
            List<Component> tooltip, Item.TooltipContext context, TooltipFlag flag,
            CallbackInfo ci) {
        try {
            // 仅本 mod 已登记导线（按注册名）追加每米电阻
            ResourceLocation rid = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (rid == null) return;
            SaggingWireType t = SaggingWireRegistry.byItemId(rid.toString());
            if (t == null) t = SaggingWireRegistry.byItem(stack.getItem());
            if (t == null) {
                // 兜底：按注册名路径直查内置三类（powergrid:wire=铜 / iron_wire / golden_wire）
                String p = rid.getPath();
                String builtin = "wire".equals(p) ? "copper"
                        : "iron_wire".equals(p) ? "iron"
                        : "golden_wire".equals(p) ? "golden" : null;
                if (builtin != null) t = SaggingWireRegistry.get(builtin);
            }
            if (t == null) return;
            String mOhm = String.format("%.2f", t.resistancePerMeter() * 1000.0);
            tooltip.add(Component.translatable(
                    "cryptand.tooltip.wire.resistance_per_meter", mOhm));
        } catch (Throwable ignored) {
        }
    }
}
