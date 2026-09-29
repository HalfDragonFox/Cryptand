package com.hdf.cryptand.neoforge.net;

import com.hdf.cryptand.neoforge.inc.config.ConfigInc;
import com.hdf.cryptand.integratednetwork.*;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 维度网络总管理类 / 句柄（2026-08-29 P2 neoforge 平台：即用户架构的「网络
 * 总管理类」，每 ServerLevel 一个，操作该维度的 {@link IntegratedNetworkCore}）。
 * <p>
 * 能力：
 * <ul>
 *   <li>【设备注册】{@link #registerDevice}/{@link #unregisterDevice}——把实现
 *       {@link INetworkDevice} 的 BE 建为「单管道一网络」（节点=端口，边=设备内
 *       输入→输出搬运通道）；</li>
 *   <li>【主线程门面 + 收集器】{@link #tick}——每 tick 收集各设备输入 → 组
 *       「输入→输出→内容→规则」表 → {@code submitTransfer} →  нагрева TICK；</li>
 *   <li>【结果写回主线程队列】核心线程 {@code listener} 只把执行表入队
 *       {@link #execQueue}，主线程 {@link #drainExecQueue} 统一消费，按表对容器
 *       直接输入/输出（与电路写回同构，遵守核心不碰 Level/BE）。</li>
 * </ul>
 * 生命周期由 {@link IntegratedNetworkPlatform} 管理（Level Load/Unload 进/出）。
 */
public final class DimensionNetworkManager {

    private final ServerLevel level;
    private final IntegratedNetworkCore core;
    /** 默认设备搬运通道吞吐（单位/帧；设备未给速率时使用） */
    private static final double DEFAULT_TP = 8.0;

    /** 帧数累加器：按配置频率(Hz)/20 折算每 tick 提交的 TICK 帧数，余数保留到下 tick */
    private double tickAccumulator = 0.0;

    /** 全局 tick 计数（§8 节流用） */
    private long tickCounter = 0;
    /** 网络 key → 上次 tickReports 的全局 tick 计数（§8 节流：BFS 变化检测降频） */
    private final java.util.concurrent.ConcurrentHashMap<Object, Long> lastReportTick =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** tickReports(BFS) 扫描间隔（§8：每 N tick 全量检测一次；变化检测延迟 ≤ 间隔，
     *   Pipez 原版节流同思路；10 万设备的 BFS 主线程开销降 N 倍） */
    private static final long REPORT_SCAN_INTERVAL = 40;

    /** 主线程结果队列：核心线程归档，主线程 tick 出队（写回通道） */
    private final ConcurrentLinkedQueue<TransferExecutionTable> execQueue =
            new ConcurrentLinkedQueue<>();

    /** 设备注册表：networkKey → 设备（主线程建网/收集/执行操作） */
    private final ConcurrentHashMap<Object, INetworkDevice> devices = new ConcurrentHashMap<>();

    /** 维度网络物品缓存（2026-08-29 用户方案 B：预提取→缓存所有权→交付；随存档持久化） */
    private final NetworkBuffer buffer = new NetworkBuffer();
    /** 网络缓存存档句柄（Level Load 时从盘加载到 buffer） */
    private final IncNetworkBufferSavedData savedData;
    /** 主线程侧审计日志（线程名 = Server thread，与核心异步线程对比验证异步） */
    private static final org.apache.logging.log4j.Logger LOG =
            CryptandNeoForge.WAF_LOGGER;

    /** 主线程审计日志（含线程名） */
    private static void log(String msg) {
        logAt(msg, true);
    }

    private static void logAt(String msg, boolean withThread) {
        try {
            if (withThread) {
                LOG.info("[INC-mgr:" + Thread.currentThread().getName() + "] " + msg);
            } else {
                LOG.info("[INC-mgr] " + msg);
            }
        } catch (Throwable t) {
            System.err.println("[INC-mgr] LOG FAIL: " + t);
        }
    }

    DimensionNetworkManager(ServerLevel level, IntegratedNetworkCore core) {
        this.level = level;
        this.core = core;
        // 网络缓存持久化：进世界从存档恢复（退出世界缓存仍在 → 重进正常传输）
        this.savedData = IncNetworkBufferSavedData.get(level, buffer);
        // 结果写回：核心线程回调只入队（绝不做 MC 主线程调用）。
        // ★ 2026-09 P0-2 根因修复：只在【传输结算】回调（onTransferSettled，
        //   仅 executeTransfer 产出执行表时触发一次）入队；onFrame 是普通流动
        //   帧（每个 TICK 帧都触发），绝不据此读「最近执行表」——否则同一张表
        //   每 tick 反复入队 → 同一批物品反复 take/写/回池、归属统计错乱。
        core.setListener(new TransportListener() {
            @Override
            public void onFrame(Object key, TransportResult frame) {
                // 普通流动帧：不携带执行表，不做任何投递
            }

            @Override
            public void onTransferSettled(Object key, TransferExecutionTable table) {
                if (table != null && !table.isEmpty()) {
                    execQueue.offer(table);
                }
            }
        });
    }

    public ServerLevel level() {
        return level;
    }

    public IntegratedNetworkCore core() {
        return core;
    }

    /** 设备 key → 接口节点的世界内唯一 id */
    public static Object ifaceNodeId(Object deviceKey, Object ifaceId) {
        return deviceKey + "|" + ifaceId;
    }

    // ===== 设备注册（主线程；建「单管道一网络」） =====

    public void registerDevice(INetworkDevice dev) {
        if (dev == null) return;
        Object key = dev.networkKey();
        if (devices.putIfAbsent(key, dev) != null) return;
        lastReportTick.put(key, 0L); // §8：新设备本 tick 立即扫描（首次建网不等节流周期）
        TransferType cap = dev.networkCapability();
        double tp = dev.defaultRate() > 0 ? dev.defaultRate() : DEFAULT_TP;
        List<TransportChange> changes = new ArrayList<>();
        for (NetworkInterface in : dev.inputInterfaces()) {
            changes.add(TransportChange.addNode(ifaceNodeId(key, in.id), cap,
                    in.x, in.y, in.z, 0));
        }
        for (NetworkInterface out : dev.outputInterfaces()) {
            changes.add(TransportChange.addNode(ifaceNodeId(key, out.id), cap,
                    out.x, out.y, out.z, 0));
        }
        // 结构节点（管道段：纯结构信息，无搬运语义；2026-08-29 用户：上报管道 BE）
        for (NetworkInterface s : structureNodes(dev)) {
            changes.add(TransportChange.addNode(ifaceNodeId(key, s.id), cap,
                    s.x, s.y, s.z, 0));
        }
        // 设备内「输入端口 → 输出端口」搬运通道（表示该设备能把输入搬到输出）
        for (NetworkInterface in : dev.inputInterfaces()) {
            for (NetworkInterface out : dev.outputInterfaces()) {
                changes.add(TransportChange.addEdge(ifaceNodeId(key, in.id),
                        ifaceNodeId(key, out.id), cap, tp, 0, 0, 1));
            }
        }
        core.submitTopology(key, changes);
    }

    /** 设备结构节点（扩展点：Pipez 管道段等纯结构信息；默认空） */
    private static java.util.List<NetworkInterface> structureNodes(INetworkDevice dev) {
        if (dev instanceof IStructuredDevice pa) {
            return pa.structureNodes();
        }
        return java.util.List.of();
    }

    public void unregisterDevice(Object networkKey) {
        INetworkDevice d = devices.remove(networkKey);
        if (d == null) return;
        List<TransportChange> changes = new ArrayList<>();
        for (NetworkInterface in : d.inputInterfaces()) {
            changes.add(TransportChange.removeNode(ifaceNodeId(networkKey, in.id)));
        }
        for (NetworkInterface out : d.outputInterfaces()) {
            changes.add(TransportChange.removeNode(ifaceNodeId(networkKey, out.id)));
        }
        for (NetworkInterface s : structureNodes(d)) {
            changes.add(TransportChange.removeNode(ifaceNodeId(networkKey, s.id)));
        }
        core.submitDestroy(networkKey, changes);
        // 拆除：网络上残留缓存物品 → 掉落（不静默吞物品）
        List<ItemStack> leftover = buffer.drain(networkKey);
        if (!leftover.isEmpty()) {
            double x = 0, y = 0, z = 0;
            List<NetworkInterface> all = new ArrayList<>(d.inputInterfaces());
            all.addAll(d.outputInterfaces());
            if (!all.isEmpty()) {
                NetworkInterface ifc = all.get(0);
                x = ifc.x + 0.5; y = ifc.y + 0.5; z = ifc.z + 0.5;
            }
            for (ItemStack st : leftover) {
                level.addFreshEntity(new ItemEntity(level, x, y, z, st));
            }
            savedData.setDirty();
        }
    }

    public INetworkDevice device(Object networkKey) {
        return devices.get(networkKey);
    }

    public java.util.Collection<INetworkDevice> devices() {
        return devices.values();
    }

    /** 缓存池数量（诊断，/cryptand inc 端点用） */
    public int bufferPoolCount() {
        return buffer.poolCount();
    }

    /** 待交付执行表队列大小（诊断，/cryptand inc 端点用） */
    public int execQueueSize() {
        return execQueue.size();
    }

    // ===== 主线程门面：预提取(限速)→缓存→上报→交付(回池) → TICK =====

    /** 每 tick（主线程，ServerTickEvent.Post）驱动该维度全部设备 */
    public void tick() {
        // ⓪ 接口/容器【上报】（2026-08-29 用户：请求重新返回整个网络的连接情况
        //    ——全量快照；核心按 id 幂等增删。图节点由 diff（增量）或 forceFullReport/
        //    retryDue 触发时全量重建（核心按连通分量自动拆/合，类仿真引擎 SPLIT_MERGE）。
        tickCounter++;
        for (INetworkDevice dev : devices.values()) {
            Object key = dev.networkKey();
            // ★ 2026-09 §8 活跃设备列表（节流版）：tickReports(BFS) 每
            //   REPORT_SCAN_INTERVAL tick 检测一次（变化发现延迟 ≤ 2 秒，
            //   事件触发置 needsRescan 由下次扫描消费；preExtract/reportBuffer
            //   仍每 tick 按 buffer 有货轻量处理）——10 万设备的 BFS 主线程
            //   开销降 N 倍，正确性不变（周期扫描保证最终一致）。
            if (tickCounter - lastReportTick.getOrDefault(key, 0L) >= REPORT_SCAN_INTERVAL) {
                lastReportTick.put(key, tickCounter);
                java.util.List<NetworkReport> reports = dev.tickReports();
                if (!reports.isEmpty()) {
                    core.submitReport(key, reports);
                    applyInterfaceDiff(dev);
                }
                // ★ 2026-09 P0-1 根因修复：设备 BFS 后已无任何结构节点/接口
                //   （整簇拆完）→ 空壳设备卸载（图节点已随增量 diff 移除；
                //   缓存残留物品掉落，不静默吞）
                if (dev instanceof IStructuredDevice sd
                        && sd.structureNodes().isEmpty()
                        && dev.inputInterfaces().isEmpty()
                        && dev.outputInterfaces().isEmpty()) {
                    log("empty-cluster unload net=" + key);
                    unregisterDevice(key);
                }
            }
        }
        // ① 按每 tick 传输速率限量【预提取】源→网络缓存（2026-08-29 用户：
        //    防止一次性全量缓存；速率在 BE 基类 INetworkDevice.transferRate() 定义）
        for (INetworkDevice dev : devices.values()) preExtract(dev);
        // ② 交付：按执行表从缓存取真实物品写目标，写不进的回池（不丢，下轮重试）
        drainExecQueue();
        // ③ 缓存量 → 核心分配（产出新执行表）
        for (INetworkDevice dev : devices.values()) reportBuffer(dev);
        // ④ 流动帧
        int frames = tickFrames();
        if (frames <= 0) return;
        for (Object key : devices.keySet()) {
            for (int i = 0; i < frames; i++) core.submitTick(key);
        }
        // 缓存有货 → 标记存档 dirty（退出世界缓存仍在）
        if (buffer.poolCount() > 0) savedData.setDirty();
    }

    /**
     * 按配置的 INC 异步检查频率（默认 20Hz）折算本 tick 应提交的 TICK 帧数。
     * 服务器固定 20 TPS：20Hz = 每 tick 1 帧；40Hz = 每 tick 2 帧。
     * 每次 tick 累加 Hz/20，取整提交，余数保留（不足 1 帧的本 tick 跳过）。
     */
    private int tickFrames() {
        double hz = ConfigInc.INC_TICK_FREQUENCY_HZ.get();
        tickAccumulator += hz / 20.0;
        int frames = (int) tickAccumulator;
        tickAccumulator -= frames;
        return frames;
    }

    /** 每 tick 分配消费值上限（§13 消费值联动；后续可配置化） */
    private static final int ALLOC_BUDGET_MAX = 256;

    /**
     * 本 tick 下发给异步核心的分配消费值：execQueue 交付积压越大 → 消费值越低
     * （主线程交付跟不上 → 异步放缓，不积压不膨胀）；空闲给满。
     */
    private int allocBudget() {
        int penalty = (int) Math.min(ALLOC_BUDGET_MAX, execQueue.size() * 4);
        return Math.max(8, ALLOC_BUDGET_MAX - penalty);
    }

    /** 预提取：该设备每 tick 按 transferRate() 限量从各输入接口拉真实内容 → 缓存 */
    private void preExtract(INetworkDevice dev) {
        Object key = dev.networkKey();
        // 2026-08-29 用户：当没有输出时跳过传输——无可用输出口则既不抽也不入缓存
        // （避免物品囤积在输入端缓存，等输出容器到位后再恢复）
        if (!hasUsableOutput(dev)) return;
        // 2026-08-29 用户：如果输出的容器都满了则不要进行传输——预提取前也拦截
        if (allOutputsFull(dev)) {
            return; // 全部输出满 → 不抽不入缓存（背包/缓存不膨胀）
        }
        double rate = dev.transferRate();
        if (rate <= 0) rate = DEFAULT_TP; // 未定义速率 → 默认每 tick 限量
        // 2026-08-29 用户：缓存单实例上限可配置（默认 64×速率）。到顶停止预提取，
        // 防止输入端缓存无限囤积爆内存。
        double cap = ConfigInc.INC_BUFFER_CAP_MULTIPLIER.get() * rate;
        int current = buffer.count(key);
        if (cap > 0 && current >= cap) return; // 缓存满 → 本次跳过
        double remain = cap > 0 ? Math.max(0.0, cap - current) : Double.MAX_VALUE;
        List<NetworkInterface> ins = dev.inputInterfaces();
        int n = Math.max(1, ins.size());
        // 每 tick 总量不超过 rate，且不超过缓存剩余空间（均分各输入面）
        double perIface = Math.min(rate / n, remain / n);
        int pulled = 0;
        for (NetworkInterface in : ins) {
            if (dev.isDisabled(in.id)) continue; // 已卸载接口跳过（2026-08-29 用户）
            List<Object> got = dev.pull(in.id, perIface);
            if (got == null || got.isEmpty()) continue;
            for (Object o : got) {
                if (o instanceof ItemStack st) {
                    // 2026-08-30 用户：记录来源输入接口 id（解析器按接口归属分配）
                    buffer.add(key, in.id, List.of(st));
                    pulled += st.getCount();
                }
            }
        }
    }

    /** 设备是否有可用（非 disabled 且**未满**）输出口；无 → 本次跳过传输
     *  （2026-08-29 用户：箱子满了排除该容器；全部满/无输出 → 不抽不入缓存） */
    private boolean hasUsableOutput(INetworkDevice dev) {
        for (NetworkInterface out : dev.outputInterfaces()) {
            if (dev.isDisabled(out.id)) continue;
            if (!dev.isOutputFull(out.id)) return true; // 有未满输出 → 可用
        }
        return false;
    }

    /**
     * 所有输出容器是否【全部满/被排除】（2026-08-29 用户：输出容器全满则不传输；
     * 满容器从输出排除）。至少一个未满 → false（可继续）；无输出 → false
     * （由 hasUsableOutput 拦截）；全部满 → true（预提取/上报前整体跳过）。
     */
    private boolean allOutputsFull(INetworkDevice dev) {
        boolean any = false;
        for (NetworkInterface out : dev.outputInterfaces()) {
            if (dev.isDisabled(out.id)) continue;
            any = true;
            if (!dev.isOutputFull(out.id)) return false; // 有未满的 → 继续传输
        }
        return any; // 有输出且全满 → true；无输出 → false（由 hasUsableOutput 拦截）
    }

    /**
     * 应用【接口增量 diff】（2026-08-29 用户：只发增量，核心自动拆/合）。
     * 把设备本 tick 的新增/移除接口转成 {@link TransportChange} 增量指令：
     * <ul>
     *   <li>新增输入 → addNode + 锚点边（接口→所属管道段）；</li>
     *   <li>移除输入 → removeNode（核心连带边/缓冲/在途，按连通分量自动拆分）；</li>
     *   <li>新增输出 → addNode + 锚点边（接口→所属管道段）；</li>
     *   <li>移除输出 → removeNode；</li>
     *   <li>结构节点（管道段）→ addNode + p-p 段间边（2026-08-30 用户：
     *       真实管道拓扑，距离/传播/时间基于 p-p 边）。</li>
     * </ul>
     * 2026-08-30 变更：不再建 in↔out 直连边——物品走真实管道路径：
     *   in接口 →锚点→ 段网络(p-p边) →锚点→ out接口 →(距离=段数,时间=距离×段耗时)
     */
    private void applyInterfaceDiff(INetworkDevice dev) {
        if (!(dev instanceof IStructuredDevice pa)) return;
        Object key = dev.networkKey();
        TransferType cap = dev.networkCapability();
        double tp = dev.defaultRate() > 0 ? dev.defaultRate() : DEFAULT_TP;
        var diff = pa.interfaceDiff();
        if (diff.isEmpty()) return;
        List<TransportChange> changes = new ArrayList<>();
        // 1) 移除：先 removeNode（解除旧结构，核心按连通分量自动拆分）
        for (Object id : diff.removedInputs()) {
            changes.add(TransportChange.removeNode(ifaceNodeId(key, id)));
        }
        for (Object id : diff.removedOutputs()) {
            changes.add(TransportChange.removeNode(ifaceNodeId(key, id)));
        }
        for (Object id : diff.removedStructs()) {
            changes.add(TransportChange.removeNode(ifaceNodeId(key, id)));
        }
        // 2) 新增结构节点（管道段）+ p-p 段间边（2026-08-30：真实拓扑）；
        //    锚点映射（接口 → 所属段 pos）由 pa.ifaceAnchor() 提供
        java.util.Map<Object, net.minecraft.core.BlockPos> anchors = pa.ifaceAnchor();
        java.util.Map<Object, Object> ifaceIdByAnchor = new java.util.HashMap<>();
        for (NetworkInterface s : diff.addedStructs()) {
            changes.add(TransportChange.addNode(ifaceNodeId(key, s.id), cap,
                    s.x, s.y, s.z, 0));
        }
        // 结构节点 id（"p:x,y,z"）→ 图节点 id
        java.util.function.Function<net.minecraft.core.BlockPos, Object> pipeNode =
                pos -> ifaceNodeId(key, "p:" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        // 本次"涉及的段集合"（addedStructs 的 pos）——p-p 边只对【至少一端是
        // 本次新增段】的对建边（增量正确：已存在的段对不重复提交平行边）
        java.util.Set<net.minecraft.core.BlockPos> addedPipe =
                new java.util.HashSet<>();
        for (NetworkInterface s : diff.addedStructs()) {
            addedPipe.add(new net.minecraft.core.BlockPos((int) s.x, (int) s.y, (int) s.z));
        }
        for (net.minecraft.core.BlockPos[] e : pa.pipeEdges()) {
            boolean anyNew = addedPipe.contains(e[0]) || addedPipe.contains(e[1]);
            if (!anyNew) continue; // 两端都是已有段 → 边已存在，跳过
            Object a = pipeNode.apply(e[0]);
            Object b = pipeNode.apply(e[1]);
            changes.add(TransportChange.addEdge(a, b, cap, tp, 1, 0, 1));
        }
        // 3) 新增输入节点 + 锚点边（接口 → 所属段）
        for (NetworkInterface in : diff.addedInputs()) {
            Object inId = ifaceNodeId(key, in.id);
            changes.add(TransportChange.addNode(inId, cap, in.x, in.y, in.z, 0));
            net.minecraft.core.BlockPos anchor = anchors.get(in.id);
            if (anchor != null) {
                changes.add(TransportChange.addEdge(inId, pipeNode.apply(anchor), cap, tp, 0, 0, 1));
            }
        }
        // 4) 新增输出节点 + 锚点边（接口 → 所属段）
        for (NetworkInterface out : diff.addedOutputs()) {
            Object outId = ifaceNodeId(key, out.id);
            changes.add(TransportChange.addNode(outId, cap, out.x, out.y, out.z, 0));
            net.minecraft.core.BlockPos anchor = anchors.get(out.id);
            if (anchor != null) {
                changes.add(TransportChange.addEdge(outId, pipeNode.apply(anchor), cap, tp, 0, 0, 1));
            }
        }
        if (!changes.isEmpty()) {
            core.submitTopology(key, changes);
            log("ifaceDiff net=" + key + " +IN=" + diff.addedInputs().size()
                    + " -IN=" + diff.removedInputs().size()
                    + " +OUT=" + diff.addedOutputs().size()
                    + " -OUT=" + diff.removedOutputs().size()
                    + " +P=" + diff.addedStructs().size()
                    + " -P=" + diff.removedStructs().size());
        }
    }

    /** 上报：该网络缓存已有内容 → 组表走核心分配（规则 = 设备 transferRule） */
    private void reportBuffer(INetworkDevice dev) {
        Object key = dev.networkKey();
        int avail = buffer.count(key);
        if (avail <= 0) return; // 空缓存：不上报（核心空箱背压自动退避）
        List<Object> ins = new ArrayList<>();
        for (NetworkInterface in : dev.inputInterfaces()) {
            ins.add(ifaceNodeId(key, in.id));
        }
        List<Object> outs = new ArrayList<>();
        List<Object> validOuts = new ArrayList<>();
        for (NetworkInterface out : dev.outputInterfaces()) {
            if (dev.isDisabled(out.id)) continue; // 已卸载接口跳过（2026-08-29 用户）
            // 2026-08-29 用户：如果输出的容器都满了则不要进行传输——
            // 满容器从输出排除；任一非满即可继续；全部排除则整体跳过（不提交）。
            if (dev.isOutputFull(out.id)) continue;
            // ⚠ 2026-08-29 关键修复：outs 必须用与 ins 一致的图节点 id
            //   （ifaceNodeId = networkKey + "|" + 接口id，带前缀）！之前裸 id
            //   导致核心 executeTransfer 里 g.node(out)==null → 全部过滤 →
            //   setWait → execQueue=0，物品永卡缓存（与 /cryptand inc 中
            //   waiting=true、execQueue=0、bufferPools>0 完全吻合）。
            validOuts.add(ifaceNodeId(key, out.id));
        }
        outs.addAll(validOuts);
        if (outs.isEmpty()) {
            // 全部输出被排除（满/disabled）→ 不发送转移列表（静默，每 tick 会走）
            return;
        }
        if (ins.isEmpty() || outs.isEmpty()) return;
        // 2026-08-30 用户：请求发送"网络中缓存物品以及物品所在接口的 id"——
        //   每条 item = 一个物品 id + 数量（data=PipezBufferedItem），供
        //   PipezItemResolver 按输出过滤表计算各输入消耗/各输出分配。
        java.util.Map<Object, Integer> ifaceCounts = buffer.ifaceCounts(key);
        java.util.List<TransportPayload> items = new ArrayList<>();
        // 缓存快照的物品 id（真实 ItemStack → 注册名 id，供过滤器匹配）
        java.util.List<ItemStack> snap = buffer.snapshot(key);
        java.util.Map<String, Double> byId = new java.util.LinkedHashMap<>();
        for (ItemStack st : snap) {
            if (st.isEmpty()) continue;
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(st.getItem()).toString();
            byId.merge(itemId, (double) st.getCount(), Double::sum);
        }
        if (!byId.isEmpty()) {
            for (java.util.Map.Entry<String, Double> e : byId.entrySet()) {
                items.add(new TransportPayload(dev.networkCapability(), e.getValue(), null,
                        new com.hdf.cryptand.integratednetwork.PipezAbstractResolver
                                .PipezBufferedItem(e.getKey(), e.getValue())));
            }
        } else {
            // 无缓存物品详情（空/降级）→ 聚合单条（解析器视全接受）
            items = List.of(new TransportPayload(dev.networkCapability(), avail, null,
                    new com.hdf.cryptand.integratednetwork.PipezAbstractResolver
                            .PipezBufferedItem(null, avail)));
        }
        // 图元数据：解析器 id + 管道列表（首次/变化时同步；后续增量）
        syncNetworkMeta(dev);
        // 2026-08-29 用户：完全接管原版行为——分配规则跟随 Pipez Distribution 配置
        // ★ 2026-09 §13 消费值联动：主线程每 tick 下发分配预算——交付积压
        //   （execQueue 大）→ 消费值降 → 异步放缓（背压，防主线程卡顿/积压膨胀）
        core.submitTransfer(key, new TransportTransferRequest(ins, outs,
                items, dev.transferRule(), allocBudget()));
    }

    /**
     * 同步网络元数据到图（2026-08-30 用户：网络存储解析器 id + 管道列表；
     * 每次解析由核心按解析器 id 调用；接口 Object 参数已在提交时由图接口携带）。
     * 仅变化时提交（低频；省消息）。
     */
    private void syncNetworkMeta(INetworkDevice dev) {
        if (!(dev instanceof IStructuredDevice pa)) return;
        Object key = dev.networkKey();
        com.hdf.cryptand.integratednetwork.TransportGraph g = core.graph(key);
        if (g == null) return;
        g.setResolverId("pipez:item"); // 默认通用管道解析器；自定义解析器后续注册
        java.util.List<Object> pipes = new ArrayList<>();
        for (com.hdf.cryptand.integratednetwork.NetworkInterface s : pa.structureNodes()) {
            pipes.add(s.id);
        }
        g.setPipes(pipes);
    }

    /**
     * 主线程出队并应用执行表：从【网络缓存】取真实物品交付目标，写不进的回池。
     * 输入端不再从真实源抽取（预提取阶段已把物品移入缓存——竞态消除，
     * 源被拿走/清空不影响）。
     */
    private void drainExecQueue() {
        // 先快照整队列再处理：避免处理中 re-offer 未到期条目被同一循环
        // 再次 poll（FIFO 无限循环），也保证每张表本 tick 只消费一次。
        List<TransferExecutionTable> tables = new ArrayList<>();
        TransferExecutionTable table;
        while ((table = execQueue.poll()) != null) tables.add(table);
        List<TransferExecutionTable> defer = new ArrayList<>();
        for (TransferExecutionTable t : tables) {
            if (t.outputs.isEmpty()) continue;
            List<TransferExecutionTable.Entry> due = new ArrayList<>();
            List<TransferExecutionTable.Entry> later = new ArrayList<>();
            for (TransferExecutionTable.Entry en : t.outputs) {
                INetworkDevice dev = deviceByNode(en.containerKey);
                if (dev == null) continue; // 设备已卸载 → 条目作废（图节点已移除）
                Object key = dev.networkKey();
                // ★ 2026-09 P1-3 根因修复：延迟交付按【图步号】到期才执行——
                //   未到期条目本 tick 不动（物品留在缓存），重排下轮再查。
                //   （此前 arriveAt 算而不执行 → 物品无视管道长度瞬间到终点）
                if (en.arriveAt > 0 && currentStep(key) < en.arriveAt) {
                    later.add(en);
                    continue;
                }
                due.add(en);
            }
            if (!due.isEmpty()) {
                applyDelivery(new TransferExecutionTable(t.step, List.of(), due));
            }
            if (!later.isEmpty()) {
                defer.add(new TransferExecutionTable(t.step, List.of(), later));
            }
        }
        for (TransferExecutionTable d : defer) execQueue.offer(d);
    }

    /** 交付一批已到期条目：缓存取物 → 写目标 → 回池（P1-4：按承诺量销账） */
    private void applyDelivery(TransferExecutionTable table) {
        for (TransferExecutionTable.Entry en : table.outputs) {
            INetworkDevice dev = deviceByNode(en.containerKey);
            if (dev == null) continue;
            Object key = dev.networkKey();
            List<ItemStack> taken = buffer.take(key, (int) Math.round(en.amount));
            if (taken.isEmpty()) {
                // 实物已不在缓存（被其它条目/前表消费）→ 该承诺作废，按表量销账
                consumePending(en.containerKey, en.amount, key);
                continue;
            }
            List<Object> contents = new ArrayList<>(taken);
            List<Object> remaining = dev.deliveryWrite(en.containerKey, contents);
            // 写不进去的 → 原样回池（目标满不丢，下轮重新上报分配）
            int takenCount = 0;
            for (ItemStack s : taken) takenCount += s.getCount();
            int remainCount = 0;
            for (Object o : remaining) {
                if (o instanceof ItemStack s) {
                    buffer.putBack(key, List.of(s));
                    remainCount += s.getCount();
                }
            }
            // ★ 2026-09 P1-4 根因修复：pendingWrites 是「承诺量」账本——本批承诺
            //   已处理完毕（写进的到目标、回池的归还 buffer 等待重新分配）→
            //   按【表量】销账；此前按 delivered 扣 → 回池物留下残账 → 无限涨。
            consumePending(en.containerKey, en.amount, key);
            log("deliver net=" + key + " out=" + en.containerKey
                    + " plan=" + en.amount + " taken=" + takenCount
                    + " remaining=" + remainCount + " delivered=" + (takenCount - remainCount));
        }
    }

    /** 当前图步号（核心线程推进；主线程读，用于延迟交付到期判断） */
    private long currentStep(Object key) {
        com.hdf.cryptand.integratednetwork.TransportGraph g = core.graph(key);
        return g == null ? 0 : g.step();
    }

    private void consumePending(Object containerId, double amount, Object networkKey) {
        com.hdf.cryptand.integratednetwork.TransportGraph g = core.graph(networkKey);
        if (g != null) g.consumePendingWrite(containerId, amount);
    }

    private INetworkDevice deviceByNode(Object nodeId) {
        String prefix = String.valueOf(nodeId);
        for (INetworkDevice d : devices.values()) {
            if (prefix.startsWith(String.valueOf(d.networkKey()) + "|")) return d;
        }
        return null;
    }

    /** 调试信息 */
    @Override
    public String toString() {
        return "DimensionNetworkManager{dim=" + level.dimension().location()
                + ", devices=" + devices.size() + ", execQueue=" + execQueue.size() + "}";
    }
}