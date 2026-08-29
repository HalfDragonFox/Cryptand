/** ===== 统一测量目标（纯数据，无 Level 依赖，2026-08-18） =====
 *
 * 所有 Cryptand 手持测量工具（万用表/温度计/示波器）的测量目标统一格式。
 * 服务端测量处理（SelfManagedMultimeter / ServerMeasurementSystem）与
 * 客户端探针线渲染（MeasurementLineRenderer）都基于此格式交互。
 *
 * 无 Level/Entity 引用，可在后台线程安全传递。
 */

package com.hdf.cryptand.neoforge.core.measurement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public record MeasurementTarget(
        Type type,
        BlockPos pos, int term,
        BlockPos posB, int termB,
        Vec3 exactPos,
        double freq
) {

    public enum Type {
        /** 元件内部温度（pos = 方块） */
        DEVICE_TEMPERATURE,
        /** 导线段温度（pos = 端点方块） */
        WIRE_SEGMENT_TEMP,
        /** 单点电流（pos#term） */
        NODE_CURRENT,
        /** 同方块两点电压（pos, term + termB） */
        VOLTAGE_SAME_BLOCK,
        /** 跨方块电压（pos,term + posB,termB） */
        VOLTAGE_BETWEEN_BLOCKS
    }

    // ========== 工厂方法（纯数据构造，无 Level） ==========

    public static MeasurementTarget deviceTemp(BlockPos pos) {
        return new MeasurementTarget(Type.DEVICE_TEMPERATURE, pos, -1, null, -1, null, 0);
    }

    public static MeasurementTarget wireSegTemp(BlockPos epPos) {
        return new MeasurementTarget(Type.WIRE_SEGMENT_TEMP, epPos, -1, null, -1, null, 0);
    }

    public static MeasurementTarget nodeCurrent(BlockPos pos, int term, double freq) {
        return new MeasurementTarget(Type.NODE_CURRENT, pos, term, null, -1, null, freq);
    }

    public static MeasurementTarget sameBlockVoltage(BlockPos pos, int t1, int t2, double freq) {
        return new MeasurementTarget(Type.VOLTAGE_SAME_BLOCK, pos, t1, pos, t2, null, freq);
    }

    public static MeasurementTarget betweenBlocksVoltage(BlockPos a, int ta, BlockPos b, int tb, double freq) {
        return new MeasurementTarget(Type.VOLTAGE_BETWEEN_BLOCKS, a, ta, b, tb, null, freq);
    }

    // ========== 探针线位置解析（需要 Level） ==========

    /** 客户端解析目标的世界坐标（用于画探针线）。
     *  返回绝对世界坐标列表（手持位置→目标），每对 [handPos, targetPos]。 */
    public static List<Vec3[]> resolveProbePoints(
            net.minecraft.client.multiplayer.ClientLevel level, Vec3 handPos,
            List<MeasurementTarget> targets, int maxLines) {
        if (targets == null || targets.isEmpty()) return List.of();
        java.util.ArrayList<Vec3[]> lines = new java.util.ArrayList<>();
        int count = 0;
        for (MeasurementTarget t : targets) {
            if (count >= maxLines) break;
            Vec3 wp = worldPosOf(level, t);
            if (wp == null) continue;
            lines.add(new Vec3[]{handPos, wp});
            count++;
            // 对 VOLTAGE_SAME_BLOCK/VOLTAGE_BETWEEN_BLOCKS 还需要第二根线（Neg）
            if (t.type() == Type.VOLTAGE_SAME_BLOCK || t.type() == Type.VOLTAGE_BETWEEN_BLOCKS) {
                if (count >= maxLines) break;
                Vec3 wp2 = worldPosOf(level, t, true); // 第二个点（neg）
                if (wp2 != null) {
                    lines.add(new Vec3[]{handPos, wp2});
                    count++;
                }
            }
        }
        return lines;
    }

    /** 单目标→世界坐标（客户端，0=第一个端子，neg=false */
    private static Vec3 worldPosOf(net.minecraft.client.multiplayer.ClientLevel level, MeasurementTarget t) {
        return worldPosOf(level, t, false);
    }

    /** 单目标→世界坐标（客户端；neg=true 取第二个端子） */
    private static Vec3 worldPosOf(net.minecraft.client.multiplayer.ClientLevel level, MeasurementTarget t, boolean neg) {
        try {
            BlockPos p = neg && t.posB() != null ? t.posB() : t.pos();
            int term = neg ? t.termB() : t.term();
            if (term >= 0) {
                Vec3 exact = org.patryk3211.powergrid.electricity.base.IElectric
                        .getTerminalPos(level, p, term);
                if (exact != null && exact.lengthSqr() > 0) return exact;
            }
            return net.minecraft.world.phys.Vec3.atCenterOf(p);
        } catch (Throwable ignored) {
            return null;
        }
    }
}