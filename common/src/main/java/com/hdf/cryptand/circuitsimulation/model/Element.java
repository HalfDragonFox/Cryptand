package com.hdf.cryptand.circuitsimulation.model;

import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.FloatMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 电路元件接口。DC/AC 共用同一抽象：
 * <ul>
 *   <li>stampRealAt —— 时域/直流实数 MNA 装配（含电容电感的伴随模型，dt 为步长，t 为仿真时间）</li>
 *   <li>stampReal —— 无时间版本，默认由 stampRealAt 委托；波形源等时间相关元件覆写 stampRealAt</li>
 *   <li>stampComplex —— 交流相量复数 MNA 装配（omega=2πf，f 为网络频率）</li>
 *   <li>stamp*Float —— float 求解器版本（2026-08-12 最小影响 float 化）</li>
 * </ul>
 * 这是"可替换计算核心"的关键：求解器只依赖此接口，不关心具体元件。
 */
public interface Element {
    int nodeA();
    int nodeB();

    /** 时域/直流：把导纳与源贡献 stamp 进实数矩阵（无仿真时间版本，默认实现为空） */
    default void stampReal(MnaBuilder m, double dt) {}

    /**
     * 时域/直流：带仿真时间的 stamp。默认委托给 {@link #stampReal}；
     * 波形源（依赖当前时间 t 求瞬时值）覆写此方法。
     */
    default void stampRealAt(MnaBuilder m, double dt, double t) { stampReal(m, dt); }

    /** 交流相量：把复数导纳与源贡献 stamp 进复数矩阵 */
    default void stampComplex(ComplexMnaBuilder m, double omega) {}

    /** 求解完成后更新元件内部历史状态（电容电压/电感电流等）。va/vb 为该步两端电压。 */
    default void commit(double va, double vb, double dt) {}

    // ===== float 求解器支持（2026-08-12 最小影响 float 化） =====
    // 默认不支持（supportsFloat*=false）+ float stamp 空实现。实现 float
    // stamp 的元件返回 true 并覆盖对应方法；网络含任一不支持元件 →
    // Solvers 自动回退 double 求解器（行为完全不变）。
    // 魔法数字等恒定参数由 double 计算后转 float（见各元件 stampFloat 实现）。

    /** 是否支持 float 实数（DC/时域）stamp。 */
    default boolean supportsFloatReal() { return false; }

    /** 是否支持 float 复数（AC 相量）stamp。 */
    default boolean supportsFloatComplex() { return false; }

    /** float 版时域/直流 stamp（带仿真时间）。默认空实现（不支持时回退 double）。 */
    default void stampRealFloat(FloatMnaBuilder m, double dt, double t) {}

    /** float 版交流相量 stamp。默认空实现（不支持时回退 double）。 */
    default void stampComplexFloat(FloatComplexMnaBuilder m, double omega) {}

    /** float 版状态提交（默认委托 double commit）。 */
    default void commitFloat(float va, float vb, double dt) { commit(va, vb, dt); }

    /** 供序列化/分布式调度使用的元件类型 */
    ElementType type();

    /**
     * 是否为【有源元件】（电压源/电流源/波形源/受控源等）。2026-08-13 用户
     * 架构要求：基本接口提供基础判断函数，源元件通过继承重写返回 true——
     * 用于虚拟/EDA/拓扑判断（如：源组件绑定的模型未加载 → 临时不加入运算）。
     */
    default boolean isSource() { return false; }

    /**
     * 是否【非线性/带能量状态】（2026-08-15 用户架构）：非线性器件计入网络
     * nonlinearCount → 该网络进入固定节拍伪时域求解；全线性网络走正常路径
     * （事件驱动、缓存命中零重解）。默认 false（纯线性无状态元件）；电容/电感
     * 等带能量状态的元件覆写返回 true。
     */
    default boolean isNonlinear() { return false; }

    /** 波形类型（序列化到 C++/集群时识别用）；非波形源返回 DC */
    default WaveformType waveform() { return WaveformType.DC; }

    // ===== 多频叠加（2026-08-13 PLC 核心） =====
    // 线性叠加定理：多频网络对每个频率独立求解（同一矩阵结构不同 omega），
    // 各频率响应叠加。源声明其工作频率；求解器按目标频率选择【完整注入】或
    // 【仅被动】（源对非工作频率表现为短路/内阻）。

    /** 源频率（Hz）：0 = DC / 跟随网络主导频率（单频网络无条件注入）；&gt;0 = 固定频率（多频载波源） */
    default double sourceFrequency() { return 0; }

    /** 该元件是否在目标频率下【完整注入】（源匹配才 true；无源元件恒 true）。
     *  跟随源（sourceFrequency=0）只在主导频率 true（工频源不注入载波频率）。
     *  @param omega 目标角频率 2πf
     *  @param dominantFreq 网络主导频率（工频，Hz） */
    default boolean activeAt(double omega, double dominantFreq) {
        return true;
    }

    /** 仅被动部分（源对非工作频率的短路/内阻；无源元件默认 = 完整 stampComplex） */
    default void stampComplexPassive(ComplexMnaBuilder m, double omega) {
        stampComplex(m, omega);
    }

    /** 序列化参数（供 NetworkSnapshot 发送到 C++/GPU 等） */
    default double[] params() { return new double[0]; }
}
