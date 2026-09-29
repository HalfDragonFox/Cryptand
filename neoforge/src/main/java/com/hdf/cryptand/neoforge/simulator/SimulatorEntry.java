package com.hdf.cryptand.neoforge.simulator;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/**
 * ===== 仿真器子包入口（2026-08-30 用户架构：core 仅保留注册 lib 功能） =====
 *
 * 用户："所有配置全部放到相关子包——比如导线检测，这个是仿真器子包的就放仿真器
 * 子包（neoforge 没有就新建）"。
 *
 * 本子包承载【电路仿真器】相关配置与（未来）调度：
 *   - ConfigCircuit（求解/转换/拓扑/设备参数/相量/网络/导线检测/爆炸/INC 节拍等
 *     ——原 core/config/ConfigCircuit 整体迁入）
 *   - 引擎（common SimulationCore/NetworkSolver）经 powergrid 适配层驱动；
 *     本子包负责其配置归属（core 不再定义任何仿真配置）。
 */
@CryptandSubpackage(id = "simulator", order = 15)
public final class SimulatorEntry implements SubpackageEntry {

    public static final SimulatorEntry INSTANCE = new SimulatorEntry();

    private SimulatorEntry() {
    }

    @Override
    public void registerConfigs() {
        // 仿真器配置（域 circuit → config/cryptand/circuit-simulation.toml）
        ConfigCircuit.register();
    }

    @Override
    public boolean enabled() {
        // 仿真开关（求解器替换 或 仿真接管任一开启；构造期用预读）
        try {
            if (ConfigCircuit.SPEC.isLoaded()) {
                return ConfigCircuit.ENABLE_CRYPTAND_SIMULATION.get()
                        || ConfigCircuit.ENABLE_CRYPTAND_SOLVER.get();
            }
            return ConfigLoad.preloadBoolean("circuit", "enableCryptandSimulation", true)
                    || ConfigLoad.preloadBoolean("circuit", "enableCryptandSolver", true);
        } catch (final Throwable t) {
            return true;
        }
    }

    @Override
    public String conditionDesc() {
        return "circuit-simulation.toml#enableCryptandSimulation|enableCryptandSolver";
    }

    @Override
    public void init(IEventBus bus) {
        // 本子包为配置/调度归属；引擎实例（SimulationCore）由 powergrid 适配层驱动
    }

    @Override
    public void tick(ServerLevel level) {
    }
}
