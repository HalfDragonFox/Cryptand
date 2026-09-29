/**
 * ===== 服务器测量系统（请求合并处理） =====
 *
 * 服务器端维护的测量系统类：
 *   - 收到客户端 MultimeterRequestPayload 后入队（不立即计算）
 *   - 每 tick 批量处理：按【目标所在 ElectricalNetwork + 频率】分组
 *   - 每组只调用一次 EngineMeasurements.solveNetwork（同网络合并求解，缓存复用）
 *   - 从该次求解结果反查组内每个请求的电压/电流 → 分别回发 MultimeterResponsePayload
 *
 * 好处：
 *   - 同一网络 N 个请求 → 只构建/求解一次相量网络（而不是 N 次）
 *   - PhasorEngine 内部 per-net 缓存进一步保证同网络同频率在 CACHE_TICKS 内不重解
 *   - 完全零导线 BFS（buildContextFromNetwork 跨变压器合并）
 */

package com.hdf.cryptand.neoforge.powergrid.measurement;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.net.MultimeterResponsePayload;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCurrent;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceVoltageStore;
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

    /** 后台求解任务（主线程收集 → 后台只读 seedKey/freq 构建+求解，不碰世界） */
    private static final class JobData {
        final String seedKey;
        final double freq;
        final List<Pending> requests = new ArrayList<>();
        final java.util.Set<BaseWireEntity> wires = new java.util.LinkedHashSet<>();
        JobData(String seedKey, double freq) { this.seedKey = seedKey; this.freq = freq; }
    }

    /** 后台求解完成条目（后台线程入队 → 主线程反查 + 回发） */
    private record Solved(JobData job, EngineMeasurements.NetworkSolve solve) {}

    /** 后台结果队列（后台 offer / 主线程 poll） */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Solved> SOLVED =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 后台求解在飞标志：保证同一时刻只有一个后台求解批（避免请求堆积/竞态） */
    private static final java.util.concurrent.atomic.AtomicBoolean SOLVE_IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 收到客户端请求包后调用：入队待处理（不立即计算，等 tick 合并） */
    public static void enqueue(ServerPlayer player, String key, MultimeterReadoutStore.Target target) {
        if (player == null || key == null || target == null) return;
        PENDING.put(player.getStringUUID() + '|' + key,
                new Pending(player, key, target));
    }

    /**
     * 服务端 tick（主线程）：【收集 → 投递后台 → 回发】三阶段。
     * <p>2026-09-11（用户：所有计算为引擎异步计算，主线程仅接收）：
     *   - 主线程只做：解析请求（读世界方块/端子/导线实体）、解析自管图种子键、
     *     反查读数并回发（网络包必须主线程）；
     *   - 后台（ThreadDispatchers.submitGeneric）：用自管图构建源
     *     {@link PhasorEngine#solveFromGraphSeed} 构建 + 矩阵求解——本系统最重的
     *     部分（该构建源只读自管图与主线程预同步的 DeviceParamCache，不读 Level）；
     *   - 结果入队 → 主线程 drainSolved 反查 + PacketDistributor 回发。
     * 无自管种子（转换关闭/孤立器件）→ 回退原主线程合并求解（legacySolveTick）。
     */
    public static void tick(ServerLevel level) {
        // (1) 主线程：先消费后台结果（反查读世界 + 发包）
        drainSolved(level);
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
        if (batch.isEmpty()) return;
        long gameTime = level.getGameTime();
        // (2) 主线程解析：种子方块 → 自管图种子键 + 频率 + 导线实体（读世界）
        Map<String, JobData> jobs = new LinkedHashMap<>();
        for (Pending q : batch) {
            // ===== 2026-09-13 读数表快路径（零建网 / 零求解 / 零结构改动）=====
            // 引擎每轮求解时已经把结果写进读数表（DeviceVoltageStore / DeviceCurrent），
            // 因此"电压类 + 单点电流"测量不必再为测量单独构建并求解一遍网络 ——
            // 直接读表即可。既省掉一次矩阵求解，也确保测量【绝不触碰网络结构】
            // （不新增节点/元件 ⇒ 不改签名 ⇒ 不会触发网络重建、不会让电机归零）。
            Double fast = tryReadoutTables(q.target);
            if (fast != null) {
                sendValue(q, fast);
                continue; // 不进 jobs → 本 tick 不触发任何求解
            }
            Seed seed = resolveSeed(level, q.target);
            String graphSeed = seed == null ? null : resolveGraphSeed(level, seed.blocks);
            if (graphSeed == null) { sendInvalid(q); continue; }
            JobData jd = jobs.computeIfAbsent(graphSeed + '@' + seed.freq,
                    k -> new JobData(graphSeed, seed.freq));
            jd.requests.add(q);
            if (q.target.kind == MultimeterReadoutStore.Kind.WIRE_CURRENT && q.target.eid >= 0) {
                Entity en = level.getEntity(q.target.eid);
                if (en instanceof BaseWireEntity w) jd.wires.add(w);
            }
        }
        if (jobs.isEmpty()) {
            // ⚠ 2026-09-11 用户：禁用主线程求解，只允许异步——原版兜底路径
            //（legacySolveTick：buildContextFromBlocks + 主线程 solveBlocks）【整体停用】：
            // 无自管图种子（转换关闭 / 孤立器件）时直接回发无效读数，绝不在主线程求解。
            for (Pending q : batch) sendInvalid(q);
            return;
        }
        // (3) 投递后台求解（构建 + 矩阵求解）；上一批仍在算 → 本批回队下一 tick
        if (!SOLVE_IN_FLIGHT.compareAndSet(false, true)) {
            for (Pending q : batch) PENDING.putIfAbsent(q.player.getStringUUID() + '|' + q.key, q);
            return;
        }
        final List<JobData> jobList = new ArrayList<>(jobs.values());
        try {
            dbgTick("[MeterTick] level={} batch={} jobs={} dropped={}",
                    level.dimension(), batch.size(), jobList.size(), dropped);
        } catch (Throwable ignored) {
        }
        ThreadDispatchers.submitGeneric(() -> {
            try {
                for (JobData jd : jobList) {
                    SOLVED.add(new Solved(jd,
                            EngineMeasurements.solveFromGraphSeed(jd.seedKey, jd.freq)));
                }
            } catch (Throwable ignored) {
            } finally {
                SOLVE_IN_FLIGHT.set(false);
            }
        });
    }

    /** 主线程：消费后台求解结果 → 反查读数 → 回发（读世界 + 发包只能主线程） */
    private static void drainSolved(ServerLevel level) {
        Solved sv;
        while ((sv = SOLVED.poll()) != null) {
            try {
                EngineMeasurements.NetworkSolve raw = sv.solve();
                // ⚠ BE 判型必须在主线程：terminalNodeId【方式1】用 NetworkSolve.level
                // 读 BE（ElectricBehaviour 端子 → nodeToEngine 映射）——代理方块
                //（设备连接器：端子委托到被代理设备）只能走方式1。后台构建时
                // 不持有 level（BE/Level 线程绑定），故在此【主线程】补上：
                // 构造器包私有，本包可见。
                EngineMeasurements.NetworkSolve solve = raw == null ? null
                        : new EngineMeasurements.NetworkSolve(raw.net, raw.freq, raw.ctx,
                                raw.result, level);
                double freq = solve == null ? sv.job().freq : solve.freq;
                for (Pending q : sv.job().requests) {
                    try {
                        double rms = Double.NaN;
                        if (q.target.kind == MultimeterReadoutStore.Kind.RESISTANCE_BETWEEN) {
                            // ⚠ 2026-09-11 用户：禁用主线程求解，只允许异步——直流测试
                            // 电流法（零化源 + 注入 1A）属主线程求解 → 已停用，此处回发
                            // 无效读数；电阻测量的异步化方案待定（不阻塞本轮清理）。
                            rms = Double.NaN;
                        } else if (solve != null) {
                            rms = computeFromSolve(solve, level, q.target, freq);
                        }
                        boolean valid = !Double.isNaN(rms) && Double.isFinite(rms);
                        PacketDistributor.sendToPlayer(q.player,
                                new MultimeterResponsePayload(q.key, valid ? rms : 0.0,
                                        valid, freq));
                    } catch (Throwable t) {
                        dbgFail("send failed key={} player={}: {}", q.key,
                                q.player == null ? "?" : q.player.getName().getString(), t);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * ===== 读数表快路径（2026-09-13 评估落地）=====
     *
     * 设计依据（用户："手持式的万用表、手持式的电阻表、手持式的温度表等都是被动器件，
     *  主要通过网络计算时顺带把参数记下发送给相应的测量设备即可"）：
     *   · 读数表由引擎每轮求解【顺带】写入（DeviceVoltageStore 记各端子对地电压、
     *     DeviceCurrent 记设备电流），测量端只读不写 ⇒ 天然被动；
     *   · 不新建任何"测量模型"、不进 composites ⇒ 不改 nodeSignature/wiresSignature
     *     ⇒ 不会触发网络重建（这正是"临时测量模型"方案会踩的坑）；
     *   · **跨网络天然支持**：对地电压相减，两点分属不同网络也成立。
     *
     * @return 命中则返回读数；null = 未命中 → 交回原有求解路径
     *         （WIRE_CURRENT 依赖导线实体、RESISTANCE_BETWEEN 需要注入测试电流，
     *          二者都必须走原路径）。
     */
    private static Double tryReadoutTables(MultimeterReadoutStore.Target t) {
        try {
            if (t == null) return null;
            switch (t.kind) {
                case SAME_BLOCK_VOLTAGE: {
                    // 该位置被引擎建模过（读数表有记录）→ 直接取两端子压差
                    if (t.posA == null || !DeviceVoltageStore.has(t.posA)) return null;
                    return DeviceVoltageStore.readBetween(t.posA, t.tA, t.tB);
                }
                case BETWEEN_BLOCKS_VOLTAGE: {
                    // 跨方块（可跨网络）：两块各有对地电压记录即可相减
                    if (t.posA == null || t.posB == null) return null;
                    if (!DeviceVoltageStore.has(t.posA)
                            || !DeviceVoltageStore.has(t.posB)) {
                        return null;
                    }
                    return DeviceVoltageStore.read(t.posA, t.tA)
                            - DeviceVoltageStore.read(t.posB, t.tB);
                }
                case NODE_CURRENT: {
                    if (t.posA == null || !DeviceVoltageStore.has(t.posA)) return null;
                    return DeviceCurrent.read(t.posA);
                }
                default:
                    return null; // WIRE_CURRENT / RESISTANCE_BETWEEN → 原路径
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 快路径命中 → 直接回发读数（零求解） */
    private static void sendValue(Pending q, double value) {
        try {
            PacketDistributor.sendToPlayer(q.player,
                    new MultimeterResponsePayload(q.key, value, true, q.target.freq));
        } catch (Throwable ignored) {
        }
    }

    /** 无法解析的请求 → 回发无效读数 */
    private static void sendInvalid(Pending q) {
        try {
            PacketDistributor.sendToPlayer(q.player,
                    new MultimeterResponsePayload(q.key, 0.0, false, q.target.freq));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 解析自管图种子键（"B" + BlockPos + "#" + 端子号）：取第一个存在于自管图的
     * 端子点。读世界（declaredTerminalCount → BE 查端子数）→ 只能主线程调用。
     * 与 {@code PhasorEngine.buildForBlocksMeasurement} 的自管分支同源，保证测量
     * 走同一个构建源（后台求解才能不读 Level）。
     */
    public static String resolveGraphSeed(Level level, List<BlockPos> blocks) {
        try {
            if (!PowerGridWireConverter.isEnabled()) return null;
            var mgr = WireNetworkManager.get();
            if (mgr.nodeCount() == 0) return null;
            for (BlockPos bp : blocks) {
                if (bp == null) continue;
                int termCount = PhasorNetworkBuilder.declaredTerminalCount(
                        level.getBlockEntity(bp));
                for (int t = 0; t < Math.max(termCount, 2); t++) {
                    if (mgr.contains(new WirePoint("B" + bp + "#" + t))) {
                        return "B" + bp + "#" + t;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 回退路径（无自管种子：转换关闭/孤立器件）：原主线程合并求解 + 回发。
     * 此时仿真核心未接管（PowerGridWireConverter 关闭），保持原行为不退化。
     */
    private static void legacySolveTick(ServerLevel level, List<Pending> batch, long gameTime) {
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
            if (q.target.kind == MultimeterReadoutStore.Kind.WIRE_CURRENT && q.target.eid >= 0) {
                Entity en = level.getEntity(q.target.eid);
                if (en instanceof BaseWireEntity w) gd.wires.add(w);
            }
        }
        for (Map.Entry<GroupKey, GroupData> e : groups.entrySet()) {
            GroupKey gk = e.getKey();
            GroupData gd = e.getValue();
            EngineMeasurements.NetworkSolve solve = EngineMeasurements.solveBlocks(
                    level, new ArrayList<>(gd.blocks), new ArrayList<>(gd.wires),
                    gk.freq, gameTime);
            for (Pending q : gd.requests) {
                try {
                    double rms = Double.NaN;
                    if (q.target.kind == MultimeterReadoutStore.Kind.RESISTANCE_BETWEEN) {
                        rms = EngineMeasurements.resistanceBetween(
                                level, q.target.posA, q.target.tA,
                                q.target.posB, q.target.tB);
                    } else if (solve != null) {
                        rms = computeFromSolve(solve, level, q.target, gk.freq);
                    }
                    boolean valid = !Double.isNaN(rms) && Double.isFinite(rms);
                    PacketDistributor.sendToPlayer(q.player,
                            new MultimeterResponsePayload(q.key, valid ? rms : 0.0, valid,
                                    gk.freq));
                } catch (Throwable ignored) {
                }
            }
        }
        for (Pending q : batch) {
            if (keyOf.get(q) == null) sendInvalid(q);
        }
    }

    /** 由已求解网络反查单个目标的 RMS（V/A）；失败 NaN */
    private static double computeFromSolve(EngineMeasurements.NetworkSolve solve, Level level,
                                           MultimeterReadoutStore.Target t, double freq) {
        double peak = Double.NaN;
        switch (t.kind) {
            case SAME_BLOCK_VOLTAGE:
                if (t.posA != null) {
                    peak = EngineMeasurements.voltageFromSolve(solve, t.posA, t.tA, t.tB);
                }
                break;
            case BETWEEN_BLOCKS_VOLTAGE:
                if (t.posA != null && t.posB != null) {
                    peak = EngineMeasurements.voltageBetweenSolve(solve, t.posA, t.tA, t.posB, t.tB);
                }
                break;
            case WIRE_CURRENT:
                if (t.eid >= 0) {
                    Entity en = level.getEntity(t.eid);
                    if (en instanceof BaseWireEntity w) {
                        peak = EngineMeasurements.wireCurrentSolve(solve, w, freq);
                    }
                }
                break;
            case NODE_CURRENT:
                // 单点电流（2026-08-15）：流经该点（posA#tA）的电流（KCL 支路和）
                if (t.posA != null && t.tA >= 0) {
                    peak = EngineMeasurements.nodeCurrentSolve(solve, t.posA, t.tA, freq);
                }
                break;
        }
        if (Double.isNaN(peak)) return Double.NaN;
        double rms = freq > 1.0 ? peak / Math.sqrt(2.0) : peak;
        // 诊断（节流）
        if (++dbgResponseCounter % 20 == 0) {
            try {
                CryptandNeoForge.WAF_LOGGER.info(
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
        if (gk == null && PowerGridWireConverter
                .isEnabled()) {
            var mgr = WireNetworkManager.get();
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
        if (PowerGridWireConverter
                .isEnabled()) {
            try {
                double f = MultimeterDebug.getFrequencyHzFromGraph(level, blocks.get(0));
                if (f > 0) freq = f;
                CryptandNeoForge.WAF_LOGGER.info(
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

    /** ⚠ 2026-08-30 审计 #17：测量系统高频日志节流（原 [MeterTick] 每 tick 无条件
     *  INFO 刷屏）。 */
    private static volatile long dbgTickLastLog = 0;

    private static void dbgTick(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - dbgTickLastLog < 1000) return;
        dbgTickLastLog = now;
        try {
            CryptandNeoForge.WAF_LOGGER.info("[MeterTick] " + fmt, args);
        } catch (Throwable ignored) {
        }
    }

    private static volatile long dbgNetLastLog = 0;
    private static void dbgNet(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - dbgNetLastLog < 1000) return;
        dbgNetLastLog = now;
        try {
            CryptandNeoForge.WAF_LOGGER.info("[MeterNet] " + fmt, args);
        } catch (Throwable ignored) {
        }
    }

    private static volatile long dbgFailLastLog = 0;
    private static void dbgFail(String fmt, Object... args) {
        long now = System.currentTimeMillis();
        if (now - dbgFailLastLog < 1000) return;
        dbgFailLastLog = now;
        try {
            CryptandNeoForge.WAF_LOGGER.info("[MeterFail] " + fmt, args);
        } catch (Throwable ignored) {
        }
    }
}
