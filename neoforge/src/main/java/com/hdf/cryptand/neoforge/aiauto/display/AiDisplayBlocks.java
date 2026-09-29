package com.hdf.cryptand.neoforge.aiauto.display;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * ===== AI 展示方块的注册（aiauto 子包内的调试设施）=====
 *
 * <p>只注册一个方块 + 一个物品 + 一个方块实体类型；随 aiauto 子包存在，删除子包即消失。</p>
 */
public final class AiDisplayBlocks {

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("cryptand");
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("cryptand");
    public static final DeferredRegister<BlockEntityType<?>> BE_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "cryptand");

    public static final DeferredHolder<Block, AiDisplayBlock> DISPLAY = BLOCKS.register("ai_display",
            () -> new AiDisplayBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_BLACK)
                    .strength(0.5F)
                    .sound(SoundType.STONE)
                    .noOcclusion()
                    .isViewBlocking((s, l, p) -> false)));

    public static final DeferredHolder<Item, BlockItem> DISPLAY_ITEM = ITEMS.register("ai_display",
            () -> new BlockItem(DISPLAY.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AiDisplayBlockEntity>> DISPLAY_BE =
            BE_TYPES.register("ai_display", () -> BlockEntityType.Builder
                    .of(AiDisplayBlockEntity::new, DISPLAY.get()).build(null));

    private AiDisplayBlocks() {
    }

    /** 双端注册（方块/物品/BE 类型） */
    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BE_TYPES.register(bus);
    }
}
