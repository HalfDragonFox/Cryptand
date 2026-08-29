/**
 * ===== 受电弓自管贴合算法（2026-08-22 "原版仅接管算法/相当于电气设备"） =====
 *
 * CEE 原版 / Cryptand 自家受电弓在【自管模式】（PowerGridWireConverter.isEnabled
 * 且 CeeTerminalSupport.ceeEnabled）下，服务端贴合统一走本工具：
 *   - 找自管网（WireNetworkManager）中最近的接触网边（holder↔holder，catenary）
 *   - 三分搜索升弓 extension∈[0,2.7] 使滑触板 y 贴合接触网（CEE 客户端算法）
 *   - 写 {@link PantographTapCache}（主线程同步 = 消息；后台 buildContextFromGraph
 *     读缓存把接触网段拆两半 + 1:1 理想互感桥接底座↔触点）
 * 结果通过 setTarget 回调写回 BE 的 targetExtensionState（动画由 BE tick lerp）。
 *
 * 纯主线程 MC 适配层；不承接计算（求解在引擎）。
 */
package com.hdf.cryptand.neoforge.cee;

import com.hdf.cryptand.neoforge.railway.pantograph.PantographTapCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.function.Consumer;

public final class PantographTapLogic {

    private PantographTapLogic() {
    }

    /** 诊断节流 */
    private static volatile long lastDbg = 0;

    /** 自管模式（算法接管开关）：PowerGrid 接管启用 && CEE 支持启用 */
    public static boolean isSelfManaged(Level level) {
        try {
            if (level == null) return false;
            if (!com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter.isEnabled())
                return false;
            return CeeTerminalSupport.ceeEnabled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 服务端（主线程）滑触头定位（CEE 原版/Cryptand 自家受电弓通用）。
     * @param setTarget 扩展状态写回（BE 的 targetExtensionState 字段）
     */
    public static void serverHandle(Level level, BlockPos pos, BlockState state,
                                    boolean extended, Consumer<Float> setTarget) {
        try {
            if (level == null || pos == null || state == null) return;
            var mgr = com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get();
            double reach = 1.625;
            float yaw = CeeTerminalSupport.facingYRotOf(state);
            com.hdf.cryptand.circuitsimulation.netgraph.WireEdge best = null;
            BlockPos bestA = null, bestB = null;
            Vec3 bestClosest = null;
            double bestDist = Double.MAX_VALUE;
            int total = 0, cat = 0;
            // 2026-08-23 识别放宽：扫描【全部自管导线段】（不只 catenary）——
            // 受电弓应能识别/贴合上方任何导线；取电 tap 仍仅猫天线（下方判断）。
            for (com.hdf.cryptand.circuitsimulation.netgraph.WireEdge e : mgr.edgeList()) {
                total++;
                boolean isCat = CeeTerminalSupport.isCatenaryEdge(e);
                if (isCat) cat++;
                BlockPos hA = CeeTerminalSupport.holderPosOf(e.a);
                BlockPos hB = CeeTerminalSupport.holderPosOf(e.b);
                if (hA == null || hB == null) continue;
                Vec3 pA = CeeTerminalSupport.terminalPosWorld(level, hA);
                Vec3 pB = CeeTerminalSupport.terminalPosWorld(level, hB);
                if (pA == null || pB == null) continue;
                Vec3 probe = connectorPos(state, pos, extended ? 1f : 0f);
                float t = CeeTerminalSupport.closestPointOnWire(pA, pB, probe);
                if (t <= -0.01 || t >= 1.01) continue;
                Vec3 closest = pA.lerp(pB, t);
                double dx = probe.x - closest.x;
                double dz = probe.z - closest.z;
                double horiz = dx * dx + dz * dz;
                if (horiz < bestDist) {
                    if (Math.abs(probe.y - closest.y) < reach * 1.2) {
                        bestDist = horiz;
                        best = e;
                        bestA = hA;
                        bestB = hB;
                        bestClosest = closest;
                    }
                }
            }
            // 诊断（节流 ~2s）
            try {
                long now = System.currentTimeMillis();
                if (now - lastDbg > 2000) {
                    lastDbg = now;
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[PantoScan] edges={} catenary={} hit={} pos={}",
                            total, cat, best != null, pos);
                }
            } catch (Throwable ignored) {
            }
            if (best == null || bestA == null || bestB == null) {
                PantographTapCache.set(pos, null);
                if (extended && setTarget != null)
                    setTarget.accept(1f);   // CEE：无网回退 extension=1
                return;
            }
            // 2) 三分对齐滑触板高度（CEE 域 [0,2.7]）——任何导线段都贴合升弓
            float contactY = (float) bestClosest.y;
            float newExt = fitExtensionToWire(state, pos, contactY);
            if (setTarget != null) {
                setTarget.accept(newExt);
            }
            // 3) 触点参数（基于贴合后滑触板位置）——【仅猫天线写入取电 tap】
            if (!CeeTerminalSupport.isCatenaryEdge(best)) {
                PantographTapCache.set(pos, null);
                return;
            }
            Vec3 conn = connectorPos(state, pos, newExt);
            Vec3 pA = CeeTerminalSupport.terminalPosWorld(level, bestA);
            Vec3 pB = CeeTerminalSupport.terminalPosWorld(level, bestB);
            if (pA == null || pB == null) return;
            float t = CeeTerminalSupport.closestPointOnWire(pA, pB, conn);
            Vec3 cp = CeeTerminalSupport.checkCatenary(pA, pB, conn, t,
                    (float) reach, yaw);
            PantographTapCache.set(pos, cp != null
                    ? new PantographTapCache.PantographTap(pos.immutable(),
                    bestA.immutable(), bestB.immutable(), t, true)
                    : null);
        } catch (Throwable ignored) {
        }
    }

    /** 三分搜索升弓高度 extension∈[0,2.7]（CEE 域；renderer 角度按此设计） */
    private static float fitExtensionToWire(BlockState state, BlockPos pos, double contactY) {
        float hi = 2.7f;
        float lo = 0;
        for (int i = 0; i < 20; i++) {
            float m1 = lo + (hi - lo) / 3f;
            float m2 = hi - (hi - lo) / 3f;
            if (Math.abs(connectorPos(state, pos, m1).y - contactY)
                    < Math.abs(connectorPos(state, pos, m2).y - contactY))
                hi = m2;
            else
                lo = m1;
        }
        return (lo + hi) / 2f;
    }

    /** 滑触板世界坐标（参数化几何；与 PantographBlockEntity.getConnectorPos 相同公式） */
    public static Vec3 connectorPos(BlockState state, BlockPos pos, float extensionState) {
        net.minecraft.core.Direction facing = state.hasProperty(
                net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)
                ? state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING)
                : net.minecraft.core.Direction.NORTH;
        boolean isDouble = state.hasProperty(
                com.hdf.cryptand.neoforge.railway.pantograph.PantographBlock.DOUBLE)
                && state.getValue(com.hdf.cryptand.neoforge.railway.pantograph.PantographBlock.DOUBLE);
        net.minecraft.core.Direction.Axis axis = facing.getAxis();
        if (isDouble) {
            float lowerArmRadians = (-90 + extensionState * 27) * net.minecraft.util.Mth.DEG_TO_RAD;
            double armHingePosY = net.minecraft.util.Mth.cos(lowerArmRadians) * 1.5 + 0.5;
            double armHingePosX = net.minecraft.util.Mth.sin(lowerArmRadians) * 1.5;
            float b = (float) (-0.4f - Math.abs(armHingePosX));
            float a = net.minecraft.util.Mth.sqrt(-b * b + 2f * 2f);
            double pantographX = facing.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE ? 0 : 1;
            Vec3 plate = new Vec3(axis == net.minecraft.core.Direction.Axis.X ? pantographX : 0.5,
                    0.1875 + a + armHingePosY,
                    axis == net.minecraft.core.Direction.Axis.Z ? pantographX : 0.5);
            return plate.add(pos.getX(), pos.getY(), pos.getZ());
        }
        float lowerArmRadians = (-75 + extensionState * 30) * net.minecraft.util.Mth.DEG_TO_RAD;
        double armHingePosY = net.minecraft.util.Mth.cos(lowerArmRadians) * 1.875 + 0.5;
        double armHingePosX = net.minecraft.util.Mth.sin(lowerArmRadians) * 1.875;
        float upperArmRadians = (89 - extensionState * 50) * net.minecraft.util.Mth.DEG_TO_RAD;
        double pantographX = facing.getAxisDirection() == net.minecraft.core.Direction.AxisDirection.POSITIVE ? 0.25 : 0.75;
        Vec3 plate = new Vec3(axis == net.minecraft.core.Direction.Axis.X ? pantographX : 0.5,
                armHingePosY,
                armHingePosX + (axis == net.minecraft.core.Direction.Axis.Z ? pantographX : 0.5))
                .add(0, net.minecraft.util.Mth.cos(upperArmRadians) * 1.9,
                        net.minecraft.util.Mth.sin(upperArmRadians) * 1.9);
        return plate.add(pos.getX(), pos.getY(), pos.getZ());
    }
}
