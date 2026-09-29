package com.hdf.cryptand.integratednetwork;

import com.hdf.cryptand.integratednetwork.TransportChange.Kind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;

/**
 * 核心传输执行器（2026-08-26 集成网络核心）：传输执行器的内置默认实现。
 * <p>
 * 在纯虚拟传输图（{@link TransportGraph}）上运行通用物流/无线电流动：
 *   - 拥有每个传输网的图 + 仿真器（{@link TransportSimulator}），按网隔离；
 *   - 应用拓扑变更（{@link TransportChange}）到图（增/删节点、边）；
 *   - 执行流动推进（{@link #executeTick}），缓存最近帧结果并经
 *     {@link #setListener} 在核心线程发布；
 *   - 帧结果缓存 {@code result(key)} 供主线程直接查询（消费式 |
 *     事件队列式，见监听器）。
 * <p>
 * 本类只操作纯虚拟图，绝不碰 MC 对象（核心铁律）。后续平台接入管道/无线电
 * 时可替换为平台实现（{@link TransportExecutor}），或对本类图做世界映射。
 */
public final class CoreTransportExecutor implements TransportExecutor {

    /** 空箱/背压默认等待帧数（2026-08-29：无传输需求时网络进入等待期） */
    private static final int DEFAULT_WAIT_TICKS = 20;

    /**
     * 审计日志钩子（2026-08-29 用户：打印日志查看管道路线是否异步 + 详细传输信息）。
     * 平台层可注入（如 neoforge 接到 log4j）；null = 静默。核心关键路径
     * （拓扑/TICK/TRANSFER/拆合）打印【线程名】——核心线程与主线程名
     * （Server thread）不同即证路由/传输是异步执行的。
     */
    public static volatile java.util.function.Consumer<String> AUDIT;

    /** 审计日志（含调用线程名；钩子为空则静默） */
    private static void audit(String msg) {
        java.util.function.Consumer<String> a = AUDIT;
        if (a != null) {
            try {
                a.accept("[" + Thread.currentThread().getName() + "] " + msg);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 惰性审计（2026-09 S4：msg 在钩子非空时才拼接——plan 等大对象的 toString
     *  不能无条件执行，10 万条目 plan 的字符串拼接是隐蔽的每 transfer 大开销） */
    private static void auditLazy(java.util.function.Supplier<String> msg) {
        java.util.function.Consumer<String> a = AUDIT;
        if (a != null) {
            try {
                a.accept("[" + Thread.currentThread().getName() + "] " + msg.get());
            } catch (Throwable ignored) {
            }
        }
    }

    /** 传输网键 → 传输图（纯虚拟） */
    private final ConcurrentMap<Object, TransportGraph> graphs = new ConcurrentHashMap<>();
    /** 传输网键 → 仿真器 */
    private final ConcurrentMap<Object, TransportSimulator> sims = new ConcurrentHashMap<>();
    /** 随机源（RANDOM 分配用，2026-08-29） */
    private final java.util.Random rng = new java.util.Random();
    /** 传输网键 → 最近帧结果（volatile 桥：核心线程写、任意线程读） */
    private final ConcurrentMap<Object, TransportResult> results = new ConcurrentHashMap<>();

    /** 帧监听器（核心线程回调；平台层收集事件，主线程随后应用副作用） */
    private volatile TransportListener listener;

    /** 累计统计（跨步长期累计：送达/丢弃/移动量），executeTick 内累加 */
    private final ConcurrentMap<Object, Tally> tallies = new ConcurrentHashMap<>();
    /** 最近传输执行表：模拟结算后缓存，供主线程据此直接输入/输出（内存态） */
    private final ConcurrentMap<Object, TransferExecutionTable> exec = new ConcurrentHashMap<>();

    /** 设置帧监听器（回调在核心线程执行，勿做 MC 主线程调用） */
    public void setListener(TransportListener l) {
        this.listener = l;
    }

    /** 当前监听器（诊断） */
    public TransportListener listener() {
        return listener;
    }

    // ===== 图访问 =====

    /** 注册/替换已建好的传输图（键不存在自动建） */
    public void register(Object key, TransportGraph g) {
        if (key == null || g == null) return;
        graphs.put(key, g);
        sims.put(key, new TransportSimulator(g));
        results.remove(key);
        tallies.remove(key);
        exec.remove(key);
    }

    /** 注销传输图（清空图与结果） */
    public TransportGraph unregister(Object key) {
        if (key == null) return null;
        sims.remove(key);
        results.remove(key);
        tallies.remove(key);
        exec.remove(key);
        return graphs.remove(key);
    }

    /** 查询传输图（未注册返回 null） */
    public TransportGraph graph(Object key) {
        return graphs.get(key);
    }

    /** 查询仿真器（未注册返回 null） */
    public TransportSimulator simulator(Object key) {
        return sims.get(key);
    }

    /** 最近一次帧结果（未运行返回 null） */
    public TransportResult result(Object key) {
        return results.get(key);
    }

    /** 累计送达负载数（跨步长期累计；未运行 0） */
    public long delivered(Object key) {
        Tally t = tallies.get(key);
        return t == null ? 0 : t.delivered.get();
    }

    /** 累计丢弃负载数（跨步长期累计；未运行 0） */
    public long dropped(Object key) {
        Tally t = tallies.get(key);
        return t == null ? 0 : t.dropped.get();
    }

    /** 累计移入在途的总量（跨步长期累计；未运行 0） */
    public double moved(Object key) {
        Tally t = tallies.get(key);
        return t == null ? 0 : t.moved();
    }

    /** 已注册传输图数量 */
    public int graphCount() {
        return graphs.size();
    }

    /** 清空全部图/仿真器/结果（世界卸载/实例释放） */
    public void clear() {
        graphs.clear();
        sims.clear();
        results.clear();
        tallies.clear();
        exec.clear();
    }

    // ===== TransportExecutor 实现 =====

    @Override
    public boolean executeDestroy(Object key, Object data) {
        TransportGraph g = graph(key);
        if (g == null) return false;
        boolean any = false;
        for (TransportChange c : changes(data)) {
            if (c == null) continue;
            if (c.kind == TransportChange.Kind.REMOVE_NODE) {
                g.removeNode(c.id);
                any = true;
            } else if (c.kind == TransportChange.Kind.REMOVE_EDGE) {
                g.removeEdge(((Number) c.id).longValue());
                any = true;
            }
        }
        return any;
    }

    @Override
    public boolean executeTopology(Object key, Object data) {
        TransportGraph g = graphs.computeIfAbsent(key, k -> new TransportGraph());
        TransportSimulator s = sims.computeIfAbsent(key, k -> new TransportSimulator(g));
        boolean any = false;
        int n = 0;
        for (TransportChange c : changes(data)) {
            if (c == null) continue;
            n++;
            any |= applyChange(g, c);
        }
        audit("TOPOLOGY net=" + key + " changes=" + n
                + " nodes=" + g.nodeCount() + " edges=" + g.edgeCount());
        // 路由在下一步自动重算（routesDirty 由图内部置位）；可显式 submitRoute
        return any;
    }

    @Override
    public void executeRoute(Object key, Object data) {
        TransportSimulator s = sims.get(key);
        if (s != null) s.computeRoutes();
    }

    @Override
    public void executeTick(Object key, Object data) {
        TransportSimulator s = sims.get(key);
        if (s == null) return;
        TransportGraph g = s.graph();
        int frames = data instanceof Integer i ? Math.max(1, i) : 1;

        // 空箱/背压等待：等待期跳过流动推进（只走步号，省计算）
        if (g.waiting(g.step())) {
            for (int i = 0; i < frames; i++) g.incrementStep();
            return;
        }

        TransportListener l = listener;
        Tally tally = tallies.computeIfAbsent(key, k -> new Tally());
        TransportResult last = null;
        for (int i = 0; i < frames; i++) {
            last = s.step();
            results.put(key, last);      // 缓存帧结果（任意线程可读）
            tally.add(last);             // 累计送达/丢弃/移动量
            if (l != null) {
                try {
                    l.onFrame(key, last); // 核心线程发布
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public boolean executeSplitMerge(Object key, Object data) {
        if (!(data instanceof TransportSplitMerge sm)) return false;
        if (sm.mode == TransportSplitMerge.Mode.MERGE) {
            TransportGraph base = graphs.get(sm.a);
            TransportGraph other = graphs.get(sm.b);
            if (base == null || other == null || base == other) return false;
            audit("SPLIT_MERGE net=" + key + " mode=MERGE a=" + sm.a + " b=" + sm.b);
            base.mergeFrom(other);
            detach(sm.b);               // 并入后注销 b
            return true;
        }
        // SPLIT：按连通分量拆分，原图注销，分量注册为 key[0] key[1] ...
        TransportGraph src = graphs.get(sm.a);
        if (src == null) return false;
        List<TransportGraph> parts = src.split();
        detach(sm.a);
        int i = 0;
        for (TransportGraph part : parts) {
            register(key + "[" + i + "]", part);
            i++;
        }
        return true;
    }

    @Override
    public boolean executeReport(Object key, Object data) {
        TransportGraph g = graph(key);
        if (g == null) return false;
        boolean any = false;
        for (NetworkReport r : reports(data)) {
            if (r == null) continue;
            switch (r.kind) {
                case ADD_INTERFACE -> {
                    g.addInterface(r.iface);
                    any = true;
                }
                case REMOVE_INTERFACE -> {
                    g.removeInterface(r.iface.id);
                    any = true;
                }
                case ADD_CONTAINER -> {
                    g.addContainer(r.container);
                    any = true;
                }
                case REMOVE_CONTAINER -> {
                    g.removeContainer(r.container.id);
                    any = true;
                }
            }
        }
        return any;
    }

    @Override
    public boolean executeTransfer(Object key, Object data) {
        if (!(data instanceof TransportTransferRequest req)) return false;
        TransportGraph g = graph(key);
        if (g == null) return false;
        TransportListener l = listener;
        long step = g.step();

        // 空箱/空需求 → 背压等待（未到等待期不重复结算）
        double total = 0;
        for (TransportPayload p : req.items) total += Math.max(0, p.amount);
        if (req.isEmpty() || total <= 0) {
            g.setWait(step, DEFAULT_WAIT_TICKS);
            return true;
        }

        // 过滤输出：节点必须存在；有容器信息时按过滤表（黑白名单）剔除不被接受的内容
        List<Object> outs = new ArrayList<>();
        for (Object out : req.outputs) {
            if (out == null || g.node(out) == null) continue;
            ContainerInfo ci = g.container(out);
            if (ci == null) {
                outs.add(out);
                continue;
            }
            boolean anyAccept = false;
            for (TransportPayload item : req.items) {
                if (ci.accepts(item == null ? null : item.data)) {
                    anyAccept = true;
                    break;
                }
            }
            if (anyAccept) outs.add(out);
        }
        if (outs.isEmpty()) {
            audit("TRANSFER net=" + key + " step=" + step
                    + " NO_VALID_OUT (filtered by container/accept) req.outputs=" + req.outputs);
            g.setWait(step, DEFAULT_WAIT_TICKS);
            return true;
        }

        // ★ 2026-08-30 用户：解析器系统——若有解析器（默认 "pipez:item"），优先按
        //   【缓存物品 + 接口参数】调解析器计算计划；否则回退图法分配。
        Map<Object, Double> plan = null;
        Map<Object, Long> arriveOf = new LinkedHashMap<>();
        TransportPlan resolverPlan = null;
        String rId = g.resolverId();
        TransportResolver resolver = TransportResolvers.getOrDefault(rId);
        if (resolver != null) {
            java.util.List<TransportResolveContext.ResolvedInterface> insList = new ArrayList<>();
            for (Object in : req.inputs) {
                NetworkInterface ni = g.interfaceAt(in);
                insList.add(new TransportResolveContext.ResolvedInterface(in,
                        ni == null ? -1 : ni.direction, ni == null ? 0 : ni.rate,
                        ni == null ? null : ni.param));
            }
            java.util.List<TransportResolveContext.ResolvedInterface> outsList = new ArrayList<>();
            for (Object out : outs) {
                NetworkInterface no = g.interfaceAt(out);
                outsList.add(new TransportResolveContext.ResolvedInterface(out,
                        no == null ? -1 : no.direction, no == null ? 0 : no.rate,
                        no == null ? null : no.param));
            }
            java.util.List<TransportResolveContext.BufferedItem> buffered = new ArrayList<>();
            for (TransportPayload item : req.items) {
                // 物品所属输入接口：默认首个输入（后续按 item.data 归属扩展）
                Object belongTo = req.inputs.isEmpty() ? null : req.inputs.get(0);
                buffered.add(new TransportResolveContext.BufferedItem(
                        belongTo, item.amount, item.type, item.data));
            }
            TransportResolveContext ctx = new TransportResolveContext(rId,
                    insList, outsList, g.pipes(), buffered, req.rule);
            resolverPlan = resolver.resolve(ctx);
            if (resolverPlan != null && !resolverPlan.isEmpty()) {
                plan = new LinkedHashMap<>(resolverPlan.outputAllocs);
            }
        }
        if (plan == null) {
            // ★ 2026-09 S3a 图法分配：输入驱动 TransportAllocator（每输入按
            //   自己的规则分发给可达候选输出；时间值 = Dijkstra 段时间之和）。
            //   替代传统总盘 allocate——EQUALIZE 在候选=全部输出时结果与旧一致。
            //   S4：返回 AllocOut（plan + 各输出最小到达步 arriveOf，避免产出段 O(N²)）
            AllocOut ao = allocatorAllocate(g, req, outs, total);
            plan = ao.plan();
            arriveOf = ao.arriveOf();
        }
        final Map<Object, Double> planForLog = plan; // lambda 捕获需 effectively final
        final double totalForLog = total;
        auditLazy(() -> "TRANSFER net=" + key + " step=" + step
                + " req{in=" + req.inputs.size() + " out=" + req.outputs.size()
                + " items=" + req.items.size() + " total=" + totalForLog
                + " rule=" + req.rule + " resolver=" + rId + "}"
                + " planSize=" + planForLog.size());

        // 内容引用（data）-主线程直 IO 用：取请求首个 item 的真实容器引用
        Object refData = req.items.isEmpty() ? null : req.items.get(0).data;
        // 产出传输执行表（主线程据此直接 IO）+ EXTRACT/WRITE 结算事件 + 待写表
        List<TransferExecutionTable.Entry> outEntries = new ArrayList<>();
        List<TransferExecutionTable.Entry> inEntries = new ArrayList<>();
        List<TransportEvent> evs = new ArrayList<>();
        double movedSum = 0;
        // ★ 2026-09 S4 根因修复：到达步不再 per-output 重算（旧实现每输出循环内
        //   hasPipeStruct 遍历全图 + 遍历全部输入 shortestDistance = O(N²)）。
        //   图法分配路径：arriveOf 由 TransportAllocator 的 T_ij（Dijkstra 段时间）
        //   提供（arriveAt = step + T_ij）；resolver 路径缺省：每输出一次最短距离
        //   （线性，仅管道图启用）。
        if (arriveOf.isEmpty() && hasPipeStruct(g) && !req.inputs.isEmpty()) {
            long segTicks = distSegTicks(g);
            if (segTicks > 0) {
                Object firstIn = req.inputs.get(0);
                for (Object out : plan.keySet()) {
                    int d = g.shortestDistance(firstIn, out);
                    arriveOf.put(out, d > 0 ? step + (long) d * segTicks : step);
                }
            }
        }
        for (Map.Entry<Object, Double> en : plan.entrySet()) {
            double amt = en.getValue();
            Object out = en.getKey();
            if (amt <= 0 || g.node(out) == null) continue;
            long arrive = arriveOf.getOrDefault(out, 0L);
            g.addPendingWrite(out, amt);
            outEntries.add(new TransferExecutionTable.Entry(out, amt, g.capability(),
                    refData, arrive));
            evs.add(new TransportEvent(TransportEvent.Kind.WRITE, out,
                    new TransportPayload(g.capability(), amt, out, refData), step, null));
            movedSum += amt;
        }
        double perInput = total / Math.max(1, req.inputs.size());
        for (Object in : req.inputs) {
            if (in == null || g.node(in) == null) continue;
            double amt = Math.min(perInput, movedSum);
            if (amt <= 0) continue;
            inEntries.add(new TransferExecutionTable.Entry(in, amt, g.capability(), refData, 0));
            evs.add(new TransportEvent(TransportEvent.Kind.EXTRACT, in,
                    new TransportPayload(g.capability(), amt, null, null), step, null));
        }
        // 缓存最近传输执行表（内存态；主线程查询后按表对容器直接输入/输出）
        TransferExecutionTable table = new TransferExecutionTable(step, inEntries, outEntries);
        exec.put(key, table);
        audit("TRANSFER net=" + key + " RESULT exec{in=" + inEntries.size()
                + " out=" + outEntries.size() + " movedSum=" + movedSum
                + " pendingWrites=" + g.pendingWriteTotal() + "}");

        // 结果缓存 + 监听器发布（同普通帧入口）
        TransportResult settle = new TransportResult(step, evs, movedSum,
                (int) g.pending(), System.nanoTime());
        results.put(key, settle);
        // ★ 2026-09 P0-2 根因修复：结算通知走【专用回调】只触发一次（普通帧
        //   executeTick 的 onFrame 不携带执行表）——平台层据此把表交给主线程
        //   执行 IO，杜绝「每帧重读最近执行表 → 同一批物品反复投递」。
        if (l != null) {
            try {
                l.onTransferSettled(key, table);
            } catch (Throwable ignored) {
            }
            if (!evs.isEmpty()) {
                try {
                    l.onFrame(key, settle);
                } catch (Throwable ignored) {
                }
            }
        }
        return true;
    }

    /** 是否为真实管道图（含 "p:" 前缀结构节点；2026-08-30 延迟交付仅管道启用） */
    private static boolean hasPipeStruct(TransportGraph g) {
        for (TransportNode n : g.nodes()) {
            if (n.id instanceof String s && s.contains("|p:")) return true;
        }
        return false;
    }

    /** 每段管道耗时(帧)：取图内 p-p 边(连通段)的 latencyTicks；无正 latency 边
     *  → 0（虚拟直连网络立即交付，不延迟）。2026-08-30 用户：时间 = 距离×段耗时。 */
    private static long distSegTicks(TransportGraph g) {        long ticks = 0;
        for (TransportEdge e : g.edges()) {
            if (e.latencyTicks > 0) {
                ticks = e.latencyTicks;
                break; // 同构网络段耗时一致，取首个正 latency 边
            }
        }
        return Math.max(0, ticks);
    }

    /** 图法分配并行度（分片数；0/1 = 串行。系统属性 cryptand.allocShards 可调） */
    private static final int PARALLEL_SHARDS =
            Math.max(0, Integer.getInteger("cryptand.allocShards", 4));

    /**
     * ★ 2026-09 S3a/S3b 图法分配：输入驱动——每个输入按 TransportTransferRequest 的规则
     * 把「输入槽供应量」（= 总量均分各输入）分发给【可达候选输出】（已过滤剪枝）。
     * 时间值 = forwardTimes（Dijkstra，权重=边段时间 latencyTicks）。
     * 双槽语义：输出槽无限（无容量约束）；Σ分配 = 输入槽总量守恒。
     * <p>
     * S3b 分片并行：活跃输入按 W 片提交 ThreadDispatcher（虚拟线程池）并行执行——
     * 每片只读图（CHM 弱一致）+ 写自己的局部 plan（零共享写），完成后同步合并
     * （allOf.join）。同步等待发生在本网络 op 锁内（AsyncTransportManager 每网
     * 独立 op → 锁仅同网络内使用，跨网络并行不受阻塞）。
     * 版本屏障：分配期间拓扑变化 → 该帧作废（返回空 plan，下 tick 重算）。
     */
    private AllocOut allocatorAllocate(TransportGraph g,
                                        TransportTransferRequest req,
                                        List<Object> outs, double total) {
        // 活跃输入（节点存在）
        List<Object> ins = new ArrayList<>();
        for (Object in : req.inputs) {
            if (in != null && g.node(in) != null) ins.add(in);
        }
        if (ins.isEmpty() || outs.isEmpty()) return new AllocOut(new LinkedHashMap<>(), new LinkedHashMap<>());
        // ★ 2026-09 §13 消费值联动：主线程每 tick 下发 req.budget——只处理窗口内
        //   活跃输入（轮转指针覆盖全量），余量下 tick；budget<=0 = 不限（全量）。
        //   每输入供应仍按【原输入数】均分（总量守恒：多 tick 窗口累加 = 全量）。
        ins = g.nextAllocWindow(ins, req.budget);
        double perIn = total / req.inputs.size();
        int W = Math.max(1, PARALLEL_SHARDS);
        if (W > ins.size()) W = Math.max(1, ins.size());
        if (W <= 1) {
            return allocatorShard(g, ins, outs, perIn, req.rule, g.step());
        }
        // 分片（哈希均分，稳定序）
        List<List<Object>> shards = new ArrayList<>(W);
        for (int s = 0; s < W; s++) shards.add(new ArrayList<>());
        int idx = 0;
        for (Object in : ins) shards.get((idx++) % W).add(in);
        // 版本屏障起点
        long v0 = g.version();
        AllocOut[] partials = new AllocOut[W];
        ThreadDispatcher dispatcher = ThreadDispatchers.get();
        CompletableFuture<?>[] futs = new CompletableFuture<?>[W];
        java.util.Set<Object> targetSet = new java.util.HashSet<>(outs);
        for (int s = 0; s < W; s++) {
            final int si = s;
            final List<Object> shard = shards.get(s);
            futs[s] = dispatcher.submitGeneric(() -> {
                partials[si] = allocatorShard(g, shard, outs, perIn, req.rule, g.step());
            });
        }
        CompletableFuture.allOf(futs).join(); // 同网络 op 锁内同步等待
        // 版本屏障：拓扑变了 → 该帧作废（不产出，下 tick 重算）
        if (g.version() != v0) return new AllocOut(new LinkedHashMap<>(), new LinkedHashMap<>());
        Map<Object, Double> plan = new LinkedHashMap<>();
        Map<Object, Long> arriveOf = new LinkedHashMap<>();
        for (AllocOut p : partials) {
            if (p == null) continue;
            for (Map.Entry<Object, Double> en : p.plan().entrySet()) {
                plan.merge(en.getKey(), en.getValue(), Double::sum);
            }
            for (Map.Entry<Object, Long> en : p.arriveOf().entrySet()) {
                arriveOf.merge(en.getKey(), en.getValue(), Math::min);
            }
        }
        return new AllocOut(plan, arriveOf);
    }

    /** 单个分片（一批输入）的图法分配：每输入 Dijkstra 时间值 → 按规则分发 */
    private AllocOut allocatorShard(TransportGraph g, List<Object> ins,
                                    List<Object> outs, double perIn,
                                    DistributionRule rule, long step) {
        Map<Object, Double> partial = new LinkedHashMap<>();
        Map<Object, Long> arriveOf = new LinkedHashMap<>();
        if (ins.isEmpty() || outs.isEmpty()) return new AllocOut(partial, arriveOf);
        TransportAllocator alloc = new TransportAllocator();
        java.util.Set<Object> targetSet = new java.util.HashSet<>(outs);
        for (Object in : ins) {
            if (in == null || g.node(in) == null) continue;
            Map<Object, Integer> times = alloc.forwardTimes(g, in, targetSet);
            if (times.isEmpty()) continue; // 无可达输出
            // ★ 2026-09 S4 根因修复：只遍历【可达输出】（times 结果，通常极小）——
            //   此前对每个输入遍历全部 outs（10 万）→ 10 万输入 × 10 万 outs = O(N²)
            List<TransportAllocator.Out> cands = new ArrayList<>();
            for (Map.Entry<Object, Integer> en : times.entrySet()) {
                if (targetSet.contains(en.getKey())) {
                    cands.add(new TransportAllocator.Out(en.getKey(), Double.MAX_VALUE, null));
                }
            }
            if (cands.isEmpty()) continue;
            TransportAllocator.In inRec = new TransportAllocator.In(
                    in, perIn, null, rule, null);
            TransportAllocator.Plan p = alloc.allocateForInput(g, inRec, cands, times, step);
            for (Map.Entry<Object, Double> en : p.outputAllocs().entrySet()) {
                partial.merge(en.getKey(), en.getValue(), Double::sum);
            }
            // ★ 2026-09 S4 根因修复：分配阶段已算 T_ij（arriveAt）——合并取最小到达步，
            //   产出段直接复用，不再每输出重算 hasPipeStruct/shortestDistance（O(N²)）
            for (TransferExecutionTable.Entry en : p.outEntries()) {
                arriveOf.merge(en.containerKey, en.arriveAt, Math::min);
            }
        }
        return new AllocOut(partial, arriveOf);
    }

    /** 图法分配结果：输出→数量 + 输出→最小到达步（2026-09 S4） */
    private record AllocOut(Map<Object, Double> plan, Map<Object, Long> arriveOf) {
    }

    /** 按规则把总量分配给各输出 */
    private Map<Object, Double> allocate(TransportTransferRequest req, List<Object> outs,
                                         TransportGraph g, double total) {
        Map<Object, Double> plan = new LinkedHashMap<>();
        int n = outs.size();
        switch (req.rule) {
            case EQUALIZE -> {
                double per = total / n;
                for (Object o : outs) plan.put(o, per);
            }
            case ORDERED -> plan.put(outs.get(0), total);
            case ROUND_ROBIN -> {
                double[] share = new double[n];
                double remain = total;
                int idx = 0;
                while (remain > 0) {
                    double unit = Math.min(remain, 1.0);
                    share[idx % n] += unit;
                    remain -= unit;
                    idx++;
                }
                for (int i = 0; i < n; i++) plan.put(outs.get(i), share[i]);
            }
            case NEAREST -> {
                outs.sort(Comparator.comparingDouble(o -> distOf(g.container(o))));
                plan.put(outs.get(0), total);
            }
            case FURTHEST -> {
                outs.sort(Comparator.comparingDouble(o -> -distOf(g.container(o))));
                plan.put(outs.get(0), total);
            }
            case RANDOM -> {
                plan.put(outs.get(rng.nextInt(n)), total);
            }
            case FASTEST -> {
                outs.sort(Comparator.comparingDouble(o -> -rateOf(g.container(o))));
                plan.put(outs.get(0), total);
            }
        }
        return plan;
    }

    private static double distOf(ContainerInfo c) {
        return c == null ? Double.MAX_VALUE : c.distance;
    }

    private static double rateOf(ContainerInfo c) {
        return c == null ? 0 : c.rate;
    }

    /** 注销一个网络的全部注册项（图/仿真器/结果/统计/执行表） */
    private void detach(Object key) {
        graphs.remove(key);
        sims.remove(key);
        results.remove(key);
        tallies.remove(key);
        exec.remove(key);
    }

    /** 载荷归一：单条 NetworkReport 或 List<NetworkReport> → 迭代 */
    private static Iterable<NetworkReport> reports(Object data) {
        List<NetworkReport> out = new ArrayList<>();
        if (data instanceof NetworkReport r) {
            out.add(r);
        } else if (data instanceof Iterable<?> it) {
            for (Object o : it) if (o instanceof NetworkReport r) out.add(r);
        }
        return out;
    }

    // ===== 内部 =====

    /** 把单条拓扑变更应用到图 */
    private boolean applyChange(TransportGraph g, TransportChange c) {
        switch (c.kind) {
            case ADD_NODE -> {
                double[] p = c.pos;
                return g.addNode(new TransportNode(c.id, c.type,
                        p == null ? 0 : p[0], p == null ? 0 : p[1], p == null ? 0 : p[2],
                        c.capacity));
            }
            case REMOVE_NODE -> {
                g.removeNode(c.id);
                return true;
            }
            case ADD_EDGE -> {
                return g.addEdge(new TransportEdge(g.nextEdgeId(), c.a, c.b, c.type,
                        c.throughput, c.latencyTicks, c.loss, c.cost));
            }
            case REMOVE_EDGE -> {
                g.removeEdge(((Number) c.id).longValue());
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 载荷归一：单条 TransportChange 或 List<TransportChange> → 迭代 */
    private static Iterable<TransportChange> changes(Object data) {
        List<TransportChange> out = new ArrayList<>();
        if (data instanceof TransportChange c) {
            out.add(c);
        } else if (data instanceof Iterable<?> it) {
            for (Object o : it) if (o instanceof TransportChange c) out.add(c);
        }
        return out;
    }

    /** 已注册图（诊断） */
    public Map<Object, TransportGraph> graphs() {
        return graphs;
    }

    /** 累计统计（诊断） */
    public Map<Object, Tally> tallies() {
        return tallies;
    }

    /**
     * 查询当前【可交付】传输执行表（2026-08-30 用户：时间到了才发到目的地）。
     * 模拟结算后暂存；只返回【全部到期条目已就绪】的表——未到 arriveAt 的输出
     * 仍由核心持有（内部 pending），到帧(executeTick/查询时步进)后自动可交付。
     * 主线程据此对容器直 IO（无表返回 null）；已交付的表不再重复返回（单向拉取）。
     */
    public TransferExecutionTable executionTable(Object key) {
        TransferExecutionTable t = exec.get(key);
        if (t == null || t.isEmpty()) return null;
        TransportGraph g = graph(key);
        long step = g == null ? t.step : g.step();
        // 未到期 → 返回 null(还在路上)；到期 → 返回并移除(主线程消费一次)
        if (t.dueFrame > 0 && step < t.dueFrame) return null;
        exec.remove(key);
        return t;
    }

    /** 跨步累计统计（核心线程写、任意线程读原子量） */
    public static final class Tally {
        final java.util.concurrent.atomic.AtomicLong delivered =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong dropped =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong movedBits =
                new java.util.concurrent.atomic.AtomicLong();

        void add(TransportResult r) {
            delivered.addAndGet(r.delivered());
            dropped.addAndGet(r.dropped());
            movedBits.accumulateAndGet(Double.doubleToLongBits(r.movedUnits),
                    (a, b) -> Double.doubleToLongBits(
                            Double.longBitsToDouble(a) + Double.longBitsToDouble(b)));
        }

        double moved() {
            return Double.longBitsToDouble(movedBits.get());
        }

        @Override
        public String toString() {
            return "Tally{delivered=" + delivered.get() + ", dropped=" + dropped.get()
                    + ", moved=" + moved() + "}";
        }
    }

    @Override
    public String toString() {
        return "CoreTransportExecutor{graphs=" + graphs.size() + ", results=" + results.size() + "}";
    }
}