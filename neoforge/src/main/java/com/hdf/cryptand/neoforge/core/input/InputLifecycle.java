package com.hdf.cryptand.neoforge.core.input;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.gameinput.GameInputManager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * 通用游戏输入后端生命周期接线（2026-09-08，替代原 WheelLifecycle）：
 * 服务端停止（退出游戏）→ {@link GameInputManager#DEFAULT#shutdown()} 遍历关闭全部
 * 已申请输入设备（释放 DirectInput/罗技 SDK 句柄与力反馈效果）。
 */
public final class InputLifecycle {

    private InputLifecycle() {
    }

    @SubscribeEvent
    public static void onServerStopping(final ServerStoppingEvent ev) {
        GameInputManager.DEFAULT.shutdown();
    }
}