package com.hdf.cryptand.circuitsimulation.model;

import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;

import java.util.ArrayList;
import java.util.List;

/**
 * 电路网络：节点集合 + 元件集合 + 复合元件集合。
 * frequency=0 表示纯直流/时域；frequency>0 表示交流相量模式（复数求解）。
 * 本类完全独立于 Minecraft/PowerGrid，是分布式求解任务的数据源。
 * <p>
 * 2026-08-12 用户要求：网络收集【所有元件】——基础元件（{@link #elements()}）
 * 与复合元件（{@link #composites()}）。复合元件 {@link #addComposite} 时自动
 * 展开为基础元件进 elements（求解器直接装配）；求解完成后对每个复合元件调
 * {@link CompositeElement#update}（算损耗/推进温度）。外部持有基类引用即可
 * 统一操作所有复合元件（设备/导线）。
 */
public class Network {
    /** 时间步长（秒），时域瞬态用。默认 0.05（20 TPS 对应 1 tick） */
    public double dt = 0.05;

    /** 当前仿真时间（秒）。时域瞬态由外部在每个求解步推进；波形源据此求瞬时值 */
    public double time = 0;

    /** 网络交流频率（Hz）。0 = DC / 时域；>0 = AC 相量 */
    public double frequency = 0;

    /** 多频波形组（2026-08-13 PLC 核心）：null = 单频（现状）；非 null 且多频
     *  → MultiToneSolver 每频率独立求解 + 合成。主导频率由波形组决定。 */
    private WaveformGroup waveforms;

    public WaveformGroup waveforms() { return waveforms; }

    /** 设置多频波形组（多频叠加；置 null 恢复单频） */
    public void setWaveforms(WaveformGroup wg) { this.waveforms = wg; }

    /** 主导频率（多频 = 波形组主频；单频 = frequency） */
    public double dominantFrequency() {
        if (waveforms != null) {
            double dom = waveforms.dominantFrequency();
            if (dom > 0) return dom;
        }
        return frequency;
    }

    /** 参考节点（地），默认节点 0 接地 */
    public int groundNode = 0;

    /**
     * 状态是否已稳定（2026-08-20 用户要求：稳态电路跳过，加强实时能力）。
     * <p>
     * 由 StateNode 每次推进后更新：网络内所有状态模型（温度/转速/应力）都
     * 到达稳态 → true（网络可跳过求解）；任一状态仍在变化 → false。
     * buildPending 据此：状态稳定 + 参数未变 → 完全跳过（复用缓存，不求解）。
     * 非 state 网络（无状态模型）恒 true（线性静态电路本就稳态）。
     */
    public volatile boolean stateSettled = true;

    private final List<Node> nodes = new ArrayList<>();
    private final List<Element> elements = new ArrayList<>();
    private final List<CompositeElement> composites = new ArrayList<>();
    /** 设备真实端子 → 引擎节点映射（2026-08-12 端子元件；非电路元件，不进 elements） */
    private final List<TerminalElement> terminals = new ArrayList<>();

    public Node addNode() {
        Node n = new Node(nodes.size());
        nodes.add(n);
        return n;
    }

    public Node node(int id) { return nodes.get(id); }

    /** 网络结构指纹（2026-08-24 防线：与 SolveResult.networkHash 校验同源）。
     *  含节点数/地节点/全部基础元件（type+nodeA+nodeB+关键参数）哈希——
     *  任意元素变化 → 指纹变 → 旧结果无法通过校验。 */
    public long structureHash() {
        long h = 1125899906842597L;
        h = h * 31 + nodeCount();
        h = h * 31 + groundNode;
        try {
            for (Element e : elements) {
                h = h * 31 + e.type().hashCode();
                h = h * 31 + e.nodeA();
                h = h * 31 + e.nodeB();
                if (e instanceof com.hdf.cryptand.circuitsimulation.model.elements
                        .AcVoltageSource av) {
                    h = h * 31 + Double.doubleToLongBits(av.amplitude);
                    h = h * 31 + Double.doubleToLongBits(av.seriesResistance);
                } else if (e instanceof com.hdf.cryptand.circuitsimulation.model.elements
                        .Resistor rr) {
                    h = h * 31 + Double.doubleToLongBits(rr.resistance);
                } else if (e instanceof com.hdf.cryptand.circuitsimulation.model.elements
                        .Inductor in) {
                    h = h * 31 + Double.doubleToLongBits(in.inductance);
                }
            }
        } catch (Throwable ignored) {
        }
        return h;
    }

    public void addElement(Element e) {
        elements.add(e);
    }

    /** 添加复合元件：记录到 composites + 自动展开为基础元件进 elements（求解直接装配） */
    public void addComposite(CompositeElement c) {
        if (c == null) return;
        composites.add(c);
        for (Element e : c.decompose()) {
            if (e != null) elements.add(e);
        }
    }

    public List<Node> nodes() { return nodes; }
    public List<Element> elements() { return elements; }
    /** 全部复合元件（设备复合模型/导线段）；求解后统一 update */
    public List<CompositeElement> composites() { return composites; }

    /**
     * 非线性器件计数（2026-08-15 用户架构）：elements 中 {@code isNonlinear()}
     * 数量。&gt;0 → 该网络需【固定节拍伪时域】反复求解（含能量/温度状态漂移）；
     * =0 → 全线性，走【正常求解】（事件驱动、缓存命中零重解）。
     * <p>
     * 2026-08-18 修复“加热器不发热”：composites（复合元件：电机/加热器/导线等）
     * 的 {@code isNonlinear()}（有热模型 → 温度随损耗漂移）也必须计入——否则
     * 加热器（电感=0 展开为纯 RESISTOR，elements 全线性）被缓存命中跳过
     * solveAll → advancePseudoTime 不执行 → 设备温度冻结在环境温度。
     */
    public int nonlinearCount() {
        int n = 0;
        for (Element e : elements) if (e.isNonlinear()) n++;
        for (CompositeElement c : composites) if (c.isNonlinear()) n++;
        return n;
    }

    /**
     * 是否含【无记忆相量非线性元件】（2026-08-15：{@link NonlinearPhasorElement}，
     * 二极管等）。&gt;0 → 该网络的电气求解用增强相量算法（分段线性化/谐波平衡/
     * 动态相量）；能量元件（电容/电感 isNonlinear 但非本接口）仍由伪时域
     * advanceState 推进——混合架构。
     */
    public boolean hasPhasorNonlinear() {
        for (Element e : elements) {
            if (e instanceof NonlinearPhasorElement) return true;
        }
        return false;
    }

    /** 添加设备端子映射（2026-08-12 端子元件：多端子模型真实端子 → 引擎节点） */
    public void addTerminal(TerminalElement t) {
        if (t != null) terminals.add(t);
    }

    /** 全部设备端子映射（不进 elements/不序列化，仅结构层映射记录） */
    public List<TerminalElement> terminals() { return terminals; }

    public int nodeCount() { return nodes.size(); }

    @Override
    public String toString() {
        return "Network{nodes=" + nodes.size() + ", elements=" + elements.size()
                + ", composites=" + composites.size()
                + ", f=" + frequency + ", dt=" + dt + "}";
    }
}
