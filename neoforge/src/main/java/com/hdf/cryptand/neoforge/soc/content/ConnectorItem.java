package com.hdf.cryptand.neoforge.soc.content;

import com.lowdragmc.lowdraglib2.gui.factory.HeldItemUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;

import java.util.List;

/**
 * ===== 硬件连接器（2026-09-29 用户定案：**取消绑定功能**）=====
 *
 * <p>用户原话：「硬件连接器还是没改掉，取消绑定功能，仅右键机箱打开硬件配置页面」。</p>
 *
 * <p>所以本物品<b>不再</b>有"选中端点 / 建链路 / 潜行断开 / 色点配对"这些语义，
 * 只剩一个作用：<b>右键机箱 → 打开硬件配置页面</b>（页面数据在服务端枚举后写进本物品的 NBT，
 * LDLib2 的手持物品菜单会把那份物品栈发给客户端 ⇒ 两端读同一份数据）。</p>
 *
 * <p>页面里的改动（接口优先级顺序）也存本物品的 NBT：改完点「保存到机箱芯片」才写进机箱里那颗
 * Cryptand 芯片（用户定案：CPU 信息是权威、所有配置信息在 CPU 里）。</p>
 */
public class ConnectorItem extends Item implements HeldItemUIMenuType.HeldItemUI {

    /** 页面数据（设备 + 接口点，由 {@code SocPairingData} 服务端枚举后写入） */
    public static final String TAG_PANEL = "HwPanel";

    /** 页面锚点（右键的那台机箱坐标：保存时要回到它去找芯片） */
    public static final String TAG_ANCHOR = "HwAnchor";

    /** 接口优先级覆盖：每个设备一条 {@code "<pos>|<deviceId>" -> "port1,port2,..."} */
    public static final String TAG_ORDER = "HwOrder";

    /** 机箱里那颗 CPU 的架构：{@code cryptand}（我们的 RV 芯片，可接线）/ {@code lua}（原版 Lua，接线无效）/ 其它 */
    public static final String TAG_CPU_ARCH = "HwCpuArch";

    /** 接线表：{@code "<pos>|<deviceId>" -> "RV 芯片端口 id"}（用户：外设/扩展卡接到 RV 芯片的哪个口） */
    public static final String TAG_BINDING = "HwBinding";

    /** 画布布局：器件 key -> 拖动后的 x,y（编码见 {@code HwCanvasLayout.encodePositions}，空 = 用自动摆位） */
    public static final String TAG_LAYOUT = "HwLayout";

    public ConnectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public ModularUI createUI(HeldItemUIMenuType.HeldItemUIHolder holder) {
        return com.hdf.cryptand.neoforge.soc.ui.HardwareConfigUi.build(holder);
    }

    // ==================== NBT 读写（面板与配置的唯一载体）====================

    private static CompoundTag tag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    private static void put(ItemStack stack, CompoundTag tag) {
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static void setPanel(ItemStack stack, String anchorKey, CompoundTag panel, String cpuArch) {
        final CompoundTag tag = tag(stack);
        tag.putString(TAG_ANCHOR, anchorKey);
        tag.put(TAG_PANEL, panel);
        tag.putString(TAG_CPU_ARCH, cpuArch == null ? "none" : cpuArch);
        put(stack, tag);
    }

    /** 机箱里 CPU 的架构（cryptand / lua / none） */
    public static String cpuArch(ItemStack stack) {
        final String a = tag(stack).getString(TAG_CPU_ARCH);
        return a.isEmpty() ? "none" : a;
    }

    /** 某设备当前接到 RV 芯片的哪个端口（没接返回空串） */
    public static String binding(ItemStack stack, String deviceKey) {
        return tag(stack).getCompound(TAG_BINDING).getString(deviceKey);
    }

    /** 接上 / 改接某个端口（portId 为空 = 解绑） */
    public static void setBinding(ItemStack stack, String deviceKey, String portId) {
        final CompoundTag tag = tag(stack);
        final CompoundTag bindings = tag.getCompound(TAG_BINDING).copy();
        if (portId == null || portId.isEmpty()) {
            bindings.remove(deviceKey);
        } else {
            bindings.putString(deviceKey, portId);
        }
        tag.put(TAG_BINDING, bindings);
        put(stack, tag);
    }

    /** 全部接线（诊断/写芯片用） */
    public static CompoundTag bindings(ItemStack stack) {
        return tag(stack).getCompound(TAG_BINDING).copy();
    }

    public static CompoundTag panel(ItemStack stack) {
        return tag(stack).getCompound(TAG_PANEL);
    }

    public static String anchor(ItemStack stack) {
        return tag(stack).getString(TAG_ANCHOR);
    }

    /** 该设备当前的接口优先级（没改过就是设备自己的默认顺序，空表表示"用默认"） */
    public static List<String> order(ItemStack stack, String deviceKey) {
        final String raw = tag(stack).getCompound(TAG_ORDER).getString(deviceKey);
        return raw.isEmpty() ? List.of() : List.of(raw.split(","));
    }

    /** 画布布局编码（空串 = 用户还没拖过，用自动摆位） */
    public static String layout(ItemStack stack) {
        return tag(stack).getString(TAG_LAYOUT);
    }

    /** 记住画布布局（只存用户拖过的 x/y；标签/端口以实时枚举为准） */
    public static void setLayout(ItemStack stack, String encoded) {
        final CompoundTag tag = tag(stack);
        tag.putString(TAG_LAYOUT, encoded == null ? "" : encoded);
        put(stack, tag);
    }

    public static void setOrder(ItemStack stack, String deviceKey, List<String> ports) {
        final CompoundTag tag = tag(stack);
        final CompoundTag order = tag.getCompound(TAG_ORDER).copy();
        order.putString(deviceKey, String.join(",", ports));
        tag.put(TAG_ORDER, order);
        put(stack, tag);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.cryptand.connector.line1").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.cryptand.connector.line2").withStyle(ChatFormatting.GRAY));
        final String anchor = anchor(stack);
        tooltip.add(Component.literal(anchor.isEmpty() ? "还没打开过任何机箱" : "上次机箱：" + anchor)
                .withStyle(ChatFormatting.DARK_GRAY));
    }
}
