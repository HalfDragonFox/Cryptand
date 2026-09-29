/**
 * ===== 外设栏杆（按钮）注册（2026-09-13） =====
 * 复制外设拉杆的注册结构：方块 / 物品 / 方块实体 / 数据包条件。
 * ⚠ 客户端渲染器注册与内容注册共用 registered 内存标志（避免 unbound value 崩溃）。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

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

public final class PeripheralRailingRegistry {

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

    public static final DeferredHolder<Block, PeripheralRailingBlock> PERIPHERAL_RAILING = BLOCKS.register(
            "peripheral_railing",
            () -> new PeripheralRailingBlock(BlockBehaviour.Properties.of()
                    .noOcclusion().strength(2.0f, 6.0f)));

    public static final DeferredHolder<Item, BlockItem> PERIPHERAL_RAILING_ITEM = ITEMS.register(
            "peripheral_railing",
            () -> new BlockItem(PERIPHERAL_RAILING.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PeripheralRailingBlockEntity>>
            PERIPHERAL_RAILING_BE = BE_TYPES.register(
            "peripheral_railing",
            () -> BlockEntityType.Builder.of(PeripheralRailingBlockEntity::new,
                    PERIPHERAL_RAILING.get()).build(null));

    public static void register(IEventBus bus) {
        if (registered) {
            return;
        }
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BE_TYPES.register(bus);
        CONDITIONS.register(bus);
        CONDITIONS.register("peripheral_railing_enabled", () -> PeripheralRailingCondition.CODEC);
        registered = true;
    }

    private PeripheralRailingRegistry() {
    }
}
