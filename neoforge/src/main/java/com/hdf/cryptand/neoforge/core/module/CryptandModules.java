/**
 * ===== 模块注册/分发器（2026-08-24，用户模块化） =====
 *
 * 主类（CryptandNeoForge）只调用 registerAll/tickAll 两个入口；
 * 每个功能子包的主类 XxxModule 自持 shouldLoad() 门控——不支持的模块
 * 自行跳过，主类无需逐个感知；Mixin 由各功能 XxxMixinPlugin 子管理。
 */

package com.hdf.cryptand.neoforge.core.module;

import com.hdf.cryptand.neoforge.aeronautics.AeronauticsModule;
import com.hdf.cryptand.neoforge.cee.CeeModule;
import com.hdf.cryptand.neoforge.create.CreateModule;
import com.hdf.cryptand.neoforge.powergrid.PowergridModule;
import com.hdf.cryptand.neoforge.railway.RailwayModule;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class CryptandModules {

    private CryptandModules() {
    }

    /** 注册全部模块（每个模块 shouldLoad() 自门控） */
    public static void registerAll(IEventBus bus) {
        mod(PowergridModule::register, PowergridModule::shouldLoad, bus);
        mod(RailwayModule::register, RailwayModule::shouldLoad, bus);
        mod(CeeModule::register, CeeModule::shouldLoad, bus);
        mod(CreateModule::register, CreateModule::shouldLoad, bus);
        mod(AeronauticsModule::register, AeronauticsModule::shouldLoad, bus);
    }

    /** 服务端 tick 分发（各模块内部自持门控） */
    public static void tickAll(ServerLevel level) {
        PowergridModule.tick(level);
        RailwayModule.tick(level);
        CeeModule.tick(level);
        CreateModule.tick(level);
        AeronauticsModule.tick(level);
    }

    private static void mod(Consumer<IEventBus> register, BooleanSupplier enabled,
                            IEventBus bus) {
        try {
            if (enabled.getAsBoolean()) {
                register.accept(bus);
            }
        } catch (Throwable ignored) {
        }
    }
}
