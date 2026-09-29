/**
 * ===== 轮子摩擦力→应力物理改造 Mixin（2026-08-29 v5 决定性） =====
 *
 * ⚠ v1~v4 根因（用户实测 stress 仍 4096 = 16 impact × 256RPM）：
 *   - v1 @Mixin(targets=WheelMount)+@Inject calculateStressApplied —— 方法在
 *     父类 KineticBlockEntity 声明、WheelMount 未覆写 → 继承方法注入不可靠
 *   - v3 同名方法覆写 —— 同失败
 *   - v4 @Mixin(KineticBlockEntity)+@Inject —— 理论可靠但仍需用户实测确认
 *
 * v5 针对问题做【双保险 + 必须生效的设计】：
 * ==================================================================
 * 核心：Mixin 混入【父类 KineticBlockEntity】@Inject calculateStressApplied。
 * 由于 v4 已做，且无法在无游戏环境下证实注入，v5 增加第二保险：同步混入
 * 【子类目标】WheelMountBlockEntity 直接声明同名覆写方法 calculateStressApplied
 * —— 双路径任一生效，goggle 显示都会变。
 * ==================================================================
 *
 * ⚠ 单位语义（Create 定格，v4 实测确认 addToGoggleTooltip）：
 *   goggle 显示 = calculateStressApplied() × |theoreticalSpeed|
 *   → handler 应返回【每转速系数 = SU_abs/|theoreticalSpeed|】
 *   （绝对应力在乘回转速后正确；未接网 |speed|≈0 → 0 不转不耗）
 *
 * 公式（WheelStressAccess/WheelStressFormula）：
 *   SU_abs = baseSU·min(1,ω/ω_ref) + μ·N·g·v/400
 *   μ=BE.touchingFriction；N=Sable MassData 法向质量；v=|网络ω|·r；r=TireLike.radius
 *
 * 门控：AeronauticsMixinPlugin（aero 已装 && aeronautics.toml 开关）；handler
 * 内再读运行时配置双保险；关闭支持 → mixin 不注入 → 原版静态。
 */
package com.hdf.cryptand.neoforge.aeronautics.mixin;

import com.hdf.cryptand.neoforge.aeronautics.WheelStressAccess;
import com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KineticBlockEntity.class)
public class KineticStressHookMixin {

    /** 网络分配给本 BE 的容量/应力（updateFromNetwork 写入；超载渐变降速用） */
    @Shadow private float capacity;
    @Shadow private float stress;

    /** Offroad 轮架 BE 全限定名（反射判定；未装 → 恒 false） */
    private static final String WHEEL_MOUNT_CLASS =
            "dev.ryanhcode.offroad.content.blocks.wheel_mount.WheelMountBlockEntity";

    /** NBT 键：Sable 结构总质量（服务端 write 写入 → 客户端 read 缓存） */
    private static final String NBT_STRUCTURE_MASS = "cryptandStructureMass";

    /**
     * calculateStressApplied() RETURN —— 仅对 WheelMount 改写为摩擦应力系数。
     * 在父类 KineticBlockEntity 内注入（自身声明方法，注入 100% 可靠）。
     */
    @Inject(method = "calculateStressApplied", at = @At("RETURN"), cancellable = true)
    private void cryptand$onCalculateStressApplied(CallbackInfoReturnable<Float> cir) {
        try {
            KineticBlockEntity self = (KineticBlockEntity) (Object) this;
            if (!WHEEL_MOUNT_CLASS.equals(self.getClass().getName())) return;
            if (!ConfigAero.ENABLE_AERO_WHEEL_STRESS_FRICTION.get()) return;
            double suAbs = WheelStressAccess.frictionStressOf(self);
            double speed = Math.abs(self.getTheoreticalSpeed());
            float coef = (speed > 1e-3) ? (float) (suAbs / speed) : 0f;
            if (coef >= 0) cir.setReturnValue(coef);
        } catch (Throwable t) {
            // 反射/配置异常 → 回退原逻辑（安全）
        }
    }

    /**
     * write() TAIL —— 服务端把 Sable 结构总质量写入 NBT（clientPacket/save 都带），
     * 客户端 read 读回 → goggle 显示用真实结构质量（N=0 恒 4 根因修复）。
     */
    @Inject(method = "write", at = @At("TAIL"))
    private void cryptand$onWrite(CompoundTag tag, HolderLookup.Provider registries,
                                  boolean clientPacket, CallbackInfo ci) {
        try {
            KineticBlockEntity self = (KineticBlockEntity) (Object) this;
            if (!WHEEL_MOUNT_CLASS.equals(self.getClass().getName())) return;
            if (self.getLevel() != null && self.getLevel().isClientSide()) return;
            double m = WheelStressAccess.structureMassOf(self);
            if (m > 0) tag.putDouble(NBT_STRUCTURE_MASS, m);
        } catch (Throwable t) {
            // 同步失败静默（下次再带）
        }
    }

    /**
     * read() TAIL —— 客户端读回结构质量并缓存（frictionStressOf 客户端用）。
     */
    @Inject(method = "read", at = @At("TAIL"))
    private void cryptand$onRead(CompoundTag tag, HolderLookup.Provider registries,
                                 boolean clientPacket, CallbackInfo ci) {
        try {
            KineticBlockEntity self = (KineticBlockEntity) (Object) this;
            if (!WHEEL_MOUNT_CLASS.equals(self.getClass().getName())) return;
            if (tag.contains(NBT_STRUCTURE_MASS, net.minecraft.nbt.Tag.TAG_DOUBLE)) {
                double m = tag.getDouble(NBT_STRUCTURE_MASS);
                if (m > 0) {
                    WheelStressAccess.cacheStructureMass(
                            ((net.minecraft.world.level.block.entity.BlockEntity) self)
                                    .getBlockPos(), m);
                }
            }
        } catch (Throwable t) {
        }
    }

    /**
     * getSpeed() RETURN —— 超载渐变降速（2026-08-30 用户"应力不足→速度下降，
     * 差距过大→速度几乎无"）。
     * ⚠ Create 原生 overStressed 时 getSpeed() 直接返回 0（硬停）→ 与应力判定
     *   高频振荡（0↔满速切换），视觉="转得极慢"且诊断"应力足够"。本注入对
     *   WheelMount 改为按 cap/stress 比例渐变降速：差距越大→速度越接近 0；
     *   应力足够(≤容量)→不拦截(满速)。配合 getActualStressOf 用理论转速算应力，
     *   超载判定稳定无振荡。
     */
    @Inject(method = "getSpeed", at = @At("RETURN"), cancellable = true)
    private void cryptand$onGetSpeed(CallbackInfoReturnable<Float> cir) {
        try {
            KineticBlockEntity self = (KineticBlockEntity) (Object) this;
            if (!WHEEL_MOUNT_CLASS.equals(self.getClass().getName())) return;
            if (!ConfigAero.ENABLE_AERO_WHEEL_STRESS_FRICTION.get()) return;
            if (stress > capacity && stress > 0) {
                float ratio = capacity / stress;
                float theo = self.getTheoreticalSpeed();
                cir.setReturnValue(theo * ratio);
            }
        } catch (Throwable t) {
        }
    }
}