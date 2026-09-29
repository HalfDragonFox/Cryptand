package com.hdf.cryptand.soc.board;

import java.util.Arrays;

/**
 * ===== 真彩屏字符层的像素化（common，纯 Java 零 MC，2026-09-27）=====
 *
 * <p>把 {@link TrueColorScreen} 在 {@link TrueColorScreen.Mode#TEXT 文本模式}下的
 * 字符缓冲（字符 + 每格前景/背景，8×8 一格）画成 <b>ARGB 像素</b>。这就是"TEXT 模式渲染"
 * 的全部算法：字形位来自 {@link TextFont8x8}，颜色来自设备给的每格 fg/bg。</p>
 *
 * <p><b>为什么放 common</b>：拿掉 Minecraft，"字符格 → 像素"这件事一样成立（它只是查位 + 上色）。
 * MC 侧（原 {@code TrueScreenRenderer}，**已删除**；现由移植来的 OC 屏幕渲染层的 VRAM 内容来源调用本类）只剩"把 int[] 搬进贴图"这一件事，
 * 于是这段逻辑能被离线闸门钉死，不必每改一次就起客户端。</p>
 *
 * <h3>口径</h3>
 * <ul>
 *   <li>输出是 <b>0xAARRGGBB</b>（alpha 恒为 FF：屏幕不透明）；</li>
 *   <li>设备字符格是 <b>1 基</b>（{@code textAt(1,1)} = 左上角第一格），行 0 在最上面；</li>
 *   <li>字体里<b>没有</b>的字符（例如中文）：该格只画背景，<b>不编造字形</b>，
 *       并把格数<b>报给调用方</b>（返回值）—— 不静默；</li>
 *   <li>字符格铺不满的边角（屏宽/高不是 8 的倍数，例如 70×70 只铺 64×64）填
 *       {@link #UNCOVERED}（不透明黑），而不是留着上一帧的像素。</li>
 * </ul>
 *
 * <p>跑法（离线闸门）：{@code ./gradlew :common:runFontTest}</p>
 */
public final class ScreenTextLayer {

    /** 字符格铺不到的像素（不透明黑）—— 明确的一种颜色，不是"没写" */
    public static final int UNCOVERED = 0xFF000000;

    private ScreenTextLayer() {
    }

    /** 便捷入口：按设备尺寸分配一个缓冲并画满（每次调用都新分配，只适合一次性用法） */
    public static int[] paint(TrueColorScreen screen) {
        require(screen);
        final int[] argb = new int[screen.width() * screen.height()];
        paint(screen, argb);
        return argb;
    }

    /**
     * 把字符面画进 {@code argb}。
     *
     * @param argb 长度必须 = {@code width × height}（越界/不足<b>明确报错</b>，不越界写）
     * @return 字体里没有对应字形的格数（0 = 全部认识；调用方据此报一次原因）
     */
    public static int paint(TrueColorScreen screen, int[] argb) {
        require(screen);
        final int w = screen.width();
        final int h = screen.height();
        if (screen.mode() != TrueColorScreen.Mode.TEXT) {
            throw new IllegalStateException("当前是 " + screen.mode()
                    + " 模式：字符层只在 TEXT 模式有意义（像素面请按 VRAM 解码）");
        }
        if (argb == null || argb.length != w * h) {
            throw new IllegalArgumentException("像素缓冲长度必须是 " + (w * h)
                    + "（" + w + "x" + h + "），给了 " + (argb == null ? "null" : argb.length));
        }
        Arrays.fill(argb, UNCOVERED);
        final int cols = screen.textCols();
        final int rows = screen.textRows();
        int unknown = 0;
        for (int row = 1; row <= rows; row++) {
            final int py0 = (row - 1) * TrueColorScreen.CELL_H;
            for (int col = 1; col <= cols; col++) {
                final char ch = screen.textAt(col, row);
                final int fg = screen.textForeground(col, row) & 0xFFFFFF;
                final int bg = screen.textBackground(col, row) & 0xFFFFFF;
                final int code = TextFont8x8.glyphOf(ch);
                if (code < 0) {
                    unknown++;
                }
                final int px0 = (col - 1) * TrueColorScreen.CELL_W;
                // 一格 = CELL_W × CELL_H 像素：字模只占**上** GLYPH_H 行，其余行是背景
                // （字模是 8×8 ROM；换 8×16 字模时这段逻辑不用改，只是 GLYPH_H 变大）。
                for (int y = 0; y < TrueColorScreen.CELL_H; y++) {
                    final int py = py0 + y;
                    if (py >= h) {
                        break;                          // 屏高不是 CELL_H 的倍数：多出来的行不画（留给 UNCOVERED）
                    }
                    final int bits = (code < 0 || y >= TextFont8x8.GLYPH_H) ? 0 : TextFont8x8.row(code, y);
                    final int at = py * w + px0;
                    for (int x = 0; x < TrueColorScreen.CELL_W; x++) {
                        if (px0 + x >= w) {
                            break;                      // 屏宽不是 CELL_W 的倍数：同上
                        }
                        final boolean on = x < TextFont8x8.GLYPH_W
                                && ((bits >> (TextFont8x8.GLYPH_W - 1 - x)) & 1) != 0;
                        argb[at + x] = 0xFF000000 | (on ? fg : bg);
                    }
                }
            }
        }
        return unknown;
    }

    /**
     * 单格里的一个像素（{@code x}/{@code y} 是<b>格内</b>坐标，x ∈ [0, CELL_W)、y ∈ [0, CELL_H)）
     * —— 给闸门与诊断用，也是 {@link #paint} 的"一格"定义（两者不可能不一致）。
     *
     * <p>字模只覆盖上 {@code GLYPH_H} 行：y 超出部分是背景（与 paint 同一口径）。</p>
     */
    public static int cellPixel(char ch, int fg, int bg, int x, int y) {
        final int code = TextFont8x8.glyphOf(ch);
        final boolean on = code >= 0 && y < TextFont8x8.GLYPH_H && x < TextFont8x8.GLYPH_W
                && TextFont8x8.pixel(code, x, y);
        return 0xFF000000 | (on ? fg & 0xFFFFFF : bg & 0xFFFFFF);
    }

    private static void require(TrueColorScreen screen) {
        if (screen == null) {
            throw new IllegalArgumentException("screen 不能为 null");
        }
    }
}
