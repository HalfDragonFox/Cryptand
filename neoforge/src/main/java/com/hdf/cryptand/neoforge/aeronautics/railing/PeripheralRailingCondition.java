/**
 * ===== 外设栏杆（按钮）数据包加载条件（2026-09-13） =====
 *
 * 方块只在开关开启时注册，配方/战利品表按【内容是否已注册】决定加载，避免引用不存在的方块。
 */

package com.hdf.cryptand.neoforge.aeronautics.railing;

import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.common.conditions.ICondition;

public final class PeripheralRailingCondition implements ICondition {

    public static final PeripheralRailingCondition INSTANCE = new PeripheralRailingCondition();

    public static final MapCodec<PeripheralRailingCondition> CODEC = MapCodec.unit(INSTANCE);

    @Override
    public boolean test(IContext context) {
        return PeripheralRailingRegistry.isRegistered();
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }
}
