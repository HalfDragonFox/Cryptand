/**
 * ===== 方块温度计接管（2026-08-21 用户要求：全部接管原版算法，不写回原版） =====
 *
 * PowerGrid ThermometerBlockEntity（带模型方块，extends Create SmartBlockEntity）
 * 原版 temperature() 读【朝向前方相邻方块】的 BlockEntityBehaviour.get(
 * ThermalBehaviour.TYPE).getTemperature()——自管模式原版时域被禁用 → 原版温度
 * 恒环境（22°C）→ 方块温度计指向电容/电阻/加热器/导线都读不到温度。
 *
 * 接管方案：@Inject temperature() HEAD cancellable → 直接读 Cryptand 温度存储：
 *   1. DeviceThermalStore（所有 Cryptand 设备温度模型：电机/加热器/电容/电阻/
 *      电感/换向器……）—— 朝向前方设备方块读到 Cryptand 推进的真实温度
 *   2. TransformerHeatStore（变压器温度）
 *   3. WireThermalStore（导线段温度——朝向前方导线经过的方块）
 *   4. 都没有 → 报环境温度 22°C（⚠ 2026-09-12：原版 ThermalBehaviour 兜底已按
 *      用户要求移除——温度全部走自管，不再读原版那条已停推的路径）
 *
 * ⚠ 不写回原版 ThermalBehaviour（2026-08-21 用户明确：全部接管，不要写回
 * 原版内容——写回会污染原版状态/造成双份温度）。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.state.TransformerHeatStore;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import com.hdf.cryptand.neoforge.powergrid.state.WireThermalStore;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "org.patryk3211.powergrid.equipment.thermometer.ThermometerBlockEntity",
       remap = false)
public abstract class ThermometerBlockEntityMixin {

    /** 完全接管 temperature()：返回 Cryptand 温度（设备/变压器/导线段）。 */
    @Inject(method = "temperature", at = @At("HEAD"), cancellable = true)
    private void cryptand$readCryptandTemp(CallbackInfoReturnable<Float> cir) {
        try {
            BlockEntity be = (BlockEntity) (Object) this;
            Level level = be.getLevel();
            if (level == null || level.isClientSide) return;
            Direction facing = cryptand$facing(be);
            if (facing == null) return;
            // 朝向前方相邻方块
            BlockPos pos = be.getBlockPos().relative(facing);
            float tempC = cryptand$readTemp(level, pos);
            cir.setReturnValue(tempC);
        } catch (Throwable ignored) {
        }
    }

    /** 方块朝向（ThermometerBlock.FACING） */
    @Unique
    private static Direction cryptand$facing(BlockEntity be) {
        try {
            BlockState st = be.getBlockState();
            if (st.hasProperty(org.patryk3211.powergrid.equipment.thermometer
                    .ThermometerBlock.FACING)) {
                return st.getValue(org.patryk3211.powergrid.equipment.thermometer
                        .ThermometerBlock.FACING);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 朝向前方方块 → Cryptand 温度（设备/变压器/导线段；兜底原版只读） */
    @Unique
    private static float cryptand$readTemp(Level level, BlockPos pos) {
        // 1) 设备温度（DeviceThermalStore——所有 Cryptand 设备温度模型）
        try {
            if (DeviceThermalStore.contains(pos)) {
                return (float) com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore.thermalFor(pos).tempCelsius();
            }
        } catch (Throwable ignored) {
        }
        // 2) 变压器温度（TransformerHeatStore；多方块 PART → 主方块）
        try {
            BlockPos main = com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache.transformerMainPos(level, pos);
            if (main != null) pos = main;
            com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel tf =
                    TransformerHeatStore
                            .getThermal(pos);
            if (tf != null) return (float) tf.tempCelsius();
        } catch (Throwable ignored) {
        }
        // 3) 导线段温度（WireThermalStore——朝向前方导线经过的方块）
        try {
            var mgr = WireNetworkManager.get();
            if (mgr != null) {
                String segKey = mgr.segmentKeyAtBlock(pos);
                if (segKey != null) {
                    return (float) com.hdf.cryptand.neoforge.powergrid.state.WireThermalStore.thermalFor(segKey).tempCelsius();
                }
            }
        } catch (Throwable ignored) {
        }
        // ⚠ 2026-09-12 用户："温度不再使用原版路径，全部使用自管，温度计也是测量
        //   接管温度"——原版 ThermalBehaviour 兜底已移除。没有自管温度模型的方块
        //   一律报环境温度（原版那条路径的温度已不被推进，读它只会显示恒温假值）。
        return 22.0f;
    }
}
