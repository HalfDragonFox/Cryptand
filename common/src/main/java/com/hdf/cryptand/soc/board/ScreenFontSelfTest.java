package com.hdf.cryptand.soc.board;

import java.nio.charset.Charset;

/**
 * ===== 8×8 字模 + TEXT 模式字符层闸门（common，纯 Java 零 MC，2026-09-27）=====
 *
 * <p>为什么必须有这道闸门：真彩屏的 TEXT 模式渲染只有两个输入 —— <b>字模</b>与<b>设备的字符格</b>。
 * 字模是 2048 字节的裸数据（抄错一个字节、少一个字形、位序反了都只能靠肉眼在客户端截图里发现），
 * 字符层是"查位 + 上色"。两条都能<b>不需要 Minecraft</b>钉死 ⇒ 就在这里钉死。</p>
 *
 * <p>覆盖：字模完整性（256 字形 × 8 字节）、取位函数（bit7 = 最左像素，逐位对拍经典字模）、
 * 空字符 = 全 0 的边界、Unicode → 字形号与 CP437 码页<b>一一对应</b>（256 个字节一个不差）、
 * 字符层像素化（颜色/越界/掉字形/边角）与全部 256 个字形都能画出来。</p>
 *
 * <p>跑法：{@code ./gradlew :common:runFontTest}</p>
 */
public final class ScreenFontSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        fontIntegrity();
        bitOrder();
        cp437Mapping();
        textLayer();
        edgeCases();
        preview();

        System.out.println("[FONT] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 1. 字模完整性 ====================

    private static void fontIntegrity() {
        check("字形数 = 256（一整张 CP437 码页）", TextFont8x8.GLYPH_COUNT == 256);
        check("字形 = 8×8", TextFont8x8.GLYPH_W == 8 && TextFont8x8.GLYPH_H == 8);
        check("字模 = 256 字形 × 8 字节 = 2048 字节", TextFont8x8.ROM_BYTES == 2048);
        check("字模字节数就是 2048（不是「声明了但少了」）", TextFont8x8.romBytes() == 2048);
        check("256 个字形全部可读（0..7 行一行不少）", allGlyphRowsReadable());
        check("字形号越界明确报错（不静默给空白）", fails(() -> TextFont8x8.row(256, 0), "字形号越界"));
        check("负字形号明确报错", fails(() -> TextFont8x8.row(-1, 0), "字形号越界"));
        check("行号越界明确报错（第 8 行不存在）", fails(() -> TextFont8x8.row(0, 8), "字形行越界"));
    }

    private static boolean allGlyphRowsReadable() {
        for (int code = 0; code < TextFont8x8.GLYPH_COUNT; code++) {
            for (int y = 0; y < TextFont8x8.GLYPH_H; y++) {
                final int b = TextFont8x8.row(code, y);
                if (b < 0 || b > 0xFF) {
                    return false;
                }
            }
        }
        return true;
    }

    // ==================== 2. 取位函数（bit7 = 最左像素） ====================

    private static void bitOrder() {
        // 经典 CP437 字模里的三个字形（值取自 IBM VGA 8×8 ROM，不是我们编的）
        check("'A' 的 8 行 = ROM 原值 30 78 CC CC FC CC CC 00",
                rows(0x41, 0x30, 0x78, 0xCC, 0xCC, 0xFC, 0xCC, 0xCC, 0x00));
        check("'H' 的 8 行 = ROM 原值 CC CC CC FC CC CC CC 00",
                rows(0x48, 0xCC, 0xCC, 0xCC, 0xFC, 0xCC, 0xCC, 0xCC, 0x00));
        check("'─'(0xC4) 只有中间一行是实线", TextFont8x8.row(0xC4, 4) == 0xFF
                && TextFont8x8.row(0xC4, 0) == 0 && TextFont8x8.row(0xC4, 7) == 0);
        check("'█'(0xDB) 8 行全亮", !TextFont8x8.blank(0xDB) && allRows(0xDB, 0xFF));

        // 位序：0x30 = 0b0011_0000 ⇒ 点亮的必须是 x=2、x=3（bit7 在最左）
        check("bit7 = 最左像素：0x30 ⇒ x=2/3 亮，x=0/1/4..7 灭",
                TextFont8x8.pixel(0x41, 2, 0) && TextFont8x8.pixel(0x41, 3, 0)
                        && !TextFont8x8.pixel(0x41, 0, 0) && !TextFont8x8.pixel(0x41, 1, 0)
                        && !TextFont8x8.pixel(0x41, 4, 0) && !TextFont8x8.pixel(0x41, 7, 0));
        // 逐位对拍整张表：pixel(code,x,y) 必须恒等于"第 y 行右移 (7-x) 位"
        check("256×8×8 全表逐位对拍取位函数", bitwiseMatchesRawRows());
        check("字形 x 越界明确报错", fails(() -> TextFont8x8.pixel(0x41, 8, 0), "字形 x 越界"));
        check("字形 y 越界明确报错", fails(() -> TextFont8x8.pixel(0x41, 0, -1), "字形行越界"));
    }

    private static boolean rows(int code, int... expected) {
        for (int y = 0; y < TextFont8x8.GLYPH_H; y++) {
            if (TextFont8x8.row(code, y) != expected[y]) {
                return false;
            }
        }
        return true;
    }

    private static boolean allRows(int code, int value) {
        for (int y = 0; y < TextFont8x8.GLYPH_H; y++) {
            if (TextFont8x8.row(code, y) != value) {
                return false;
            }
        }
        return true;
    }

    private static boolean bitwiseMatchesRawRows() {
        for (int code = 0; code < TextFont8x8.GLYPH_COUNT; code++) {
            for (int y = 0; y < TextFont8x8.GLYPH_H; y++) {
                final int raw = TextFont8x8.row(code, y);
                for (int x = 0; x < TextFont8x8.GLYPH_W; x++) {
                    if (TextFont8x8.pixel(code, x, y) != (((raw >> (7 - x)) & 1) != 0)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // ==================== 3. 空字符边界 + Unicode → 字形号 ====================

    private static void cp437Mapping() {
        // 边界：空字符 = 全 0
        check("空字符：码页 0x00（NUL）整格全 0", TextFont8x8.blank(0x00) && allRows(0x00, 0));
        check("空字符：码页 0x20（空格）整格全 0", TextFont8x8.blank(TextFont8x8.SPACE) && allRows(0x20, 0));
        check("非空字符不会被当成空（'A'）", !TextFont8x8.blank(0x41));
        check("空格是「有字形且全 0」，不是「没字形」", TextFont8x8.hasGlyph(' '));

        // Unicode → 字形号：ASCII、拉丁扩展、制表符、实心块、希腊、数学
        check("'A' → 0x41", TextFont8x8.glyphOf('A') == 0x41);
        check("' ' → 0x20", TextFont8x8.glyphOf(' ') == 0x20);
        check("'é' → 0x82 / 'ü' → 0x81", TextFont8x8.glyphOf('é') == 0x82
                && TextFont8x8.glyphOf('ü') == 0x81);
        check("'░' → 0xB0 / '│' → 0xB3 / '═' → 0xCD", TextFont8x8.glyphOf('░') == 0xB0
                && TextFont8x8.glyphOf('│') == 0xB3 && TextFont8x8.glyphOf('═') == 0xCD);
        check("'█' → 0xDB / '▄' → 0xDC / '▀' → 0xDF", TextFont8x8.glyphOf('█') == 0xDB
                && TextFont8x8.glyphOf('▄') == 0xDC && TextFont8x8.glyphOf('▀') == 0xDF);
        check("'α' → 0xE0 / 'π' → 0xE3 / '∞' → 0xEC", TextFont8x8.glyphOf('α') == 0xE0
                && TextFont8x8.glyphOf('π') == 0xE3 && TextFont8x8.glyphOf('∞') == 0xEC);
        check("'≡' → 0xF0 / '√' → 0xFB / '■' → 0xFE", TextFont8x8.glyphOf('≡') == 0xF0
                && TextFont8x8.glyphOf('√') == 0xFB && TextFont8x8.glyphOf('■') == 0xFE);
        check("字形号反查字符：0x41 → 'A'", TextFont8x8.unicodeOf(0x41) == 'A');

        // 字体里没有的字符：**明确说没有**，绝不静默换字形
        check("中文没有字形 ⇒ -1（不是随便找一个近似字形）",
                TextFont8x8.glyphOf('中') == -1 && !TextFont8x8.hasGlyph('中'));

        // 与 JDK 的 CP437 码页一一对应（256 个字节一个不差）
        check("码页 256 个字节与字形号一一对应（一个不差）", codePageBijection());
        check("码页映射项 = 256（没有被跳过的字节）", TextFont8x8.mappedCodes() == 256);
    }

    private static boolean codePageBijection() {
        final Charset cp437 = DisplayCharset.CP437;
        final byte[] one = new byte[1];
        for (int b = 0; b < 256; b++) {
            one[0] = (byte) b;
            final String s = new String(one, cp437);
            if (s.length() != 1 || TextFont8x8.glyphOf(s.charAt(0)) != b) {
                System.out.println("      码页不匹配：字节 0x" + Integer.toHexString(b)
                        + " → " + s.length() + " 个字符 → 字形 "
                        + TextFont8x8.glyphOf(s.charAt(0)));
                return false;
            }
        }
        return true;
    }

    // ==================== 4. 字符层像素化 ====================

    private static void textLayer() {
        // 字符格 = 8×16 像素（OC 终端口径）：8 列 × 8 行 ⇒ 设备 64 × 128
        final TrueColorScreen s = TrueColorScreen.of(64, 128, 8);
        s.setMode(TrueColorScreen.Mode.TEXT);
        final int fg = 0x00FF00;                        // 绿
        final int bg = 0x0000FF;                        // 蓝
        s.setText(1, 1, 'A', fg, bg);
        final int[] px = ScreenTextLayer.paint(s);

        check("缓冲长度 = 宽 × 高", px.length == 64 * 128);
        check("字形点亮处 = 前景色（'A' 第 0 行 x=2）", px[0 * 64 + 2] == 0xFF00FF00);
        check("字形未点亮处 = 背景色（'A' 第 0 行 x=0）", px[0 * 64 + 0] == 0xFF0000FF);
        check("第 5 行 0xCC ⇒ x=4/5 也是前景", px[5 * 64 + 4] == 0xFF00FF00
                && px[5 * 64 + 5] == 0xFF00FF00);
        check("别的格是设备默认色（黑底黑字，不是这格的蓝）", px[0 * 64 + 8] == 0xFF000000);
        check("第二行格子的像素从 y=CELL_H(16) 起（行不串）", px[16 * 64 + 2] == 0xFF000000);
        check("返回的「掉字形格数」= 0", ScreenTextLayer.paint(s, new int[64 * 128]) == 0);

        // cellPixel 与 paint 是同一份定义（逐像素对拍）
        boolean same = true;
        for (int y = 0; y < TrueColorScreen.CELL_H && same; y++) {
            for (int x = 0; x < TrueColorScreen.CELL_W; x++) {
                if (px[y * 64 + x] != ScreenTextLayer.cellPixel('A', fg, bg, x, y)) {
                    same = false;
                    break;
                }
            }
        }
        check("cellPixel 与 paint 逐像素一致（只有一份定义）", same);

        // 字体里没有的字符：只画背景，别编字形，并且要把格数报出来
        final TrueColorScreen u = TrueColorScreen.of(64, 128, 8);
        u.setMode(TrueColorScreen.Mode.TEXT);
        u.setText(1, 1, '中', fg, bg);
        final int[] up = ScreenTextLayer.paint(u);
        check("掉字形的格子只画背景（不编造字形）", up[0] == 0xFF0000FF && up[7] == 0xFF0000FF);
        check("掉字形格数如实上报（1 格）", ScreenTextLayer.paint(u, new int[64 * 128]) == 1);

        // 256 个字形都能画出来（不抛、不剩"未知"）
        final TrueColorScreen all = TrueColorScreen.of(128, 256, 8);      // 16 列 × 16 行 = 256 格（格 8×16）
        all.setMode(TrueColorScreen.Mode.TEXT);
        for (int code = 0; code < 256; code++) {
            // ⚠ 必须写**码页字符**（unicodeOf）而不是 (char) code：CP437 的高 128 个字节
            //   对应的 Unicode 是 U+00C7/U+2591/U+03B1 这些，不是 U+0080..U+00FF（那是 Latin-1 控制区）
            all.setText(code % 16 + 1, code / 16 + 1, TextFont8x8.unicodeOf(code), 0xFFFFFF, 0x000000);
        }
        final int unknown = ScreenTextLayer.paint(all, new int[128 * 256]);
        check("256 个码页字形全部认得（掉字形 = 0）", unknown == 0);
    }

    // ==================== 5. 边界（模式 / 缓冲 / 铺不满的边角） ====================

    private static void edgeCases() {
        // 模式闸门：图形模式下字符层没有意义 ⇒ 明确报错，不按像素解释
        final TrueColorScreen g = TrueColorScreen.of(64, 128, 8);
        g.setMode(TrueColorScreen.Mode.GRAPHICS);
        check("图形模式下画字符层明确报错",
                fails(() -> ScreenTextLayer.paint(g, new int[64 * 128]), "字符层只在 TEXT 模式"));

        // 缓冲长度
        final TrueColorScreen t = TrueColorScreen.of(64, 128, 8);
        t.setMode(TrueColorScreen.Mode.TEXT);
        check("像素缓冲长度不对明确报错（不越界写）",
                fails(() -> ScreenTextLayer.paint(t, new int[10]), "像素缓冲长度必须是 8192"));
        check("像素缓冲为 null 明确报错",
                fails(() -> ScreenTextLayer.paint(t, null), "像素缓冲长度必须是 8192"));

        // 屏宽/高不是格尺寸的倍数：70px ⇒ 列 70/8=8、行 70/16=4，只有 64×64 像素有字符，剩下的用 UNCOVERED
        final TrueColorScreen odd = TrueColorScreen.of(70, 70, 8);
        odd.setMode(TrueColorScreen.Mode.TEXT);
        odd.setText(1, 1, 'A', 0x00FF00, 0x0000FF);
        // 最后一列（第 8 列）也设成蓝底：默认的"黑底"与 UNCOVERED 同色，证明不了"这一格被画过"
        odd.setText(8, 1, 'B', 0x00FF00, 0x0000FF);
        final int[] px = ScreenTextLayer.paint(odd);
        check("70×70 只有 8 列 × 4 行（64×64 像素；格 8×16）", odd.textCols() == 8 && odd.textRows() == 4);
        check("格内像素正常（x=63 是最后一格 = 背景蓝）", px[0 * 70 + 63] == 0xFF0000FF);
        check("格铺不到的边角 = UNCOVERED（不是上一帧的残留）",
                px[0 * 70 + 64] == ScreenTextLayer.UNCOVERED && px[0 * 70 + 69] == ScreenTextLayer.UNCOVERED);
        check("最后一行之下也是 UNCOVERED", px[69 * 70 + 0] == ScreenTextLayer.UNCOVERED);

        // 设备自己的模式闸门（字符层依赖它：GRAPHICS 下 textAt 会抛，字符层不该自己去碰）
        check("设备在图形模式下读字符格明确报错",
                fails(() -> g.textAt(1, 1), "字符读写要先 setMode(TEXT)"));
    }

    // ==================== 6. 肉眼可查的预览（写进闸门日志） ====================

    private static void preview() {
        System.out.println("      字模预览（CP437 ROM，'#' = 点亮）：");
        for (int code : new int[]{0x41, 0x48, 0xB0, 0xC4, 0xDB, 0xE3}) {
            System.out.println("        0x" + Integer.toHexString(code).toUpperCase()
                    + " (" + describe(code) + ")");
            for (int y = 0; y < 8; y++) {
                final StringBuilder sb = new StringBuilder("          ");
                for (int x = 0; x < 8; x++) {
                    sb.append(TextFont8x8.pixel(code, x, y) ? '#' : '.');
                }
                System.out.println(sb);
            }
        }
    }

    private static String describe(int code) {
        final char ch = TextFont8x8.unicodeOf(code);
        return ch == 0 ? "无对应码位" : "U+" + Integer.toHexString(ch).toUpperCase();
    }

    // ==================== 断言小工具（与 ScreenSelfTest 同一套风格） ====================

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [ok] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    private static boolean fails(Runnable body, String expectContains) {
        try {
            body.run();
            System.out.println("        （预期抛异常，但没抛）");
            return false;
        } catch (RuntimeException e) {
            final String m = String.valueOf(e.getMessage());
            final boolean ok = m.contains(expectContains);
            if (!ok) {
                System.out.println("        （异常消息不含 \"" + expectContains + "\"：\"" + m + "\"）");
            }
            return ok;
        }
    }
}
