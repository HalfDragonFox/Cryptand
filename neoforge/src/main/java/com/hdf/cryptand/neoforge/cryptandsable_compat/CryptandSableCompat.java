package com.hdf.cryptand.neoforge.cryptandsable_compat;

/**
 * CryptandSable 兼容子包（cryptandsable_compat）—— 与 {@code cryptandsable}（主包）平级，
 * 同属 {@code com.hdf.cryptand.neoforge}。
 *
 * <p>专用于【兼容其他 mod】：当 aeronautics / simulated / offroad / sable_schematic_api /
 * CEE 等第三方 mod 按其自身编译视角调用 sable 时，用 mixin 等手段把 Minecraft 原版类或
 * 第三方类桥接进 CryptandSable 语义面，避免 ClassCastException / NoSuchMethodError。
 *
 * <p>组织（与 cryptandsable 主包平级的独立子树）：
 * <ul>
 *   <li>{@code cryptandsable_compat.mixin.…} —— 兼容 mixin（让原版/第三方类 implements sable 接口）</li>
 *   <li>{@code cryptandsable_compat.bridge.…} —— 若需要运行时桥接（非 mixin 的 Java 桥）</li>
 * </ul>
 *
 * <p>本类为兼容子包锚点：包存在即代表 CryptandSable 对外兼容层接入；无需逻辑。
 */
public final class CryptandSableCompat {
    private CryptandSableCompat() {
    }
}