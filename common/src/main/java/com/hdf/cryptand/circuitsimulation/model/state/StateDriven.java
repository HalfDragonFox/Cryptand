package com.hdf.cryptand.circuitsimulation.model.state;

import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;

/**
 * 状态推进统一接口（2026-08-20 用户要求：全部电气模型抽象为最基础模型，
 * 新增模型无需新增计算节点；统一接口带求解器类型参数，统一名称）。
 * <p>
 * 任何【求解后需要推进状态】的模型（温度/电荷/机械转速/应力/磁场/化学
 * SOC…）实现本接口，由统一的计算节点 {@code StateNode} 遍历调用——
 * 不需要为每种模型编写专门的节点。
 * <p>
 * 接口参数 {@link SolveMode} = 当前求解器类型（REAL_DC 实数时域 /
 * COMPLEX_AC 复数相量）——模型据此选择推进方式（如温度固定节拍 vs
 * 真实时间；电荷硬积分 vs 动力学松弛）。
 * <p>
 * 与 {@link com.hdf.cryptand.circuitsimulation.model.energy.EnergyState} 的关系：
 * EnergyState 是"带能量状态"的元件接口（含 stateVersion/storedEnergy），
 * 本接口是其【超集】——任何 EnergyState 都可作为 StateDriven，但反过来
 * 不一定（纯机械转子/应力没有"储能"概念）。StateNode 统一按本接口推进。
 */
public interface StateDriven {

    /**
     * 求解后推进状态（温度/电荷/转速/应力等）。
     *
     * @param va     端口 A 相量（null = 无求解值，跳过）
     * @param vb     端口 B 相量（null = 无求解值，跳过）
     * @param freqHz 网络频率（Hz）
     * @param dt     伪时域步长（s）
     * @param mode   当前求解器类型（REAL_DC / COMPLEX_AC）
     * @return true = 状态变化导致电学参数变化（下轮需重解）
     */
    boolean advanceState(Complex va, Complex vb, double freqHz, double dt, SolveMode mode);

    /**
     * 重置状态（网络重建/世界切换/设备复位时调用）。
     *
     * 2026-09-13 用户："统一基类只需要保留计算接口即可" —— 因此本接口【只有】
     * 计算（advanceState）与重置（reset）两项：不塞类型标识、不塞处理器注册、
     * 不塞序列化。
     *   · 不塞 type()：推进按【数量分块并行】，不做类型分发，类型无关紧要；
     *   · 不塞 snapshot()/restore()：状态量少且是标量（温度/电荷/转速），
     *     持久化留在各 *Store 层（DeviceThermalStore / CapacitorStateStore /
     *     MotorStateStore），那里已经解决"主线程读 / 引擎写"的线程边界；
     *     塞进基类反而要给每个模型开一条序列化路径。
     * 默认空实现 —— 已有实现类（ThermalModel/DynamicsModel/EnergyModel）
     * 各自已有 reset()，会自然覆写本方法。
     */
    default void reset() {
    }
}
