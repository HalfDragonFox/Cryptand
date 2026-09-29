/**
 * ===== 交流创造源注册 =====
 *
 * 注册：ac_creative_voltage_source / ac_creative_current_source 方块 + 物品、
 * 共享 BlockEntityType、MenuType、以及"交流创造源"创造标签栏。
 */

package com.hdf.cryptand.neoforge.powergrid.block;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.powergrid.item.AdvancedMultimeterItem;
import com.hdf.cryptand.neoforge.powergrid.item.ConfigStickItem;
import com.hdf.cryptand.neoforge.powergrid.item.OscilloscopeItem;
import com.hdf.cryptand.neoforge.powergrid.item.ResistanceMeterItem;
import com.hdf.cryptand.neoforge.powergrid.item.ThermometerItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public final class CreativeSources {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cryptand.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cryptand.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BE_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cryptand.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Cryptand.MOD_ID);

    // ========== 方块 ==========

    public static final DeferredHolder<Block, AcCreativeSourceBlock> AC_CREATIVE_VOLTAGE_SOURCE = BLOCKS.register(
            "ac_creative_voltage_source",
            () -> new AcCreativeSourceBlock(BlockBehaviour.Properties.of().noOcclusion(), true));

    public static final DeferredHolder<Block, AcCreativeSourceBlock> AC_CREATIVE_CURRENT_SOURCE = BLOCKS.register(
            "ac_creative_current_source",
            () -> new AcCreativeSourceBlock(BlockBehaviour.Properties.of().noOcclusion(), false));

    // ========== 物品 ==========

    public static final DeferredHolder<Item, BlockItem> AC_CREATIVE_VOLTAGE_SOURCE_ITEM = ITEMS.register(
            "ac_creative_voltage_source",
            () -> new BlockItem(AC_CREATIVE_VOLTAGE_SOURCE.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> AC_CREATIVE_CURRENT_SOURCE_ITEM = ITEMS.register(
            "ac_creative_current_source",
            () -> new BlockItem(AC_CREATIVE_CURRENT_SOURCE.get(), new Item.Properties()));

    // ========== 高级万用表（可测 DC / AC） ==========

    public static final DeferredHolder<Item, AdvancedMultimeterItem> ADVANCED_MULTIMETER = ITEMS.register(
            "advanced_multimeter",
            () -> new AdvancedMultimeterItem(new Item.Properties()));

    // ========== 电容 / 电感 ==========

    public static final DeferredHolder<Block, CapacitorBlock> CAPACITOR = BLOCKS.register(
            "capacitor",
            () -> new CapacitorBlock(BlockBehaviour.Properties.of().noOcclusion()));

    public static final DeferredHolder<Block, InductorBlock> INDUCTOR = BLOCKS.register(
            "inductor",
            () -> new InductorBlock(BlockBehaviour.Properties.of().noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> CAPACITOR_ITEM = ITEMS.register(
            "capacitor",
            () -> new BlockItem(CAPACITOR.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> INDUCTOR_ITEM = ITEMS.register(
            "inductor",
            () -> new BlockItem(INDUCTOR.get(), new Item.Properties()));

    // ========== 可编程元件（元件库容器方块） ==========

    public static final DeferredHolder<Block, ProgrammableComponentBlock> PROGRAMMABLE_COMPONENT = BLOCKS.register(
            "programmable_component",
            () -> new ProgrammableComponentBlock(BlockBehaviour.Properties.of().noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> PROGRAMMABLE_COMPONENT_ITEM = ITEMS.register(
            "programmable_component",
            () -> new BlockItem(PROGRAMMABLE_COMPONENT.get(), new Item.Properties()));

    // ========== 配置棒（木棍模型工具） ==========

    public static final DeferredHolder<Item, ConfigStickItem> CONFIG_STICK = ITEMS.register(
            "config_stick",
            () -> new ConfigStickItem(new Item.Properties()));

    public static final DeferredHolder<Item, OscilloscopeItem> OSCILLOSCOPE = ITEMS.register(
            "oscilloscope",
            () -> new OscilloscopeItem(new Item.Properties()));

    // ========== 手持温度表（万用表同款模型，单线连接测温度） ==========

    public static final DeferredHolder<Item, ThermometerItem> THERMOMETER = ITEMS.register(
            "thermometer",
            () -> new ThermometerItem(new Item.Properties()));

    // ========== 手持电阻表（双点测等效电阻，2026-08-19） ==========

    public static final DeferredHolder<Item, ResistanceMeterItem> RESISTANCE_METER = ITEMS.register(
            "resistance_meter",
            () -> new ResistanceMeterItem(new Item.Properties()));

    public static final Supplier<BlockEntityType<CapacitorBlockEntity>> CAPACITOR_BE = BE_TYPES.register(
            "capacitor",
            () -> BlockEntityType.Builder.of(CapacitorBlockEntity::new, CAPACITOR.get())
                    .build(null));

    public static final Supplier<BlockEntityType<InductorBlockEntity>> INDUCTOR_BE = BE_TYPES.register(
            "inductor",
            () -> BlockEntityType.Builder.of(InductorBlockEntity::new, INDUCTOR.get())
                    .build(null));

    public static final Supplier<BlockEntityType<ProgrammableComponentBlockEntity>> PROGRAMMABLE_COMPONENT_BE = BE_TYPES.register(
            "programmable_component",
            () -> BlockEntityType.Builder.of(ProgrammableComponentBlockEntity::new,
                            PROGRAMMABLE_COMPONENT.get())
                    .build(null));

    // ========== 方块实体（两个方块共享） ==========

    public static final Supplier<BlockEntityType<AcCreativeSourceBlockEntity>> AC_CREATIVE_SOURCE_BE = BE_TYPES.register(
            "ac_creative_source",
            () -> BlockEntityType.Builder.of(AcCreativeSourceBlockEntity::new,
                            AC_CREATIVE_VOLTAGE_SOURCE.get(), AC_CREATIVE_CURRENT_SOURCE.get())
                    .build(null));

    // ========== 创造标签栏："交流创造源" ==========

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register(
            "ac_creative_sources",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cryptand.ac_creative_sources"))
                    .icon(() -> new ItemStack(AC_CREATIVE_VOLTAGE_SOURCE.get()))
                    .displayItems((params, output) -> {
                        output.accept(AC_CREATIVE_VOLTAGE_SOURCE.get());
                        output.accept(AC_CREATIVE_CURRENT_SOURCE.get());
                        output.accept(ADVANCED_MULTIMETER.get());
                        output.accept(CAPACITOR.get());
                        output.accept(INDUCTOR.get());
                        output.accept(PROGRAMMABLE_COMPONENT.get());
                        output.accept(CONFIG_STICK.get());
                        output.accept(OSCILLOSCOPE.get());
                        output.accept(THERMOMETER.get());
                        output.accept(RESISTANCE_METER.get());
                    })
                    .build());

    private CreativeSources() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BE_TYPES.register(bus);
        TABS.register(bus);
    }
}
