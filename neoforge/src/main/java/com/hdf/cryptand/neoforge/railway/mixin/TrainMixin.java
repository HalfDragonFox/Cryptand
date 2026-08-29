package com.hdf.cryptand.neoforge.railway.mixin;

import com.hdf.cryptand.neoforge.railway.train.ElectricTrainData;
import com.hdf.cryptand.neoforge.railway.train.ICEETrainExtension;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import com.simibubi.create.content.trains.graph.TrackGraph;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Map;
import java.util.UUID;

/**
 * Train 电气数据扩展（移植自 CEE TrainMixin，简化去声音）。
 * 用 MixinExtras {@link WrapMethod} 以兼容 Create 自身对 write/read 的包装：
 * 在列车归档时持久化 Cryptand 电气数据（蓄电池计数/充电量/受电弓母线电压）。
 */
@Mixin(Train.class)
public class TrainMixin implements ICEETrainExtension {
    @Unique
    public ElectricTrainData cryptand$electricTrainData = new ElectricTrainData();

    @Override
    public ElectricTrainData getElectricTrainData() {
        return cryptand$electricTrainData;
    }

    @WrapMethod(method = "write")
    public CompoundTag cryptand$write(DimensionPalette dimensions,
                                      HolderLookup.Provider registries,
                                      @NotNull Operation<CompoundTag> original) {
        CompoundTag tag = original.call(dimensions, registries);
        ElectricTrainData d = cryptand$electricTrainData;
        tag.putInt("CryptandAccumulators", d.accumulators);
        tag.putDouble("CryptandAccumulatorCharge", d.accumulatorCharge);
        tag.putDouble("CryptandLastVoltage", d.lastVoltage);
        return tag;
    }

    @WrapMethod(method = "read")
    private static Train cryptand$read(CompoundTag tag, HolderLookup.Provider registries,
                                       Map<UUID, TrackGraph> trackNetworks,
                                       DimensionPalette dimensions,
                                       @NotNull Operation<Train> original) {
        Train t = original.call(tag, registries, trackNetworks, dimensions);
        ElectricTrainData d = ((ICEETrainExtension) t).getElectricTrainData();
        d.accumulators = tag.getInt("CryptandAccumulators");
        d.accumulatorCharge = tag.getDouble("CryptandAccumulatorCharge");
        d.lastVoltage = tag.getDouble("CryptandLastVoltage");
        return t;
    }
}