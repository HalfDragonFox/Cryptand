package com.hdf.cryptand.neoforge.powergrid.engine;

import com.hdf.cryptand.circuitsimulation.cache.AppLink;
import com.hdf.cryptand.circuitsimulation.cache.NetworkWorld;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;

/**
 * MC 应用对话（2026-08-22 用户架构：网络世界通过对话与具体对象交互）。
 * <p>
 * 实现核心 {@link AppLink}（SPI，platform = "mc"）——NetworkWorld 创建时绑定本
 * 对话，缓存就绪/变化/释放时回调 MC 侧：客户端导线图即时同步、诊断日志。
 * 核心保持通用（不依赖 MC），本类只在 neoforge 侧提供 MC 对话实现。
 */
public final class McAppLink implements AppLink {

    public static final McAppLink INSTANCE = new McAppLink();

    private McAppLink() {
    }

    @Override
    public String platform() {
        return "mc";
    }

    @Override
    public void onCacheReady(NetworkWorld world) {
        CryptandNeoForge.WAF_LOGGER.info(
                "[CacheMgr] mc dialogue ready {}", world);
    }

    @Override
    public void onCacheChanged(NetworkWorld world, long version) {
        // 缓存结构变化 → 客户端导线图即时同步（服务端安全时；定期同步仍由
        // convertWires 每 tick 处理，这里只做变化驱动的即时同步补偿）。
        try {
            if (PowerGridWireConverter.isEnabled()) {
                PowerGridWireConverter.syncGraphToClientsNow();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onCacheDisposed(NetworkWorld world) {
        CryptandNeoForge.WAF_LOGGER.info(
                "[CacheMgr] mc dialogue disposed {}", world);
    }
}