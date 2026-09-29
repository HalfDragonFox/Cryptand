/**
 * ===== 轮子摩擦应力→网络总账本 Mixin（2026-08-29 v3 终版） =====
 *
 * 配合 WheelStressMixin（覆写 calculateStressApplied 供 goggle 显示）补充
 * 【网络总应力】的正确性：
 *   Create 网络 calculateStress() = Σ getActualStressOf(成员)，每个成员实时调用
 *   （KinceticNetwork 是编译期可见类 → 本 mixin 注入 100% 可靠）。
 *   原逻辑：members缓存 × |theoreticalSpeed|（缓存 impact）
 *   本注入：对 WheelMount 成员直接返回【绝对摩擦应力 SU_abs】（实时，不经缓存、
 *           不再乘转速）→ 网络总账本 = Σ SU_abs。
 *   非 WheelMount 成员 → 不干预，走原逻辑。
 *
 * 门控：AeronauticsMixinPlugin（aero 已装 && 开关开）层；方法内再读运行时配置
 * （tick 时 spec 已 build 可安全 .get()）双保险。
 */
package com.hdf.cryptand.neoforge.aeronautics.mixin;

import com.hdf.cryptand.neoforge.aeronautics.WheelStressAccess;
import com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KineticNetwork.class)
public class KineticNetworkStressMixin {

    /** Offroad 轮架 BE 全限定名（反射判定，无编译期依赖） */
    private static final String WHEEL_MOUNT_CLASS =
            "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity";

    /** log4j 诊断（进 latest.log） */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("Cryptand-AeroNet");

    /** [WheelNet] 诊断节流（5s） */
    private static volatile long NET_DBG_LAST;

    @Shadow private float currentCapacity;
    @Shadow private float currentStress;

    /**
     * updateNetwork() TAIL —— 节流打印容量/应力/衰减比例，验收
     * "应力足够→满速；不足→降速；差距过大→近停"（Create 原生机制，
     * currentStress 已含我们的轮子真实应力）。
     */
    @Inject(method = "updateNetwork", at = @At("TAIL"), remap = false)
    private void cryptand$onUpdateNetwork(CallbackInfo ci) {
        long now = System.currentTimeMillis();
        if (now - NET_DBG_LAST < 5000) return;
        NET_DBG_LAST = now;
        try {
            float cap = currentCapacity;
            float stress = currentStress;
            float ratio = (stress > 1e-6f) ? (cap / stress) : 0f;
            LOGGER.info(String.format("[WheelNet] cap=%.1f stress=%.1f ratio=%.3f"
                    + " overloaded=%s", cap, stress, ratio,
                    stress > cap ? "YES(降速)" : "no(满速)"));
        } catch (Throwable t) {
        }
    }

    /**
     * getActualStressOf(KineticBlockEntity) —— HEAD 拦截。
     * 只对 WheelMount 成员返回实时绝对摩擦应力；其他成员原逻辑。
     */
    @Inject(method = "getActualStressOf", at = @At("HEAD"),
            cancellable = true, remap = false)
    private void cryptand$onGetActualStressOf(KineticBlockEntity kbe,
                                              CallbackInfoReturnable<Float> cir) {
        if (kbe == null) return;
        try {
            if (!WHEEL_MOUNT_CLASS.equals(kbe.getClass().getName())) return;
            if (!ConfigAero.ENABLE_AERO_WHEEL_STRESS_FRICTION.get()) return;
            // ⚠ 2026-08-30 用户"重量过重、应力足够但轮子转速慢"：网络账本(超载判定)
            //   必须用【理论转速】算——若用实际转速，超载降速后 SU 骤降 → currentStress
            //   被低估 → 误判"应力足够"，但转速已被拖低且不恢复。理论转速反映"满速需求"，
            //   超载持续判定（差距过大→速度几乎无），减载才恢复。
            double theoRpm = Math.abs(kbe.getTheoreticalSpeed());
            double su = WheelStressAccess.frictionStressOfAt(kbe, theoRpm);
            if (Double.isFinite(su) && su >= 0) {
                cir.setReturnValue((float) su);
            }
        } catch (Throwable t) {
            // 反射/配置异常 → 回退原逻辑（安全）
        }
    }
}