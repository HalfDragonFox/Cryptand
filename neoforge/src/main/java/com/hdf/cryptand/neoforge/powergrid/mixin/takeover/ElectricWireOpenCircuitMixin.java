/**
 * ===== 悬空导线电流保护 =====
 *
 * PowerGrid 导线电流 = potentialDifference × conductance（两端节点电压差 × 电导）。
 * 时域禁用后节点电压由 PhasorPipeline 回写；若导线一端连【孤立节点】
 * （空接线端子等，未并入任何 ElectricalNetwork → getNetwork()==null），
 * 该端电压保持 0 → 导线两端巨大电压差 × 大电导 = 巨大电流 → 烧线。
 *
 * 物理：开路导线（一端悬空）应无电流。本 mixin 在 current() 入口检测：
 * 任一端节点是孤立节点（OwnedFloatingNode 且无网络）→ 返回 0。
 * 仅判定 OwnedFloatingNode（方块端子），内部节点/变压器耦合不受影响。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorPipeline;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.node.IElectricNode;
import org.patryk3211.powergrid.electricity.sim.node.INode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.transformer.TransformerBlockEntity;
import org.patryk3211.powergrid.electricity.transformer.TransformerCoilParameters;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractElectricWire.class, remap = false)
public abstract class ElectricWireOpenCircuitMixin {

    @Shadow private IElectricNode node1;
    @Shadow private IElectricNode node2;
    @Shadow public abstract double conductance();
    @Shadow public abstract double potentialDifference();
    @Shadow public abstract ElectricalNetwork getNetwork();

    /** 导线任一端是孤立节点（无 ElectricalNetwork）→ 开路，无电流 */
    @Inject(method = "current", at = @At("HEAD"), cancellable = true)
    private void cryptand$openCircuitCurrent(CallbackInfoReturnable<Double> cir) {
        try {
            if (isOrphan(node1) || isOrphan(node2)) {
                cir.setReturnValue(0.0);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 大电流诊断（节流）：非孤立但电压差×电导巨大 → 打印两端节点详情，
     *  定位"烧线"根因（哪端电压=0/未回写/未在网络）。 */
    @Inject(method = "current", at = @At("HEAD"))
    private void cryptand$diagBurn(CallbackInfoReturnable<Double> cir) {
        try {
            if (isOrphan(node1) || isOrphan(node2)) return;
            double diff = potentialDifference();
            double i = Math.abs(diff * conductance());
            if (i > 10.0) {
                long now = System.currentTimeMillis();
                if (now - dbgBurnLastLog >= 1000) {
                    dbgBurnLastLog = now;
                    try {
                        String cls = "?";
                        try { cls = this.getClass().getSimpleName(); } catch (Throwable ignored2) { }
                        CryptandNeoForge.WAF_LOGGER.info(
                                "[WireBurn] {} i={}A diff={}V | {} | {}",
                                cls, String.format("%.1f", i), String.format("%.1f", diff),
                                nodeInfo(node1), nodeInfo(node2));
                        cryptand$dumpNetwork();
                        cryptand$dumpTransformer();
                        cryptand$dumpEndpoints();
                        cryptand$dumpWireEndpoints();
                        cryptand$dumpCtx();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** [WireBurn] 附加诊断：烧线导线两端节点在最近一次 writeback 的 nodeToEngine
     *  映射 id + 求解电压 —— 实证定位“合并成功但电压不同”的环节：
     *   - inCtx=false → 节点不在求解集 → 未回写 → 保持 0V → 烧线根因
     *   - id 不同 → 合并3.5 union 未在步骤4 生效（实例/位置解析偏差）
     *   - id 相同但 v 不同 → 求解/回写问题 */
    @Unique
    private void cryptand$dumpCtx() {
        try {
            var ctx = PhasorPipeline.LAST_CTX;
            var res = PhasorPipeline.LAST_RES;
            if (ctx == null || res == null) {
                CryptandNeoForge.WAF_LOGGER.info(
                        "[WireBurnCtx] no-last-ctx");
                return;
            }
            for (IElectricNode nd : new IElectricNode[]{node1, node2}) {
                if (!(nd instanceof OwnedFloatingNode ofn)) continue;
                Integer id = ctx.nodeToEngine.get(ofn);
                String v = "?";
                if (id != null && res.complex != null && id >= 0 && id < res.complex.length) {
                    v = String.format("%.2f", res.complex[id].abs() / Math.sqrt(2.0));
                }
                String pos = ofn.endpoint instanceof BlockWireEndpoint bep
                        ? bep.getPos() + "#" + bep.getTerminal() : "?";
                CryptandNeoForge.WAF_LOGGER.info(
                        "[WireBurnCtx] {} inCtx={} id={} v={} ctxSize={} resN={}",
                        pos, id != null, id, v, ctx.nodeToEngine.size(),
                        res.complex == null ? -1 : res.complex.length);
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile long dbgBurnLastLog;

    @Unique
    private static String nodeInfo(IElectricNode node) {
        try {
            if (node == null) return "null";
            double v = node.getVoltage();
            if (node instanceof OwnedFloatingNode ofn) {
                boolean inNet = ofn.getNetwork() != null;
                String ep = ofn.endpoint == null ? "noEP" : ofn.endpoint.getClass().getSimpleName();
                String pos = "-";
                try {
                    if (ofn.endpoint instanceof BlockWireEndpoint bep) pos = bep.getPos() + "#" + bep.getTerminal();
                    else if (ofn.endpoint instanceof JunctionWireEndpoint) pos = "junction";
                } catch (Throwable ignored) {
                }
                return String.format("v=%.2f %s net=%s ep=%s@%s", v,
                        node.getClass().getSimpleName(), inNet, ep, pos);
            }
            return String.format("v=%.2f %s", v, node.getClass().getSimpleName());
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** [WireBurn] 附加诊断：打印烧线导线两端端点的方块类 + connections 状态 ——
     *  定位"端点为何 0V"：方块类（接线端子/设备/变压器）、beh 是否 null、
     *  connections 里该端点的导线数、endpointHasWireConnection 判定结果。 */
    @Unique
    private void cryptand$dumpEndpoints() {
        try {
            net.minecraft.world.level.Level lv =
                    PhasorPipeline.CRYPTAND_LAST_LEVEL;
            if (lv == null) return;
            for (IElectricNode nd : new IElectricNode[]{node1, node2}) {
                if (!(nd instanceof OwnedFloatingNode ofn)) continue;
                if (!(ofn.endpoint instanceof BlockWireEndpoint bep)) continue;
                StringBuilder sb = new StringBuilder();
                sb.append("ep=").append(bep.getPos()).append('#').append(bep.getTerminal());
                net.minecraft.core.BlockPos bp0 = bep.getPos();
                net.minecraft.world.level.block.entity.BlockEntity be = bp0 == null ? null
                        : lv.getBlockEntity(bp0);
                sb.append(" block=").append(be == null ? "null" : be.getClass().getSimpleName());
                try {
                    org.patryk3211.powergrid.electricity.base.ElectricBehaviour beh =
                            bep.getElectricBehaviour(lv);
                    if (beh == null) {
                        sb.append(" beh=null");
                    } else {
                        java.util.Map<?, ?> conns = beh.getConnections();
                        Object set = conns == null ? null : conns.get(bep);
                        sb.append(" conns=").append(set instanceof java.util.Set<?> s ? s.size() : "none");
                        sb.append(" hasWire=").append(
                                PhasorNetworkBuilder
                                        .endpointHasWireConnection(lv, bep));
                    }
                } catch (Throwable ignored2) {
                    sb.append(" beh=EX");
                }
                CryptandNeoForge.WAF_LOGGER.info("[WireBurnEp] {}", sb);
            }
        } catch (Throwable ignored) {
        }
    }

    /** [WireBurn] 附加诊断：打印烧线导线的 endpoint1/2 与 node1/2 端点 ——
     *  确认合并用的位置是否一致：node.endpoint（resolveToAll 用）vs 导线端点。
     *  若 endpoint 是偏移端点/代理端点（pos 与 node 不同）→ 位置匹配失败。 */
    @Unique
    private void cryptand$dumpWireEndpoints() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("cls=").append(this.getClass().getSimpleName());
            Object self = (Object) this;
            if (self instanceof org.patryk3211.powergrid.electricity.sim.special.TransmissionLine tl) {
                try {
                    sb.append(" e1=").append(epStr(tl.getEndpoint1()));
                    sb.append(" e2=").append(epStr(tl.getEndpoint2()));
                } catch (Throwable ignored2) {
                    sb.append(" epEX");
                }
            }
            sb.append(" n1=").append(ndStr(node1));
            sb.append(" n2=").append(ndStr(node2));
            CryptandNeoForge.WAF_LOGGER.info("[WireBurnW] {}", sb);
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private static String epStr(org.patryk3211.powergrid.electricity.wire.IWireEndpoint ep) {
        if (ep == null) return "null";
        if (ep instanceof BlockWireEndpoint bep) {
            return "B(" + bep.getPos() + "#" + bep.getTerminal() + ")";
        }
        if (ep instanceof JunctionWireEndpoint) {
            return "J";
        }
        return ep.getClass().getSimpleName();
    }

    @Unique
    private static String ndStr(IElectricNode nd) {
        if (nd == null) return "null";
        if (nd instanceof OwnedFloatingNode ofn) {
            return "OFN(" + epStr(ofn.endpoint) + ")";
        }
        return nd.getClass().getSimpleName();
    }

    @Unique
    private static boolean isOrphan(IElectricNode node) {
        if (node instanceof OwnedFloatingNode ofn) {
            try {
                return ofn.getNetwork() == null;
            } catch (Throwable ignored) {
            }
        }
        return false; // 内部节点（变压器 primaryStray 等）不判定
    }

    /** [WireBurn] 附加诊断：打印导线所属网络的全部节点（电压/类型/位置/端子） */
    @Unique
    private void cryptand$dumpNetwork() {
        try {
            ElectricalNetwork net = getNetwork();
            if (net == null) {
                // 两端节点各自网络
                for (IElectricNode n : new IElectricNode[]{node1, node2}) {
                    if (n instanceof OwnedFloatingNode ofn && ofn.getNetwork() != null) {
                        cryptand$dumpNet(ofn.getNetwork());
                        return;
                    }
                }
                CryptandNeoForge.WAF_LOGGER.info("[WireBurnNet] wireNet=null");
                return;
            }
            cryptand$dumpNet(net);
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private static void cryptand$dumpNet(ElectricalNetwork net) {
        try {
            StringBuilder sb = new StringBuilder();
            int cnt = 0;
            for (INode n : net.getNodes()) {
                if (n instanceof OwnedFloatingNode ofn) {
                    if (cnt++ < 16) sb.append(nodeInfo(ofn)).append(" | ");
                }
            }
            CryptandNeoForge.WAF_LOGGER.info(
                    "[WireBurnNet] id={} nodes={} [{}]",
                    System.identityHashCode(net), net.getNodes().size(), sb);
        } catch (Throwable ignored) {
        }
    }

    /** [WireBurn] 附加诊断：若导线两端任一在变压器方块上 → 打印变压器详细数据 + 4 端子电压 */
    @Unique
    private void cryptand$dumpTransformer() {
        try {
            net.minecraft.world.level.Level lv =
                    PhasorPipeline.CRYPTAND_LAST_LEVEL;
            if (lv == null) {
                CryptandNeoForge.WAF_LOGGER.info("[WireBurnXfrm] no-level");
                return;
            }
            for (IElectricNode nd : new IElectricNode[]{node1, node2}) {
                if (!(nd instanceof OwnedFloatingNode ofn)) continue;
                if (!(ofn.endpoint instanceof BlockWireEndpoint bep)) continue;
                net.minecraft.core.BlockPos bp1 = bep.getPos();
                if (bp1 == null) continue;
                net.minecraft.world.level.block.entity.BlockEntity be = lv.getBlockEntity(bp1);
                if (!(be instanceof TransformerBlockEntity tb)) continue;
                TransformerCoilParameters pc = null, sc = null;
                try { pc = tb.getPrimary(); } catch (Throwable ignored2) { }
                try { sc = tb.getSecondary(); } catch (Throwable ignored2) { }
                StringBuilder sb = new StringBuilder();
                sb.append("pos=").append(bep.getPos());
                sb.append(" pcTurns=").append(pc != null && pc.isDefined() ? pc.getTurns() : -1);
                sb.append(" scTurns=").append(sc != null && sc.isDefined() ? sc.getTurns() : -1);
                sb.append(" pcTerm=[").append(pc == null ? -1 : pc.getTerminal1())
                        .append(',').append(pc == null ? -1 : pc.getTerminal2()).append(']');
                sb.append(" scTerm=[").append(sc == null ? -1 : sc.getTerminal1())
                        .append(',').append(sc == null ? -1 : sc.getTerminal2()).append(']');
                sb.append(" coreAl=").append(tb.coreAl());
                sb.append(" cf=").append(tb.couplingFactor());
                CryptandNeoForge.WAF_LOGGER.info("[WireBurnXfrm] {}", sb);
                // 4 端子电压/网络详情
                try {
                    org.patryk3211.powergrid.electricity.base.ElectricBehaviour beh =
                            tb.getElectricBehaviour();
                    if (beh != null) {
                        for (int t = 0; t < 4; t++) {
                            OwnedFloatingNode tn = beh.getTerminal(t);
                            if (tn != null) {
                                CryptandNeoForge.WAF_LOGGER.info(
                                        "  xfrmTerm{} {}", t, nodeInfo(tn));
                            } else {
                                CryptandNeoForge.WAF_LOGGER.info(
                                        "  xfrmTerm{} null", t);
                            }
                        }
                    }
                } catch (Throwable ignored2) {
                }
                break; // 只打一个变压器
            }
        } catch (Throwable ignored) {
        }
    }
}
