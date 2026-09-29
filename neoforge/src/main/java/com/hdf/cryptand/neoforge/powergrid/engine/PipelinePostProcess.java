package com.hdf.cryptand.neoforge.powergrid.engine;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import com.hdf.cryptand.neoforge.powergrid.device.Assemblers;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding;
import com.hdf.cryptand.neoforge.powergrid.device.thermal.ThermalDiffusionConfig;
import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessage;
import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser;
import com.hdf.cryptand.neoforge.powergrid.device.thermal.ThermalDiffusionType;
import com.hdf.cryptand.neoforge.powergrid.network.DestructionQueue;
import com.hdf.cryptand.neoforge.powergrid.persistence.NetworkCacheManager;
import com.hdf.cryptand.neoforge.powergrid.state.DevicePowerStore;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import java.util.List;
import java.util.Map;

/**
 * ===== 主线程后处理：后台结果落世界 =====
 *
 * <p>从 {@code PhasorPipeline} 拆出。后台 round 结束前把结果压进 {@code POST_NETS}
 * 队列，主线程每 tick 取出，本类负责把「组装器侧静态存储 / 引擎消息」翻译成
 * 世界侧可见效果——<b>这里不做任何物理计算</b>（发热/储能/温度已由后台
 * {@code EngineThermalCompute} 算完），只做：
 *
 * <ul>
 *   <li>{@link #syncDevicePowerMessages} 设备功率/电流上报消息 → 实际 BE 参数</li>
 *   <li>{@link #consumeBecToEngineMotorMessages} BE→引擎电机消息回灌</li>
 *   <li>{@link #checkDeviceOverheat} 过热销毁（含防重复冷却）</li>
 *   <li>{@link #collectThermalDiffusion} / {@link #applyThermalDiffusion} 热扩散
 *       （当前默认关闭，保留设计：主线程采集 → 后台纯虚拟传播）</li>
 * </ul>
 *
 * <p>线程约定：本类<b>只允许主线程调用</b>（除 {@code applyThermalDiffusion} 的
 * 纯虚拟传播分支外，其设计意图是在后台 round 内执行）。
 */
public final class PipelinePostProcess {

    private PipelinePostProcess() {}

    static void syncDevicePowerMessages(Level level) {
        try {
            // ⚠ 2026-09-11 用户：读取改增量、变化即上报——只处理【值变化过】的条目
            //（DevicePowerStore 后台写入时标记），不再每 tick 全量快照 + 逐设备 getBlockEntity。
            java.util.List<Object[]> snap =
                    DevicePowerStore.drainChanged();
            if (snap.isEmpty()) return;
            for (Object[] e : snap) {
                net.minecraft.core.BlockPos pos = (net.minecraft.core.BlockPos) e[0];
                double powerW = (Double) e[1];
                net.minecraft.world.level.block.entity.BlockEntity be =
                        level.getBlockEntity(pos);
                if (be == null) continue;
                try {
                    // 只与绑定接口交互（2026-08-26 用户）
                    if (!(be instanceof com.hdf.cryptand.neoforge.powergrid.device
                            .ICryptandCircuitBe icc)) continue;
                    BeMessageParser bridge =
                            com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser.cache(be);
                    if (bridge == null
                            || bridge.protocol()
                            != com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser.Protocol.POWER) continue;
                    icc.cryptandOnEngineMessage(
                            new com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessage(powerW));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 每 tick 消费电机 BE→引擎消息（2026-08-27 断电滑行修复）。
     * <p>PowerGridPlus 电机 BE 经 PowerGridMotorParser.serverTick 每 tick 上报：
     * <ul>
     *   <li>有 Create 网络：[λ, networkStressSU, 容量SU, networkRadS]</li>
     *   <li>无网络（断电/剪线）：空心跳（size=0）</li>
     * </ul>
     * 组装器侧在此消费 → 更新 {@code ElectroMachineModel} 的网络负载参数。
     * 空心跳 → 清零（负载网络脱离，转纯空载滑行，不再按旧负载 1s 归零）。
     */
    static void consumeBecToEngineMotorMessages(Level level) {
        if (level == null) return;
        try {
            // ⚠ 2026-09-11 用户架构：主线程【只做前处理消息化】——把 BE 上报投递到
            // NetworkMessageBus（同位置覆盖式合并），**不定位元件、不写引擎模型**；
            // 模型只在异步线程的【网络计算前】被写（applyPendingMessages）→
            // 彻底消除"主线程写模型 vs 后台求解读模型"的竞态。
            for (net.minecraft.core.BlockPos pos
                    : BeMessageParser.pendingInputPositions()) {
                try {
                    BeMessage msg = BeMessageParser.pollInput(pos);
                    if (msg == null) continue;
                    com.hdf.cryptand.engine.NetworkMessageBus.post(pos.asLong(),
                            com.hdf.cryptand.engine.NetworkMessageBus.TYPE_PARAM, msg);
                } catch (Throwable ignored) {
                }
            }
            com.hdf.cryptand.engine.NetworkMessageBus.sweepStale(); // 超时清扫
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【异步 · 网络计算前】消费并应用本网络的消息（2026-09-11 用户架构）。
     * <p>主线程前处理只投递（NetworkMessageBus），引擎模型【只在引擎线程被写】→
     * 消除「主线程写模型 vs 后台求解读模型」竞态；计算完成后按需发送消息回主线程后处理。
     */
    static void applyPendingMessages(Object netId, PhasorNetworkContext ctx) {
        if (ctx == null || ctx.network == null || ctx.blockTerminals == null
                || ctx.blockTerminals.isEmpty()) return;
        java.util.List<Long> keys = new java.util.ArrayList<>(ctx.blockTerminals.size());
        for (net.minecraft.core.BlockPos p : ctx.blockTerminals.keySet()) {
            if (p != null) keys.add(p.asLong());
        }
        // 2026-09-11 用户架构：全局池 → 【本网络消息缓存池】（上限 20；超限由
        // NetworkMessageBus 合并，合并后仍超 → 丢弃最旧 + 打印），随后取走应用。
        com.hdf.cryptand.engine.NetworkMessageBus.transferToPool(netId, keys);
        for (Object[] e : com.hdf.cryptand.engine.NetworkMessageBus.drainPool(netId)) {
            try {
                if (!(e[1] instanceof com.hdf.cryptand.engine.NetworkMessageBus.Entry en)) {
                    continue;
                }
                if (en.type != com.hdf.cryptand.engine.NetworkMessageBus.TYPE_PARAM) continue;
                if (!(en.payload instanceof BeMessage msg)) continue;
                net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of((Long) e[0]);
                com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel em =
                        findMachineIn(ctx.network, pos);
                if (em != null) applyMotorMessage(em, msg);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 在引擎网络中按 pos 查电机复合元件（compositeKey 以 "D" 开头且位置匹配）。 */
    private static com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel
            findMachineIn(com.hdf.cryptand.circuitsimulation.model.Network net,
                          net.minecraft.core.BlockPos pos) {
        if (net == null || pos == null) return null;
        for (com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement ce
                : net.composites()) {
            if (!(ce instanceof com.hdf.cryptand.circuitsimulation.model.composite
                    .ElectroMachineModel em)) continue;
            String ck = ce.compositeKey();
            if (ck == null || !ck.startsWith("D")) continue;
            if (pos.equals(WireKeyUtil.posOfComposite(ck))) return em;
        }
        return null;
    }

    /** 应用 BE→引擎上报：空心跳（size=0）= 无网/断电 → 清零（纯空载滑行）；
     *  ≥3 元素 = [λ, networkStressSU, 容量SU, networkRadS] 全量参数。 */
    static void applyMotorMessage(
            com.hdf.cryptand.circuitsimulation.model.composite.ElectroMachineModel em,
            BeMessage msg) {
        int sz = msg.size();
        if (sz == 0) {
            em.setLoadRatio(0);
            em.setNetworkStressSU(0);
            em.setNetworkRadS(0);
            em.setNetworkConnected(false); // 断电
        } else if (sz >= 3) {
            double lambda = (msg.raw(0) instanceof Number n0) ? n0.doubleValue() : 0;
            double stress = (msg.raw(1) instanceof Number n1) ? n1.doubleValue() : 0;
            double capacity = (msg.raw(2) instanceof Number n2) ? n2.doubleValue() : 0;
            double netRadS = (sz >= 4 && msg.raw(3) instanceof Number n3)
                    ? n3.doubleValue() : 0;
            em.setLoadRatio(lambda);
            em.setNetworkStressSU(stress);
            em.setNetworkRadS(netRadS);
            // ⚠ 供电判据：网络【有源容量 capacity>0】（空网络/仅电机 → 断电）
            em.setNetworkConnected(capacity > 0);
        }
    }

    /**
     * 设备过热运行时检查（2026-08-18 新增：此前只在构建时 Assembler.bindAllPos
     * 检查一次——设备升温后从不触发销毁 → "温度过高没烧线"）。每 tick 主线程
     * 检查 Cryptand 设备温度模型（DeviceThermalStore）与 ThermalBehaviour 原版
     * 方块温度，超限 → 销毁设备方块 + 烧断相连的全部导线段（同段统一烧毁）。
     * ⚠ 防反复冷却：销毁请求后 30s 内同 pos 不重复（否则设备未销毁期间每轮
     * 检查都触发 → 反复销毁/重建风暴 → 卡死）。
     */
    private static final java.util.Map<BlockPos, Long> DEVICE_BURN_COOLDOWN =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long DEVICE_BURN_COOLDOWN_MS = 30_000L;

    static void checkDeviceOverheat(Level level) {
        try {
            long now = System.currentTimeMillis();
            // 收集所有过热设备：pos + 温度（DeviceThermalStore 或 ThermalBehaviour）
            java.util.List<Object[]> over = new java.util.ArrayList<>();
            for (BlockPos pos : DeviceThermalStore.keys()) {
                try {
                    com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel th =
                            DeviceThermalStore.thermalFor(pos);
                    double temp = th.tempCelsius();
                    // ⚠ 2026-08-30 审计 U10 根因：过温阈值读取该 pos 实际
                    //   ThermalModel.maxTemp（thermalFor 200°C / thermalForHighTemp
                    //   400°C），不再硬编码 200——否则高耐温设备（加热器等 400°C
                    //   上限）在 200°C 即被主线程销毁，与引擎侧 400°C 烧毁阈值
                    //   双路径矛盾（thermalForHighTemp 形同虚设）。
                    double maxC = th.maxTemp - 273.15;
                    // ⚠ 2026-09-12 用户："温度不再使用原版路径，全部使用自管"——
                    //   过热判定只看自管温度模型（原版 ThermalBehaviour 已不参与推进，
                    //   取它的值只会把 22°C 假值当作"未过热"而漏判）。
                    boolean isOver = temp >= maxC;
                    if (isOver) over.add(new Object[]{pos, temp});
                } catch (Throwable ignored) {
                }
            }
            if (over.isEmpty()) return;
            // 只销毁【温度最高】的过热设备（先断短路 → 其余元件冷却 → 不连锁爆炸）：
            // 短路回路中最高温者（如 1939°C 的电阻）先烧 → 导线烧断 → 其他（如
            // 213°C 的加热器）自然冷却，避免"重新接线导致加热器爆炸"。
            over.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
            BlockPos hottest = (BlockPos) over.get(0)[0];
            double hottestTemp = (Double) over.get(0)[1];
            Long last = DEVICE_BURN_COOLDOWN.get(hottest);
            if (last != null && now - last < DEVICE_BURN_COOLDOWN_MS) return;
            DEVICE_BURN_COOLDOWN.put(hottest, now);
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[DeviceBurn] {} 过热销毁（{}°C，over={}）",
                    hottest, String.format("%.0f", hottestTemp), over.size());
            // 先确保设备从网络移除（绑定模型销毁 → 网络重建后不再建模）
            DeviceBinding db =
                    DeviceBinding.forPos(hottest);
            if (db != null) {
                try { db.notifyModelDestroyed(); } catch (Throwable ignored) { }
            }
            // 标记该位置正因过热销毁（mixin setRemoved 据此跳过导线清理——
            // 2026-08-19：设备过热销毁【不再烧断相连导线】，导线保留悬垂，由
            // WireDanglingDetector 悬空检测平滑断开（懒检测可配置周期 + 爆炸
            // 强制检测），避免"20A 金导线未过热却被 50Ω 电阻爆炸连带烧断"）
            // 2026-09-12 用户："爆炸全部接管，按照配置来设置爆炸效果"——过热爆炸由
            // 自管判定驱动（原版 ThermalBehaviour 的过热演化与爆炸已随 tick 停用），
            // 威力/破坏方块/火焰全部走 powergrid 配置。默认只有视觉效果、不破坏方块
            // （设备本身仍由下面的 DestructionQueue 销毁）。
            try {
                // ⚠ 2026-09-12 用户："爆炸后方块没消失必须被更新，要求先触发方块拆除再爆炸"
                //   ——原先在这里【先爆炸】、拆除却走队列（且被 EXPLOSION_MODE=0 早退），
                //   于是"爆炸了但方块还在"。现在拆除+爆炸统一由
                //   DestructionQueue.destroyModel 负责（它内部就是先 destroyBlock 再 explode），
                //   本处只负责【立即触发】它，不再自行爆炸。
                if (false && com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                        .OVERHEAT_EXPLOSION_ENABLED.get()) {
                    double power = com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                            .OVERHEAT_EXPLOSION_POWER.get();
                    boolean fire = com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                            .OVERHEAT_EXPLOSION_FIRE.get();
                    boolean destroyBlocks =
                            com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                                    .OVERHEAT_EXPLOSION_DESTROY_BLOCKS.get();
                    level.explode(null,
                            hottest.getX() + 0.5, hottest.getY() + 0.5, hottest.getZ() + 0.5,
                            (float) power, fire,
                            destroyBlocks ? Level.ExplosionInteraction.BLOCK
                                    : Level.ExplosionInteraction.NONE);
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[DeviceBurn] explosion pos={} power={} destroyBlocks={} fire={}",
                            hottest, String.format("%.2f", power), destroyBlocks, fire);
                }
            } catch (Throwable ignored) {
            }
            DestructionQueue
                    .markOverheatBurning(hottest);
            // 销毁设备方块（异步队列，主线程处理）
            DestructionQueue.request(
                    level, hottest, level.getBlockEntity(hottest), "过热销毁");
            // ⚠ 2026-09-12 实测（日志 02:02:26 判定过热 → 02:02:39 才销毁，隔 13 秒）：
            //   队列 process 的判定要求 cur 与 entry.be 是【同一实例】才执行销毁，
            //   实测该条件未命中 → 队列项被直接 remove，方块留到之后才被
            //   NetworkDestructionDetector 兜底销毁 → 玩家看到"要等旁边方块更新才破坏"。
            //   改为【当场直接调用 destroyModel】：拆除 + 爆炸一步到位（内部顺序仍是
            //   先 destroyBlock 再 explode），不再依赖队列时序与实例比对。
            // 2026-09-12 用户："发送爆炸消息到主线程，主线程根据方块信息交给具体的 BE
            //   类进行处理（通过消息函数内部处理，默认处理为爆炸+破坏此方块，可以被继承
            //   方便进行扩展操作）"——改为【投递爆炸消息】，由 BE 的 cryptandOnExplode
            //   内部处理（默认 destroyModel：先拆方块再爆炸；设备可覆写扩展）。
            //   这里紧接着 process 一次 = 立即在主线程分发，避免"延迟破坏"。
            try {
                // 2026-09-15 去引用：不再携带 BE 引用（EngineBus.post 已无该参数），
                //  主线程消费时按 pos 自己取 BE。
                EngineBus.post(EngineBus.Type.EXPLODE, hottest, "过热销毁");
                EngineBus.process(level);
            } catch (Throwable ignored) {
            }
            // 不再主动烧断相连导线——设备销毁后由悬空检测自动断开
        } catch (Throwable ignored) {
        }
    }

    /**
     * 热扩散（2026-08-18 用户需求）：【只有带温度扩散模型的组装器】能向周围
     * 元件传导温度——普通设备默认不扩散，不会被周围高温设备影响。
     * 扩散分多种类型（见 {@link com.hdf.cryptand.neoforge.powergrid.device.thermal.ThermalDiffusionType}）：
     *   1. 仅输出（OUTPUT_ONLY）：只向周围传热（源更热 → 邻居），不接受外部输入
     *   2. 仅输入（INPUT_ONLY）：只接受外部传热（邻居更热 → 源），不向周围输出
     *   3. 双向（BIDIRECTIONAL）：热 → 冷双向交互
     * 方向按配置（默认全方向）；【扩散距离】按配置沿方向传播多格；
     * 【衰减度】每格按 (1−attenuation)^(d−1) 衰减——0 = 到距离上限前同样传递。
     * 相邻/沿途方块必须已有温度模型才接收热量。
     */
    /** 热扩散参数快照（主线程采集 → 后台数值计算；组装器侧数据，无 Level/BE 引用） */
    static final class DiffusionParams {
        final java.util.Set<net.minecraft.core.Direction> dirs;
        final int distance;
        final double attenuation;
        final double conductance;
        final com.hdf.cryptand.neoforge.powergrid.device.thermal.ThermalDiffusionType type;
        DiffusionParams(java.util.Set<net.minecraft.core.Direction> dirs, int distance,
                        double attenuation, double conductance,
                        com.hdf.cryptand.neoforge.powergrid.device.thermal.ThermalDiffusionType type) {
            this.dirs = dirs; this.distance = distance; this.attenuation = attenuation;
            this.conductance = conductance; this.type = type;
        }
    }

    /** 热扩散参数表（主线程写 / 后台读；引擎侧数据，与 DeviceThermalStore 同层） */
    private static final java.util.Map<BlockPos, DiffusionParams> DIFFUSION_PARAMS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 【主线程】采集热扩散参数（读世界：组装器配置 / 方块朝向 / 风机距离）→ 参数表。
     * <p>2026-09-11（用户：所有计算为引擎异步计算，主线程仅接收）：主线程只做世界
     * 采集，扩散的数值传播与传热在后台 {@link #applyThermalDiffusion()}（纯虚拟：
     * 只读参数表 + DeviceThermalStore 温度模型）。降频调用（默认每 4 tick）。
     */
    static void collectThermalDiffusion(Level level) {
        try {
            java.util.Set<BlockPos> live = DeviceThermalStore.keys();
            DIFFUSION_PARAMS.keySet().retainAll(live); // 设备消失 → 参数同步清理
            for (BlockPos pos : live) {
                try {
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                    if (be == null) { DIFFUSION_PARAMS.remove(pos); continue; }
                    Assembler asm = Assemblers.get(be);
                    if (asm == null) { DIFFUSION_PARAMS.remove(pos); continue; }
                    ThermalDiffusionConfig cfg = asm.thermalDiffusion();
                    if (cfg == null || cfg.conductance <= 0) {
                        DIFFUSION_PARAMS.remove(pos);
                        continue;
                    }
                    // 方向解析：前后方向（forwardBackward）→ 按方块朝向 + 反方向
                    java.util.Set<net.minecraft.core.Direction> dirs = cfg.directions;
                    if (cfg.forwardBackward) {
                        net.minecraft.core.Direction facing = readFacing(be);
                        dirs = java.util.Set.of(facing, facing.getOpposite());
                    }
                    // 风机增强：被鼓风机/风机吹时【扩散距离 = 风机吹的最大距离】
                    // （热风沿气流传播多格）；读不到风机距离 → 兜底 +blownBonus
                    int dist = cfg.distance;
                    if (cfg.blownBonus > 0) {
                        int fanDist = fanMaxDistance(level, pos);
                        if (fanDist > 0) {
                            dist = fanDist;
                        } else if (isBlownByFan(level, pos)) {
                            dist += cfg.blownBonus;
                        }
                    }
                    DIFFUSION_PARAMS.put(pos, new DiffusionParams(dirs, dist, cfg.attenuation,
                            cfg.conductance, cfg.type));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 【后台引擎线程】热扩散数值计算（纯虚拟：仅读参数表 + DeviceThermalStore 温度
     * 模型，不读 Level/BE）。语义与旧主线程版本一致：只有带温度扩散模型的组装器
     * 才向周围元件传导温度——
     *   1. 仅输出（OUTPUT_ONLY）：只向周围传热（源更热 → 邻居）
     *   2. 仅输入（INPUT_ONLY）：只接受外部传热（邻居更热 → 源）
     *   3. 双向（BIDIRECTIONAL）：热 → 冷双向交互
     * 参数来自 {@link #collectThermalDiffusion(Level)}（主线程采集）。
     */
    static void applyThermalDiffusion() {
        try {
            for (java.util.Map.Entry<BlockPos, DiffusionParams> en : DIFFUSION_PARAMS.entrySet()) {
                try {
                    BlockPos pos = en.getKey();
                    DiffusionParams p = en.getValue();
                    if (!DeviceThermalStore.contains(pos)) continue;
                    com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel self =
                            DeviceThermalStore.thermalFor(pos);
                    double tSelf = self.getTemperature();
                    double falloff = 1.0 - p.attenuation; // 每格保留比例（0~1）
                    for (net.minecraft.core.Direction dir : p.dirs) {
                        double factor = 1.0;
                        for (int d = 1; d <= p.distance; d++) {
                            if (factor <= 1e-6) break; // 衰减到 0 → 不再传播
                            net.minecraft.core.BlockPos np = pos.relative(dir, d);
                            if (!DeviceThermalStore.contains(np)) {
                                factor *= falloff; // 该格无温度模型 → 继续衰减向后传
                                continue;
                            }
                            com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel nb =
                                    DeviceThermalStore.thermalFor(np);
                            double dT = tSelf - nb.getTemperature(); // 正 = 源更热
                            switch (p.type) {
                                case OUTPUT_ONLY:
                                    if (dT <= 0.5) { factor *= falloff; continue; }
                                    {
                                        double heat = p.conductance * factor * dT * 0.05; // dt=1 tick
                                        self.addHeat(-heat);
                                        nb.addHeat(heat);
                                    }
                                    break;
                                case INPUT_ONLY:
                                    if (dT >= -0.5) { factor *= falloff; continue; }
                                    {
                                        double heat = p.conductance * factor * (-dT) * 0.05;
                                        nb.addHeat(-heat);
                                        self.addHeat(heat);
                                    }
                                    break;
                                case BIDIRECTIONAL:
                                    if (Math.abs(dT) <= 0.5) { factor *= falloff; continue; }
                                    {
                                        double heat = p.conductance * factor * dT * 0.05;
                                        self.addHeat(-heat);
                                        nb.addHeat(heat);
                                    }
                                    break;
                            }
                            factor *= falloff; // 衰减度：每格衰减（0 = 到上限前同样传递）
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 方块朝向（前后扩散用；无 HORIZONTAL_FACING 属性 → 默认 NORTH） */
    private static net.minecraft.core.Direction readFacing(
            net.minecraft.world.level.block.entity.BlockEntity be) {
        try {
            net.minecraft.world.level.block.state.BlockState st = be.getBlockState();
            if (st.hasProperty(net.minecraft.world.level.block.state.properties
                    .BlockStateProperties.HORIZONTAL_FACING)) {
                return st.getValue(net.minecraft.world.level.block.state.properties
                        .BlockStateProperties.HORIZONTAL_FACING);
            }
        } catch (Throwable ignored) {
        }
        return net.minecraft.core.Direction.NORTH;
    }

    /** 是否被鼓风机/风机吹（ThermalBehaviour 冷却倍率 > 1，反射同 computeDeviceHeatOne） */
    private static boolean isBlownByFan(Level level, BlockPos pos) {
        try {
            com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour tb =
                    com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
                            .get(level, pos,
                                    org.patryk3211.powergrid.electricity.base.ThermalBehaviour.TYPE);
            if (tb instanceof org.patryk3211.powergrid.electricity.base.ThermalBehaviour thb) {
                java.lang.reflect.Field f = org.patryk3211.powergrid.electricity.base.ThermalBehaviour.class
                        .getDeclaredField("totalCoolingFactorMultiplier");
                f.setAccessible(true);
                float cm = f.getFloat(thb);
                return cm > 1f;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 覆盖该方块的风机最大吹风距离（格）：反射读 ThermalBehaviour 冷却源 Map
     *  （key = AirCurrent）→ getMaxDistance()。读不到 → -1（调用方兜底）。 */
    private static int fanMaxDistance(Level level, BlockPos pos) {
        try {
            com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour tb =
                    com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour
                            .get(level, pos,
                                    org.patryk3211.powergrid.electricity.base.ThermalBehaviour.TYPE);
            if (tb instanceof org.patryk3211.powergrid.electricity.base.ThermalBehaviour thb) {
                // 反射找冷却源字段（Map<AirCurrent, strength> 等）
                for (java.lang.reflect.Field f :
                        org.patryk3211.powergrid.electricity.base.ThermalBehaviour.class
                                .getDeclaredFields()) {
                    if (!java.util.Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object m = f.get(thb);
                    if (m instanceof java.util.Map<?, ?> map && !map.isEmpty()) {
                        Object ac = map.keySet().iterator().next();
                        if (ac instanceof com.simibubi.create.content.kinetics.fan.AirCurrent air) {
                            // AirCurrent 无 getMaxDistance（在 IAirCurrentSource 上）——
                            // 反射读其 source 字段取风机最大吹风距离
                            try {
                                java.lang.reflect.Method gm = air.getClass().getMethod("getMaxDistance");
                                if (gm != null) {
                                    float md = ((Number) gm.invoke(air)).floatValue();
                                    return Math.max(1, (int) Math.ceil(md));
                                }
                            } catch (Throwable ignored2) {
                            }
                            try {
                                java.lang.reflect.Field sf =
                                        com.simibubi.create.content.kinetics.fan.AirCurrent.class
                                                .getDeclaredField("source");
                                sf.setAccessible(true);
                                Object src = sf.get(air);
                                if (src instanceof
                                        com.simibubi.create.content.kinetics.fan.IAirCurrentSource ics) {
                                    float md = ics.getMaxDistance();
                                    return Math.max(1, (int) Math.ceil(md));
                                }
                            } catch (Throwable ignored2) {
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

}
