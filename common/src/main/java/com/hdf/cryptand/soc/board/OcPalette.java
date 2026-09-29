package com.hdf.cryptand.soc.board;

/**
 * ===== OC 16 色板（common，纯 Java 零 MC，2026-09-28）=====
 *
 * <p><b>为什么需要它</b>：显存窗口的三个平面（code/fg/bg）每格只有 1 字节颜色 ⇒ 里面装的
 * 只能是<b>调色板索引</b>。索引顺序不是随便定的：它就是 OC / OpenOS 的那张表
 * （{@code assets/opencomputers/loot/openos/lib/colors.lua}：{@code [0]="white" … [15]="black"}）。
 * 索引变 RGB 这一步<b>只能在宿主侧做</b>——很多屏是 1 位单色档，直接把它们塞给 OC 的
 * {@code setForegroundColor(index, true)} 会抛 {@code IllegalArgumentException:
 * color palette not supported}（2026-09-27 真机实测：整条页路径因此被永久降级成逐行）。</p>
 *
 * <p>所以这里是<b>唯一一张</b>索引→RGB 的表（固件侧不再各自维护一张），
 * RGB 取 MC 染料色（OC 自己的 {@code Colors.rgbValues} 同源）。</p>
 */
public final class OcPalette {

    /** 索引 0..15 = white / orange / magenta / lightblue / yellow / lime / pink / gray &#10;
     *  silver / cyan / purple / blue / brown / green / red / black */
    private static final int[] RGB = {
            0xF9FFFE,   // 0 white
            0xF9801D,   // 1 orange
            0xC74EBD,   // 2 magenta
            0x3AB3DA,   // 3 lightblue
            0xFED83D,   // 4 yellow
            0x80C71F,   // 5 lime
            0xF38BAA,   // 6 pink
            0x474F52,   // 7 gray
            0x9D9D97,   // 8 silver
            0x169C9C,   // 9 cyan
            0x8932B8,   // 10 purple
            0x3C44AA,   // 11 blue
            0x835432,   // 12 brown
            0x5E7C16,   // 13 green
            0xB02E26,   // 14 red
            0x1D1D21,   // 15 black
    };

    /** 索引 → RGB（越界按 0xF 掩码，绝不抛：一帧画面不该因为一个坏字节整屏黑掉） */
    public static int rgb(int index) {
        return RGB[index & 0xF];
    }

    public static int size() {
        return RGB.length;
    }

    private OcPalette() {
    }
}
