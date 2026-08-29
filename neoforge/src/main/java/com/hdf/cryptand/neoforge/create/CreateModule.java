/**
 * ===== Create 联动模块主类（2026-08-24，模块化） =====
 *
 * Create 为必装依赖 → 默认加载；Mixin（KineticBlockEntityMixin）由
 * create.mixin.CreateMixinPlugin 按 enableCreateStressLimit 子管理。
 */

package com.hdf.cryptand.neoforge.create;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

public final class CreateModule {

    private CreateModule() {
    }

    public static boolean shouldLoad() {
        return true;
    }

    public static void register(IEventBus bus) {
    }

    public static void tick(ServerLevel level) {
    }
}
