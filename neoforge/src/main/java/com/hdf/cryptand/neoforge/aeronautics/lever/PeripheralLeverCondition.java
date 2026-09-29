/**
 * ===== 外设拉杆数据包加载条件（2026-09-13） =====
 *
 * 方块只在 `enablePeripheralLever=true` 时注册，但配方与战利品表始终在 jar 里 ——
 * 开关关闭时它们引用的 `cryptand:peripheral_lever` 不存在 ⇒ 启动报 "Unknown registry key" ERROR。
 * 本条件按【内容是否已注册】决定加载（与 {@link PeripheralLeverRegistry#isRegistered()} 同一标志）。
 */

package com.hdf.cryptand.neoforge.aeronautics.lever;

import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.common.conditions.ICondition;

public final class PeripheralLeverCondition implements ICondition {

    public static final PeripheralLeverCondition INSTANCE = new PeripheralLeverCondition();

    public static final MapCodec<PeripheralLeverCondition> CODEC = MapCodec.unit(INSTANCE);

    @Override
    public boolean test(IContext context) {
        return PeripheralLeverRegistry.isRegistered();
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }
}
