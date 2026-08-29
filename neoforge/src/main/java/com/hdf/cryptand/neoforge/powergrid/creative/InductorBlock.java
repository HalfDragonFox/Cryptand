/**
 * ===== 电感方块 =====
 *
 * 复制 PowerGrid 功率电阻（SurfaceElectricBlock 表面安装，2 个 CONNECTOR 端子），
 * 模型暂用功率电阻模型（powergrid:block/resistor_h / resistor_v）。
 * 注意 Shapes.box 需 0~1 归一化坐标（PowerGrid 原版用 0~16 像素值导致碰撞箱巨大，此处已修正）。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.SurfaceElectricBlock;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * LDLib2 方块 UI：右键打开电感数值/单位配置（现代化界面）。
 */
public class InductorBlock extends SurfaceElectricBlock implements IBE<InductorBlockEntity>, BlockUIMenuType.BlockUI {

    // ⚠ PowerGrid 0.6.0.1 重做电阻模型（resistor_h/v），引脚从旧 (x=6..10,y=7..9,z-face)
    //   移动到新位置。照抄 0.6.0.1 AbstractResistorBlock.TERMINALS（权威值，保证对齐新模型引脚）。
    //   surfaceTerminals 基方向 = facing=DOWN（resistor_v），其他朝向自动旋转跟随模型。
    private static final TerminalBoundingBox[] TERMINALS = {
            new TerminalBoundingBox(IDecoratedTerminal.CONNECTOR, 7, 4, 0.5, 9, 6, 3),
            new TerminalBoundingBox(IDecoratedTerminal.CONNECTOR, 7, 4, 13, 9, 6, 15.5)
    };

    // ⚠ PowerGrid 0.6.0.1 AbstractResistorBlock 的紧凑单盒碰撞箱（照抄权威值）。
    //   旧版多盒 SHAPE 延伸到 z=0/整块 → ①碰撞框比模型大 ②碰撞面(z=0)落在
    //   端子盒(z=0.5..3)之外 → 无法选中接线端子。改用单盒后两问题同时解决。
    private static final VoxelShape SHAPE1 = Shapes.box(5 / 16f, 2 / 16f, 3 / 16f, 11 / 16f, 8 / 16f, 13 / 16f);
    private static final VoxelShape SHAPE2 = Shapes.box(3 / 16f, 2 / 16f, 5 / 16f, 13 / 16f, 8 / 16f, 11 / 16f);

    public InductorBlock(Properties properties) {
        super(properties);
        setTerminalCollection(surfaceTerminals(this, TERMINALS, SHAPE1, SHAPE2));
    }

    @Override
    public Class<InductorBlockEntity> getBlockEntityClass() {
        return InductorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends InductorBlockEntity> getBlockEntityType() {
        return CreativeSources.INDUCTOR_BE.get();
    }

    /** 点击端子区域 → 放行接线（不打开 UI）；其他区域 → 打开配置 UI（LDLib2） */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        net.minecraft.world.phys.Vec3 local = hit.getLocation().subtract(
                pos.getX(), pos.getY(), pos.getZ());
        if (terminalIndexAt(state, local) >= 0) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer sp) {
            BlockUIMenuType.openUI(sp, pos);
        }
        return InteractionResult.CONSUME;
    }

    /** LDLib2 BlockUI：创建电感数值/单位配置 UI */
    @Override
    public ModularUI createUI(BlockUIHolder holder) {
        ConfigurableComponent be = holder.player.level().getBlockEntity(holder.pos)
                instanceof ConfigurableComponent c ? c : null;
        return com.hdf.cryptand.neoforge.core.ui.ValueUnitUi.build(holder, false, be);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        BlockState state = super.getStateForPlacement(ctx);
        if (state == null) return null;
        return state.cycle(ALONG_FIRST_AXIS);   // 与功率电阻一致
    }
}
