package com.hdf.cryptand.circuitsimulation.compute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 求解节点图：一个网络分解出的 SolveNode 集合 + 依赖关系。
 *
 * <p>2026-08-19：{@link #addNode} 时立即校验：
 *   - id 重复 → IllegalArgumentException
 *   - 依赖引用不存在的节点 → IllegalArgumentException
 *   - 成环 → IllegalArgumentException（DAG 校验）
 * 校验通过后 {@link #topoLayers()} 给出按依赖深度分层的执行序
 * （同层节点互相无依赖，可并行）。
 */
public final class SolveGraph {

    private final Map<String, SolveNode> nodes = new LinkedHashMap<>();

    /** 注册节点；自动做 DAG 校验。 */
    public SolveGraph addNode(SolveNode node) {
        String id = node.id();
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("节点 id 不能为空");
        if (nodes.containsKey(id)) throw new IllegalArgumentException("重复节点 id: " + id);
        nodes.put(id, node);
        validateDag();
        return this;
    }

    /** 是否为空（没有节点）。 */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** 节点总数。 */
    public int nodeCount() {
        return nodes.size();
    }

    /** 按 id 取节点。 */
    public SolveNode node(String id) {
        return nodes.get(id);
    }

    /** 全部节点（按注册序）。 */
    public List<SolveNode> nodes() {
        return new ArrayList<>(nodes.values());
    }

    /** 指定形态的节点子集。 */
    public List<SolveNode> nodesOfKind(SolveNodeKind kind) {
        List<SolveNode> out = new ArrayList<>();
        for (SolveNode n : nodes.values()) if (n.kind() == kind) out.add(n);
        return out;
    }

    /**
     * 拓扑分层：第 0 层 = 无依赖节点；第 k 层 = 依赖最大深度为 k 的节点。
     * 同层无依赖关系，可并行执行。
     */
    public List<List<SolveNode>> topoLayers() {
        Map<String, Integer> depth = new LinkedHashMap<>();
        List<List<SolveNode>> layers = new ArrayList<>();
        // Kahn 式：反复收集"依赖全已分层"的节点
        Set<String> scheduled = new LinkedHashSet<>();
        List<SolveNode> remaining = new ArrayList<>(nodes.values());
        while (!remaining.isEmpty()) {
            List<SolveNode> layer = new ArrayList<>();
            List<SolveNode> next = new ArrayList<>();
            for (SolveNode n : remaining) {
                boolean ready = true;
                for (String dep : n.dependsOn()) {
                    if (!scheduled.contains(dep)) { ready = false; break; }
                }
                if (ready) layer.add(n);
                else next.add(n);
            }
            if (layer.isEmpty()) {
                // 依赖已全部注册（构造时校验过 DAG），理论不可达；防御性兜底
                throw new IllegalStateException("节点图存在环或悬空依赖: " + next);
            }
            for (SolveNode n : layer) {
                scheduled.add(n.id());
                depth.put(n.id(), layers.size());
            }
            layers.add(layer);
            remaining = next;
        }
        return layers;
    }

    private void validateDag() {
        for (SolveNode n : nodes.values()) {
            for (String dep : n.dependsOn()) {
                if (dep.equals(n.id())) throw new IllegalArgumentException("节点不能依赖自身: " + n.id());
                if (!nodes.containsKey(dep)) {
                    throw new IllegalArgumentException("节点 " + n.id() + " 依赖不存在的节点: " + dep);
                }
            }
        }
        // 环检测：DFS 三色标记
        Map<String, Integer> color = new LinkedHashMap<>();
        for (String id : nodes.keySet()) color.put(id, 0);
        for (String id : nodes.keySet()) {
            if (color.get(id) == 0) dfsCycle(id, color);
        }
    }

    private void dfsCycle(String id, Map<String, Integer> color) {
        color.put(id, 1);
        for (String dep : nodes.get(id).dependsOn()) {
            int c = color.get(dep);
            if (c == 1) throw new IllegalArgumentException("节点图存在环: " + id + " -> " + dep);
            if (c == 0) dfsCycle(dep, color);
        }
        color.put(id, 2);
    }
}
