package com.hdf.cryptand.neoforge.railway.mixin;

import com.hdf.cryptand.neoforge.railway.pantograph.IPantographBlock;
import com.hdf.cryptand.neoforge.railway.pantograph.PantographBlock;
import com.hdf.cryptand.neoforge.railway.train.IPantographList;
import com.hdf.cryptand.neoforge.railway.train.TrainPantographEntry;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * CarriageContraption 装配识别受电弓（移植自 CEE CarriageContraptionMixin）。
 * <p>
 * 装配每个方块时（capture TAIL）：若方块实现 {@link IPantographBlock} → 记录
 * TrainPantographEntry（受电弓类型/朝向/本地 pos）。装配后随车移动渲染由
 * {@link com.hdf.cryptand.neoforge.railway.pantograph.PantographMovementBehaviour} 承担。
 */
@Mixin(CarriageContraption.class)
public abstract class CarriageContraptionMixin extends Contraption implements IPantographList {
    @Unique
    public List<TrainPantographEntry> cryptand$pantographs = new ArrayList<>();

    @Override
    public void setPantographList(List<TrainPantographEntry> list) {
        this.cryptand$pantographs = list;
    }

    @Override
    public List<TrainPantographEntry> getPantographList() {
        return cryptand$pantographs;
    }

    @Accessor("assemblyDirection")
    abstract Direction cryptand$getAssemblyDirection();

    @Inject(method = "capture", at = @At("TAIL"), remap = false)
    public void cryptand$capture(Level level, BlockPos pos,
                                 CallbackInfoReturnable<?> cir) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof IPantographBlock pb))
            return;
        Direction facing = state.getValue(PantographBlock.FACING);
        Direction assemblyDirection = this.cryptand$getAssemblyDirection();
        if (pb.isSidewaysPantograph())
            return; // 侧装受电弓（第三轨）暂未实现
        cryptand$pantographs.add(new TrainPantographEntry(toLocalPos(pos),
                toLocalPos(pos).rotate(
                        assemblyDirection == Direction.NORTH ? Rotation.COUNTERCLOCKWISE_90 :
                        assemblyDirection == Direction.EAST ? Rotation.CLOCKWISE_180 :
                        assemblyDirection == Direction.SOUTH ? Rotation.CLOCKWISE_90 : Rotation.NONE),
                pb.getPantographType(state), true, facing == assemblyDirection));
    }
}