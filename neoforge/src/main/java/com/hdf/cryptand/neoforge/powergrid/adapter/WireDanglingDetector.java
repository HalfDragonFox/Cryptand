package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ===== 导线悬空检测器（2026-08-19 用户需求） =====
 *
 * 设备被烧毁/爆炸破坏后，相连导线【不再被烧断】（checkDeviceOverheat 已删除
 * burnWiresAtBlock），而是保留悬垂，由本检测器发现"端点方块已消失"后【平滑断开】
 * （removeEdge，无爆炸）：
 *
 *   - {@link #tick}：主线程每 tick 调用，按配置周期懒检测（默认 20 tick = 1s；
 *     0 = 禁用定期检测）。全图扫描每条边，端点方块已不是电气设备 → 断开。
 *   - {@link #forceCheck}：设备被爆炸破坏后立即对该 pos 相连导线强制检测一次
 *     （不用等下一个懒检测周期）。
 *
 * 悬空判定（保守，避免误删正常导线）：
 *   - 仅方块端子（"B<pos>#<term>"）参与；接线端子（"J<id>"）跳过
 *   - 区块未加载（isLoaded=false）→ 跳过（区块卸载不等于设备消失）
 *   - 正因过热销毁（isOverheatBurning）→ 跳过（销毁流程执行中）
 *   - 该 pos 仍是 ElectricBlockEntity → 不悬空（设备还在）
 *   - 方块消失 / 不再是电气设备 → 悬空 → 断开
 *
 * 断开效果：removeEdge（网络分裂/收尾核心自动完成）+ markTopologyChanged（重建
 * 求解）+ syncGraphToClientsNow（客户端渲染）。
 */
public final class WireDanglingDetector {

    private static int tickCounter;

    private WireDanglingDetector() {
    }

    /**
     * 主线程每 tick 调用（processPost 内）：按配置周期懒检测全图悬空导线。
     * ⚠ 2026-08-20 用户要求"导线不再固定时间寻找，而是一旦有元件被移除或
     * 主动发送导线被破坏消息再进行导线检测"：定期全图扫描【默认禁用】——
     * 悬空导线由消息驱动检测（元件移除 → {@link #forceCheck}，导线破坏 →
     * DestructionQueue/EngineBus DESTROY_WIRE）。interval 配置保留（>0 且显式
     * 配置才定期扫描，兼容旧行为；默认 0 = 禁）。
     */
    public static void tick(Level level) {
        try {
            int interval = ConfigLoad.CRYPTAND_WIRE_DANGLING_CHECK_INTERVAL_TICKS.get();
            if (interval <= 0) return; // 0 = 禁用定期检测（默认，消息驱动）
            if (++tickCounter % interval != 0) return;
            checkAll(level);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 全图扫描：断开所有端点方块已消失的导线。返回断开条数（诊断）。
     */
    public static int checkAll(Level level) {
        if (level == null) return 0;
        try {
            WireNetworkManager mgr = WireNetworkManager.get();
            List<WireEdge> dangling = new ArrayList<>();
            for (WireEdge e : mgr.edgeList()) {
                try {
                    if (isDangling(level, e.a) || isDangling(level, e.b)) {
                        dangling.add(e);
                    }
                } catch (Throwable ignored) {
                }
            }
            return removeEdges(level, mgr, dangling);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 强制检测（设备爆炸破坏后）：只检查该 pos 所有端子相连的导线。
     * 不用等懒检测周期——立即断开已悬空的边。
     * ⚠ 2026-08-19：此处设备已确认被销毁（destroyModel 刚 destroyBlock）→
     * 直接以"该 pos 方块已非电气设备"判定，不依赖 isDangling 的 DeviceParamCache
     * 兜底（缓存尚未同步，会漏判）。
     */
    public static void forceCheck(Level level, BlockPos pos) {
        if (level == null || pos == null) return;
        try {
            WireNetworkManager mgr = WireNetworkManager.get();
            // 该 pos 全部端子点 key
            Set<String> pointKeys = new HashSet<>();
            for (int t = 0; t < 8; t++) pointKeys.add("B" + pos + "#" + t);
            boolean deviceGone = !(level.getBlockEntity(pos) instanceof ElectricBlockEntity);
            List<WireEdge> dangling = new ArrayList<>();
            for (WireEdge e : mgr.edgeList()) {
                try {
                    boolean hitsPos = (e.a != null && pointKeys.contains(e.a.key))
                            || (e.b != null && pointKeys.contains(e.b.key));
                    if (!hitsPos) continue;
                    // 该设备已销毁 → 只要它还挂着就断开（无论另一端状态）
                    if (deviceGone) {
                        dangling.add(e);
                    } else if (isDangling(level, e.a) || isDangling(level, e.b)) {
                        dangling.add(e);
                    }
                } catch (Throwable ignored) {
                }
            }
            removeEdges(level, mgr, dangling);
        } catch (Throwable ignored) {
        }
    }

    /** 统一断开：removeEdge + 拓扑重建 + 客户端同步。返回断开条数。 */
    private static int removeEdges(Level level, WireNetworkManager mgr,
                                   List<WireEdge> dangling) {
        if (dangling.isEmpty()) return 0;
        int removed = 0;
        for (WireEdge e : dangling) {
            try {
                if (e.a != null && e.b != null && mgr.contains(e.a) && mgr.contains(e.b)) {
                    mgr.removeEdge(e.a, e.b);
                    removed++;
                }
            } catch (Throwable ignored) {
            }
        }
        if (removed > 0) {
            try {
                CryptandTopologyManager.get().markTopologyChanged();
            } catch (Throwable ignored) {
            }
            try {
                PowerGridWireConverter.syncGraphToClientsNow(level);
            } catch (Throwable ignored) {
            }
            try {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[WireDangle] 悬空检测断开 {} 条导线", removed);
            } catch (Throwable ignored) {
            }
        }
        return removed;
    }

    /** 端点是否悬空（所在方块消失 / 不再是电气设备）。保守判定。 */
    private static boolean isDangling(Level level, WirePoint p) {
        if (p == null || !WireKeyUtil.isBlock(p.key)) return false; // 仅方块端子
        BlockPos pos = WireKeyUtil.posOf(p.key);
        if (pos == null) return false;
        if (!level.isLoaded(pos)) return false; // 区块未加载 ≠ 设备消失
        if (DestructionQueue.isOverheatBurning(pos)) return false; // 过热销毁流程中
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ElectricBlockEntity) return false;
        // 2026-08-19 防误判（"连接电机导线消失"排查）：BE 为 null / 非电气类型
        // 可能是瞬时状态（区块加载初始化中 / 2x2 变压器 PART 方块 / 特殊设备）。
        // DeviceParamCache 是主线程每 tick 同步的权威设备表——仍登记 → 设备还在
        // （只是 BE 暂不可见）→ 不判悬空；设备真正消失后 sync 移除条目 → 下轮
        // 才断开（懒检测 20 tick 一次，远慢于 sync 的每 tick，天然防瞬时误判）。
        if (com.hdf.cryptand.neoforge.powergrid.adapter.DeviceParamCache.get(pos) != null) {
            return false;
        }
        return true;
    }
}
