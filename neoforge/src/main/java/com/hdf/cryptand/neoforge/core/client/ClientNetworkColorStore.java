/**
 * ===== 网络颜色显示（调试，2026-08-21 用户要求） =====
 *
 * 从客户端同步导线（{@link ClientWireGraphStore}）本地构建【连通分量】——
 * 导线端点（块坐标）通过边连通 → 每个分量 = 一个电气网络。每个分量分配
 * 【稳定随机颜色】（按分量内容哈希取色轮，同网络始终同色不闪烁），网络内
 * 所有元件方块（端子所在方块）映射该颜色 → {@link NetworkColorRenderer}
 * 渲染彩色外框，直观显示网络合并/拆分是否正确（同网络同色、分裂网络异色）。
 *
 * 门控：ConfigLoad.DEBUG_NETWORK_COLORS（默认 false）。只做客户端本地计算
 * （基于同步的导线列表），不碰服务端。
 */
package com.hdf.cryptand.neoforge.core.client;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ClientNetworkColorStore {

    /** 色轮（20 个高区分度颜色，ARGB） */
    private static final int[] PALETTE = {
            0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF8E24AA,
            0xFF00ACC1, 0xFFFB8C00, 0xFF3949AB, 0xFFC0CA33, 0xFF6D4C41,
            0xFFD81B60, 0xFF00897B, 0xFF5E35B1, 0xFFF4511E, 0xFF039BE5,
            0xFF7CB342, 0xFFF06292, 0xFF546E7A, 0xFF8D6E63, 0xFF26A69A
    };

    /** 方块 → 网络颜色（ARGB）。空 = 未开启/无网络。 */
    private static volatile Map<BlockPos, Integer> colors = Map.of();
    /** 服务端同步的网络颜色（含孤立设备；服务端自管图分量分配，2026-08-21） */
    private static volatile Map<BlockPos, Integer> serviceColors = Map.of();
    /** ClientWireGraphStore 版本号（变化才重建，零开销） */
    private static volatile int lastVersion = -1;

    private ClientNetworkColorStore() {
    }

    /** 当前方块 → 颜色映射（渲染层读）。服务端数据优先（含孤立设备），
     *  本地导线分量兜底。 */
    public static Map<BlockPos, Integer> colors() {
        Map<BlockPos, Integer> s = serviceColors;
        return s.isEmpty() ? colors : s;
    }

    /** 服务端同步网络颜色（NetworkColorPayload.handle，客户端主线程） */
    public static void setServiceColors(Map<BlockPos, Integer> m) {
        serviceColors = m == null ? Map.of() : m;
    }

    /** 清空全部（世界退出/重进时调用，2026-08-22 修复"新世界残留旧网络颜色"）：
     *  服务端同步色（serviceColors）+ 本地重建色（colors）+ 版本号全部重置——
     *  否则返回主菜单/创建新世界后旧颜色包残留，NetworkColorRenderer 继续渲染
     *  旧网络外框。 */
    public static void clear() {
        serviceColors = Map.of();
        colors = Map.of();
        lastVersion = -1;
    }

    /**
     * 重建（客户端导线变化时）：连通分量 → 稳定颜色。零成本时跳过（版本未变）。
     * 调用方（渲染层每帧）可无条件调用；内部按 ClientWireGraphStore.version() 去重。
     */
    public static void refresh() {
        try {
            int v = ClientWireGraphStore.version();
            if (v == lastVersion) return;
            lastVersion = v;
            List<ClientWireGraphStore.ClientWire> wires = ClientWireGraphStore.wires();
            if (wires.isEmpty()) {
                colors = Map.of();
                return;
            }
            // DSU：导线端点（块坐标）通过边连通 → 分量
            Map<BlockPos, BlockPos> parent = new HashMap<>();
            for (ClientWireGraphStore.ClientWire w : wires) {
                BlockPos pa = new BlockPos(w.ax(), w.ay(), w.az());
                BlockPos pb = new BlockPos(w.bx(), w.by(), w.bz());
                parent.putIfAbsent(pa, pa);
                parent.putIfAbsent(pb, pb);
            }
            for (ClientWireGraphStore.ClientWire w : wires) {
                BlockPos pa = new BlockPos(w.ax(), w.ay(), w.az());
                BlockPos pb = new BlockPos(w.bx(), w.by(), w.bz());
                union(parent, pa, pb);
            }
            // 分量聚合
            Map<BlockPos, List<BlockPos>> comps = new HashMap<>();
            for (BlockPos p : parent.keySet()) {
                BlockPos root = find(parent, p);
                comps.computeIfAbsent(root, k -> new ArrayList<>()).add(p);
            }
            // 每分量稳定颜色（按内容哈希）→ 方块映射
            Map<BlockPos, Integer> out = new HashMap<>();
            for (List<BlockPos> comp : comps.values()) {
                if (comp.size() < 2) continue; // 孤立点不成网（无导线），不标色
                List<BlockPos> sorted = new ArrayList<>(comp);
                sorted.sort(Comparator.comparingLong(BlockPos::asLong));
                long h = 0;
                for (BlockPos p : sorted) h = h * 31 + p.asLong();
                int color = PALETTE[Math.floorMod(h, PALETTE.length)];
                for (BlockPos p : comp) out.put(p, color);
            }
            colors = out;
        } catch (Throwable ignored) {
        }
    }

    private static BlockPos find(Map<BlockPos, BlockPos> parent, BlockPos p) {
        BlockPos root = p;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        // 路径压缩
        BlockPos cur = p;
        while (!cur.equals(root)) {
            BlockPos next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<BlockPos, BlockPos> parent, BlockPos a, BlockPos b) {
        BlockPos ra = find(parent, a);
        BlockPos rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }
}
