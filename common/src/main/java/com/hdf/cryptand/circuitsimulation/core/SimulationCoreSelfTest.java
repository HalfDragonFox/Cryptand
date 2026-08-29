package com.hdf.cryptand.circuitsimulation.core;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.Node;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

/**
 * 仿真核心自测（2026-08-16，纯引擎无 Minecraft）。
 * <p>
 * 验证（参考架构图）：
 *   1) 核心启动：分配器（线程 A）+ 异步交互管理类（线程 B）就绪；
 *   2) 网络注册 + 直接 API 求解（{@link SimulationCore#submitSolve} 走分配器
 *      线程 C 执行，异步返回）；
 *   3) 同步求解（{@link SimulationCore#solveNow} 直接调执行器）；
 *   4) 网络重建（{@link SimulationCore#submitRebuild}）；
 *   5) 网络拆合（{@link SimulationCore#submitSplitMerge} 合并两个网络）；
 *   6) 结果缓存（{@link NetworkRegistry#result} / solvedVersion）。
 * <p>
 * 运行：{@code ./gradlew :common:runSimCoreTest} 或
 * {@code java -cp ... com.hdf.cryptand.circuitsimulation.core.SimulationCore}
 */
public final class SimulationCoreSelfTest {

    /** 构建直流分压网络：5V → R1=10Ω → R2=20Ω → GND，Vmid ≈ 3.333V */
    static Network divider() {
        Network net = new Network();
        net.dt = 1.0;
        Node n0 = net.addNode(); // 地(0)
        Node n1 = net.addNode(); // 中间
        Node n2 = net.addNode(); // 源侧
        net.addElement(new DcVoltageSource(n2.id, n0.id, 5.0, 1e-9));
        net.addElement(new Resistor(n2.id, n1.id, 10.0));
        net.addElement(new Resistor(n1.id, n0.id, 20.0));
        return net;
    }

    /** 等待某网络求解完成（轮询结果非 null 或版本变化），超时返回 false */
    static boolean awaitResult(NetworkRegistry reg, Object key, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (reg.result(key) != null) return true;
            try { Thread.sleep(10); } catch (InterruptedException ignored) { }
        }
        return false;
    }

    /** 等待某网络求解版本变化（>before），超时返回 false */
    static boolean awaitVersion(NetworkRegistry reg, Object key, long before, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (reg.solvedVersion(key) > before) return true;
            try { Thread.sleep(10); } catch (InterruptedException ignored) { }
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        boolean pass = true;
        SimulationCore core = SimulationCore.get();
        System.out.println("==== 仿真核心自测（SimulationCore） ====");
        System.out.println("1) 启动核心（分配器线程 A + 异步交互管理类线程 B）");
        core.start();
        System.out.println("   已启动=" + core.isStarted()
                + " 分配器线程=" + core.async().dispatcher().maxThreads() + " 个");
        pass &= core.isStarted();

        System.out.println("2) 注册网络 + 异步求解（走分配器线程 C）");
        core.registerNetwork("div1", divider());
        core.submitSolve("div1");
        boolean got = awaitResult(core.registry(), "div1", 3000);
        SolveResult r1 = core.result("div1");
        double vmid = got && r1 != null ? r1.voltageAt(core.network("div1").node(1)) : Double.NaN;
        boolean dcOk = got && r1 != null && Math.abs(vmid - 10.0 / 3.0) < 0.01;
        System.out.println("   异步求解 Vmid=" + vmid + "V（期望≈3.333）"
                + (dcOk ? "✅" : "❌") + " 版本=" + core.registry().solvedVersion("div1"));
        pass &= dcOk;

        System.out.println("3) 同步求解（solveNow 直接调执行器）");
        core.registerNetwork("div2", divider());
        SolveResult r2 = core.solveNow("div2");
        double vmid2 = r2 != null ? r2.voltageAt(core.network("div2").node(1)) : Double.NaN;
        boolean syncOk = r2 != null && Math.abs(vmid2 - 10.0 / 3.0) < 0.01;
        System.out.println("   同步求解 Vmid=" + vmid2 + "V（期望≈3.333）" + (syncOk ? "✅" : "❌"));
        pass &= syncOk;

        System.out.println("4) 网络重建（清缓存后重解）");
        core.registerNetwork("reb1", divider());
        core.solveNow("reb1");
        long vBefore = core.registry().solvedVersion("reb1");
        core.submitRebuild("reb1", false);
        core.submitSolve("reb1");
        got = awaitVersion(core.registry(), "reb1", vBefore, 3000);
        long vAfter = core.registry().solvedVersion("reb1");
        boolean rebuildOk = got && vAfter > vBefore;
        System.out.println("   重建+重解 版本 " + vBefore + "→" + vAfter + (rebuildOk ? " ✅" : " ❌"));
        pass &= rebuildOk;

        System.out.println("5) 网络拆合（合并两个分压网络）");
        core.registerNetwork("m1", divider());
        core.registerNetwork("m2", divider());
        core.submitSplitMerge("m1", "merge:m2");
        // 等待：轮询 m2 被移除
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && core.registry().contains("m2")) {
            Thread.sleep(10);
        }
        boolean mergeOk = !core.registry().contains("m2")
                && core.network("m1") != null
                && core.network("m1").elements().size() >= 6;
        System.out.println("   合并后 m2 移除=" + !core.registry().contains("m2")
                + " m1 元件数=" + (core.network("m1") == null ? 0 : core.network("m1").elements().size())
                + (mergeOk ? " ✅" : " ❌"));
        pass &= mergeOk;

        System.out.println("6) 结果缓存与诊断");
        System.out.println("   注册表=" + core.registry() + " 待处理=" + core.pendingOperations()
                + " 核心=" + core);
        System.out.println(pass ? "\n==== 全部通过 ✅ ====" : "\n==== 有失败项 ❌ ====");

        core.stop();
        if (!pass) System.exit(1);
    }
}
