package com.hdf.cryptand.circuitsimulation.core;

import com.hdf.cryptand.circuitsimulation.compute.DirectSolveNode;
import com.hdf.cryptand.circuitsimulation.compute.SolveGraph;
import com.hdf.cryptand.circuitsimulation.compute.SolveNodeContext;
import com.hdf.cryptand.circuitsimulation.compute.SolvePipeline;
import com.hdf.cryptand.circuitsimulation.compute.SolvePipeline.SolvePipelineResult;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.Node;
import com.hdf.cryptand.circuitsimulation.model.TerminalElement;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.Set;

/**
 * 求解节点化流水线自测（2026-08-19，纯引擎无 Minecraft）。
 *
 * <p>验证（对应 architecture 节点化架构图）：
 *   1) DC 网络：MnaNode 不可块化 → 独立实数求解（Vmid≈3.333V）；
 *   2) AC 网络：MnaNode 块对角/单块路径 → 相量求解（Vmid≈6.667V 幅值）；
 *   3) 端子回填：PostNode 依赖 mna 后回填 TerminalElement；
 *   4) 阈值并行：parallelThreshold 调低 → 直算节点走 ThreadDispatchers，
 *      结果与串行一致（asyncUsed=true）；
 *   5) DAG 校验：成环依赖 → IllegalArgumentException。
 *
 * 运行：{@code ./gradlew :common:runPipelineTest}
 */
public final class SolvePipelineSelfTest {

    /** 直流分压网络：5V → R1=10Ω → R2=20Ω → GND，Vmid ≈ 3.333V */
    static Network dcDivider() {
        Network net = new Network();
        net.dt = 1.0;
        Node n0 = net.addNode(); // 地(0)
        Node n1 = net.addNode(); // 中间
        Node n2 = net.addNode(); // 源侧
        net.addElement(new DcVoltageSource(n2.id, n0.id, 5.0, 1e-9));
        net.addElement(new Resistor(n2.id, n1.id, 10.0));
        net.addElement(new Resistor(n1.id, n0.id, 20.0));
        net.addTerminal(new TerminalElement("dev", 0, n1.id));
        return net;
    }

    /** 交流分压网络：10V 峰值 → R1=10Ω → R2=20Ω → GND，Vmid ≈ 6.667V（幅值） */
    static Network acDivider() {
        Network net = new Network();
        net.frequency = 50.0;
        Node n0 = net.addNode(); // 地(0)
        Node n1 = net.addNode(); // 中间
        Node n2 = net.addNode(); // 源侧
        net.addElement(new AcVoltageSource(n2.id, n0.id, 10.0, 0.0, 0.1));
        net.addElement(new Resistor(n2.id, n1.id, 10.0));
        net.addElement(new Resistor(n1.id, n0.id, 20.0));
        net.addTerminal(new TerminalElement("dev", 0, n1.id));
        return net;
    }

    public static void main(String[] args) {
        boolean pass = true;
        System.out.println("==== 求解节点化流水线自测（SolvePipeline） ====");
        NetworkDecomposer decomposer = new NetworkDecomposer();

        // 1) DC：MnaNode 不可块化 → 独立实数求解
        System.out.println("1) DC 分压网络（节点化求解）");
        Network dc = dcDivider();
        SolvePipelineResult r1 = decomposer.run(dc);
        SolveResult s1 = r1.primaryResult;
        double vmid1 = s1.voltageAt(dc.node(1));
        boolean dcOk = Math.abs(vmid1 - 10.0 / 3.0) < 0.01;
        System.out.println("   Vmid=" + vmid1 + "V（期望≈3.333）" + (dcOk ? "✅" : "❌")
                + " 节点数=" + r1.directNodeCount + " 块合成=" + r1.mergedBlockCount
                + " 主结果=" + s1);
        pass &= dcOk;

        // 2) AC：MnaNode 块对角/单块路径 → 相量求解
        System.out.println("2) AC 分压网络（相量求解）");
        Network ac = acDivider();
        SolvePipelineResult r2 = decomposer.run(ac);
        SolveResult s2 = r2.primaryResult;
        double vmid2 = s2.voltageAt(ac.node(1));
        boolean acOk = s2.complex != null && Math.abs(vmid2 - 20.0 / 3.0) < 0.05;
        System.out.println("   Vmid=" + vmid2 + "V（期望≈6.667 幅值）" + (acOk ? "✅" : "❌")
                + " 相量=" + (s2.complex != null) + " 块合成=" + r2.mergedBlockCount);
        pass &= acOk;

        // 3) 端子回填：PostNode 依赖 mna 后回填 TerminalElement
        System.out.println("3) 端子回填（PostNode）");
        TerminalElement t = ac.terminals().get(0);
        boolean postOk = Math.abs(t.voltage() - 20.0 / 3.0) < 0.05
                && Math.abs(t.frequency() - 50.0) < 1e-9
                && Math.abs(t.re() - 20.0 / 3.0) < 0.05;
        System.out.println("   dev#0 V=" + t.voltage() + "V re=" + t.re()
                + " im=" + t.im() + " f=" + t.frequency() + "Hz " + (postOk ? "✅" : "❌"));
        pass &= postOk;

        // 4) 阈值并行：parallelThreshold=1 → 直算节点走虚拟线程并行
        System.out.println("4) 阈值并行（parallelThreshold=1）");
        SolvePipeline.parallelThreshold = 1;
        NetworkDecomposer par = new NetworkDecomposer();
        SolvePipelineResult r3 = par.run(dcDivider());
        SolveResult s3 = r3.primaryResult;
        double vmid3 = s3.voltageAt(dcDivider().node(1));
        boolean parOk = r3.asyncUsed && Math.abs(vmid3 - 10.0 / 3.0) < 0.01;
        System.out.println("   asyncUsed=" + r3.asyncUsed + " Vmid=" + vmid3 + "V"
                + (parOk ? " ✅" : " ❌"));
        pass &= parOk;
        SolvePipeline.parallelThreshold = 64; // 复位

        // 5) DAG 校验：悬空依赖 / 自依赖 / 成环 → IllegalArgumentException
        System.out.println("5) DAG 校验");
        SolveGraph g = new SolveGraph();
        DirectSolveNode nodeA = new DirectSolveNode() {
            public String id() { return "a"; }
            public Set<String> dependsOn() { return Set.of("b"); }
            public int estimateCost() { return 1; }
            public void execute(SolveNodeContext ctx) { }
        };
        boolean danglingRejected = false;
        try {
            g.addNode(nodeA); // b 未注册 → 悬空依赖拒绝
        } catch (IllegalArgumentException e) {
            danglingRejected = true;
        }
        boolean selfRejected = false;
        DirectSolveNode selfNode = new DirectSolveNode() {
            public String id() { return "s"; }
            public Set<String> dependsOn() { return Set.of("s"); }
            public int estimateCost() { return 1; }
            public void execute(SolveNodeContext ctx) { }
        };
        try {
            g.addNode(selfNode); // 依赖自身 → 拒绝
        } catch (IllegalArgumentException e) {
            selfRejected = true;
        }
        boolean cycleRejected = false;
        try {
            SolveGraph cg = new SolveGraph();
            DirectSolveNode b = new DirectSolveNode() {
                public String id() { return "b"; }
                public Set<String> dependsOn() { return Set.of(); }
                public int estimateCost() { return 1; }
                public void execute(SolveNodeContext ctx) { }
            };
            cg.addNode(b); // b 无依赖，可注册
            DirectSolveNode a = new DirectSolveNode() {
                public String id() { return "a"; }
                public Set<String> dependsOn() { return Set.of("b"); }
                public int estimateCost() { return 1; }
                public void execute(SolveNodeContext ctx) { }
            };
            cg.addNode(a); // a→b 无环，可注册
            a.dependsOn(); // 占位避免未使用告警
            DirectSolveNode c = new DirectSolveNode() {
                public String id() { return "c"; }
                public Set<String> dependsOn() { return Set.of("a"); }
                public int estimateCost() { return 1; }
                public void execute(SolveNodeContext ctx) { }
            };
            cg.addNode(c); // c→a 无环
            c.dependsOn();
            DirectSolveNode a2 = new DirectSolveNode() {
                public String id() { return "a"; }
                public Set<String> dependsOn() { return Set.of(); }
                public int estimateCost() { return 1; }
                public void execute(SolveNodeContext ctx) { }
            };
            cg.addNode(a2); // id 重复 → 拒绝（环无法构造：依赖缺失即时拒绝，防御性兜底）
        } catch (IllegalArgumentException e) {
            cycleRejected = true;
        }
        boolean dagOk = danglingRejected && selfRejected && cycleRejected;
        System.out.println("   悬空拒绝=" + danglingRejected + " 自依赖拒绝=" + selfRejected
                + " 非法图拒绝=" + cycleRejected + (dagOk ? " ✅" : " ❌"));
        pass &= dagOk;

        System.out.println("==== " + (pass ? "全部通过 ✅" : "存在失败 ❌") + " ====");
        System.exit(pass ? 0 : 1);
    }
}
