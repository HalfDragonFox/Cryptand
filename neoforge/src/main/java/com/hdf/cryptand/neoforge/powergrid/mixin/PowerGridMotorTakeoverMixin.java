package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.device.ICryptandCircuitBe;
import com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ===== PowerGrid 电机完全接管（2026-08-24 用户：禁用原版算法、直接写变量） =====
 *
 * 原版 PowerGrid 电机每 tick 自算：
 *   avgSpeed += calculateSpeed(coil.power(), torque()) × signum(coil.current())
 * 再经 lazyTick clamp→generatedSpeed→updateGeneratedRotation（Create 网络传播）。
 * 由于线圈已由我们引擎接管（PowerGrid 模拟器不回写功率），原版算出的
 * avgSpeed 是旧值（76RPM/4864 应力）→ Create 应力表显示不符。
 *
 * 本 mixin【把原版算法输出直接覆写为引擎值】：
 *   - avgSpeed（rad/s）   ← 引擎 ω（MotorStateStore{ω,emf,stress}）
 *   - generatedSpeed(rpm) ← 引擎 rpm（仅在有 Create 网络时同步，规避 NPE）
 *   - load（应力）        ← 引擎 stress
 * 原版 tick 继续执行，但结果每 tick 被覆盖 = 等效"禁原版算法+写变量"。
 * 无引擎状态（未建模/无 store）→ 不干预。
 */
@Mixin(targets = {
        "org.patryk3211.powergrid.kinetics.motor.ElectricMotorBlockEntity"
}, remap = false)
// ⚠ 2026-08-26 不用 @Implements(@Interface(prefix="cryptand$"))：本类已有
// cryptand$apply / cryptand$overrideFields 等 cryptand$ 前缀方法，会被 prefix
// 规则误解析成接口方法 apply → InValidMixinException "apply does not exist in
// target interface" → 整个 mixin 注入失败（接口也不注入、tick 也不注入，
// 实测 [MotorTakeover] 诊断消失 + [RotorDbgNoRotor] 持续）。改用纯
// implements ICryptandCircuitBe + 同名 @Override 实现（Sponge 同样会把接口
// 附加到目标类，default 方法兜底，与 ConstantSpeed/Servo 兼容）。
public abstract class PowerGridMotorTakeoverMixin implements ICryptandCircuitBe {

    /* ===== 2026-08-26 接口通用数据通路：转发给电机解析器 =====
     * （与 ConstantSpeed/Servo 一致；普通电机此前【漏接】→ 电机 BE 不是
     *  ICryptandCircuitBe → EngineBus 判定"未绑定转子桥"→ [RotorDbgNoRotor]
     *  每 5s WARN + 引擎状态无法回写 BE → 应力/转速不更新） */
    @Override
    public void cryptandOnEngineMessage(Object m) {
        try {
            BeMessageParser b = BeMessageParser.cache((BlockEntity) (Object) this);
            if (b != null) b.onEngineMessage(m);
        } catch (Throwable ignored) {
        }
    }

    @Unique private float cryptand$lastEngineRpm = Float.NaN;

    static {
        // 2026-08-24 确认 mixin 被加载（进 debug.log）
        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.debug(
                "[MotorTakeover] mixin class loaded (ElectricMotorBlockEntity)");
    }

    @Unique private static volatile long cryptand$dbg;

    @Inject(method = "tick", at = @At("RETURN"))
    private void cryptand$overrideFields(CallbackInfo ci) {
        cryptand$apply();
    }

    @Inject(method = "lazyTick", at = @At("RETURN"))
    private void cryptand$overrideFieldsLazy(CallbackInfo ci) {
        cryptand$apply();
    }

    @Inject(method = "lazyTick", at = @At("HEAD"), cancellable = true)
    private void cryptand$guardLazyTick(CallbackInfo ci) {
        // 2026-08-26 崩溃修复（改到 lazyTick HEAD）：原版 lazyTick →
        // updateGeneratedRotation → applyNewSpeed → getOrCreateNetwork() 返回
        // null（电机无 Create 网络：剪线/自管接管后网络对象不存在）→ NPE
        // "Cannot read field members"。⚠ updateGeneratedRotation/applyNewSpeed
        // 是 Create 父类方法，不能作为 mixin targets（前版报
        // "could not find targets 'updateGeneratedRotation'" → FATAL）。
        // lazyTick 是本类自身方法可直接注入：无网络时跳过整个原版 lazyTick。
        // 转速已由 cryptand$apply 每 tick 写 avgSpeed/generatedSpeed → 不丢，
        // 有网络时照常执行原版（Create 传动不受影响）。
        try {
            if (!((com.simibubi.create.content.kinetics.base.KineticBlockEntity)
                    (Object) this).hasNetwork()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private void cryptand$apply() {
        try {
            BlockEntity be = (BlockEntity) (Object) this;
            BlockPos pos = be.getBlockPos();
            double[] s = com.hdf.cryptand.neoforge.powergrid.adapter
                    .MotorStateStore.get(pos);
            long now = System.currentTimeMillis();
            if (now - cryptand$dbg >= 5000) {
                cryptand$dbg = now;
                float av = readF(this, "avgSpeed");
                float ld = readF(this, "load");
                Float gn = (Float) getField(this, "generatedSpeed");
                int hz = (int) (s == null ? -1 : (s[0] * 60.0 / (2.0 * Math.PI)));
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[MotorTakeover] pos={} storeRpm={} avgSpeed={} load={} gen={}",
                        pos, hz, String.format("%.1f", av),
                        String.format("%.0f", ld),
                        gn == null ? "null" : String.format("%.1f", gn));
            }
            if (s == null || s.length < 1) return; // 无引擎状态 → 不干预
            double omega = s[0];
            float rpm = (float) (omega * 60.0 / (2.0 * Math.PI));
            float stress = (float) (s.length > 2 ? s[2] : 0);
            // ① 直接写原版字段（私有反射 set；等效"禁用算法输出"）
            setField(this, "avgSpeed", (float) omega);
            setField(this, "load", stress);
            // ② generatedSpeed 变化 → 同步 Create 网络（仅网络存在时，规避 NPE）
            Float gen = (Float) getField(this, "generatedSpeed");
            if (gen == null || Math.abs(gen - rpm) > 0.01f) {
                setField(this, "generatedSpeed", rpm);
                try {
                    var g = (com.simibubi.create.content.kinetics.base
                            .GeneratingKineticBlockEntity) (Object) this;
                    if (g.hasNetwork()) {
                        g.updateGeneratedRotation();
                    }
                } catch (Throwable ignored) {
                }
                cryptand$lastEngineRpm = rpm;
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private static float readF(Object obj, String name) {
        Object v = getField(obj, name);
        return v instanceof Number n ? n.floatValue() : Float.NaN;
    }

    @Unique
    private static void setField(Object obj, String name, Object val) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(obj, val);
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private static Object getField(Object obj, String name) {
        try {
            java.lang.reflect.Field f = obj.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Throwable ignored) {
            return null;
        }
    }
}