package com.hdf.cryptand.neoforge.railway;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.cee.config.ConfigCee;
import com.hdf.cryptand.neoforge.railway.catenary.CatenaryHolderBlock;
import com.hdf.cryptand.neoforge.railway.catenary.CatenaryHolderBlockEntity;
import com.hdf.cryptand.neoforge.railway.pantograph.PantographBlock;
import com.hdf.cryptand.neoforge.railway.pantograph.PantographBlockEntity;
import com.hdf.cryptand.neoforge.railway.pantograph.PantographMovementBehaviour;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
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

/**
 * CEE 铁路电气化内容注册（受电弓 + 接触网悬挂块）。
 * 命名空间沿用 Cryptand（cryptand 前缀），避免与真实 CEE mod 已注册的
 * electroenergetics:* 冲突（两者可同装：Cryptand 自有受电弓 + CEE 兼容层）。
 * <p>
 * Java 代码按用户要求放在独立的 CEE modid 命名空间目录（com.hdf.cryptand.neoforge.cee）。
 */
public final class RailwayRegistry {

    /** 内容是否已注册（register() 成功调用后置 true；配置关闭/异常 → false）。
     *  ⚠ 2026-08-22 启动崩溃修复：客户端渲染器（RailwayClient）与内容注册
     *  必须用【同一内存标志】——ConfigLoad 在 mod 构造期可能未加载（get() 抛
     *  异常），若渲染器事件期读到 true 而构造期跳过注册 → 未绑定 BE get()
     *  → NPE（unbound value cryptand:cee_pantograph）。 */
    private static volatile boolean registered = false;

    public static boolean isRegistered() {
        return registered;
    }

    /** 内容是否应注册（CEE 支持配置；构造期配置未加载/异常 → 默认 true=启用）。 */
    public static boolean shouldRegister() {
        try {
            // ⚠ 构造期 spec 未加载 → 预读配置文件（铁路电气化随 CEE 支持开关）
            return ConfigCee.SPEC.isLoaded()
                    ? ConfigCee.ENABLE_CEE_SUPPORT.get()
                    : com.hdf.cryptand.neoforge.core.config.ConfigLoad
                            .preloadBoolean("cee", "enableCeeSupport", true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cryptand.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cryptand.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BE_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cryptand.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Cryptand.MOD_ID);

    // ========== 方块 ==========

    public static final DeferredHolder<Block, PantographBlock> CEE_PANTOGRAPH = BLOCKS.register(
            "cee_pantograph",
            () -> new PantographBlock(BlockBehaviour.Properties.of()
                    .noOcclusion().strength(3.0f, 6.0f)));

    public static final DeferredHolder<Block, CatenaryHolderBlock> CEE_CATENARY_HOLDER = BLOCKS.register(
            "cee_catenary_holder",
            () -> new CatenaryHolderBlock(BlockBehaviour.Properties.of()
                    .noOcclusion().strength(2.0f, 6.0f)));

    // ========== 物品 ==========

    public static final DeferredHolder<Item, BlockItem> CEE_PANTOGRAPH_ITEM = ITEMS.register(
            "cee_pantograph",
            () -> new BlockItem(CEE_PANTOGRAPH.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> CEE_CATENARY_HOLDER_ITEM = ITEMS.register(
            "cee_catenary_holder",
            () -> new BlockItem(CEE_CATENARY_HOLDER.get(), new Item.Properties()));

    // ========== 方块实体 ==========

    public static final Supplier<BlockEntityType<PantographBlockEntity>> CEE_PANTOGRAPH_BE = BE_TYPES.register(
            "cee_pantograph",
            () -> BlockEntityType.Builder.of(PantographBlockEntity::new, CEE_PANTOGRAPH.get())
                    .build(null));

    public static final Supplier<BlockEntityType<CatenaryHolderBlockEntity>> CEE_CATENARY_HOLDER_BE = BE_TYPES.register(
            "cee_catenary_holder",
            () -> BlockEntityType.Builder.of(CatenaryHolderBlockEntity::new, CEE_CATENARY_HOLDER.get())
                    .build(null));

    // ========== 创造标签栏：铁路电气化 ==========

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register(
            "cee_railway",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cryptand.cee_railway"))
                    .icon(() -> new ItemStack(CEE_PANTOGRAPH_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(CEE_PANTOGRAPH_ITEM.get());
                        output.accept(CEE_CATENARY_HOLDER_ITEM.get());
                    })
                    .build());

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BE_TYPES.register(modEventBus);
        TABS.register(modEventBus);
        registered = true;
        // CEE 全自管电路（2026-08-22）：接触网导线类型注册 + 放置/拆除入网钩子
        RailwayWireType.ensureRegistered();
        RailwayPlacementHook.registerBus();
        // M3：列车/装置上的受电弓行为（MovementBehaviour；装配后骨架渲染+升降弓动画）
        try {
            MovementBehaviour.REGISTRY.register(CEE_PANTOGRAPH.get(),
                    new PantographMovementBehaviour());
        } catch (Throwable ignored) {
        }
    }

    private RailwayRegistry() {
    }
}