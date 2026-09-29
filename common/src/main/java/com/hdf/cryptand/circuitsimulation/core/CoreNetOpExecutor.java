package com.hdf.cryptand.circuitsimulation.core;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.Node;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.netop.NetOpExecutor;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.PhasorNonlinearMethod;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.circuitsimulation.solver.Solver;
import com.hdf.cryptand.circuitsimulation.solver.Solvers;

/**
 * 仿真核心内置网络操作执行器（2026-08-16，线程 C 上执行）。
 * <p>
 * 对应架构图「网表相关操作类」的执行端：把整合后的网络操作交给本类对
 * {@link Network} 执行：
 *   - 网络拆合（{@link #executeSplitMerge}）：注册表层面合并/拆分网络条目；
 *   - 网络重建（{@link #executeRebuild}）：重建网络（清缓存/重新标记）；
 *   - 网络求解（{@link #executeSolve}）：用内核求解器（Real/Complex/多频/非线性）
 *     求解，结果写入注册表缓存（{@link NetworkRegistry#setResult}）。
 * <p>
 * 求解完成对复合元件统一调 {@link CompositeElement#update}（推进能量/温度，
 * 对应架构图「求解器链条：模型求解 → 记录需要重建」）。
 * <p>
 * 线程安全：本类在分配器线程（普通模式=虚拟线程）执行；只操作注册表（线程安全）
 * 与 Network（调用方保证同一网络不并发求解）。
 */
public final class CoreNetOpExecutor implements NetOpExecutor {

    /** 非线性相量求解方法（可配置；NONE = 普通相量） */
    public volatile PhasorNonlinearMethod nonlinearMethod = PhasorNonlinearMethod.NONE;

    /** 节点化求解路径开关（2026-08-19）：true = NetworkDecomposer+SolvePipeline，
     *  false = 旧版单求解器路径（行为完全等价，用于对比回退）。 */
    public volatile boolean usePipeline = true;

    private final NetworkRegistry registry;
    private final NetworkDecomposer decomposer;
    /** ⚠ 2026-08-30 每实例时间基准（用户：核心实例连接 EDA/MC 各自独立时间）。
     *  求解前设置 net.dt = timeBase.advance()（真实流逝或仿真固定步长）。 */
    private final com.hdf.cryptand.circuitsimulation.solver.TimeBase timeBase;

    public CoreNetOpExecutor(NetworkRegistry registry) {
        this(registry, new com.hdf.cryptand.circuitsimulation.solver.SimClock());
    }

    /** 每实例时间基准构造（EDA 传 SimulatedTimeBase，MC 传共享 SimClock） */
    public CoreNetOpExecutor(NetworkRegistry registry,
                             com.hdf.cryptand.circuitsimulation.solver.TimeBase timeBase) {
        this.registry = registry;
        this.timeBase = timeBase == null
                ? new com.hdf.cryptand.circuitsimulation.solver.SimClock() : timeBase;
        this.decomposer = new NetworkDecomposer();
    }

    /** 当前时间基准（诊断/扩展） */
    public com.hdf.cryptand.circuitsimulation.solver.TimeBase timeBase() {
        return timeBase;
    }

    /** 内部节点化流水线（可调阈值：parallelThreshold / asyncEnabled）。 */
    public NetworkDecomposer decomposer() {
        return decomposer;
    }

    @Override
    public boolean executeSplitMerge(Object networkKey, Object data) {
        // 网络拆合：注册表层面。
        // data 约定：String 拆合指令（"merge:key2,key3" 合并 / "split:keyA" 拆分）
        // 或 null（无操作）。合并 = 把目标网络的元件并入本网络；拆分 = 无操作占位。
        try {
            if (data instanceof String cmd && cmd.startsWith("merge:")) {
                Network target = registry.get(networkKey);
                if (target != null) {
                    for (String k : cmd.substring(6).split(",")) {
                        if (k.isBlank()) continue;
                        Network other = registry.get(k.trim());
                        if (other == null) continue;
                        for (var e : other.elements()) target.addElement(e);
                        for (var c : other.composites()) target.addComposite(c);
                        registry.unregister(k.trim());
                    }
                }
            }
            // "split:" 拆分：保持原网络（注册表不支持自动拆分——由调用方建新网络注册）
        } catch (Throwable ignored) {
        }
        return true;
    }

    @Override
    public boolean executeRebuild(Object networkKey, Object data) {
        // 网络重建：注册表层面清求解结果缓存（重建后需重新求解）。
        // 返回 true = 重建后需要执行拆合（data 为 Boolean 且 true）。
        Network net = registry.get(networkKey);
        if (net == null) return false;
        boolean splitAfter = data instanceof Boolean b && b;
        return splitAfter;
    }

    @Override
    public boolean executeDestroy(Object networkKey, Object data) {
        // 网络内容破坏（设备拆除/导线剪切）：核心注册表场景由调用方自行从
        // 网络/注册表移除元件（图/网表数据变更已由调用方完成），此处空实现占位。
        return true;
    }

    @Override
    public void executeSolve(Object networkKey, Object data) {
        // ⚠ 2026-08-30 引擎独立求解：注册为引擎网络上下文（工厂创建——图+组装器
        // +绑定）→ NetworkSolver 完整求解（电路 + 分片组装器并行 + 绑定器
        // SOLVE_DONE 消息）；纯图（无 ctx）→ 回退旧 solve 路径。
        com.hdf.cryptand.engine.NetworkContext<?> ctx = registry.ctx(networkKey);
        if (ctx != null && ctx.network != null) {
            try {
                ctx.network.dt = timeBase.advance(); // 每实例时间基准
                SolveResult cr = com.hdf.cryptand.engine.NetworkSolver
                        .solveNetwork(ctx);
                if (cr != null) registry.setResult(networkKey, cr);
                return;
            } catch (Throwable ignored) {
                // 引擎求解异常 → 回退旧路径（防御）
            }
        }
        Network net = registry.get(networkKey);
        if (net == null) return;
        SolveResult r = usePipeline ? solveViaPipeline(net) : solve(net);
        registry.setResult(networkKey, r);
    }

    /**
     * 节点化求解入口（2026-08-19）：网络 → NetworkDecomposer 分解为
     * Mna/Thermal/Energy/Post 节点图 → SolvePipeline（矩阵块对角合成
     * + 直算节点阈值串行/并行）。行为与 {@link #solve} 等价
     * （旧 solve 保留作对比回退）。
     */
    public SolveResult solveViaPipeline(Network net) {
        if (net == null) return null;
        // ⚠ 2026-08-30 每实例时间基准：求解前设置网络步长（真实流逝/仿真步进）
        net.dt = timeBase.advance();
        decomposer.nonlinearMethod = nonlinearMethod;
        var pr = decomposer.run(net);
        return pr.primaryResult;
    }

    /**
     * 求解单个网络（可直接调用——同步 API）。选择求解器：
     *   - DC（frequency=0）→ RealMnaSolver（实数 MNA；DC 源在相量域不注入
     *     电压，不能走相量）；
     *   - AC（frequency>0）→ 多频波形组 MultiToneSolver / 相量非线性增强 /
     *     ComplexMnaSolver（按频率阈值）。
     * 求解后对每个复合元件调 update（推进能量/温度，对应求解器链条后处理）。
     */
    public SolveResult solve(Network net) {
        if (net == null) return null;
        // ⚠ 2026-08-30 每实例时间基准：求解前设置网络步长（真实流逝/仿真步进）
        net.dt = timeBase.advance();
        Solver solver;
        if (net.frequency <= 0) {
            // DC / 时域实数求解（独立内核语义：DC 源经诺顿注入电流）
            solver = new com.hdf.cryptand.circuitsimulation.solver.RealMnaSolver();
        } else if (nonlinearMethod != null && nonlinearMethod != PhasorNonlinearMethod.NONE
                && net.hasPhasorNonlinear()) {
            solver = Solvers.createNonlinear(net, nonlinearMethod);
        } else {
            solver = Solvers.create(net);
        }
        SolveResult r = solver.solve(net);
        // 求解器链条后处理：模型求解 → 复合元件推进状态（按端口相量电压）
        double omega = 2 * Math.PI * net.dominantFrequency();
        long now = System.nanoTime();
        for (CompositeElement c : net.composites()) {
            try {
                int a = c.nodeA(), b = c.nodeB();
                if (a < 0 || b < 0) {
                    c.update(null, null, omega, now);
                    continue;
                }
                Complex va = a < net.nodeCount() ? r.voltageAtComplex(net.node(a)) : null;
                Complex vb = b < net.nodeCount() ? r.voltageAtComplex(net.node(b)) : null;
                c.update(va, vb, omega, now);
            } catch (Throwable ignored) {
            }
        }
        return r;
    }
}
