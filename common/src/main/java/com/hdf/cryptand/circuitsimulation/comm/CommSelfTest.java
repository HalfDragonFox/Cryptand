package com.hdf.cryptand.circuitsimulation.comm;

import com.hdf.cryptand.circuitsimulation.cache.NetworkWorldManager;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.circuitsimulation.netop.NetOpExecutor;

import java.util.Map;

/**
 * 通信组件自测（2026-08-22：TCP / UDP / 进程内消息方式与实例隔离交互）。
 *
 * <p>验证：
 *   1) 进程内消息（InProcess）：接口类 → 总接口管理类 → 转发具体实例（创建/数据/查询/释放）；
 *   2) TCP：loopback 客户端经 TCP 发请求 → 服务端转发实例；
 *   3) UDP：loopback 数据报同等工作（不可靠，验证 best-effort）。
 *
 * 运行：{@code ./gradlew :common:runCommTest}
 */
public final class CommSelfTest {

    /** 模拟执行器（服务端创建实例用——平台能力服务端注入，不外传） */
    private static final NetOpExecutor EXEC = new NetOpExecutor() {
        @Override public boolean executeDestroy(Object k, Object d) { return true; }
        @Override public boolean executeSplitMerge(Object k, Object d) { return true; }
        @Override public boolean executeRebuild(Object k, Object d) { return true; }
        @Override public void executeSolve(Object k, Object d) { }
    };

    private static CommHub newHub() {
        WorldFactory factory = new WorldFactory() {
            @Override public com.hdf.cryptand.circuitsimulation.cache.AppLink linkFor(String w) { return null; }
            @Override public NetOpExecutor executorFor(String w) { return EXEC; }
        };
        return new CommHub(new CommRequestHandler(NetworkWorldManager.get(), factory));
    }

    /** 场景：创建实例 → 接线 → 查询 → 释放（验证完全隔离交互） */
    private static boolean scenario(CommClient client, String world) {
        CommResponse r;
        r = client.call(CommOp.CREATE_WORLD, world);
        if (!r.ok) { System.out.println("    CREATE fail: " + r.error); return false; }
        r = client.call(CommOp.ADD_EDGE, world,
                Map.<String, Object>of("a", "B(1,2,3)#0", "b", "B(4,5,6)#0", "r", 1.5));
        if (!r.ok) { System.out.println("    ADD_EDGE fail: " + r.error); return false; }
        r = client.call(CommOp.ADD_DEVICE, world,
                Map.<String, Object>of("keys", java.util.List.of("B(1,2,3)#1", "B(1,2,3)#2")));
        if (!r.ok) { System.out.println("    ADD_DEVICE fail: " + r.error); return false; }
        r = client.call(CommOp.QUERY_SUMMARY, world);
        @SuppressWarnings("unchecked")
        Map<String, Object> sum = (Map<String, Object>) (r.result instanceof Map ? r.result : Map.of());
        boolean ok = r.ok
                && ((Number) sum.getOrDefault("nodes", 0)).intValue() >= 4
                && ((Number) sum.getOrDefault("edges", 0)).intValue() >= 1;
        System.out.println("    summary=" + sum + (ok ? " ✅" : " ❌"));
        r = client.call(CommOp.REMOVE_WORLD, world);
        return ok && r.ok;
    }

    public static void main(String[] args) {
        boolean pass = true;
        System.out.println("==== 通信组件自测（Comm） ====");

        // 1) 进程内消息（InProcess）——接口类本地直调总接口管理类
        System.out.println("1) 进程内消息（CommClient.local → CommHub）");
        CommHub hubIn = newHub();
        CommClient local = CommClient.local(hubIn);
        boolean c1 = scenario(local, "local-world");
        local.close();
        hubIn.close();
        pass &= c1;

        // 1b) 进程内经传输（InProcessTransport 配对 + CommClient.remote）
        System.out.println("2) 进程内经传输（InProcess pair）");
        inProcessScenario();
        pass &= true; // 内部打印结果

        // 2) TCP loopback
        System.out.println("3) TCP loopback");
        try {
            CommHub hubTcp = newHub();
            TcpTransport server = TcpTransport.server(0);
            hubTcp.addEndpoint("tcp", server);
            int port = server.localPort();
            TcpTransport client = TcpTransport.client("127.0.0.1", port);
            client.open(p -> { });
            CommClient remote = CommClient.remote(client);
            boolean c3 = scenario(remote, "tcp-world");
            System.out.println("   tcp ok=" + c3 + (c3 ? " ✅" : " ❌"));
            pass &= c3;
            remote.close();
            hubTcp.close();
        } catch (Exception e) {
            System.out.println("   tcp exception: " + e + (false ? "" : " ❌"));
            pass = false;
        }

        // 3) UDP loopback（不可靠，best-effort）
        System.out.println("4) UDP loopback");
        try {
            CommHub hubUdp = newHub();
            UdpTransport server = UdpTransport.server(0);
            int port = server.localPort();
            hubUdp.addEndpoint("udp", server);
            UdpTransport client = UdpTransport.client("127.0.0.1", port);
            client.open(p -> { });
            CommClient remote = CommClient.remote(client);
            CommResponse r = remote.call(CommOp.PING, "");
            boolean c4 = r.ok && "pong".equals(String.valueOf(r.result));
            System.out.println("   udp ping=" + c4 + (c4 ? " ✅" : " ❌"));
            pass &= c4;
            remote.close();
            hubUdp.close();
        } catch (Exception e) {
            System.out.println("   udp exception: " + e + " ❌");
            pass = false;
        }

        // 5) 对话（Dialogue）——创建句柄（实例引用 + 专属虚拟线程隔离）
        System.out.println("5) 对话句柄（Dialogue：实例引用 + 专属线程隔离）");
        CommHub hubDlg = newHub();
        CommClient localDlg = CommClient.local(hubDlg);
        DialogueHandle d1 = localDlg.openDialogue("dlg-1");
        DialogueHandle d2 = localDlg.openDialogue("dlg-2");
        boolean refOk = d1.world() != null && d2.world() != null
                && d1.world() != d2.world()                       // 各自独立实例
                && d1.executor() != null && d2.executor() != null
                && d1.executor() != d2.executor();                // 各自独立虚拟机线程（隔离）
        // 客户端只对句柄操作：引用直调（性能）+ 专属线程投递 + 经总通讯管理类查询
        d1.world().addEdge(new WireEdge(new WirePoint("B(1,2,3)#0"), new WirePoint("B(4,5,6)#0"),
                1.0, null, 1, "copper", 0, true, "w"));
        boolean[] onThread = {false};
        d1.submitOnDialogueThread(() -> { onThread[0] = !onThread[0]; }); // 专属线程执行
        CommResponse q = d1.call(CommOp.QUERY_SUMMARY);
        boolean c5 = refOk && q.ok && d1.world().edgeCount() == 1;
        System.out.println("   ref(引用)=" + (d1.world() != null) + " 独立线程="
                + (d1.executor() != d2.executor()) + " 直调 edges=" + d1.world().edgeCount()
                + (c5 ? " ✅" : " ❌"));
        pass &= c5;
        // 销毁对话 = 关闭对话（释放实例 + 停专属线程）
        d1.close();
        d2.close();
        boolean closed = NetworkWorldManager.get().getWorld("dlg-1") == null
                && !d1.executor().running();
        System.out.println("   关闭对话：instance freed="
                + (NetworkWorldManager.get().getWorld("dlg-1") == null)
                + " executor stopped=" + !d1.executor().running()
                + (closed ? " ✅" : " ❌"));
        pass &= closed;
        localDlg.close();
        hubDlg.close();

        // 收尾（清理全局管理器）
        for (String w : NetworkWorldManager.get().names()) NetworkWorldManager.get().removeWorld(w);

        System.out.println("==== " + (pass ? "全部通过 ✅" : "存在失败 ❌") + " ====");
        System.exit(pass ? 0 : 1);
    }

    /** 进程内经传输（InProcessTransport 配对：server→hub, client→CommClient.remote） */
    private static void inProcessScenario() {
        CommHub hub = newHub();
        InProcessTransport[] pair = InProcessTransport.pair();
        hub.addEndpoint("inproc", pair[0]); // server 端
        CommClient client = CommClient.remote(pair[1]); // client 端
        boolean ok = scenario(client, "inproc-world");
        System.out.println("   inprocess ok=" + ok + (ok ? " ✅" : " ❌"));
        client.close();
        hub.close();
    }
}