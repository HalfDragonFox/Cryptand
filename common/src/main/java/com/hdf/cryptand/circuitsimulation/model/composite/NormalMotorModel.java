package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * 普通电机模型（2026-08-27 用户：电机模型拆三份——普通/恒速/伺服，先做普通）。
 * <p>
 * 规格（2026-08-23 用户）：额定 230V → 最大转速 256RPM（空载 EMF≈V：
 * E = K·ω，K = 额定电压 / 额定角速度）；最大应力 16384；&gt;258V 过热爆炸。
 * <p>
 * 公式 = 统一现实 DC 物理链（继承 {@link ElectroMachineModel}，2026-08-27）：
 * <pre>
 *   1) EMF = K_E·ω                反电动势（随转速升高）
 *   2) I   = (V_ab − EMF)/R       电枢电流（启动大 → 升速后回落）
 *   3) T_em = K_T·I               电磁转矩
 *   4) dω/dt = (T_em − T_load − b·ω)/J   惯性方程（断电 T_em=0 → 摩擦滑行）
 *   5) P = T_em·ω；σ = P/ω_r      机械功率 → Create 应力输出
 * </pre>
 */
public class NormalMotorModel extends ElectroMachineModel {

    /**
     * 普通电机：构造固化规格（230V / 最大应力 16384 / 额定 256RPM / 非恒速）。
     * 历史背景：此前生产代码从不调用 {@link #setSpecs}，字段全走默认值；显式
     * 固化一次消除隐式依赖，语义清晰、可独立调参。
     */
    public NormalMotorModel(int a, int b, int x, double resistance, double inductance,
                            ThermalModel thermal, EnergyModel energy,
                            boolean generatorMode, double emfVoltage) {
        super(a, b, x, resistance, inductance, thermal, energy, generatorMode, emfVoltage);
        setSpecs(230.0, 258.0, 16384.0, 256.0, false, 0);
    }
}