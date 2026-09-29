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

package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.powergrid.device.thermal.LegacyThermalParams;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ThermalBehaviour.class, remap = false)
public abstract class ThermalBehaviourWindingMixin {

    @Shadow private float thermalMass;
    @Shadow private float temperature;

    /**
     * 【原版热行为演化停用】（2026-09-12 用户："温度不再使用原版路径，全部使用自管，
     * 爆炸全部接管"）
     *
     * <p>自管模式（PowerGrid 接管开启）下取消 {@code tick()}——原版的
     * 加热累积 / 自然散热 / 过热判定 / 过热爆炸【全部停用】。此后：
     *   - 温度只有 Cryptand {@code ThermalModel} 一份（DeviceThermalStore /
     *     TransformerHeatStore / WireThermalStore）
     *   - 过热爆炸由 {@code PipelinePostProcess.checkDeviceOverheat} 按配置产生
     *     （overheatExplosion* 四项）
     *   - 没有自管温度模型的方块不再有温度演化、也不会爆炸（用户确认属预期：
     *     "目的是接管所有的"）
     *
     * <p>注意：各设备 mixin 里遗留的 {@code tb.applyTickPower(...)} 注入因此只写进
     * 一个【已退役、无人读取】的原版字段；这些热量源（线圈铁耗/换向器不匹配热等）
     * 迁到自管模型是后续项（见 ai_memory/repo/thermal-self-managed-migration.md）。
     */
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cryptand$disableVanillaThermalTick(CallbackInfo ci) {
        try {
            if (com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter
                    .isEnabled()) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
        }
    }

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
            double maxRise = ConfigPowerGrid.COIL_MAX_TEMP_RISE_PER_TICK.get();
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

    /* ==========================================================================
     * 原版热行为【对象】不再创建（2026-09-12 用户："全部接管，全部由自管模型实现，
     * 原版算法全部不使用"）
     *
     * 原版设计本身就允许这些静态工厂返回 null（各设备调用处都写了
     * {@code if(thermalBehaviour != null)}），所以自管模式下直接返回 null：
     *   - 省掉每个设备一个 ThermalBehaviour 对象 + coolingAir Map + 若干字段
     *   - 返回前把 thermalMass / dissipationFactor 记入 LegacyThermalParams，
     *     供热源迁移做等价换算：J = (P/20) × heatCapacity / thermalMass
     * 未接管（enablePowergridSupport 关闭）→ 不拦截，保持完全原版。
     * ======================================================================== */

    /** 统一拦截体：自管模式 → 记录参数 + 返回 null；否则放行原版 */
    @Unique
    private static boolean cryptand$noVanillaThermal(SmartBlockEntity be,
            double mass, double diss, CallbackInfoReturnable<ThermalBehaviour> cir) {
        try {
            if (!com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter
                    .isEnabled()) {
                return false;
            }
            if (be != null) {
                com.hdf.cryptand.neoforge.powergrid.device.thermal.LegacyThermalParams
                        .record(be.getBlockPos(), mass, diss);
            }
            // ⚠ 2026-09-13 修复"进入世界崩溃"（crash-report 实锤）：
            //   powergrid HeaterBlockEntity.addToGoggleTooltip(115) 等原版代码【直接】
            //   this.thermalBehaviour.getTemperature()（无 null 检查）→ 工厂返回 null 必 NPE。
            //   改为【照常创建对象】（放弃"省内存"收益），温度仍然全自管：
            //     · tick 已停用（cryptand$disableVanillaThermalTick）
            //     · 热量已改注入自管模型（Winding/Commutator/Generator + legacyMass 换算）
            //   ⇒ 该对象只作【占位 / 数据容器】，不参与任何温度演化。
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Inject(method = "simple(Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;FFF)"
                    + "Lorg/patryk3211/powergrid/electricity/base/ThermalBehaviour;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void cryptand$noThermalSimple3(SmartBlockEntity be, float mass, float diss,
            float extra, CallbackInfoReturnable<ThermalBehaviour> cir) {
        cryptand$noVanillaThermal(be, mass, diss, cir);
    }

    @Inject(method = "simple(Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;FF)"
                    + "Lorg/patryk3211/powergrid/electricity/base/ThermalBehaviour;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void cryptand$noThermalSimple2(SmartBlockEntity be, float mass, float diss,
            CallbackInfoReturnable<ThermalBehaviour> cir) {
        cryptand$noVanillaThermal(be, mass, diss, cir);
    }

    @Inject(method = "always(Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;FFF)"
                    + "Lorg/patryk3211/powergrid/electricity/base/ThermalBehaviour;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void cryptand$noThermalAlways(SmartBlockEntity be, float mass, float diss,
            float extra, CallbackInfoReturnable<ThermalBehaviour> cir) {
        cryptand$noVanillaThermal(be, mass, diss, cir);
    }

    @Inject(method = "forMaxPower(Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;FF)"
                    + "Lorg/patryk3211/powergrid/electricity/base/ThermalBehaviour;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void cryptand$noThermalForMaxPower2(SmartBlockEntity be, float maxPower,
            float temp, CallbackInfoReturnable<ThermalBehaviour> cir) {
        cryptand$noVanillaThermal(be, com.hdf.cryptand.neoforge.powergrid.device.thermal.LegacyThermalParams.DEFAULT_MASS, 0, cir);
    }

    @Inject(method = "forMaxPower(Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;FFF)"
                    + "Lorg/patryk3211/powergrid/electricity/base/ThermalBehaviour;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void cryptand$noThermalForMaxPower3(SmartBlockEntity be, float maxPower,
            float temp, float extra, CallbackInfoReturnable<ThermalBehaviour> cir) {
        cryptand$noVanillaThermal(be, com.hdf.cryptand.neoforge.powergrid.device.thermal.LegacyThermalParams.DEFAULT_MASS, 0, cir);
    }

    @Inject(method = "fromConfig(Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;)"
                    + "Lorg/patryk3211/powergrid/electricity/base/ThermalBehaviour;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void cryptand$noThermalFromConfig1(SmartBlockEntity be,
            CallbackInfoReturnable<ThermalBehaviour> cir) {
        cryptand$noVanillaThermal(be, com.hdf.cryptand.neoforge.powergrid.device.thermal.LegacyThermalParams.DEFAULT_MASS, 0, cir);
    }

    @Inject(method = "fromConfig(Lcom/simibubi/create/foundation/blockEntity/SmartBlockEntity;F)"
                    + "Lorg/patryk3211/powergrid/electricity/base/ThermalBehaviour;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void cryptand$noThermalFromConfig2(SmartBlockEntity be, float extra,
            CallbackInfoReturnable<ThermalBehaviour> cir) {
        cryptand$noVanillaThermal(be, com.hdf.cryptand.neoforge.powergrid.device.thermal.LegacyThermalParams.DEFAULT_MASS, 0, cir);
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
