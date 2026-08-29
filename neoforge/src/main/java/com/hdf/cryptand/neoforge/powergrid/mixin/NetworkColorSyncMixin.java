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
 *     ConfigLoad.DEBUG_NETWORK_COLORS 门控，正常模式客户端忽略包。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.CryptandTopologyManager;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorWriteback;
import com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.adapter.WireKeyUtil;
import com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager;
import com.hdf.cryptand.neoforge.powergrid.creative.NetworkColorPayload;
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

    /** 色轮（与 ClientNetworkColorStore / PowerGridWireConverter.sendNetworkColors 一致，ARGB） */
    private static final int[] PALETTE = {
            0xFFE53935, 0xFF1E88E5, 0xFF43A047, 0xFFFDD835, 0xFF8E24AA,
            0xFF00ACC1, 0xFFFB8C00, 0xFF3949AB, 0xFFC0CA33, 0xFF6D4C41,
            0xFFD81B60, 0xFF00897B, 0xFF5E35B1, 0xFFF4511E, 0xFF039BE5,
            0xFF7CB342, 0xFFF06292, 0xFF546E7A, 0xFF8D6E63, 0xFF26A69A
    };

    /** 上次发送颜色的图版本（去重：图没变不重复发） */
    @Unique
    private static long cryptand$lastColorVer = -1;

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
            long ver = mgr.version();
            if (ver == cryptand$lastColorVer) return;
            cryptand$lastColorVer = ver;
            Level level = CryptandTopologyManager.get().getLevel();
            if (level == null) level = PhasorWriteback.CRYPTAND_LAST_LEVEL;
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
                int color = PALETTE[Math.floorMod(h, PALETTE.length)];
                for (BlockPos bp : poss) {
                    ce.add(new NetworkColorPayload.ColorEntry(
                            bp.getX(), bp.getY(), bp.getZ(), color));
                }
            }
            // ⚠ 图空也发送空列表（客户端 setServiceColors 清空旧方框）——保证每次
            // 网络变动客户端状态与服务端一致（不过滤流量，debug 模式专用）。
            net.neoforged.neoforge.network.PacketDistributor.sendToAllPlayers(
                    new NetworkColorPayload(ce));
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[ColorSync] sent entries={} ver={}", ce.size(), ver);
        } catch (Throwable ignored) {
        }
    }
}
