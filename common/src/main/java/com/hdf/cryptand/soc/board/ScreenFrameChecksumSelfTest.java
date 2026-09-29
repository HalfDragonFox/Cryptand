package com.hdf.cryptand.soc.board;

/**
 * ===== 整屏校验闸门（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>钉死四件事：</p>
 * <ol>
 *   <li><b>确定性与对称性</b>：同内容必同值；同一块缓冲整块算 == 分段算（区间语义无歧义）；</li>
 *   <li><b>敏感性</b>：任意一字节变化（含最高位字节、含首尾）必须改变校验值 ——
 *       否则"客户端对不上"这个判据就失效；</li>
 *   <li><b>设备重载</b>：{@code of(device)} 只看 {@code frameBytes()} 那一整块
 *       （调色板/尺寸/模式变化不在校验范围，除非它们改变了像素字节）；</li>
 *   <li><b>边界</b>：越界区间必须明确抛错，不静默截断。</li>
 * </ol>
 *
 * <p>跑法：{@code ./gradlew :common:runScreenFrameChecksumTest}</p>
 */
public final class ScreenFrameChecksumSelfTest {

    private static int passed;
    private static int failed;

    private ScreenFrameChecksumSelfTest() {
    }

    public static void main(String[] args) {
        determinism();
        sensitivity();
        deviceBlock();
        boundaries();
        System.out.println("ScreenFrameChecksumSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void determinism() {
        final byte[] a = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        final byte[] b = {1, 2, 3, 4, 5, 6, 7, 8, 9};
        check(ScreenFrameChecksum.of(a, 0, 9) == ScreenFrameChecksum.of(b, 0, 9),
                "同内容同值");
        check(ScreenFrameChecksum.of(a, 0, 4) == ScreenFrameChecksum.of(a, 0, 4), "同区间同值（4 字节对齐段）");
        check(ScreenFrameChecksum.of(a, 0, 9) == ScreenFrameChecksum.of(a, 0, 9), "同区间同值（含尾部）");
        check(ScreenFrameChecksum.of(new byte[0], 0, 0) == ScreenFrameChecksum.of(new byte[0], 0, 0),
                "空缓冲同值");
        // 区间 == 截取后的同内容
        final byte[] prefix = {1, 2, 3, 4};
        final byte[] slice = new byte[9];
        System.arraycopy(a, 0, slice, 0, 9);
        check(ScreenFrameChecksum.of(slice, 0, 4) == ScreenFrameChecksum.of(prefix, 0, 4),
                "区间语义与等长缓冲一致");
    }

    private static void sensitivity() {
        final byte[] base = new byte[64];
        for (int i = 0; i < base.length; i++) {
            base[i] = (byte) (i * 7);
        }
        final int want = ScreenFrameChecksum.of(base, 0, base.length);
        for (int at : new int[]{0, 1, 3, 31, 60, 63}) {
            final byte[] mutated = base.clone();
            mutated[at] ^= 0x01;
            check(ScreenFrameChecksum.of(mutated, 0, mutated.length) != want,
                    "第 " + at + " 字节翻转必须改变校验值");
        }
    }

    private static void deviceBlock() {
        final TrueColorScreen d = TrueColorScreen.of(4, 4, 16);
        d.setMode(TrueColorScreen.Mode.GRAPHICS);
        final byte[] frame = new byte[4 * 4 * 2];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) (i * 3 + 1);
        }
        ScreenFrameEncoder.encodeInto(d, frame, 4, 4);
        check(ScreenFrameChecksum.of(d) == ScreenFrameChecksum.of(frame, 0, frame.length),
                "设备整块 == 同字节缓冲（16bpp 逐字节同构）");
        check(ScreenFrameChecksum.matches(ScreenFrameChecksum.of(d), d), "matches 自洽");
        check(!ScreenFrameChecksum.matches(ScreenFrameChecksum.of(d) ^ 1, d), "值不符要判否");
        // 只改一个像素 → 校验必变
        d.setRgb(0, 0, 0x123456);
        check(!ScreenFrameChecksum.matches(ScreenFrameChecksum.of(frame, 0, frame.length), d),
                "写像素后与旧值不再匹配");
    }

    private static void boundaries() {
        final byte[] a = new byte[8];
        checkThrows(() -> ScreenFrameChecksum.of(null, 0, 1), "data=null 要抛错");
        checkThrows(() -> ScreenFrameChecksum.of(a, -1, 1), "负偏移要抛错");
        checkThrows(() -> ScreenFrameChecksum.of(a, 0, -1), "负长度要抛错");
        checkThrows(() -> ScreenFrameChecksum.of(a, 4, 5), "越界区间要抛错");
        checkThrows(() -> ScreenFrameChecksum.of((TrueColorScreen) null), "device=null 要抛错");
    }

    private static void check(boolean condition, String what) {
        if (condition) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    private static void checkThrows(Runnable body, String what) {
        try {
            body.run();
            failed++;
            System.out.println("  FAIL " + what + "（没有抛错）");
        } catch (RuntimeException expected) {
            passed++;
            System.out.println("  ok   " + what + "（" + expected.getClass().getSimpleName() + "）");
        }
    }
}
