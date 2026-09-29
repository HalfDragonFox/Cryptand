package com.hdf.cryptand.engine;

import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * ===== 组装器【计算与返回】接口（2026-09-12 用户）=====
 *
 * <p>用户："组装器提供组装器接口基类，提供计算与返回等接口"——本接口即"计算与返回"
 * 那部分能力：组装器（或组装器组）实现它，上层（引擎后处理 / 温度推进 / 诊断 /
 * 温度计）就能统一向"逻辑设备"索要损耗与温度，而不必知道它内部由几个块/几个
 * 成员组装器组成。
 *
 * <p>与 {@link Assembler} 的关系：{@code Assembler} 是组装/绑定接口（build/bind），
 * 本接口是可选的【计算】扩展——普通组装器按需实现，{@link AssemblerGroup}
 * 必然实现并递归聚合成员。
 */
public interface AssemblerComputed {

    /** 当前损耗（平均功率 W）。无损耗模型 → 0。 */
    default double lossPower(Complex va, Complex vb, double omega) {
        return 0;
    }

    /** 当前温度（°C）；无温度模型 → {@link Double#NaN} */
    default double temperatureC() {
        return Double.NaN;
    }
}
