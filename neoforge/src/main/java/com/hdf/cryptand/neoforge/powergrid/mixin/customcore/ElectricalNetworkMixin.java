/**
 * ===== 替换 PowerGrid 的 MNA 求解器为 Cryptand 自研引擎 =====
 *
 * 拦截 ElectricalNetwork 的两个构造器，在 mna 字段赋值完成后，
 * 用 CryptandMna（内部走 common 引擎 DenseRealLU）替换之。
 *
 * 本 Mixin 始终注入（见 CryptandMixinPlugin），替换与否完全由
 * 运行时配置 enableCryptandSolver 独立控制：
 *   - enableCryptandSolver=true  → 用自研引擎替换 MNA 求解（打开即生效）
 *   - enableCryptandSolver=false → 保持 PowerGrid 原求解器，完全原版行为
 * 该开关与 PowerGrid 多线程独立（多线程可独立开关；旧 enablePowergridAsyncNetwork
 * 已废除，2026-08-22）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.customcore;

import com.hdf.cryptand.neoforge.powergrid.adapter.CryptandMna;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.solver.IMNA;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

@Mixin(value = ElectricalNetwork.class, remap = false)
public abstract class ElectricalNetworkMixin {

    @Shadow
    private IMNA mna;

    /** 构造器1：默认 JavaMNA */
    @Inject(method = "<init>(Z)V", at = @At("RETURN"))
    private void cryptand$installSolver1(boolean addGMin, CallbackInfo ci) {
        cryptand$maybeReplaceMna();
    }

    /** 构造器2：外部传入后端工厂（CSolver.solverBackend 的 constructor） */
    @Inject(method = "<init>(ZLjava/util/function/Function;)V", at = @At("RETURN"))
    private void cryptand$installSolver2(boolean addGMin, Function<ElectricalNetwork, IMNA> factory,
                                         CallbackInfo ci) {
        cryptand$maybeReplaceMna();
    }

    @Unique
    private void cryptand$maybeReplaceMna() {
        if (!ConfigLoad.ENABLE_CRYPTAND_SOLVER.get()) return;   // 关闭 → 保持 PowerGrid 原求解器
        this.mna = new CryptandMna((ElectricalNetwork) (Object) this);
    }

    // ==================== 拓扑事件（消息驱动，2026-08-11） ====================
    // 导线接入/移除/网络合并 → CryptandTopologyManager 收到消息 →
    // 异步模式下强制下一轮立即重算（不用等频率心跳）。
    // 只在 Cryptand 求解启用时发消息；原版模式不发（不影响原版行为）。

    /** 导线接入网络 wires（新接线/方块放置/网络合并转移）。
     *  ⚠ 过滤 SwitchedWire（开关内部触点 wire）：开关断开/闭合的 rebuildCircuit
     *  会 add/remove SwitchedWire——但开关状态已由 SwitchAssembler 参数模型（大电阻）
     *  表达，触发重建反而导致高频开关每次全量重建（性能 + 电压瞬态跳变）。
     *  2026-08-12【无感重建】：@Inject 处理器签名【不允许把接收者 this 作为参数】
     *  （Mixin 内部自动绑定 this；加了会 InvalidInjectionException → 启动崩溃，
     *  2026-08-13 实锤）。方法体内 this 即目标网络实例 → postWireConnect(this)
     *  网络级失效，只重建本网络，其他网络缓存命中不卡顿。 */
    @Inject(method = "addWire", at = @At("TAIL"))
    private void cryptand$topoWireAdd(AbstractElectricWire wire, CallbackInfo ci) {
        try {
            if (ConfigLoad.ENABLE_CRYPTAND_SOLVER.get()
                    && !(wire instanceof org.patryk3211.powergrid.electricity.sim.SwitchedWire)) {
                ElectricalNetwork self = (ElectricalNetwork) (Object) this;
                // 诊断（节流 2s）：持续 addWire → netVer 持续变 → 后台构建
                // 版本校验永远失败 → solved=0 → 设备不工作（电机不转）
                long now = System.currentTimeMillis();
                if (now - TOPO_WIRE_DBG_LAST >= 2000) {
                    TOPO_WIRE_DBG_LAST = now;
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[TopoWireAdd] wire={} netVer={}",
                            wire == null ? "null" : wire.getClass().getSimpleName(),
                            com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
                                    .netVersionOf(self));
                }
                com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
                        .postWireConnect(self);
            }
        } catch (Throwable ignored) {
        }
    }
    private static volatile long TOPO_WIRE_DBG_LAST;

    /** 导线移出网络 wires（拆线/方块移除）。同上过滤 SwitchedWire。 */
    @Inject(method = "removeWire", at = @At("TAIL"))
    private void cryptand$topoWireRemove(AbstractElectricWire wire, CallbackInfo ci) {
        try {
            if (ConfigLoad.ENABLE_CRYPTAND_SOLVER.get()
                    && !(wire instanceof org.patryk3211.powergrid.electricity.sim.SwitchedWire)) {
                com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
                        .postWireDisconnect((ElectricalNetwork) (Object) this);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 网络合并（merge：连接两块网络）→ 跨网络结构变化：本网络 + other 网络级失效
     *  + 全局兜底（物理连通改变 → DSU 需全量重建；合并是低频大变化，全量可接受）。 */
    @Inject(method = "merge", at = @At("TAIL"))
    private void cryptand$topoMerge2(ElectricalNetwork other, CallbackInfo ci) {
        try {
            if (ConfigLoad.ENABLE_CRYPTAND_SOLVER.get()) {
                var mgr = com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get();
                ElectricalNetwork self = (ElectricalNetwork) (Object) this;
                mgr.postWireConnect(self);
                if (other != null) mgr.postWireConnect(other);
                mgr.markTopologyChanged(); // 全局：DSU 全量重建 + 防烧线兜底
            }
        } catch (Throwable ignored) {
        }
    }
}
