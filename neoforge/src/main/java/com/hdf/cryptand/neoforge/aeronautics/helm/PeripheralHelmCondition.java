/**
 * ===== 外设船舵数据包加载条件（2026-09-13） =====
 *
 * 方块/物品只在 `aeronautics.toml#enablePeripheralHelm=true` 时注册，但配方与战利品表
 * 始终躺在 jar 里——开关关闭时它们引用的 `cryptand:peripheral_helm` 不存在 ⇒ 每次启动
 * 两条 "Unknown registry key ... cryptand:peripheral_helm" ERROR。
 *
 * 本条件让相关数据包文件按【内容是否已注册】决定加载（与 {@link PeripheralHelmRegistry#isRegistered()}
 * 同一标志，天然与开关一致；数据包加载远晚于 mod 构造期的内容注册）。
 * JSON 用法：
 * <pre>{@code
 * { "neoforge:conditions": [ { "type": "cryptand:peripheral_helm_enabled" } ], ... }
 * }</pre>
 */

package com.hdf.cryptand.neoforge.aeronautics.helm;

import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.common.conditions.ICondition;

public final class PeripheralHelmCondition implements ICondition {

    public static final PeripheralHelmCondition INSTANCE = new PeripheralHelmCondition();

    /** 无条件字段（"type" 由 NeoForge 的分发 codec 处理） */
    public static final MapCodec<PeripheralHelmCondition> CODEC = MapCodec.unit(INSTANCE);

    @Override
    public boolean test(IContext context) {
        return PeripheralHelmRegistry.isRegistered();
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }
}
