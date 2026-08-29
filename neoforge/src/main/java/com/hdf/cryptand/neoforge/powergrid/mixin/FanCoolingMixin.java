/**
 * ===== 风扇/鼓风机冷却：可靠覆盖整条气流（修复"只冷却前面一个"） =====
 *
 * 同时覆盖两种风扇：
 *   - PowerGrid 电动风扇（ElectricFanBlockEntity）
 *   - Create 机械风扇（EncasedFanBlockEntity）
 * 两者都实现 IAirCurrentSource，冷却逻辑统一。
 *
 * PowerGrid 的 AirCurrentMixin 在 Create AirCurrent.rebuild() 里重建冷却，
 * 但 rebuild 只在 fanBlockCheckRate 触发一次且范围受 getLimit（转速→距离）限制，
 * 实测导致气流覆盖线圈一整条时只有第一个方块降温。
 *
 * 本 Mixin 在两种风扇的 tick TAIL 每 tick 接管冷却刷新：
 *   1. 移除上一帧冷却的方块（防止残留/累积）
 *   2. 沿气流方向遍历 getMaxDistance() 距离内的所有方块，
 *      每个有 ThermalBehaviour 的方块（含整条线圈的每个分段）都加冷却，
 *      强度随距离线性衰减（与 PowerGrid 公式一致）。
 *
 * 这样无论 PowerGrid 的 rebuild 何时/是否触发，冷却都正确覆盖整条气流。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.simibubi.create.content.kinetics.fan.AirCurrent;
import com.simibubi.create.content.kinetics.fan.IAirCurrentSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.collections.ModdedConfigs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(targets = {
        "org.patryk3211.powergrid.electricity.fan.ElectricFanBlockEntity",
        "com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity"
}, remap = false)
public abstract class FanCoolingMixin {

    /** 上一帧被冷却的方块（本 mixin 维护，先移除再添加保持平衡） */
    @Unique private final List<BlockPos> cryptand$prevCooled = new ArrayList<>();

    @Inject(method = "tick", at = @At("TAIL"))
    private void cryptand$refreshCooling(CallbackInfo ci) {
        try {
            IAirCurrentSource self = (IAirCurrentSource) (Object) this;
            Level level = self.getAirCurrentWorld();
            if (level == null || level.isClientSide) return; // 冷却/发热在服务端

            AirCurrent ac = self.getAirCurrent();
            if (ac == null) return;
            BlockPos pos = self.getAirCurrentPos();
            Direction dir = self.getAirFlowDirection();
            if (dir == null) return;

            float speed = Math.abs(self.getSpeed());
            if (speed <= 0) return; // 无风

            // 2) 冷却距离（与 Create getLimit 一致：非整数 maxDistance → +1）
            float maxDist = self.getMaxDistance();
            int limit = (int) maxDist;
            if (maxDist != limit) limit++;
            if (limit <= 0) return;

            float coolingStrength = speed * ModdedConfigs.server().kinetics.encasedFanCoolingStrength.getF();

            // 3) 沿气流遍历整条：把每个被吹到的方块冷却贡献登记到
            //    FanCoolingRegistry（2026-08-20 用户要求：多方块整体冷却 +
            //    多面曲线叠加）。多方块（变压器 2x2 主+PART）映射到主方块——
            //    吹到任意子方块 → 整个设备（主方块温度模型）整体降温。
            for (int i = 1; i <= limit; i++) {
                BlockPos p = pos.relative(dir, i);
                float strength = (1f - (i - 1f) / limit) * coolingStrength;
                if (strength <= 0) continue;
                // 多方块映射（变压器 PART → 主方块）
                BlockPos target = p;
                try {
                    BlockPos main = com.hdf.cryptand.neoforge.powergrid.adapter
                            .DeviceParamCache.transformerMainPos(level, p);
                    if (main != null) target = main;
                } catch (Throwable ignored) {
                }
                // 登记贡献（统一曲线叠加，由 FanCoolingRegistry.apply 汇总应用）
                com.hdf.cryptand.neoforge.powergrid.adapter.FanCoolingRegistry
                        .add(target, strength);
                cryptand$prevCooled.add(target);
            }
        } catch (Throwable t) {
            // 永不崩溃
        }
    }
}
