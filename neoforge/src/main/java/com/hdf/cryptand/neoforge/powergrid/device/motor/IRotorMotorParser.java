package com.hdf.cryptand.neoforge.powergrid.device.motor;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * PowerGrid「真转子」（IRotor）桥（2026-08-24，继承 BE 基类）：
 * 组装器引擎转速 → BE 实际转子跟随（PowerGrid IRotor 无 setter，只能
 * applyTickForce 速度差）；回读 getAngularVelocityRadians。
 */
public final class IRotorMotorParser extends BeMessageParser {

    private final org.patryk3211.powergrid.electricity.sim.special.IRotor rotor;

    IRotorMotorParser(BlockEntity be, org.patryk3211.powergrid.electricity.sim.special.IRotor rotor) {
        super(be);
        this.rotor = rotor;
    }

    /** 机电转子协议（引擎→BE [方向, rpm, 应力, 温度]） */
    @Override
    public Protocol protocol() { return Protocol.ROTOR; }

    @Override
    protected void onMessage(Object raw) {
        BeMessage m = BeMessage.of(raw);
        // 处理收到的【纯数据体】——电机协议 [方向, 转速(rpm), 应力] 直接强转：
        // 目标角速度 = 方向 × rpm → 速度差 force 跟随（基类已统一诊断）
        try {
            double dir = Double.isNaN(m.num(0)) ? 1 : m.num(0);
            double rpm = m.num(1);
            double omegaTarget = dir * rpm * (2.0 * Math.PI) / 60.0;
            float current = rotor.getAngularVelocityRadians();
            rotor.applyTickForce((float) (omegaTarget - current) * 2.0f);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public float currentRadS() {
        try { return rotor.getAngularVelocityRadians(); } catch (Throwable ignored) { return 0; }
    }

    @Override
    public double temperatureC() { return 25; }

    @Override
    public void addHeat(double joules) { /* 无温度模型 */ }

    @Override
    public String describe() { return "IRotor"; }
}
