package com.hdf.cryptand.neoforge.powergrid.motor.singlephase;

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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ===== 单相异步电机注册（2026-08-30 用户：删除电容启动电机，改两极/四极/
 * 六极/八极单相异步电机，功率统一 2 kW，现实参数建模） =====
 *
 * 注册 4 个方块：cryptand:single_phase_motor_2p / _4p / _6p / _8p
 *  - 共用 BE {@link SinglePhaseAsyncMotorBlockEntity}（极数从 Block 读）；
 *  - 模型【与原版普通电机同样材质】：blockstate 引用 powergrid:block/
 *    electric_motor（原版电机模型/纹理——材质完全一致）；
 *  - 渲染：ElectricMotorRenderer（Create 轴）+ HalfShaftVisual（Flywheel 轴）。
 */
public final class SinglePhaseAsyncMotors {

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(Cryptand.MOD_ID);
    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(Cryptand.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BE_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Cryptand.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Cryptand.MOD_ID);

    /** 支持的极数（2/4/6/8） */
    public static final int[] POLES = {2, 4, 6, 8};

    // ⚠ 2026-08-30 崩溃根因（放置 "Block not found in any resistance providers"）：
    // ElectricMotorBlockEntity.addBehaviours → ElectricBehaviour → buildCircuit →
    // IElectricEntity.resistance → PowerGrid ResistanceValues.get(block) 查线圈电阻——
    // 原版 electric_motor 经 CResistance.setResistance(25.6) 注册；我们方块未注册 →
    // 遍历 providers 全 null → 抛 IllegalArgumentException 崩溃。注册我们的 provider。
    static {
        org.patryk3211.powergrid.config.ResistanceValues.register(
                new org.patryk3211.powergrid.config.ResistanceValues.Provider() {
                    @Override
                    public java.util.function.DoubleSupplier get(net.minecraft.world.level.block.Block block) {
                        return block instanceof SinglePhaseAsyncMotorBlock ? () -> 25.6 : null;
                    }

                    @Override
                    public java.util.function.DoubleSupplier get(net.minecraft.world.level.block.Block block,
                                                                 String suffix) {
                        return block instanceof SinglePhaseAsyncMotorBlock ? () -> 25.6 : null;
                    }
                });
    }

    /** name → 极数（注册顺序：2p/4p/6p/8p） */
    public static final Map<String, Integer> MOTOR_NAMES = new LinkedHashMap<>();
    static {
        for (int p : POLES) MOTOR_NAMES.put("single_phase_motor_" + p + "p", p);
    }

    /** 极数 → 方块（注册后填充；MotorAssembler 分发用） */
    public static final Map<Integer, DeferredHolder<Block, SinglePhaseAsyncMotorBlock>> BY_POLES =
            new LinkedHashMap<>();
    /** 极数 → 物品（客户端注册物品形态渲染用，2026-09-13 用户："手持时转轴
     *  没渲染出来，放置是能渲染的" → 需要 IClientItemExtensions 指向我们的
     *  BlockEntityWithoutLevelRenderer）。 */
    private static final Map<Integer, DeferredHolder<Item, BlockItem>> ITEM_HOLDERS =
            new LinkedHashMap<>();

    /** 极数 → BE type holder（注册返回；beTypeFor 直接取，避免经 Block 递归） */
    private static final Map<Integer, DeferredHolder<BlockEntityType<?>, BlockEntityType<?>>>
            BE_TYPE_HOLDERS = new LinkedHashMap<>();

    // ========== 方块 + 物品 + 方块实体（4 个极数） ==========

    static {
        for (Map.Entry<String, Integer> e : MOTOR_NAMES.entrySet()) {
            String name = e.getKey();
            int poles = e.getValue();
            DeferredHolder<Block, SinglePhaseAsyncMotorBlock> blk = BLOCKS.register(name,
                    () -> {
                        // ⚠ 2026-08-30 根因（放置崩溃 "Block not found in any
                        // resistance providers"）：仿原版 electric_motor（Registrate
                        // .transform(CStress.setCapacity(64))）注册 Create 应力——
                        // Block 实例创建时注册 CAPACITIES/IMPACTS/发电机转速，
                        // 否则 Create BlockStressValues.getCapacity 抛异常崩溃。
                        SinglePhaseAsyncMotorBlock mb = new SinglePhaseAsyncMotorBlock(
                                BlockBehaviour.Properties.of().strength(3.0f, 6.0f).noOcclusion(),
                                poles);
                        try {
                            com.simibubi.create.api.stress.BlockStressValues.CAPACITIES
                                    .register(mb, () -> 64.0);
                            com.simibubi.create.api.stress.BlockStressValues.IMPACTS
                                    .register(mb, () -> 0.0);
                            com.simibubi.create.api.stress.BlockStressValues
                                    .setGeneratorSpeed(256, true).accept(mb);
                        } catch (Throwable ignored) {
                        }
                        return mb;
                    });
            DeferredHolder<Item, BlockItem> ih = ITEMS.register(name,
                    () -> new BlockItem(blk.get(), new Item.Properties()));
            ITEM_HOLDERS.put(poles, ih);
            // 与 RailwayRegistry/CreativeSources 先例一致：Builder.of(BE::new, block)
            DeferredHolder<BlockEntityType<?>, BlockEntityType<?>> beh = BE_TYPES.register(name,
                    () -> BlockEntityType.Builder
                            .of(SinglePhaseAsyncMotorBlockEntity::new, blk.get())
                            .build(null));
            BE_TYPE_HOLDERS.put(poles, beh);
            BY_POLES.put(poles, blk);
        }
    }

    /** 按极数取方块（未注册 → null） */
    public static Block blockFor(int poles) {
        DeferredHolder<Block, SinglePhaseAsyncMotorBlock> h = BY_POLES.get(poles);
        return h == null ? null : h.get();
    }

    /** 按极数取物品（客户端注册物品渲染扩展用；未注册 → null） */
    public static Item itemFor(int poles) {
        DeferredHolder<Item, BlockItem> h = ITEM_HOLDERS.get(poles);
        return h == null ? null : h.get();
    }

    /** 按极数取 BE type（直接取注册 holder；⚠ 2026-08-30 修复：不再经
     *  Block.getBlockEntityType()——那会再次调用本方法 → 无限递归 → null
     *  → 放置方块 IBE.newBlockEntity NPE 崩溃） */
    public static BlockEntityType<?> beTypeFor(int poles) {
        DeferredHolder<BlockEntityType<?>, BlockEntityType<?>> h = BE_TYPE_HOLDERS.get(poles);
        return h == null ? null : h.get();
    }

    // ========== 创造标签栏："Cryptand 单相异步电机" ==========

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register(
            "single_phase_motors",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.cryptand.single_phase_motors"))
                    .icon(() -> new ItemStack(blockFor(2)))
                    .displayItems((params, output) -> {
                        for (int p : POLES) {
                            Block b = blockFor(p);
                            if (b != null) output.accept(b);
                        }
                    })
                    .build());

    private SinglePhaseAsyncMotors() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        BE_TYPES.register(bus);
        TABS.register(bus);
    }
}
