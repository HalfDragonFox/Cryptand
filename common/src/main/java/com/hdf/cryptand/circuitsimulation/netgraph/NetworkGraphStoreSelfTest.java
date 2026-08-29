package com.hdf.cryptand.circuitsimulation.netgraph;

/**
 * 网络图存储自测（2026-08-19 算法下沉：纯核心自动处理拆合/合并）。
 *
 * <p>验证（对应架构「网络拆合/导线合并由核心自动实现」）：
 *   1) 接线合并：两孤立点加边 → 同一网络；再加第三条边 → 三网络合一；
 *   2) 拆线分裂：移除桥边 → 分裂为两个网络（b 侧移入新网络）；
 *   3) 幂等：重复加同一条边（新对象 equals 相同）→ 不涨 version；
 *   4) 空网络收尾：compactNetworks 移除空网络；
 *   5) 设备放下即建网：addDevice 把同设备端子并入同一网络。
 *
 * 运行：{@code ./gradlew :common:runGraphStoreTest}
 */
public final class NetworkGraphStoreSelfTest {

    public static void main(String[] args) {
        boolean pass = true;
        System.out.println("==== 网络图存储自测（NetworkGraphStore） ====");

        // 1) 接线合并
        System.out.println("1) 接线合并（addEdge 自动合并/新建）");
        NetworkGraphStore store = new NetworkGraphStore();
        WirePoint a = new WirePoint("B1#0");
        WirePoint b = new WirePoint("B2#0");
        WirePoint c = new WirePoint("B3#0");
        boolean c1 = store.addEdge(new WireEdge(a, b, 1.0, null, 1, "r", 0, false, "w"));
        boolean c2 = store.addEdge(new WireEdge(b, c, 1.0, null, 1, "r", 0, false, "w"));
        boolean mergeOk = c1 && c2 && store.networkOf(a) == store.networkOf(b)
                && store.networkOf(b) == store.networkOf(c)
                && store.networks().size() == 1 && store.nodeCount() == 3 && store.edgeCount() == 2;
        System.out.println("   3 点 2 边 → 1 网络 / nodes=" + store.nodeCount()
                + " edges=" + store.edgeCount() + " nets=" + store.networks().size()
                + (mergeOk ? " ✅" : " ❌"));
        pass &= mergeOk;

        // 2) 拆线分裂
        System.out.println("2) 拆线分裂（removeEdge b 侧移入新网络）");
        long vBefore = store.version();
        boolean removed = store.removeEdge(new WirePoint("B1#0"), new WirePoint("B2#0"));
        boolean splitOk = removed && store.networkOf(a) != store.networkOf(b)
                && store.networks().size() == 2 && store.version() > vBefore;
        System.out.println("   拆桥边后 2 网络 / nets=" + store.networks().size()
                + " aNet≠bNet=" + (store.networkOf(a) != store.networkOf(b))
                + " ver " + vBefore + "→" + store.version() + (splitOk ? " ✅" : " ❌"));
        pass &= splitOk;

        // 3) 幂等（同网络重复导入同一边不涨 version）
        System.out.println("3) 幂等（每 tick 全量重发不涨版本）");
        NetworkGraphStore s3 = new NetworkGraphStore();
        WirePoint p1 = new WirePoint("P1#0");
        WirePoint p2 = new WirePoint("P2#0");
        s3.addEdge(new WireEdge(p1, p2, 1.0, null, 1, "r", 0, false, "w"));
        long v1 = s3.version();
        // 重复导入同一条边（新对象 equals 相同 → 同网络重复边幂等）
        s3.addEdge(new WireEdge(p1, p2, 1.0, null, 1, "r", 0, false, "w"));
        long v2 = s3.version();
        boolean idemOk = v1 == v2;
        System.out.println("   重复加边 ver " + v1 + "→" + v2 + "（不变）"
                + (idemOk ? " ✅" : " ❌"));
        pass &= idemOk;

        // 4) 空网络收尾
        System.out.println("4) 空网络收尾（compactNetworks）");
        store.removePoint(new WirePoint("B1#0")); // 移除点 → 可能残留
        store.compactNetworks();
        boolean compactOk = store.networks().stream().noneMatch(n -> n.nodeCount() == 0);
        System.out.println("   无空网络=" + compactOk + (compactOk ? " ✅" : " ❌"));
        pass &= compactOk;

        // 5) 设备放下即建网
        System.out.println("5) 设备放下即建网（addDevice 同设备端子同网络）");
        NetworkGraphStore s2 = new NetworkGraphStore();
        boolean d1 = s2.addDevice(java.util.List.of("B10#0", "B10#1", "B10#2"));
        boolean d2 = s2.addDevice(java.util.List.of("B10#0", "B10#1", "B10#2")); // 幂等
        boolean devOk = d1 && !d2 && s2.networkOf(new WirePoint("B10#0"))
                == s2.networkOf(new WirePoint("B10#2")) && s2.networks().size() == 1;
        System.out.println("   3 端子同网络 / nets=" + s2.networks().size()
                + " 幂等=" + !d2 + (devOk ? " ✅" : " ❌"));
        pass &= devOk;

        System.out.println("==== " + (pass ? "全部通过 ✅" : "存在失败 ❌") + " ====");
        System.exit(pass ? 0 : 1);
    }
}
