package com.hdf.cryptand.circuitsimulation.model.energy;

import com.hdf.cryptand.circuitsimulation.model.state.StateDriven;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;

/**
 * 能量状态统一接口（2026-08-15 用户架构：带能量模型的非线性组件）。
 * <p>
 * 带能量/时间相关状态的元件（电容/电感/电池/电机/热设备等）实现本接口，
 * 求解器在【伪时域固定节拍】下推进其状态：
 *   - {@link #advanceState}：求解后用端口相量电压推进储能/温度/SOC 等；
 *     返回 true = 状态变化导致电学参数变化（下轮需重解）。
 *   - {@link #stateVersion}：状态版本（每次推进 +1），供收敛判定/缓存失效判断。
 *   - {@link #storedEnergy}：当前储能（J），诊断/守恒校验用。
 * <p>
 * 与热模型的关系：{@link ThermalModel} 只管温度（损耗→温度→过热事件）；
 * 本接口是其上的【统一能量状态视图】——一个元件可同时有热模型与本状态。
 * <p>
 * 2026-08-20：继承 {@link StateDriven}（统一状态推进接口）——StateDriven
 * 是更基础的状态抽象（温度/机械/应力等任何状态），EnergyState 在其上增加
 * 储能语义（stateVersion/storedEnergy）。统一计算节点 StateNode 按
 * StateDriven 推进，任何 EnergyState 自动兼容。advanceState 带当前求解器
 * 类型参数 {@link SolveMode}（统一接口约定）。
 */
public interface EnergyState extends StateDriven {

    /** 状态版本（每次 advanceState +1；0 = 从未推进） */
    long stateVersion();

    /**
     * 推进能量状态（求解完成后调用）。
     *
     * @param va     端口 A 相量（null = 无求解值，跳过）
     * @param vb     端口 B 相量（null = 无求解值，跳过）
     * @param freqHz 网络频率（Hz）
     * @param dt     伪时域步长（s，固定节拍）
     * @param mode   当前求解器类型（REAL_DC / COMPLEX_AC）
     * @return true = 状态变化导致电学参数变化，下轮需要重解（false = 状态平稳）
     */
    boolean advanceState(Complex va, Complex vb, double freqHz, double dt, SolveMode mode);

    /** 当前储能（J）；非储能元件返回 0 */
    double storedEnergy();
}
