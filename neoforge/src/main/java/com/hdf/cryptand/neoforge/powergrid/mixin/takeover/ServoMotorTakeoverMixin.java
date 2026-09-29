package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.device.ICryptandCircuitBe;
import com.hdf.cryptand.neoforge.powergrid.device.motor.parser.BeMessageParser;
import com.hdf.cryptand.neoforge.powergrid.device.motor.MotorTakeoverBase;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceVoltageStore;
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
        CryptandNeoForge.WAF_LOGGER.debug(
                "[MotorTakeover] mixin class loaded (ServoBlockEntity)");
    }

    /**
     * @Overwrite tick：**参考原版算法接管**（2026-09-12 用户："三种模式不需要恢复
     * 原版 tick 而是参考原版算法建模接管"）。
     *
     * 原版 ServoBlockEntity.tick() 的算法（逐条复刻）：
     *   ① avgSpeed += calculateSpeed(coil.power(), torque())
     *   ② ctrlTarget = round(clamp(control.potentialDifference() / 5 × 360, ±360))
     *      prevTarget == ctrlTarget 时提交 → currentTarget
     *   ③ rotation = currentTarget − currentAngle；movingTicks 倒计时到 0 → 停机
     *   ④ rotation ≠ 0 → generatedSpeed = clamp(rotation/0.05/6, ±maxSpeed)；
     *      coil 切到 resistance("on")；currentAngle = currentTarget；
     *      movingTicks = |rotation / convertToAngular(generatedSpeed)| + 5
     *
     * 自管侧代入的电气量（原版 coil/control 电压在自管模式下不可用）：
     *   coil.power()                → I²·R（DeviceCurrent，端子 0/1 的电流）
     *   control.potentialDifference → DeviceVoltageStore 端子 2 − 端子 1
     */
    @org.spongepowered.asm.mixin.Overwrite
    public void tick() {
        try {
            super.tick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.tick.super", e);
        }
        // 2026-09-13：逻辑已搬入自有 BE（委派式迁移；@Overwrite 保留以调用 super）
        if ((Object) this instanceof com.hdf.cryptand.neoforge.powergrid.motor
                .CryptandServoMotorBE be) {
            be.cryptandMotorTick();
            takeover$diag();
            return;
        }
        try {
            BlockEntity be = (BlockEntity) (Object) this;
            BlockPos pos = be.getBlockPos();

            // ① 原版电气累加：coil 功率 → avgSpeed
            double torque = MotorTakeoverBase.callNumber(this, "torque");
            double i = MotorTakeoverBase.deviceCurrent(be);
            if (torque > 0 && i != 0) {
                double rIdle = MotorTakeoverBase.callNumberStr(this, "resistance", "idle");
                if (rIdle <= 0) rIdle = MotorTakeoverBase.callNumber(this, "resistance");
                double pw = i * i * Math.max(rIdle, 1e-9);
                MotorTakeoverBase.addFloat(this, "avgSpeed",
                        pw / torque * MotorTakeoverBase.CONVERSION_CONSTANT);
            }

            // ② 原版控制线电压 → 目标角度（端子 2 − 端子 1；5V = 360°）
            double ctrlV = com.hdf.cryptand.neoforge.powergrid.state.DeviceVoltageStore
                    .readBetween(pos, 2, 1);
            int ctrlTarget = (int) Math.round(Math.max(-360.0, Math.min(360.0,
                    ctrlV / 5.0 * 360.0)));
            if (MotorTakeoverBase.readInt(this, "prevTarget") == ctrlTarget) {
                MotorTakeoverBase.setField(this, "currentTarget", ctrlTarget);
            }
            MotorTakeoverBase.setField(this, "prevTarget", ctrlTarget);

            // ③④ 原版角度推进
            int rotation = MotorTakeoverBase.readInt(this, "currentTarget")
                    - MotorTakeoverBase.readInt(this, "currentAngle");
            if (Math.abs(rotation) < 1) rotation = 0;
            int moving = MotorTakeoverBase.readInt(this, "movingTicks");
            if (moving > 0) {
                moving--;
                MotorTakeoverBase.setField(this, "movingTicks", moving);
                if (moving == 0) {
                    MotorTakeoverBase.setField(this, "generatedSpeed", 0f);
                    takeover$sync();
                }
                takeover$diag();
                return;
            }
            if (rotation != 0) {
                double maxSpeed = MotorTakeoverBase.readFloat(this, "maxSpeed");
                double gs = Math.max(-maxSpeed, Math.min(maxSpeed, rotation / 0.05 / 6.0));
                MotorTakeoverBase.setField(this, "generatedSpeed", (float) gs);
                takeover$sync();
                MotorTakeoverBase.setField(this, "currentAngle",
                        MotorTakeoverBase.readInt(this, "currentTarget"));
                double ang = Math.abs(convertToAngular((float) gs));
                MotorTakeoverBase.setField(this, "movingTicks",
                        (int) (Math.abs(rotation) / Math.max(ang, 1e-6)) + 5);
            }
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.tick.vanilla", e);
        }
        takeover$diag();
    }

    /** Create 网络同步（无网络跳过，规避 NPE） */
    @org.spongepowered.asm.mixin.Unique
    private void takeover$sync() {
        try {
            var g = (com.simibubi.create.content.kinetics.base
                    .GeneratingKineticBlockEntity) (Object) this;
            if (g.hasNetwork()) {
                g.updateGeneratedRotation();
            }
        } catch (Throwable ignored) {
        }
    }

    /** @Overwrite lazyTick：原版算法 —— maxSpeed = min(avgSpeed/5, MAX_SPEED=32)；
     *  avgSpeed 清零；maxSpeed 为 0 且仍有输出 → 立即停机（即开即停）。 */
    @org.spongepowered.asm.mixin.Overwrite
    public void lazyTick() {
        try {
            super.lazyTick();
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.lazyTick.super", e);
        }
        if ((Object) this instanceof com.hdf.cryptand.neoforge.powergrid.motor
                .CryptandServoMotorBE be) {
            be.cryptandMotorLazyTick();
        }
        try {
            float avg = MotorTakeoverBase.readFloat(this, "avgSpeed");
            MotorTakeoverBase.setField(this, "avgSpeed", 0f);
            float maxS = Math.min(avg / 5.0f, 32.0f); // 原版 AVERAGING_TICKS=5, MAX_SPEED=32
            if (maxS < 0) maxS = 0;
            MotorTakeoverBase.setField(this, "maxSpeed", maxS);
            if (maxS == 0f && MotorTakeoverBase.readFloat(this, "generatedSpeed") != 0f) {
                MotorTakeoverBase.setField(this, "generatedSpeed", 0f);
                takeover$sync();
            }
        } catch (Throwable e) {
            MotorTakeoverBase.logErr("MotorTakeoverSV.lazyTick.vanilla", e);
        }
    }

    /** getGeneratedSpeed 覆写：原版语义 —— {@code convertToDirection(generatedSpeed, FACING)}
     *  （generatedSpeed 由上面的原版角度算法只写，本方法只做朝向换算）。 */
    @org.spongepowered.asm.mixin.Overwrite
    public float getGeneratedSpeed() {
        if ((Object) this instanceof com.hdf.cryptand.neoforge.powergrid.motor
                .CryptandServoMotorBE be) {
            return be.cryptandGeneratedSpeed();
        }
        try {
            return convertToDirection(generatedSpeed,
                    getBlockState().getValue(org.patryk3211.powergrid.kinetics.motor
                            .ElectricMotorBlock.FACING));
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
