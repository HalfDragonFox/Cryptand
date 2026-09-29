/**
 * ===== 设备端子电压缓存（2026-09-12 伺服控制线用） =====
 *
 * 与 {@link DeviceCurrent} 同构：后台求解 round 写、主线程（电机/伺服 mixin）读。
 *
 *   - 写：{@code EngineThermalCompute.computeDeviceHeatUnified} 里对每个设备位置
 *     记录【各端子对地电压】（RMS；实数模式取瞬时值）——与设备电流同一次求解结果。
 *   - 读：{@link #read(BlockPos, int)}（单端子对地）/ {@link #readBetween(BlockPos, int, int)}
 *     （端子间电压差）。
 *
 * 为什么需要：原版伺服的角度由【控制线电压】决定
 *   {@code control.potentialDifference() / 5 * 360}（端子 2 与 1 之间），
 * 自管模式下原版 coil/control 的电压不可用 → 由本缓存提供同源的求解结果，
 * 使接管层能【参考原版算法建模】，而不需要恢复原版 tick。
 */

package com.hdf.cryptand.neoforge.powergrid.state;

import com.hdf.cryptand.circuitsimulation.model.TerminalElement;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class DeviceVoltageStore {

    /** 每设备最多记录的端子上限（与 TerminalRegistry 约定一致） */
    private static final int MAX_TERMINALS = 8;

    private static final Map<BlockPos, double[]> VOLT = new ConcurrentHashMap<>();

    private DeviceVoltageStore() {
    }

    /** 求解 round（后台）：记录该设备各端子对地电压（V） */
    public static void write(BlockPos pos, SolveResult res) {
        if (pos == null || res == null) return;
        try {
            double[] out = new double[MAX_TERMINALS];
            boolean any = false;
            for (int t = 0; t < MAX_TERMINALS; t++) {
                TerminalElement te = TerminalRegistry.get(pos, t);
                if (te == null || !te.valid()) continue;
                int id = te.engineNode;
                if (id < 0) continue;
                out[t] = valAt(res, id);
                any = true;
            }
            if (any) VOLT.put(pos, out);
        } catch (Throwable ignored) {
        }
    }

    /** 单端子对地电压（V；无记录/端子未注册 → 0） */
    public static double read(BlockPos pos, int terminal) {
        if (pos == null || terminal < 0 || terminal >= MAX_TERMINALS) return 0;
        double[] v = VOLT.get(pos);
        return (v == null || terminal >= v.length) ? 0 : v[terminal];
    }

    /** 两端子间的电压差（V，V(a) − V(b)） */
    public static double readBetween(BlockPos pos, int a, int b) {
        return read(pos, a) - read(pos, b);
    }

    /** 是否有该位置的记录（诊断） */
    public static boolean has(BlockPos pos) {
        return pos != null && VOLT.containsKey(pos);
    }

    /** 位置清理（设备移除） */
    public static void remove(BlockPos pos) {
        if (pos != null) VOLT.remove(pos);
    }

    /** 端子电压取样：相量模式取 RMS 幅值，实数模式取瞬时值（与 DeviceCurrent 一致） */
    private static double valAt(SolveResult res, int id) {
        try {
            if (res.complex != null && id < res.complex.length) {
                return res.complex[id].abs() / Math.sqrt(2.0);
            }
            if (res.voltages != null && id < res.voltages.length) {
                return res.voltages[id];
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }
}
