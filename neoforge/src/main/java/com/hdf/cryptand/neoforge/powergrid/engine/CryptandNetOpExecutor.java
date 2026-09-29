package com.hdf.cryptand.neoforge.powergrid.engine;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.circuitsimulation.netop.NetOpExecutor;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceAssemblerUpdate;
import com.hdf.cryptand.neoforge.powergrid.device.cache.CacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.network.NetworkDestructionPlan;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCache;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import com.hdf.cryptand.neoforge.powergrid.state.VirtualDeviceStore;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;

/**
 * 网络操作执行器（2026-08-16 用户架构，游戏层实现）。
 * <p>
 * 网表相关操作类（分配器分配的线程 C，普通模式为虚拟线程）把整合后的网络操作
 * 交给本执行器执行：
 *   - 网络拆合（{@link #executeSplitMerge}）：自管图 addEdge/removeEdge 已在主线程
 *     事件时同步完成（合并/分裂已执行）→ 此处做一致性收尾（清理空网络对象）。
 *   - 网络重建（{@link #executeRebuild}）：作废相关网络求解缓存（重建请求经记录表
 *     串行锁定）；返回是否需要重建完成后执行拆合（数据携带事件拆合标记）。
 *   - 网络求解（{@link #executeSolve}）：自管模式 → {@code roundFromGraph}（线程
 *     安全，结果入队主线程 processPost 消费）；原版模式后台求解不安全（level
 *     依赖）→ 保持现状由主线程驱动（此处跳过）。
 * <p>
 * ⚠ 线程安全边界：本执行器在后台线程（虚拟线程）执行，不得直接触碰 level/BE；
 * 求解只走自管模式（roundFromGraph 构建/求解/写回测试点均无 level 依赖，
 * 读主线程预同步的 DeviceParamCache / WireNetworkManager）。
 */
public final class CryptandNetOpExecutor implements NetOpExecutor {

    private static final CryptandNetOpExecutor INSTANCE = new CryptandNetOpExecutor();

    public static CryptandNetOpExecutor get() {
        return INSTANCE;
    }

    private CryptandNetOpExecutor() {
    }

    @Override
    public boolean executeSplitMerge(Object networkKey, Object data) {
        // 网络拆合：自管图合并/分裂已由事件时同步完成；此处做一致性收尾。
        try {
            WireNetworkManager.get().compactNetworks();
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean executeDestroy(Object networkKey, Object data) {
        // 网络内容破坏（设备方块拆除/导线剪切，2026-08-22 用户架构）：
        // 主线程已确认目标确实被移除（区块重载/卸载等假删除已过滤），本方法
        // 在异步线程只操作自管图（NetworkGraphStore 内部读写锁，线程安全），
        // 绝不碰 level/BE（铁律：核心不写回 Level）。
        try {
            var mgr = WireNetworkManager.get();
            if (mgr == null || mgr.nodeCount() <= 0) return true;
            if (data instanceof net.minecraft.core.BlockPos pos) {
                // 设备方块拆除：移除该方块全部端子点 + 邻接边，重新分组
                java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> pts =
                        new java.util.ArrayList<>();
                for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : mgr.pointList()) {
                    net.minecraft.core.BlockPos pp =
                            com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder.pointPosOfPublic(p.key);
                    if (pos.equals(pp)) pts.add(p);
                }
                if (pts.isEmpty()) return true;
                for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : pts) {
                    for (com.hdf.cryptand.circuitsimulation.netgraph.WireEdge e
                            : new java.util.ArrayList<>(mgr.adjacent(p))) {
                        mgr.removeEdge(e.a, e.b);
                    }
                }
                for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : pts) {
                    mgr.removePoint(p);
                }
                // 图内端子标记清理（与 addDevice 登记对称；防 isDeviceTerminal 恒真）
                mgr.unmarkDevice(pos);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[NetOpDestroy] block pos={} removed={}", pos, pts.size());
            } else if (data instanceof com.hdf.cryptand.circuitsimulation.netgraph.WireEdge e) {
                // 导线剪切：移除单条边
                mgr.removeEdge(e.a, e.b);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[NetOpDestroy] edge {}<->{}", e.a.key, e.b.key);
            } else if (data instanceof java.util.List<?> edges) {
                // 导线剪切（批量）：移除多条边（同一端子剪线）
                int n = 0;
                for (Object o : edges) {
                    if (o instanceof com.hdf.cryptand.circuitsimulation.netgraph.WireEdge we) {
                        mgr.removeEdge(we.a, we.b);
                        n++;
                    }
                }
                CryptandNeoForge.WAF_LOGGER.info(
                        "[NetOpDestroy] edges removed={}", n);
            } else if (data instanceof NetworkDestructionPlan plan) {
                // 破坏计划（主线程检测类产出）：删除悬空边 + 悬空端子 + 相关 BE 端子
                int removedEdges = 0;
                for (com.hdf.cryptand.circuitsimulation.netgraph.WireEdge e : plan.danglingEdges) {
                    boolean ca = e.a != null && mgr.contains(e.a);
                    boolean cb = e.b != null && mgr.contains(e.b);
                    if (!ca || !cb) {
                        // 诊断（2026-08-22 剪线无法拆除）：contains 失败原因
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[NetOpDestroy] contains-fail a={} b={} ca={} cb={} "
                                        + "inst={} nodeCount={} edgeCount={}",
                                e.a == null ? "null" : e.a.key,
                                e.b == null ? "null" : e.b.key,
                                ca, cb,
                                mgr.instanceId(), mgr.nodeCount(), mgr.edgeCount());
                        // 打印图中前 10 个点 key（对比格式）
                        StringBuilder ks = new StringBuilder();
                        int n = 0;
                        for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : mgr.pointList()) {
                            if (n++ >= 10) break;
                            ks.append(p.key).append(' ');
                        }
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[NetOpDestroy] graph-points: {}", ks);
                    }
                    if (ca && cb) {
                        mgr.removeEdge(e.a, e.b);
                        removedEdges++;
                    }
                }
                int removedPoints = 0;
                for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : plan.danglingPoints) {
                    boolean cp = p != null && mgr.contains(p);
                    if (!cp) {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[NetOpDestroy] point-contains-fail key={} inst={} "
                                        + "nodeCount={}",
                                p == null ? "null" : p.key,
                                mgr.instanceId(), mgr.nodeCount());
                    }
                    if (cp) {
                        mgr.removePoint(p);
                        removedPoints++;
                    }
                }
                for (net.minecraft.core.BlockPos bp : plan.removedBlocks) {
                    java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> pts =
                            new java.util.ArrayList<>();
                    for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : mgr.pointList()) {
                        net.minecraft.core.BlockPos pp =
                                com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder.pointPosOfPublic(p.key);
                        if (bp.equals(pp)) pts.add(p);
                    }
                    for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : pts) {
                        if (mgr.contains(p)) {
                            mgr.removePoint(p);
                            removedPoints++;
                        }
                    }
                    // 图内端子标记清理（与 addDevice 登记对称）
                    mgr.unmarkDevice(bp);
                }
                CryptandNeoForge.WAF_LOGGER.info(
                        "[NetOpDestroy] plan={} removed edges={} points={}",
                        plan, removedEdges, removedPoints);
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean executeRebuild(Object networkKey, Object data) {
        // 网络重建：缓存即时失效已由主线程事件保持（零风险）；此处执行记录表
        // 层面的重建串行化，并检测是否需要在重建完成后执行拆合。
        boolean splitAfter = false;
        ElectricalNetwork net = null;
        if (data instanceof Object[] arr && arr.length >= 1) {
            if (arr[0] instanceof ElectricalNetwork en) net = en;
            if (arr.length >= 2 && arr[1] instanceof Boolean b) splitAfter = b;
        }
        try {
            if (net != null) {
                CryptandTopologyManager.get().markNetworkChanged(net);
            } else {
                CryptandTopologyManager.get().markTopologyChanged();
            }
        } catch (Throwable ignored) {
        }
        // 事件为拆/合（接线合并/拆线分裂）→ 重建完成后执行拆合操作
        return splitAfter;
    }

    @Override
    public boolean executeDeviceDelta(Object networkKey, Object data) {
        // 电气设备列表包增量更新（2026-08-23 用户协议）：删除/变更/新增组装器列表。
        // 本方法在重建之前执行（设备增量是重建输入）；只操作纯数据/线程安全
        // 缓存（DeviceInfo 登记、组装器缓存预注册、参数失效），绝不碰 level/BE。
        try {
            if (!(data instanceof com.hdf.cryptand.circuitsimulation.netop.GridMessage.DevicePackage dp)) {
                return false;
            }
            var mgr = WireNetworkManager.get();
            int added = 0, removed = 0;
            if (dp.addOrUpdate() != null) {
                for (Object o : dp.addOrUpdate()) {
                    try {
                        if (o instanceof com.hdf.cryptand.circuitsimulation.cache.DeviceInfo di) {
                            // 设备元数据登记（导出原理图/构建读）
                            com.hdf.cryptand.circuitsimulation.cache.NetworkWorld nw =
                                    com.hdf.cryptand.neoforge.powergrid.engine.MainThreadInteractionManager.get().world();
                            if (nw != null) nw.registerDevice(di);
                            added++;
                        } else if (o instanceof com.hdf.cryptand.neoforge.powergrid.device.DeviceAssemblerUpdate up && up.pos() != null) {
                            // 新增/变更设备：预注册组装器缓存（主线程已读值→纯数据）
                            Object asm = up.assembler();
                            if (asm instanceof com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler sca) {
                                DeviceCache c =
                                        com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheRegistry.get(up.pos());
                                if (c == null) c = sca.cacheFor(up.pos());
                                if (c != null) added++;
                            } else if (asm instanceof com.hdf.cryptand.neoforge.powergrid.device.cache.CacheAssembler ca) {
                                DeviceCacheRegistry
                                        .register(up.pos(), ca);
                                added++;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (dp.remove() != null) {
                for (Object o : dp.remove()) {
                    try {
                        if (o instanceof net.minecraft.core.BlockPos pos) {
                            // 删除设备：参数缓存失效 + 虚拟快照移除 + 元数据注销
                            DeviceParamCache
                                    .invalidate(pos);
                            VirtualDeviceStore
                                    .remove(pos);
                            com.hdf.cryptand.circuitsimulation.cache.NetworkWorld nw2 =
                                    com.hdf.cryptand.neoforge.powergrid.engine.MainThreadInteractionManager.get().world();
                            if (nw2 != null) nw2.unregisterDevice("B" + pos);
                            mgr.unmarkDevice(pos);
                            removed++;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (added + removed > 0) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[GridMsg] device delta applied add={} remove={}", added, removed);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void executeSolve(Object networkKey, Object data) {
        // 网络求解：仅自管模式可后台求解（线程安全）。
        // ⚠ Level 来源：优先 CryptandTopologyManager.getLevel()（tick 刷新，可靠）；
        //   CRYPTAND_LAST_LEVEL 仅由 round()/roundFromGraph() 赋值——NetOp 模式下
        //   首次 executeSolve 时它可能仍为 null（新 JVM/重进世界），导致 roundFromGraph
        //   永不执行 → 求解/温度推进全部停摆（温度表恒 20°C 根因）。
        try {
            boolean forceInit = data instanceof Boolean b && b;
            Level lv = CryptandTopologyManager.get().getLevel();
            if (lv == null) lv = PhasorPipeline.CRYPTAND_LAST_LEVEL;
            if (lv == null) return;
            if (PowerGridWireConverter.isEnabled()
                    && WireNetworkManager.get().nodeCount() > 0) {
                // 2026-08-24 操作表内求解：该网络已移入操作表并完成操作（拆合/重建），
                // fromOpTable=true → 不再等待操作表（避免自锁），直接求解。
                PhasorPipeline.roundFromGraph(lv, forceInit, true);
            }
        } catch (Throwable ignored) {
        }
    }
}
