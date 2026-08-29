/**
 * ===== 统一接管 PowerGrid 所有设备加热（防失真爆炸） =====
 *
 * PowerGrid 每个设备都有各自的原版失真加热点，且互不共享：
 *   - 励磁绕组 WindingBlockEntity.electricalTick()：I²R（失真电流）
 *   - 变压器 TransformerBlockEntity.tick()：primaryStray/mutualInductance 的 I²R
 *   - 基类 ElectricBlockEntity.applyPower(wire) → applyWirePower(wire.power())
 * 这些失真功率（5kHz 下可达 31391）直接 applyTickPower → 单 tick +1046°C → 爆炸。
 *
 * 本 mixin 在【所有设备加热的最终汇聚点】ThermalBehaviour.applyTickPower 统一钳制：
 *   - 任何设备传入的功率都限制单 tick 温升 ≤ COIL_MAX_TEMP_RISE_PER_TICK
 *   - 失真/过载功率 → 渐进温升（可观察、可反应），不再瞬间爆炸
 *   - 正常功率（< 上限）→ 原版行为不变
 * 同时保留 applyWirePower 对励磁绕组的跳过（Winding 发热完全由
 * WindingBlockEntityAcMixin 物理模型控制，不走原版 wire 功率）。
 * 散热/过热爆炸仍走原版。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ThermalBehaviour.class, remap = false)
public abstract class ThermalBehaviourWindingMixin {

    @Shadow private float thermalMass;
    @Shadow private float temperature;

    /**
     * 统一钳制所有设备加热（applyTickPower 是最终汇聚点）。
     * 原版实现：temperature += (power/20.0)/thermalMass（仅有限值）。
     * 本注入：HEAD 拦截，功率超上限时手动重放钳制后的温升并 cancel。
     * 正常功率（≤ 上限）→ 不拦截，原版行为不变。
     */
    @Inject(method = "applyTickPower", at = @At("HEAD"), cancellable = true)
    private void cryptand$clampAllHeating(double power, CallbackInfo ci) {
        try {
            if (!Double.isFinite(power)) { ci.cancel(); return; }
            double maxRise = ConfigLoad.COIL_MAX_TEMP_RISE_PER_TICK.get();
            if (maxRise <= 0) return;               // 关闭钳制 → 完全原版
            if (thermalMass <= 0) return;
            double limit = maxRise * 20.0 * thermalMass; // 单 tick 温升上限对应功率
            if (power > limit) {
                // 失真/过载功率 → 渐进温升（真实热惯性）
                this.temperature += (float) ((limit / 20.0) / thermalMass);
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // 读取失败 → 不拦截（原版行为）
        }
    }

    /**
     * 拦截 applyWirePower：blockEntity 是励磁绕组时跳过（完全由自定义模型控制发热）。
     * 注意：ThermalBehaviour 的 blockEntity 字段是 private final SmartBlockEntity，
     * 经 Create BlockEntityBehaviour 构造传入；这里用反射读取避免名字问题。
     */
    @Inject(method = "applyWirePower", at = @At("HEAD"), cancellable = true)
    private void cryptand$skipWindingWirePower(AbstractElectricWire wire, CallbackInfo ci) {
        try {
            Object be = cryptand$blockEntity((ThermalBehaviour) (Object) this);
            if (be != null && be.getClass().getName()
                    .equals("org.patryk3211.powergrid.kinetics.generator.winding.WindingBlockEntity")) {
                ci.cancel(); // 励磁绕组：禁用原版 wire 失真功率加热
            }
        } catch (Throwable ignored) {
            // 读取失败 → 不拦截（原版行为）
        }
    }

    @Unique
    private static Object cryptand$blockEntity(ThermalBehaviour tb) {
        java.lang.reflect.Field f = null;
        Class<?> c = tb.getClass();
        while (c != null && f == null) {
            try {
                f = c.getDeclaredField("blockEntity");
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (f == null) return null;
        try {
            f.setAccessible(true);
            return f.get(tb);
        } catch (Throwable t) {
            return null;
        }
    }
}
