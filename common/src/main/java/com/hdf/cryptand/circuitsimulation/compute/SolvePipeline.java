package com.hdf.cryptand.circuitsimulation.compute;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import com.hdf.cryptand.circuitsimulation.solver.BlockDiagonalSolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 求解流水线：统一执行一个 {@link SolveGraph}。
 *
 * <p>2026-08-19 节点化求解核心。两阶段：
 * <pre>
 * 阶段1 MATRIX：块对角合成
 *   所有 blockEligible 的矩阵节点 → 各自子矩阵 stamp 进同一全局
 *   ComplexMnaBuilder（offset 偏移）→ BlockDiagonalSolver 一次求解 →
 *   按 offset 切回各块（applyBlock）；不可块化的（DC/非线性/多频）
 *   → directSolve() 独立求解。
 *
 * 阶段2 DIRECT：按拓扑层推进
 *   依赖分析由 SolveGraph.topoLayers() 给出；每层节点无依赖可并行，
 *   节点数 ≥ parallelThreshold 且 asyncEnabled → ThreadDispatchers
 *   虚拟线程并行，否则同线程串行（零调度开销）。
 * </pre>
 *
 * <p>阈值配置（纯 common，neoforge ConfigLoad 可启动时写入）：
 * <ul>
 *   <li>{@link #parallelThreshold} 直算节点数阈值，默认 64</li>
 *   <li>{@link #asyncEnabled} 是否启用异步并行，默认 true</li>
 * </ul>
 */
public final class SolvePipeline {

    /** 直算节点数达到该阈值才走异步并行（低于阈值串行，避免线程调度开销）。 */
    public static volatile int parallelThreshold = 64;

    /** 是否启用 ThreadDispatchers 异步并行（关掉后恒串行，行为等价于旧路径）。 */
    public static volatile boolean asyncEnabled = true;

    /** 单次流水线执行结果。 */
    public static final class SolvePipelineResult {
        /** 每个节点的求解结果（矩阵节点必填，直算节点可能为 null）。 */
        public final Map<String, SolveResult> nodeResults;
        /** 网络主结果 = 第一个矩阵节点（电气 MNA）的结果；无矩阵节点时为 null。 */
        public final SolveResult primaryResult;
        /** 块对角合成子网数（0 = 未合成）。 */
        public final int mergedBlockCount;
        /** 直算节点总数。 */
        public final int directNodeCount;
        /** 是否走了异步并行路径。 */
        public final boolean asyncUsed;

        SolvePipelineResult(Map<String, SolveResult> nodeResults, SolveResult primaryResult,
                            int mergedBlockCount, int directNodeCount, boolean asyncUsed) {
            this.nodeResults = nodeResults;
            this.primaryResult = primaryResult;
            this.mergedBlockCount = mergedBlockCount;
            this.directNodeCount = directNodeCount;
            this.asyncUsed = asyncUsed;
        }

        @Override
        public String toString() {
            return "SolvePipelineResult{merged=" + mergedBlockCount + ", direct=" + directNodeCount
                    + ", async=" + asyncUsed + ", primary=" + primaryResult + "}";
        }
    }

    /**
     * 执行节点图。
     *
     * @param graph   节点图（网络分解产物）
     * @param network 被求解的网络（只读）
     * @param omega   求解角频率
     * @param simDt   推进步长（秒）
     * @param nowNanos 仿真时钟（纳秒）
     */
    public SolvePipelineResult execute(SolveGraph graph, Network network,
                                       double omega, double simDt, long nowNanos) {
        Map<String, SolveResult> results = new LinkedHashMap<>();
        SolveResult primary = null;
        int mergedCount = 0;
        boolean asyncUsed = false;

        // ── 阶段1：矩阵节点 ──────────────────────────────────────────
        List<MatrixSolveNode> blockNodes = new ArrayList<>();
        List<MatrixSolveNode> soloNodes = new ArrayList<>();
        for (SolveNode n : graph.nodesOfKind(SolveNodeKind.MATRIX)) {
            MatrixSolveNode mn = (MatrixSolveNode) n;
            if (mn.blockEligible()) blockNodes.add(mn);
            else soloNodes.add(mn);
        }

        if (blockNodes.size() == 1) {
            // 单块：不做合成，直接块内求解（等价于旧单网络求解，零额外开销）
            MatrixSolveNode mn = blockNodes.get(0);
            SolveResult r = mn.directSolve();
            results.put(mn.id(), r);
            primary = r;
        } else if (blockNodes.size() > 1) {
            // 多块：块对角合成 → 一次全局求解
            int total = 0;
            for (MatrixSolveNode mn : blockNodes) total += mn.blockSize();
            ComplexMnaBuilder global = new ComplexMnaBuilder(total);
            int offset = 0;
            for (MatrixSolveNode mn : blockNodes) {
                global.offset = offset;
                mn.stampBlock(global, omega);
                offset += mn.blockSize();
            }
            global.offset = 0;
            Complex[] vGlobal = BlockDiagonalSolver.solve(global);
            offset = 0;
            for (MatrixSolveNode mn : blockNodes) {
                mn.applyBlock(vGlobal, offset);
                offset += mn.blockSize();
                results.put(mn.id(), mn.directSolve()); // 节点自行缓存 → 返回自身结果
                if (primary == null) primary = results.get(mn.id());
            }
            mergedCount = blockNodes.size();
        }
        for (MatrixSolveNode mn : soloNodes) {
            SolveResult r = mn.directSolve();
            results.put(mn.id(), r);
            if (primary == null) primary = r;
        }

        // ── 阶段2：直算节点按拓扑层执行 ──────────────────────────────
        // 注意：topoLayers 包含全部节点（含矩阵节点），直算阶段只取 DIRECT
        List<List<SolveNode>> layers = graph.topoLayers();
        SolveNodeContext ctx = new SolveNodeContext(network, simDt, primary, nowNanos, results);
        int directCount = 0;
        for (List<SolveNode> layer : layers) {
            List<DirectSolveNode> directLayer = new ArrayList<>(layer.size());
            for (SolveNode n : layer) {
                if (n instanceof DirectSolveNode dn) directLayer.add(dn);
            }
            if (directLayer.isEmpty()) continue;
            boolean layerAsync = asyncEnabled && directLayer.size() >= parallelThreshold;
            if (layerAsync) {
                List<CompletableFuture<Void>> fs = new ArrayList<>(directLayer.size());
                for (DirectSolveNode dn : directLayer) {
                    fs.add(ThreadDispatchers.submitGeneric(() -> dn.execute(ctx)));
                }
                CompletableFuture.allOf(fs.toArray(new CompletableFuture[0])).join();
                asyncUsed = true;
            } else {
                for (DirectSolveNode dn : directLayer) dn.execute(ctx);
            }
            directCount += directLayer.size();
        }

        return new SolvePipelineResult(results, primary, mergedCount, directCount, asyncUsed);
    }
}
