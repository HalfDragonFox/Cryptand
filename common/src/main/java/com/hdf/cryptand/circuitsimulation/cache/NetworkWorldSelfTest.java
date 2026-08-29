package com.hdf.cryptand.circuitsimulation.cache;

import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.circuitsimulation.netop.NetOpExecutor;
import com.hdf.cryptand.circuitsimulation.netop.NetOpKind;

/**
 * 网络世界组件自测（2026-08-22：实例统管对话 + 缓存数据 + 消息）。
 *
 * <p>验证：
 *   1) 通过实例（NetworkWorld.create）创建具体对象对话（MC/EDA）+ 自动创建
 *      缓存数据；NetworkWorldManager 全局统一管理；
 *   2) 网络 = 完整数据容器（导线/端点/连续段/元件/结果）；
 *   3) 外部发 CacheOp（世界名 + 网络引用 + 操作）→ 路由 → 核心（异步执行）；
 *   4) 对话回调：onCacheReady / onCacheChanged（版本去重）/ onCacheDisposed；
 *   5) 释放实例 → 数据 + 记录表清除。
 *
 * 运行：{@code ./gradlew :common:runWorldTest}
 */
public final class NetworkWorldSelfTest {

    private static final class RecordingLink implements AppLink {
        final String platform;
        int ready, changed, disposed;
        long changedVer = -1;
        RecordingLink(String p) { this.platform = p; }
        @Override public String platform() { return platform; }
        @Override public void onCacheReady(NetworkWorld w) { ready++; }
        @Override public void onCacheChanged(NetworkWorld w, long v) { changed++; changedVer = v; }
        @Override public void onCacheDisposed(NetworkWorld w) { disposed++; }
    }

    private static final class RecordingExecutor implements NetOpExecutor {
        int destroy, splitMerge, rebuild, solve;
        @Override public boolean executeDestroy(Object k, Object d) { destroy++; return true; }
        @Override public boolean executeSplitMerge(Object k, Object d) { splitMerge++; return true; }
        @Override public boolean executeRebuild(Object k, Object d) { rebuild++; return true; }
        @Override public void executeSolve(Object k, Object d) { solve++; }
    }

    private static final RecordingExecutor EXEC = new RecordingExecutor();

    public static void main(String[] args) {
        boolean pass = true;
        System.out.println("==== 网络世界组件自测（NetworkWorld） ====");
        NetworkWorldManager mgr = NetworkWorldManager.get();

        // 1) 实例创建对话 + 缓存数据 + 全局管理（幂等）
        System.out.println("1) 实例创建（MC/EDA 对话 + 缓存数据）");
        RecordingLink mcLink = new RecordingLink("mc");
        RecordingLink edaLink = new RecordingLink("eda");
        NetworkWorld mc = NetworkWorld.create("mc-world", mcLink, EXEC);
        NetworkWorld eda = NetworkWorld.create("eda-world", edaLink, EXEC);
        NetworkWorld mcAgain = NetworkWorld.create("mc-world", null, EXEC); // 幂等
        boolean c1 = mc.name().equals("mc-world") && eda.name().equals("eda-world")
                && mc.link() == mcLink && eda.link() == edaLink
                && mc == mcAgain
                && mcLink.ready == 1 && edaLink.ready == 1
                && mgr.getWorld("mc-world") == mc && mgr.size() == 2;
        System.out.println("   worlds=" + mgr.names() + " ready(mc=" + mcLink.ready
                + ",eda=" + edaLink.ready + ") 幂等=" + (mc == mcAgain)
                + (c1 ? " ✅" : " ❌"));
        pass &= c1;

        // 2) 网络 = 完整数据容器（ECS 只存数据）
        System.out.println("2) 网络 = 完整数据容器（导线/端点/段/元件）");
        CachedNetwork net = mc.createNetwork("net-1");
        WirePoint a = new WirePoint("B(1,2,3)#0");
        WirePoint b = new WirePoint("B(4,5,6)#0");
        net.addWire(new WireEdge(a, b, 0.5, "t0", 1.5, "copper", 0, true, "c"));
        net.addPoint(new WirePoint("B(1,2,3)#1"));
        long verBefore = net.version();
        net.addElement(new com.hdf.cryptand.circuitsimulation.model.AbstractElement(0, 1) {
            @Override public com.hdf.cryptand.circuitsimulation.model.ElementType type() {
                return com.hdf.cryptand.circuitsimulation.model.ElementType.RESISTOR;
            }
        });
        boolean c2 = net.edgeCount() == 1 && net.nodeCount() == 3
                && net.points().size() == 3 && net.segments().size() == 1
                && net.adjacent(a).size() == 1
                && net.elements().size() == 1 && net.version() > verBefore;
        System.out.println("   " + net + (c2 ? " ✅" : " ❌"));
        pass &= c2;

        // 3) CacheOp 消息路由（世界名 + 网络引用 + 操作）→ 核心
        System.out.println("3) CacheOp 消息路由（外部 → 核心）");
        mgr.submit(CacheOp.of("mc-world", "net-1", NetOpKind.SPLIT_MERGE));
        mgr.submit(CacheOp.of("mc-world", "net-1", NetOpKind.REBUILD, new Object[]{null, true}));
        mgr.submit(CacheOp.of("mc-world", "net-1", NetOpKind.SOLVE));
        for (int i = 0; i < 200 && (EXEC.splitMerge < 1 || EXEC.rebuild < 1 || EXEC.solve < 1); i++) {
            try { Thread.sleep(10); } catch (InterruptedException ignored) { }
        }
        boolean c3 = EXEC.splitMerge >= 1 && EXEC.rebuild >= 1 && EXEC.solve >= 1;
        System.out.println("   executor: splitMerge=" + EXEC.splitMerge + " rebuild="
                + EXEC.rebuild + " solve=" + EXEC.solve + (c3 ? " ✅" : " ❌"));
        pass &= c3;

        // 4) 对话回调（缓存变化 → onCacheChanged 版本去重）
        System.out.println("4) 对话回调（notifyChanged 版本去重）");
        mc.addEdge(new WireEdge(new WirePoint("P#0"), new WirePoint("Q#0"),
                1.0, null, 1, "copper", 0, true, "w"));
        mc.notifyChanged();
        mc.notifyChanged(); // 版本未再变 → 去重
        boolean c4 = mcLink.changed == 1 && mcLink.changedVer == mc.version();
        System.out.println("   changed=" + mcLink.changed + " ver=" + mcLink.changedVer
                + (c4 ? " ✅" : " ❌"));
        pass &= c4;

        // 5) 释放实例 → onCacheDisposed + 数据清除
        System.out.println("5) 释放实例（removeWorld）");
        mgr.removeWorld("eda-world");
        boolean c5 = edaLink.disposed == 1 && mgr.getWorld("eda-world") == null
                && mgr.size() == 1;
        System.out.println("   disposed(eda)=" + edaLink.disposed + " worlds=" + mgr.size()
                + (c5 ? " ✅" : " ❌"));
        pass &= c5;

        mgr.removeWorld("mc-world");

        System.out.println("==== " + (pass ? "全部通过 ✅" : "存在失败 ❌") + " ====");
        System.exit(pass ? 0 : 1);
    }
}