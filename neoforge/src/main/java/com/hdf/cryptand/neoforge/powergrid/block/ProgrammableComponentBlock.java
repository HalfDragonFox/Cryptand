/**
 * ===== 可编程元件方块 =====
 *
 * 通用元件库容器方块：4 个 CONNECTOR 端子（北/南/西/东）。
 * 点击端子区域放行接线；点击其他区域打开元件库选择界面。
 * 外观复用 powergrid 电阻模型（blockstate 引用）。
 */

package com.hdf.cryptand.neoforge.powergrid.block;

import com.hdf.cryptand.neoforge.powergrid.ui.ProgrammableUi;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
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
 * LDLib2 方块 UI：右键打开元件库选择（现代化界面）。
 */
public class ProgrammableComponentBlock extends SurfaceElectricBlock
        implements IBE<ProgrammableComponentBlockEntity>, BlockUIMenuType.BlockUI {

    /** 4 个 CONNECTOR 端子：北/南/西/东 */
    // ⚠ PowerGrid 0.6.0.1 重做电阻模型：北/南端照抄 AbstractResistorBlock.TERMINALS（权威值），
    //   西/东端按镜像对称布局（新模型纵跨 y=4..6、居中 x/z=7..9）保持四向一致。
    private static final TerminalBoundingBox[] TERMINALS = {
            new TerminalBoundingBox(IDecoratedTerminal.CONNECTOR, 7, 4, 0.5, 9, 6, 3),
            new TerminalBoundingBox(IDecoratedTerminal.CONNECTOR, 7, 4, 13, 9, 6, 15.5),
            new TerminalBoundingBox(IDecoratedTerminal.CONNECTOR, 0.5, 4, 7, 3, 6, 9),
            new TerminalBoundingBox(IDecoratedTerminal.CONNECTOR, 13, 4, 7, 15.5, 6, 9)
    };

    private static final VoxelShape SHAPE =
            Shapes.box(3 / 16f, 2 / 16f, 3 / 16f, 13 / 16f, 14 / 16f, 13 / 16f);

    public ProgrammableComponentBlock(Properties properties) {
        super(properties);
        setTerminalCollection(surfaceTerminals(this, TERMINALS, SHAPE, SHAPE));
    }

    @Override
    public Class<ProgrammableComponentBlockEntity> getBlockEntityClass() {
        return ProgrammableComponentBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends ProgrammableComponentBlockEntity> getBlockEntityType() {
        return CreativeSources.PROGRAMMABLE_COMPONENT_BE.get();
    }

    /** 点击端子区域 → 放行接线（不打开 UI）；其他区域 → 打开元件库配置界面 */
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

    /** LDLib2 BlockUI：创建可编程元件库选择 UI */
    @Override
    public ModularUI createUI(BlockUIHolder holder) {
        ProgrammableComponentBlockEntity be = holder.player.level().getBlockEntity(holder.pos)
                instanceof ProgrammableComponentBlockEntity e ? e : null;
        return ProgrammableUi.build(holder, be);
    }
}
