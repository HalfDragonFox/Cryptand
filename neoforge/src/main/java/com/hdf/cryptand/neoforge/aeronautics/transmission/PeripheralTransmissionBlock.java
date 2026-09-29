/**
 * ===== 外设模拟传动器方块（2026-09-14）=====
 *
 * 复制原版 `simulated:analog_transmission` 的方块形态（`RotatedPillarKineticBlock`：沿 AXIS 的传动轴）。
 *
 * <p>与原版的两点差异，都是用户 2026-09-14 定稿的后果：
 * <ol>
 *   <li><b>不用 `ExtraKinetics` 双 KBE</b>：那套机制要 hook {@code RotationPropagator}（本质是 Mixin），
 *       而用户要求"尽量不要 mixin 防止冲突"。改用 Create 原生的分轴机制
 *       （{@link com.simibubi.create.content.kinetics.transmission.SplitShaftBlockEntity}）——
 *       见 BE 里的 {@code getRotationSpeedModifier}，语义等价且零侵入；</li>
 *   <li><b>没有 `POWERED` 红石属性</b>：传动比不再来自红石信号，而来自外部设备轴 → 曲线。</li>
 * </ol>
 *
 * <p>右键打开绑定界面（LDLib2，与外设船舵/拉杆/栏杆一致）。
 */
package com.hdf.cryptand.neoforge.aeronautics.transmission;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType.BlockUIHolder;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.mojang.serialization.MapCodec;
import com.simibubi.create.AllItems;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public class PeripheralTransmissionBlock extends RotatedPillarKineticBlock
        implements IBE<PeripheralTransmissionBlockEntity>, BlockUIMenuType.BlockUI,
        dev.simulated_team.simulated.util.extra_kinetics.ExtraKinetics.ExtraKineticsBlock {

    public static final MapCodec<PeripheralTransmissionBlock> CODEC =
            simpleCodec(PeripheralTransmissionBlock::new);

    public PeripheralTransmissionBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends RotatedPillarKineticBlock> codec() {
        return CODEC;
    }

    /** 传动轴沿 AXIS */
    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public boolean hasShaftTowards(LevelReader world, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS);
    }

    /** 右键 = 打开绑定/曲线界面（扳手交给 Create 处理） */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        if (AllItems.WRENCH.isIn(player.getMainHandItem())) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlockUIMenuType.openUI(serverPlayer, pos);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public ModularUI createUI(BlockUIHolder holder) {
        PeripheralTransmissionBlockEntity be = holder.player.level().getBlockEntity(holder.pos)
                instanceof PeripheralTransmissionBlockEntity transmission ? transmission : null;
        return PeripheralTransmissionUi.build(holder, be);
    }

    /**
     * 附加动力学节点的机械形态（原版同款）：告诉 Create 的传播器"这个坐标上的第二个 KBE
     * 是沿 AXIS 的齿轮形态"，从而让"传动杆"与"齿轮"在两个节点上表现不同。
     */
    @Override
    public com.simibubi.create.content.kinetics.base.IRotate getExtraKineticsRotationConfiguration() {
        return PeripheralTransmissionBlockEntity.Cogwheel.EXTRA_COGWHEEL_CONFIG;
    }

    @Override
    public Class<PeripheralTransmissionBlockEntity> getBlockEntityClass() {
        return PeripheralTransmissionBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends PeripheralTransmissionBlockEntity> getBlockEntityType() {
        return PeripheralTransmissionRegistry.PERIPHERAL_TRANSMISSION_BE.get();
    }
}
