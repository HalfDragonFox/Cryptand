/**
 * ===== PowerGrid kinetics 设备通用接口注入（2026-08-26） =====
 *
 * PowerGrid 的运动系电气设备（HvSwitch / HvBreaker / Rheostat / Variac /
 * Plotter / PunchCardReader / SolarPanelBearing 及其基类 TunedBlockEntity）
 * 继承 Create 的 KineticBlockEntity（→ kinetics.base.ElectricKineticBlockEntity），
 * 与 {@link ElectricBlockEntity}（electricity.base，SmartBlockEntity 系）不是
 * 一条继承链——{@link ElectricBlockEntityTakeoverMixin} 覆盖不到它们。
 *
 * 2026-08-26 用户架构（组装器只与绑定接口 BE 交互、BE 每 tick 主动上报）下，
 * 这些设备也必须实现 {@link ICryptandCircuitBe}，否则组装器 pollInput /
 * EngineBus 回写判定「未绑定接口」→ 参数变化不生效 / 状态无法回写。
 *
 * 用 kinetics 基类做 target → 一次覆盖全部运动系电气设备（继承性）。接口
 * 方法全 default（委托 BeMessageParser.cache → 无解析器兜底忽略，安全）。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.device.ICryptandCircuitBe;
import com.hdf.cryptand.neoforge.powergrid.device.motor.BeMessageParser;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 运动系电气设备：通用接口注入（含 {@code cryptandOnEngineMessage} 转发，
 * 与电机 mixin 一致；非电机设备经 BeMessageParser.cache 路由自身解析器）。
 */
@Mixin(value = org.patryk3211.powergrid.kinetics.base.ElectricKineticBlockEntity.class,
        remap = false)
// 纯 implements + 同名 @Override 实现（不用 @Implements，避免 prefix 解析歧义）
public abstract class KineticDeviceCircuitBeMixin implements ICryptandCircuitBe {

    /* ===== 通用接口实现（转发解析器；无解析器 = 兜底忽略） ===== */
    @Override
    public void cryptandOnEngineMessage(Object m) {
        try {
            BeMessageParser b = BeMessageParser.cache((BlockEntity) (Object) this);
            if (b != null) b.onEngineMessage(m);
        } catch (Throwable ignored) {
        }
    }
}