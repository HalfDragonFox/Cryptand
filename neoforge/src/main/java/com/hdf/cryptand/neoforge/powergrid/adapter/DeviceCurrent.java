/**
 * ===== 设备电流缓存读写（2026-08-22 用户需求：供电判断走检测内部元件电流） =====
 *
 * 架构：组装器缓存（DeviceCache）保存设备电流——
 *   - 后台求解 round（PhasorEngine.computeDeviceHeatUnified）对每设备计算
 *     【端子0 电流】（= 设备内部流过电阻/绕组的净电流：邻接 R/L/C 支路按元件
 *     阻抗计算，KCL 使节点注入电流 = 设备内部元件电流）；
 *   - 写入组装器缓存 {@link DeviceCache#setDeviceCurrent}；
 *   - 主线程 tick（电机/伺服/风扇 mixin）读缓存判断供电（不再用电压差猜电流）。
 *
 * 对应框架：设备生命周期（DeviceCacheRegistry remove）自动清理缓存。
 */

package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.TerminalElement;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;
import net.minecraft.core.BlockPos;

public final class DeviceCurrent {

    private DeviceCurrent() {
    }

    /** 主线程读设备电流（组装器缓存；无缓存/未求解 → 0）。有向，流入端子0 为正 */
    public static double read(BlockPos pos) {
        if (pos == null) return 0;
        DeviceCache c = DeviceCacheRegistry.get(pos);
        return c == null ? 0 : c.deviceCurrent();
    }

    /** 求解 round：计算设备端子0 电流并写入组装器缓存（后台；无缓存跳过） */
    public static void write(BlockPos pos, com.hdf.cryptand.circuitsimulation.model.Network net,
                             SolveResult res) {
        try {
            TerminalElement t0 = TerminalRegistry.get(pos, 0);
            double cur = (t0 != null && t0.valid())
                    ? nodeCurrent(net, res, t0.engineNode) : 0;
            DeviceCache c = DeviceCacheRegistry.get(pos);
            if (c != null) c.setDeviceCurrent(cur);
        } catch (Throwable ignored) {
        }
    }

    /** 端子节点注入电流（A，流入 nodeId 为正）= 邻接【非理想】支路电流之和
     *  （R/L/C 支路按元件阻抗算；源/受控源/理想导线跳过）。KCL 使对接到设备
     *  内部元件的端子（如电机绕组 R-L）得到设备内部流过元件的净电流。 */
    public static double nodeCurrent(com.hdf.cryptand.circuitsimulation.model.Network net,
                                     SolveResult res, int nodeId) {
        if (net == null || res == null || res.voltages == null || nodeId < 0) return 0;
        double[] v = res.voltages;
        Complex[] c = res.complex;
        int n = v.length;
        if (nodeId >= n) return 0;
        double freq = net.dominantFrequency();
        double cur = 0;
        try {
            for (Element e : net.elements()) {
                int a = e.nodeA();
                int b = e.nodeB();
                if (a != nodeId && b != nodeId) continue;
                boolean outFromId = (a == nodeId); // 元件电流定义沿 a→b
                int other = outFromId ? b : a;
                if (other < 0 || other >= n) continue;
                double z = impedance(e, freq);
                if (z <= 1e-9) continue;
                double vj = valAt(v, c, n, nodeId);
                double vk = valAt(v, c, n, other);
                double curEl = (vj - vk) / z; // 支路电流（a→b 方向）
                // 流进 nodeId：元件从 nodeId → other 时对 nodeId 是流出 → 取负
                cur += outFromId ? -curEl : curEl;
            }
        } catch (Throwable ignored) {
        }
        return cur;
    }

    /** 节点电压取样：相量模式取 RMS 幅值，实数模式取瞬时值 */
    private static double valAt(double[] v, Complex[] c, int n, int id) {
        if (c != null && id < c.length) return c[id].abs() / Math.sqrt(2.0);
        return v[id];
    }

    /** 元件支路阻抗（Ω）。R/L/C 支撑（电机绕组 R-L + 导线 R 是主要支路）；
     *  源/受控源/理想导线不参与（return 0 → 跳过，其电流由相邻 R/L/C 反映）。 */
    private static double impedance(Element e, double freq) {
        try {
            double[] p = e.params();
            double p0 = (p == null || p.length == 0) ? 0 : p[0];
            switch (e.type()) {
                case RESISTOR: {
                    double r = p0 > 1e-9 ? p0 : 1.0;
                    return r;
                }
                case INDUCTOR: {
                    double l = Math.max(p0, 0);
                    double w = 2.0 * Math.PI * Math.max(freq, 0);
                    double z = l > 1e-12 ? l * w : 0;
                    return z > 1e-6 ? z : 1e-3; // DC/近 0 频率 → 忽略电感支路电流
                }
                case CAPACITOR: {
                    double cCap = Math.max(p0, 0);
                    if (freq > 1e-6 && cCap > 1e-15) {
                        return 1.0 / (cCap * 2.0 * Math.PI * freq);
                    }
                    return 1e9; // DC → 电容近似开路
                }
                default:
                    return 0; // 源/受控源等不提供基础阻抗
            }
        } catch (Throwable ignored) {
            return 0;
        }
    }
}