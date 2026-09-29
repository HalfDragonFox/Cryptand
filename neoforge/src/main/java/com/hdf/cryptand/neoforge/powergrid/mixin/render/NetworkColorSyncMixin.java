/**
 * ===== 网络颜色同步（2026-08-22 用户要求：debug 模式通过 mixin 实现，不动原本代码） =====
 *
 * 注入 {@link PowerGridWireConverter#convertWires}（每 tick 由 WorldNetworksMixin.
 * preTick → syncFromWorld 调用）【返回后】：自管图版本变化 → 立即把网络颜色
 * （NetworkColorPayload，方框）发送给客户端。
 *
 * 设计：
 *   - 【不动原本代码】：不修改 PowerGridWireConverter 的同步逻辑，颜色发送完全
 *     由本 mixin 独立注入（复制色轮/分量逻辑，与 ClientNetworkColorStore 一致）；
 *   - 【每次网络变动发送】：图 version 变化即发送（不过滤流量，用户要求 debug
 *     模式不用管流量）；图空也发空列表（客户端清空旧方框，防残留）；
 *   - 【debug 模式专用】：客户端渲染（NetworkColorRenderer）由
 *     com.hdf.cryptand.neoforge.core.config.ConfigPowerGrid.DEBUG_NETWORK_COLORS 门控，正常模式客户端忽略包。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin.render;

import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.core.wire.WireKeyUtil;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorPipeline;

import com.hdf.cryptand.neoforge.powergrid.net.NetworkColorPayload;
import com.hdf.cryptand.neoforge.powergrid.network.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.network.NetworkPalette;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.sim.special.TransmissionLine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Mixin(value = PowerGridWireConverter.class, remap = false)
public abstract class NetworkColorSyncMixin {

    /* 色轮已抽到 {@link com.hdf.cryptand.neoforge.powergrid.network.NetworkPalette}
     * （2026-09-13 用户："改随机颜色代码值，可以定义基础色轮然后在此基础上进行
     *  小范围随机，尽可能丰富并且明显一些"）：
     *   · 基础色轮 24 色，黄金角在 HSV 色相环均匀铺开（相邻色差异最大）；
     *   · 每通道 ±18 抖动（约 ±7%），色相不变 ⇒ 同族仍可辨；
     *   · 随机源 = 网络内容哈希 ⇒ 同网络颜色恒定、不闪烁。
     * 服务端与客户端共用同一个类，保证两边颜色一致。 */

    /** 上次发送颜色的图版本（去重：图没变不重复发） */
    @Unique
    private static long cryptand$lastColorVer = -1;

    /** 上次发送时刻（心跳节流，见下） */
    @Unique
    private static long cryptand$lastColorMs;

    /**
     * 上传间隔（ms）= **10 tick**（0.5s）。
     *
     * 2026-09-13 用户："按照每 10 tick 从服务器上传每个网表对应的电气设备位置到
     * 客户端然后客户端渲染方框，每个网络不同颜色，仅 debug 模式开启时有效"。
     * `convertWires` 每 tick 被调用（WorldNetworksMixin.preTick → syncFromWorld），
     * 因此按墙钟 500ms 节流即等价于每 10 tick 上传一次（不依赖 tick 计数，
     * 即使某 tick 未触发也不会漏）。
     */
    @Unique
    private static final long COLOR_SYNC_INTERVAL_MS = 500;

    /** convertWires 返回后：图版本变化 → 发送网络颜色（每次网络变动就发）。
     *  ⚠ 必须用 @At("TAIL")（方法体末尾正常 return）——@At("RETURN") 默认 ordinal=0
     *  捕获【第一个 return】（convertWires 开头的 if(!isEnabled()) return），导致
     *  正常路径永不触发（实测 [ColorSync] 无日志）。 */
    @Inject(method = "convertWires", at = @At("TAIL"), remap = false)
    private static void cryptand$afterConvertWires(List<TransmissionLine> worldWires,
                                                   CallbackInfo ci) {
        try {
            var mgr = WireNetworkManager.get();
            if (mgr == null) return;
            // ===== 2026-09-13 用户："客户端全量显示网络……仅 debug 模式开启时有效" =====
            // 【仅 debug】：DEBUG_NETWORK_COLORS 关闭时完全不发（零流量、零开销）。
            boolean debugOn = false;
            try {
                debugOn = com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid
                        .DEBUG_NETWORK_COLORS.get();
            } catch (Throwable ignored) {
            }
            if (!debugOn) return;
            // 【每 10 tick 全量上传】：不做"图版本变化才发"的去重 —— 全量下发保证
            // 客户端始终持有完整的"网络 → 电气设备位置"映射（重进世界/换维度/丢包
            // 后最多 0.5s 内自愈）。
            long nowMs = System.currentTimeMillis();
            if (nowMs - cryptand$lastColorMs < COLOR_SYNC_INTERVAL_MS) return;
            cryptand$lastColorMs = nowMs;
            cryptand$lastColorVer = mgr.version();
            Level level = CryptandTopologyManager.get().getLevel();
            if (level == null) level = PhasorPipeline.CRYPTAND_LAST_LEVEL;
            if (level == null || level.isClientSide) return;
            // 服务端/玩家在线检查（防止保存/退出期间发送阻塞 Server 线程）
            net.minecraft.server.MinecraftServer srv =
                    net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (srv == null || srv.isStopped()) return;
            if (srv.getPlayerList() == null
                    || srv.getPlayerList().getPlayers().isEmpty()) return;
            // 构建网络颜色（连通分量 → 内容哈希稳定色）→ 发送客户端
            List<NetworkColorPayload.ColorEntry> ce = new ArrayList<>();
            for (Set<com.hdf.cryptand.circuitsimulation.netgraph.WirePoint> comp : mgr.components()) {
                if (comp == null || comp.isEmpty()) continue;
                List<BlockPos> poss = new ArrayList<>();
                for (com.hdf.cryptand.circuitsimulation.netgraph.WirePoint p : comp) {
                    if (WireKeyUtil.isBlock(p.key)) {
                        BlockPos bp = PhasorNetworkBuilder.pointPosOfPublic(p.key);
                        if (bp != null) poss.add(bp);
                    }
                }
                if (poss.isEmpty()) continue;
                poss.sort(Comparator.comparingLong(BlockPos::asLong));
                long h = 0;
                for (BlockPos bp : poss) h = h * 31 + bp.asLong();
                // 基础色轮 + 小范围哈希抖动（同网络稳定）
                int color = com.hdf.cryptand.neoforge.powergrid.network.NetworkPalette.colorFor(h);
                for (BlockPos bp : poss) {
                    ce.add(new NetworkColorPayload.ColorEntry(
                            bp.getX(), bp.getY(), bp.getZ(), color));
                }
            }
            // ⚠ 图空也发送空列表（客户端 setServiceColors 清空旧方框）——保证每次
            // 网络变动客户端状态与服务端一致（不过滤流量，debug 模式专用）。
            net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
                    new NetworkColorPayload(ce));
            CryptandNeoForge.WAF_LOGGER.info(
                    "[ColorSync] sent entries={} ver={}（每 10 tick 全量）", ce.size(),
                    cryptand$lastColorVer);
        } catch (Throwable ignored) {
        }
    }
}
