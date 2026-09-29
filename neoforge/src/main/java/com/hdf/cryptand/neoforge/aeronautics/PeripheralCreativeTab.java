/**
 * ===== 外设统一创造标签栏（2026-09-13） =====
 *
 * 用户定稿："可以参考外设船舵，统一放在外设的标签栏" —— 外设船舵与外设拉杆（以及以后的外设）
 * 共用同一个【外设】标签页，而不是各开一个。
 *
 * 实现要点：
 * <ul>
 *   <li>标签栏本身<b>无条件注册</b>（只要 aeronautics 联动加载），因此"只开拉杆"或"只开船舵"时
 *       都能看到它；</li>
 *   <li>内容在 {@code displayItems} 里<b>按实际注册情况动态填充</b>（{@code isRegistered()} 内存标志），
 *       未开启的功能不会被塞进来（否则访问未绑定的 DeferredHolder 会崩）。</li>
 * </ul>
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmRegistry;
import com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PeripheralCreativeTab {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Cryptand.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register(
            "peripherals",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cryptand.peripherals"))
                    .icon(() -> new ItemStack(iconItem()))
                    .displayItems((params, output) -> {
                        if (PeripheralHelmRegistry.isRegistered()) {
                            output.accept(PeripheralHelmRegistry.PERIPHERAL_HELM_ITEM.get());
                        }
                        if (PeripheralLeverRegistry.isRegistered()) {
                            output.accept(PeripheralLeverRegistry.PERIPHERAL_LEVER_ITEM.get());
                        }
                        if (com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingRegistry
                                .isRegistered()) {
                            output.accept(com.hdf.cryptand.neoforge.aeronautics.railing
                                    .PeripheralRailingRegistry.PERIPHERAL_RAILING_ITEM.get());
                        }
                        if (com.hdf.cryptand.neoforge.aeronautics.transmission
                                .PeripheralTransmissionRegistry.isRegistered()) {
                            output.accept(com.hdf.cryptand.neoforge.aeronautics.transmission
                                    .PeripheralTransmissionRegistry.PERIPHERAL_TRANSMISSION_ITEM.get());
                        }
                        if (com.hdf.cryptand.neoforge.aeronautics.transmissionkey
                                .PeripheralTransmissionKeyRegistry.isRegistered()) {
                            output.accept(com.hdf.cryptand.neoforge.aeronautics.transmissionkey
                                    .PeripheralTransmissionKeyRegistry.PERIPHERAL_TRANSMISSION_ITEM.get());
                        }
                    })
                    .build());

    /** 标签图标：优先用已开启的外设物品；两个都没开时退化为屏障（理论上不会出现） */
    private static Item iconItem() {
        if (PeripheralHelmRegistry.isRegistered()) {
            return PeripheralHelmRegistry.PERIPHERAL_HELM_ITEM.get();
        }
        if (PeripheralLeverRegistry.isRegistered()) {
            return PeripheralLeverRegistry.PERIPHERAL_LEVER_ITEM.get();
        }
        return Items.BARRIER;
    }

    public static void register(IEventBus bus) {
        TABS.register(bus);
    }

    private PeripheralCreativeTab() {
    }
}
