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
package com.hdf.cryptand.neoforge.powergrid.network;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding;
import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser;
import com.hdf.cryptand.neoforge.powergrid.engine.MainThreadInteractionManager;
import com.hdf.cryptand.neoforge.powergrid.state.CapacitorStateStore;
import com.hdf.cryptand.neoforge.powergrid.state.MotorStateStore;
import com.hdf.cryptand.neoforge.powergrid.state.TerminalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.base.IElectricEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class NetworkDestructionDetector {

    private static final NetworkDestructionDetector INSTANCE = new NetworkDestructionDetector();

    public static NetworkDestructionDetector get() {
        return INSTANCE;
    }

    private NetworkDestructionDetector() {
    }


    // ==================== 事件入口（主线程） ====================

    /**
     * 【真实方块移除】入口（2026-09-11 起由 ChunkBlockChangeMixin.removeBlockEntity 触发，
     * 主线程）——不再挂 BlockEntity.setRemoved（该回调在区块加载/卸载/重载时也会触发，
     * 是误删导线的根源）。事件即真拆除 → 立即执行。
     *
     * @param overheatBurning true = 过热爆炸销毁（跳过导线清理，保留悬垂线，
     *                        DestructionQueue 处理）
     */
    public void onBeRemoved(ServerLevel level, BlockPos pos, boolean overheatBurning) {
        if (level == null || pos == null) return;
        // 真实方块变化事件 → 立即执行（无延迟确认/无重试/无存在性判定）
        executeDestroy(level, pos.immutable(), overheatBurning);
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
            CryptandNeoForge.WAF_LOGGER.info(
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
            CryptandNeoForge.WAF_LOGGER.info(
                    "[DestDetect] wire-cut plan={}", plan);
            MainThreadInteractionManager.get().postDestroy(plan);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 延迟确认（区块重载过滤） ====================

    /**
     * 【唯一删除入口 · 真实方块移除】（2026-09-11 用户要求：不要兜底、只允许一套操作、查到底层）：
     * <p>由【真实方块变化】事件驱动（ChunkBlockChangeMixin：setBlockState 旧状态有 BE 而
     * 新状态没有 / removeBlockEntity）——这两条路径【区块加载/卸载/重载都不会经过】
     *（加载走 addAndRegisterBlockEntity、卸载走 invalidateAllBlockEntities）→ 事件发生
     * 即为真拆除，因此【无需任何判定】：不做 isLoaded/chunkReady、不做"方块或 BE 存在则
     * 保留"、不做重试。
     * <p>历史教训：原实现挂在 BlockEntity.setRemoved —— 该回调在区块卸载/重载时也会触发，
     * 为压制误报先后堆了 isLoaded 判定 + 3 次延迟确认 + 区块重载过滤 + chunkReady 四层兜底，
     * 仍无法消除误报（实测导线放下后 edges 2→1 被误删）。故改为单一干净事件源。
     */
    private void executeDestroy(ServerLevel sl, BlockPos pos, boolean overheatBurning) {
        sl.getServer().execute(() -> {
            try {
                NetworkDestructionPlan plan = buildBlockPlan(sl, pos);
                if (plan == null || plan.isEmpty()) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[DestDetect] remove pos={} no-plan", pos);
                    return;
                }
                CryptandNeoForge.WAF_LOGGER.info(
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
                // ⚠ 2026-09-11 事件驱动（用户：接线柱检测全部事件驱动，废除定时全扫）：
                // 真拆除确认后清理接线柱注册表 + 刷新「facing 该位置」的邻位接线柱引用
                //（设备被移除 → 引用置 null → 不建模；接线柱自身被移除 → 条目清理）。
                try {
                    com.hdf.cryptand.neoforge.powergrid.device.connector
                            .ProxyConnectorAssembler.detectNear(sl, pos);
                } catch (Throwable ignored) {
                }
                // ⚠ 2026-08-30 审计 U11/U9/U17 根因：真拆除必须同步清理——
                //   ① BeMessageParser.unregister：否则设备拆除后桥实例悬垂在
                //     ACTIVE 注册表，tickAll 每 tick 对失效 BE 调 serverTick
                //     （悬垂引用 + 注册表无限增长）；
                //   ② MotorStateStore.remove：直接破坏方块（非过热销毁）不走
                //     DestructionQueue.destroyModel，必须在此清理（否则旧转速
                //     残留 → 同位置重建恢复旧状态 + 跨世界串扰）；
                //   ③ CapacitorStateStore.remove：防 V_PREV 泄漏。
                try {
                    com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser.unregister(pos);
                } catch (Throwable ignored) {
                }
                try {
                    MotorStateStore.remove(pos);
                } catch (Throwable ignored) {
                }
                // ⚠ 2026-08-30 审计 C16：清 LAST_SYNC（Create 同步防抖缓存）——
                // 否则泄漏 + 同位置重放新设备首次 syncCreate 可能误判"值未变"
                // 跳过网络传播。
                try {
                    com.hdf.cryptand.neoforge.powergrid.device.motor
                            .MotorTakeoverBase.syncCleanupPos(pos);
                } catch (Throwable ignored) {
                }
                try {
                    CapacitorStateStore.remove("C" + pos);
                } catch (Throwable ignored) {
                }
                // 图删除交给异步核心（发破坏计划消息）；过热销毁跳过（保留悬垂线）
                if (!overheatBurning) {
                    MainThreadInteractionManager.get().postDestroy(plan);
                }
                return;
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
