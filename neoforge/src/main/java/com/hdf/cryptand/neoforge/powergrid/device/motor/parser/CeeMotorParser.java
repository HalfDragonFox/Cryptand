package com.hdf.cryptand.neoforge.powergrid.device.motor.parser;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * CEE（george_vi Create Electro Energetics）电机桥（2026-08-24，继承 BE 基类）：
 * 实现相关函数即可接入；与 PowerGrid 电机互不影响。
 * <p>
 * ⚠ 2026-08-24 用户：CEE 兼容已移除（问题丛生）——本桥保留骨架，函数实现
 * 待用户确认后再启用（不再主动写 updateFromNetwork——同款 Create 自动更新
 * 链 NPE 风险；只读回读安全）。
 */
public final class CeeMotorParser extends BeMessageParser {

    CeeMotorParser(BlockEntity be) {
        super(be);
    }

    /** 机电转子协议暂时未启用（CEE 兼容已移除，骨架保留） */
    @Override
    public Protocol protocol() { return Protocol.NONE; }

    @Override
    protected void onMessage(Object raw) {
        BeMessage m = BeMessage.of(raw);
        // 只读（不调 updateFromNetwork：Create 自动更新链上 NPE 风险；基类已诊断）
    }

    @Override
    public float currentRadS() {
        try {
            Object gen = be.getClass().getMethod("getGeneratedSpeed")
                    .invoke(be);
            if (gen instanceof Number n) {
                return n.floatValue() * (float) (2.0 * Math.PI) / 60.0f;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    @Override
    public double temperatureC() { return 25; }

    @Override
    public void addHeat(double joules) { /* 无温度模型 */ }

    @Override
    public String describe() { return "CeeMotor"; }
}
