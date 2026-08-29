/**
 * ===== WorldNetworks.preTick 主线程驱动（相量回写唯一入口） =====
 *
 * Target: org.patryk3211.powergrid.electricity.WorldNetworks
 * Strategy: @Inject at HEAD + cancellable=true (replaces preTick entirely)
 *
 * 2026-08-20 用户决策：删除原版基础上改造的【多线程后台计算】死代码
 * （getPool/cryptand$ensureComputeThread/线程池/cryptand$instances 等——
 * 时域求解彻底禁用后无调用点、无用途）。仅保留主线程 preTick →
 * {@code cryptand$doComputeRound}：快照世界导线/网络 + 自管图同步 + 设备
 * 参数缓存同步 + 驱动 CryptandTopologyManager（相量求解 + 自管宿主写回）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.multithreading;

import org.patryk3211.powergrid.PowerGrid;
import org.patryk3211.powergrid.electricity.WorldNetworks;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.PerformanceCounter;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLine;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLinePart;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Mixin(value = WorldNetworks.class, remap = false)
public abstract class WorldNetworksMixin {

    @Shadow @Final public net.minecraft.world.level.Level world;
    @Shadow private PerformanceCounter perf;
    @Shadow private boolean runningDiscovery;
    @Shadow private Set<ElectricalNetwork> islandDiscoveryQueue;
    @Shadow protected Set<TransmissionLinePart> deferredRewireEntities;
    @Shadow public List<ElectricalNetwork> subnetworks;
    @Shadow public java.util.Map<Integer, TransmissionLine> transmissionLines;
    @Shadow public java.util.Map<org.patryk3211.powergrid.electricity.wire.IWireEndpoint,
            org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode> globalExternalNodes;
    @Shadow private void runIslandDiscoveryFor(ElectricalNetwork network) {}

    // ===== 主线程 round 驱动（见类头） =====

    @Unique
    private void cryptand$doComputeRound() {
        this.perf.start();

        // 快照遍历：主线程可能在 island discovery / 节点注册时修改 subnetworks，
        // 直接用 iterator 遍历会抛 ConcurrentModificationException。快照用于清理空网络。
        List<ElectricalNetwork> snapshot = new ArrayList<>(this.subnetworks);
        for (ElectricalNetwork network : snapshot) {
            if (network.isEmpty()) {
                this.subnetworks.remove(network);
                network.cleanup();
            }
        }

        // ===== 时域求解彻底禁用（2026-08-10 起；2026-08-15 完全接管：删除
        // enableCryptandSolver=false 的原版 prepare+singleTick 回退分支，
        // 相量成为唯一求解器） =====
        // 不再 prepare + singleTick：LRSeriesWire 电感历史积分（Vprev、I*=0.99999）、
        // 变压器 Tr2P2S 耦合、innerHooks 动态残差全部停止 —— 这是"电流无限上升"
        // 发散的直接根源。节点电压完全由相量回写驱动：CryptandTopologyManager
        // 每 tick（主线程或异步调度线程按频率）调用相量核心求解，并把节点 RMS
        // 电压 network.setValue 写回 PowerGrid 网络。
        // 原版设备 getValue()/current()/power() 全部从回写电压自然计算
        // （V×G / V²/R），相量成为唯一求解器，与时域零冲突。
        try {
            // ===== 补全网络集合（2026-08-11，治本） =====
            // PowerGrid merge/islandDiscovery 后，旧网络对象可能从 subnetworks
            // 移除，但【导线/节点仍引用旧对象】（merge 残留）→ Cryptand round
            // 只遍历 subnetworks → 残留网络被丢弃 → 其端子永不写回 → 保持旧电压
            // （电压表端子 0.18V vs 电流表 33V → [WireBurn] i=11000A 实锤）。
            // 双源补全：
            //   源1 transmissionLines —— WorldNetworks 全量导线表（含残留网络导线）
            //   源2 globalExternalNodes —— 全局端子节点表（所有 endpoint→OFN，
            //      覆盖残留网络里仍被引用的端子，即使导线已被 merge 清理）
            // → 残留网络也被纳入求解/写回。
            java.util.LinkedHashSet<ElectricalNetwork> allNets =
                    new java.util.LinkedHashSet<>(this.subnetworks);
            for (TransmissionLine tl : this.transmissionLines.values()) {
                ElectricalNetwork n = tl.getNetwork();
                if (n != null) allNets.add(n);
            }
            if (this.globalExternalNodes != null) {
                for (org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode node
                        : this.globalExternalNodes.values()) {
                    ElectricalNetwork n = node.getNetwork();
                    if (n != null) allNets.add(n);
                }
            }
            // 快照世界全量导线表（buildFromTerminals 连通性扩展用；主线程安全）
            com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.WORLD_WIRES =
                    new java.util.ArrayList<>(this.transmissionLines.values());
            // 快照世界全部网络（电路原理图导出等工具用）
            com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.WORLD_NETS =
                    new java.util.ArrayList<>(allNets);
            // 自管导线拓扑同步（2026-08-13 阶段1：双源迁移入口）——从原版全量
            // 导线表生成引擎级 WireGraph，Cryptand 核心拓扑自此进入自管图。
            // 原版网络仍作电压宿主（阶段1）；拓扑已脱离"读原版网络"。
            try {
                com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().syncFromWorld(
                        com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback.WORLD_WIRES);
            } catch (Throwable ignored) {
            }
            // 设备参数同步（2026-08-15 完全异步架构：主线程=发消息，不计算）：
            // 读方块参数 → 线程安全缓存 DeviceParamCache。后台求解线程只读缓存，
            // 绝不碰 level/BE（避免 MC BE 表并发崩溃）。
            try {
                com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParamCache.sync(this.world);
            } catch (Throwable ignored) {
            }
            // 组装器缓存更新（2026-08-15 用户设计：BE 绑定缓存，主线程每 tick
            // 原子写；后台组装器读缓存构建，不碰 BE）。
            try {
                com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCacheRegistry
                        .syncAll(this.world);
            } catch (Throwable ignored) {
            }
            com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager.get()
                    .tick(this.world, new java.util.ArrayList<>(allNets));
        } catch (Throwable t) {
            // 相量回写失败不影响游戏主循环（下一 tick 自动恢复）
        }

        this.perf.end();
    }

    // ========== preTick ==========

    @Unique
    private static long cryptand$diagPreTickUs;
    @Unique
    private static long cryptand$diagPreTickLastMs;

    @Inject(method = "preTick", at = @At("HEAD"), cancellable = true)
    private void cryptand$preTick(CallbackInfo ci) {
        ci.cancel();
        long tickStartNanos = System.nanoTime();

        // Phase 1: deferred rewire cleanup
        this.deferredRewireEntities.removeIf(part -> {
            part.refreshEndpointNodes();
            return true;
        });

        // Phase 2: process new junction nodes
        JunctionWireEndpoint.processNewNodes(this.world);

        // Phase 3: island discovery
        this.runningDiscovery = true;
        for (ElectricalNetwork network : this.islandDiscoveryQueue) {
            this.runIslandDiscoveryFor(network);
        }
        this.islandDiscoveryQueue.clear();
        this.runningDiscovery = false;

        // Phase 4: transmission line tick
        Iterator<TransmissionLine> lineIter = this.transmissionLines.values().iterator();
        ArrayList<TransmissionLine> removed = new ArrayList<>();
        // ===== 禁原版网络维护（2026-08-13 完整闭环） =====
        // 自管模式（转换启用 + 自管图非空）→ 拓扑真相 = 自管 WireGraph（转换类
        // 从 transmissionLines 全量表同步），原版网络的 addWire/merge 拓扑维护
        // 【禁用】——原版网络仅作转换源，不再维护 wires/网络对象（设备已从自管
        // 宿主 DEVICE_TERMINAL_V 读电压）。空导线清理仍执行（防脏表膨胀）。
        // 转换关闭 → 保留原版 line.tick()（安全兜底，完全原版行为）。
        boolean selfManaged = com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter
                .isEnabled()
                && com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().nodeCount() > 0;
        while (lineIter.hasNext()) {
            TransmissionLine line = lineIter.next();
            if (line.segments.isEmpty()) {
                PowerGrid.LOGGER.warn("Empty transmission line {} dropped during tick", line);
                removed.add(line);
                lineIter.remove();
            } else if (!selfManaged) {
                // ===== 核心：TransmissionLine.tick()（原版逻辑，仅非自管模式） =====
                // 这是新导线进入网络 wires 的关键！node1/node2 同网络 → addWire；
                // 不同网络且无线自身网络 → merge。之前 Cryptand 用 lineOrphaned
                // 拦截/误删导线，且依赖不稳定的 connections 判定 → 新导线永远
                // 进不了网络 wires → writeback 合并 1 处理不到 → 烧线。
                // 已删除 lineOrphaned 补丁，恢复原版 addWire/merge 拓扑维护。
                line.tick();
            }
            // 自管模式：跳过 line.tick()——原版网络 wires 不维护（拓扑由自管图
            // 全量接管），transmissionLines 全量表仍保留（转换类数据源）。
        }
        removed.forEach(TransmissionLine::remove);

        // 每 tick 主线程执行求解。field 计算依赖主线程状态，
        // 必须在主线程完成，否则换向器电流/转速异常。
        cryptand$doComputeRound();

        // 调试：累计 preTick 耗时，每 1 秒打印一次
        if (cryptand$debug()) {
            cryptand$diagPreTickUs += (System.nanoTime() - tickStartNanos) / 1000;
            long now = System.currentTimeMillis();
            if (now - cryptand$diagPreTickLastMs >= 1000) {
                cryptand$diagPreTickLastMs = now;
                org.apache.logging.log4j.LogManager.getLogger("cryptand").info(
                        "[Cryptand-DIAG] preTick 总耗时={}µs/s thread={}",
                        cryptand$diagPreTickUs, Thread.currentThread().getName());
                cryptand$diagPreTickUs = 0;
            }
        }
    }

    @Unique
    private static boolean cryptand$debug() {
        // 2026-08-12：移除 enablePowergridDebug 配置，调试信息默认关闭
        return false;
    }
}
