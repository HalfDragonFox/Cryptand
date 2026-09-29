/**
 * ===== 组装器缓存化分支 1：BE → 组装器（组装器只读） =====
 *
 * 方向：BE 模型 → 组装器。主线程每 tick {@link #refreshCache} 读 BE 字段 →
 * 原子写【输入槽】（DeviceCache.SLOT_IN）；后台组装器 {@link #assembleFromCache}
 * 原子读输入槽构建模型（不碰 level/BE）。
 *
 * 适用：开关状态、变阻器滑片、trim、AC 源参数等"BE 提供、组装器消费"的数据。
 */
package com.hdf.cryptand.neoforge.powergrid.device.cache;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

public interface SourceCacheAssembler extends CacheAssembler {

    /** 主线程每 tick：从 BE 读参数 → 原子写【输入槽】（组装器定义读什么字段） */
    void refreshCache(BlockEntity be, DeviceCache cache);

    /**
     * 后台：从缓存【输入槽】快照构建复合模型（不碰 level/BE）。
     * 返回 null = 走原 {@link #assemble}（BE 路径）或跳过。
     */
    default CompositeModel assembleFromCache(BlockPos pos, DeviceCache cache,
                                             int a, int b, Network net) {
        return null;
    }
}
