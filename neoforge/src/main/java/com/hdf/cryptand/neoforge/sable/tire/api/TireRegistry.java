/**
 * ===== 轮胎模型注册表（轮胎拟真框架 API，2026-09-14） =====
 *
 * <p>【扩展点】延续力显示框架的注册式风格：注册 = 模型 + 参数 + 是否默认。
 * <pre>
 *   TireRegistry.register(
 *       ResourceLocation.fromNamespaceAndPath("mymod", "rally_tire"),
 *       new TireParams(7.5, 5.0, Math.toRadians(9), 0.18, 0.02, 1.1),
 *       new MyPacejkaModel(),
 *       false);
 * </pre>
 *
 * <p>参数解析顺序（{@link #paramsFor}）：配置项覆盖 > 注册的模型参数 > 内置默认。
 * 兼容"按轮胎细分"的扩展（后续可加 radius → 参数表）。
 */
package com.hdf.cryptand.neoforge.sable.tire.api;

import com.hdf.cryptand.neoforge.sable.tire.impl.LinearSaturationTire;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TireRegistry {

    /** 一条注册项。 */
    public record Entry(ResourceLocation id, TireParams params, TireModel model) {
    }

    private static final Map<ResourceLocation, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static volatile ResourceLocation defaultId =
            ResourceLocation.fromNamespaceAndPath("cryptand", "linear_saturation");

    private TireRegistry() {
    }

    /** 注册（幂等：同 id 覆盖）；{@code makeDefault=true} 时同时设为默认模型。 */
    public static void register(final ResourceLocation id, final TireParams params,
                                final TireModel model, final boolean makeDefault) {
        if (id == null || model == null) return;
        ENTRIES.put(id, new Entry(id, params == null ? TireParams.DEFAULT : params, model));
        if (makeDefault) defaultId = id;
    }

    public static Entry byId(final ResourceLocation id) {
        return id == null ? null : ENTRIES.get(id);
    }

    /** 默认模型（未注册任何模型时回退内置线性饱和模型）。 */
    public static Entry defaultEntry() {
        final Entry e = ENTRIES.get(defaultId);
        if (e != null) return e;
        return new Entry(defaultId, TireParams.DEFAULT, LinearSaturationTire.INSTANCE);
    }

    /**
     * 解析实际使用的参数：以注册参数为底，再用配置项覆盖（配置优先，便于调参）。
     *
     * @param registered 注册的模型参数（null → 内置默认）
     */
    public static TireParams paramsFor(final TireParams registered) {
        final TireParams base = registered == null ? TireParams.DEFAULT : registered;
        try {
            return new TireParams(
                    com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_TIRE_LATERAL_STIFFNESS.get(),
                    com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_TIRE_LONGITUDINAL_STIFFNESS.get(),
                    Math.toRadians(com.hdf.cryptand.neoforge.sable.config.ConfigSable
                            .SABLE_TIRE_PEAK_SLIP_ANGLE_DEG.get()),
                    com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_TIRE_PEAK_SLIP_RATIO.get(),
                    com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_TIRE_ROLLING_RESISTANCE.get(),
                    com.hdf.cryptand.neoforge.sable.config.ConfigSable.SABLE_TIRE_MU_SCALE.get());
        } catch (final Throwable t) {
            return base;
        }
    }

    /** 已注册模型数（诊断）。 */
    public static int size() {
        return ENTRIES.size();
    }
}
