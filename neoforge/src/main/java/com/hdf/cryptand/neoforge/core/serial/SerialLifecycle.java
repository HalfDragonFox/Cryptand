package com.hdf.cryptand.neoforge.core.serial;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.serial.SerialManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 串口对接库生命周期接线（2026-09-08）：
 * 服务端停止（退出游戏）→ {@link SerialManager#DEFAULT#shutdown()} 遍历关闭全部
 * 已申请串口实例（逐个 close 后清空登记列表；此后 acquire 拒绝）。
 */
public final class SerialLifecycle {

    private SerialLifecycle() {
    }

    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent ev) {
        SerialManager.DEFAULT.shutdown();
    }
}