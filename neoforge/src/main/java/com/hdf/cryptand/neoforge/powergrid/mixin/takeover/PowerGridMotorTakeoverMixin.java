package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.device.ICryptandCircuitBe;
import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser;
import com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ===== PowerGrid 普通电机【完全接管】（2026-09-13）=====
 *
 * 用户方针：
 *   ① "原版内容全部放弃，全部使用我们的自定义的 BE 相关内容"；
 *   ② "电机类的组装器应该带机械相关的模型，计算是在上面完成的"；
 *   ③ "每次异步线程计算完成转速后发送消息更新到主线程"；
 *   ④ "组装器绑定时直接绑定对应 BE 类引用"（消息携带引用）。
 *
 * 因此本 mixin 的职责只剩两条：
 *   1) @Overwrite tick/lazyTick —— 把 PowerGrid 原版算法体【整体丢弃】，只保留
 *      Create 的 kinetic 机制（@Overwrite 的 super 指向目标的父类
 *      GeneratingKineticBlockEntity）；
 *   2) 委派给自有 BE（CryptandElectricMotorBE）做诊断 —— 转速【不在主线程计算】，
 *      由引擎侧组装器建的 VanillaMotorModel 算好后经 ROTOR_SPEED 消息下发。
 *
 * 数据流（唯一真源 = 引擎）：
 *   MotorAssembler 建 VanillaMotorModel（纯电阻 + 原版公式机械侧 + BE 引用绑定）
 *     → 引擎每轮求解 lossPower 钩子：I = |Vab|/R → rpm = I²R/torque × 60π/2
 *     → EngineBus.post(ROTOR_SPEED, pos, beRef, model, {radS, rpm})
 *     → 主线程 EngineBus.process → cryptandOnEngineMessage
 *     → ICryptandMotorBE.cryptandApplyRotorMessage（默认=引擎权威值）
 *     → MotorTakeoverBase.applyValues：avgSpeed = rpm×5、generatedSpeed = rpm
 *       → updateGeneratedRotation（Create 网络传播）。
 *
 * ⚠ 2026-08-26 不用 @Implements(@Interface(prefix="cryptand$"))：本类已有 cryptand$
 *   前缀方法会被 prefix 规则误解析 → InValidMixinException。改用纯
 *   implements ICryptandCircuitBe + 同名 @Override。
 */
@Mixin(targets = {
        "org.patryk3211.powergrid.kinetics.motor.ElectricMotorBlockEntity"
}, remap = false)
public abstract class PowerGridMotorTakeoverMixin extends GeneratingKineticBlockEntity
        implements ICryptandCircuitBe {

    protected PowerGridMotorTakeoverMixin(BlockEntityType<?> type, BlockPos pos,
                                          BlockState state) {
        super(type, pos, state);
    }

    static {
        CryptandNeoForge.WAF_LOGGER.debug(
                "[MotorTakeover] mixin class loaded (ElectricMotorBlockEntity)");
    }

    /* ===== 接口通用数据通路：转发给电机解析器类 ===== */
    @Override
    public void cryptandOnEngineMessage(Object m) {
        try {
            BeMessageParser b = BeMessageParser.cache((BlockEntity) (Object) this);
            if (b != null) b.onEngineMessage(m);
        } catch (Throwable ignored) {
        }
    }

    /** @Overwrite tick：原版电气算法体【整体放弃】（PowerGrid 一行都不执行），
     *  只保留 Create 的 tick（super）+ 自有 BE 的诊断委派。 */
    @org.spongepowered.asm.mixin.Overwrite
    public void tick() {
        try {
            super.tick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeover.tick.super", e);
        }
        if ((Object) this instanceof com.hdf.cryptand.neoforge.powergrid.motor
                .CryptandElectricMotorBE be) {
            be.cryptandMotorTick();
        }
    }

    /** @Overwrite lazyTick：原版在此把 avgSpeed/5 折算成 generatedSpeed（并走
     *  applyNewSpeed → getOrCreateNetwork() 的 NPE 路径）——整体放弃；
     *  转速已由引擎消息写入（avgSpeed = rpm×5 → 自有 BE 的 lazyTick 折算后仍是
     *  同一个 rpm，幂等无副作用）。 */
    @org.spongepowered.asm.mixin.Overwrite
    public void lazyTick() {
        try {
            super.lazyTick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeover.lazyTick.super", e);
        }
        if ((Object) this instanceof com.hdf.cryptand.neoforge.powergrid.motor
                .CryptandElectricMotorBE be) {
            be.cryptandMotorLazyTick();
        }
    }

    /** 原版 applyPower（电气域热计算）取消：温度由引擎 ThermalModel 统一计算。 */
    @Inject(method = "applyPower", at = @At("HEAD"), cancellable = true)
    private void cryptand$noHeat(org.patryk3211.powergrid.electricity.sim
            .AbstractElectricWire wire, CallbackInfo ci) {
        ci.cancel();
    }
}
