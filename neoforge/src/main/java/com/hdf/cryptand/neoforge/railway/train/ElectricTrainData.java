package com.hdf.cryptand.neoforge.railway.train;

import java.util.ArrayList;
import java.util.List;

/**
 * 列车电气数据（移植自 CEE ElectricTrainData，剥离 CEE 模拟库：
 * 无 WireSimulationState.cut/AttachedNode——触点接入由 Cryptand 自管网承担）。
 * <p>
 * 当前承载：受电弓列表（装配时收集）+ 蓄电池计数/充电量占位（M3b 列车电网）。
 * 列车受电弓触点接入 Cryptand 自管接触网（buildContextFromGraph 滑触头拆分）
 * 属于 M3b：需要 engine 侧"列车网络对象"的动态建模。
 */
public class ElectricTrainData {
    public List<TrainPantographEntry> pantographs = new ArrayList<>();
    public int accumulators = 0;
    public double accumulatorCharge = 0d;
    public double lastVoltage = 0d;
}