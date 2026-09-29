/**
 * ===== CryptandSable 客户端 tick 驱动（2026-09-07） =====
 *
 * 承载原 ClientSoundTicker（core）中的亚层渲染 payload 批处理驱动——子包隔离：
 * core 零引用子包，SableClientRenderModule.tick() 由本子包自持驱动（core 关闭 →
 * 零行为；子包删除 → 本类随之消失，core 不受影响）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.client;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.cryptandsable.client.render.SableClientRenderModule;
import com.hdf.cryptand.neoforge.cryptandsable.config.ConfigCryptandSable;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class SableClientTickDriver {

    private SableClientTickDriver() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // core 总闸：关闭 → 零行为（渲染缓存本就为空）
        if (!ConfigCryptandSable.ENABLE_CRYPTAND_SABLE_CORE.get()) {
            return;
        }
        // 亚层渲染 payload 批处理（物理化装载/卸载；避免 payload handler 主线程卡）
        SableClientRenderModule.INSTANCE.tick();
    }
}