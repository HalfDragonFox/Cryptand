/**
 * ===== 组装器缓存化分支 2：组装器 → BE（组装器只写） =====
 *
 * 方向：组装器 → BE 模型。后台组装器/引擎模型把【计算结果】原子写【输出槽】
 * （DeviceCache.SLOT_OUT）；主线程每 tick {@link #applyCacheToBe} 读输出槽 →
 * 应用到 BE（视觉/物理/状态同步）。
 *
 * 适用：引擎算出的转速、温度、电压、电流等"组装器提供、BE 消费"的结果数据。
 */
package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCache;
import net.minecraft.world.level.block.entity.BlockEntity;

public interface SinkCacheAssembler extends CacheAssembler {

    /** 主线程每 tick：读【输出槽】→ 应用到 BE（视觉/物理/状态同步） */
    void applyCacheToBe(BlockEntity be, DeviceCache cache);
}
