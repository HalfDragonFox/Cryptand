package com.hdf.cryptand.circuitsimulation.cache;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;

/**
 * 网络世界图算法自测（2026-08-22 数据层迁移；NetworkWorld 实例）。
 *
 * <p>验证（NetworkWorld 图算法 = NetworkGraphStore 行为一致）：
 *   1) 接线合并：两孤立点加边 → 同一网络；再加边 → 多网络合一；
 *   2) 拆线分裂：移除桥边 → 分裂（b 侧移入新网络）+ 同方块端子保持；
 *   3) 设备放下即建网：addDevice 同方块端子同网络（幂等）；
 *   4) ensureComponentNetworks：重建后 byPoint 与网络一致（无丢点/幻影点）；
 *   5) removePoint 重新分组 + compactNetworks 空网络收尾；
 *   6) 双线回路剪一根 → 剩余线仍连通（设备端子不分离，剪线端点仍在图）。
 *
 * 运行：{@code ./gradlew :common:runWorldGraphTest}
 */
public final class NetworkWorldGraphSelfTest {

    private static WireEdge edge(String a, String b, double r) {
        return new WireEdge(new WirePoint(a), new WirePoint(b), r, null, 1.0, "copper", 0, true, "wire");
    }

    public static void main(String[] args) {
        boolean pass = true;
        System.out.println("==== 网络世界图算法自测（NetworkWorld） ====");

        NetworkWorld world = new NetworkWorld("test");

        // 1) 接线合并
        System.out.println("1) 接线合并（addEdge 自动合并/新建）");
        WirePoint a = new WirePoint("B1#0");
        WirePoint b = new WirePoint("B2#0");
        WirePoint c = new WirePoint("B3#0");
        boolean c1 = world.addEdge(edge("B1#0", "B2#0", 1.0))
                && world.addEdge(edge("B2#0", "B3#0", 1.0));
        boolean mergeOk = c1 && world.networkOf(a) != null
                && world.networkOf(a) == world.networkOf(b)
                && world.networkOf(b) == world.networkOf(c)
                && world.networkCount() == 1 && world.nodeCount() == 3 && world.edgeCount() == 2;
        System.out.println("   3 点 2 边 → 1 网络 / nodes=" + world.nodeCount()
                + " edges=" + world.edgeCount() + " nets=" + world.networkCount()
                + (mergeOk ? " ✅" : " ❌"));
        pass &= mergeOk;

        // 2) 拆线分裂（b 侧移入新网络 + 同方块端子保持）
        System.out.println("2) 拆线分裂（removeEdge）");
        NetworkWorld c2 = new NetworkWorld("test2");
        c2.addDevice(java.util.List.of("X#0", "X#1"));
        c2.addDevice(java.util.List.of("Y#0"));
        c2.addEdge(edge("X#0", "Y#0", 1.0));
        long vBefore = c2.version();
        boolean splitOk1 = c2.removeEdge(new WirePoint("X#0"), new WirePoint("Y#0"));
        boolean splitOk = splitOk1
                && c2.networkOf(new WirePoint("X#0")) == c2.networkOf(new WirePoint("X#1")) // 同方块不分离
                && c2.networkOf(new WirePoint("X#0")) != c2.networkOf(new WirePoint("Y#0"))
                && c2.networkCount() == 2 && c2.version() > vBefore;
        System.out.println("   拆桥边后 2 网络 / nets=" + c2.networkCount()
                + " X 端子同网络=" + (c2.networkOf(new WirePoint("X#0"))
                == c2.networkOf(new WirePoint("X#1")))
                + " ver " + vBefore + "→" + c2.version() + (splitOk ? " ✅" : " ❌"));
        pass &= splitOk;

        // 3) 设备放下即建网（addDevice 幂等）
        System.out.println("3) 设备放下即建网（addDevice）");
        NetworkWorld c3 = new NetworkWorld("test3");
        boolean d1 = c3.addDevice(java.util.List.of("D#0", "D#1", "D#2"));
        boolean d2 = c3.addDevice(java.util.List.of("D#0", "D#1", "D#2"));
        boolean devOk = d1 && !d2
                && c3.networkOf(new WirePoint("D#0")) == c3.networkOf(new WirePoint("D#2"))
                && c3.networkCount() == 1 && c3.nodeCount() == 3;
        System.out.println("   3 端子同网络 / nets=" + c3.networkCount()
                + " nodes=" + c3.nodeCount() + " 幂等=" + !d2 + (devOk ? " ✅" : " ❌"));
        pass &= devOk;

        // 4) ensureComponentNetworks 一致性
        System.out.println("4) ensureComponentNetworks 一致性（byPoint=网络）");
        NetworkWorld c4 = new NetworkWorld("test4");
        c4.addDevice(java.util.List.of("E#0", "E#1"));
        c4.addDevice(java.util.List.of("F#0"));
        c4.addEdge(edge("E#0", "F#0", 1.0));
        c4.ensureComponentNetworks();
        boolean consistOk = c4.networkCount() == 1
                && c4.networkOf(new WirePoint("E#0")) == c4.networkOf(new WirePoint("E#1"))
                && c4.networkOf(new WirePoint("E#1")) == c4.networkOf(new WirePoint("F#0"))
                && c4.nodeCount() == 3 && c4.edgeCount() == 1
                && c4.pointList().size() == 3
                && c4.contains(new WirePoint("E#1")) && c4.contains(new WirePoint("F#0"));
        System.out.println("   1 网络 3 点 1 边 / nets=" + c4.networkCount()
                + " nodes=" + c4.nodeCount() + " points=" + c4.pointList().size()
                + (consistOk ? " ✅" : " ❌"));
        pass &= consistOk;

        // 4b) 二次 ensure（幂等：不丢点不涨点）
        c4.ensureComponentNetworks();
        boolean idemOk = c4.nodeCount() == 3 && c4.pointList().size() == 3 && c4.edgeCount() == 1;
        System.out.println("   二次重建：nodes=" + c4.nodeCount() + " points="
                + c4.pointList().size() + " edges=" + c4.edgeCount() + (idemOk ? " ✅" : " ❌"));
        pass &= idemOk;

        // 5) removePoint 重新分组 + compactNetworks
        System.out.println("5) removePoint + compactNetworks（空网络收尾）");
        NetworkWorld c5 = new NetworkWorld("test5");
        c5.addEdge(edge("P#0", "Q#0", 1.0));
        c5.addEdge(edge("Q#0", "R#0", 1.0));
        c5.removePoint(new WirePoint("P#0"));
        c5.compactNetworks();
        boolean rmOk = c5.contains(new WirePoint("Q#0"))
                && c5.networkOf(new WirePoint("Q#0")) == c5.networkOf(new WirePoint("R#0"))
                && c5.nodeCount() == 2 && c5.edgeCount() == 1;
        System.out.println("   删 P 后：nodes=" + c5.nodeCount() + " edges=" + c5.edgeCount()
                + " Q-R 同网络=" + (c5.networkOf(new WirePoint("Q#0"))
                == c5.networkOf(new WirePoint("R#0"))) + (rmOk ? " ✅" : " ❌"));
        pass &= rmOk;

        // 6) 双线回路剪一根 → 剩余线仍连通
        System.out.println("6) 双线回路剪一根（设备端子不分离）");
        NetworkWorld c6 = new NetworkWorld("test6");
        c6.addDevice(java.util.List.of("A#0", "A#1"));
        c6.addDevice(java.util.List.of("B#0", "B#1"));
        c6.addEdge(edge("A#0", "B#0", 1.0));
        c6.addEdge(edge("A#1", "B#1", 1.0));
        c6.removeEdge(new WirePoint("A#0"), new WirePoint("B#0"));
        boolean cutOk = c6.contains(new WirePoint("A#1")) && c6.contains(new WirePoint("B#1"))
                && c6.networkOf(new WirePoint("A#1")) == c6.networkOf(new WirePoint("B#1"));
        System.out.println("   剪第 1 根后：A#1=" + c6.contains(new WirePoint("A#1"))
                + " B#1=" + c6.contains(new WirePoint("B#1"))
                + " 同网络=" + (c6.networkOf(new WirePoint("A#1"))
                == c6.networkOf(new WirePoint("B#1"))) + (cutOk ? " ✅" : " ❌"));
        pass &= cutOk;

        System.out.println("==== " + (pass ? "全部通过 ✅" : "存在失败 ❌") + " ====");
        System.exit(pass ? 0 : 1);
    }
}