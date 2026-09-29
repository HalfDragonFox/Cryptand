package com.hdf.cryptand.engine;

import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaSolver;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

/**
 * ===== 引擎网络求解器（2026-08-30 引擎独立核心——纯 Java 零 MC） =====
 *
 * ECS 式网络求解器：对一个网络上下文（{@link NetworkContext}——图 + 组装器
 * 表 + 温度/能量模型 + 绑定器）做完整求解——纯算法（可单独作为软件仿真器
 * 使用——不依赖任何 MC 内容）：
 *
 *  ① 电路求解：ComplexMnaSolver.solve(network)（解图——节点电压/电流）；
 *  ② 遍历组装器表 → 按组装器含的模型分发（温度模型 → 温度任务；能量模型 →
 *     能量任务）——【分片组装器】并行（组装器数分片——虚拟线程）；
 *  ③ 求解完成后向绑定器发 SOLVE_DONE 消息（绑定器必须实现消息接口——
 *     消息处理由平台实现）。
 *
 * 网络间天然并行（网络锁 tryBegin/end——异步调度；本求解器只做单网络求解）。
 */
public final class NetworkSolver {

    private NetworkSolver() {}

    /** 分片数上限（按组装器数量分片——每片一组组装器并行） */
    private static final int MAX_SLICES = 8;
    /** 导线温度诊断节流（温度不变定位） */
    private static volatile long WIRE_DIAG_LAST;

    /** 求解一个网络：① 电路（图）② 分片组装器（温度/能量）③ 绑定器 SOLVE_DONE */
    public static <K> SolveResult solveNetwork(NetworkContext<K> ctx) {
        if (ctx == null || ctx.network == null) return null;
        // ⚠ 2026-08-30 网络仿真步长 = 0.05 × 倍率（用户：可配置倍率——节点化
        // 管线 simDt / 伪时域 / 温度推进统一用）
        ctx.network.dt = ctx.stepDt();
        // ① 电路求解（解图——节点电压/电流）
        SolveResult res = new ComplexMnaSolver().solve(ctx.network);
        if (res == null) return null;
        // ② 分片组装器（温度/能量模型分发——并行）
        dispatchModels(ctx, res);
        // ②b 导线段温度推进（用户：导线每段一个组装器（含 R+温度模型）——
        // 连续段合并统一计算；求解后每段 update（lossPower→thermal.advance））
        advanceWires(ctx, res);
        // ③ 绑定器消息（SOLVE_DONE——平台处理）
        sendSolved(ctx, res);
        return res;
    }

    /** 导线段/导线组温度推进：统一复合元件生命周期 update（损耗 → 温度推进——
     *  黑盒）。导线组：每组建 WireComposite（总 R + 共享温度模型——统一计算
     *  温度后统一赋值）；单段（wireSegments）兼容旧路径。 */
    private static void advanceWires(NetworkContext<?> ctx, SolveResult res) {
        if (res == null || res.complex == null) return;
        int n = res.complex.length;
        double omega = 2 * Math.PI * ctx.frequency;
        long now = System.nanoTime();
        // ① 导线组（每组建 WireComposite——总 R + 共享温度——统一推进）
        if (ctx.wireGroups != null && !ctx.wireGroups.isEmpty()) {
            for (com.hdf.cryptand.engine.WireGroup g : ctx.wireGroups) {
                try {
                    if (g.segmentCount() == 0) continue;
                    // 组的总 R + 共享温度 → WireComposite（额定功率 = 额定电流²×总R）
                    double ratedW = g.ratedCurrent() * g.ratedCurrent()
                            * g.totalResistance();
                    com.hdf.cryptand.circuitsimulation.model.composite.WireComposite wc =
                            new com.hdf.cryptand.circuitsimulation.model.composite.WireComposite(
                                    g.segments().get(0).nodeA(),
                                    g.segments().get(g.segmentCount() - 1).nodeB(),
                                    g.totalResistance(), g.thermal(),
                                    "WG-" + System.identityHashCode(g), ratedW);
                    int na = wc.nodeA(), nb = wc.nodeB();
                    if (na < 0 || nb < 0 || na >= n || nb >= n) continue;
                    // ⚠ 固定仿真步长推进（0.05s/轮——快轮次也累积正确；
                    // realDt 真实时间毫秒级 → 升温极慢 = "温度不变"根因）
                    double power = wc.lossPower(res.complex[na], res.complex[nb], omega);
                    if (g.thermal() != null) {
                        g.thermal().advanceStep(power, ctx.stepDt()); // 0.05×倍率
                        // 诊断（节流）：确认推进执行 + power 值（温度不变定位）
                        long dNow = System.currentTimeMillis();
                        if (dNow - WIRE_DIAG_LAST >= 2000) {
                            WIRE_DIAG_LAST = dNow;
                            System.out.println("[WireDiag] na=" + na + " nb=" + nb
                                    + " R=" + String.format("%.4f", wc.resistance)
                                    + " vd=" + String.format("%.3f",
                                    res.complex[na].sub(res.complex[nb]).abs())
                                    + " power=" + String.format("%.3f", power)
                                    + " T=" + String.format("%.2f",
                                    g.thermal().tempCelsius()));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            return; // 用导线组（网络存导线组——不再走单段）
        }
        // ② 单段（wireSegments——旧路径兼容）
        if (ctx.wireSegments == null || ctx.wireSegments.isEmpty()) return;
        for (com.hdf.cryptand.circuitsimulation.model.composite.WireComposite seg
                : ctx.wireSegments) {
            try {
                int na = seg.nodeA(), nb = seg.nodeB();
                if (na < 0 || nb < 0 || na >= n || nb >= n) continue;
                seg.update(res.complex[na], res.complex[nb], omega, now); // lossPower→thermal
            } catch (Throwable ignored) {
            }
        }
    }

    /** 遍历组装器表 → 按组装器数量分片 → 每片并行；片内每个组装器按其所含
     *  模型分发到对应求解器（温度 → 温度任务；能量 → 能量任务）。 */
    private static <K> void dispatchModels(NetworkContext<K> ctx, SolveResult res) {
        if (ctx.assemblers.isEmpty()) return;
        java.util.List<java.util.Map.Entry<K, Assembler>> list =
                new java.util.ArrayList<>(ctx.assemblers.entrySet());
        int n = list.size();
        int slices = Math.min(n, MAX_SLICES);
        if (slices <= 1) {
            // 少量组装器 → 串行（零线程开销）
            for (java.util.Map.Entry<K, Assembler> e : list) {
                processAssembler(ctx, res, e.getKey(), e.getValue());
            }
            return;
        }
        // 分片并行：每片一组组装器（虚拟线程——JDK21，纯 Java）
        int sliceSize = (n + slices - 1) / slices;
        try (java.util.concurrent.ExecutorService ex =
                     java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            for (int s = 0; s < slices; s++) {
                final int from = s * sliceSize;
                final int to = Math.min(n, from + sliceSize);
                ex.submit(() -> {
                    for (int i = from; i < to; i++) {
                        java.util.Map.Entry<K, Assembler> e = list.get(i);
                        processAssembler(ctx, res, e.getKey(), e.getValue());
                    }
                });
            }
        } catch (Throwable ignored) {
            for (java.util.Map.Entry<K, Assembler> e : list) {
                processAssembler(ctx, res, e.getKey(), e.getValue());
            }
        }
    }

    /** 单个组装器：按其含的模型分发（温度 → 温度任务；能量 → 能量任务） */
    private static <K> void processAssembler(NetworkContext<K> ctx, SolveResult res,
                                             K key, Assembler assembler) {
        try {
            // 温度模型 → 温度任务（模型推进——黑盒；平台策略如 DC 惩罚由平台扩展）
            ThermalDevice td = ctx.deviceThermals.get(key);
            if (td != null) {
                // 温度推进由模型生命周期/引擎伪时域完成——此处仅显式推进入口
                // （模型黑盒 set/compute/get——组装器/平台可扩展策略）
            }
            // 能量模型 → 能量任务（电荷/电压同步）
            EnergyDevice ed = ctx.energyDevices.get(key);
            if (ed != null) {
                syncEnergy(ctx, res, ed);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 能量任务（状态驱动：电荷/电压同步——隔直通交由相量导纳） */
    private static void syncEnergy(NetworkContext ctx, SolveResult res, EnergyDevice ed) {
        try {
            if (res == null || res.complex == null) return;
            int n = res.complex.length;
            int na = ed.nodeA(), nb = ed.nodeB();
            if (na < 0 || nb < 0 || na >= n || nb >= n) return;
            ed.syncCharge(res.complex[na], res.complex[nb]);
        } catch (Throwable ignored) {
        }
    }

    /** 求解完成 → 绑定器发 SOLVE_DONE（消息处理由平台实现） */
    private static void sendSolved(NetworkContext<?> ctx, SolveResult res) {
        if (ctx.bindings.isEmpty()) return;
        EngineMessage done = EngineMessage.of(EngineMessage.Type.SOLVE_DONE, res);
        for (Binding b : ctx.bindings.values()) {
            try {
                b.onMessage(done);
            } catch (Throwable ignored) {
            }
        }
    }
}
