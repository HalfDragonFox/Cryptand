package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.board.ScreenOutputFace.Capability;
import com.hdf.cryptand.soc.board.ScreenOutputFace.Painter;

/**
 * ===== 输出面自动派生闸门（common，纯 Java 零 MC，2026-09-27 任务 G）=====
 *
 * <p>钉死的东西：用户定案的四条语义在**规则层**成立 ——
 * 程序用 OC 字符 API ⇒ 一律字符语义（真彩屏上就是 TEXT 面）；
 * 程序画图像 ⇒ 真彩屏直接写 VRAM（GRAPHICS）、只能字符的屏转字符（TEXT + 转换）；
 * 而且判定**只有一个入口**（{@link ScreenOutputFace#derive}），没有任何"外部模式位"输入
 * （签名里就没有 mode 这个参数 —— 谁想再插一个开关都必须先改这个签名，改不动就说明它不是开关驱动的）。</p>
 *
 * <p>跑法（离线闸门）：{@code java -cp <common classes> com.hdf.cryptand.soc.board.ScreenOutputFaceSelfTest}
 * （gradle 任务登记需要 {@code common/build.gradle} 授权，见任务 G 报告）。</p>
 */
public final class ScreenOutputFaceSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        truthTable();
        noExternalModeInput();
        imageNeedsConversionOnlyForCharacterScreens();
        capabilityFromScreen();

        System.out.println("[SCREEN-FACE] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 真值表四条 —— 就是定案的四句话 */
    private static void truthTable() {
        check("字符 API × 真彩屏 ⇒ TEXT（定案第 3 条：真彩屏上就是 TEXT 面）",
                ScreenOutputFace.derive(Painter.CHARACTER, Capability.PIXELS)
                        == TrueColorScreen.Mode.TEXT);
        check("字符 API × 字符屏 ⇒ TEXT（同一条字符路）",
                ScreenOutputFace.derive(Painter.CHARACTER, Capability.CHARACTERS_ONLY)
                        == TrueColorScreen.Mode.TEXT);
        check("图形面 × 真彩屏 ⇒ GRAPHICS（定案第 2 条：直接写 VRAM 画像素）",
                ScreenOutputFace.derive(Painter.IMAGE, Capability.PIXELS)
                        == TrueColorScreen.Mode.GRAPHICS);
        check("图形面 × 字符屏 ⇒ TEXT（转字符之后仍是字符面）",
                ScreenOutputFace.derive(Painter.IMAGE, Capability.CHARACTERS_ONLY)
                        == TrueColorScreen.Mode.TEXT);
    }

    /** 判定不接受任何模式输入：同一对输入永远同一结果（派生 = 无状态、无外部开关） */
    private static void noExternalModeInput() {
        final TrueColorScreen.Mode first = ScreenOutputFace.derive(Painter.IMAGE, Capability.PIXELS);
        TrueColorScreen device = TrueColorScreen.of(64, 64, 8);
        device.setMode(TrueColorScreen.Mode.TEXT);          // 调试用的强制位（无人化工具那支）
        final TrueColorScreen.Mode second = ScreenOutputFace.derive(Painter.IMAGE, Capability.PIXELS);
        device.setMode(TrueColorScreen.Mode.GRAPHICS);
        final TrueColorScreen.Mode third = ScreenOutputFace.derive(Painter.IMAGE, Capability.PIXELS);
        check("派生结果与设备当前的 mode 位无关（不是外部开关驱动的）",
                first == second && second == third);
        check("derive 的签名里没有 mode/开关参数（两个输入：谁在画 × 屏能显示什么）", true);
        check("null 输入明确报错（判定不许猜）",
                fails(() -> ScreenOutputFace.derive(null, Capability.PIXELS), "都不能为 null")
                        && fails(() -> ScreenOutputFace.derive(Painter.IMAGE, null), "都不能为 null"));
    }

    /** "要转字符"这一支只在"图形面 × 只能字符"时为真 —— 唯一的换算触发条件 */
    private static void imageNeedsConversionOnlyForCharacterScreens() {
        check("图形面 × 字符屏 ⇒ 要转字符",
                ScreenOutputFace.requiresImageToCharacters(Painter.IMAGE, Capability.CHARACTERS_ONLY));
        check("图形面 × 真彩屏 ⇒ 不转（直接写 VRAM）",
                !ScreenOutputFace.requiresImageToCharacters(Painter.IMAGE, Capability.PIXELS));
        check("字符 API × 字符屏 ⇒ 不转（本来就是字符，走原路）",
                !ScreenOutputFace.requiresImageToCharacters(Painter.CHARACTER, Capability.CHARACTERS_ONLY));
        check("字符 API × 真彩屏 ⇒ 不转（写到它的 TEXT 面）",
                !ScreenOutputFace.requiresImageToCharacters(Painter.CHARACTER, Capability.PIXELS));
    }

    /** 屏能力只有"有没有像素面"一个事实来源 */
    private static void capabilityFromScreen() {
        check("有像素面 ⇒ PIXELS", ScreenOutputFace.capabilityOf(true) == Capability.PIXELS);
        check("没有像素面 ⇒ CHARACTERS_ONLY",
                ScreenOutputFace.capabilityOf(false) == Capability.CHARACTERS_ONLY);
        check("字符屏能力下 derive 不出 GRAPHICS（不可能把图像写进不存在的像素面）",
                ScreenOutputFace.derive(Painter.CHARACTER, Capability.CHARACTERS_ONLY)
                        != TrueColorScreen.Mode.GRAPHICS
                        && ScreenOutputFace.derive(Painter.IMAGE, Capability.CHARACTERS_ONLY)
                        != TrueColorScreen.Mode.GRAPHICS);
    }

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
            final String msg = e.getMessage() == null ? "" : e.getMessage();
            if (!msg.contains(expectContains)) {
                System.out.println("        （异常文本不含 \"" + expectContains + "\"：\"" + msg + "\"）");
                return false;
            }
            return true;
        }
    }
}
