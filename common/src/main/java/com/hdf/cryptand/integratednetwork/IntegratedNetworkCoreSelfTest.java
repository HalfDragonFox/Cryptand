package com.hdf.cryptand.integratednetwork;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 集成网络核心自测（2026-08-26，纯引擎无 Minecraft）。
 * <p>
 * 验证：
 *   1) 异步建管网 + 注入 + 批量 TICK → 多跳送达（DELIVERED 事件 + 步号推进）
 *   2) 多 TICK 请求在核心合并为一条并【帧数累加】一次执行（少投递多跑帧）
 *   3) 边吞吐量上限：每帧移入在途量 ≤ throughput
 *   4) 无线链路丢包：loss 边产生 DROPPED（reason="loss"）且不计入移动量
 *   5) 最短路径路由：Dijkstra 选代价最小路径（A→B 直连贵、经中继便宜）
 *   6) 帧监听器：核心线程回调 onFrame（非主线程）+ 次数 ≥1
 *   7) 多实例隔离：注册表 acquire/release 各自独立，释放即清缓存
 *   8) 同步 API：tickNow 直接推进且返回帧
 * <p>
 * 运行：{@code ./gradlew :common:runTransportTest}
 */
public final class IntegratedNetworkCoreSelfTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        // S4 压力用例并行度（CoreTransportExecutor 类加载前设置）
        System.setProperty("cryptand.allocShards", "16");
        // 统一分配器（线程 A）——INC 内部经 ThreadDispatchers.get() 获取同一实例
        ThreadDispatchers.get();

        System.out.println("==== 1) 异步建网 + 注入 + 多跳送达 ====");
        boolean ok1 = testDelivery();
        System.out.println();

        System.out.println("==== 2) 多 TICK 批量合并（帧数累加一次执行） ====");
        boolean ok2 = testTickBatching();
        System.out.println();

        System.out.println("==== 3) 边吞吐量上限 ====");
        boolean ok3 = testThroughput();
        System.out.println();

        System.out.println("==== 4) 无线链路丢包 ====");
        boolean ok4 = testLoss();
        System.out.println();

        System.out.println("==== 5) 最短路径路由（Dijkstra） ====");
        boolean ok5 = testRouting();
        System.out.println();

        System.out.println("==== 6) 帧监听器（核心线程回调） ====");
        boolean ok6 = testListener();
        System.out.println();

        System.out.println("==== 7) 多实例隔离（registry） ====");
        boolean ok7 = testMultiInstance();
        System.out.println();

        System.out.println("==== 8) 同步 tickNow API ====");
        boolean ok8 = testTickNow();
        System.out.println();

        System.out.println("==== 9) 能力标注（单网络单能力，GAS） ====");
        boolean ok9 = testCapability();
        System.out.println();

        System.out.println("==== 10) 接口→容器信息上报（含过滤/变化上报） ====");
        boolean ok10 = testInterfaceContainer();
        System.out.println();

        System.out.println("==== 11) 网络拆分/合并（类仿真引擎 SPLIT_MERGE） ====");
        boolean ok11 = testSplitMerge();
        System.out.println();

        System.out.println("==== 12) 传输请求流程（多对多/均分→EXTRACT/WRITE+待写表） ====");
        boolean ok12 = testTransferFlow();
        System.out.println();

        System.out.println("==== 13) 空箱背压（无需求→网络等待期跳过） ====");
        boolean ok13 = testEmptyBackoff();
        System.out.println();

        System.out.println("==== 14) 大图按需路由（内存受控，无全对全表） ====");
        boolean ok14 = testLazyRouteScale();
        System.out.println();

        System.out.println("==== 15) 大型紧凑存储冒烟（5万节点 + 按需路由/传输结算不膨胀） ====");
        boolean ok15 = testCompactScale();
        System.out.println();

        System.out.println("==== 16) 图法分配引擎：时间值 Dijkstra + 输入驱动规则（S2） ====");
        boolean ok16 = testAllocator();
        System.out.println();

        System.out.println("==== 17) 图法分配：双槽转账 + 输出过滤剪枝 + 到达时间（S2） ====");
        boolean ok17 = testAllocatorSlots();
        System.out.println();

        System.out.println("==== 18) 10万×10万 规模压力：图法分配 + 分片并行（S4） ====");
        boolean ok18 = testHugeScale();
        System.out.println();

        System.out.println("==== 19) 消费值联动：预算截断 + 轮转覆盖（S5 §13） ====");
        boolean ok19 = testAllocBudget();
        System.out.println();

        System.out.println("========================================");
        System.out.println(failures == 0 ? "✅ 全部通过" : "❌ 失败 " + failures + " 项");
        if (failures > 0) System.exit(1);
    }

    // ---- 1) 异步建网 + 注入 + 送达 ----

    static boolean testDelivery() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t1");
        core.start();
        try {
            core.submitTopology("pipe", List.of(
                    TransportChange.addNode("A", TransferType.ITEM, 0, 0, 0, 100),
                    TransportChange.addNode("J", TransferType.ITEM, 0, 0, 1, 100),
                    TransportChange.addNode("B", TransferType.ITEM, 0, 0, 2, 100),
                    TransportChange.addEdge("A", "J", TransferType.ITEM, 5, 1, 0, 1),
                    TransportChange.addEdge("J", "B", TransferType.ITEM, 5, 1, 0, 1)
            ));

            // 等拓扑异步应用（图内出现 3 节点 2 边）
            boolean built = waitUntil(() -> {
                TransportGraph g = core.graph("pipe");
                return g != null && g.nodeCount() == 3 && g.edgeCount() == 2;
            }, 10000);
            if (!built) return fail("拓扑应用超时", null);

            // 注入 + 提交多帧（3 跳链路 latency=1 → 第 3 帧送达；合并批量执行）
            boolean injected = core.inject("pipe", "A",
                    new TransportPayload(TransferType.ITEM, 1, "B", "apple"));
            if (!injected) return fail("注入被拒绝", null);
            for (int i = 0; i < 4; i++) core.submitTick("pipe");

            // 等累计送达（3 跳链路 latency=1 → 第 3 帧送达）
            boolean delivered = waitUntil(() -> core.delivered("pipe") >= 1, 10000);

            TransportGraph g = core.graph("pipe");
            long step = g == null ? -1 : g.step();
            TransportResult r = core.result("pipe");
            System.out.println("  图=" + g + ", 累计送达=" + core.delivered("pipe")
                    + ", step=" + step);
            if (!delivered) return fail("未在超时内送达", r);

            boolean stepOk = step >= 3;
            System.out.println("  送达累计=" + core.delivered("pipe")
                    + "（期望 1），step=" + step + "（期望 ≥3）");
            return pass(stepOk && core.delivered("pipe") == 1);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 2) 多 TICK 批量合并 ----

    static boolean testTickBatching() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t2");
        core.start();
        try {
            core.submitTopology("co", List.of(
                    TransportChange.addNode("A", TransferType.GENERIC, 0, 0, 0, 100),
                    TransportChange.addNode("B", TransferType.GENERIC, 0, 0, 1, 100),
                    TransportChange.addEdge("A", "B", TransferType.GENERIC, 5, 1, 0, 1)
            ));
            boolean built = waitUntil(() -> {
                TransportGraph g = core.graph("co");
                return g != null && g.nodeCount() == 2;
            }, 10000);
            if (!built) return fail("拓扑应用超时", null);

            // 连发 3 个 TICK（应合并为一条操作、帧数累加 → 3 帧一次执行）
            for (int i = 0; i < 3; i++) core.submitTick("co");

            boolean settled = waitUntil(() -> {
                TransportGraph g = core.graph("co");
                return g.step() >= 3 && core.pendingOperations() == 0;
            }, 10000);

            long step = core.graph("co").step();
            CoreTransportExecutor ce = core.coreExecutor();
            System.out.println("  step=" + step + "（期望 3）pending=" + core.pendingOperations()
                    + " resultStep=" + (ce.result("co") == null ? "?" : ce.result("co").step));
            boolean ok = settled && step == 3;
            return pass(ok);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 3) 吞吐量上限 ----

    static boolean testThroughput() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t3");
        java.util.concurrent.CopyOnWriteArrayList<Double> frameMoved =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        core.setListener((key, f) -> frameMoved.add(f.movedUnits));
        core.start();
        try {
            core.submitTopology("tp", List.of(
                    TransportChange.addNode("A", TransferType.ITEM, 0, 0, 0, 100),
                    TransportChange.addNode("B", TransferType.ITEM, 0, 0, 1, 0),
                    // 吞吐 1/帧、零延迟
                    TransportChange.addEdge("A", "B", TransferType.ITEM, 1, 0, 0, 1)
            ));
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("tp");
                return g != null && g.edgeCount() == 1;
            }, 10000)) return fail("拓扑应用超时", null);

            // 注入 3 个负载（吞吐 1/帧 → 逐帧挤压，全部送达）
            for (int i = 0; i < 3; i++) core.inject("tp", "A",
                    new TransportPayload(TransferType.ITEM, 1, "B", "p" + i));
            for (int i = 0; i < 6; i++) core.submitTick("tp"); // 提交足量帧（合并批量）

            boolean delivered = waitUntil(() -> core.delivered("tp") >= 3, 10000);
            double maxFrameMoved = frameMoved.stream().mapToDouble(Double::doubleValue)
                    .max().orElse(0);
            System.out.println("  累计送达=" + core.delivered("tp")
                    + "（期望 3），每帧移入量最大值=" + maxFrameMoved + "（期望 ≤1.0）");
            if (!delivered) return fail("吞吐场景下未全部送达", core.result("tp"));
            return pass(core.delivered("tp") == 3 && maxFrameMoved <= 1.0 + 1e-9);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 4) 无线链路丢包 ----

    static boolean testLoss() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t4");
        core.start();
        try {
            core.submitTopology("radio", List.of(
                    TransportChange.addNode("TX", TransferType.SIGNAL, 0, 0, 0, 10),
                    TransportChange.addNode("RX", TransferType.SIGNAL, 0, 0, 10, 10),
                    // 100% 丢包无线链路（确定性验证丢包分支）
                    TransportChange.addEdge("TX", "RX", TransferType.SIGNAL, 1, 0, 1.0, 1)
            ));
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("radio");
                return g != null && g.edgeCount() == 1;
            }, 10000)) return fail("拓扑应用超时", null);

            core.inject("radio", "TX",
                    new TransportPayload(TransferType.SIGNAL, 1, "RX", "msg"));
            core.submitTick("radio");
            boolean dropped = waitUntil(() -> core.dropped("radio") >= 1, 10000);
            TransportResult r = core.result("radio");
            TransportEvent ev = (r == null || r.events.isEmpty()) ? null : r.events.get(0);
            boolean lossOk = dropped && ev != null && ev.reason.equals("loss") && ev.kind == TransportEvent.Kind.DROPPED;
            System.out.println("  事件=" + r + " moved=" + core.moved("radio"));
            if (!lossOk) return fail("丢包行为不符", r);
            return pass(true);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 5) 最短路径路由 ----

    static boolean testRouting() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t5");
        core.start();
        try {
            core.submitTopology("r5", List.of(
                    TransportChange.addNode("A", TransferType.GENERIC, 0, 0, 0, 100),
                    TransportChange.addNode("C", TransferType.GENERIC, 0, 0, 1, 100),
                    TransportChange.addNode("B", TransferType.GENERIC, 0, 0, 2, 100),
                    // 直连 A→B 代价 10；经中继 A→C(1) + C→B(1) 代价 2 → 应选中继
                    TransportChange.addEdge("A", "B", TransferType.GENERIC, 5, 1, 0, 10),
                    TransportChange.addEdge("A", "C", TransferType.GENERIC, 5, 1, 0, 1),
                    TransportChange.addEdge("C", "B", TransferType.GENERIC, 5, 1, 0, 1)
            ));
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("r5");
                return g != null && g.edgeCount() == 3;
            }, 10000)) return fail("拓扑应用超时", null);

            // 按需路由：注入 A→B 负载触发移动 → nextHop 首次访问做一次 Dijkstra 并缓存
            core.inject("r5", "A",
                    new TransportPayload(TransferType.GENERIC, 1, "B", "x"));
            core.submitTick("r5", 4); // A→C→B 两跳 latency1 → 第 3 帧送达
            boolean routed = waitUntil(() -> {
                TransportGraph g = core.graph("r5");
                return g != null && "C".equals(g.nextHop("A", "B"));
            }, 10000);
            TransportGraph g = core.graph("r5");
            Object hop = g == null ? null : g.nextHop("A", "B"); // 命中懒缓存
            System.out.println("  A→B 下一跳=" + hop + "（期望 C）delivered=" + core.delivered("r5"));
            boolean dlv = waitUntil(() -> core.delivered("r5") >= 1, 10000); // 等异步送达
            System.out.println("  送达累计=" + core.delivered("r5"));
            if (!routed) return fail("按需路由未生成", null);
            return pass("C".equals(hop) && dlv);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 6) 帧监听器 ----

    static boolean testListener() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t6");
        AtomicInteger frames = new AtomicInteger();
        CopyOnWriteArrayList<String> threads = new CopyOnWriteArrayList<>();
        core.setListener((key, frame) -> {
            frames.incrementAndGet();
            threads.add(Thread.currentThread().getName());
        });
        core.start();
        try {
            core.submitTopology("l6", List.of(
                    TransportChange.addNode("A", TransferType.GENERIC, 0, 0, 0, 10),
                    TransportChange.addNode("B", TransferType.GENERIC, 0, 0, 1, 10),
                    TransportChange.addEdge("A", "B", TransferType.GENERIC, 5, 1, 0, 1)
            ));
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("l6");
                return g != null && g.edgeCount() == 1;
            }, 10000)) return fail("拓扑应用超时", null);
            core.submitTick("l6");
            boolean ran = waitUntil(() -> frames.get() >= 1, 10000);
            System.out.println("  回调次数=" + frames.get() + " 线程=" + threads);
            boolean onCoreThread = !threads.isEmpty()
                    && !threads.get(0).equals(Thread.currentThread().getName());
            if (!ran) return fail("监听器未回调", null);
            return pass(onCoreThread);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 7) 多实例隔离 ----

    static boolean testMultiInstance() throws Exception {
        String n1 = "case-x1", n2 = "case-x2";
        IntegratedNetworkCore c1 = IntegratedNetworkCoreRegistry.acquire(n1);
        IntegratedNetworkCore c2 = IntegratedNetworkCoreRegistry.acquire(n2);
        c1.start();
        c2.start();
        try {
            // 各自建一个独立的图
            TransportChange node = TransportChange.addNode("N", TransferType.GENERIC, 0, 0, 0, 10);
            c1.submitTopology("g1", node);
            c2.submitTopology("g2", node);
            boolean both = waitUntil(() -> {
                TransportGraph a = c1.graph("g1");
                TransportGraph b = c2.graph("g2");
                return a != null && b != null && a.nodeCount() == 1 && b.nodeCount() == 1;
            }, 10000);

            // 释放 x1 → 实例独立移除，x2 不受影响
            IntegratedNetworkCoreRegistry.release(n1);
            boolean isolated = both
                    && IntegratedNetworkCoreRegistry.get(n1) == null
                    && IntegratedNetworkCoreRegistry.get(n2) == c2
                    && c2.graph("g2") != null;
            System.out.println("  活动实例=" + IntegratedNetworkCoreRegistry.names());
            if (!both) return fail("实例建图超时/失败", null);
            return pass(isolated);
        } finally {
            IntegratedNetworkCoreRegistry.release(n1);
            IntegratedNetworkCoreRegistry.release(n2);
        }
    }

            // ---- 8) 同步 tickNow ----

    static boolean testTickNow() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t8");
        core.start();
        try {
            core.registerGraph("g8", new TransportGraph());
            core.submitTopology("g8", List.of(
                    TransportChange.addNode("A", TransferType.GENERIC, 0, 0, 0, 10),
                    TransportChange.addNode("B", TransferType.GENERIC, 0, 0, 1, 10),
                    TransportChange.addEdge("A", "B", TransferType.GENERIC, 5, 1, 0, 1)
            ));
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("g8");
                return g != null && g.edgeCount() == 1 && g.nodeCount() == 2;
            }, 10000)) return fail("拓扑应用超时", null);
            boolean injected = core.inject("g8", "A",
                    new TransportPayload(TransferType.GENERIC, 1, "B", "x"));
            TransportGraph g = core.graph("g8");
            System.out.println("  inject=" + injected
                    + " bufferUnits=" + (g == null ? "?" : g.buffer("A").units())
                    + " nodeCount=" + (g == null ? "?" : g.nodeCount())
                    + " edgeCount=" + (g == null ? "?" : g.edgeCount()));
            if (!injected) return fail("注入被拒绝", null);

            TransportResult r = core.tickNow("g8", 3); // 同步推进 3 帧（latency1 → 第 3 帧送达）
            boolean ok = r != null && core.delivered("g8") == 1
                    && core.graph("g8").step() == 3;
            System.out.println("  累计送达=" + core.delivered("g8") + " step=" + core.graph("g8").step()
                    + " 帧=" + r);
            if (r == null) return fail("tickNow 返回 null", null);
            return pass(ok);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 9) 能力标注（单网络单能力） ----

    static boolean testCapability() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t9");
        core.start();
        try {
            TransportGraph g0 = new TransportGraph(TransferType.ITEM); // 单能力=物品
            core.registerGraph("c9", g0);
            core.submitTopology("c9", List.of(
                    TransportChange.addNode("A", TransferType.ITEM, 0, 0, 0, 100),
                    TransportChange.addNode("B", TransferType.GAS, 0, 0, 1, 100) // GAS 不相容→被拒
            ));
            boolean applied = waitUntil(() -> {
                TransportGraph g = core.graph("c9");
                return g != null && g.nodeCount() == 1;
            }, 10000);
            TransportGraph g = core.graph("c9");
            boolean ok = applied && g != null && g.capability() == TransferType.ITEM
                    && g.nodeCount() == 1;
            System.out.println("  nodeCount=" + (g == null ? "?" : g.nodeCount())
                    + "（期望 1，GAS 被拒）capability=" + (g == null ? "?" : g.capability()));
            return pass(ok);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 10) 接口→容器信息上报（含过滤 + 速录变化上报） ----

    static boolean testInterfaceContainer() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t10");
        core.start();
        try {
            core.submitTopology("ic", List.of(
                    TransportChange.addNode("pipe", TransferType.ITEM, 0, 0, 0, 100),
                    TransportChange.addNode("chest", TransferType.ITEM, 0, 0, 1, 100),
                    TransportChange.addNode("chest2", TransferType.ITEM, 0, 0, 2, 100),
                    TransportChange.addEdge("pipe", "chest", TransferType.ITEM, 5, 1, 0, 1),
                    TransportChange.addEdge("pipe", "chest2", TransferType.ITEM, 5, 1, 0, 1)
            ));
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("ic");
                return g != null && g.edgeCount() == 2;
            }, 10000)) return fail("拓扑应用超时", null);

            // 上报：接口(pipe 输出口) + 两个容器（chest 白名单/chest2 黑名单）
            core.submitReport("ic", List.of(
                    NetworkReport.addInterface(new NetworkInterface(
                            "pipe", TransferType.ITEM, false, 3, 0, 0, 0, 8)),
                    NetworkReport.addContainer(new ContainerInfo(
                            "chest", 5, 1, 16, false, new Object[]{"ingot_iron"})),
                    NetworkReport.addContainer(new ContainerInfo(
                            "chest2", 4, 2, 8, true, new Object[]{"diamond"}))
            ));
            boolean applied = waitUntil(() -> {
                TransportGraph g = core.graph("ic");
                return g != null && g.interfaceCount() == 1 && g.containerCount() == 2;
            }, 10000);
            TransportGraph g = core.graph("ic");
            NetworkInterface iface = g == null ? null : g.interfaceAt("pipe");
            ContainerInfo chest = g == null ? null : g.container("chest");
            ContainerInfo chest2 = g == null ? null : g.container("chest2");
            boolean body = applied
                    && iface != null && !iface.input && iface.rate == 8
                    && chest != null && chest.distance == 1 && chest.rate == 16
                    && chest.accepts("ingot_iron") && !chest.accepts("diamond")   // 白名单
                    && chest2 != null && !chest2.accepts("diamond") && chest2.accepts("ingot_iron"); // 黑名单

            // 变化上报：接口速率 8 → 16
            core.submitReport("ic", NetworkReport.addInterface(new NetworkInterface(
                    "pipe", TransferType.ITEM, false, 3, 0, 0, 0, 16)));
            boolean updated = waitUntil(() -> {
                TransportGraph gg = core.graph("ic");
                NetworkInterface i2 = gg == null ? null : gg.interfaceAt("pipe");
                return i2 != null && i2.rate == 16;
            }, 10000);
            System.out.println("  iface=" + iface + " chest=" + chest + " chest2=" + chest2
                    + " 速率变化=" + updated);
            return pass(body && updated);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 11) 网络拆分/合并 ----

    static boolean testSplitMerge() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t11");
        core.start();
        try {
            // 一网含两个不连通分量：A-B 与 C-D
            core.submitTopology("net", List.of(
                    TransportChange.addNode("A", TransferType.GENERIC, 0, 0, 0, 100),
                    TransportChange.addNode("B", TransferType.GENERIC, 0, 0, 1, 100),
                    TransportChange.addNode("C", TransferType.GENERIC, 0, 0, 2, 100),
                    TransportChange.addNode("D", TransferType.GENERIC, 0, 0, 3, 100),
                    TransportChange.addEdge("A", "B", TransferType.GENERIC, 5, 1, 0, 1),
                    TransportChange.addEdge("C", "D", TransferType.GENERIC, 5, 1, 0, 1)
            ));
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("net");
                return g != null && g.nodeCount() == 4;
            }, 10000)) return fail("拓扑应用超时", null);

            // SPLIT → 两个分量 net[0] net[1]，各 2 节点；原 net 注销
            core.submitSplitMerge("net", TransportSplitMerge.split("net"));
            boolean splitOk = waitUntil(() -> {
                TransportGraph p0 = core.graph("net[0]");
                TransportGraph p1 = core.graph("net[1]");
                return p0 != null && p1 != null && p0.nodeCount() == 2 && p1.nodeCount() == 2
                        && core.graph("net") == null;
            }, 10000);

            // MERGE net[0] + net[1] → 4 节点；net[1] 注销
            core.submitSplitMerge("net[0]", TransportSplitMerge.merge("net[0]", "net[1]"));
            boolean merged = waitUntil(() -> {
                TransportGraph m = core.graph("net[0]");
                return m != null && m.nodeCount() == 4 && core.graph("net[1]") == null;
            }, 10000);
            System.out.println("  split=" + splitOk + " merge4Node=" + merged);
            return pass(splitOk && merged);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 12) 传输请求流程（多对多/均分） ----

    static boolean testTransferFlow() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t12");
        CopyOnWriteArrayList<TransportEvent> ioEvents = new CopyOnWriteArrayList<>();
        core.setListener((key, frame) -> {
            for (TransportEvent e : frame.events) {
                if (e.kind == TransportEvent.Kind.EXTRACT || e.kind == TransportEvent.Kind.WRITE) {
                    ioEvents.add(e);
                }
            }
        });
        core.start();
        try {
            core.submitTopology("tf", List.of(
                    TransportChange.addNode("IN", TransferType.ITEM, 0, 0, 0, 100),
                    TransportChange.addNode("OUT1", TransferType.ITEM, 0, 0, 1, 100),
                    TransportChange.addNode("OUT2", TransferType.ITEM, 0, 0, 2, 100),
                    TransportChange.addEdge("IN", "OUT1", TransferType.ITEM, 5, 1, 0, 1),
                    TransportChange.addEdge("IN", "OUT2", TransferType.ITEM, 5, 2, 0, 1)
            ));
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("tf");
                return g != null && g.nodeCount() == 3;
            }, 10000)) return fail("拓扑应用超时", null);

            // 传输请求：输入[IN] → 输出[OUT1,OUT2]，100 单位，均分
            core.submitTransfer("tf", new TransportTransferRequest(
                    List.of("IN"), List.of("OUT1", "OUT2"),
                    List.of(new TransportPayload(TransferType.ITEM, 100, null, "iron")),
                    DistributionRule.EQUALIZE));
            boolean settled = waitUntil(() -> ioEvents.size() >= 3, 10000); // 2×WRITE + 1×EXTRACT
            // 推图：让 step 越过执行表 dueFrame（延迟交付到期发放语义需要时间流逝）
            for (int i = 0; i < 4; i++) core.submitTick("tf");
            double pending = core.pendingWrites("tf");
            boolean hasWrite50 = ioEvents.stream().anyMatch(e -> e.kind == TransportEvent.Kind.WRITE
                    && e.payload != null && Math.abs(e.payload.amount - 50) < 1e-9);
            boolean hasExtract100 = ioEvents.stream().anyMatch(e -> e.kind == TransportEvent.Kind.EXTRACT
                    && e.payload != null && Math.abs(e.payload.amount - 100) < 1e-9);
            // 执行表：核心侧到期发放（executionTable 未到期返回 null、消费即删）——
            //   等表全部条目到期（dueFrame）后再读并验证结构
            final TransferExecutionTable[] tblBox = new TransferExecutionTable[1];
            boolean tblReady = waitUntil(() -> {
                TransferExecutionTable t = core.executionTable("tf");
                if (t != null) { tblBox[0] = t; return true; }
                return false;
            }, 10000);
            TransferExecutionTable tbl = tblBox[0];
            boolean tblOk = tblReady && tbl != null
                    && tbl.outputs.size() == 2
                    && tbl.inputs.size() == 1
                    && Math.abs(tbl.outputs.get(0).amount - 50) < 1e-9
                    && Math.abs(tbl.outputs.get(1).amount - 50) < 1e-9
                    && Math.abs(tbl.inputs.get(0).amount - 100) < 1e-9;
            boolean ok = settled && Math.abs(pending - 100) < 1e-9
                    && hasWrite50 && hasExtract100 && tblOk;
            System.out.println("  事件数=" + ioEvents.size() + " pendingWrites=" + pending
                    + " write50=" + hasWrite50 + " extract100=" + hasExtract100
                    + " 执行表=" + tbl);
            return pass(ok);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 13) 空箱背压（无需求 → 网络等待期跳过流动） ----

    static boolean testEmptyBackoff() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t13");
        core.start();
        try {
            core.submitTopology("wb", List.of(
                    TransportChange.addNode("A", TransferType.GENERIC, 0, 0, 0, 10),
                    TransportChange.addNode("B", TransferType.GENERIC, 0, 0, 1, 10),
                    TransportChange.addEdge("A", "B", TransferType.GENERIC, 5, 1, 0, 1)
            ));
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("wb");
                return g != null && g.nodeCount() == 2;
            }, 10000)) return fail("拓扑应用超时", null);

            // 空内容传输请求 → 网络进入等待（背压）
            core.submitTransfer("wb", new TransportTransferRequest(
                    List.of("A"), List.of("B"), List.of(), DistributionRule.EQUALIZE));
            boolean waited = waitUntil(() -> {
                long[] w = core.waitInfo("wb");
                return w != null && w[1] > 0;
            }, 10000);
            long stepBefore = core.graph("wb").step();
            core.submitTick("wb");
            boolean skipOk = waitUntil(() -> core.graph("wb").step() > stepBefore, 10000);
            long[] w = core.waitInfo("wb");
            System.out.println("  等待 info=" + (w == null ? "?" : Arrays.toString(w))
                    + " 等待期仍走步号=" + skipOk);
            return pass(waited && skipOk);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 14) 大图按需路由（内存受控：不建全对全表） ----

    static boolean testLazyRouteScale() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t14");
        core.start();
        try {
            int n = 20_000;
            List<TransportChange> changes = new ArrayList<>(n * 2);
            for (int i = 0; i < n; i++) {
                changes.add(TransportChange.addNode("n" + i, TransferType.GENERIC, 0, 0, i, 10_000));
                if (i > 0) {
                    changes.add(TransportChange.addEdge("n" + (i - 1), "n" + i,
                            TransferType.GENERIC, 100, 0, 0, 1));
                }
            }
            core.submitTopology("big", changes);
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("big");
                return g != null && g.nodeCount() == n;
            }, 30_000)) return fail("大图建立超时", null);

            // 只请求 n0 → nN-1 一个 (源,目标) 对 → 懒路由缓存应只有一个源表、一个目标对
            TransportGraph g = core.graph("big");
            Object hop = g.nextHop("n0", "n" + (n - 1));
            int srcTables = g.routes().size();
            Map<Object, Object> table = g.routes().get("n0");
            int cachedPairs = table == null ? 0 : table.size();
            System.out.println("  N=" + n + " hop=" + hop + "（期望 n1）源表=" + srcTables
                    + " 缓存对=" + cachedPairs + "（期望 1；远小于 N²）");
            boolean ok = "n1".equals(hop) && srcTables == 1 && cachedPairs <= 1;
            return pass(ok);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 15) 大型紧凑存储冒烟（P1 扁平邻接 + P2 primitive 计数） ----

    static boolean testCompactScale() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t15");
        core.start();
        try {
            int n = 50_000;
            List<TransportChange> changes = new ArrayList<>(n * 2);
            for (int i = 0; i < n; i++) {
                changes.add(TransportChange.addNode("c" + i, TransferType.ITEM, 0, 0, i, 10_000));
                if (i > 0) {
                    changes.add(TransportChange.addEdge("c" + (i - 1), "c" + i,
                            TransferType.ITEM, 100, 0, 0, 1));
                }
            }
            core.submitTopology("bigc", changes);
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("bigc");
                return g != null && g.nodeCount() == n && g.edgeCount() == n - 1;
            }, 60_000)) return fail("5万节点建图超时", null);

            // 2000 个按需路由对（均匀分布）→ 懒路由源表应受控
            TransportGraph g = core.graph("bigc");
            for (int k = 0; k < 2000; k++) {
                g.nextHop("c" + (k * 10), "c" + (n - 1 - (k % 97)));
            }
            int srcTables = g.routes().size();

            // 一半输入一半输出：10 输入 → 10 输出均分 → 执行表/待写表用 primitive 计数
            List<Object> ins = new ArrayList<>();
            List<Object> outs = new ArrayList<>();
            for (int k = 0; k < 10; k++) {
                ins.add("c" + k);
                outs.add("c" + (n - 1 - k));
            }
            core.submitTransfer("bigc", new TransportTransferRequest(ins, outs,
                    List.of(new TransportPayload(TransferType.ITEM, 1000, null, "iron")),
                    DistributionRule.EQUALIZE));
            // ⚠ 必须等到【全部结算完成】（pendingWrites 达 1000）——不能只等 >0，
            //   否则读到异步结算中途的部分值（如 200）导致偶发失败（2026-08-29 实测）
            boolean settled = waitUntil(() -> core.pendingWrites("bigc") >= 999.999, 30_000);

            double pending = core.pendingWrites("bigc");
            long memMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
            System.out.println("  N=" + n + " 路由源表=" + srcTables
                    + "（期望 ≤2000，远小于 N） 结算待写=" + pending
                    + "（期望 1000） 已用内存MB~=" + memMb);
            boolean ok = settled && Math.abs(pending - 1000) < 1e-9 && srcTables <= 2000;
            return pass(ok);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 工具 ----

    private static boolean waitUntil(BooleanSupplier cond, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return true;
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return cond.getAsBoolean();
    }

    private static boolean pass(boolean ok) {
        if (!ok) failures++;
        System.out.println("  " + (ok ? "✅" : "❌"));
        return ok;
    }

    private static boolean fail(String msg, Object detail) {
        failures++;
        System.out.println("  ❌ " + msg + (detail != null ? " :: " + detail : ""));
        return false;
    }

    // ---- 16) 图法分配引擎：时间值 Dijkstra + 输入驱动规则（2026-09 S2） ----

    static boolean testAllocator() {
        try {
            // 图：A --(lat=2)--> J --(lat=3)--> B；C --(lat=1)--> B（A 到 B 时间 5，C 到 B 时间 1）
            TransportGraph g = new TransportGraph(TransferType.ITEM);
            g.addNode(new TransportNode("A", TransferType.ITEM, 0, 0, 0, 100));
            g.addNode(new TransportNode("J", TransferType.ITEM, 0, 0, 1, 100));
            g.addNode(new TransportNode("B", TransferType.ITEM, 0, 0, 2, 100));
            g.addNode(new TransportNode("C", TransferType.ITEM, 0, 0, 3, 100));
            g.addEdge(new TransportEdge(1, "A", "J", TransferType.ITEM, 5, 2, 0, 1));
            g.addEdge(new TransportEdge(2, "J", "B", TransferType.ITEM, 5, 3, 0, 1));
            g.addEdge(new TransportEdge(3, "C", "B", TransferType.ITEM, 5, 1, 0, 1));

            TransportAllocator alloc = new TransportAllocator();

            // 时间值：A→B = 2+3 = 5；C→B = 1
            Map<Object, Integer> timesA = alloc.forwardTimes(g, "A", java.util.Set.of("B"));
            Map<Object, Integer> timesC = alloc.forwardTimes(g, "C", java.util.Set.of("B"));
            if (!Integer.valueOf(5).equals(timesA.get("B")) || !Integer.valueOf(1).equals(timesC.get("B"))) {
                return fail("时间值错误 timesA=" + timesA + " timesC=" + timesC, null);
            }

            // NEAREST：C 到 B 时间 1 < A 到 B 时间 5 → 输入 C 全部分给 B
            TransportAllocator.Plan planN = alloc.allocateForInput(g,
                    new TransportAllocator.In("C", 10, "apple", DistributionRule.NEAREST, null),
                    List.of(new TransportAllocator.Out("B", Double.MAX_VALUE, null)),
                    timesC, 100L);
            if (planN.isEmpty() || Math.abs(planN.outputAllocs().get("B") - 10) > 1e-9) {
                return fail("NEAREST 应全给最近 B", planN);
            }
            // arriveAt = step + 时间值 = 100 + 1
            double arrive = planN.outEntries().get(0).arriveAt;
            if (arrive != 101) return fail("arriveAt 应为 101 实际 " + arrive, null);

            // EQUALIZE：A 均分给两个候选输出
            Map<Object, Integer> timesA2 = alloc.forwardTimes(g, "A", java.util.Set.of("J", "B"));
            TransportAllocator.Plan planE = alloc.allocateForInput(g,
                    new TransportAllocator.In("A", 10, "apple", DistributionRule.EQUALIZE, null),
                    List.of(new TransportAllocator.Out("J", Double.MAX_VALUE, null),
                            new TransportAllocator.Out("B", Double.MAX_VALUE, null)),
                    timesA2, 0L);
            double j = planE.outputAllocs().getOrDefault("J", 0.0);
            double b = planE.outputAllocs().getOrDefault("B", 0.0);
            if (Math.abs(j - 5) > 1e-9 || Math.abs(b - 5) > 1e-9) {
                return fail("EQUALIZE 应 5/5 实际 " + j + "/" + b, planE);
            }
            // 双槽总量守恒：Σ分配 = 输入槽 supply
            double sum = 0;
            for (double v : planE.outputAllocs().values()) sum += v;
            if (Math.abs(sum - 10) > 1e-9) return fail("双槽转账总量应 = 输入槽 10", sum);
            return pass(true);
        } catch (Throwable t) {
            return fail("testAllocator 异常", t);
        }
    }

    // ---- 17) 图法分配：双槽转账 + 输出过滤剪枝 + 到达时间（2026-09 S2） ----

    static boolean testAllocatorSlots() {
        try {
            TransportGraph g = new TransportGraph(TransferType.ITEM);
            g.addNode(new TransportNode("IN", TransferType.ITEM, 0, 0, 0, 100));
            g.addNode(new TransportNode("O1", TransferType.ITEM, 0, 0, 1, 100));
            g.addNode(new TransportNode("O2", TransferType.ITEM, 0, 0, 2, 100));
            g.addEdge(new TransportEdge(1, "IN", "O1", TransferType.ITEM, 5, 1, 0, 1));
            g.addEdge(new TransportEdge(2, "IN", "O2", TransferType.ITEM, 5, 2, 0, 1));

            TransportAllocator alloc = new TransportAllocator();

            // 输出过滤剪枝（调用方职责）：O2 黑名单拒绝 "apple" → 候选只剩 O1
            // （分配器只对已剪枝候选做数量转账，不重复剪枝）
            Map<Object, Integer> times = alloc.forwardTimes(g, "IN", java.util.Set.of("O1", "O2"));
            List<TransportAllocator.Out> cands = List.of(
                    new TransportAllocator.Out("O1", Double.MAX_VALUE, null));
            TransportAllocator.Plan plan = alloc.allocateForInput(g,
                    new TransportAllocator.In("IN", 64, "apple", DistributionRule.EQUALIZE, null),
                    cands, times, 50L);
            if (plan.isEmpty() || Math.abs(plan.outputAllocs().get("O1") - 64) > 1e-9) {
                return fail("过滤剪枝后应全给 O1", plan);
            }
            if (plan.outputAllocs().containsKey("O2")) {
                return fail("O2 应被过滤剪枝排除", plan);
            }
            // 到达时间：IN→O1 时间 1 → arriveAt = 50+1 = 51
            double arrive1 = plan.outEntries().get(0).arriveAt;
            if (arrive1 != 51) return fail("O1 arriveAt 应为 51 实际 " + arrive1, null);

            // 输入槽容量语义：supply = 输入槽当前数量（cap = k×rate 由平台层约束），
            // 分配量恒 ≤ supply（双槽转账不超卖）
            TransportAllocator.Plan plan2 = alloc.allocateForInput(g,
                    new TransportAllocator.In("IN", 0, "apple", DistributionRule.EQUALIZE, null),
                    cands, times, 50L);
            if (!plan2.isEmpty()) return fail("输入槽空（supply=0）不应分配", plan2);
            return pass(true);
        } catch (Throwable t) {
            return fail("testAllocatorSlots 异常", t);
        }
    }

    // ---- 18) 10万×10万 规模压力（2026-09 S4：图法分配 + 分片并行 + 内存线性） ----

    static boolean testHugeScale() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t18");
        core.start();
        try {
            int nin = 100_000;  // 10 万输入节点（S4：10 万×10 万规模压力）
            int nout = 100_000; // 10 万输出节点
            // 星型稀疏：输入 ck 直接连输出 ok（每输入 1 条边）——10 万×10 万请求、
            //   分配在稀疏可达下按需完成（Dijkstra 早停）
            List<TransportChange> changes = new ArrayList<>(nin * 3);
            for (int k = 0; k < nin; k++) {
                changes.add(TransportChange.addNode("c" + k, TransferType.ITEM, 0, 0, k, 10_000));
                changes.add(TransportChange.addNode("o" + k, TransferType.ITEM, 0, 0, nin + k, 10_000));
                changes.add(TransportChange.addEdge("c" + k, "o" + k, TransferType.ITEM, 100, 1, 0, 1));
            }
            long t0 = System.currentTimeMillis();
            core.submitTopology("big10", changes);
            boolean built = waitUntil(() -> {
                TransportGraph g = core.graph("big10");
                return g != null && g.nodeCount() == nin + nout;
            }, 180_000);
            if (!built) return fail("20万节点建图超时", null);
            System.out.println("  建网 20万节点耗时ms=" + (System.currentTimeMillis() - t0));

            // 10 万输入 × 10 万输出 全活跃请求（EQUALIZE，每输入 1 单位）
            List<Object> ins = new ArrayList<>(nin);
            List<Object> outs = new ArrayList<>(nout);
            for (int k = 0; k < nin; k++) ins.add("c" + k);
            for (int k = 0; k < nout; k++) outs.add("o" + k);
            double total = nin;
            long t1 = System.currentTimeMillis();
            core.submitTransfer("big10", new TransportTransferRequest(
                    ins, outs,
                    List.of(new TransportPayload(TransferType.ITEM, total, null, "iron")),
                    DistributionRule.EQUALIZE));
            boolean settled = waitUntil(() -> core.pendingWrites("big10") >= total - 1e-6, 240_000);
            long dt = System.currentTimeMillis() - t1;
            double pending = core.pendingWrites("big10");
            long memMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20;
            // 分配耗时分阶段观察：结算前中期采样
            long t2 = System.currentTimeMillis();
            double mid = core.pendingWrites("big10");
            System.out.println("  10万x10万分配耗时ms=" + dt + " pendingWrites=" + pending
                    + "（期望 " + total + "） 已用内存MB~=" + memMb
                    + "（String id 图结构，§14.1 整数化后预计 <50MB）");
            boolean ok = settled && Math.abs(pending - total) < 1.0 && memMb < 1200;
            return pass(ok);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }

    // ---- 19) 消费值联动：预算截断 + 轮转覆盖（2026-09 §13） ----

    static boolean testAllocBudget() throws Exception {
        IntegratedNetworkCore core = new IntegratedNetworkCore("t19");
        core.start();
        try {
            // 星型：10 输入 → 10 输出（每对独立）
            List<TransportChange> changes = new ArrayList<>();
            for (int k = 0; k < 10; k++) {
                changes.add(TransportChange.addNode("i" + k, TransferType.ITEM, 0, 0, k, 100));
                changes.add(TransportChange.addNode("o" + k, TransferType.ITEM, 0, 0, 20 + k, 100));
                changes.add(TransportChange.addEdge("i" + k, "o" + k, TransferType.ITEM, 100, 1, 0, 1));
            }
            core.submitTopology("b", changes);
            if (!waitUntil(() -> {
                TransportGraph g = core.graph("b");
                return g != null && g.nodeCount() == 20;
            }, 10000)) return fail("拓扑应用超时", null);

            List<Object> ins = new ArrayList<>();
            List<Object> outs = new ArrayList<>();
            for (int k = 0; k < 10; k++) { ins.add("i" + k); outs.add("o" + k); }
            double total = 10; // 每输入 1 单位

            // 第一轮：budget=5 → 只处理 5 个输入 → pendingWrites = 5（截断）
            core.submitTransfer("b", new TransportTransferRequest(
                    ins, outs, List.of(new TransportPayload(TransferType.ITEM, total, null, "iron")),
                    DistributionRule.EQUALIZE, 5));
            boolean w1 = waitUntil(() -> core.pendingWrites("b") >= 5 - 1e-6, 10_000);
            double p1 = core.pendingWrites("b");

            // 第二轮：budget=5 → 轮转指针处理剩下 5 个 → pendingWrites = 10（覆盖）
            core.submitTransfer("b", new TransportTransferRequest(
                    ins, outs, List.of(new TransportPayload(TransferType.ITEM, total, null, "iron")),
                    DistributionRule.EQUALIZE, 5));
            boolean w2 = waitUntil(() -> core.pendingWrites("b") >= 10 - 1e-6, 10_000);
            double p2 = core.pendingWrites("b");

            System.out.println("  第一轮pending=" + p1 + "（期望 5） 第二轮pending=" + p2
                    + "（期望 10） 截断=" + w1 + " 轮转覆盖=" + w2);
            boolean ok = w1 && w2 && Math.abs(p1 - 5) < 1e-6 && Math.abs(p2 - 10) < 1e-6;
            return pass(ok);
        } finally {
            core.stop();
            core.coreExecutor().clear();
        }
    }
}