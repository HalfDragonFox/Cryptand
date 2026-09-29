/**
 * ===== 外设船舵内容注册（航空学子包扩展，2026-09-13） =====
 *
 * 方块 / 物品 / 方块实体 / 创造标签栏。仅在 `aeronautics.toml#enablePeripheralHelm=true`
 * （默认 false）且 gameinput 库同步开启时注册（见 {@link com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero#peripheralHelmEnabled()}）。
 *
 * ⚠ 客户端渲染器注册必须与内容注册用【同一内存标志】（registered）——
 * 构造期配置可能未加载，读 ConfigLoad 会时序不一致（参见 RailwayRegistry 教训）。
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import com.hdf.cryptand.Cryptand;
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

public final class PeripheralHelmRegistry {

    private static volatile boolean registered = false;

    /** 数据包加载条件（配方/战利品表按开关跳过；见 {@link PeripheralHelmCondition}） */
    public static final DeferredRegister<com.mojang.serialization.MapCodec<? extends net.neoforged.neoforge.common.conditions.ICondition>>
            CONDITIONS = DeferredRegister.create(
                    net.neoforged.neoforge.registries.NeoForgeRegistries.CONDITION_SERIALIZERS.key(),
                    Cryptand.MOD_ID);

    /** 内容是否已注册（客户端渲染器注册用同一标志，避免 unbound value 崩溃）。 */
    public static boolean isRegistered() {
        return registered;
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Cryptand.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Cryptand.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BE_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cryptand.MOD_ID);


    /** 转速源容量（SU/RPM；与 Create 大齿轮同档，玩家可自行限流） */
    public static final double STRESS_CAPACITY = 16.0;

    public static final DeferredHolder<Block, PeripheralHelmBlock> PERIPHERAL_HELM = BLOCKS.register(
            "peripheral_helm",
            () -> {
                PeripheralHelmBlock block = new PeripheralHelmBlock(BlockBehaviour.Properties.of()
                        .noOcclusion().strength(2.0f, 6.0f));
                // ⚠ Create 的 BlockStressValues.getCapacity/getImpact 对未注册方块会抛异常
                // （放置即崩）——必须像原版电机/单相异步电机一样在方块构造期注册（见
                // SinglePhaseAsyncMotors 的同类注释）。
                try {
                    com.simibubi.create.api.stress.BlockStressValues.CAPACITIES
                            .register(block, () -> STRESS_CAPACITY);
                    com.simibubi.create.api.stress.BlockStressValues.IMPACTS
                            .register(block, () -> 0.0);
                    com.simibubi.create.api.stress.BlockStressValues
                            .setGeneratorSpeed(PeripheralHelmBlockEntity.RPM, true).accept(block);
                } catch (Throwable t) {
                    // 注册失败 → 放置时 Create 会抛 "Block not found in any stress providers"，
                    // 必须可见（不静默）
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(
                            "[PeripheralHelm] Create 应力注册失败（放置会崩）", t);
                }
                return block;
            });

    public static final DeferredHolder<Item, BlockItem> PERIPHERAL_HELM_ITEM = ITEMS.register(
            "peripheral_helm",
            () -> new BlockItem(PERIPHERAL_HELM.get(), new Item.Properties()));

    public static final Supplier<BlockEntityType<PeripheralHelmBlockEntity>> PERIPHERAL_HELM_BE =
            BE_TYPES.register(
                    "peripheral_helm",
                    () -> BlockEntityType.Builder.of(PeripheralHelmBlockEntity::new,
                            PERIPHERAL_HELM.get()).build(null));

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        ITEMS.register(modEventBus);
        BE_TYPES.register(modEventBus);
        CONDITIONS.register("peripheral_helm_enabled", () -> PeripheralHelmCondition.CODEC);
        CONDITIONS.register(modEventBus);
        registered = true;
    }

    private PeripheralHelmRegistry() {
    }
}
