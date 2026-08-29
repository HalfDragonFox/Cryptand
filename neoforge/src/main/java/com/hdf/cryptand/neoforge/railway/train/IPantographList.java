package com.hdf.cryptand.neoforge.railway.train;

import java.util.List;

/**
 * 装配物/车厢的受电弓列表 mixin 接口（移植自 CEE IPantographList，简化去声音）。
 * {@link com.simibubi.create.content.trains.entity.CarriageContraption} 与
 * {@link com.simibubi.create.content.trains.entity.Carriage} 分别混入字段。
 */
public interface IPantographList {
    List<TrainPantographEntry> getPantographList();

    void setPantographList(List<TrainPantographEntry> pantographs);
}