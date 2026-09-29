package com.hdf.cryptand.neoforge.pipez;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.net.DimensionNetworkManager;
import com.hdf.cryptand.neoforge.net.INetworkDevice;
import com.hdf.cryptand.neoforge.net.IntegratedNetworkPlatform;
import de.maxhenkel.pipez.blocks.tileentity.PipeLogicTileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pipez 完全接管中枢（2026-08-29 P3）。
 * <p>
 * 职责：
 * <ul>
 *   <li>【接管标记】{@link #isTakenOver(BlockPos)}——被接管的 Pipez 管道位置集合；
 *       {@link com.hdf.cryptand.neoforge.pipez.mixin.PipezTakeoverMixin} 据此在
 *       {@code PipeLogicTileEntity.tick()} HEAD 取消原版自 tick（完全接管）；</li>
 *   <li>【注册】放置 → {@code IntegratedNetworkPlatform.acquire(level)} +
 *       {@code DimensionNetworkManager.registerDevice(new PipezDeviceAdapter(...))}；
 *       拆除 → {@code unregisterDevice} + 移除标记；</li>
 *   <li>【相邻合并】放置时若有相邻已接管管道，作为同一连接簇（物理连通网络）接入
 *       —— 邻居合并的 SPLIT_MERGE 增强留待下一步（当前每管道一网络，可独立传输）。</li>
 * </ul>
 */
public final class PipezTakeover {

    private PipezTakeover() {
    }

    private static final org.apache.logging.log4j.Logger LOG =
            CryptandNeoForge.WAF_LOGGER;
    /** 被完全接管的 Pipez 管道位置集（世界无关；随放置/拆除增删） */
    private static final Set<BlockPos> TAKEN = ConcurrentHashMap.newKeySet();

    /** 平台总线注册（在 CryptandNeoForge 构造里调用） */
    public static void register(IEventBus gameBus) {
        // 2026-08-29 修复：EntityPlaceEvent 时机 BE 尚未写入 world，getBlockEntity(pos)
        // 持续返回 null → 注册必定失败（devices=0）。改由 PipezTakeoverMixin 在
        // PipeLogicTileEntity.tick() 首帧用 this 懒注册（BE 必然存在，最可靠）。
        // 此处保留拆除事件（BreakEvent 时方块还在，可安全取状态）。
        gameBus.addListener((net.neoforged.neoforge.event.level.BlockEvent.BreakEvent ev) -> {
            try {
                if (ev.getLevel() == null || ev.getLevel().isClientSide()) return;
                if (!(ev.getLevel() instanceof ServerLevel sl)) return;
                BlockPos pos = ev.getPos();
                if (onPipeRemoved(sl, pos)) {
                    LOG.info("[PipezTakeover] remove pos={}", pos);
                } else if (sl.getBlockState(pos).getBlock()
                        instanceof de.maxhenkel.pipez.blocks.PipeBlock) {
                    LOG.warn("[PipezTakeover] remove untaken pos={}", pos);
                }
            } catch (Throwable t) {
                LOG.warn("[PipezTakeover] break event err pos={}", ev.getPos(), t);
            }
        });
    }

    /**
     * 懒注册（2026-08-29 修复；由 PipezTakeoverMixin 在 {@code PipeLogicTileEntity.tick()}
     * HEAD 调用）：BE 此刻必然已进入 world（getLevel/getBlockPos 可用）。
     * <ul>
     *   <li>已接管 → 返回 true（原版自 tick 应被取消）；</li>
     *   <li>未接管且是 Pipez 管道 BE → 注册设备（acquire+registerDevice）+ 标记；成功 true；</li>
     *   <li>注册失败/非服务端 → false（不接管，原版继续，不破坏管道）。</li>
     * </ul>
     */
    public static boolean ensureRegistered(net.minecraft.world.level.block.entity.BlockEntity be) {
        try {
            if (be == null) return false;
            BlockPos pos = be.getBlockPos();
            if (!(be.getLevel() instanceof ServerLevel sl)) return false;
            if (!(be instanceof PipeLogicTileEntity tile)) return false;
            // ★ 2026-09 P0-1 根因修复：TAKEN 命中 ≠ 一定已注册——拆除整簇后
            //   其余段的 TAKEN 仍在但设备已消失（此前直接 return true →
            //   原版 tick 被取消且 INC 无设备 → 僵尸管道永久停传）。
            //   必须校验【活簇设备】存在；不存在 → 撤销标记并重新注册。
            if (TAKEN.contains(pos)) {
                if (clusterDeviceAlive(sl, pos)) return true;
                TAKEN.remove(pos);
                LOG.warn("[PipezTakeover] stale TAKEN pos={} re-register", pos);
            }
            TAKEN.add(pos);
            try {
                DimensionNetworkManager mgr = IntegratedNetworkPlatform.acquire(sl);
                if (mgr == null) {
                    TAKEN.remove(pos);
                    LOG.warn("[PipezTakeover] tick-register no manager pos={}", pos);
                    return false;
                }
                // ★ 2026-08-30 用户：45 管道一簇却 45 网络 → 任务爆炸。
                //   同簇合并：若已有设备覆盖本管道位置（同物理簇）→ 只需标记
                //   TAKEN（共享该设备），不新建设备/网络。
                //   先 BFS 探测本管道所在簇，与已注册设备的 clusterPipes 比对。
                PipezDeviceAdapter probe =
                        new PipezDeviceAdapter(sl, tile);
                boolean merged = false;
                for (INetworkDevice dev : mgr.devices()) {
                    if (dev instanceof
                            PipezDeviceAdapter pa) {
                        if (pa.clusterPipes().contains(pos)) {
                            merged = true; // 同簇已有设备 → 共享，不新建
                            break;
                        }
                    }
                }
                if (merged) {
                    LOG.info("[PipezTakeover] tick-merged pos={} (cluster device exists)",
                            pos);
                    return true; // 共享簇设备（BFS 会随原设备更新，无需单独注册）
                }
                mgr.registerDevice(probe);
                LOG.info("[PipezTakeover] tick-registered BE={} pos={} devices={}",
                        tile.getClass().getSimpleName(), pos, mgr.devices().size());
                return true;
            } catch (Throwable t) {
                TAKEN.remove(pos);
                LOG.error("[PipezTakeover] tick-register FAIL pos={}", pos, t);
                return false;
            }
        } catch (Throwable t) {
            LOG.warn("[PipezTakeover] ensureRegistered err", t);
            return false;
        }
    }

    public static boolean isTakenOver(BlockPos pos) {
        return pos != null && TAKEN.contains(pos);
    }

    /** 该 pos 是否被某个【活簇设备】覆盖（devices 表里任一 Pipez 设备的
     *  clusterPipes 含 pos；P0-1 根因修复用——TAKEN 与设备生命周期强绑定） */
    private static boolean clusterDeviceAlive(ServerLevel sl, BlockPos pos) {
        DimensionNetworkManager mgr = IntegratedNetworkPlatform.manager(sl);
        if (mgr == null) return false;
        for (INetworkDevice dev : mgr.devices()) {
            if (dev instanceof PipezDeviceAdapter pa
                    && pa.clusterPipes().contains(pos)) {
                return true;
            }
        }
        return false;
    }

    /** 放置 Pipez 管道：接管 + 建网（2026-08-30：同簇合并——只留一个设备/网络） */
    public static void onPipePlaced(ServerLevel level, BlockPos pos, PipeLogicTileEntity tile) {
        TAKEN.add(pos);
        try {
            DimensionNetworkManager mgr = IntegratedNetworkPlatform.acquire(level);
            if (mgr != null) {
                PipezDeviceAdapter probe =
                        new PipezDeviceAdapter(level, tile);
                boolean merged = false;
                for (INetworkDevice dev : mgr.devices()) {
                    if (dev instanceof
                            PipezDeviceAdapter pa) {
                        if (pa.clusterPipes().contains(pos)) { merged = true; break; }
                    }
                }
                if (!merged) mgr.registerDevice(probe);
                LOG.info("[PipezTakeover] placed pos={} devices={} merged={}",
                        pos, mgr.devices().size(), merged);
            } else {
                LOG.warn("[PipezTakeover] no manager for pos={}", pos);
            }
        } catch (Throwable t) {
            TAKEN.remove(pos);
            LOG.error("[PipezTakeover] register FAIL pos={}", pos, t);
        }
    }

    /**
     * 拆除 Pipez 管道：解绑网络（2026-09 P0-1 根因修复）。
     * <p>
     * 此前：拆除任意一段 → 找到簇设备【整簇 unregisterDevice】+ 只删被拆段
     * TAKEN → 同簇其余段 TAKEN 仍在但设备已消失 → ensureRegistered 提前返回
     * true → 原版 tick 被取消且 INC 无设备 → 整簇管道永久停传（僵尸化）。
     * <p>
     * 现在：拆除只影响被拆段本身——
     * <ul>
     *   <li>设备簇只剩被拆段（clusterPipes.size()==1）→ 整设备卸载（图清空）；</li>
     *   <li>设备簇还有其他段 → 保留设备 + {@code markRescan()}：下一 tick
     *       {@code tickReports()} 的 BFS 已不含被拆段（方块已破坏）→ 增量 diff
     *       移除该段节点/边 → 图按连通分量自动拆分收缩，其余段继续传输。</li>
     * </ul>
     */
    public static boolean onPipeRemoved(ServerLevel level, BlockPos pos) {
        if (!TAKEN.remove(pos)) return false;
        try {
            DimensionNetworkManager mgr = IntegratedNetworkPlatform.manager(level);
            if (mgr != null) {
                Object targetKey = null;
                PipezDeviceAdapter target = null;
                for (INetworkDevice dev : mgr.devices()) {
                    if (dev instanceof PipezDeviceAdapter pa) {
                        if (pa.clusterPipes().contains(pos)) {
                            targetKey = dev.networkKey();
                            target = pa;
                            break;
                        }
                    }
                }
                if (targetKey == null || target == null) return true;
                if (target.clusterPipes().size() <= 1) {
                    // 该设备只剩被拆段 → 整设备卸载
                    mgr.unregisterDevice(targetKey);
                } else {
                    // 同簇还有段 → 保留设备，BFS 下一 tick 收缩移除被拆段
                    target.markRescan();
                }
            }
        } catch (Throwable t) {
            LOG.warn("[PipezTakeover] onPipeRemoved err pos={}", pos, t);
        }
        return true;
    }

    /**
     * 邻居变化监听（2026-08-29 用户：箱子放下/拆走 → 管道连接变化消息）。
     * 若该管道已接管 → 立即让设备 forceRescan（重新识别 + 置 needsRescan
     * 强制发增量 diff），不等每 tick 轮询——解决"放箱子不触发"。
     */
    public static void onConnectionChanged(Level world, BlockPos pos) {
        try {
            if (!(world instanceof ServerLevel sl)) return;
            DimensionNetworkManager mgr = IntegratedNetworkPlatform.manager(sl);
            if (mgr == null) return;
            INetworkDevice target = null;
            for (INetworkDevice dev : mgr.devices()) {
                if (dev instanceof
                        PipezDeviceAdapter pa) {
                    if (pa.clusterPipes().contains(pos)) { target = dev; break; }
                }
            }
            if (target instanceof
                    PipezDeviceAdapter pa) {
                boolean changed = pa.forceRescan();
                if (changed) {
                    LOG.info("[PipezTakeover] connectionChanged trigger rescan pos={}", pos);
                }
            }
        } catch (Throwable t) {
            LOG.warn("[PipezTakeover] onConnectionChanged err pos={}", pos, t);
        }
    }

    /** 当前接管管道数（诊断） */
    public static int takenCount() {
        return TAKEN.size();
    }
}