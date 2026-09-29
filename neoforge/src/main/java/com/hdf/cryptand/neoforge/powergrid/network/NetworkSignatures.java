package com.hdf.cryptand.neoforge.powergrid.network;

import com.hdf.cryptand.neoforge.powergrid.engine.PhasorPipeline;
import com.hdf.cryptand.neoforge.powergrid.mixin.customcore.ElectricalNetworkAccessor;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;

import java.util.Set;

/**
 * ===== 网络拓扑签名（缓存失效的判据）=====
 *
 * <p>从 {@code PhasorNetworkBuilder} 拆出：本类只回答一个问题——
 * <b>「这张 PowerGrid 网络的拓扑相对上次求解是否变了？」</b>
 * 三个签名分别对应三种"变化却不易察觉"的场景：
 *
 * <ul>
 *   <li>{@link #wiresSignature} —— 网络导线集合变化（addWire/removeWire）：只按
 *       节点数判断缓存会命中旧 context，导致新导线两端不合并 → 一端 0V 一端
 *       有电压 → 虚假大电流烧线。</li>
 *   <li>{@link #nodeSignature} —— 节点集合变化（新设备/新端子接入）：网络对象
 *       被替换/分裂时按对象键的失效标记会漏，节点签名兜底强制重建。</li>
 *   <li>{@link #worldWiresSignature} —— 世界全量导线表变化：导线"创建即入表"，
 *       在尚未 addWire 的窗口里兜住拓扑变化。</li>
 * </ul>
 *
 * <p>三者都是"结构性 hash"，只用于相等性比较，不做物理含义解读。
 */
public final class NetworkSignatures {

    private NetworkSignatures() {}

    /** 反射读取 ElectricalNetwork.wires（protected Set<AbstractElectricWire>）。
     *  首选 mixin accessor（cryptand$wires）——Java 21 强封装下 setAccessible 反射
     *  可能抛 InaccessibleObjectException 恒空 → 网络导线合并失效 → 导线两端未合并
     *  → 孤立端 0V → 虚假大电流烧线。accessor 由 mixin 直接注入字段访问，可靠。 */
    @SuppressWarnings("unchecked")
    public static Set<AbstractElectricWire> getNetworkWires(ElectricalNetwork net) {
        try {
            if (net instanceof ElectricalNetworkAccessor acc) {
                Set<AbstractElectricWire> ws = acc.cryptand$wires();
                if (ws != null) return ws;
            }
        } catch (Throwable ignored) {
        }
        try {
            java.lang.reflect.Field f = ElectricalNetwork.class.getDeclaredField("wires");
            f.setAccessible(true);
            Object o = f.get(net);
            if (o instanceof Set) {
                return (Set<AbstractElectricWire>) o;
            }
        } catch (Throwable ignored) {
        }
        return java.util.Collections.emptySet();
    }

    /** 网络 wires 集合签名：wires 内容变化（addWire/removeWire/导线接入移除）→
     *  签名变化。用于 writeback 缓存失效：新导线接入【不改变节点数】，若缓存
     *  只按节点数判断 → 命中旧 context（不含新导线合并）→ 新导线两端不合并
     *  → 一端 0V 一端有电压 → 虚假大电流烧线（[WireBurn] 实锤）。
     *  wires 签名变化 → 强制重解 → context 重建 → 合并最新导线 → 等电位。 */
    public static int wiresSignature(ElectricalNetwork net) {
        int sig = 0;
        try {
            for (AbstractElectricWire w : getNetworkWires(net)) {
                sig = sig * 31 + System.identityHashCode(w);
            }
        } catch (Throwable ignored) {
        }
        return sig;
    }

    /** 网络【节点集合】签名（2026-08-13 无感重建修复）：所有节点的 endpoint
     *  位置/端子号聚合 hash。节点集合变化 = 有新设备/新端子接入 = 该网络必须
     *  重建（设备悬空端子收集/新设备建模）。配合缓存校验：
     *  <p>网络级失效（netVersions 按对象键）在 PowerGrid 网络【对象替换/分裂】
     *  时可能漏标记（markNetworkChanged 标记旧对象、查询/缓存用新对象）→ 旧
     *  ctx 被命中（不含新负载端子）→ 负载端 0V 不工作/烧线（[WireBurn] 实锤：
     *  Connector (3,0,12)#0 网络节点存在但 inCtx=false）。节点签名兜底：节点
     *  集合变化 → 必 miss → 重建。其他网络节点集合不变 → 缓存命中（无感保持）。 */
    public static int nodeSignature(ElectricalNetwork net) {
        int sig = 0;
        try {
            for (INode in : net.getNodes()) {
                if (in instanceof OwnedFloatingNode ofn) {
                    if (ofn.endpoint instanceof BlockWireEndpoint bep) {
                        sig = sig * 31 + bep.getPos().hashCode() * 7 + bep.getTerminal();
                    } else if (ofn.endpoint != null) {
                        sig = sig * 31 + System.identityHashCode(ofn.endpoint);
                    } else {
                        sig = sig * 31 + System.identityHashCode(ofn);
                    }
                } else {
                    sig = sig * 31 + System.identityHashCode(in);
                }
            }
        } catch (Throwable ignored) {
        }
        return sig;
    }

    /** 世界全量导线表签名（transmissionLines 全量：导线【创建即加入】，不依赖
     *  addWire/deferredRewire 延迟）。任何新导线（含变压器副边接线）接入 → 签名
     *  必变 → writeback 缓存失效 → 强制重建 ctx。这是烧线窗口的治本防线：
     *  新导线接入后【尚未 addWire】时网络 wires 签名不变、topoVersion 不变，
     *  但全量导线表已含它 → 用此签名兜住，杜绝"新导线一端 0V 一端有电压"。 */
    public static int worldWiresSignature() {
        int sig = 0;
        try {
            for (org.patryk3211.powergrid.electricity.sim.special.TransmissionLine tl
                    : PhasorPipeline.WORLD_WIRES) {
                sig = sig * 31 + System.identityHashCode(tl);
            }
        } catch (Throwable ignored) {
        }
        return sig;
    }
}
