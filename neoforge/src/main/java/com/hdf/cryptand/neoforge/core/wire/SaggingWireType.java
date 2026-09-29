/**
 * ===== 悬垂导线类型（2026-08-14 注册器：集中定义一种导线的全部参数） =====
 *
 * 一种导线 = 一个 {@link SaggingWireType}，集中配置三块能力：
 *   - 【渲染】SaggingWireRenderer 用：材质（texture）/ 纯色+颜色代码（color，
 *     默认方式：无材质时用纯色染色）/ 悬垂率（sag）/ 粗细（thickness）
 *   - 【组装】WireAssembler 用：电阻值（resistancePerMeter，Ω/格）+ 电气限制
 *     （itemsPerMeter / maximumLength / maximumCurrent）
 *   - 【实际物品绑定】（可选）：item（剪线返回物品 / 按物品反查类型）
 *
 * 默认值对齐原版 PowerGrid wire_types（copper/golden/iron JSON）。
 */

package com.hdf.cryptand.neoforge.core.wire;


import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

public final class SaggingWireType {

    private final String id;
    /** 材质（可 null → 纯色+颜色代码模式） */
    private final ResourceLocation texture;
    /** 颜色代码（ARGB；纯色注册的默认方式） */
    private final int color;
    /** 悬垂率（渲染；CEE 二次曲线 dip） */
    private final float sag;
    /** 导线粗细（格；原版 0.0625 / 铁 0.125） */
    private final float thickness;
    /** 电阻值（Ω/格；组装默认电阻） */
    private final double resistancePerMeter;
    /** 每米物品消耗 */
    private final float itemsPerMeter;
    /** 最大长度（格） */
    private final float maximumLength;
    /** 最大电流（A） */
    private final float maximumCurrent;
    /** 实际物品 id 字符串（反查键；静态注册期 registry 可能未就绪，字符串最可靠） */
    private final String itemId;
    /** 实际物品绑定（可 null=纯渲染/虚拟导线；静态注册期解析失败时为 null） */
    private final Item item;

    private SaggingWireType(Builder b) {
        this.id = b.id;
        this.texture = b.texture;
        this.color = b.color;
        this.sag = b.sag;
        this.thickness = b.thickness;
        this.resistancePerMeter = b.resistancePerMeter;
        this.itemsPerMeter = b.itemsPerMeter;
        this.maximumLength = b.maximumLength;
        this.maximumCurrent = b.maximumCurrent;
        this.itemId = b.itemId;
        this.item = b.item;
    }

    public String id() { return id; }
    /** 实际物品 id 字符串（"powergrid:wire" 等；静态注册期即可用） */
    public String itemId() { return itemId; }
    public ResourceLocation texture() { return texture; }
    public int color() { return color; }
    public float sag() { return sag; }
    public float thickness() { return thickness; }
    public double resistancePerMeter() { return resistancePerMeter; }
    public float itemsPerMeter() { return itemsPerMeter; }
    public float maximumLength() { return maximumLength; }
    public float maximumCurrent() { return maximumCurrent; }
    public Item item() { return item; }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    @Override
    public String toString() {
        return "SaggingWireType{" + id + " tex=" + texture + " color="
                + String.format("%08X", color) + " sag=" + sag
                + " R=" + resistancePerMeter + "Ω/m}";
    }

    /** 链式构造 */
    public static final class Builder {
        private final String id;
        private ResourceLocation texture;
        private int color = 0xFFC0C0C0;   // 默认灰（纯色模式）
        private float sag = 2.0f;
        private float thickness = 0.0625f;
        private double resistancePerMeter = 0.001;
        private float itemsPerMeter = 0.5f;
        private float maximumLength = 24f;
        private float maximumCurrent = 80f;
        private String itemId;
        private Item item;

        Builder(String id) { this.id = id; }

        /** 材质注册 */
        public Builder texture(ResourceLocation t) { this.texture = t; return this; }
        public Builder texture(String rl) { this.texture = ResourceLocation.tryParse(rl); return this; }
        /** 纯色 + 指定颜色代码（默认方式） */
        public Builder color(int argb) { this.color = argb | 0xFF000000; return this; }
        /** 悬垂率（渲染） */
        public Builder sag(float s) { this.sag = s; return this; }
        /** 粗细 */
        public Builder thickness(float t) { this.thickness = t; return this; }
        /** 电阻值（Ω/格；组装默认电阻） */
        public Builder resistancePerMeter(double r) { this.resistancePerMeter = r; return this; }
        public Builder itemsPerMeter(float v) { this.itemsPerMeter = v; return this; }
        public Builder maximumLength(float v) { this.maximumLength = v; return this; }
        public Builder maximumCurrent(float v) { this.maximumCurrent = v; return this; }
        /** 实际物品绑定 */
        public Builder item(Item it) { this.item = it; return this; }
        /** 实际物品绑定（字符串 id）。⚠ 静态注册期 registry 可能未就绪，
         *  Item 解析允许失败（item 保持 null），但 itemId 字符串必须始终保存——
         *  反查（byItemId）以字符串为第一键，不依赖 Item 对象（2026-08-22 导线全白根因）。 */
        public Builder itemId(String itemId) {
            this.itemId = itemId;
            try {
                this.item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
            } catch (Throwable ignored) {
            }
            return this;
        }

        public SaggingWireType build() { return new SaggingWireType(this); }
    }
}
