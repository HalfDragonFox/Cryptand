package com.hdf.cryptand.neoforge.powergrid.network.wire;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.engine.AdapterDiag;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLine;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;

import java.util.*;

/**
 * ===== 连续导线段：分段、key 空间、段注册表 =====
 *
 * <p>从 {@code PhasorNetworkBuilder} 拆出。本类只负责一件事——
 * <b>把一堆 PowerGrid 导线切成「连续段」，并给每段一个可查询的身份</b>。
 *
 * <p>分段规则（源码语义，勿改）：
 * <ul>
 *   <li>段内中间点（度数 2 且非接线端子）→ 等效串联省略，不设引擎节点</li>
 *   <li>段两端（度数≠2、接线端子、设备端子）→ 进引擎，加一个【段电阻】元件</li>
 *   <li>接线端子（CordJunction）是汇流点 → 其上多根导线共享节点（等电位）</li>
 * </ul>
 *
 * <p>key 空间是自管图与引擎之间的"地址"：
 * {@code B<pos>#<term>} = 方块端子，{@code J<pos>} = 接线端子。
 * 段的 {@code pathKey} 同时是温度模型（{@code WireThermalStore}）的键。
 *
 * <p>{@link #SEGMENT_EDGES} 是"段 → 段内导线"的注册表，烧毁/剪线时按段整段移除。
 */
public final class WireSegments {

    private WireSegments() {}

    // 导线视作【电阻元件】：连续段导线电阻相加、共用同一套温度模型，不用单独
    // 收集每根导线。分叉等不连续处（设备端子/接线端子/悬空端）视作不同段。
    // 段 = 设备端子/分叉点/接线端子之间无分叉的导线链：
    //   - 段内中间点（度数 2 且非接线端子）→ 等效串联省略（不设引擎节点）
    //   - 段两端（度数≠2 或接线端子）→ 进引擎，加一个【段电阻】元件
    // 接线端子（CordJunction）是汇流点 → 其上多根导线的段端点 union（等电位）。
    /** 连续导线段的电阻元件信息。 */
    public static final class Segment {
        public String keyA, keyB;                 // 段两端端点 key
        public OwnedFloatingNode nodeA, nodeB;    // 段两端端点节点
        public double resistance;                 // Σ 段内导线电阻
        public String pathKey;                    // 段路径签名（温度模型 key）
        public Segment() {}
    }

    /** 端点 → key（BlockWireEndpoint: pos#term；Junction: J+pos）。 */
    public static String wireEndpointKey(Level level, IWireEndpoint ep) {
        if (ep instanceof BlockWireEndpoint bep) return "B" + bep.getPos() + "#" + bep.getTerminal();
        if (ep instanceof JunctionWireEndpoint jep) {
            try {
                return "J" + BlockPos.containing(jep.getExactPosition(level));
            } catch (Throwable ignored) {
                return "J" + System.identityHashCode(jep);
            }
        }
        return null;
    }

    /** 导线节点 key：优先【稳定节点】endpoint（PowerGrid 权威，endpoint 固定）；
     *  稳定节点不可得时退回导线 endpoint。 */
    static String wireNodeKeyOf(Level level, IElectricNode nd, IWireEndpoint ep) {
        try {
            if (nd instanceof OwnedFloatingNode ofn && ofn.endpoint != null) {
                String k = wireEndpointKey(level, ofn.endpoint);
                if (k != null) return k;
            }
        } catch (Throwable ignored) {
        }
        return wireEndpointKey(level, ep);
    }

    /** 端点是接线端子（CordJunction 汇流点）→ 段边界（其上多导线各自成段）。 */
    public static boolean isCordJunction(Level level, IWireEndpoint ep) {
        try {
            if (!(ep instanceof BlockWireEndpoint bep)) return false;
            net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(bep.getPos());
            return com.hdf.cryptand.neoforge.powergrid.device.Assemblers
                .classChainHas(be, "CordJunctionBlockEntity");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 把导线集合分段（连续段电阻相加）。
     * @return 段列表；段端点（度数≠2 或接线端子）已解析到 allNetNodes 中的权威节点。
     */
    public static java.util.List<Segment> buildWireSegments(Level level,
            java.util.Collection<TransmissionLine> allWires,
            java.util.Set<OwnedFloatingNode> allNetNodes,
            java.util.Set<String> boundaryKeys) {
        java.util.List<Segment> out = new java.util.ArrayList<>();
        if (allWires == null || allWires.isEmpty()) return out;
        int dbgNullKey = 0, dbgInTl = 0, dbgSkipOuter = 0, dbgSkipResolve = 0;
        // 端点 key → 节点 索引（2026-08-12 性能优化）：一次遍历 allNetNodes 建索引，
        // 段端点 resolve 从 O(段数×节点数) 降到 O(段数)。
        java.util.Map<String, OwnedFloatingNode> keyToNode = new java.util.HashMap<>();
        for (OwnedFloatingNode n : allNetNodes) {
            try {
                String k = wireEndpointKey(level, n.endpoint);
                if (k != null) keyToNode.putIfAbsent(k, n);
            } catch (Throwable ignored) {
            }
        }
        try {
            // 端点 key → 导线列表（度数）+ key → endpoint
            java.util.Map<String, java.util.List<TransmissionLine>> byEp =
                    new java.util.HashMap<>();
            java.util.Map<TransmissionLine, String[]> tlKeys = new java.util.HashMap<>();
            java.util.Map<String, IWireEndpoint> keyToEp = new java.util.HashMap<>();
            for (TransmissionLine tl : allWires) {
                // 优先用【稳定节点】endpoint 的 key（PowerGrid 权威节点，endpoint
                // 固定）——比直接读 getEndpoint1/2（可能 MutableBlockPos 漂移）可靠。
                String k1 = wireNodeKeyOf(level, tl.getNode1(), tl.getEndpoint1());
                String k2 = wireNodeKeyOf(level, tl.getNode2(), tl.getEndpoint2());
                if (k1 == null || k2 == null || k1.equals(k2)) {
                    dbgNullKey++;
                    long now0 = System.currentTimeMillis();
                    if (AdapterDiag.gate("builder.seg", 2000)) {
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[SegSkip] k1={} k2={} ep1={} ep2={} n1ep={} n2ep={}",
                                k1, k2, epDbg(tl.getEndpoint1()), epDbg(tl.getEndpoint2()),
                                ndDbg(tl.getNode1()), ndDbg(tl.getNode2()));
                    }
                    continue;
                }
                dbgInTl++;
                tlKeys.put(tl, new String[]{k1, k2});
                byEp.computeIfAbsent(k1, k -> new java.util.ArrayList<>()).add(tl);
                byEp.computeIfAbsent(k2, k -> new java.util.ArrayList<>()).add(tl);
                keyToEp.putIfAbsent(k1, tl.getEndpoint1());
                keyToEp.putIfAbsent(k2, tl.getEndpoint2());
            }
            // 段边界 = 度数 != 2（1=设备端/悬空，>=3=分叉）+ 测量端点 +
            // 接线端子（CordJunction 汇流/测量点，无论度数都作段边界，保留
            // 节点可测量；多导线在接线端子汇流 → 各自成段，共享接线端子节点）
            java.util.Set<String> allBoundary = new java.util.HashSet<>();
            if (boundaryKeys != null) allBoundary.addAll(boundaryKeys);
            for (java.util.Map.Entry<String, IWireEndpoint> e : keyToEp.entrySet()) {
                if (isCordJunction(level, e.getValue())) allBoundary.add(e.getKey());
                // 所有电气设备端子（含源/变压器/设备）强制作为段边界（2026-08-12）：
                // 设备端子即使度数2（分叉）也必须保留引擎节点——否则被链当中间点
                // 省略 → 源/变压器/设备端子孤立 → 元件连不上 → 烧线。
                if (e.getValue() instanceof BlockWireEndpoint bep) {
                    try {
                        net.minecraft.world.level.block.entity.BlockEntity be =
                                level.getBlockEntity(bep.getPos());
                        if (be instanceof org.patryk3211.powergrid.electricity.base.ElectricBlockEntity) {
                            allBoundary.add(e.getKey());
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            java.util.Set<TransmissionLine> visited = new java.util.HashSet<>();
            for (TransmissionLine start : allWires) {
                if (visited.contains(start) || !tlKeys.containsKey(start)) continue;
                visited.add(start);
                String[] sk = tlKeys.get(start);
                // 左链（沿 sk[0] 外扩）+ 右链（沿 sk[1] 外扩）
                java.util.List<TransmissionLine> left = new java.util.ArrayList<>();
                java.util.List<TransmissionLine> right = new java.util.ArrayList<>();
                expandChain(start, sk[0], byEp, tlKeys, visited, allBoundary, left);
                expandChain(start, sk[1], byEp, tlKeys, visited, allBoundary, right);
                // 完整链 = 左链(逆) + start + 右链
                java.util.List<TransmissionLine> chain = new java.util.ArrayList<>();
                for (int i = left.size() - 1; i >= 0; i--) chain.add(left.get(i));
                chain.add(start);
                chain.addAll(right);
                // 段外端 key
                String keyA = chainOuterKey(chain.get(0), tlKeys, byEp, allBoundary);
                String keyB = chainOuterKey(chain.get(chain.size() - 1), tlKeys, byEp, allBoundary);
                if (keyA == null || keyB == null) {
                    dbgSkipOuter++;
                    // 诊断（节流）：打印被跳过链的端点 key —— 定位 chainOuterKey 失败
                    long now1 = System.currentTimeMillis();
                    if (AdapterDiag.gate("builder.seg", 2000)) {
                        try {
                            StringBuilder cb = new StringBuilder();
                            for (TransmissionLine ctl : chain) {
                                String[] ck = tlKeys.get(ctl);
                                cb.append('[').append(ck[0]).append('|').append(ck[1]).append("] ");
                                if (cb.length() > 400) { cb.append("..."); break; }
                            }
                            CryptandNeoForge.WAF_LOGGER.info(
                                    "[SegSkipOuter] chainN={} keyA={} keyB={} chain=[{}]",
                                    chain.size(), keyA, keyB, cb);
                        } catch (Throwable ignored) {
                        }
                    }
                    continue;
                }
                // 单导线段（chainN==1）：chain.get(0)==chain.get(last) → chainOuterKey
                // 对同一根导线两次返回【同一端点】→ keyA==keyB 误判跳过
                // （[SegSkipOuter] 实锤：chainN=1 keyA==keyB → 每根设备导线都被跳过
                // → 无段电阻 → 烧线）。单导线段两端都是边界（expandChain 停在度数≠2）
                // → keyB 取另一端即可。
                if (keyA.equals(keyB) && chain.size() == 1) {
                    String[] tk0 = tlKeys.get(chain.get(0));
                    if (tk0 != null) {
                        keyB = tk0[0].equals(keyA) ? tk0[1] : tk0[0];
                    }
                }
                if (keyA == null || keyB == null || keyA.equals(keyB)) {
                    dbgSkipOuter++;
                    continue;
                }
                Segment seg = new Segment();
                seg.keyA = keyA;
                seg.keyB = keyB;
                double r = 0;
                for (TransmissionLine tl : chain) {
                    try { r += tl.getResistance(); } catch (Throwable ignored) { }
                }
                seg.resistance = Math.max(r, 0);
                seg.nodeA = keyToNode.get(keyA);
                if (seg.nodeA == null) {
                    seg.nodeA = PhasorNetworkBuilder.collectEndpointNode(level, keyToEp.get(keyA), allNetNodes);
                }
                seg.nodeB = keyToNode.get(keyB);
                if (seg.nodeB == null) {
                    seg.nodeB = PhasorNetworkBuilder.collectEndpointNode(level, keyToEp.get(keyB), allNetNodes);
                }
                if (seg.nodeA == null || seg.nodeB == null) {
                    dbgSkipResolve++;
                    continue;
                }
                // 段路径签名（温度模型 key）：段内所有端点 key【排序】拼接——
                // DFS 起点不同链方向可能反转，排序保证同一段每次 key 稳定
                // （温度模型跨重建持久，key 变了会重置温度）
                java.util.Set<String> uniq = new java.util.LinkedHashSet<>();
                for (TransmissionLine tl : chain) {
                    String[] k = tlKeys.get(tl);
                    uniq.add(k[0]);
                    uniq.add(k[1]);
                }
                java.util.List<String> sorted = new java.util.ArrayList<>(uniq);
                java.util.Collections.sort(sorted);
                StringBuilder sb = new StringBuilder();
                for (String k : sorted) sb.append(k).append(';');
                seg.pathKey = sb.toString();
                out.add(seg);
            }
            // 诊断（节流 ~2s）：分段统计——定位"导线不成段 → 无电阻 → 烧线"
            long segNow = System.currentTimeMillis();
            if (AdapterDiag.gate("builder.seg", 2000)) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[SegStat] allWires={} inTl={} segs={} nullKey={} skipOuter={} skipResolve={}",
                        allWires.size(), dbgInTl, out.size(), dbgNullKey,
                        dbgSkipOuter, dbgSkipResolve);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 从导线 tl 的 curKey 端向外扩展（该端度数==2 继续，否则停）。经过的导线加入 out。 */
    private static void expandChain(TransmissionLine tl, String curKey,
            java.util.Map<String, java.util.List<TransmissionLine>> byEp,
            java.util.Map<TransmissionLine, String[]> tlKeys,
            java.util.Set<TransmissionLine> visited,
            java.util.Set<String> boundaryKeys,
            java.util.List<TransmissionLine> out) {
        try {
            String[] tk = tlKeys.get(tl);
            if (tk == null) return;
            String other = tk[0].equals(curKey) ? tk[1] : tk[0];
            java.util.List<TransmissionLine> atOther =
                    byEp.getOrDefault(other, java.util.Collections.emptyList());
            // 度数≠2（设备端/分叉/悬空）或被测量端点 → 段边界
            if (atOther.size() != 2 || (boundaryKeys != null && boundaryKeys.contains(other))) {
                return;
            }
            TransmissionLine next = null;
            for (TransmissionLine t : atOther) {
                if (t != tl && !visited.contains(t)) { next = t; break; }
            }
            if (next == null) return;
            visited.add(next);
            out.add(next);
            String[] nk = tlKeys.get(next);
            String nextOther = nk[0].equals(other) ? nk[1] : nk[0];
            expandChain(next, other, byEp, tlKeys, visited, boundaryKeys, out);
        } catch (Throwable ignored) {
        }
    }

    /** 链首/链尾导线的边界外端 key：两端中【度数≠2 或边界】的那端。
     *  用 byEp 度数判断（不依赖 chain 内共享——分叉点度数≥3 直接返回，
     *  不会因分叉导线也在 chain 内误判共享而返回 null → 整条链被跳过
     *  （[SegStat] skipOuter=7 实锤：9 根导线只 1 段 → 无电阻 → 烧线）。 */
    private static String chainOuterKey(TransmissionLine tl,
            java.util.Map<TransmissionLine, String[]> tlKeys,
            java.util.Map<String, java.util.List<TransmissionLine>> byEp,
            java.util.Set<String> allBoundary) {
        try {
            String[] tk = tlKeys.get(tl);
            if (tk == null) return null;
            for (String k : tk) {
                java.util.List<TransmissionLine> atK =
                        byEp.getOrDefault(k, java.util.Collections.emptyList());
                if (atK.size() != 2 || (allBoundary != null && allBoundary.contains(k))) {
                    return k;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 端点 → 简写（诊断用） */
    public static String epDbg(IWireEndpoint ep) {
        if (ep == null) return "null";
        if (ep instanceof BlockWireEndpoint bep) return "B(" + bep.getPos() + "#" + bep.getTerminal() + ")";
        if (ep instanceof JunctionWireEndpoint) return "J";
        return ep.getClass().getSimpleName();
    }

    /** 节点 → 简写（诊断用） */
    static String ndDbg(IElectricNode nd) {
        if (nd == null) return "null";
        if (nd instanceof OwnedFloatingNode ofn) return "OFN(" + epDbg(ofn.endpoint) + ")";
        return nd.getClass().getSimpleName();
    }

    // ===== 连续段注册表（2026-08-14 用户要求：连续导线统一段处理/统一烧毁） =====

    /** 段 key → 段内导线（自管构建时注册；烧毁时按段统一移除全部边） */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge>> SEGMENT_EDGES =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 注册段（构建时；重复段覆盖） */
    public static void registerSegment(String key,
            java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge> edges) {
        if (key != null && edges != null && !edges.isEmpty()) {
            SEGMENT_EDGES.put(key, edges);
        }
    }

    /** 段内导线（烧毁/剪线用；无返回 null） */
    public static java.util.List<com.hdf.cryptand.circuitsimulation.netgraph.WireEdge>
            segmentEdges(String key) {
        return key == null ? null : SEGMENT_EDGES.get(key);
    }

    /** 段消失清理（拆线/烧毁后；只保留活跃段 key） */
    public static void retainSegments(java.util.Set<String> activeKeys) {
        try {
            if (activeKeys == null) {
                SEGMENT_EDGES.clear();
                return;
            }
            SEGMENT_EDGES.keySet().removeIf(k -> !activeKeys.contains(k));
        } catch (Throwable ignored) {
        }
    }
}
