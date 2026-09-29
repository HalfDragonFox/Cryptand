package com.hdf.cryptand.fabric;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.gameinput.GameInputConfig;
import com.hdf.cryptand.gameinput.GameInputManager;
import com.hdf.cryptand.gameinput.GameInputProvider;
import com.hdf.cryptand.serial.SerialManager;
import com.hdf.cryptand.serial.SerialPortProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public class CryptandFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Cryptand.init();
        // 通用游戏输入门控注入 + 后端注册（fabric 无 TOML 配置体系：总开关默认开；
        // 唯一后端 SDL2，可用 -Dcryptand.disableGameInput=true 关闭——neoforge 走 core 域配置）
        GameInputConfig.configure(!Boolean.getBoolean("cryptand.disableGameInput"));
        GameInputProvider.load();          // 按配置注册 SDL2 后端（懒加载）
        SerialPortProvider.load();         // 串口子包就绪（懒加载）
        // 退出游戏（服务端停止）→ 遍历关闭全部已申请串口/游戏输入实例（2026-09-08 硬件对接库管理类）
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            SerialManager.DEFAULT.shutdown();
            GameInputManager.DEFAULT.shutdown();
        });
    }
}
