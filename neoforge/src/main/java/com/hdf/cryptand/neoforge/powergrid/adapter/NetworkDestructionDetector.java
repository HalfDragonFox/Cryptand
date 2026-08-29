/**
 * ===== 网络破坏检测类（2026-08-22 用户架构，主线程） =====
 *
 * 职责：
 *   - 接收 BE 破坏 / 放置 / 导线剪切检测（基于 BE 位置）；
 *   - 【区块重载过滤】：区块加载/卸载/重载触发的 setRemoved 不产生破坏计划
 *     （延迟确认 getBlockState：方块仍在 → 保留；持续缺失 → 真拆除）；
 *   - 检测该网络的【单端子/无端子悬空导线】，整合需要破坏的内容为
 *     {@link NetworkDestructionPlan}（悬空端子 + 相关 BE 列表 + 悬空导线）；
 *   - 计划发给异步核心执行删除（{@link MainThreadInteractionManager#postDestroy}），
 *     主线程【不直接删图】。
 *
 * 铁律：本类只运行在主线程，做【检测 / 整合 / 消息投递 / 缓存失效】，不做图
 * 删除；图删除由异步核心按消息串行执行（NetOpKind.DESTROY）。
 */
package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class NetworkDestructionDetector {

    private static final NetworkDestructionDetector INSTANCE = new NetworkDestructionDetector();

    public static NetworkDestructionDetector get() {
        return INSTANCE;
    }

    private NetworkDestructionDetector() {
    }

    /** 区块重载确认重试次数（≈0.6s）：覆盖区块加载/重载时方块状态恢复时间 */
    private static final int REMOVAL_RETRY = 12;

    /** ⚠ 2026-08-22 放置保护期（根因修复）：实测放置设备后 ~0.9s confirmAndDestroy
     *  误判"真拆除"（12 次检查 block/be 全 false，无 keep 日志）→ 删掉刚 addDevice
     *  的端子点 → 设备端子缺失 → 电路开路后剪线 contains-fail（b 端点不在图）。
     *  onBePlaced 记录放置时间，confirmAndDestroy 在保护期内【强制保留】——无论
     *  getBlockState/getBlockEntity 为何读不到（放置替换/区块重载时序问题），
     *  放置的方块在保护期内绝不删除。 */
    private static final long PLACE_GUARD_MS = 3000;
    /** pos -> 放置毫秒（主线程读写；按维度隔离防跨世界误保护） */
    private static final Map<ServerLevel, Map<BlockPos, Long>> RECENTLY_PLACED =
            new ConcurrentHashMap<>();

    /** 记录放置（onBePlaced 调用，主线程） */
    private static void markPlaced(ServerLevel level, BlockPos pos) {
        RECENTLY_PLACED.computeIfAbsent(level, k -> new ConcurrentHashMap<>())
                .put(pos.immutable(), System.currentTimeMillis());
    }

    /** 该 pos 是否在放置保护期内（主线程） */
    private static boolean inPlaceGuard(ServerLevel level, BlockPos pos) {
        Map<BlockPos, Long> m = RECENTLY_PLACED.get(level);
        if (m == null) return false;
        Long t = m.get(pos);
        if (t == null) return false;
        if (System.currentTimeMillis() - t < PLACE_GUARD_MS) return true;
        m.remove(pos); // 保护期已过 → 清理
        return false;
    }

    // ==================== 事件入口（主线程） ====================

    /**
     * BE 被破坏/移除（ElectricBlockEntityRemoveMixin.setRemoved 触发，主线程）。
     * 延迟确认（区块重载过滤）：方块仍在（getBlockState 非 air）→ 保留；
     * 持续缺失（真拆除）→ 构建破坏计划发异步核心。
     *
     * @param overheatBurning true = 过热爆炸销毁（跳过导线清理，保留悬垂线，
     *                        DestructionQueue 处理）
     */
    public void onBeRemoved(ServerLevel level, BlockPos pos, boolean overheatBurning) {
        if (level == null || pos == null) return;
        confirmAndDestroy(level, pos.immutable(), overheatBurning, REMOVAL_RETRY);
    }

    /**
     * BE 放置（EntityPlaceEvent，主线程）：建网（addDevice）。
     * ⚠ 2026-08-22 移除 cleanupStalePoints（不必要的有害补丁）：它把 pos 放进
     * plan.removedBlocks → 异步 executeDestroy 删除 pos 所有端子（含刚 addDevice
     * 的新点）→ 图被清空（实测 nodes 2->4->2，剪线/拆除全部失效）。放置替换的
     * 残留由 ensureComponentNetworks（同方块端子同网络）+ 接线自然处理。
     */
    public void onBePlaced(ServerLevel level, BlockPos pos, int terms) {
        try {
            var mgr = WireNetworkManager.get();
            if (mgr == null) return;
            // 诊断（2026-08-22 定位图被清空）：addDevice 前后节点数
            int before = mgr.nodeCount();
            boolean ok = mgr.addDevice(pos, terms);
            int after = mgr.nodeCount();
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[DestDetect] placed pos={} terms={} ok={} nodes {}->{} inst={}",
                    pos, terms, ok, before, after, mgr.instanceId());
        } catch (Throwable ignored) {
        }
    }

    /** 导线剪切（剪线钳，主线程）：构建剪线计划（剪掉的边 + 悬空端子）。 */
    public void onWireCut(Level level, List<WireEdge> cutEdges) {
        try {
            if (level == null || cutEdges == null || cutEdges.isEmpty()) return;
            NetworkDestructionPlan plan = buildWireCutPlan(level, cutEdges);
            if (plan == null || plan.isEmpty()) return;
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[DestDetect] wire-cut plan={}", plan);
            MainThreadInteractionManager.get().postDestroy(plan);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 延迟确认（区块重载过滤） ====================

    private void confirmAndDestroy(ServerLevel sl, BlockPos pos,
                                   boolean overheatBurning, int retriesLeft) {
        sl.getServer().execute(() -> {
            try {
                net.minecraft.world.level.block.entity.BlockEntity cur =
                        sl.getBlockEntity(pos);
                net.minecraft.world.level.block.state.BlockState st = sl.getBlockState(pos);
                boolean loaded = sl.isLoaded(pos);
                boolean blockPresent = st != null && !st.isAir();
                boolean bePresent = cur != null;
                // 区块未加载（加载中/卸载中）→ 无法判断方块是否还在 →【不消耗计数】
                // 继续等待（防误删：区块边界玩家放置后 isLoaded false 时若消耗计数，
                // 0.6s 后强制删除新点 → 图被清空，实测 nodes 4->2）。
                if (!loaded) {
                    confirmAndDestroy(sl, pos, overheatBurning, retriesLeft);
                    return;
                }
                // 方块 或 BE 任一存在（区块重载重建 / 替换 / 刚放置）→ 保留图点
                if (blockPresent || bePresent) {
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[DestDetect] keep pos={} block={} be={} inst={}",
                            pos, blockPresent, bePresent,
                            com.hdf.cryptand.neoforge.powergrid.adapter
                                    .WireNetworkManager.get().instanceId());
                    return;
                }
                // 诊断（2026-08-22 误删定位）：方块+BE 都缺失（消耗计数/删除前）打印
                if (retriesLeft <= 3) {
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[DestDetect] missing pos={} block={} be={} retry={}",
                            pos, blockPresent, bePresent, retriesLeft);
                }
                if (retriesLeft <= 0) {
                    // 连续确认【方块+BE 都缺失】→ 真拆除 → 构建破坏计划发异步核心
                    NetworkDestructionPlan plan = buildBlockPlan(sl, pos);
                    if (plan == null || plan.isEmpty()) {
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[DestDetect] remove pos={} no-plan", pos);
                        return;
                    }
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[DestDetect] remove pos={} plan={}", pos, plan);
                    // 缓存失效 + 端子注销（主线程即时）
                    try {
                        DeviceBinding db = DeviceBinding.forPos(pos);
                        if (db != null) {
                            db.notifyModelDestroyed();
                        } else {
                            CryptandTopologyManager.get().markTopologyChanged();
                        }
                    } catch (Throwable ignored) {
                    }
                    try {
                        TerminalRegistry.unregister(pos);
                    } catch (Throwable ignored) {
                    }
                    // 图删除交给异步核心（发破坏计划消息）；过热销毁跳过（保留悬垂线）
                    if (!overheatBurning) {
                        MainThreadInteractionManager.get().postDestroy(plan);
                    }
                    return;
                }
                // 方块+BE 都缺失 → 可能是真拆除，或区块数据尚未恢复 → 重试
                confirmAndDestroy(sl, pos, overheatBurning, retriesLeft - 1);
            } catch (Throwable ignored) {
            }
        });
    }

    // ==================== 构建破坏计划 ====================

    /**
     * 设备破坏计划：破坏方块全部端子 + 邻接悬空导线 + 悬空端子。
     * 悬空判定：邻接边另一端若端点方块已消失/非电气设备 → 也纳入删除（防悬垂残留）。
     */
    private NetworkDestructionPlan buildBlockPlan(Level level, BlockPos removedBlock) {
        try {
            var mgr = WireNetworkManager.get();
            if (mgr == null || mgr.nodeCount() <= 0) return null;
            List<BlockPos> removedBlocks = new ArrayList<>();
            List<WirePoint> danglingPoints = new ArrayList<>();
            List<WireEdge> danglingEdges = new ArrayList<>();
            Set<String> seenPoints = new HashSet<>();
            Set<String> seenEdges = new HashSet<>();
            removedBlocks.add(removedBlock);
            // 1) 该方块全部端子点
            for (WirePoint p : mgr.pointList()) {
                BlockPos pp = WireKeyUtil.posOf(p.key);
                if (pp != null && pp.equals(removedBlock) && seenPoints.add(p.key)) {
                    danglingPoints.add(p);
                }
            }
            // 2) 邻接边（一端在破坏方块 → 悬空边）+ 另一端悬空端子
            for (WirePoint p : new ArrayList<>(danglingPoints)) {
                for (WireEdge e : mgr.adjacent(p)) {
                    String ek = e.a.key + "~" + e.b.key;
                    if (!seenEdges.add(ek)) continue;
                    danglingEdges.add(e);
                    WirePoint other = e.a.equals(p) ? e.b : e.a;
                    if (isDanglingTerminal(level, other) && seenPoints.add(other.key)) {
                        danglingPoints.add(other);
                    }
                }
            }
            if (danglingPoints.isEmpty() && danglingEdges.isEmpty()) return null;
            return new NetworkDestructionPlan(removedBlocks, danglingPoints, danglingEdges);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 剪线计划：剪掉的边 + 剪后悬空端子（无其他边 且 端点方块消失/非电气）。
     * 设备端子（方块仍在）剪线后保留（设备还在，可再接）。
     */
    private NetworkDestructionPlan buildWireCutPlan(Level level, List<WireEdge> cutEdges) {
        try {
            var mgr = WireNetworkManager.get();
            if (mgr == null || cutEdges == null || cutEdges.isEmpty()) return null;
            List<WirePoint> danglingPoints = new ArrayList<>();
            List<WireEdge> danglingEdges = new ArrayList<>(cutEdges);
            Set<String> seenPoints = new HashSet<>();
            for (WireEdge e : cutEdges) {
                for (WirePoint p : new WirePoint[]{e.a, e.b}) {
                    if (p == null || !WireKeyUtil.isBlock(p.key)) continue;
                    if (!seenPoints.add(p.key)) continue;
                    // 剪后：该端子是否还有其他边
                    boolean hasOtherEdge = false;
                    for (WireEdge ae : mgr.adjacent(p)) {
                        if (!cutEdges.contains(ae)) {
                            hasOtherEdge = true;
                            break;
                        }
                    }
                    // 无其他边 + 端点方块悬空（消失/非电气）→ 悬空端子删除
                    if (!hasOtherEdge && isDanglingTerminal(level, p)) {
                        danglingPoints.add(p);
                    }
                }
            }
            return new NetworkDestructionPlan(List.of(), danglingPoints, danglingEdges);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 端子是否悬空：端点方块消失 / 不再是电气设备（与 WireDanglingDetector 一致）。 */
    private boolean isDanglingTerminal(Level level, WirePoint p) {
        if (p == null || !WireKeyUtil.isBlock(p.key)) return false;
        BlockPos pos = WireKeyUtil.posOf(p.key);
        if (pos == null || !level.isLoaded(pos)) return false;
        if (DestructionQueue.isOverheatBurning(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ElectricBlockEntity) return false;
        if (be instanceof IElectricEntity) return false;
        return true;
    }
}
