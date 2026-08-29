package com.hdf.cryptand.circuitsimulation.model;

/**
 * 端子元件（2026-08-12 用户要求）：实际模型真实端子 ↔ 引擎网络的映射接口。
 * <p>
 * 多端子实际模型（变压器 4 端子、三相电机 A/B/C/N、万用表 3 端子等）的每个
 * 真实端子对应一个 TerminalElement，把引擎【内部电路节点】映射到【真实模型
 * 端子】——内部电路元件连到 engineNode，外部导线也连到同一 engineNode（端子
 * 即接口挂载点），引擎结构据此知道"哪些引擎节点是设备的哪些端子"。
 * <p>
 * 【测试点（2026-08-13 用户架构）】：端子 = 电路的测试点。求解器求解完成后
 * 引擎底层自动把该端子对应节点的电压回填到端子本身（{@link #record}）——
 * 天然正确：悬空端因内部元件（绕组 R-L）MNA 等电位 → 求解值即正确值；不做
 * 任何"强制 0V / 强制等电位"的上层 hack。消费端（风扇/电机/仪表/写回）直接
 * 读端子电压即可。
 * <p>
 * 悬空端子：即使无外部导线，端子节点也【始终在引擎】（构建时无条件收集/
 * 创建）→ 设备两端齐全 → 内部元件（绕组 R-L 等）让悬空端与接入端 MNA
 * 等电位 → 设备不会因"一端接入、一端悬空"而误启动（开路电机不转）；
 * 写回时悬空端回写等电位值（非 0）。
 * <p>
 * 本类【不是电路元件】：不进 Network.elements()、不参与 MNA stamp、不序列化
 * （NetworkSnapshot 只抓基础元件）——它是网络结构层的端子映射 + 测试点记录，
 * 由 {@link Network#terminals()} 收集，供写回/诊断/未来多端子扩展使用。
 */
public final class TerminalElement {

    /** 设备键（如 "B" + BlockPos） */
    public final String deviceKey;
    /** 真实端子号（0..N-1，对应 PowerGrid ElectricBehaviour.getTerminal） */
    public final int terminalIndex;
    /** 引擎节点（挂载点 = 该端子在引擎网络里的节点 id） */
    public final int engineNode;

    /** 测试点电压（RMS；DC 为瞬时值）。volatile：求解线程写、消费线程读 */
    private volatile double voltage;
    /** 测试点完整相量（AC 实部/虚部；DC 时 re=voltage, im=0） */
    private volatile double re, im;
    /** 测试点频率（Hz；DC=0；2026-08-15：端子即接入模型，频率随每次求解回填） */
    private volatile double frequency;
    /** 本轮求解是否有有效值（求解成功回填 true；清零/无解 → false） */
    private volatile boolean valid;

    public TerminalElement(String deviceKey, int terminalIndex, int engineNode) {
        this.deviceKey = deviceKey;
        this.terminalIndex = terminalIndex;
        this.engineNode = engineNode;
    }

    /** 求解完成后回填端子电压（由求解器经 TerminalRecorder 调用；天然正确） */
    public void record(double voltage, double re, double im, double frequency) {
        this.voltage = voltage;
        this.re = re;
        this.im = im;
        this.frequency = frequency;
        this.valid = true;
    }

    /** 本端子无有效求解值（网络清零/求解失败/未建模）→ 电压 0 + 无效 */
    public void invalidate() {
        this.voltage = 0;
        this.re = 0;
        this.im = 0;
        this.frequency = 0;
        this.valid = false;
    }

    /** 测试点电压（RMS；DC 为瞬时值）。无有效值 → 0 */
    public double voltage() { return voltage; }

    /** 完整相量实部（AC）；DC 时 = voltage */
    public double re() { return re; }

    /** 完整相量虚部（AC）；DC 时 0 */
    public double im() { return im; }

    /** 测试点频率（Hz；DC=0）。无有效值 → 0 */
    public double frequency() { return frequency; }

    /** 本轮是否有有效求解值 */
    public boolean valid() { return valid; }

    /** 是否同一设备端子 */
    public boolean matches(String key, int term) {
        return key != null && key.equals(deviceKey) && term == terminalIndex;
    }

    @Override
    public String toString() {
        return "Terminal{" + deviceKey + "#" + terminalIndex + "→node" + engineNode + "}";
    }
}
