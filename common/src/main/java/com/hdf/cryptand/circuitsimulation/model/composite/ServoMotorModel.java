package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;

/**
 * 伺服电机模型（2026-08-27 用户：电机模型拆三份——普通/恒速/伺服）。
 * <p>
 * 基础壳：构造固化规格 + {@code servoMode=true}（advanceShaft 内惯量 jI×0.02
 * → 瞬达、无爬升）。公式先继承统一现实 DC 链。
 *
 * <h3>⚠ TODO（2026-08-27，待细化）</h3>
 * 伺服语义：立马开、立马停，无惯性过程。当前先跑通继承公式验证，后续在此
 * 类中独立调整伺服公式（瞬态/点位控制），不牵动普通/恒速。伺服电压规格暂按
 * 45V 上限/1024SU（注释值，待用户确认精确数值）。
 */
public class ServoMotorModel extends ElectroMachineModel {

    /**
     * 伺服电机：设 {@link #setServoMode}（小惯量瞬达）+ 规格（45V 上限 /
     * 1024SU / 额定 256RPM，数值 TODO 待用户确认）。
     */
    public ServoMotorModel(int a, int b, int x, double resistance, double inductance,
                           ThermalModel thermal, EnergyModel energy,
                           boolean generatorMode, double emfVoltage) {
        super(a, b, x, resistance, inductance, thermal, energy, generatorMode, emfVoltage);
        setServoMode(true);
        setSpecs(45.0, 45.0, 1024.0, 256.0, true, 256.0);
    }
}