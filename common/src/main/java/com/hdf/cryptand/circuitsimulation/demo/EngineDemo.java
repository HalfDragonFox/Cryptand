package com.hdf.cryptand.circuitsimulation.demo;

import com.hdf.cryptand.circuitsimulation.compute.ComputeEngineType;
import com.hdf.cryptand.circuitsimulation.compute.ComputeResult;
import com.hdf.cryptand.circuitsimulation.compute.ComputeScheduler;
import com.hdf.cryptand.circuitsimulation.compute.ComputeTask;
import com.hdf.cryptand.circuitsimulation.compute.LocalComputeEngine;
import com.hdf.cryptand.circuitsimulation.compute.NetworkSnapshot;
import com.hdf.cryptand.circuitsimulation.compute.ThreadQuality;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.WaveformType;
import com.hdf.cryptand.circuitsimulation.model.elements.Capacitor;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.elements.Resistor;
import com.hdf.cryptand.circuitsimulation.model.elements.WaveformSource;
import com.hdf.cryptand.circuitsimulation.solver.RealMnaSolver;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.circuitsimulation.solver.Solver;
import com.hdf.cryptand.circuitsimulation.solver.SolverModeSelector;

import java.util.Arrays;

/**
 * 引擎自测演示：验证整条链路
 *  模型 → 求解器（DC/瞬态/AC）→ 快照序列化 → 调度器 → 本地引擎执行
 *
 * 运行：java -cp common/build/classes/java/main com.hdf.cryptand.circuitsimulation.demo.EngineDemo
 */
public final class EngineDemo {

    public static void main(String[] args) {
        dcVoltageDivider();
        rcTransient();
        rlTransient();
        acPhasor();
        waveformTransient();
        waveformSerialization();
        frequencyDualMode();
        distributedChain();
    }

    /** 1) 直流分压：5V → R1=10Ω → R2=20Ω → GND，Vmid 应 ≈ 3.333V */
    static void dcVoltageDivider() {
        Network net = new Network();
        net.dt = 1.0;
        var n0 = net.addNode(); // 地(0)
        var n1 = net.addNode(); // 中间
        var n2 = net.addNode(); // 源侧
        net.addElement(new DcVoltageSource(n2.id, n0.id, 5.0, 1e-9));
        net.addElement(new Resistor(n2.id, n1.id, 10.0));
        net.addElement(new Resistor(n1.id, n0.id, 20.0));

        SolveResult r = new com.hdf.cryptand.circuitsimulation.solver.RealMnaSolver().solve(net);
        System.out.println("[DC分压] " + r + "  Vmid=" + fmt(r.voltageAt(n1)) + "V (期望≈3.333)  Vsrc=" + fmt(r.voltageAt(n2)) + "V (期望≈5.000)");
    }

    /** 2) RC 瞬态：5V → R=1kΩ → C=1mF → GND，τ=1s，3τ 后电容电压应 ≈ 4.75V */
    static void rcTransient() {
        Network net = new Network();
        net.dt = 0.1;
        var g = net.addNode();
        var a = net.addNode(); // 源
        var b = net.addNode(); // 电容
        net.addElement(new DcVoltageSource(a.id, g.id, 5.0, 1e-9));
        net.addElement(new Resistor(a.id, b.id, 1000.0));
        Capacitor c = new Capacitor(b.id, g.id, 1e-3);
        net.addElement(c);

        var solver = new com.hdf.cryptand.circuitsimulation.solver.RealMnaSolver();
        double v = 0;
        for (int t = 1; t <= 30; t++) {   // 30 步 × 0.1s = 3s
            SolveResult r = solver.solve(net);
            v = r.voltageAt(b);
        }
        System.out.println("[RC瞬态] 3s后 Vc=" + fmt(v) + "V (期望≈4.752, 5*(1-e^-3))");
    }

    /** 3) RL 瞬态：5V → L=1H → R=1Ω → GND，τ=1s，3s 后电流 ≈ 4.75A */
    static void rlTransient() {
        Network net = new Network();
        net.dt = 0.1;
        var g = net.addNode();
        var a = net.addNode(); // 源
        var b = net.addNode(); // R/L 连接点
        net.addElement(new DcVoltageSource(a.id, g.id, 5.0, 1e-9));
        Inductor l = new Inductor(a.id, b.id, 1.0);
        net.addElement(l);
        net.addElement(new Resistor(b.id, g.id, 1.0));

        var solver = new com.hdf.cryptand.circuitsimulation.solver.RealMnaSolver();
        double i = 0;
        for (int t = 1; t <= 30; t++) {
            SolveResult r = solver.solve(net);
            i = l.iPrev;
        }
        System.out.println("[RL瞬态] 3s后 I=" + fmt(i) + "A (期望≈4.752, (5/R)*(1-e^-3))");
    }

    /** 4) AC 相量：10V∠0° → R=3Ω → L(ωL=4Ω) 串联，|I| 应 = 10/5 = 2A，VR=6V，VL=8V */
    static void acPhasor() {
        Network net = new Network();
        net.frequency = 1.0; // ω = 2π
        var g = net.addNode();
        var a = net.addNode();
        var b = net.addNode();
        net.addElement(new com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource(a.id, g.id, 10.0, 0.0, 1e-9));
        net.addElement(new Resistor(a.id, b.id, 3.0));
        net.addElement(new Inductor(b.id, g.id, 4.0 / (2 * Math.PI))); // 使 ωL = 4Ω

        SolveResult r = new com.hdf.cryptand.circuitsimulation.solver.ComplexMnaSolver().solve(net);
        double vl = r.voltageAt(b);              // 电感电压幅值（b→g）
        // VR = Va - Vb（用完整相量相减再取模，避免幅值相减丢相位）
        double vr = r.voltageAtComplex(a).sub(r.voltageAtComplex(b)).abs();
        double i = vl / 4.0;                     // |I| = |VL| / ωL
        System.out.println("[AC相量] |I|=" + fmt(i) + "A (期望≈2.0)  VR=" + fmt(vr) + "V (期望≈6.0)  VL=" + fmt(vl) + "V (期望≈8.0)");
    }

    /** 6) 波形源时域验证：方波/三角波/锯齿/正弦在特征时刻的瞬时电压 */
    static void waveformTransient() {
        var solver = new RealMnaSolver();
        checkWaveform(solver, WaveformType.SINE,     10, 0.25,  10.0, "正弦 t=0.25");
        checkWaveform(solver, WaveformType.SINE,     10, 0.75, -10.0, "正弦 t=0.75");
        checkWaveform(solver, WaveformType.SQUARE,   10, 0.25,  10.0, "方波 t=0.25");
        checkWaveform(solver, WaveformType.SQUARE,   10, 0.75, -10.0, "方波 t=0.75");
        checkWaveform(solver, WaveformType.TRIANGLE, 10, 0.25,  10.0, "三角 t=0.25");
        checkWaveform(solver, WaveformType.TRIANGLE, 10, 0.75, -10.0, "三角 t=0.75");
        checkWaveform(solver, WaveformType.SAWTOOTH, 10, 0.25,  -5.0, "锯齿 t=0.25");
        checkWaveform(solver, WaveformType.SAWTOOTH, 10, 0.75,   5.0, "锯齿 t=0.75");
    }

    /** 波形源（1Hz 电压源）驱动 1Ω 到地，节点电压应等于源瞬时值 */
    static void checkWaveform(RealMnaSolver solver, WaveformType wf, double amp, double t,
                              double expect, String label) {
        Network net = new Network();
        var g = net.addNode();
        var a = net.addNode();
        net.time = t;
        net.addElement(new WaveformSource(a.id, g.id, true, wf, amp, 1.0, 0.0, 0.5, 0.0, 1e-6));
        net.addElement(new Resistor(a.id, g.id, 1.0));
        double v = solver.solve(net).voltageAt(a);
        System.out.println("[波形] " + label + " V=" + fmt(v) + "V (期望" + fmt(expect) + ")  "
                + (Math.abs(v - expect) < 1e-3 ? "OK" : "FAIL"));
    }

    /** 7) 波形序列化往返：WaveformType 随快照传输并被正确识别 */
    static void waveformSerialization() {
        Network net = new Network();
        var g = net.addNode();
        var a = net.addNode();
        var b = net.addNode();
        net.time = 0.375;
        net.addElement(new WaveformSource(a.id, g.id, true, WaveformType.SQUARE, 12.0, 2.0, 45.0, 0.3, 1.0, 1e-6));
        net.addElement(new WaveformSource(b.id, g.id, false, WaveformType.TRIANGLE, 3.0, 1.0, 0.0, 0.5, 0.0, 0.0));
        net.addElement(new Resistor(a.id, b.id, 100.0));

        byte[] bytes = NetworkSnapshot.from(net).toByteArray();
        NetworkSnapshot back = NetworkSnapshot.fromByteArray(bytes);
        boolean wfOk = back.elements[0].waveform == WaveformType.SQUARE
                && back.elements[1].waveform == WaveformType.TRIANGLE
                && back.time == net.time;

        // 重建网络并求解，远端结果应与本地一致
        Network rebuilt = back.toNetwork();
        double local = new RealMnaSolver().solve(net).voltageAt(b);
        double remote = new RealMnaSolver().solve(rebuilt).voltageAt(b);
        System.out.println("[波形序列化] 字节=" + bytes.length
                + " 波形识别=" + wfOk
                + " 时间还原=" + (back.time == net.time)
                + " 本地Vb=" + fmt(local) + " 远端Vb=" + fmt(remote)
                + " (期望一致)");
    }
    /** 8) 频率阈值双模式：低频实时时域采样 vs 高频频率标签相量求解 */
    static void frequencyDualMode() {
        final double threshold = 100.0;   // 配置指定阈值（默认 100Hz）

        // ===== 低频 50Hz（< 100Hz）：实时时域逐采样 =====
        Network low = new Network();
        low.dt = 0.05;
        var g = low.addNode();
        var a = low.addNode();
        var b = low.addNode();
        low.addElement(new WaveformSource(a.id, g.id, true, WaveformType.SINE, 10.0, 50.0, 0.0, 0.5, 0.0, 1e-6));
        low.addElement(new Resistor(a.id, b.id, 100.0));
        low.addElement(new Resistor(b.id, g.id, 100.0));
        System.out.println("[双模式] " + SolverModeSelector.describe(low, threshold));

        var realSolver = new RealMnaSolver();
        // 逐采样：t=0.001s 处瞬时值 = 10*sin(2π*50*0.001)/2 ≈ 1.545V
        low.time = 0.001;
        double vLow = realSolver.solve(low).voltageAt(b);
        double expectLow = 10.0 * Math.sin(2 * Math.PI * 50 * 0.001) / 2.0;
        System.out.println("[双模式] 低频实时采样 t=0.001s Vb=" + fmt(vLow)
                + "V (期望≈" + fmt(expectLow) + ")  "
                + (Math.abs(vLow - expectLow) < 1e-3 ? "OK" : "FAIL"));
        // 再采样一点，展示可追踪波形
        low.time = 0.005;
        double vLow2 = realSolver.solve(low).voltageAt(b);
        System.out.println("[双模式] 低频实时采样 t=0.005s Vb=" + fmt(vLow2) + "V (随波形变化)");

        // ===== 高频 1000Hz（≥ 100Hz）：频率标签相量求解 =====
        Network high = new Network();
        high.frequency = 1000.0;
        var g2 = high.addNode();
        var a2 = high.addNode();
        var b2 = high.addNode();
        high.addElement(new com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource(a2.id, g2.id, 10.0, 0.0, 1e-6));
        high.addElement(new Resistor(a2.id, b2.id, 100.0));
        high.addElement(new Resistor(b2.id, g2.id, 100.0));
        System.out.println("[双模式] " + SolverModeSelector.describe(high, threshold));

        SolveResult rHigh = new com.hdf.cryptand.circuitsimulation.solver.ComplexMnaSolver().solve(high);
        double vHigh = rHigh.voltageAt(b2);   // 纯电阻分压 → 幅值 5V
        System.out.println("[双模式] 高频相量 Vb幅值=" + fmt(vHigh) + "V (期望≈5.0)  "
                + (Math.abs(vHigh - 5.0) < 1e-3 ? "OK" : "FAIL"));

        // ===== 自动选择验证 =====
        Solver sLow = SolverModeSelector.selectSolver(low, threshold);
        Solver sHigh = SolverModeSelector.selectSolver(high, threshold);
        System.out.println("[双模式] 自动选择: 低频→" + sLow.mode() + "  高频→" + sHigh.mode()
                + "   (期望 REAL_DC / COMPLEX_AC)");
    }

    /** 5) 分布式链路：Network → 快照 → 字节流 → 任务(含最低质量) → 调度器 → 本地引擎 */
    static void distributedChain() {
        Network net = new Network();
        var g = net.addNode();
        var a = net.addNode();
        var b = net.addNode();
        net.addElement(new DcVoltageSource(a.id, g.id, 12.0, 1e-9));
        net.addElement(new Resistor(a.id, b.id, 6.0));
        net.addElement(new Resistor(b.id, g.id, 6.0));

        NetworkSnapshot snap = NetworkSnapshot.from(net);
        byte[] payload = snap.toByteArray();
        System.out.println("[分布式] 快照=" + snap + "  序列化字节数=" + payload.length);

        ComputeTask task = new ComputeTask(snap, ThreadQuality.SINGLE_CORE, 5,
                ComputeEngineType.CPU.bit, System.nanoTime() + 1_000_000_000L, 1_000_000_000L);
        System.out.println("[分布式] 任务=" + task + "  任务字节数=" + task.toByteArray().length);

        ComputeScheduler scheduler = new ComputeScheduler();
        scheduler.register(new LocalComputeEngine(2, "test"));
        ComputeResult result = scheduler.execute(task);
        System.out.println("[分布式] 结果=" + result + "  Vsrc=" + fmt(result.voltages[1]) + "V (期望≈12.0)  Vmid=" + fmt(result.voltages[2]) + "V (期望≈6.0)");
        scheduler.close();
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }
}
