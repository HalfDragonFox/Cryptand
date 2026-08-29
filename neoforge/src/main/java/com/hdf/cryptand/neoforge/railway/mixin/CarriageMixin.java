package com.hdf.cryptand.neoforge.railway.mixin;

import com.hdf.cryptand.neoforge.railway.pantograph.PantographType;
import com.hdf.cryptand.neoforge.railway.train.ElectricTrainData;
import com.hdf.cryptand.neoforge.railway.train.ICEETrainExtension;
import com.hdf.cryptand.neoforge.railway.train.IPantographList;
import com.hdf.cryptand.neoforge.railway.train.TrainPantographEntry;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Carriage 受电弓列表（移植自 CEE CarriageMixin，简化去声音/蓄电池）。
 * <p>
 * 装配（setContraption）时从装配物拷受电弓；村庄持久化（read/write）恢复受电弓；
 * 挂载到列车（setTrain/CarriageContraptionEntity）时并入列车 ElectricTrainData，
 * 供未来（M3b）随车触头接入 Cryptand 自管接触网。
 */
@Mixin(Carriage.class)
public class CarriageMixin implements IPantographList {
    @Unique
    public List<TrainPantographEntry> cryptand$pantographs = new ArrayList<>();

    @Shadow
    public Train train;

    @Override
    public void setPantographList(List<TrainPantographEntry> list) {
        this.cryptand$pantographs = list;
    }

    @Override
    public List<TrainPantographEntry> getPantographList() {
        return cryptand$pantographs;
    }

    @Inject(method = "read(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;"
            + "Lcom/simibubi/create/content/trains/graph/TrackGraph;"
            + "Lcom/simibubi/create/content/trains/graph/DimensionPalette;)"
            + "Lcom/simibubi/create/content/trains/entity/Carriage;",
            at = @At("RETURN"), remap = false)
    private static void cryptand$read(CompoundTag tag, HolderLookup.Provider registries,
                                      TrackGraph graph, DimensionPalette dimensions,
                                      CallbackInfoReturnable<Carriage> cir) {
        Carriage carriage = cir.getReturnValue();
        List<TrainPantographEntry> pantographs = new ArrayList<>();
        NBTHelper.iterateCompoundList(tag.getList("CryptandPantographs", Tag.TAG_COMPOUND), pt -> {
            BlockPos rotatedPos = NBTHelper.readBlockPos(pt, "RotatedPos");
            BlockPos originalPos = NBTHelper.readBlockPos(pt, "OriginalPos");
            boolean forward = pt.getBoolean("Forward");
            boolean active = pt.getBoolean("Active");
            int typeIdx = pt.getInt("Type");
            PantographType type = typeIdx == 1 ? PantographType.DOUBLE : PantographType.STANDARD;
            pantographs.add(new TrainPantographEntry(originalPos, rotatedPos, type, active, forward));
        });
        ((IPantographList) carriage).setPantographList(pantographs);
    }

    @Inject(method = "setContraption", at = @At("TAIL"), remap = false)
    public void cryptand$setContraption(Level level, CarriageContraption contraption, CallbackInfo ci) {
        this.cryptand$pantographs = ((IPantographList) contraption).getPantographList();
        if (train == null) return;
        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
        for (TrainPantographEntry e : this.cryptand$pantographs) {
            if (!data.pantographs.contains(e)) data.pantographs.add(e);
        }
    }

    @Inject(method = "write(Lcom/simibubi/create/content/trains/graph/DimensionPalette;"
            + "Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("RETURN"), remap = false)
    public void cryptand$write(DimensionPalette dimensions, HolderLookup.Provider registries,
                               CallbackInfoReturnable<CompoundTag> cir) {
        CompoundTag tag = cir.getReturnValue();
        ListTag list = new ListTag();
        for (TrainPantographEntry e : cryptand$pantographs) {
            CompoundTag pt = new CompoundTag();
            if (e.rotatedPos != null) pt.put("RotatedPos", NbtUtils.writeBlockPos(e.rotatedPos));
            if (e.originalPos != null) pt.put("OriginalPos", NbtUtils.writeBlockPos(e.originalPos));
            pt.putBoolean("Forward", e.facingForward);
            pt.putBoolean("Active", e.active);
            pt.putInt("Type", e.type == PantographType.DOUBLE ? 1 : 0);
            list.add(pt);
        }
        tag.put("CryptandPantographs", list);
    }

    @Inject(method = "setTrain", at = @At("TAIL"), remap = false)
    public void cryptand$setTrain(Train train, CallbackInfo ci) {
        if (train == null) return;
        ElectricTrainData data = ((ICEETrainExtension) train).getElectricTrainData();
        for (TrainPantographEntry e : this.cryptand$pantographs) {
            if (!data.pantographs.contains(e)) data.pantographs.add(e);
        }
    }
}