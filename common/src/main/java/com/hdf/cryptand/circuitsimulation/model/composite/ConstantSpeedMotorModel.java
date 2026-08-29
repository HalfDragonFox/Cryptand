package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * 恒速电机模型（2026-08-27 用户：电机模型拆三份——普通/恒速/伺服）。
 * <p>
 * 基础壳：构造固化规格 + {@code constantSpeed=true}（EMF 系数按用户经验
 * (1 − 1/k)·V_rated 配置，见 {@link #setSpecs}），公式先继承统一现实 DC 链。
 *
 * <h3>⚠ TODO（2026-08-27，待细化）</h3>
 * 恒速电机语义：转速=用户设置、功率=应力×设置转速。当前首次拆分先跑通继承
 * 公式验证（与普通同链，差异仅规格/EMF 系数），后续在此类中独立调整恒速
 * 公式（不牵动普通/伺服）。
 */
public class ConstantSpeedMotorModel extends ElectroMachineModel {

    /**
     * 恒速电机：额定 230V、最大应力 16384、设置转速先默认 256RPM（BE 后续
     * 上报设置转速再调 {@link #setSetSpeedRPM}）。
     */
    public ConstantSpeedMotorModel(int a, int b, int x, double resistance, double inductance,
                                   ThermalModel thermal, EnergyModel energy,
                                   boolean generatorMode, double emfVoltage) {
        super(a, b, x, resistance, inductance, thermal, energy, generatorMode, emfVoltage);
        setSpecs(230.0, 258.0, 16384.0, 256.0, true, 256.0);
    }
}