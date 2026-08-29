/**
 * ===== 示波器 =====
 *
 * 像万用表那样右键电网端点搭线（复用 MultimeterItem 搭线机制，端点记录在
 * modeData.Pos）。接入后：
 *   - 手持时 HUD 显示小波形屏幕（OscilloscopeHudMixin 渲染）
 *   - 右键空气 → 放大到屏幕 80% 的波形界面（OscilloscopeScreen）
 * 客户端测量请求/响应统一走自定义测量类（SelfManagedMultimeter）：
 * 手持时每 10 tick 发送 OscilloscopeRequestPayload（C2S）→ 服务端求解网络
 * 提取该端子每频率相量 → 回发 → 自定义测量类接受 → OscilloscopeStore 更新。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.neoforge.core.measurement.CryptandMeterItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterItem;

public class OscilloscopeItem extends CryptandMeterItem {

    public OscilloscopeItem(Properties properties) {
        super(properties);
    }

    @Override
    public int maxProbeLines() { return 1; }

    /** 右键空气：客户端打开放大波形界面（LDLib2 ModularUIScreen）；搭线仍走 useOn。
     *  ⚠ 2026-08-14 服务端兼容：方法体不能直接引用客户端类（RuntimeDistCleaner
     *  在服务端 transform 时扫描方法体引用并加载检查 @OnlyIn → Screen 崩）。
     *  客户端逻辑全部移到 OscilloscopeClientBridge，这里用反射字符串调用。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            try {
                Class.forName("com.hdf.cryptand.neoforge.powergrid.creative.OscilloscopeClientBridge")
                        .getMethod("openScreen", Level.class, Player.class,
                                InteractionHand.class, ItemStack.class)
                        .invoke(null, level, player, hand, stack);
            } catch (Throwable ignored) {
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    /** 手持时客户端每 10 tick 发送波形请求（统一走自定义测量类，含节流）。
     *  ⚠ 服务端兼容：同上，经 OscilloscopeClientBridge 反射调用。 */
    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        // 仅手持选中时发送（自定义测量类内做节流）
        if (level.isClientSide && selected && entity instanceof Player player) {
            try {
                Class.forName("com.hdf.cryptand.neoforge.powergrid.creative.OscilloscopeClientBridge")
                        .getMethod("tickRequest", Level.class, Player.class, ItemStack.class)
                        .invoke(null, level, player, stack);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 搭线端点提示（接上时 HUD 显示位置） */
    public static Component probeLabel(ItemStack stack) {
        try {
            net.minecraft.nbt.CompoundTag modeData = getModeData(stack);
            if (modeData != null && modeData.contains("Pos")) {
                IWireEndpoint ep = WireEndpointType.deserialize(modeData.getCompound("Pos"));
                if (ep instanceof BlockWireEndpoint bep) {
                    return Component.literal(bep.getPos().toShortString() + "#" + bep.getTerminal());
                }
            }
        } catch (Throwable ignored) {
        }
        return Component.literal("未接线");
    }

    /** 检测时间（秒）：读物品 NBT（默认 1.0） */
    public static double getDetectSeconds(ItemStack stack) {
        try {
            net.minecraft.nbt.CompoundTag modeData = getModeData(stack);
            if (modeData != null && modeData.contains("ScopeDetectS")) {
                return modeData.getDouble("ScopeDetectS");
            }
        } catch (Throwable ignored) {
        }
        return 1.0;
    }

    /** 检测时间（秒）：写物品 NBT（客户端设置，下次请求生效） */
    public static void setDetectSeconds(ItemStack stack, double seconds) {
        try {
            if (stack == null || stack.isEmpty()) return;
            net.minecraft.nbt.CompoundTag modeData = getModeData(stack);
            if (modeData == null) modeData = new net.minecraft.nbt.CompoundTag();
            modeData.putDouble("ScopeDetectS",
                    Math.max(0.05, Math.min(5.0, seconds)));
            saveModeData(stack, modeData);
        } catch (Throwable ignored) {
        }
    }
}
