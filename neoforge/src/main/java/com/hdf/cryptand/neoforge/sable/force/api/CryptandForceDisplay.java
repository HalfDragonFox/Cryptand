/**
 * ===== 力学可视化注册门面（框架 API，2026-09-14） =====
 *
 * <p>框架的唯一对外入口。其它子系统 / 其它 mod 通过它接入：
 * <pre>
 *   // 1) 注册一个力源（服务端采集；框架负责同步与渲染）
 *   CryptandForceDisplay.registerSource(new MyThrusterForceSource());
 *
 *   // 2) 注册该力的颜色/样式 —— 【注册时决定，之后固定不变】
 *   CryptandForceDisplay.registerStyle(MyForces.THRUST, ForceStyle.of(0xB07BFF, 0.25f, 1.0f));
 * </pre>
 *
 * <p><b>颜色语义（铁律）</b>：
 * <ul>
 *   <li>颜色是【力组的身份属性】，在 {@link #registerStyle} 时一次性决定，之后**不可更改**
 *       （重复注册同 id 被忽略，返回 false）</li>
 *   <li>同一力组的所有力（合力/点力/多个力源）必然同色；球与箭头同色；
 *       不同力组颜色应互不相同</li>
 *   <li>唯一能在"注册后"修正颜色的入口是 {@link #registerColorOverride}（配置覆盖）——
 *       它表达的是"配置优先级"，仍然每个力只定一次色</li>
 * </ul>
 *
 * <p>内置力 id（与官方 Sable ForceGroups 注册名一致，桥接零映射）：
 * {@link #GRAVITY}（Cryptand 自算，官方不记录）、{@link #LIFT}、{@link #DRAG}、
 * {@link #LEVITATION}、{@link #PROPULSION}、{@link #BALLOON_LIFT}、{@link #MAGNETIC_FORCE}。
 *
 * <p>线程安全：注册表为并发容器，允许在 mod 初始化期与运行期注册。
 */
package com.hdf.cryptand.neoforge.sable.force.api;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class CryptandForceDisplay {

    /** Cryptand 自算重力（官方 ForceGroups 不记录重力）。 */
    public static final ResourceLocation GRAVITY = cryptand("gravity");
    /** 官方 sable:gravity（有则沿用）。 */
    public static final ResourceLocation SABLE_GRAVITY = sable("gravity");
    public static final ResourceLocation LIFT = sable("lift");
    public static final ResourceLocation DRAG = sable("drag");
    public static final ResourceLocation LEVITATION = sable("levitation");
    public static final ResourceLocation PROPULSION = sable("propulsion");
    public static final ResourceLocation BALLOON_LIFT = sable("balloon_lift");
    public static final ResourceLocation MAGNETIC_FORCE = sable("magnetic_force");
    /** 质心球（不是力，但作为"中心球"参与渲染）。 */
    public static final ResourceLocation CENTER_OF_MASS = cryptand("center_of_mass");
    /** 合力（各力矢量求和；简化模式的总力箭头）。 */
    public static final ResourceLocation NET_FORCE = cryptand("net_force");

    private static final List<ForceSource> SOURCES = new CopyOnWriteArrayList<>();
    /** 力 id → 最终样式（颜色在注册时决定；此后只读）。 */
    private static final Map<ResourceLocation, ForceStyle> STYLES = new ConcurrentHashMap<>();
    /** 配置颜色覆盖（登记后对所有注册（含已注册）生效，仍是"每力一次定色"）。 */
    private static final Map<ResourceLocation, Integer> COLOR_OVERRIDES = new ConcurrentHashMap<>();

    private CryptandForceDisplay() {
    }

    // ===== 注册 =====

    /** 注册一个力源（同 id 覆盖：力源是采集逻辑，允许热替换）。 */
    public static void registerSource(final ForceSource source) {
        if (source == null || source.id() == null) return;
        SOURCES.removeIf(s -> s.id().equals(source.id()));
        SOURCES.add(source);
        SOURCES.sort(Comparator.comparingInt(ForceSource::priority));
    }

    /**
     * 注册一个力的样式 —— 【首次生效】：颜色在此刻决定，之后不可更改。
     *
     * <p>若该 id 已有配置颜色覆盖（{@link #registerColorOverride}），则以覆盖色为准。
     *
     * @return true = 本次注册被接受（首次）；false = 已注册过，忽略（保持首次决定的颜色）
     */
    public static boolean registerStyle(final ResourceLocation id, final ForceStyle style) {
        if (id == null || style == null) return false;
        final Integer override = COLOR_OVERRIDES.get(id);
        final ForceStyle effective = (override == null) ? style : style.withColor(override);
        return STYLES.putIfAbsent(id, effective) == null;
    }

    /**
     * 配置颜色覆盖（{@code "力id=0xRRGGBB"}）。
     *
     * <p>唯一允许"修正已注册颜色"的入口：表达配置优先级。
     * 对已注册的力立即更新其颜色；对尚未注册的力在注册时套用。
     * 每个力仍然只定一次色（覆盖本身也是"注册时决定"的一部分）。
     */
    public static void registerColorOverride(final ResourceLocation id, final int color) {
        if (id == null) return;
        final int rgb = color & 0xFFFFFF;
        COLOR_OVERRIDES.put(id, rgb);
        STYLES.computeIfPresent(id, (k, old) -> old.withColor(rgb));
    }

    // ===== 查询 =====

    public static Collection<ForceSource> sources() {
        return new ArrayList<>(SOURCES);
    }

    /** 该力的样式（颜色固定）；未注册 → 默认灰。 */
    public static ForceStyle styleOf(final ResourceLocation id) {
        if (id == null) return ForceStyle.DEFAULT;
        return STYLES.getOrDefault(id, ForceStyle.DEFAULT);
    }

    public static Map<ResourceLocation, ForceStyle> styles() {
        return Map.copyOf(STYLES);
    }

    /** 是否已注册任何力源（框架总闸判据）。 */
    public static boolean hasSources() {
        return !SOURCES.isEmpty();
    }

    private static ResourceLocation sable(final String path) {
        return ResourceLocation.fromNamespaceAndPath("sable", path);
    }

    private static ResourceLocation cryptand(final String path) {
        return ResourceLocation.fromNamespaceAndPath("cryptand", path);
    }
}
