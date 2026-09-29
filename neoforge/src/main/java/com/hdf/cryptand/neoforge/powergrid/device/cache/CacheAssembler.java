/**
 * ===== 组装器缓存化分支（2026-08-15 用户设计：三种分支） =====
 *
 * 公共基接口：组装器缓存化，向主控（DeviceCacheRegistry）注册设备缓存引用，
 * 支持【多数据槽】（DeviceCache：槽0=输入 BE→组装器、槽1=输出 组装器→BE、2+扩展）。
 *
 * 三种分支（各自继承本接口）：
 *   - {@link SourceCacheAssembler}：BE→组装器（组装器只读输入槽）
 *   - {@link SinkCacheAssembler}：组装器→BE（组装器只写输出槽）
 *   - {@link BidiCacheAssembler}：双向（两槽都用）
 */
package com.hdf.cryptand.neoforge.powergrid.device.cache;

import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry;
import net.minecraft.core.BlockPos;

/**
 * 组装器缓存化公共基：{@link #cacheFor} 注册/获取设备缓存（默认走主控注册表）。
 */
public interface CacheAssembler extends Assembler {

    /** 获取/注册设备缓存（默认走主控注册表；首次创建，多槽） */
    default DeviceCache cacheFor(BlockPos pos) {
        return DeviceCacheRegistry.register(pos, this);
    }

    /**
     * 后台：从缓存 stamp 基础元件（stamp 模式设备用：仪表/创造源/连接器/电池 DC 源）。
     * 由 builder 在 {@code assembleFromCache} 返回 null 时调用。默认空。
     */
    default void stampFromCache(BlockPos pos, DeviceCache cache, int a, int b,
                                com.hdf.cryptand.circuitsimulation.model.Network net) {
    }
}

