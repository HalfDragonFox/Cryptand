/**
 * ===== 服务器测量系统（请求合并处理） =====
 *
 * 服务器端维护的测量系统类：
 *   - 收到客户端 MultimeterRequestPayload 后入队（不立即计算）
 *   - 每 tick 批量处理：按【目标所在 ElectricalNetwork + 频率】分组
 *   - 每组只调用一次 PhasorEngine.solveNetwork（同网络合并求解，缓存复用）
 *   - 从该次求解结果反查组内每个请求的电压/电流 → 分别回发 MultimeterResponsePayload
 *
 * 好处：
 *   - 同一网络 N 个请求 → 只构建/求解一次相量网络（而不是 N 次）
 *   - PhasorEngine 内部 per-net 缓存进一步保证同网络同频率在 CACHE_TICKS 内不重解
 *   - 完全零导线 BFS（buildContextFromNetwork 跨变压器合并）
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.neoforge.powergrid.creative.MultimeterResponsePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.patryk3211.powergrid.electricity.base.ElectricBehaviour;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServerMeasurementSystem {

    /** 待处理请求（packet handler 入队，服务端 tick 消费） */
    public static final class Pending {
        public final ServerPlayer player;
        public final String key;
        public final MultimeterReadoutStore.Target target;
        Pending(ServerPlayer player, String key, MultimeterReadoutStore.Target target) {
            this.player = player;
            this.key = key;
            this.target = target;
        }
    }

    /** ⚠ 2026-08-24 只保留最新：key = 玩家 UUID + 请求 key → 覆盖旧请求。
     *  客户端可能每帧/高频轮询同一表 → 旧请求堆积，tick 消费的是过期
     *  target/位置。覆盖式 = 任意时刻每玩家每表只有最新一次请求。 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Pending> PENDING =
            new java.util.concurrent.ConcurrentHashMap<>();

    private ServerMeasurementSystem() {}

    /** 收到客户端请求包后调用：入队待处理（不立即计算，等 tick 合并） */
    public static void enqueue(ServerPlayer player, String key, MultimeterReadoutStore.Target target) {
        if (player == null || key == null || target == null) return;
        PENDING.put(player.getStringUUID() + '|' + key,
                new Pending(player, key, target));
    }

    /**
     * 服务端 tick：取出全部待处理请求，按 (net, freq) 分组合并求解后批量回发。
     * 由 CryptandNeoForge 的 ServerTickEvent.Post 驱动（每个维度一次）。
     */
    public static void tick(ServerLevel level) {
        if (PENDING.isEmpty()) return;
        List<Pending> batch = new ArrayList<>();
        int dropped = 0;
        var it = PENDING.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            it.remove();
            Pending p = e.getValue();
            if (p.player.serverLevel() == level) batch.add(p);
            else dropped++;
        }
        if (batch.isEmpty()) {
            try {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[MeterTick] level={} batch=0 dropped={}", level.dimension(), dropped);
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[MeterTick] level={} batch={} dropped={}", level.dimension(),
                    batch.size(), dropped);
        } catch (Throwable ignored) {
        }

        long gameTime = level.getGameTime();

        // 1) 按 (代表网络/孤立方块, freq) 分组；同组并集种子方块，一次求解
        Map<GroupKey, GroupData> groups = new LinkedHashMap<>();
        Map<Pending, GroupKey> keyOf = new java.util.HashMap<>();
        for (Pending q : batch) {
            Seed seed = resolveSeed(level, q.target);
            if (seed == null) { keyOf.put(q, null); continue; }
            GroupKey gk = new GroupKey(seed.groupKey, seed.freq);
            keyOf.put(q, gk);
            GroupData gd = groups.computeIfAbsent(gk, k -> new GroupData());
            gd.blocks.addAll(seed.blocks);
            gd.requests.add(q);
            // 电流请求：收集被测量导线（构建时不合并、串联电阻以便测流）
            if (q.target.kind == MultimeterReadoutStore.Kind.WIRE_CURRENT && q.target.eid >= 0) {
                Entity en = level.getEntity(q.target.eid);
                if (en instanceof BaseWireEntity w) gd.wires.add(w);
            }
        }
        try {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[MeterTick] groups={}", groups.size());
        } catch (Throwable ignored) {
        }

        // 2) 每组一次求解（种子方块并集）→ 组内各目标反查 → 回发
        for (Map.Entry<GroupKey, GroupData> e : groups.entrySet()) {
            GroupKey gk = e.getKey();
            GroupData gd = e.getValue();
            PhasorEngine.NetworkSolve solve = PhasorEngine.solveBlocks(
                    level, new ArrayList<>(gd.blocks), new ArrayList<>(gd.wires),
                    gk.freq, gameTime);
            for (Pending q : gd.requests) {
                double rms = Double.NaN;
                if (q.target.kind == MultimeterReadoutStore.Kind.RESISTANCE_BETWEEN) {
                    // 电阻：独立【直流测试电流法】求解（零化源 + 注入 1A），
                    // 不走本组共享相量求解（结果即 Ω，无 RMS 换算）。
                    rms = PhasorEngine.resistanceBetween(
                            level, q.target.posA, q.target.tA,
                            q.target.posB, q.target.tB);
                } else if (solve != null) {
                    rms = computeFromSolve(solve, level, q.target, gk.freq);
                }
                boolean valid = !Double.isNaN(rms) && Double.isFinite(rms);
                if (!valid) {
                    dbgFail("kind={} freq={} solveNull={} key={} posA={} tA={} posB={} tB={} netNodes={}",
                            q.target.kind, String.format("%.2f", gk.freq), solve == null,
                            q.key, q.target.posA, q.target.tA, q.target.posB, q.target.tB,
                            solve == null ? -1 : solve.ctx.blockTerminals.size());
                }
                // 合并求解后回发（客户端仅收到此包才更新读数）
                PacketDistributor.sendToPlayer(q.player,
                        new MultimeterResponsePayload(q.key, valid ? rms : 0.0, valid, gk.freq));
            }
        }

        // 3) 无法解析的请求 → 回发无效
        for (Pending q : batch) {
            if (keyOf.get(q) == null) {
                PacketDistributor.sendToPlayer(q.player,
                        new MultimeterResponsePayload(q.key, 0.0, false, q.target.freq));
            }
        }
    }

    /** 由已求解网络反查单个目标的 RMS（V/A）；失败 NaN */
    private static double computeFromSolve(PhasorEngine.NetworkSolve solve, Level level,
                                           MultimeterReadoutStore.Target t, double freq) {
        double peak = Double.NaN;
        switch (t.kind) {
            case SAME_BLOCK_VOLTAGE:
                if (t.posA != null) {
                    peak = PhasorEngine.voltageFromSolve(solve, t.posA, t.tA, t.tB);
                }
                break;
            case BETWEEN_BLOCKS_VOLTAGE:
                if (t.posA != null && t.posB != null) {
                    peak = PhasorEngine.voltageBetweenSolve(solve, t.posA, t.tA, t.posB, t.tB);
                }
                break;
            case WIRE_CURRENT:
                if (t.eid >= 0) {
                    Entity en = level.getEntity(t.eid);
                    if (en instanceof BaseWireEntity w) {
                        peak = PhasorEngine.wireCurrentSolve(solve, w, freq);
                    }
                }
                break;
            case NODE_CURRENT:
                // 单点电流（2026-08-15）：流经该点（posA#tA）的电流（KCL 支路和）
                if (t.posA != null && t.tA >= 0) {
                    peak = PhasorEngine.nodeCurrentSolve(solve, t.posA, t.tA, freq);
                }
                break;
        }
        if (Double.isNaN(peak)) return Double.NaN;
        double rms = freq > 1.0 ? peak / Math.sqrt(2.0) : peak;
        // 诊断（节流）
        if (++dbgResponseCounter % 20 == 0) {
            try {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[MeterRsp] kind={} freq={} peak={} rms={}",
                        t.kind, String.format("%.2f", freq), String.format("%.2f", peak),
                        String.format("%.2f", rms));
            } catch (Throwable ignored) {
            }
        }
        return rms;
    }

    private static int dbgResponseCounter;

    /** 分组键：代表网络（有网络）或孤立方块位置 + 频率 */
    private record GroupKey(Object key, double freq) {}

    /** 组内累积：种子方块并集 + 待处理请求 */
    private static final class GroupData {
        final java.util.Set<BlockPos> blocks = new java.util.LinkedHashSet<>();
        final java.util.Set<BaseWireEntity> wires = new java.util.LinkedHashSet<>();
        final List<Pending> requests = new ArrayList<>();
    }

    /** 解析结果：种子方块列表 + 分组键 + 服务端频率 */
    private static final class Seed {
        final List<BlockPos> blocks;
        final Object groupKey; // ElectricalNetwork（有网络）或 BlockPos（孤立器件）
        final double freq;
        Seed(List<BlockPos> blocks, Object groupKey, double freq) {
            this.blocks = blocks; this.groupKey = groupKey; this.freq = freq;
        }
    }

    /** 解析目标：收集需要纳入的种子方块 + 代表网络 + 服务端频率；完全无效 → null */
    private static Seed resolveSeed(Level level, MultimeterReadoutStore.Target t) {
        List<BlockPos> blocks = new ArrayList<>();
        if (t.kind == MultimeterReadoutStore.Kind.WIRE_CURRENT && t.eid >= 0) {
            Entity en = level.getEntity(t.eid);
            if (!(en instanceof BaseWireEntity w)) {
                dbgNet("fail why=eid-not-wire ({}) kind={} freq={}",
                        en == null ? "null" : en.getClass().getSimpleName(), t.kind,
                        String.format("%.2f", t.freq));
                return null;
            }
            addEndpointBlock(level, w.getEndpoint1(), blocks);
            addEndpointBlock(level, w.getEndpoint2(), blocks);
        } else {
            if (t.posA != null) blocks.add(t.posA);
            if ((t.kind == MultimeterReadoutStore.Kind.BETWEEN_BLOCKS_VOLTAGE
                    || t.kind == MultimeterReadoutStore.Kind.RESISTANCE_BETWEEN)
                    && t.posB != null) {
                blocks.add(t.posB);
            }
        }
        if (blocks.isEmpty()) {
            dbgNet("fail why=no-blocks kind={} freq={}", t.kind, String.format("%.2f", t.freq));
            return null;
        }
        // 代表网络（第一个有网络的种子端子）+ 服务端网络级频率
        ElectricalNetwork repNet = null;
        double freq = t.freq;
        for (BlockPos bp : blocks) {
            if (!(level.getBlockEntity(bp) instanceof ElectricBlockEntity ebe)) continue;
            ElectricBehaviour beh = ebe.getElectricBehaviour();
            if (beh == null) continue;
            for (int i = 0; i < 4; i++) {
                OwnedFloatingNode n = beh.getTerminal(i);
                if (n == null) continue;
                ElectricalNetwork net = n.getNetwork();
                if (net == null) continue;
                if (repNet == null) repNet = net;
                double f = MultimeterDebug.getNetworkFrequencyHz(net);
                if (f > 0) freq = f;
            }
        }
        // 分组键：原版网络优先；自管模式（2026-08-15 单点电流）→ 自管 WireNetwork
        // （同自管网络多请求合并一次求解）；都没有 → 方块位置（孤立器件）
        Object gk = repNet;
        if (gk == null && com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter
                .isEnabled()) {
            var mgr = com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get();
            for (BlockPos bp : blocks) {
                for (int tt = 0; tt < 8; tt++) {
                    var wn = mgr.networkOf("B" + bp + "#" + tt);
                    if (wn != null) {
                        gk = wn;
                        break;
                    }
                }
                if (gk != null) break;
            }
        }
        // ⚠ 2026-08-21 修复"电容+AC源 万用表显示 [DC] 0Hz"：自管模式下频率必须
        //   无条件从自管图分量反查（AC 源枚举）。原来只在 gk==null 时才查——自管
        //   模式原版网络（repNet）可能残留非 null → 跳过图查询 → freq 沿用
        //   t.freq（客户端 BFS 已失效=0）或 getNetworkFrequencyHz(原版网络)=0 →
        //   万用表误显示 [DC]（实际网络 50Hz AC，电容 163A 是正常容性电流）。
        if (com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter
                .isEnabled()) {
            try {
                double f = MultimeterDebug.getFrequencyHzFromGraph(level, blocks.get(0));
                if (f > 0) freq = f;
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[MeterFreq] pos={} graphFreq={} finalFreq={}",
                        blocks.get(0), String.format("%.2f", f), String.format("%.2f", freq));
            } catch (Throwable ignored) {
            }
        }
        if (gk == null) gk = blocks.get(0);
        return new Seed(blocks, gk, freq);
    }

    private static void addEndpointBlock(Level level, org.patryk3211.powergrid.electricity.wire.IWireEndpoint ep,
                                         List<BlockPos> blocks) {
        if (ep instanceof BlockWireEndpoint bep) {
            blocks.add(bep.getPos());
        } else if (ep instanceof org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint jep) {
            try {
                BlockPos p = BlockPos.containing(jep.getExactPosition(level));
                if (p != null) blocks.add(p);
            } catch (Throwable ignored) {
            }
        }
    }

    private static volatile long dbgNetLastLog = 0;
    private static void dbgNet(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - dbgNetLastLog < 1000) return;
        dbgNetLastLog = now;
        try {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info("[MeterNet] " + fmt, args);
        } catch (Throwable ignored) {
        }
    }

    private static volatile long dbgFailLastLog = 0;
    private static void dbgFail(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - dbgFailLastLog < 1000) return;
        dbgFailLastLog = now;
        try {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info("[MeterFail] " + fmt, args);
        } catch (Throwable ignored) {
        }
    }
}
