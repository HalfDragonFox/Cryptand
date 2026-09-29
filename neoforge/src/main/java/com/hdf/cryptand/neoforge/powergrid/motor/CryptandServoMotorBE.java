/**
 * ===== Cryptand 自有伺服电机 BE（2026-09-13 基础 BE 架构：具体电机）=====
 *
 * 继承关系（用户设计）：ICryptandCircuitBe（BE 基类，两级消息/爆炸）
 *   └ ICryptandMotorBE（通用电机：ROTOR 协议 + 通用驱动写回 + 可覆写钩子）
 *       └ 本类：extends org.patryk3211.powergrid.kinetics.servo.ServoBlockEntity
 *
 * - 继承原版 BE ⇒ NBT/字段/接口全兼容（旧存档零迁移、instanceof 关系不断）
 * - 挂通用电机层 ⇒ 引擎消息走 cryptandOnEngineMessage（通用层）→
 *   cryptandHandleMessage / cryptandApplyRotorMessage（具体层）
 * - 后续把 mixin 里的 tick/lazyTick 行为逐步搬进本类覆写（mixin 只留必要注入）
 */
package com.hdf.cryptand.neoforge.powergrid.motor;

import com.hdf.cryptand.neoforge.powergrid.device.ICryptandMotorBE;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceVoltageStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.kinetics.servo.ServoBlockEntity;

public class CryptandServoMotorBE extends ServoBlockEntity implements ICryptandMotorBE {

    /** ==================== 行为实现（2026-09-13 从 ServoMotorTakeoverMixin 搬入）
     * 原版算法（自管电气输入）：
     *   ① avgSpeed += calculateSpeed(I²R, torque)
     *   ② ctrlTarget = round(clamp(控制线电压/5×360, ±360))（端子 2 − 端子 1，取 DeviceVoltageStore）
     *   ③ rotation = currentTarget − currentAngle；movingTicks 倒计时到 0 → 停机
     *   ④ generatedSpeed = clamp(rotation/0.05/6, ±maxSpeed)；currentAngle = currentTarget；
     *      movingTicks = |rotation / convertToAngular(gs)| + 5
     *   lazyTick：maxSpeed = min(avgSpeed/5, 32)；getGeneratedSpeed = convertToDirection(...)
     * mixin 的 @Overwrite 必须保留（需调 super），故改为【委派】到本类实现。
     * ================================================================================== */

    public void cryptandMotorTick() {
        try {
            double torque = cryptandCallNumber("torque");
            double rIdle = com.hdf.cryptand.neoforge.powergrid.device.motor
                    .MotorTakeoverBase.callNumberStr(this, "resistance", "idle");
            if (rIdle <= 0) rIdle = cryptandCallNumber("resistance");
            double i = cryptandMotorCurrent(Math.max(rIdle, 1e-9));
            if (torque > 0 && i != 0) {
                double pw = i * i * Math.max(rIdle, 1e-9);
                com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase.addFloat(
                        this, "avgSpeed", pw / torque
                                * com.hdf.cryptand.neoforge.powergrid.device.motor
                                        .MotorTakeoverBase.CONVERSION_CONSTANT);
            }
            double ctrlV = com.hdf.cryptand.neoforge.powergrid.state.DeviceVoltageStore
                    .readBetween(getBlockPos(), 2, 1);
            int ctrlTarget = (int) Math.round(Math.max(-360.0, Math.min(360.0,
                    ctrlV / 5.0 * 360.0)));
            if (cryptandReadInt("prevTarget") == ctrlTarget) {
                cryptandSetField("currentTarget", ctrlTarget);
            }
            cryptandSetField("prevTarget", ctrlTarget);
            int rotation = cryptandReadInt("currentTarget") - cryptandReadInt("currentAngle");
            if (Math.abs(rotation) < 1) rotation = 0;
            int moving = cryptandReadInt("movingTicks");
            if (moving > 0) {
                moving--;
                cryptandSetField("movingTicks", moving);
                if (moving == 0) {
                    cryptandSetField("generatedSpeed", 0f);
                    cryptandSyncNetwork();
                }
                return;
            }
            if (rotation != 0) {
                double maxSpeed = cryptandReadFloat("maxSpeed");
                double gs = Math.max(-maxSpeed, Math.min(maxSpeed, rotation / 0.05 / 6.0));
                cryptandSetField("generatedSpeed", (float) gs);
                cryptandSyncNetwork();
                cryptandSetField("currentAngle", cryptandReadInt("currentTarget"));
                double ang = Math.abs(convertToAngular((float) gs));
                cryptandSetField("movingTicks",
                        (int) (Math.abs(rotation) / Math.max(ang, 1e-6)) + 5);
            }
        } catch (Throwable ignored) {
        }
    }

    public void cryptandMotorLazyTick() {
        try {
            float avg = cryptandReadFloat("avgSpeed");
            cryptandSetField("avgSpeed", 0f);
            float maxS = Math.min(avg / 5.0f, 32.0f);
            if (maxS < 0) maxS = 0;
            cryptandSetField("maxSpeed", maxS);
            if (maxS == 0f && cryptandReadFloat("generatedSpeed") != 0f) {
                cryptandSetField("generatedSpeed", 0f);
                cryptandSyncNetwork();
            }
        } catch (Throwable ignored) {
        }
    }

    public float cryptandGeneratedSpeed() {
        try {
            return convertToDirection(cryptandReadFloat("generatedSpeed"),
                    getBlockState().getValue(org.patryk3211.powergrid.kinetics.motor
                            .ElectricMotorBlock.FACING));
        } catch (Throwable ignored) {
            return cryptandReadFloat("generatedSpeed");
        }
    }

    private void cryptandSyncNetwork() {
        try {
            if (hasNetwork()) updateGeneratedRotation();
        } catch (Throwable ignored) {
        }
    }


    /** ===== 原版电机的【寄生接管】：引擎 ROTOR 消息不参与（2026-09-13）=====
     * 本类继承的是"原版方块"的 BE，机械输出由自有公式 cryptandMotorTick() 用
     * 【自管电流】算出（即开即停/固定应力/无反馈）。若这里再写一遍 avgSpeed，
     * 会与公式路径互相覆盖（旧实测：avgSpeed 被写成引擎 rpm，lazyTick 再 /5
     * → 转速只剩 1/5）。引擎消息只用于诊断。
     * ⚠ 与之相对：通用电机层 ICryptandMotorBE 的默认实现【保留】引擎权威值写回
     *   —— 自研电机（SinglePhaseAsyncMotorBlockEntity 等）靠它转动，不可动。 */
    @Override
    public void cryptandApplyRotorMessage(double dir, double rpm, double stress, double tempC) {
        // 有意留空：见上
    }

    public CryptandServoMotorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
}
