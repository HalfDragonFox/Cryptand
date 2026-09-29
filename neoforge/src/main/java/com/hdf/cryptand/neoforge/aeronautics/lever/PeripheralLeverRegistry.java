/**
 * ===== 外设拉杆内容注册（航空学子包扩展，2026-09-13） =====
 *
 * 方块 / 物品 / 方块实体 / 数据包加载条件。仅在 `aeronautics.toml#enablePeripheralLever=true`
 * （默认 false，且需要 gameinput 库同步开启）时注册。
 *
 * ⚠ 客户端渲染器注册必须与内容注册用【同一内存标志】（registered）——构造期配置可能未加载，
 * 读配置会时序不一致（RailwayRegistry 教训）。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

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

public final class PeripheralLeverRegistry {

    private static volatile boolean registered = false;

    public static boolean isRegistered() {
        return registered;
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cryptand.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cryptand.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BE_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cryptand.MOD_ID);
    /** 数据包加载条件（配方/战利品表按开关跳过，避免引用不存在的方块） */
    public static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS =
            DeferredRegister.create(NeoForgeRegistries.CONDITION_SERIALIZERS.key(), Cryptand.MOD_ID);

    public static final DeferredHolder<Block, PeripheralLeverBlock> PERIPHERAL_LEVER = BLOCKS.register(
            "peripheral_lever",
            () -> new PeripheralLeverBlock(BlockBehaviour.Properties.of()
                    .noOcclusion().strength(2.0f, 6.0f)));

    public static final DeferredHolder<Item, BlockItem> PERIPHERAL_LEVER_ITEM = ITEMS.register(
            "peripheral_lever",
            () -> new BlockItem(PERIPHERAL_LEVER.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PeripheralLeverBlockEntity>>
            PERIPHERAL_LEVER_BE = BE_TYPES.register(
            "peripheral_lever",
            () -> BlockEntityType.Builder.of(PeripheralLeverBlockEntity::new, PERIPHERAL_LEVER.get())
                    .build(null));

    public static void register(IEventBus bus) {
        if (registered) {
            return;
        }
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BE_TYPES.register(bus);
        CONDITIONS.register(bus);
        CONDITIONS.register("peripheral_lever_enabled", () -> PeripheralLeverCondition.CODEC);
        registered = true;
    }

    private PeripheralLeverRegistry() {
    }
}
