package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import com.hdf.cryptand.neoforge.waterphysics.config.ConfigWaterphysics;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/**
 * ===== waterphysics（流体物理）子包入口 =====
 *
 * <p>有限水量水流物理：水位存 side table（common 的 WaterLevelField），求解在 common 纯 Java
 * 完成，主线程只按预算采集点名格（读上限 maxWorkPerTick，单格粒度）
 * 与写回变更（写上限 writeBudgetPerTick）。
 *
 * <p>照 IncEntry 写：配置注册必须在 {@link #registerConfigs()}（框架在 consumeConfigs 之前调用），
 * 写在 init() 里会导致 toml 永不加载。
 */
@CryptandSubpackage(id = "waterphysics", order = 21)
public final class WaterphysicsEntry implements SubpackageEntry {

    public static final WaterphysicsEntry INSTANCE = new WaterphysicsEntry();

    private WaterphysicsEntry() {
    }

    @Override
    public void registerConfigs() {
        ConfigWaterphysics.register();
    }

    @Override
    public boolean enabled() {
        try {
            return ConfigWaterphysics.SPEC.isLoaded()
                    ? ConfigWaterphysics.ENABLE_WATERPHYSICS.get()
                    : ConfigLoad.preloadBoolean("waterphysics", "enableWaterphysics", true);
        } catch (final Throwable t) {
            return true;
        }
    }

    @Override
    public String conditionDesc() {
        return "waterphysics.toml#enableWaterphysics";
    }

    @Override
    public void init(IEventBus bus) {
        WaterPhysicsModule.register(bus);
    }

    @Override
    public void tick(ServerLevel level) {
        WaterPhysicsModule.tick(level);
    }

    @Override
    public java.util.List<String> mixinConfigs() {
        return java.util.List.of("cryptand.waterphysics.mixins.json");
    }
}
