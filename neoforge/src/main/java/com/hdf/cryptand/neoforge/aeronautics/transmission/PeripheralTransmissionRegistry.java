/**
 * ===== 外设模拟传动器内容注册（航空学子包扩展，2026-09-14）=====
 *
 * 复制原版 `simulated:analog_transmission`（模拟传动器）。仅在
 * `aeronautics.toml#enablePeripheralTransmission=true`（默认 false，且需 gameinput 同步开启）时注册。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmission;

import com.hdf.cryptand.Cryptand;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

public final class PeripheralTransmissionRegistry {

    private static volatile boolean registered = false;

    public static boolean isRegistered() {
        return registered;
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cryptand.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cryptand.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BE_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cryptand.MOD_ID);
    public static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS =
            DeferredRegister.create(NeoForgeRegistries.CONDITION_SERIALIZERS.key(), Cryptand.MOD_ID);

    public static final DeferredHolder<Block, PeripheralTransmissionBlock> PERIPHERAL_TRANSMISSION =
            BLOCKS.register("peripheral_transmission",
                    () -> new PeripheralTransmissionBlock(BlockBehaviour.Properties.of()
                            .noOcclusion().strength(2.0f, 6.0f)));

    public static final DeferredHolder<Item, BlockItem> PERIPHERAL_TRANSMISSION_ITEM = ITEMS.register(
            "peripheral_transmission",
            () -> new BlockItem(PERIPHERAL_TRANSMISSION.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PeripheralTransmissionBlockEntity>>
            PERIPHERAL_TRANSMISSION_BE = BE_TYPES.register(
            "peripheral_transmission",
            () -> BlockEntityType.Builder.of(
                    PeripheralTransmissionBlockEntity::new, PERIPHERAL_TRANSMISSION.get()).build(null));

    public static void register(IEventBus bus) {
        if (registered) {
            return;
        }
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BE_TYPES.register(bus);
        CONDITIONS.register(bus);
        registered = true;
    }

    private PeripheralTransmissionRegistry() {
    }
}
