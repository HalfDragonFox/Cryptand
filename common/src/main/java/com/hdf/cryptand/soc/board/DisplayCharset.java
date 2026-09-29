package com.hdf.cryptand.soc.board;

import java.nio.charset.Charset;

/**
 * ===== 字符屏码页（common，纯 Java 零 MC，2026-09-18）=====
 *
 * <p>固件把字符当成<b>单字节</b>写进显存窗口（{@code uint8_t}），用的是 PC 的 <b>CP437</b>
 * 码页 —— 阴影 {@code ░▒▓}、实心块 {@code █}、框线 {@code ─│┌┐└┘} 都在 0x80 以上。
 * 而 OC 的 {@code gpu.set} 收的是 <b>UTF-8 字符串</b>。</p>
 *
 * <p>⚠ 所以<b>绝不能</b>按 Latin-1 直转（{@code (char) 0xB0}）：那会得到 U+00B0（度数符号 °），
 * 屏幕上全是乱的。必须走真正的码页解码 —— 本类用 JDK 自带的 {@code IBM437} 字符集，
 * <b>不自己抄表</b>（抄 128 项 Unicode 码点就是第二份真相，错一个就是乱码）。</p>
 *
 * <p>两类字节被<b>明确</b>当空白（不是猜的，是"这一段的图形符号我们不用"这个决定）：</p>
 * <ul>
 *   <li>{@code 0x00}：CP437 里是 NUL，而 OC 的 {@code TextBuffer} 对 {@code wcwidth <= 0}
 *       的码点<b>直接跳过</b> ⇒ 一旦有 NUL，后面的字符会整体左移、整屏格子错位
 *       （LVGL 的绘制缓冲初值就是 0 ⇒ 这在实际使用中必然出现）；</li>
 *   <li>{@code 0x01-0x1F} 与 {@code 0x7F}：CP437 在这一段是 ☺☻♥ 之类的图形符号，
 *       但 JDK 的 {@code IBM437} 把它们当控制码解（U+0001..），同样会被 OC 跳过 ⇒ 一律当空白。</li>
 * </ul>
 */
public final class DisplayCharset {

    /** CP437（IBM437）码页 —— JDK 自带，无需第三方依赖 */
    public static final Charset CP437 = Charset.forName("IBM437");

    /** 字节 → 该字节在 CP437 里对应的字符串（定长单字符；被当空白的见类注释） */
    private static final String[] TABLE = build();

    private DisplayCharset() {
    }

    private static String[] build() {
        final String[] table = new String[256];
        final byte[] one = new byte[1];
        for (int i = 0; i < 256; i++) {
            one[0] = (byte) i;
            table[i] = new String(one, CP437);
        }
        for (int i = 0; i < 0x20; i++) {
            table[i] = " ";
        }
        table[0x7F] = " ";
        return table;
    }

    /** 把固件的单字节字符解成要交给 OC 的文本（长度恒为 1） */
    public static String decode(int b) {
        return TABLE[b & 0xFF];
    }

    /** 把一段字符码点解成字符串（{@code from}..{@code to} 闭区间，越界返回空串） */
    public static String decodeRange(byte[] code, int from, int to) {
        if (code == null || from < 0 || to < from || to >= code.length) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(to - from + 1);
        for (int i = from; i <= to; i++) {
            sb.append(TABLE[code[i] & 0xFF]);
        }
        return sb.toString();
    }
}
