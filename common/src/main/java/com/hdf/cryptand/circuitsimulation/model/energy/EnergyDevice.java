package com.hdf.cryptand.circuitsimulation.model.energy;

import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 储能设备接口（2026-08-12 用户要求）：电容/电池等能量存储元件。
 * <p>
 * 统一提供：
 *   - {@link #energy()} —— 能量模型（储能状态：电荷/电容/电压/容量）
 *   - {@link #nodeA()}/{@link #nodeB()} —— 端口节点（求解后取端口电压）
 *   - {@link #syncCharge(Complex, Complex)} —— 求解后从端口相量电压同步储能
 *     电荷（时间相关变量绑定：相量求不出时由电荷状态驱动电压，类似电池）
 */
public interface EnergyDevice {

    /** 能量模型（储能状态） */
    EnergyModel energy();

    /** 端口节点 A（引擎节点 id） */
    int nodeA();

    /** 端口节点 B（引擎节点 id） */
    int nodeB();

    /** 求解后同步储能电荷（时间相关变量绑定）：从端口相量电压更新电荷状态 */
    void syncCharge(Complex va, Complex vb);
}
