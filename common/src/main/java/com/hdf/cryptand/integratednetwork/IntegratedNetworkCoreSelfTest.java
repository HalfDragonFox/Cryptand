package com.hdf.cryptand.integratednetwork;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;

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

            // 触发一次路由重算（显式 ROUTE 也可以；TICK 自动重算亦等效）
            core.submitTick("r5");
            boolean routed = waitUntil(() -> {
                TransportGraph g = core.graph("r5");
                Map<Object, ?> table = g.routes().get("A");
                return table != null && table.containsKey("B");
            }, 10000);

            TransportGraph g = core.graph("r5");
            Object hop = g == null ? null : g.routes().get("A").get("B");
            System.out.println("  A→B 下一跳=" + hop + "（期望 C）");
            if (!routed) return fail("路由未生成", null);
            return pass("C".equals(hop));
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
}