/**
 * ===== 导线视线射线检测（2026-08-17） =====
 *
 * 自管模式下导线实体已删除（无碰撞体）→ 原版"准星瞄准导线实体/射线命中"
 * 失效。本类每 tick 从玩家眼睛沿视线发射射线，对所有客户端自管导线
 * （ClientWireGraphStore.ClientWire，二次曲线 posAt）求交——找到视线最近
 * 命中点写入 WireLookStore（渲染层高亮 + 剪线钳右键拆除的依据）。
 *
 * 对齐铁律：纯客户端【渲染/模型层】（读 level 拿玩家状态），不涉及计算。
 * 命中算法：曲线参数 t∈[0,1] 采样 → 点到射线距离 < 阈值（导线粗细宽容）。
 *
 * 线程：客户端主线程 tick（ClientSoundTicker.onClientTick 调用）。
 */

package com.hdf.cryptand.neoforge.powergrid.client.wire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.patryk3211.powergrid.utility.PlayerUtilities;

import java.util.List;

public final class WireLookPicker {

    /** 命中半径（格）：导线渲染粗细 1/16，给准星较大宽容便于选中 */
    private static final double HIT_RADIUS = 0.15;
    /** 曲线参数采样步长（t 每 0.02 采样一次，最长导线 51 点/条，开销可忽略） */
    private static final double STEP = 0.02;

    private WireLookPicker() {
    }

    /** 每客户端 tick 调用：射线检测并更新 WireLookStore。 */
    public static void tick() {
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            LocalPlayer player = mc.player;
            // 仅手持剪线钳/万用表/温度计时激活选中（剪线钳→右键拆除，
            // 测量工具→右键抓取导线；否则准星穿过导线无意义）
            if (level == null || player == null
                    || (!isWireCutter(player.getMainHandItem())
                    && !isWireCutter(player.getOffhandItem())
                    && !isMeasureTool(player.getMainHandItem())
                    && !isMeasureTool(player.getOffhandItem()))) {
                WireLookStore.clear();
                return;
            }
            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            double reach = PlayerUtilities.getReachDistance(player) + 1.0;

            List<ClientWireGraphStore.ClientWire> wires = ClientWireGraphStore.wires();
            WireLookStore.WireHit best = null;
            double bestDistSqr = Double.MAX_VALUE;
            for (ClientWireGraphStore.ClientWire w : wires) {
                double t = rayIntersect(eye, look, reach, w);
                if (t < 0) continue;
                Vec3 hitPoint = QuadraticWireHelper.posAt(w.p1(), w.p2(), (float) t,
                        w.sag() > 0 ? w.sag() : 2f);
                double d = eye.distanceToSqr(hitPoint);
                if (d < bestDistSqr) {
                    bestDistSqr = d;
                    best = new WireLookStore.WireHit(
                            w.ax(), w.ay(), w.az(), w.aTerm(),
                            w.bx(), w.by(), w.bz(), w.bTerm(),
                            (float) t, hitPoint);
                }
            }
            WireLookStore.set(best);
        } catch (Throwable ignored) {
        }
    }

    /** 射线与导线二次曲线求交：返回曲线参数 t（0..1），未命中 -1。 */
    private static double rayIntersect(Vec3 origin, Vec3 dir, double reach,
                                       ClientWireGraphStore.ClientWire w) {
        double thresholdSqr = HIT_RADIUS * HIT_RADIUS;
        double bestT = -1;
        double bestDistSqr = Double.MAX_VALUE;
        float sag = w.sag() > 0 ? w.sag() : 2f;
        for (double t = 0; t <= 1.0001; t += STEP) {
            Vec3 pt = QuadraticWireHelper.posAt(w.p1(), w.p2(), (float) t, sag);
            Vec3 rel = pt.subtract(origin);
            double proj = rel.dot(dir);
            if (proj < 0 || proj > reach) continue; // 点不在射线前方可达段
            double distSqr = rel.lengthSqr() - proj * proj;
            if (distSqr < thresholdSqr && distSqr < bestDistSqr) {
                bestDistSqr = distSqr;
                bestT = t;
            }
        }
        return bestT;
    }

    /** 剪线钳判定（powergrid:wire_cutter） */
    private static boolean isWireCutter(net.minecraft.world.item.ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id != null && id.getNamespace().equals("powergrid")
                    && id.getPath().equals("wire_cutter");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 测量工具判定（右键导线抓取）：原版万用表 powergrid:multimeter +
     *  高级万用表 cryptand:advanced_multimeter + 温度计 cryptand:thermometer
     *  + 电阻表 cryptand:resistance_meter */
    private static boolean isMeasureTool(net.minecraft.world.item.ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id == null) return false;
            if (id.getNamespace().equals("powergrid") && id.getPath().equals("multimeter")) return true;
            if (id.getNamespace().equals("cryptand")
                    && (id.getPath().equals("advanced_multimeter")
                    || id.getPath().equals("thermometer")
                    || id.getPath().equals("resistance_meter"))) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }
}
