/**
 * ===== Cryptand 自有恒速电机 BE（2026-09-13 基础 BE 架构：具体电机）=====
 *
 * 继承关系（用户设计）：ICryptandCircuitBe（BE 基类，两级消息/爆炸）
 *   └ ICryptandMotorBE（通用电机：ROTOR 协议 + 通用驱动写回 + 可覆写钩子）
 *       └ 本类：extends org.patryk3211.powergrid.kinetics.motor.ConstantSpeedMotorBlockEntity
 *
 * - 继承原版 BE ⇒ NBT/字段/接口全兼容（旧存档零迁移、instanceof 关系不断）
 * - 挂通用电机层 ⇒ 引擎消息走 cryptandOnEngineMessage（通用层）→
 *   cryptandHandleMessage / cryptandApplyRotorMessage（具体层）
 * - 后续把 mixin 里的 tick/lazyTick 行为逐步搬进本类覆写（mixin 只留必要注入）
 */
package com.hdf.cryptand.neoforge.powergrid.motor;

import com.hdf.cryptand.neoforge.powergrid.device.ICryptandMotorBE;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.patryk3211.powergrid.kinetics.motor.ConstantSpeedMotorBlockEntity;

public class CryptandConstantSpeedMotorBE extends ConstantSpeedMotorBlockEntity implements ICryptandMotorBE {

    /** ==================== 行为实现（2026-09-13 从 ConstantSpeedMotorTakeoverMixin 搬入）
     * 原版语义（即开即停 / 固定应力 / 无反馈）：
     *   tick      : avgSpeed += calculateSpeed(I²R, torque) × sign(I)
     *   lazyTick  : generatedSU = clamp(avgSpeed/5, ±maxRPM) × capacity → Create 同步
     *   getGeneratedSpeed : |generatedSU| ≥ 64 ? scrollValue × sign(gsu) : 0
     * mixin 的 @Overwrite 因需调 super（GeneratingKineticBlockEntity.tick）而必须保留，
     * 故改为【委派】：overwrite 里调用本类方法，逻辑集中在这里（可覆写扩展）。
     * ================================================================================== */

    /**
     * tick 主体 —— 主线程【不计算】（2026-09-13 用户："每次异步线程计算完成转速后
     * 发送消息更新到主线程"）。
     *
     * 转速唯一真源 = 引擎侧 {@code VanillaMotorModel}（组装器建的机械模型）：每轮
     * 求解后算 rpm → EngineBus.post(ROTOR_SPEED) → 通用层默认实现写
     * {@code avgSpeed = rpm×5} / {@code generatedSpeed = rpm}；本类的
     * {@link #cryptandMotorLazyTick()} 再折算成
     * {@code generatedSU = (avgSpeed/5) × capacity}（原版"是否通电"判据 +
     * Create 应力显示），{@link #cryptandGeneratedSpeed()} 用滚轮设定值输出转速。
     *
     * 旧版在此自算累加 avgSpeed → 与消息路径【双写同一字段】互相覆盖，已移除。
     */
    public void cryptandMotorTick() {
        // 有意留空：转速来自引擎消息（见方法注释）
    }

    /**
     * lazyTick 主体 —— 有意留空（2026-09-13）。
     *
     * 原实现在这里 `generatedSU = clamp((int)(avgSpeed/5), ±maxRPM) × capacity`：
     *   · 与引擎消息【双写同一字段】（applyValues 也写 generatedSU）——旧版
     *     "转速只剩 1/5" 就是这条路径与消息互相覆盖造成的；
     *   · 每 tick 反射读 avgSpeed、查 Create 应力容量表 —— 稳态纯浪费。
     * 现在：转速与应力全部由引擎每轮算好经 ROTOR_SPEED 消息下发，
     * `applyValues` 写入 generatedSpeed/generatedSU 并（脏标记）触发
     * `updateGeneratedRotation` —— 主线程零计算、零重复传播。
     */
    public void cryptandMotorLazyTick() {
        // 有意留空：见方法注释
    }

    /** getGeneratedSpeed 主体：原版语义（转速 = 滚轮设定值，generatedSU 仅作通电判据） */
    public float cryptandGeneratedSpeed() {
        try {
            if (getLevel() == null) return 0;
            float gsu = cryptandReadFloat("generatedSU");
            if (Math.abs(gsu) < 64f) return 0;
            Object sv = cryptandField("scrollValue");
            double set = com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase
                    .callNumber(sv, "getValue");
            return (float) (set * Math.signum(gsu));
        } catch (Throwable ignored) {
            return 0;
        }
    }



    public CryptandConstantSpeedMotorBE(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }
}
