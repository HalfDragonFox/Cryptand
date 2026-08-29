package com.hdf.cryptand.neoforge.railway.train;

/**
 * 列车电气数据扩展接口（移植自 CEE ICEETrainExtension，简化去声音）。
 * {@link com.simibubi.create.content.trains.entity.Train} 通过 TrainMixin 混入
 * {@link ElectricTrainData}。
 */
public interface ICEETrainExtension {
    ElectricTrainData getElectricTrainData();
}