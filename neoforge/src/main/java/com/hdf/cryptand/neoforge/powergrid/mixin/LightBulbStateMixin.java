/**
 * ===== 灯座灯泡点亮/烧断（2026-08-22 用户需求："接上灯泡后不会亮也不会烧毁"） =====
 *
 * 自管模式（完全接管）下，原版 LightFixtureBlockEntity.electricalTick 用
 * filament（SwitchedWire）功率调 LightBulbState.applyPower——但自管求解只写
 * 自管宿主，原版 filament 节点电压/功率=0 → applyPower(0) → 灯泡既不亮也不烧。
 *
 * 本 Mixin 在 LightBulbState.applyPower 修改功率参数：从 Cryptand【设备电流
 * 缓存】（DeviceCurrent，后台求解 round 写入）读灯丝电流 I，替换功率为
 * P = I²·R（R = 灯泡当前电阻 resistance()）——温度积分/亮度模型/过热烧断
 * 全部复用原版（applyPower 内部 temperature / overheatTemperature / burn）。
 *
 * 无 Cryptand 电流（未接线/未建模）→ 保持原逻辑（0 功率）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceCurrent;
import net.minecraft.core.BlockPos;
import org.patryk3211.powergrid.electricity.light.fixture.LightFixtureBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(targets = "org.patryk3211.powergrid.electricity.light.bulb.LightBulbState",
       remap = false)
public abstract class LightBulbStateMixin {

    /** 灯座（applyPower 传入的电源来自它） */
    @Shadow(remap = false) private LightFixtureBlockEntity fixture;

    /** 灯泡当前电阻（Ω；温度相关，原版 resistance()） */
    @Shadow(remap = false) public abstract float resistance();

    /**
     * 把原版传入的灯丝功率（自管下恒 0）替换为 Cryptand 电流计算的功率 I²R。
     */
    @ModifyVariable(method = "applyPower", at = @At("HEAD"), ordinal = 0,
                    argsOnly = true, remap = false)
    private double cryptand$replaceWirePower(double power) {
        try {
            if (fixture == null) return power;
            BlockPos pos = fixture.getBlockPos();
            double i = DeviceCurrent.read(pos);
            if (Math.abs(i) < 1e-4) return power; // 无 Cryptand 电流 → 原逻辑
            double r = resistance();
            if (r <= 0) return power;
            return i * i * r; // P = I²·R（灯丝真实功率）
        } catch (Throwable ignored) {
            return power;
        }
    }
}
