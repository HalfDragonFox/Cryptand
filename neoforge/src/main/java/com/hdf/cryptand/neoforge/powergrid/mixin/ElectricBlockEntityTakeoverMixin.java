/**
 * ===== 原版设备 BE 本体通用接管（2026-08-13 用户要求"全部接管"） =====
 *
 * 自管模式下禁掉原版设备 BE 的【原版功率/发热注入】路径：
 *   - applyPower(AbstractElectricWire)：原版把导线功率累加到 power 字段 +
 *     thermalBehaviour.applyWirePower（原版 I²R 发热）。自管模式下源功率与
 *     发热已由自管引擎（DEVICE_TERMINAL_V / WireThermalStore / DeviceThermalStore
 *     / TransformerHeatStore）完全接管 → 原版注入无意义且会污染（虚假功率/
 *     温度）。
 *
 * 设备数值读取已由 OwnedFloatingNodeGetVoltageMixin（getVoltage → 自管宿主）
 * 接管；本 mixin 进一步禁掉原版"写入/注入"路径，实现数值层完全接管。
 *
 * ⚠ 范围说明：electricalTick() 基类是空实现（各设备覆写，无法通用禁）；此处
 *   只禁基类有实现且自管可替代的 applyPower。buildCircuit 由变压器等专用
 *   mixin 处理（自管模式创建的节点不求解，无害）。
 *
 * 门控：PowerGridWireConverter.isEnabled()（自管模式）；非自管完全放行。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.device.ICryptandCircuitBe;
import com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;
import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ===== 原版电气设备基类：通用接口注入 + 功率路径禁用（2026-08-26） =====
 *
 * 本 mixin 注入【所有 PowerGrid 电气设备基类】ElectricBlockEntity：
 *   1. @Implements(ICryptandCircuitBe)：让【所有电气设备】运行时实现通用接口
 *      ——组装器/EngineBus 只需 `be instanceof ICryptandCircuitBe` 即可绑定
 *      （不再需要 BeBridge.of 的逐类型工厂）。
 *   2. 自管模式禁用原版 applyPower（原版功率/发热注入自管引擎已接管）。
 *
 * ✓ 设备子类（电机/灯/加热器/变阻器等）继承 ElectricBlockEntity → 自动获得
 *   接口身份（mixin 注入基类是继承性的，无需逐子类加）。
 * ✗ 电机各自 mixin 显式覆写 cryptandOnEngineMessage/serverTick（具体逻辑
 *   转发电机桥），基类 default 兜底（非电机设备走 default → 通用桥）。
 */
@Mixin(value = ElectricBlockEntity.class, remap = false)
@org.spongepowered.asm.mixin.Implements(
        @org.spongepowered.asm.mixin.Interface(
                iface = ICryptandCircuitBe.class,
                prefix = "cryptand$"))
public abstract class ElectricBlockEntityTakeoverMixin implements ICryptandCircuitBe {

    /* ===== 通用接口实现（基类级：直接委托解析器） =====
     * 电机子类 mixin 显式覆写（转发电机解析器）；本基类实现供非电机设备用。 */
    @Override
    public void cryptandOnEngineMessage(Object m) {
        try {
            BeMessageParser b = BeMessageParser.cache((net.minecraft.world.level.block.entity
                    .BlockEntity) (Object) this);
            if (b != null) b.onEngineMessage(m);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 自管模式禁用原版 applyPower：源功率不再注入原版网络（power 字段）+ 
     * 原版 I²R 发热不再累计（thermalBehaviour.applyWirePower）——自管引擎
     * 已接管功率/温度。非自管放行原版。
     */
    @Inject(method = "applyPower", at = @At("HEAD"), cancellable = true)
    private void takeover$noVanillaApplyPower(AbstractElectricWire wire, CallbackInfo ci) {
        try {
            if (!PowerGridWireConverter.isEnabled()) return;
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }
}
