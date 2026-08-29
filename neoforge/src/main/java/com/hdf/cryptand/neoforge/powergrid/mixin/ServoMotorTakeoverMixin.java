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
 * ===== 伺服电机【完全接管】（2026-08-25 用户：全部由 BE 电机基类接管） =====
 *
 * 原版 ServoBlockEntity.tick()：
 *   applyPower(coil) → avgSpeed += calculateSpeed(coil.power(), torque)
 *   + 角度插值（currentTarget/currentAngle/movingTicks/sequenceContext 精密定位）
 * lazyTick：avgSpeed/5 → maxSpeed(≤32)
 *
 * 本 mixin @Overwrite 覆盖 tick/lazyTick/getGeneratedSpeed：
 *   - 原版电气算法【完全不执行】（上电瞬达/掉电瞬停由引擎 ElectroMachineModel
 *     servoMode 控制）；
 *   - Create 网络管理（super.tick()）保留；
 *   - 引擎值直写：generatedSpeed ← 引擎 rpm（getGeneratedSpeed 读引擎存储）；
 *   - applyPower 取消（温度由引擎 ThermalModel 计算）。
 *
 * ⚠ 伺服电气侧（coil/control 场源、on/off 电阻切换）由引擎接管；原版
 * buildCircuit 仍执行（线圈/控制线创建保留，供引擎建模节点）。
 */
@Mixin(targets = {
        "org.patryk3211.powergrid.kinetics.servo.ServoBlockEntity"
}, remap = false)
@org.spongepowered.asm.mixin.Implements(
        @org.spongepowered.asm.mixin.Interface(
                iface = ICryptandCircuitBe.class,
                prefix = "cryptand$"))
public abstract class ServoMotorTakeoverMixin extends GeneratingKineticBlockEntity
        implements ICryptandCircuitBe {

    /* ===== 2026-08-26 B 方案：@Shadow 直读引擎写回字段（替换高频反射） ===== */
    @Shadow(remap = false) private float generatedSpeed;
    @Shadow(remap = false) private float avgSpeed;

    /* ===== 2026-08-26 接口通用数据通路：转发给电机解析器类（非 default） ===== */
    @Override
    public void cryptandOnEngineMessage(Object m) {
        try {
            BeMessageParser b = BeMessageParser.cache((BlockEntity) (Object) this);
            if (b != null) b.onEngineMessage(m);
        } catch (Throwable ignored) {
        }
    }

    protected ServoMotorTakeoverMixin(BlockEntityType<?> type, BlockPos pos,
                                      BlockState state) {
        super(type, pos, state);
    }

    static {
        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.debug(
                "[MotorTakeover] mixin class loaded (ServoBlockEntity)");
    }

    /** @Overwrite tick：完全禁原版算法（Create 网络管理保留在 super.tick）。 */
    @org.spongepowered.asm.mixin.Overwrite
    public void tick() {
        try {
            super.tick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.tick.super", e);
        }
        try {
            MotorTakeoverBase.applyFromStore((BlockEntity) (Object) this);
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.tick.store", e);
        }
        takeover$diag();
    }

    /** @Overwrite lazyTick：禁原版 avgSpeed/5→maxSpeed 累积；引擎值直写。 */
    @org.spongepowered.asm.mixin.Overwrite
    public void lazyTick() {
        try {
            super.lazyTick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.lazyTick.super", e);
        }
        try {
            MotorTakeoverBase.applyFromStore((BlockEntity) (Object) this);
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.lazyTick.store", e);
        }
    }

    /** getGeneratedSpeed 覆写：引擎 rpm（带方向；伺服没有 generatedSpeed 字段
     * 原版直接返回字段——实际有，由引擎写）。 */
    @org.spongepowered.asm.mixin.Overwrite
    public float getGeneratedSpeed() {
        try {
            if (((BlockEntity) (Object) this).getLevel() == null) return 0;
            return generatedSpeed; // @Shadow 直读（2026-08-26 B 方案）
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.getGenSpeed", e);
            return 0;
        }
    }

    @Unique
    private void takeover$diag() {
        try {
            MotorTakeoverBase.diag(this, "MotorTakeoverSV",
                    avgSpeed * 60.0f / (float) (2.0 * Math.PI), 0); // @Shadow 直读
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.diag", e);
        }
    }

    @Inject(method = "applyPower", at = @At("HEAD"), cancellable = true)
    private void takeover$noHeat(org.patryk3211.powergrid.electricity.sim
            .AbstractElectricWire wire, CallbackInfo ci) {
        ci.cancel(); // 温度由引擎 ThermalModel 计算
    }
}
