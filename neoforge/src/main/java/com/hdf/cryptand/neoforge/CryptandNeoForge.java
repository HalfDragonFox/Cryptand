package com.hdf.cryptand.neoforge;

import com.hdf.cryptand.Cryptand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(Cryptand.MOD_ID)
public class CryptandNeoForge {
    public static final Logger WAF_LOGGER = LogManager.getLogger(Cryptand.MOD_ID);

    // 只保留带 IEventBus 的构造函数，移除无参构造函数
    public CryptandNeoForge(IEventBus modEventBus) {
        // 通用设置事件（服务端和客户端均执行）
        modEventBus.addListener(this::commonSetup);

        // 仅在客户端注册客户端设置事件
        if (FMLEnvironment.dist == Dist.CLIENT) {
//            modEventBus.addListener(ClientEvents::onClientSetup);
        }

        // 如果你有其他初始化逻辑（例如配置、Architectury 事件总线等），可在此继续
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        WAF_LOGGER.info("Cryptand Common Setup Start");
        // 你的通用初始化代码（例如 Config 加载、事件监听等）
        WAF_LOGGER.info("Cryptand Common End");
    }
}