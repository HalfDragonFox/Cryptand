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
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ===== 恒速电机【完全接管】（2026-08-25 用户：全部由 BE 电机基类接管） =====
 *
 * 原版 ConstantSpeedMotorBlockEntity.tick()：
 *   applyPower(coil) → avgSpeed += calculateSpeed(V²/R·τ) × signum(I)
 * lazyTick：avgSpeed/5 × capacity → generatedSU → updateGeneratedRotation
 * getGeneratedSpeed：|generatedSU|≥64 ? scrollValue×sign(generatedSU)×convertToDirection
 *                   : 0（恒速专用：转速由滚轮设定，capacity 作阈值）
 *
 * 本 mixin @Overwrite 覆盖 tick/lazyTick/getGeneratedSpeed：
 *   - 原版电气算法完全不执行；Create 网络管理（super.tick）保留；
 *   - 引擎值直写：generatedSU ← 引擎应力（Create 显示）；generatedSpeed ←
 *     引擎 rpm（getGeneratedSpeed 直接读它，不再用 scrollValue）；
 *   - applyPower 取消（温度由引擎管）。
 *
 * ⚠ scrollValue 是恒速电机用户滚轮设定值（原版限速）——引擎侧 MotorAssembler
 * 已读取 speedRPM 作为目标转速（applySpecs），此处不再用 scrollValue 限速。
 */
@Mixin(targets = {
        "org.patryk3211.powergrid.kinetics.motor.ConstantSpeedMotorBlockEntity"
}, remap = false)
@org.spongepowered.asm.mixin.Implements(
        @org.spongepowered.asm.mixin.Interface(
                iface = ICryptandCircuitBe.class,
                prefix = "cryptand$"))
public abstract class ConstantSpeedMotorTakeoverMixin extends GeneratingKineticBlockEntity
        implements ICryptandCircuitBe {

    /* ===== 2026-08-26 B 方案：@Shadow 直读引擎写回字段（替换高频反射） ===== */
    @Shadow(remap = false) private float generatedSU;
    @Shadow(remap = false) private float load;

    /* ===== 2026-08-26 接口通用数据通路：转发给电机解析器类（非 default） ===== */
    @Override
    public void cryptandOnEngineMessage(Object m) {
        try {
            BeMessageParser b = BeMessageParser.cache((BlockEntity) (Object) this);
            if (b != null) b.onEngineMessage(m);
        } catch (Throwable ignored) {
        }
    }

    protected ConstantSpeedMotorTakeoverMixin(BlockEntityType<?> type, BlockPos pos,
                                              BlockState state) {
        super(type, pos, state);
    }

    static {
        CryptandNeoForge.WAF_LOGGER.debug(
                "[MotorTakeover] mixin class loaded (ConstantSpeedMotorBlockEntity)");
    }

    /** @Overwrite tick：原版公式（2026-09-12 用户："原版三种电机效果和原版类似，
     *  即开即停，输出固定应力以及转速，不会有反馈，总之参考原版代码"）。
     *  原版：avgSpeed += calculateSpeed(V²/R, torque) × sign(I)；自管模式下原版
     *  coil 电压不可用 → 用引擎设备电流代入同一公式（P = I²·R）。 */
    @org.spongepowered.asm.mixin.Overwrite
    public void tick() {
        try {
            super.tick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverCS.tick.super", e);
        }
        // 2026-09-13：逻辑已搬入自有 BE（委派式迁移）——@Overwrite 仍需保留以便调用
        // super（GeneratingKineticBlockEntity.tick），故此处转发到 BE 内实现。
        if ((Object) this instanceof com.hdf.cryptand.neoforge.powergrid.motor
                .CryptandConstantSpeedMotorBE be) {
            be.cryptandMotorTick();
            takeover$diag();
            return;
        }
        // ⚠ 2026-09-13：原版电气累加【已移除】——转速的唯一真源是引擎侧
        //   VanillaMotorModel（组装器建的机械模型，每轮算完经 ROTOR_SPEED
        //   消息下发）。在主线程再累加一次 avgSpeed 会与消息写入互相覆盖
        //   （avgSpeed 语义 = rpm×5，被再累加后 lazyTick 折算就错）。
        takeover$diag();
    }

    /** @Overwrite lazyTick：原版语义 —— newSpeed = clamp(avgSpeed/5, ±maxRPM)，
     *  generatedSU = newSpeed × capacity → updateGeneratedRotation（即开即停）。 */
    @org.spongepowered.asm.mixin.Overwrite
    public void lazyTick() {
        try {
            super.lazyTick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverCS.lazyTick.super", e);
        }
        if ((Object) this instanceof com.hdf.cryptand.neoforge.powergrid.motor
                .CryptandConstantSpeedMotorBE be) {
            be.cryptandMotorLazyTick();
            return;
        }
        try {
            // 原版 AVERAGING_TICKS = 5
            float avg = MotorTakeoverBase.readFloat(this, "avgSpeed");
            MotorTakeoverBase.setField(this, "avgSpeed", 0f);
            int newSpeed = (int) (avg / 5.0f);
            int maxRpm = 256;
            try {
                maxRpm = org.patryk3211.powergrid.PowerGrid.maxRPM();
            } catch (Throwable ignored) {
            }
            if (newSpeed > maxRpm) newSpeed = maxRpm;
            if (newSpeed < -maxRpm) newSpeed = -maxRpm;
            int capacity = 1;
            try {
                capacity = (int) com.simibubi.create.api.stress.BlockStressValues
                        .getCapacity(getBlockState().getBlock());
            } catch (Throwable ignored) {
            }
            float gsu = newSpeed * (float) capacity;
            if (Math.abs(MotorTakeoverBase.readFloat(this, "generatedSU") - gsu) > 0.01f) {
                MotorTakeoverBase.setField(this, "generatedSU", gsu);
                try {
                    var g = (com.simibubi.create.content.kinetics.base
                            .GeneratingKineticBlockEntity) (Object) this;
                    if (g.hasNetwork()) {
                        g.updateGeneratedRotation();
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverCS.lazyTick.vanilla", e);
        }
    }

    /** getGeneratedSpeed 覆写：原版语义 —— |generatedSU| ≥ 64 时输出【滚轮设定转速】
     *  （方向取 generatedSU 符号），否则 0（即开即停：通电转、断电停）。
     *  原版：generatedSU = clamp(avgSpeed/5, ±maxRPM) × capacity，转速由 scrollValue 设定。 */
    @org.spongepowered.asm.mixin.Overwrite
    public float getGeneratedSpeed() {
        if ((Object) this instanceof com.hdf.cryptand.neoforge.powergrid.motor
                .CryptandConstantSpeedMotorBE be) {
            return be.cryptandGeneratedSpeed();
        }
        try {
            if (((BlockEntity) (Object) this).getLevel() == null) return 0;
            float gsu = MotorTakeoverBase.readFloat(this, "generatedSU");
            if (Math.abs(gsu) < 64f) return 0;
            Object sv = MotorTakeoverBase.getField(this, "scrollValue");
            double set = MotorTakeoverBase.callNumber(sv, "getValue");
            return (float) (set * Math.signum(gsu));
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverCS.getGenSpeed", e);
            return 0;
        }
    }

    @Unique
    private void takeover$diag() {
        try {
            MotorTakeoverBase.diag(this, "MotorTakeoverCS",
                    generatedSU, load); // @Shadow 直读（2026-08-26 B 方案）
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverCS.diag", e);
        }
    }

    @Inject(method = "applyPower", at = @At("HEAD"), cancellable = true)
    private void takeover$noHeat(org.patryk3211.powergrid.electricity.sim
            .AbstractElectricWire wire, CallbackInfo ci) {
        ci.cancel(); // 温度由引擎 ThermalModel 计算
    }
}
