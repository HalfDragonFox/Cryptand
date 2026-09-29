/**
 * ===== 方块物理属性表（BlockPhysicsTable，2026-09-05） =====
 *
 * 参考原版 sable：PhysicsBlockPropertyTypes（RegistryObject<PhysicsBlockPropertyType>，
 * 每个方块类型一套 MASS/FRICTION/RESTITUTION/VOLUME）+ FloatingBlockMaterial
 * （liftStrength）——但 Cryptand 框架下用【静态注册表 + materialId】简化：
 *
 *  - 方块类型（Block）→ materialId（int，注册表索引+1；0=未注册→默认）
 *  - materialId → BlockPhysicsProps（纯数据，核心零 MC 依赖）
 *
 * 数据获取方法对齐原版"方块属性配置"思想：默认内置常见方块，扩展可注册
 * （register()）。主线程构图时把 BlockState → materialId 编码进 section 高 16 位，
 * 核心只消费 materialId，不碰 BlockState / Level（铁律：核心不写 Level）。
 *
 * 结构级综合摩擦/弹性：调用方按各材质体积加权平均摩擦、max 弹性——对应 Rapier
 * 碰撞合并规则（friction 平均/相乘、restitution max），见 CompoundShapeMerger 综合。
 */
package com.hdf.cryptand.neoforge.cryptandsable.core.material;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BlockPhysicsTable {

    private BlockPhysicsTable() {
    }

    /** 注册表（索引 0 = DEFAULT；materialId = 索引 + 1）。 */
    private static final List<BlockPhysicsProps> PROPS = new ArrayList<>();

    /** Block → materialId（快查）。 */
    private static final Map<Block, Integer> BY_BLOCK = new HashMap<>();

    /** ResourceLocation → materialId（扩展注册）。 */
    private static final Map<ResourceLocation, Integer> BY_ID = new HashMap<>();

    static {
        register(Blocks.STONE, BlockPhysicsProps.STONE);
        register(Blocks.STONE_BRICKS, BlockPhysicsProps.STONE);
        register(Blocks.COBBLESTONE, BlockPhysicsProps.STONE);
        register(Blocks.DEEPSLATE, BlockPhysicsProps.STONE);
        register(Blocks.DEEPSLATE_BRICKS, BlockPhysicsProps.STONE);
        register(Blocks.GRANITE, BlockPhysicsProps.STONE);
        register(Blocks.DIORITE, BlockPhysicsProps.STONE);
        register(Blocks.ANDESITE, BlockPhysicsProps.STONE);
        register(Blocks.TUFF, BlockPhysicsProps.STONE);
        register(Blocks.CALCITE, BlockPhysicsProps.STONE);
        register(Blocks.NETHERRACK, BlockPhysicsProps.STONE);
        register(Blocks.OBSIDIAN, new BlockPhysicsProps(6.0, 0.5, 0.0, 1.0, 0.0));

        register(Blocks.OAK_PLANKS, BlockPhysicsProps.WOOD);
        register(Blocks.SPRUCE_PLANKS, BlockPhysicsProps.WOOD);
        register(Blocks.BIRCH_PLANKS, BlockPhysicsProps.WOOD);
        register(Blocks.JUNGLE_PLANKS, BlockPhysicsProps.WOOD);
        register(Blocks.ACACIA_PLANKS, BlockPhysicsProps.WOOD);
        register(Blocks.CHERRY_PLANKS, BlockPhysicsProps.WOOD);
        register(Blocks.DARK_OAK_PLANKS, BlockPhysicsProps.WOOD);
        register(Blocks.MANGROVE_PLANKS, BlockPhysicsProps.WOOD);
        register(Blocks.BAMBOO_PLANKS, BlockPhysicsProps.WOOD);
        register(Blocks.OAK_LOG, BlockPhysicsProps.WOOD);
        register(Blocks.SPRUCE_LOG, BlockPhysicsProps.WOOD);
        register(Blocks.BIRCH_LOG, BlockPhysicsProps.WOOD);
        register(Blocks.JUNGLE_LOG, BlockPhysicsProps.WOOD);
        register(Blocks.ACACIA_LOG, BlockPhysicsProps.WOOD);
        register(Blocks.CHERRY_LOG, BlockPhysicsProps.WOOD);
        register(Blocks.DARK_OAK_LOG, BlockPhysicsProps.WOOD);
        register(Blocks.MANGROVE_LOG, BlockPhysicsProps.WOOD);
        register(Blocks.OAK_WOOD, BlockPhysicsProps.WOOD);
        register(Blocks.STRIPPED_OAK_LOG, BlockPhysicsProps.WOOD);
        register(Blocks.CRAFTING_TABLE, BlockPhysicsProps.WOOD);
        register(Blocks.OAK_FENCE, BlockPhysicsProps.WOOD);
        register(Blocks.OAK_STAIRS, BlockPhysicsProps.WOOD);

        register(Blocks.IRON_BLOCK, BlockPhysicsProps.METAL);
        register(Blocks.GOLD_BLOCK, BlockPhysicsProps.METAL);
        register(Blocks.COPPER_BLOCK, BlockPhysicsProps.METAL);
        register(Blocks.CUT_COPPER, BlockPhysicsProps.METAL);
        register(Blocks.COPPER_ORE, BlockPhysicsProps.METAL);
        register(Blocks.IRON_ORE, BlockPhysicsProps.METAL);
        register(Blocks.GOLD_ORE, BlockPhysicsProps.METAL);
        register(Blocks.DIAMOND_BLOCK, new BlockPhysicsProps(7.8, 0.1, 0.0, 1.0, 0.0));
        register(Blocks.NETHERITE_BLOCK, new BlockPhysicsProps(9.0, 0.3, 0.0, 1.0, 0.0));
        register(Blocks.ANVIL, BlockPhysicsProps.METAL);
        register(Blocks.CAULDRON, BlockPhysicsProps.METAL);
        register(Blocks.HOPPER, BlockPhysicsProps.METAL);

        register(Blocks.DIRT, BlockPhysicsProps.DIRT);
        register(Blocks.GRASS_BLOCK, BlockPhysicsProps.DIRT);
        register(Blocks.PODZOL, BlockPhysicsProps.DIRT);
        register(Blocks.MYCELIUM, BlockPhysicsProps.DIRT);
        register(Blocks.SAND, new BlockPhysicsProps(1.5, 0.9, 0.0, 1.0, 0.0));
        register(Blocks.RED_SAND, new BlockPhysicsProps(1.5, 0.9, 0.0, 1.0, 0.0));
        register(Blocks.GRAVEL, new BlockPhysicsProps(2.0, 0.8, 0.0, 1.0, 0.0));
        register(Blocks.CLAY, BlockPhysicsProps.DIRT);

        register(Blocks.GLASS, BlockPhysicsProps.GLASS);
        register(Blocks.GLASS_PANE, BlockPhysicsProps.GLASS);
        register(Blocks.ICE, BlockPhysicsProps.GLASS);
        register(Blocks.BLUE_ICE, BlockPhysicsProps.GLASS);
        register(Blocks.PACKED_ICE, BlockPhysicsProps.GLASS);
        register(Blocks.FROSTED_ICE, BlockPhysicsProps.GLASS);

        register(Blocks.SLIME_BLOCK, BlockPhysicsProps.SLIME);
        register(Blocks.HONEY_BLOCK, BlockPhysicsProps.SLIME);

        register(Blocks.SPONGE, BlockPhysicsProps.SPONGE);
        register(Blocks.WET_SPONGE, BlockPhysicsProps.SPONGE);
        register(Blocks.WHITE_WOOL, BlockPhysicsProps.SPONGE);

        // 升力/浮力方块（飞船/船体语义）
        register(Blocks.SHULKER_BOX, BlockPhysicsProps.LIFT);
        register(Blocks.WHITE_SHULKER_BOX, BlockPhysicsProps.LIFT);
        register(Blocks.PISTON, BlockPhysicsProps.METAL);
        register(Blocks.STICKY_PISTON, BlockPhysicsProps.METAL);
    }

    /** 注册一个方块类型的材质（幂等覆盖；注册表只增，id 递增）。 */
    public static synchronized void register(final Block block, final BlockPhysicsProps props) {
        final int id = PROPS.size() + 1;
        PROPS.add(props);
        BY_BLOCK.put(block, id);
    }

    /** 按 ResourceLocation 注册（扩展/数据驱动入口）。 */
    public static synchronized void register(final ResourceLocation id,
                                             final BlockPhysicsProps props) {
        BY_ID.put(id, registerOrAppend(props));
    }

    /** 追加材质 → 返回 materialId（= 索引 + 1）。 */
    private static int registerOrAppend(final BlockPhysicsProps props) {
        PROPS.add(props);
        return PROPS.size();
    }

    /** BlockState → materialId（未注册 → 默认材质 id=1）。主线程构图查（每方块一次）。 */
    public static int idOf(final BlockState state) {
        if (state == null) return 1;
        return idOf(state.getBlock());
    }

    /** Block → materialId（未注册 → 默认材质 id=1）。 */
    public static int idOf(final Block block) {
        if (block == null) return 1;
        final Integer id = BY_BLOCK.get(block);
        return id != null ? id : 1;
    }

    /** 按 ResourceLocation 查 materialId（未注册 → 默认材质 id=1；扩展/数据驱动入口）。 */
    public static int idOf(final ResourceLocation id) {
        if (id == null) return 1;
        final Integer v = BY_ID.get(id);
        return v != null ? v : 1;
    }

    /** materialId → 属性（越界/0 → DEFAULT）。 */
    public static BlockPhysicsProps propsOf(final int materialId) {
        if (materialId <= 0 || materialId > PROPS.size()) return BlockPhysicsProps.DEFAULT;
        return PROPS.get(materialId - 1);
    }

    /** 已注册材质数（含 DEFAULT）。 */
    public static int materialCount() {
        return PROPS.size();
    }
}
