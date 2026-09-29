package com.hdf.cryptand.neoforge.powergrid.network;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireDanglingDetector;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireSegments;
import com.hdf.cryptand.neoforge.powergrid.state.CapacitorStateStore;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.state.MotorStateStore;
import com.hdf.cryptand.neoforge.powergrid.state.VirtualDeviceStore;
import com.hdf.cryptand.neoforge.powergrid.state.WireThermalStore;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 销毁队列（2026-08-13 用户架构点 5）。
 * <p>
 * 组件（复合元件）计算达到极限值（温度超限/其他）→ 不直接同步爆炸，而是
 * 发送到本队列：
 *   - 入队：pos + 绑定的实际模型（BE）+ 原因 + 请求网络重建（断开该组件——
 *     重建后该组件不再建模，网络断开并更新参数）。
 *   - 处理（主线程每 tick）：对队列中每项，绑定的模型【已加载且存在】→ 执行
 *     销毁（破坏方块 + 爆炸效果 + 清理温度/虚拟）→ 移出列表；【未加载】（区块
 *     未加载/模型暂不在内存）→ 保留，等模型加载后销毁；【已破坏/替换】→ 无需
 *     销毁，直接移出。
 * <p>
 * 与 {@link BeEventSink} 的关系：过热通知 → BeEventSink.onOverheated → 本队列
 * request()（异步销毁，不再在求解上下文里直接 destroyBlock，避免重入/跨线程）。
 * <p>
 * 导线段销毁（2026-08-14 用户要求：与其他部件处理方式类似——导线移除直接发
 * 给销毁队列统一处理）：过热段 → {@link #requestWire} 入队（段 key 去重）→
 * process 主线程每 tick 统一移除段内全部导线 + 清理温度/段注册表 + 同步客户端
 * （同段统一烧毁）。
 */
public final class DestructionQueue {

    private static final Map<BlockPos, Entry> QUEUE = new LinkedHashMap<>();

    private static final class Entry {
        final BlockEntity be;      // 绑定的实际模型（原设备 BE；可能 null=纯虚拟）
        final String reason;
        Entry(BlockEntity be, String reason) {
            this.be = be;
            this.reason = reason;
        }
    }

    /** 导线段销毁队列：段 key → 段内导线（同段去重，统一烧毁） */
    private static final Map<String, WireEntry> WIRE_QUEUE = new LinkedHashMap<>();

    private static final class WireEntry {
        final java.util.List<WireEdge> edges;
        final String reason;
        WireEntry(java.util.List<WireEdge> edges, String reason) {
            this.edges = edges;
            this.reason = reason;
        }
    }

    /** 正因【过热】而销毁的设备方块位置（2026-08-19：设备过热销毁时只烧自身
     *  过热的导线，未过热导线保留悬垂——避免"20A 金导线（25°C 未过热）被 50Ω
     *  电阻爆炸连带烧断"的误判）。mixin（setRemoved → 导线清理）据此跳过该
     *  位置的导线移除。销毁完成后由 process 清除。 */
    private static final java.util.Set<BlockPos> OVERHEAT_BURNING =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 标记该位置正因过热销毁 */
    public static void markOverheatBurning(BlockPos pos) {
        if (pos != null) OVERHEAT_BURNING.add(pos.immutable());
    }

    /** 该位置是否正因过热销毁（mixin 判断是否跳过导线清理） */
    public static boolean isOverheatBurning(BlockPos pos) {
        return pos != null && OVERHEAT_BURNING.contains(pos);
    }

    /** 销毁处理完成后清除标记 */
    public static void clearOverheatMark(BlockPos pos) {
        if (pos != null) OVERHEAT_BURNING.remove(pos);
    }

    private DestructionQueue() {}

    /** 请求销毁：入队（同 pos 去重）+ 请求网络重建（断开该组件）。主线程调用。 */
    public static void request(Level level, BlockPos pos, BlockEntity be, String reason) {
        if (level == null || pos == null) return;
        QUEUE.put(pos, new Entry(be, reason));
        // 网络重建：断开该组件（重建后该 pos 设备不再建模/参数更新）
        DeviceBinding db = DeviceBinding.forPos(pos);
        if (db != null) {
            db.notifyModelDestroyed(); // 精准网络级失效（附元件引用经绑定可达）
        }
        // ⚠ 2026-09-13 用户："网络重建需要精确到具体网络……不能全世界重建"。
        //   db == null = 该位置【从未进入过求解】（没有绑定 ⇒ 不属于任何网络）
        //   ⇒ 没有任何网络需要重建；原实现在此调 markTopologyChanged()
        //   （topoVersion++ + EngineMeasurements.invalidateAll）纯属无谓开销，
        //   且会把其他所有网络的缓存一起作废。已删除。
        //   若设备确实在网络里却没绑定，下一轮组装（DeviceCacheRegistry 增量刷新）
        //   会自然发现它，不依赖这次全局重建。
    }

    /**
     * 请求导线段销毁（2026-08-14：同段统一烧毁，与其他部件同走销毁队列）：
     * 入队（段 key 去重）+ 请求网络重建（该段不再建模）。主线程调用。
     */
    public static void requestWire(String segmentKey, java.util.List<WireEdge> edges,
                                   String reason) {
        if (segmentKey == null || edges == null || edges.isEmpty()) return;
        WIRE_QUEUE.put(segmentKey, new WireEntry(edges, reason));
        CryptandTopologyManager.get().markTopologyChanged(); // 网络重建（段断开）
    }

    /** 队列大小（诊断） */
    public static int size() { return QUEUE.size(); }

    /** 模型已被破坏/移除（2026-08-13 用户架构点 6：模型破坏 → 网络发送销毁
     *  信息，附带元件引用帮助处理）→ 销毁队列中该 pos 无需再销毁模型，直接移出
     *  （避免等待/重复销毁）。 */
    public static void modelRemoved(BlockPos pos) {
        if (pos != null) QUEUE.remove(pos);
    }

    /**
     * 主线程每 tick 处理：模型已加载 → 销毁 → 移出；未加载 → 保留等待；
     * 已破坏/替换/异常 → 移出（无需再销毁）。
     */
    public static void process(Level level) {
        if (level == null || QUEUE.isEmpty()) return;
        Iterator<Map.Entry<BlockPos, Entry>> it = QUEUE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Entry> e = it.next();
            BlockPos pos = e.getKey();
            Entry entry = e.getValue();
            try {
                BlockEntity cur = level.getBlockEntity(pos);
                if (entry.be == null) {
                    // 纯虚拟组件（无绑定模型）：区块加载即销毁；未加载等加载
                    if (cur != null) {
                        destroyModel(level, pos);
                        it.remove();
                    } else if (!level.isLoaded(pos)) {
                        continue; // 区块未加载 → 保留等待
                    } else {
                        it.remove(); // 已加载但无 BE（不存在）→ 移出
                    }
                } else if (cur == entry.be) {
                    // 绑定模型仍在原位 → 执行销毁（破坏方块 + 爆炸）
                    destroyModel(level, pos);
                    it.remove();
                } else if (!level.isLoaded(pos)) {
                    continue; // 区块未加载（cur=null 但可能是虚拟）→ 等加载后销毁
                } else {
                    it.remove(); // 已加载但模型已被破坏/替换 → 无需销毁
                }
            } catch (Throwable ignored) {
                it.remove(); // 异常 → 移出，避免卡死队列
            }
            // 过热销毁处理完 → 清除标记（后续玩家破坏/正常销毁走完整导线清理）
            clearOverheatMark(pos);
        }
        processWireQueue(level);
    }

    /** 导线段销毁处理：移除段内全部导线 + 清理温度/段注册表 + 爆炸 + 同步客户端。
     *  ⚠ 2026-08-21 爆炸模式配置：0=关闭爆炸和破坏（导线不烧毁，只清理温度防
     *  反复过热请求）；1/2 导线仍烧毁，爆炸 interaction 区分（1=仅效果不破坏
     *  周围方块，2=爆炸+破坏周围方块）。 */
    private static void processWireQueue(Level level) {
        if (level == null || WIRE_QUEUE.isEmpty()) return;
        int mode = ConfigCircuit.EXPLOSION_MODE.get();
        Iterator<Map.Entry<String, WireEntry>> it = WIRE_QUEUE.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, WireEntry> e = it.next();
            String key = e.getKey();
            WireEntry entry = e.getValue();
            try {
                if (mode <= 0) {
                    // 0：关闭爆炸和破坏 → 导线不烧毁（清理温度防反复过热请求）
                    WireThermalStore.remove(key);
                    it.remove();
                    continue;
                }
                var mgr = WireNetworkManager.get();
                int removed = 0;
                double cx = 0, cy = 0, cz = 0;
                int pts = 0;
                for (WireEdge edge : entry.edges) {
                    if (mgr.contains(edge.a) && mgr.contains(edge.b)) {
                        mgr.removeEdge(edge.a, edge.b);
                        removed++;
                    }
                }
                // ⚠ 边可能已被 computeWireHeatOne 预先移除（先移除后销毁）→
                // removed 可能 0，但温度模型/爆炸/客户端同步必须清理
                WireThermalStore.remove(key);
                WireSegments
                        .retainSegments(java.util.Set.of(key));
                // 爆炸效果（段中点，取各边端点平均）
                for (WireEdge edge : entry.edges) {
                    try {
                        BlockPos pa = PhasorNetworkBuilder
                                .pointPosOfPublic(edge.a.key);
                        BlockPos pb = PhasorNetworkBuilder
                                .pointPosOfPublic(edge.b.key);
                        if (pa != null) { cx += pa.getX(); cy += pa.getY(); cz += pa.getZ(); pts++; }
                        if (pb != null) { cx += pb.getX(); cy += pb.getY(); cz += pb.getZ(); pts++; }
                    } catch (Throwable ignored) {
                    }
                }
                if (pts > 0) {
                    // 1 = 仅爆炸效果（不破坏周围方块）；2 = 爆炸 + 破坏周围方块
                    Level.ExplosionInteraction interaction = mode >= 2
                            ? Level.ExplosionInteraction.BLOCK
                            : Level.ExplosionInteraction.NONE;
                    level.explode(null, cx / pts + 0.5, cy / pts + 0.5, cz / pts + 0.5,
                            1.5f, interaction);
                }
                try {
                    PowerGridWireConverter
                            .syncGraphToClientsNow(level);
                } catch (Throwable ignored) {
                }
                if (removed > 0) {
                    CryptandNeoForge.WAF_LOGGER.warn(
                            "[WireBurn] segment {} 烧毁 {} 条导线 ({})",
                            key, removed, entry.reason);
                }
                it.remove();
            } catch (Throwable ignored) {
                it.remove(); // 异常 → 移出，避免卡死队列
            }
        }
    }

    /** 该方块是否为【创造设备】（过热不销毁不爆炸）——创造电阻/创造源/交流创造源
     *  是玩家测试用无限设备，高温不该销毁。 */
    public static boolean isCreativeDevice(Level level, BlockPos pos) {
        try {
            BlockEntity be = level.getBlockEntity(pos);
            if (be == null) return false;
            String cn = be.getClass().getName();
            return cn.contains("CreativeResistor") || cn.contains("CreativeSource")
                    || cn.contains("AcCreativeSource");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 执行销毁：破坏方块 + 爆炸效果 + 清理温度/虚拟快照（防重复触发）。
     *  主线程调用（EngineBus.process 分发复用）。
     *  ⚠ 2026-08-19 创造设备不过热爆炸：是创造设备 → 直接跳过销毁。
     *  ⚠ 2026-08-19 设备被爆炸破坏 → 向相连导线发送【强制悬空检测一次】
     *  （WireDanglingDetector.forceCheck）：导线不再被烧断，由悬空检测立即
     *  平滑断开，不等懒检测周期。
     *  ⚠ 2026-08-21 爆炸模式配置（explosionMode，用户澄清）：元件本身过热总是
     *  直接破坏（mode 1/2；0 = 关闭不破坏不爆炸）；"破坏"指爆炸波及破坏【周围
     *  其他方块】——1 = 仅爆炸效果（元件移除 + 爆炸视觉，不破坏周围方块）；
     *  2 = 爆炸 + 破坏周围方块。 */
    public static void destroyModel(Level level, BlockPos pos) {
        if (isCreativeDevice(level, pos)) return;
        int mode = ConfigCircuit.EXPLOSION_MODE.get();
        // ⚠ 2026-09-12 用户："爆炸后方块没消失必须被更新，要求先触发方块拆除再爆炸"
        //   ——原实现在 EXPLOSION_MODE<=0 时【直接 return】：方块不拆除、温度/状态
        //   存储不清理，而独立触发的爆炸照样发生 → "爆炸了但方块还在"。
        //   现在：【拆除永远执行】（过热销毁是必须的 → 见下方 destroyBlock），
        //   mode 只保留给导线段侧；设备爆炸效果由 powergrid 的 overheatExplosion* 配置控制。
        DeviceThermalStore.remove(pos);
        VirtualDeviceStore.remove(pos);
        // ⚠ 2026-08-30 审计 U9/U17 根因：设备销毁必须清理状态存储——否则
        //   MotorStateStore.STATE 永久残留（同位置重建恢复旧转速/应力 + 跨世界
        //   串扰）、CapacitorStateStore.V_PREV 无 remove 调用泄漏
        MotorStateStore.remove(pos);
        try {
            CapacitorStateStore.remove("C" + pos);
        } catch (Throwable ignored) {
        }
        // 元件本身总是直接破坏（mode 1/2；0 已 return）
        level.destroyBlock(pos, true);
        // 设备已销毁 → 相连导线悬空 → 强制检测一次（立即断开）
        try {
            WireDanglingDetector.forceCheck(level, pos);
        } catch (Throwable ignored) {
        }
        // 爆炸效果（2026-09-12）：改用 powergrid 的过热爆炸配置；顺序永远保证
        // 【先 destroyBlock 拆除（上面已执行）→ 再 explode】 = 用户要求的"先触发
        // 方块拆除再爆炸"。EXPLOSION_MODE 仍用于导线段侧（processWireQueue）。
        boolean expl;
        float power;
        boolean destroyBlocks;
        boolean fire;
        try {
            expl = com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                    .OVERHEAT_EXPLOSION_ENABLED.get();
            power = com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                    .OVERHEAT_EXPLOSION_POWER.get().floatValue();
            destroyBlocks = com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                    .OVERHEAT_EXPLOSION_DESTROY_BLOCKS.get();
            fire = com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                    .OVERHEAT_EXPLOSION_FIRE.get();
        } catch (Throwable ignored) {
            expl = mode > 0;
            power = 2.0f;
            destroyBlocks = mode >= 2;
            fire = false;
        }
        if (!expl) return; // 配置关闭 → 只拆除，不出爆炸
        level.explode(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                power, fire,
                destroyBlocks ? Level.ExplosionInteraction.BLOCK
                        : Level.ExplosionInteraction.NONE);
        CryptandNeoForge.WAF_LOGGER.info(
                "[DeviceBurn] explosion pos={} power={} destroyBlocks={} fire={}",
                pos, String.format("%.2f", power), destroyBlocks, fire);
    }

    /** 世界切换/关闭 → 清空（防跨世界 pos 串扰） */
    public static void clearAll() {
        QUEUE.clear();
        WIRE_QUEUE.clear();
        // ⚠ 2026-08-30 审计 U9 根因：世界切换同步清空电机状态存储（防跨世界
        //   同坐标串扰——MotorStateStore 以 BlockPos 为键，换世界同坐标会命中
        //   旧世界状态）
        MotorStateStore.clearAll();
    }
}
