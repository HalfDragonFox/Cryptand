package com.hdf.cryptand.neoforge.soc.block;

import com.hdf.cryptand.neoforge.soc.ui.AdvancedAnalyzerPanel;
import com.hdf.cryptand.neoforge.soc.ui.AdvancedAnalyzerPanelData;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * ===== 高级分析器方块（2026-09-27）=====
 *
 * <p>形态对齐 OC 原版 Analyzer：**右键打开面板**。区别是 OC 原版只能看到**方块级别**的信息，
 * 而且这里的读数是**活的**（挂着 {@code getTicker}：服务端每秒重采一次，内容有变化才推给客户端），
 * 这一台看的是**沙箱内部**（设备表、各设备缓存、内存占用、消息缓存区、执行状态）——
 * 数据全部来自 common 的 {@code SandboxInspect}，面板与无人化 MCP 工具 {@code soc_inspect}
 * 共用同一份快照（面板里绝不另算一套）。</p>
 *
 * <h3>它分析谁</h3>
 * <p>右键时**重扫**一次目标：先看 6 个邻居，再在半径 {@value #SCAN_RADIUS} 内找最近的 OC 机器
 * （机箱 / 服务器）。找到就把它记为本次目标；找不到就把目标清空 —— 面板会如实显示
 * "没找到 OC 机器"，而不是沿用上一次的目标（那会让人以为在看 A 机器、其实数据来自 B）。</p>
 *
 * <p>⚠ 本类**不引用 OC 的任何类型**：目标解析在 {@code AdvancedAnalyzerPanelProbe}（那里的
 * 第一件事是 {@code OpenComputersEntry.ocLoaded()} 软判），因此 OC 缺席时本方块照常存在、
 * 面板照常能打开（所有分区显示占位符）。</p>
 */
public class AdvancedAnalyzerBlock extends HorizontalDirectionalBlock implements EntityBlock,
        BlockUIMenuType.BlockUI {

    public static final MapCodec<AdvancedAnalyzerBlock> CODEC = simpleCodec(AdvancedAnalyzerBlock::new);

    /** 水平朝向（面板屏幕朝哪边；照 OC Analyzer 的"对着看"的形态） */
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;

    /** 邻居找不到时的扫描半径（方块）；再远就不该算"贴着机箱的分析器"了 */
    public static final int SCAN_RADIUS = 8;

    public AdvancedAnalyzerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends AdvancedAnalyzerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AdvancedAnalyzerBlockEntity(pos, state);
    }

    /**
     * 挂上"每秒刷新"的节拍（用户要求：面板上的数字要真的在动）。
     *
     * <p>⚠ **只挂服务端**（{@code level.isClientSide()} ⇒ 返回 null）：快照的**唯一来源**是服务端
     * 的沙箱（OC 节点/沙箱线程、盘挂载表都在服务端），客户端那份数据是方块实体更新包送过去的
     * —— 客户端自己 tick 也没有东西可采，只会白跑。服务端这边真正的节流与"变化才发"在
     * {@link AdvancedAnalyzerBlockEntity#tick()}（20 tick 一次 + 只在有面板看着时采）。</p>
     */
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        if (level.isClientSide()) {
            return null;
        }
        return (lvl, pos, st, be) -> {
            if (be instanceof AdvancedAnalyzerBlockEntity analyzer) {
                analyzer.tick();
            }
        };
    }

    // ==================== 右键：重扫目标 + 打开面板 ====================

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        return act(level, pos, player);
    }

    /**
     * ⚠ 手持物品右键走的是这个入口（不是 {@code useWithoutItem}）—— 只实现后者的话，
     * 手里拿着东西点分析器会完全没反应（同款坑在 {@code ProgramLoaderBlock} / 旧真彩屏 {@code TrueScreenBlock}（任务 F-2 已删除）
     * 上真机踩过）。
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, net.minecraft.world.InteractionHand hand,
                                              BlockHitResult hit) {
        return act(level, pos, player) == InteractionResult.SUCCESS
                ? ItemInteractionResult.SUCCESS
                : ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /** 唯一的交互入口：服务端重扫目标 → 打开 LDLib2 面板（客户端只回执） */
    private static InteractionResult act(Level level, BlockPos pos, Player player) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof AdvancedAnalyzerBlockEntity be) {
            // 重扫目标 + 采集快照（同一份 SandboxInspect 数据也喂给 soc_inspect）+ 同步到客户端
            be.collect(level, AdvancedAnalyzerPanelData.resolveTarget(level, pos));
        }
        if (player instanceof ServerPlayer sp) {
            BlockUIMenuType.openUI(sp, pos);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        final AdvancedAnalyzerBlockEntity be = holder.player.level().getBlockEntity(holder.pos)
                instanceof AdvancedAnalyzerBlockEntity a ? a : null;
        return AdvancedAnalyzerPanel.build(holder, be);
    }
}
