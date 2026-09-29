/**
 * ===== 风扇/鼓风机冷却：放下风扇时收集一次，风扇破坏时精确撤销 =====
 *
 * 同时覆盖两种风扇（都实现 IAirCurrentSource，逻辑统一）：
 *   - PowerGrid 电动风扇（ElectricFanBlockEntity）
 *   - Create 机械风扇（EncasedFanBlockEntity）
 *
 * 2026-09-12 用户设计（替代旧的"每 tick 全量重扫 + 每 tick 清空重建"）：
 *   "放下风扇后收集被散热的BE并发送额外散热值，然后每次计算时使用此值，
 *    如果风扇被破坏则减去此风扇附加的额外散热值（方便多个风扇叠加）"
 *
 * 本 Mixin 只负责【采集】这一侧（主线程）：
 *   1. 风扇 tick TAIL → 算出【登记指纹】（气流方向 + 作用距离 + 量化转速）
 *   2. 指纹与上次相同 → 直接返回（不重扫；这就是"放下风扇后收集一次"）
 *   3. 指纹变化（刚放下/转向/转速变→距离变）→ 沿气流方向收集整条路径，
 *      强度随距离线性衰减（与 PowerGrid 公式一致），交给
 *      {@link FanCoolingRegistry#setFan} 按【风扇位置】登记（幂等替换）
 *   4. 风扇停转 → 撤销本台风扇的贡献（风扇还在，只是不吹了）
 *   5. 风扇被破坏 → ElectricBlockEntityLifecycleMixin 的 setRemoved 钩子调
 *      {@link FanCoolingRegistry#removeFan}，只减掉这一台的那份
 *
 * 登记按【位置】而非"当前有 BE 的方块"——因此风扇先放、设备后放也能自动获得冷却
 * （设备温度模型创建时读累积值）。整个采集是纯主线程轻量操作：指纹未变时零遍历。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.powergrid.device.thermal.FanCoolingRegistry;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
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

import java.util.HashMap;
import java.util.Map;

@Mixin(targets = {
        "org.patryk3211.powergrid.electricity.fan.ElectricFanBlockEntity",
        "com.simibubi.create.content.kinetics.fan.EncasedFanBlockEntity"
}, remap = false)
public abstract class FanCoolingMixin {

    /** 上次登记的指纹（方向 + 距离 + 量化转速）；0 = 未登记/已撤销。
     *  @Unique：每个风扇 BE 实例一份，区块重载后新实例 key=0 → 自动重登记。 */
    @Unique private long cryptand$fanRegKey = 0L;

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
            if (pos == null || dir == null) return;

            // 心跳：只要风扇还在 tick（服务端），它的贡献就有效；区块卸载/卡死/未走
            // setRemoved 的移除都会让心跳停摆 → 看门狗（FanCoolingRegistry.sweepStale）
            // 自动撤销这一台的贡献。用户："只要风扇停止运行就停止额外散热"。
            FanCoolingRegistry.touch(pos);

            float speed = Math.abs(self.getSpeed());
            if (speed <= 0) {
                // 停转 → 撤销本台风扇的冷却（风扇还在，只是不吹了）
                if (cryptand$fanRegKey != 0L) {
                    cryptand$fanRegKey = 0L;
                    FanCoolingRegistry.removeFan(pos);
                }
                return;
            }

            // 冷却距离（与 Create getLimit 一致：非整数 maxDistance → +1）
            float maxDist = self.getMaxDistance();
            int limit = (int) maxDist;
            if (maxDist != limit) limit++;
            if (limit <= 0) return;

            // 登记指纹：方向 + 距离 + 量化转速（转速连续变化 → 量化到整数防抖）
            long key = ((long) (dir.ordinal() & 0xFF) << 40)
                    ^ ((long) (limit & 0xFFFF) << 24)
                    ^ (long) Math.round(speed);
            if (key == cryptand$fanRegKey) return; // 范围/强度未变 → 不重扫（零遍历）
            cryptand$fanRegKey = key;

            float coolingStrength =
                    speed * ModdedConfigs.server().kinetics.encasedFanCoolingStrength.getF();

            // 沿气流收集整条路径（强度随距离线性衰减，与 PowerGrid 公式一致）
            Map<BlockPos, Double> contribs = new HashMap<>();
            for (int i = 1; i <= limit; i++) {
                BlockPos p = pos.relative(dir, i);
                float strength = (1f - (i - 1f) / limit) * coolingStrength;
                if (strength <= 0) continue;
                // 多方块映射：吹到任意子方块 = 整个设备整体降温
                //   - 变压器 2x2：PART → 主方块
                //   - 线圈（绕组）：任意段 → 主方块（Cryptand 只为线圈主方块建温度模型）
                BlockPos target = p;
                try {
                    BlockPos main = com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache.transformerMainPos(level, p);
                    if (main == null) {
                        main = com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache.windingMainPos(level, p);
                    }
                    if (main != null) target = main;
                } catch (Throwable ignored) {
                }
                // 同一目标被多格命中（多方块映射）→ 取最强那一格
                contribs.merge(target, (double) strength, (a, b) -> Math.max(a, b));
            }
            // 按【风扇位置】登记：幂等替换该风扇的旧贡献（含转向/距离变化的差值）
            FanCoolingRegistry.setFan(pos, contribs);
        } catch (Throwable t) {
            // 永不崩溃
        }
    }
}
