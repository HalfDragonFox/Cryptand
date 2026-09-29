/**
 * ===== 悬垂导线注册器（2026-08-14） =====
 *
 * 集中注册导线类型（{@link SaggingWireType}）——渲染/组装/物品三块参数一处配置。
 * 目前内置三种导线（铜/铁/金，参数对齐原版 PowerGrid wire_types JSON）：
 *   - copper（原版物品 powergrid:wire，纹理 copper_wire.png）
 *   - iron   （powergrid:iron_wire）
 *   - golden （powergrid:golden_wire）
 *
 * 查询：
 *   - {@link #get(id)}：按类型 id
 *   - {@link #byItem(Item)} / {@link #byItemId(String)}：按实际物品（反向绑定）
 */

package com.hdf.cryptand.neoforge.powergrid.device.wire;

import com.hdf.cryptand.neoforge.core.wire.SaggingWireType;
import com.hdf.cryptand.neoforge.cee.CeeWireBridge;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SaggingWireRegistry {

    private static final Map<String, SaggingWireType> BY_ID = new LinkedHashMap<>();
    private static final Map<Item, SaggingWireType> BY_ITEM = new HashMap<>();
    private static final Map<String, SaggingWireType> BY_ITEM_ID = new HashMap<>();

    static {
        registerDefaults();
    }

    private SaggingWireRegistry() {
    }

    /** 注册导线类型（可被外部追加；同 id 覆盖）。
     *  ⚠ 2026-08-22 导线全白根因：此前 BY_ITEM_ID 用 BuiltInRegistries.ITEM.getKey
     *  解析物品注册名——static 初始化期 registry 可能未就绪（getKey=null）→
     *  反查失败 → 放置时 rendererId=null → 客户端纯白。
     *  现以【字符串 itemId】为第一键（不依赖 registry 时机），Item 对象反查保留作兜底。 */
    public static void register(SaggingWireType t) {
        if (t == null || t.id() == null) return;
        BY_ID.put(t.id(), t);
        // 字符串键（静态期即确定，最可靠）
        if (t.itemId() != null) {
            BY_ITEM_ID.put(t.itemId(), t);
        }
        // Item 对象键（运行时加载后可用，兜底）
        if (t.item() != null) {
            BY_ITEM.put(t.item(), t);
            try {
                BY_ITEM_ID.putIfAbsent(BuiltInRegistries.ITEM.getKey(t.item()).toString(), t);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 按类型 id 取（未注册 → null） */
    public static SaggingWireType get(String id) {
        CeeWireBridge.syncIfNeeded();
        return id == null ? null : BY_ID.get(id);
    }

    /** 按实际物品取（物品绑定注册） */
    public static SaggingWireType byItem(Item item) {
        CeeWireBridge.syncIfNeeded();
        return item == null ? null : BY_ITEM.get(item);
    }

    /** 按物品 id 字符串取（"powergrid:wire" 等） */
    public static SaggingWireType byItemId(String itemId) {
        if (itemId == null) return null;
        CeeWireBridge.syncIfNeeded();
        SaggingWireType t = BY_ITEM_ID.get(itemId);
        if (t != null) return t;
        try {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            return item == null ? null : BY_ITEM.get(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ===== 渲染参数引用化解析（2026-08-14：导线只存渲染器 id，按 id 查表取参数） =====

    /** 导线物品 id → 渲染器 id（未注册返回 null → 调用方用默认渲染） */
    public static String rendererIdOf(String itemId) {
        if (itemId == null) return null;
        SaggingWireType t = byItemId(itemId);
        return t == null ? null : t.id();
    }

    /** 渲染器 id → 类型（未注册返回 null） */
    public static SaggingWireType byRendererId(String rendererId) {
        return get(rendererId);
    }

    /** 解析渲染颜色：染色覆盖优先，否则渲染器默认色，未注册 → 白色 */
    public static int resolveColor(String rendererId, int colorOverride) {
        if (colorOverride != 0) return colorOverride;
        SaggingWireType t = get(rendererId);
        return t == null ? 0xFFFFFFFF : t.color();
    }

    /** 解析悬垂率：渲染器 sag；未注册 → 默认 2.0 */
    public static float resolveSag(String rendererId) {
        SaggingWireType t = get(rendererId);
        return t == null ? 2.0f : t.sag();
    }

    /** 解析材质路径：渲染器 texture；未注册 → null（纯色模式） */
    public static String resolveTexture(String rendererId) {
        SaggingWireType t = get(rendererId);
        return (t == null || t.texture() == null) ? null : t.texture().toString();
    }

    /** 全部已注册类型（副本） */
    public static Collection<SaggingWireType> all() {
        return java.util.List.copyOf(BY_ID.values());
    }

    /** 内置导线（铜/铁/金 + 2026-08-22 补齐 PowerGrid 全部 6 线型；
     *  参数对齐原版 wire_types JSON：wire/copper_cord/insulated_copper_wire/
     *  golden_wire/iron_wire/string_light_cord） */
    private static void registerDefaults() {
        // 额定电流（非烧毁阈值）：安全持续工作电流，超过只发热不直接烧毁；
        // 烧毁只看温升（PhasorEngine 段温度 > 200°C）。统一 80A 使同电流下
        // 电阻越低的导线温升越低（P=I²R），低阻导线更难烧——符合现实。
        register(SaggingWireType.builder("copper")
                .texture("powergrid:textures/special/copper_wire.png")
                .color(0xFFB87333)          // 铜色
                .sag(2.0f)
                .thickness(0.0625f)
                .resistancePerMeter(0.0015 * 0.5) // resistancePerItem × itemsPerMeter
                .itemsPerMeter(0.5f)
                .maximumLength(24f)
                .maximumCurrent(200f)       // 25°C 额定电流（R 最低 → 载流最高）
                .itemId("powergrid:wire")
                .build());
        register(SaggingWireType.builder("iron")
                .texture("powergrid:textures/special/iron_wire.png")
                .color(0xFFC0C0C0)          // 铁灰
                .sag(2.0f)
                .thickness(0.125f)          // 原版铁线更粗
                .resistancePerMeter(0.005 * 0.5)
                .itemsPerMeter(0.5f)
                .maximumLength(64f)
                .maximumCurrent(100f)       // 25°C 额定电流（R 最高 → 载流最低）
                .itemId("powergrid:iron_wire")
                .build());
        register(SaggingWireType.builder("golden")
                .texture("powergrid:textures/special/golden_wire.png")
                .color(0xFFFFD700)          // 金色
                .sag(2.0f)
                .thickness(0.0625f)
                .resistancePerMeter(0.003 * 0.5)
                .itemsPerMeter(0.5f)
                .maximumLength(12f)
                .maximumCurrent(160f)       // 25°C 额定电流（基准：2026-08-19 用户指定 ≥160A）
                .itemId("powergrid:golden_wire")
                .build());
        // 2026-08-22 补齐（原版 wire_types JSON 完整 6 型，此前只注册 3 型 →
        // 绝缘铜线/铜绳/灯串 byItemId 未命中 → rendererId=null → 渲染纯白）
        register(SaggingWireType.builder("copper_cord")
                .texture("powergrid:textures/special/insulated_wire.png")
                .color(0xFFFFFFFF)          // 绝缘皮贴图自带色，玩家可染色
                .sag(2.0f)
                .thickness(0.125f)
                .resistancePerMeter(0.0015 * 0.5)
                .itemsPerMeter(0.5f)
                .maximumLength(16f)
                .maximumCurrent(130f)       // 25°C 额定电流（软绳多股：取同电阻粗线的 ~65%）
                .itemId("powergrid:copper_cord")
                .build());
        register(SaggingWireType.builder("insulated_copper_wire")
                .texture("powergrid:textures/special/insulated_wire.png")
                .color(0xFFFFFFFF)
                .sag(2.0f)
                .thickness(0.0625f)
                .resistancePerMeter(0.0015 * 0.5)
                .itemsPerMeter(0.5f)
                .maximumLength(16f)
                .maximumCurrent(160f)      // 25°C 额定电流（绝缘铜，散热略差于裸铜）
                .itemId("powergrid:insulated_copper_wire")
                .build());
        register(SaggingWireType.builder("string_light_cord")
                .texture("powergrid:textures/special/insulated_wire.png")
                .color(0xFFFFFFFF)
                .sag(2.0f)
                .thickness(0.125f)
                .resistancePerMeter(0.0015 * 0.5)
                .itemsPerMeter(0.5f)
                .maximumLength(16f)
                .maximumCurrent(60f)        // 25°C 额定电流（灯串细线：最细 → 载流最低）
                .itemId("powergrid:string_light_cord")
                .build());
    }
}
