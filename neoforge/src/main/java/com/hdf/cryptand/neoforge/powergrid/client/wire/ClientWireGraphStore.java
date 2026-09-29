/**
 * ===== 客户端自管图缓存（2026-08-13） =====
 *
 * 服务端每 tick 同步自管 WireGraph（WireGraphSyncPayload）→ 本缓存。
 * 客户端渲染层（WireRenderManager）从本缓存读取导线（端点 + 颜色）生成
 * Flywheel 效果——渲染完全由【转换后的自管内容】驱动，不依赖原版导线实体
 * （服务端已删除实体，MC 同步也会移除客户端实体）。
 *
 * 线程：客户端主线程（payload handle enqueueWork + WireRenderManager.tick）。
 * 数据：不可变 List 快照（整表替换，无并发问题）。
 */

package com.hdf.cryptand.neoforge.powergrid.client.wire;

import com.hdf.cryptand.neoforge.cee.CeePoseUtil;
import com.hdf.cryptand.neoforge.cee.CeeTerminalSupport;
import com.hdf.cryptand.neoforge.powergrid.net.WireGraphSyncPayload;
import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class ClientWireGraphStore {

    /** 客户端渲染用导线。
     *  <p>端点【身份】（ax..bTerm）= 图边端点（块坐标+端子索引），渲染 diff 的稳定键
     *  （不能用解析后的位置当键——位置会随客户端重解析/回退变化）；
     *  <p>p1/p2 = 【客户端自算】精确端子位置（原版 IElectric.getTerminalPos，CEE 语义）；
     *  <p>fallbackA/B = 服务端同步的精确位置（失败回退块中心），客户端区块未加载时暂用；
     *  <p>color/sag/texture = 渲染参数（SaggingWireRegistry 解析）。 */
    public record ClientWire(
            int ax, int ay, int az, int aTerm,
            int bx, int by, int bz, int bTerm,
            Vec3 p1, Vec3 p2,
            Vec3 fallbackA, Vec3 fallbackB,
            int color, float sag, String texture) {
    }

    private static volatile List<ClientWire> wires = List.of();
    private static volatile long lastUpdateMs;
    /** 变更版本号：每次 update +1，渲染层据此【变更驱动】立即处理（无节流延迟） */
    private static volatile int version = 0;

    private ClientWireGraphStore() {
    }
    /**
     * 重置（世界退出/重进时调用，清理旧世界残留，防止新世界版本号巧合等于
     * 旧版号导致渲染跳过——"导线偶尔渲染不出来"根因之一）。
     */
    public static void reset() {
        wires = List.of();
        version = 0;
        lastUpdateMs = 0;
    }
    /** 收到服务端同步包 → 整表替换（版本 +1，通知渲染层）。
     *  2026-08-14 渲染参数引用化：包只带 rendererId + colorOverride，
     *  此处查 SaggingWireRegistry 解析最终 color/sag/texture（未注册 → 默认）。
     *  <p>2026-08-17 端点位置【客户端自算】（CEE 语义）：包只携带端点身份
     *  （块坐标+端子索引）+ 服务端精确位置（回退）。本方法立即用客户端自身
     *  Level 通过 IElectric.getTerminalPos 重算精确位置——服务端同步快照是
     *  一次性冻结的（同步时区块未加载/getAt null/方块后被旋转替换 → 永久错），
     *  客户端自算永远与客户端看到的方块状态一致。 */
    public static void update(List<WireGraphSyncPayload.WireEdgeData> edges) {
        if (edges == null) return;
        ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        java.util.ArrayList<ClientWire> out = new java.util.ArrayList<>(edges.size());
        for (WireGraphSyncPayload.WireEdgeData e : edges) {
            try {
                // 服务端同步的【端子精确位置】（原版 getTerminalPos）；
                // 精确位置缺失（旧包）时回退块中心 → 客户端区块未加载时的暂用回退。
                Vec3 fa = new Vec3(e.axF(), e.ayF(), e.azF());
                Vec3 fb = new Vec3(e.bxF(), e.byF(), e.bzF());
                if (fa.lengthSqr() == 0) fa = new Vec3(e.ax() + 0.5, e.ay() + 0.5, e.az() + 0.5);
                if (fb.lengthSqr() == 0) fb = new Vec3(e.bx() + 0.5, e.by() + 0.5, e.bz() + 0.5);
                // 客户端自算精确端子位置（IElectric.getTerminalPos = 原版 getExactPosition）；
                // 失败回退服务端位置（加载后 refreshPositions 自动修正）。
                Vec3 p1 = resolveTerminal(level, e.ax(), e.ay(), e.az(), e.aTerm(), fa);
                Vec3 p2 = resolveTerminal(level, e.bx(), e.by(), e.bz(), e.bTerm(), fb);
                String rid = (e.rendererId() == null || e.rendererId().isEmpty())
                        ? null : e.rendererId();
                out.add(new ClientWire(
                        e.ax(), e.ay(), e.az(), e.aTerm(),
                        e.bx(), e.by(), e.bz(), e.bTerm(),
                        p1, p2, fa, fb,
                        SaggingWireRegistry
                                .resolveColor(rid, e.colorOverride()),
                        SaggingWireRegistry
                                .resolveSag(rid),
                        SaggingWireRegistry
                                .resolveTexture(rid)));
            } catch (Throwable ignored) {
                // 单条边解析失败 → 跳过该边（不中断整批，防导线“偶尔渲染不出来”）
            }
        }
        wires = List.copyOf(out);
        version++;
        lastUpdateMs = System.currentTimeMillis();
    }

    /** 周期性重解析全部导线端点位置（CEE 语义：客户端每帧从自身 Level 取位置）。
     *  <p>修复：方块被旋转/替换/多方块组装、或同步时区块未加载（服务端回退块中心）
     *  之后，导线端点应自动跟随方块端子；服务端同步快照是冻结的不会更新，必须
     *  客户端重算。有位置变化才重建列表并 version++（无变化零开销）。
     *  @return 是否有位置变化 */
    public static boolean refreshPositions(ClientLevel level) {
        try {
            List<ClientWire> current = wires;
            if (current.isEmpty()) return false;
            java.util.ArrayList<ClientWire> out = new java.util.ArrayList<>(current.size());
            boolean changed = false;
            for (ClientWire w : current) {
                Vec3 p1 = resolveTerminal(level, w.ax(), w.ay(), w.az(), w.aTerm(), w.fallbackA());
                Vec3 p2 = resolveTerminal(level, w.bx(), w.by(), w.bz(), w.bTerm(), w.fallbackB());
                if (!changed
                        && (p1.distanceToSqr(w.p1()) > 1e-8 || p2.distanceToSqr(w.p2()) > 1e-8)) {
                    changed = true;
                }
                out.add(new ClientWire(
                        w.ax(), w.ay(), w.az(), w.aTerm(),
                        w.bx(), w.by(), w.bz(), w.bTerm(),
                        p1, p2, w.fallbackA(), w.fallbackB(),
                        w.color(), w.sag(), w.texture()));
            }
            if (changed) {
                wires = List.copyOf(out);
                version++;
                lastUpdateMs = System.currentTimeMillis();
            }
            return changed;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 客户端自算端子精确世界坐标：原版 IElectric.getTerminalPos（= 方块坐标 + 端子
     *  offset，getAt → BE/方块状态，纯客户端安全）。
     *  <p>区块未加载 → 暂用回退位置（不强制加载区块，加载后由 refreshPositions 修正）；
     *  J 点（term<0）无端子索引 → 直接用服务端位置/块中心。 */
    private static Vec3 resolveTerminal(ClientLevel level, int x, int y, int z, int term, Vec3 fallback) {
        if (term < 0 || level == null) return fallback;
        try {
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.isLoaded(pos)
                    && !CeePoseUtil
                            .contained(level, pos)) return fallback;
            // 2026-08-23 CEE 端子精确（客户端自算：节点 id 映射 getNodePosition）
            try {
                if (level.getBlockState(pos).getBlock() != null
                        && CeeTerminalSupport
                                .isCeeExternalBlock(level.getBlockState(pos).getBlock())) {
                    Vec3 cee = CeeTerminalSupport
                            .terminalPosWorld(level, pos, term);
                    if (cee != null) return cee;
                }
            } catch (Throwable ignored) {
            }
            Vec3 exact = org.patryk3211.powergrid.electricity.base.IElectric
                    .getTerminalPos(level, pos, term);
            if (exact != null) {
                // ⚠ 2026-08-23 物理化（Sable 亚层 plot 坐标）：PowerGrid 的
                // getTerminalPos 返回亚层局部世界坐标（2000 万格偏移），必须
                // 经 CeePoseUtil.toWorld（getContaining + logicalPose 投影）
                // 变换为真实世界坐标，否则导线画在亚层坐标 → 客户端"消失"。
                return CeePoseUtil
                        .toWorld(level, pos, exact);
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 当前变更版本（渲染层增量检测用） */
    public static int version() {
        return version;
    }

    /** 当前全部渲染用导线（不可变快照） */
    public static List<ClientWire> wires() {
        return wires;
    }

    /** 最近一次同步时间（毫秒）；0 = 从未收到 */
    public static long lastUpdateMs() {
        return lastUpdateMs;
    }
}
