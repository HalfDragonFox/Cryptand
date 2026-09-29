package com.hdf.cryptand.neoforge.powergrid.network;

/**
 * ===== 网络可视化色轮（2026-09-13 用户要求）=====
 *
 * 用户原话："网络渲染色轮改随机颜色代码值，可以定义基础色轮然后在此基础上进行
 *  小范围随机，默认的话你看多少色轮比较好，尽可能丰富并且明显一些"。
 *
 * 设计（三条）：
 *
 * ① **基础色轮 = 24 色，黄金角（≈0.618）在 HSV 色相环上均匀铺开**
 *    —— 比手写色表更均匀：黄金角序列的最优性质是【相邻索引色相差异最大】，
 *    因此相邻网络不会撞色；饱和度 0.85 / 明度 1.0 ⇒ 线框在暗背景上够亮够明显。
 *    （为什么是 24：见下面 BASE_COLORS 的注释。）
 *
 * ② **在基础色之上做小范围随机抖动（每通道 ±18/255 ≈ ±7%）**
 *    —— 色相不动、只轻微偏移，观感仍属"同一族"，但相同基础色的不同网络可区分。
 *
 * ③ **随机源 = 网络内容哈希**（不是 Math.random）⇒ 同网络颜色恒定、不闪烁；
 *    只有网络结构变化（增删设备）才换色 —— 与旧实现行为一致。
 *
 * 抖动实现是纯位运算（零对象分配、O(1)），不引入 Random 实例。
 */
public final class NetworkPalette {

    /**
     * 基础色轮数量。
     *
     * 用户问"多少色轮比较好，尽可能丰富并且明显一些"：
     *   · 12 太稀，同屏十来个网络就开始撞色；
     *   · 24 是"丰富"与"相邻可辨"的平衡点（色相间隔约 15°，线框宽度下肉眼可辨）；
     *   · 32/36 更丰富，但相邻色相间隔降到 ~10°，同屏大量线框时容易看混；
     *   · 配合 ±18 的抖动，24 基础色实际可区分【上百种】组合。
     * 想更丰富就把这里调到 32（无需改其它代码）。
     */
    public static final int BASE_COLORS = 24;

    /** 每通道抖动幅度上限（0..255）：18 ≈ 7% —— 肉眼可辨、又不打乱色相归属 */
    public static final int JITTER = 18;

    /** 黄金角（色相步进）：保证相邻索引色相差异最大化 */
    private static final float GOLDEN = 0.6180339887f;

    /** 基础色轮（静态构建一次） */
    private static final int[] BASE = buildBase();

    private NetworkPalette() {
    }

    /** 构建基础色轮：黄金角遍历色相，高饱和高明度 */
    private static int[] buildBase() {
        int[] out = new int[BASE_COLORS];
        for (int i = 0; i < BASE_COLORS; i++) {
            float hue = (i * GOLDEN) % 1.0f;
            out[i] = hsvToArgb(hue, 0.85f, 1.0f);
        }
        return out;
    }

    /**
     * 由网络哈希取【稳定颜色】（基础色 + 小范围抖动）。
     *
     * @param seed 网络内容哈希（同一网络必须恒定，否则线框会闪色）
     * @return ARGB（不透明）
     */
    public static int colorFor(long seed) {
        int base = BASE[(int) Math.floorMod(seed, BASE_COLORS)];
        // 抖动：直接切哈希的位（-32..31），再按 JITTER 缩放 ⇒ 零对象分配、同 seed 恒定
        int jr = (int) ((seed >>> 7) & 0x3FL) - 32;
        int jg = (int) ((seed >>> 17) & 0x3FL) - 32;
        int jb = (int) ((seed >>> 27) & 0x3FL) - 32;
        return 0xFF000000
                | (clamp255(((base >> 16) & 0xFF) + jr * JITTER / 32) << 16)
                | (clamp255(((base >> 8) & 0xFF) + jg * JITTER / 32) << 8)
                | clamp255((base & 0xFF) + jb * JITTER / 32);
    }

    /** 仅基础色（无抖动；诊断/对照用） */
    public static int baseColorFor(long seed) {
        return BASE[(int) Math.floorMod(seed, BASE_COLORS)];
    }

    /** 基础色轮副本（只读用途：诊断/预览） */
    public static int[] basePalette() {
        return BASE.clone();
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** HSV → ARGB（h/s/v ∈ [0,1]） */
    private static int hsvToArgb(float h, float s, float v) {
        float hh = (h - (float) Math.floor(h)) * 6.0f;
        int i = (int) hh;
        float f = hh - i;
        float p = v * (1f - s);
        float q = v * (1f - f * s);
        float t = v * (1f - (1f - f) * s);
        float r, g, b;
        switch (i) {
            case 0 -> { r = v; g = t; b = p; }
            case 1 -> { r = q; g = v; b = p; }
            case 2 -> { r = p; g = v; b = t; }
            case 3 -> { r = p; g = q; b = v; }
            case 4 -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return 0xFF000000 | ((int) (r * 255f) << 16) | ((int) (g * 255f) << 8)
                | (int) (b * 255f);
    }
}
