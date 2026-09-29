/**
 * ===== 交流创造源方块（电压源 / 电流源） =====
 *
 * 复制 PowerGrid 创造源方块（HorizontalAxisElectricBlock 接线兼容，
 * 两个端子 HORIZONTAL_AXIS 朝向），改名为交流创造源。
 * 右键打开自定义 UI 设置频率/幅值（默认正弦波）。
 */

package com.hdf.cryptand.neoforge.powergrid.block;

import com.hdf.cryptand.neoforge.powergrid.ui.AcSourceUi;
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
import org.patryk3211.powergrid.electricity.base.HorizontalAxisElectricBlock;
import org.patryk3211.powergrid.electricity.base.IDecoratedTerminal;
import org.patryk3211.powergrid.electricity.base.TerminalBoundingBox;

/**
 * LDLib2 方块 UI：右键打开交流创造源配置（现代化界面）。
 */
public class AcCreativeSourceBlock extends HorizontalAxisElectricBlock implements IBE<AcCreativeSourceBlockEntity>, BlockUIMenuType.BlockUI {

    /**
     * 注意：Shapes.box 的参数是 0.0~1.0 归一化坐标（除以 16），
     * PowerGrid 原版直接写了像素值 (0,0,0,16,2,16)，导致碰撞箱高达 16 格宽，此处已修正。
     */
    private static final VoxelShape SHAPE = Shapes.or(
            Shapes.box(0.0, 0.0, 0.0, 1.0, 2.0 / 16.0, 1.0),
            Shapes.box(1.0 / 16.0, 2.0 / 16.0, 1.0 / 16.0, 15.0 / 16.0, 13.0 / 16.0, 15.0 / 16.0));

    // ⚠ PowerGrid 0.6.0.1 重做了创造源模型（Blockbench 重建）：端子从【旧版沿 Z 轴分离】
    //   改为【新版沿 X 轴分离、顶部 y=14..16、z 居中 6.5..9.5】。
    //   horizontalZTerminals 把传入盒子当作 axis=z 基准（模型无旋转 = 新模型默认方向），
    //   axis=x 时自动 rotateAroundY(90) 跟随模型旋转 —— 坐标必须对齐新模型默认方向。
    private static final TerminalBoundingBox[] TERMINALS = {
            new TerminalBoundingBox(IDecoratedTerminal.POSITIVE, 9.5, 14, 6.5, 12.5, 16, 9.5).withColor(16726843),
            new TerminalBoundingBox(IDecoratedTerminal.NEGATIVE, 3.5, 14, 6.5, 6.5, 16, 9.5).withColor(3899647)
    };

    private final boolean voltageSource;

    public AcCreativeSourceBlock(Properties properties, boolean voltageSource) {
        super(properties);
        this.voltageSource = voltageSource;
        setTerminalCollection(HorizontalAxisElectricBlock.horizontalZTerminals(this, TERMINALS, SHAPE));
    }

    public boolean isVoltageSource() { return voltageSource; }

    @Override
    public Class<AcCreativeSourceBlockEntity> getBlockEntityClass() {
        return AcCreativeSourceBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends AcCreativeSourceBlockEntity> getBlockEntityType() {
        return CreativeSources.AC_CREATIVE_SOURCE_BE.get();
    }

    /**
     * 右键：点击【端子区域】→ 放行给接线逻辑（PowerGrid 导线工具使用，不打开 UI）；
     * 点击【其他区域】→ 打开自定义 UI（设置频率/幅值/相位）。
     * 坐标须转方块局部坐标（与 IElectric.onWire 一致）。
     */
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

    /** LDLib2 BlockUI：创建交流创造源配置 UI */
    @Override
    public ModularUI createUI(BlockUIHolder holder) {
        AcCreativeSourceBlockEntity be = holder.player.level().getBlockEntity(holder.pos)
                instanceof AcCreativeSourceBlockEntity e ? e : null;
        return AcSourceUi.build(holder, be);
    }
}
