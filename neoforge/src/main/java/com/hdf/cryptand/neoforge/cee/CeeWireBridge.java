/**
 * ===== CEE 导线 → 自研注册器桥（2026-08-22 "两个 mod 导线统一走自定义注册器"） =====
 *
 * CEE（Create-Electro-Energetics）的 WireType 通过本桥反射枚举
 * （com.george_vi.electroenergetics.CEEWireTypes 的公共 DeferredHolder 静态字段），
 * 把每种 CEE 线型【等价注册进 SaggingWireRegistry】——id 用 CEE 注册 id
 * （"electroenergetics:copper" 等），物品绑定 CEE 线盘/掉落物，参数映射：
 *   - resistancePerMeter ← CEE getResistance()（CEE 每米电阻）
 *   - sag / thickness  ← getSag() / getThickness()
 *   - maximumLength   ← getMaxLength()
 *   - color           ← Dyeable.getColor()（染色线）或按 id 近似色
 * 此后 CEE 导线与 PowerGrid 导线在 Cryptand 自管电路里【识别/渲染/组装
 * 参数查询统一走 SaggingWireRegistry】，不再区分来源 mod。
 *
 * 【分离性】仅在 CEE 支持开启 + 仿真核心启用 + CEE mod 已加载时同步；
 * 任一关闭 → 不注册（+ 已有注册不受影响；查询 miss 自然回退）。
 *
 * 同步时机：惰性（SaggingWireRegistry 查询 miss 时触发 syncIfNeeded()），
 * 保证 CEE 的 DeferredRegister 已冻结、WireType 可 get()。
 * 纯反射，无 CEE 编译期依赖（CEE 未装 → Class.forName 抛错 → 静默跳过）。
 */
package com.hdf.cryptand.neoforge.cee;

import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import com.hdf.cryptand.neoforge.core.wire.SaggingWireType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.registries.DeferredHolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class CeeWireBridge {

    /** CEE 线型注册类（反射；无硬依赖） */
    private static final String CEE_WIRE_TYPES = "com.george_vi.electroenergetics.CEEWireTypes";

    private static volatile boolean done = false;
    private static volatile long lastAttempt = 0;

    private CeeWireBridge() {
    }

    /** 惰性同步（幂等）：条件不满足/失败时后续查询会重试（30s 间隔）。 */
    public static void syncIfNeeded() {
        if (done) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < 30_000 && !done) return;
        synchronized (CeeWireBridge.class) {
            if (done) return;
            lastAttempt = now;
            try {
                if (!active()) return;
                doSync();
                done = true;
            } catch (Throwable ignored) {
                // CEE 未装/注册未冻结/反射失败 → 保持未完成，下次查询再试
            }
        }
    }

    /** 分离性门控：CEE 支持开启 && 仿真核心启用 && CEE mod 已加载 */
    public static boolean active() {
        try {
            if (!CeeTerminalSupport.ceeEnabled()) return false;
            return CeeTerminalSupport.ceeModLoaded();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 枚举 CEEWireTypes 公共静态字段（DeferredHolder 单值/数组）→ 逐个注册 */
    private static void doSync() throws Exception {
        Class<?> cls = Class.forName(CEE_WIRE_TYPES);
        for (Field f : cls.getFields()) {
            try {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    Object v = f.get(null);
                    if (v instanceof DeferredHolder<?, ?> h) {
                        registerHolder(h);
                    } else if (v instanceof DeferredHolder<?, ?>[] arr) {
                        for (DeferredHolder<?, ?> h : arr) {
                            if (h != null) registerHolder(h);
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 单字段失败不中断其余
            }
        }
    }

    /** 单个 CEE WireType → SaggingWireType 注册 */
    private static void registerHolder(DeferredHolder<?, ?> holder) {
        try {
            ResourceLocation rl = holderId(holder);
            Object wt = holder.get();
            if (rl == null || wt == null) return;
            // 装饰线不导电 → 不注册（识别留给 CEE 原生渲染）
            if (invokeBool(wt, "isDecorative", false)) return;
            Item spool = invokeItem(wt, "getSpooledItem");
            Item drops = invokeItem(wt, "getDrops");
            Item bind = (spool != null && spool != Items.AIR) ? spool
                    : (drops != null && drops != Items.AIR) ? drops : null;
            double resistance = invokeDouble(wt, "getResistance", 0.0015);
            float sag = (float) invokeDouble(wt, "getSag", 1.0);
            float thickness = (float) invokeDouble(wt, "getThickness", 0.0625);
            float maxLength = (float) invokeDouble(wt, "getMaxLength", 24.0);
            SaggingWireType t = SaggingWireType.builder(rl.toString())
                    .sag(sag)
                    .thickness(thickness)
                    .color(colorOf(wt, rl))
                    .resistancePerMeter(resistance)
                    .itemsPerMeter(0.5f)
                    .maximumLength(maxLength)
                    .maximumCurrent(80f)   // CEE 无额定电流概念，用默认安全值
                    .item(bind)
                    .build();
            SaggingWireRegistry.register(t);
        } catch (Throwable ignored) {
        }
    }

    /** DeferredHolder → ResourceLocation（getId / getKey().location() 兜底） */
    private static ResourceLocation holderId(DeferredHolder<?, ?> holder) {
        try {
            Method m = holder.getClass().getMethod("getId");
            Object v = m.invoke(holder);
            if (v instanceof ResourceLocation rl) return rl;
        } catch (Throwable ignored) {
        }
        try {
            Method m = holder.getClass().getMethod("getKey");
            Object key = m.invoke(holder);
            if (key != null) {
                Method loc = key.getClass().getMethod("location");
                Object v = loc.invoke(key);
                if (v instanceof ResourceLocation rl) return rl;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** CEE 线颜色：Dyeable.getColor()（DyeColor.getTextColor）优先；否则 id 近似色 */
    private static int colorOf(Object wt, ResourceLocation id) {
        try {
            Method m = wt.getClass().getMethod("getColor");
            Object color = m.invoke(wt);
            if (color != null) {
                Method tc = color.getClass().getMethod("getTextColor");
                Object v = tc.invoke(color);
                if (v instanceof Number n) return (0xFF000000 | n.intValue());
            }
        } catch (Throwable ignored) {
        }
        String path = id.getPath();
        if (path.startsWith("colored_")) {
            String c = path.substring("colored_".length());
            try {
                Object dye = net.minecraft.world.item.DyeColor.valueOf(c.toUpperCase(java.util.Locale.ROOT));
                Method tc = dye.getClass().getMethod("getTextColor");
                Object v = tc.invoke(dye);
                if (v instanceof Number n) return (0xFF000000 | n.intValue());
            } catch (Throwable ignored) {
            }
        }
        // 近似色（CEE 线渲染为纯色模式；后续可换 CEE PartialModel 贴图）
        if (path.equals("copper")) return 0xFFB87333;
        if (path.equals("iron")) return 0xFFC0C0C0;
        if (path.equals("electrum")) return 0xFFD4AF37;
        if (path.equals("creative")) return 0xFF55FF55;
        if (path.equals("standard")) return 0xFFD0D0D0;
        if (path.equals("heavily_insulated")) return 0xFF9A9A9A;
        if (path.equals("bundle_conductor")) return 0xFF8A8A8A;
        return 0xFFC0C0C0;
    }

    private static boolean invokeBool(Object target, String name, boolean def) {
        try {
            Object v = target.getClass().getMethod(name).invoke(target);
            return v instanceof Boolean b ? b : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static double invokeDouble(Object target, String name, double def) {
        try {
            Object v = target.getClass().getMethod(name).invoke(target);
            return v instanceof Number n ? n.doubleValue() : def;
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static Item invokeItem(Object target, String name) {
        try {
            Object v = target.getClass().getMethod(name).invoke(target);
            return v instanceof Item item ? item : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
