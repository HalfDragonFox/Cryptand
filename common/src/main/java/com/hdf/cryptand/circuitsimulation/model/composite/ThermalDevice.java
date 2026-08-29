package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 带温度的设备复合模型：复合元件组合 + 温度模型 + 损耗计算。
 * <p>
 * 实际设备（电机/加热器/电磁铁/灯具等）通过【包含】本接口做温度模拟：
 * 求解后 PhasorEngine 用端口电压差按 {@link #lossPower} 算平均损耗功率 →
 * {@link ThermalModel#advance} 推进温度（散热与发热同时算，解析解稳定）。
 * 风扇冷却可经 {@link ThermalModel#setCoolingMultiplier} 提高散热系数。
 */
public interface ThermalDevice {

    /** 端口节点（求解后电压读取用） */
    int nodeA();

    /** 端口节点（求解后电压读取用） */
    int nodeB();

    /** 温度模型（无温度模拟返回 null） */
    ThermalModel thermal();

    /** 从端口相量电压算【平均】损耗功率（W）——如绕组铜耗 I²·R/2 */
    double lossPower(Complex va, Complex vb, double omega);
}
