/**
 * ===== Aeronautics 联动模块主类（2026-08-24，模块化） =====
 *
 * 可选联动（compileOnly）：未装 create_aeronautics → shouldLoad()=false，
 * 主类无需感知；其 Mixin（暂无）将由 aeronautics 配置子管理。
 */

package com.hdf.cryptand.neoforge.aeronautics;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

public final class AeronauticsModule {

    private AeronauticsModule() {
    }

    public static boolean shouldLoad() {
        return AeronauticsCompat.isLoaded();
    }

    public static void register(IEventBus bus) {
    }

    public static void tick(ServerLevel level) {
    }
}
