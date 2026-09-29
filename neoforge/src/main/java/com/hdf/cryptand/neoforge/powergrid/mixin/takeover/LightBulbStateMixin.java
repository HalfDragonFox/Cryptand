/**
 * ===== 灯座灯泡点亮/烧断（2026-08-22 用户需求："接上灯泡后不会亮也不会烧毁"） =====
 *
 * 自管模式（完全接管）下，原版 LightFixtureBlockEntity.electricalTick 用
 * filament（SwitchedWire）功率调 LightBulbState.applyPower——但自管求解只写
 * 自管宿主，原版 filament 节点电压/功率=0 → applyPower(0) → 灯泡既不亮也不烧。
 *
 * 本 Mixin 在 LightBulbState.applyPower 修改功率参数：从 Cryptand【设备电流
 * 缓存】（DeviceCurrent，后台求解 round 写入）读灯丝电流 I，替换功率为
 * P = I²·R（R = 灯泡当前电阻 resistance()）——温度积分/亮度模型/过热烧断
 * 全部复用原版（applyPower 内部 temperature / overheatTemperature / burn）。
 *
 * 无 Cryptand 电流（未接线/未建模）→ 保持原逻辑（0 功率）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.takeover;

import com.hdf.cryptand.neoforge.powergrid.state.DeviceCurrent;
import net.minecraft.core.BlockPos;
import org.patryk3211.powergrid.electricity.light.fixture.LightFixtureBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(targets = "org.patryk3211.powergrid.electricity.light.bulb.LightBulbState",
       remap = false)
public abstract class LightBulbStateMixin {

    // ⚠ 2026-09-11 修复启动崩溃：原为
    //   @Shadow(remap=false) private LightFixtureBlockEntity fixture;
    //   @Shadow(remap=false) public abstract float resistance();
    // 但当前 PowerGrid 版本的 LightBulbState【没有 fixture 字段】→ Mixin 应用失败
    // （InvalidMixinException: @Shadow field fixture was not located）→ 启动崩溃。
    // 本 mixin 此前因门控默认不注入而从未生效，故该错误一直潜伏。
    // 改为【反射读取 + 缺失即安全降级】——不再依赖任何 @Shadow，注入永不失配。

    /** 反射取灯座（按字段名候选 + 按类型扫描；取不到 → null → 保持原逻辑）。 */
    private LightFixtureBlockEntity cryptand$fixture() {
        try {
            Class<?> c = this.getClass();
            for (String n : new String[]{"fixture", "f_"}) {
                try {
                    java.lang.reflect.Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    Object v = f.get(this);
                    if (v instanceof LightFixtureBlockEntity lf) return lf;
                } catch (Throwable ignored) {
                }
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                if (f.getType() == LightFixtureBlockEntity.class) {
                    f.setAccessible(true);
                    Object v = f.get(this);
                    if (v instanceof LightFixtureBlockEntity lf) return lf;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 反射调用原版 resistance()（缺失 → -1 → 保持原逻辑）。 */
    private float cryptand$resistance() {
        try {
            java.lang.reflect.Method m = this.getClass().getMethod("resistance");
            Object v = m.invoke(this);
            return v instanceof Number n ? n.floatValue() : -1f;
        } catch (Throwable t) {
            return -1f;
        }
    }

    /**
     * 把原版传入的灯丝功率（自管下恒 0）替换为 Cryptand 电流计算的功率 I²R。
     */
    @ModifyVariable(method = "applyPower", at = @At("HEAD"), ordinal = 0,
                    argsOnly = true, remap = false)
    private double cryptand$replaceWirePower(double power) {
        try {
            LightFixtureBlockEntity fx = cryptand$fixture();
            if (fx == null) return power;
            BlockPos pos = fx.getBlockPos();
            double i = DeviceCurrent.read(pos);
            if (Math.abs(i) < 1e-4) return power; // 无 Cryptand 电流 → 原逻辑
            double r = cryptand$resistance();
            if (r <= 0) return power;
            return i * i * r; // P = I²·R（灯丝真实功率）
        } catch (Throwable ignored) {
            return power;
        }
    }
}
