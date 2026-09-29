package com.hdf.cryptand.circuitsimulation.solver;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.CurrentSource;
import com.hdf.cryptand.circuitsimulation.model.elements.DcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.IdealTransformer;
import com.hdf.cryptand.circuitsimulation.model.elements.MutualInductor;
import com.hdf.cryptand.circuitsimulation.model.elements.WaveformSource;

/**
 * 求解器工厂：按【配置精度】+【元件能力检测】选择 float 或 double 求解器。
 * <p>
 * float 化最小影响方案：
 *   - 现有 double 求解器 100% 保留（回退路径）
 *   - float 求解器并行新增（内存减半 + 缓存/SIMD 收益）
 *   - 配置 {@link #floatEnabled}（neoforge ConfigLoad 启动时写入）选择精度
 *   - 能力检测：网络含不支持 float stamp 的元件（半导体/变压器等）→ 自动回退
 *     double，保证行为正确
 */
public final class Solvers {

    /** 是否启用 float 求解器（由 {@link #setBackend(String)} 写入；默认 false = double） */
    public static volatile boolean floatEnabled = false;

    /**
     * 当前求解后端名称（2026-09-11 用户：配置直接写 float / double，便于以后扩展其他类型）。
     * <p>扩展点：新增后端（如 native-f32、混合精度）时，在 {@link #setBackend(String)} 加分支，
     * 并在 {@link #create(SolveMode, Network)} 中按 {@code backend} 选择对应 Solver 实现即可。
     */
    public static volatile String backend = "double";

    /**
     * 设置求解后端（配置值直接写名称）。
     *
     * @param name "double"（默认）/ "float" / 未来扩展类型
     * @return true = 识别成功；false = 未知名称 → 已回落 double
     */
    public static boolean setBackend(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        switch (n) {
            case "float" -> {
                backend = "float";
                floatEnabled = true;
                return true;
            }
            case "double" -> {
                backend = "double";
                floatEnabled = false;
                return true;
            }
            default -> {
                backend = "double";
                floatEnabled = false;
                return false;
            }
        }
    }

    private Solvers() {
    }

    /** 按网络频率选择求解器（float 优先，能力不足自动回退 double）。 */
    public static Solver create(Network net) {
        if (net == null) return new RealMnaSolver();
        // 多频叠加（2026-08-13 PLC 核心）：网络声明波形组且多频 → MultiToneSolver
        // （每频率独立求解 + 合成 RMS）。绕开频率阈值（工频可能 <100Hz 但多频
        // 必须相量）。
        if (net.waveforms() != null && net.waveforms().isMultiTone()) {
            return new MultiToneSolver();
        }
        SolveMode mode = SolverModeSelector.selectMode(net.frequency,
                SolverModeSelector.DEFAULT_FREQUENCY_THRESHOLD_HZ);
        return create(mode, net);
    }

    /** 按模式 + 网络选择求解器。 */
    public static Solver create(SolveMode mode, Network net) {
        // 2026-08-20 求解器原生开路支持：stamp 前标记开路电流源（两端无闭合
        // 回路 → 不注入，避免悬空端发散 100MV）。电压源诺顿等效开路天然 =
        // 源电压，无需处理。所有 solver 路径（AC/DC/float/double）统一在此标记。
        markOpenCurrentSources(net);
        boolean useFloat = floatEnabled && net != null && canFloat(net, mode)
                && floatNativeAvailable(mode);
        if (useFloat) {
            return mode == SolveMode.COMPLEX_AC
                    ? new FloatComplexMnaSolver() : new FloatRealMnaSolver();
        }
        return mode == SolveMode.COMPLEX_AC
                ? new ComplexMnaSolver() : new RealMnaSolver();
    }

    /**
     * 标记开路源（2026-08-20 用户要求：求解器原生支持开路分支计算，不并联
     * 参考电阻 hack）。理想电流源裸注入到悬空端（无闭合回路）会由 GMin 决定电压
     * → 巨大值（100MV 实测）。构建元件连通图（节点通过非源元件连通，含导线
     * 段 composites）：源两端不连通 → setOpenCircuit(true) → stamp 不注入
     * （断路，两端等电位/悬空由 GMin 兜底，不发散）。幂等（结构属性），可重复调用。
     * <p>
     * 2026-08-23 电压源同样标记：电压源诺顿等效（内阻 1e-4 → 导纳 10000S）
     * 在【一端接下游而另一端悬空】时，注入电流经 GMin 形成假回路 → 导线假电流
     * （500KA 实锤）→ 温度爆炸。标记逻辑：源两端在非源连通图中不连通，且
     * 【至少一端接有下游元件】→ 开路（不注入，纯内阻）。孤立源（两端皆无
     * 其他元件）不标记——诺顿开路电压 = 源电压（用户要求悬空源显示电压）。
     */
    public static void markOpenCurrentSources(Network net) {
        if (net == null) return;
        try {
            java.util.Map<Integer, java.util.Set<Integer>> adj =
                    new java.util.HashMap<>();
            for (Element e : net.elements()) {
                // 各种源边剔除（回路判定：源不是“连通”元件）
                if (e instanceof CurrentSource
                        || e instanceof AcVoltageSource
                        || e instanceof DcVoltageSource
                        || e instanceof WaveformSource) continue;
                // ⚠ 2026-08-21 电容也剔除：电容两端经【自身内部】连通（ESR+Cap）
                //   ——若不剔除，孤立电容两端会被误判连通 → 永不 openCircuit →
                //   剪线后 Backward Euler I_hist 注入 GMin → 假电流 → 发热爆炸
                // ⚠ 2026-08-30 审计 U5：仅 DC/低频（ω<1）剔除——AC 下电容
                //   Y=jωC 是正常通路，应加入连通图，否则「源 a--C--w--R--b」
                //   串联电容电路源两端被误判不连通 → openCircuit=true → 源不
                //   注入 → 负载无电流（AC 隔直耦合/电容启动/LC 注入场景）。
                //   AC 下孤立电容有 stampComplex 的 openCircuit 检查兜底
                //   （隔离时不注入），无假电流风险。
                if (e instanceof com.hdf.cryptand.circuitsimulation.model.elements.Capacitor) {
                    double omegaNet = 2 * Math.PI * Math.max(net.frequency, 0);
                    if (omegaNet < 1.0) continue; // DC/低频：仍剔除（防孤立假电流）
                    // AC：电容作为通路加入 adj（走下方通用 addAdj）
                }
                if (e instanceof IdealTransformer it) {
                    // 原边/副边绕组各自连通（电流源接任一侧都不判开路）
                    if (it.a1 != it.a2) addAdj(adj, it.a1, it.a2);
                    if (it.b1 != it.b2) addAdj(adj, it.b1, it.b2);
                    continue;
                }
                if (e instanceof MutualInductor mi) {
                    addAdj(adj, mi.a1, mi.a2);
                    addAdj(adj, mi.b1, mi.b2);
                    continue;
                }
                int a = e.nodeA(), b = e.nodeB();
                if (a == b) continue;
                addAdj(adj, a, b);
            }
            // 复合元件（导线段 WireComposite 等）：导线是闭合回路关键通路，漏掉
            // 会把"电流源+电阻+导线"正常闭合电路误判成开路。
            // CapacitorModel 端口【不因自身连通】——单独标记孤立，不加进 adj。
            for (CompositeElement ce : net.composites()) {
                int a = ce.nodeA(), b = ce.nodeB();
                if (a < 0 || b < 0 || a == b) continue;
                if (ce instanceof com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel) {
                    continue;
                }
                addAdj(adj, a, b);
            }
            for (Element e : net.elements()) {
                if (e instanceof CurrentSource cs) {
                    int a = cs.nodeA(), b = cs.nodeB();
                    cs.setOpenCircuit(a == b || !connectedIn(adj, a, b));
                }
            }
            // ===== 电压源开路（2026-08-23 用户：开路时 AC+电阻导线 500KA）=====
            // 孤立源（两端皆无下游元件）不标记（保持诺顿开路电压 = 源电压）；
            // 一端接下游而两端不连通 → 标记开路（不注入 → 纯内阻，无假电流）。
            for (Element e : net.elements()) {
                boolean marked = false;
                int a = -1, b = -1;
                if (e instanceof AcVoltageSource vs) { a = vs.nodeA(); b = vs.nodeB(); }
                else if (e instanceof DcVoltageSource vs) { a = vs.nodeA(); b = vs.nodeB(); }
                else if (e instanceof WaveformSource vs) { a = vs.nodeA(); b = vs.nodeB(); }
                else continue;
                if (a < 0 || b < 0) continue;
                boolean aIn = adj.containsKey(a);
                boolean bIn = adj.containsKey(b);
                boolean open = (a == b) || ((aIn || bIn) && !connectedIn(adj, a, b));
                if (e instanceof AcVoltageSource vs) {
                    vs.setOpenCircuit(open); marked = true;
                } else if (e instanceof DcVoltageSource vs) {
                    vs.setOpenCircuit(open); marked = true;
                } else if (e instanceof WaveformSource vs) {
                    vs.setOpenCircuit(open); marked = true;
                }
                if (marked && open) {
                    long now = System.currentTimeMillis();
                    if (now - OPEN_SRC_DBG_LAST >= 3000) {
                        OPEN_SRC_DBG_LAST = now;
                        System.out.println("[OpenSrc] " + e.type() + " nodes=" + a
                                + "-" + b + " openCircuit=true (开路防假电流)");
                    }
                }
            }
            // ⚠ 2026-08-30 审计 U6：孤立/剪线电感标记（与电容/源一致）——电感
            // 此前无 openCircuit 保护，剪线后孤立电感仍 stamp 并注入 iPrev →
            // 悬空端仅 GMin 兜底 → v≈iPrev/GMin（1e8V 级发散）。标记后 stamp
            // 不注入 iPrev、不 commit（电机绕组电感在闭合回路中 connectedIn=true
            // → 不受影响）。
            for (Element e : net.elements()) {
                if (e instanceof com.hdf.cryptand.circuitsimulation.model.elements.Inductor ind) {
                    int a = ind.nodeA(), b = ind.nodeB();
                    ind.setOpenCircuit(a == b || !connectedIn(adj, a, b));
                }
            }
            // 孤立电容标记（2026-08-21 剪线不爆炸）：两端无闭合回路 → openCircuit
            // （不注入 I_hist、不 commit → 保持电荷，不假电流/不发热/不爆炸）
            for (CompositeElement ce : net.composites()) {
                if (ce instanceof com.hdf.cryptand.circuitsimulation.model.composite.CapacitorModel cm) {
                    int a = cm.nodeA(), b = cm.nodeB();
                    boolean open = (a == b || !connectedIn(adj, a, b));
                    cm.setOpenCircuit(open);
                    if (open) {
                        // 诊断（节流）：孤立电容标记——确认剪线/孤立检测是否误判
                        //（接入电路却被判孤立 → 电容开路 → 电路行为错误）
                        long now = System.currentTimeMillis();
                        if (now - OPEN_CAP_DBG_LAST >= 3000) {
                            OPEN_CAP_DBG_LAST = now;
                            System.out.println("[OpenCap] cap nodes=" + a + "-" + b
                                    + " openCircuit=true (孤立/剪线检测)");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }
    /** [OpenSrc] 诊断节流 */
    private static volatile long OPEN_SRC_DBG_LAST;
    /** [OpenCap] 诊断节流 */
    private static volatile long OPEN_CAP_DBG_LAST;

    private static void addAdj(java.util.Map<Integer, java.util.Set<Integer>> adj,
                               int a, int b) {
        adj.computeIfAbsent(a, k -> new java.util.HashSet<>()).add(b);
        adj.computeIfAbsent(b, k -> new java.util.HashSet<>()).add(a);
    }

    private static boolean connectedIn(java.util.Map<Integer, java.util.Set<Integer>> adj,
                                       int from, int to) {
        if (from == to) return true;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
        q.add(from);
        seen.add(from);
        while (!q.isEmpty()) {
            int cur = q.poll();
            for (int nb : adj.getOrDefault(cur, java.util.Collections.emptySet())) {
                if (nb == to) return true;
                if (seen.add(nb)) q.add(nb);
            }
        }
        return false;
    }

    /**
     * 非线性相量求解器（2026-08-15 用户需求）：按方法选择分段线性化 /
     * 谐波平衡 / 动态相量。NONE → 普通相量（伪时域由 PhasorEngine 处理）。
     */
    public static Solver createNonlinear(Network net, PhasorNonlinearMethod method) {
        if (method == null) return create(net);
        return switch (method) {
            case PIECEWISE -> new PiecewiseLinearSolver();
            case HARMONIC_BALANCE -> new HarmonicBalanceSolver();
            case DYNAMIC_PHASOR -> new DynamicPhasorSolver();
            case NONE -> create(net);
        };
    }

    /**
     * float 求解器需要 native 单精度可用：
     *   - AC 相量：SuperLU SCZ 或 LAPACK cgesv 任一可用
     *   - DC/时域：LAPACK sgesv 可用
     * native float 不可用时回退 double 求解器（float 自研已删除）。
     */
    private static boolean floatNativeAvailable(SolveMode mode) {
        if (mode == SolveMode.COMPLEX_AC) {
            return (NativeSparse.isLoaded() && NativeSparse.isSingleLoaded())
                    || (NativeDense.isLoaded() && NativeDense.isSingleLoaded());
        }
        return NativeDense.isLoaded() && NativeDense.isSingleLoaded();
    }

    /**
     * 网络是否支持 float 求解：所有元件实现了对应 float stamp。
     * 任一元件不支持（半导体工作点迭代、变压器等）→ 整个网络回退 double。
     */
    public static boolean canFloat(Network net, SolveMode mode) {
        for (Element e : net.elements()) {
            if (mode == SolveMode.COMPLEX_AC ? !e.supportsFloatComplex()
                    : !e.supportsFloatReal()) {
                return false;
            }
        }
        return true;
    }
}
