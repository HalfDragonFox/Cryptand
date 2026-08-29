package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.device.ICryptandCircuitBe;
import com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser;
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
        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.debug(
                "[MotorTakeover] mixin class loaded (ConstantSpeedMotorBlockEntity)");
    }

    /** @Overwrite tick：完全禁原版算法（Create 网络管理保留在 super.tick）。 */
    @org.spongepowered.asm.mixin.Overwrite
    public void tick() {
        try {
            super.tick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverCS.tick.super", e);
        }
        try {
            MotorTakeoverBase.applyFromStore((BlockEntity) (Object) this);
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverCS.tick.store", e);
        }
        takeover$diag();
    }

    /** @Overwrite lazyTick：禁原版 avgSpeed/5×capacity 累积；引擎值直写。 */
    @org.spongepowered.asm.mixin.Overwrite
    public void lazyTick() {
        try {
            super.lazyTick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverCS.lazyTick.super", e);
        }
        try {
            MotorTakeoverBase.applyFromStore((BlockEntity) (Object) this);
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverCS.lazyTick.store", e);
        }
    }

    /** getGeneratedSpeed 覆写：引擎 rpm（带方向）。恒速电机没有 generatedSpeed
     * 字段（原版由 generatedSU+scrollValue 计算）→ 直接读引擎存储权威值；
     * 无引擎值 → 回退 0。 */
    @org.spongepowered.asm.mixin.Overwrite
    public float getGeneratedSpeed() {
        try {
            if (((BlockEntity) (Object) this).getLevel() == null) return 0;
            double[] s = com.hdf.cryptand.neoforge.powergrid.adapter
                    .MotorStateStore.get(((BlockEntity) (Object) this).getBlockPos());
            if (s == null || s.length < 1) return 0;
            double omega = s[0];
            return (float) (Math.abs(omega) * 60.0 / (2.0 * Math.PI))
                    * (omega >= 0 ? 1f : -1f);
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
