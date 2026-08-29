/**
 * ===== 导线组装器（2026-08-14 注册器：实际模型与虚拟元件解耦都通过组装器） =====
 *
 * 与设备组装器（{@code Assembler}）同模式：实际模型（连续段 WireSegment）↔
 * 虚拟元件（WireComposite）通过组装器解耦组装。
 *
 * 【默认组装】带温度模型 + 电阻（用户要求：默认是带温度模型和电阻，只需要
 * 设电阻值等参数即可）：
 *   - 电阻：段电阻（Σ 边电阻）优先，未设时用 SaggingWireType.resistancePerMeter
 *     × 段长度
 *   - 温度：WireThermalStore（段路径 key，同段统一温度/统一烧毁）
 *   - 绑定：段 → 段注册表（SEGMENT_EDGES，烧毁/剪线按段定位）
 *
 * 可继承覆写 {@link #assemble} 添加各种模型和元件（电容/电感/功率计等）。
 * 三种内置导线默认都走 {@link #DEFAULT} 实例。
 */

package com.hdf.cryptand.neoforge.powergrid.device.wire;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.WireComposite;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.netgraph.WireSegment;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkBuilder;
import com.hdf.cryptand.neoforge.powergrid.adapter.WireThermalStore;

public class WireAssembler {

    /** 默认组装器（三种内置导线） */
    public static final WireAssembler DEFAULT = new WireAssembler();

    /**
     * 组装导线段 → 虚拟元件（WireComposite）。
     *
     * @param type 导线类型（可 null：用段自带电阻）
     * @param seg  连续段（实际模型）
     * @param a,b  段两端引擎节点
     * @param net  目标引擎网络
     * @return 组装出的复合元件；无效（无电阻/节点缺失）返回 null
     */
    public WireComposite assemble(SaggingWireType type, WireSegment seg,
                                  int a, int b, Network net) {
        if (seg == null || net == null) return null;
        // 电阻：段 Σ 电阻优先；未设 → 类型电阻值 × 段长度
        double r = seg.resistance > 1e-9 ? seg.resistance
                : (type != null ? type.resistancePerMeter() * Math.max(seg.length, 0) : 0);
        if (r <= 1e-9) return null;
        // 温度模型（段路径 key：同段统一温度/统一烧毁；散热/热容按段长缩放，
        // 2026-08-19 "25°C 室温下金导线 ≥160A 额定"）
        ThermalModel th = WireThermalStore.thermalFor(seg.key, seg.length);
        WireComposite wc = new WireComposite(a, b, r, th, seg.key);
        net.addComposite(wc);
        // 绑定：实际段 ↔ 虚拟元件（段注册表，烧毁/剪线按段定位）
        PhasorNetworkBuilder.registerSegment(seg.key, seg.edges);
        return wc;
    }
}
