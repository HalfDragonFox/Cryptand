package com.hdf.cryptand.soc.board;

import java.util.List;

/**
 * ===== 真彩屏闸门（纯 Java 零 MC，2026-09-26）=====
 *
 * <p>覆盖 {@code repo/truetone-screen-design-2026-09-25.md} 的验收清单：
 * 各色深的 stride / 整帧字节数（含 1bpp/4bpp 位打包与"宽度非 8 的倍数"边界）、
 * 调色板索引 → RGB、写像素 ⇒ 读回颜色正确（含 stride 不对齐）、模式切换互不串味、
 * 非法值明确报错、锁 ⇒ 跳过且 SKIPPED 累计、拼合（2×1 / 1×2 / 2×2 / L 形 / 不同尺寸）。</p>
 *
 * <p>跑法：{@code ./gradlew :common:runScreenTest}</p>
 */
public final class ScreenSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        geometry();
        palette();
        pixels();
        modes();
        locks();
        groups();
        illegal();

        System.out.println("[SCREEN] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 1. 几何 ====================

    private static void geometry() {
        check("1bpp 80 列 = 10 字节/行（按位打包）", TrueColorScreen.stride(80, 1) == 10);
        check("4bpp 5 列 = 3 字节/行（半个字节也要占满）", TrueColorScreen.stride(5, 4) == 3);
        check("24bpp 3 列 = 9 字节/行", TrueColorScreen.stride(3, 24) == 9);
        check("32bpp 7 列 = 28 字节/行", TrueColorScreen.stride(7, 32) == 28);
        check("整帧 = stride × 高（1bpp 80x25 = 250 B）", TrueColorScreen.frameBytes(80, 25, 1) == 250);
        check("整帧 = stride × 高（32bpp 80x25 = 8000 B）", TrueColorScreen.frameBytes(80, 25, 32) == 8000);

        final TrueColorScreen s = TrueColorScreen.of(80, 25, 8);
        check("设备 stride/frameBytes 与静态算式一致",
                s.stride() == TrueColorScreen.stride(80, 8) && s.frameBytes() == s.stride() * 25);
        check("VRAM 就是描述符算出来的那块（不多不少）", s.vram().length == s.frameBytes());
    }

    // ==================== 2. 调色板 ====================

    private static void palette() {
        check("1bpp 调色板 = 2 项", TrueColorScreen.of(8, 8, 1).paletteSize() == 2);
        check("4bpp 调色板 = 16 项", TrueColorScreen.of(8, 8, 4).paletteSize() == 16);
        check("8bpp 调色板 = 256 项", TrueColorScreen.of(8, 8, 8).paletteSize() == 256);
        check("16bpp 直色没有调色板", TrueColorScreen.of(8, 8, 16).paletteSize() == 0);

        final TrueColorScreen s = TrueColorScreen.of(8, 8, 4);
        s.setPaletteColor(3, 0x12_34_56);
        check("调色板写入读回一致", s.paletteColor(3) == 0x123456);
        check("调色板索引越界明确报错",
                fails(() -> s.setPaletteColor(16, 0), "调色板索引越界"));
        check("直色屏取调色板明确报错",
                fails(() -> TrueColorScreen.of(8, 8, 24).paletteColor(0), "没有调色板"));
    }

    // ==================== 3. 像素读写 ====================

    private static void pixels() {
        // 1bpp：一个字节 8 格，高位在前
        final TrueColorScreen mono = TrueColorScreen.of(8, 2, 1);
        mono.setMode(TrueColorScreen.Mode.GRAPHICS);
        mono.setIndex(0, 0, 1);
        mono.setIndex(7, 0, 1);
        check("1bpp 位打包：第 0、7 格落在同一个字节的两端",
                (mono.vram()[0] & 0xFF) == 0b1000_0001);
        check("1bpp 读回自己的位", mono.rawAt(0, 0) == 1 && mono.rawAt(7, 0) == 1 && mono.rawAt(1, 0) == 0);
        mono.setIndex(7, 1, 1);
        check("第二行从 stride 边界开始（不串行）",
                (mono.vram()[mono.stride()] & 0xFF) == 0b0000_0001 && (mono.vram()[0] & 0xFF) == 0b1000_0001);

        // 4bpp：一字节两格
        final TrueColorScreen nib = TrueColorScreen.of(3, 1, 4);
        nib.setMode(TrueColorScreen.Mode.GRAPHICS);
        nib.setIndex(0, 0, 0xA);
        nib.setIndex(1, 0, 0x5);
        nib.setIndex(2, 0, 0xF);
        check("4bpp 半字节打包（0xA5 后跟 0xF0）",
                (nib.vram()[0] & 0xFF) == 0xA5 && (nib.vram()[1] & 0xFF) == 0xF0);
        check("4bpp 读回", nib.rawAt(0, 0) == 0xA && nib.rawAt(1, 0) == 0x5 && nib.rawAt(2, 0) == 0xF);
        check("4bpp stride = 2 字节（3 列 × 4bit 向上取整）", nib.stride() == 2);

        // 调色板解码
        final TrueColorScreen pal = TrueColorScreen.of(4, 1, 4);
        pal.setMode(TrueColorScreen.Mode.GRAPHICS);
        pal.setPaletteColor(2, 0xFF0000);
        pal.setIndex(0, 0, 2);
        check("调色板屏 rgbAt = 调色板里的颜色", pal.rgbAt(0, 0) == 0xFF0000);

        // 16bpp RGB565 往返
        final TrueColorScreen rgb = TrueColorScreen.of(2, 1, 16);
        rgb.setMode(TrueColorScreen.Mode.GRAPHICS);
        rgb.setRgb(0, 0, 0xFF0000);
        rgb.setRgb(1, 0, 0x00FF00);
        check("16bpp 直色按 RGB565 打包（红 = 0xF800）", rgb.rawAt(0, 0) == 0xF800);
        check("16bpp 绿 = 0x07E0", rgb.rawAt(1, 0) == 0x07E0);
        check("16bpp 解回接近原色（565 精度）",
                ((rgb.rgbAt(0, 0) >> 16) & 0xFF) >= 0xF8 && rgb.rgbAt(1, 0) == 0x00FF00);

        // 24/32bpp
        final TrueColorScreen c24 = TrueColorScreen.of(1, 1, 24);
        c24.setMode(TrueColorScreen.Mode.GRAPHICS);
        c24.setRgb(0, 0, 0x123456);
        check("24bpp 直色 3 字节小端", (c24.vram()[0] & 0xFF) == 0x56 && (c24.vram()[2] & 0xFF) == 0x12);
        check("24bpp 读回", c24.rgbAt(0, 0) == 0x123456);
        final TrueColorScreen c32 = TrueColorScreen.of(1, 1, 32);
        c32.setMode(TrueColorScreen.Mode.GRAPHICS);
        c32.setRgb(0, 0, 0xABCDEF);
        check("32bpp 读回且 alpha 位不参与颜色", c32.rgbAt(0, 0) == 0xABCDEF && (c32.vram()[3] & 0xFF) == 0xFF);

        // 越界写不越界
        final TrueColorScreen edge = TrueColorScreen.of(3, 1, 8);
        edge.setMode(TrueColorScreen.Mode.GRAPHICS);
        edge.setIndex(2, 0, 0x7F);
        check("越界写明确报错",
                fails(() -> edge.setIndex(3, 0, 1), "像素越界"));
        check("越界写没有污染 VRAM（第 3 格仍是原值）",
                edge.rawAt(2, 0) == 0x7F && edge.vram().length == 3);
        check("越界读明确报错", fails(() -> edge.rawAt(0, 1), "像素越界"));
    }

    // ==================== 4. 双形态 ====================

    private static void modes() {
        final TrueColorScreen s = TrueColorScreen.of(64, 32, 8);
        check("默认是文本模式（OC 看到的那一面）", s.mode() == TrueColorScreen.Mode.TEXT);
        // 字符格 = 8×16 像素（OC 终端口径）：64 宽 ⇒ 8 列；32 高 ⇒ 2 行
        check("文本模式 64x32 ⇒ 8 列 × 2 行字符格", s.textCols() == 8 && s.textRows() == 2);
        s.setText(1, 1, 'A', 0xFFFFFF, 0x000000);
        check("文本模式写读回（含前景/背景）",
                s.textAt(1, 1) == 'A' && s.textForeground(1, 1) == 0xFFFFFF && s.textBackground(1, 1) == 0);
        check("文本模式写像素 ⇒ 明确报错（不静默按像素解释）",
                fails(() -> s.setIndex(0, 0, 1), "文本模式"));
        check("字符格越界明确报错", fails(() -> s.setText(99, 1, 'A', 0, 0), "字符格越界"));

        s.setMode(TrueColorScreen.Mode.GRAPHICS);
        check("切图形模式后可以写像素（8bpp 是调色板色深 ⇒ 写索引）",
                passes(() -> s.setIndex(63, 31, 5)));
        check("调色板色深写 RGB ⇒ 明确报错（不静默转索引）",
                fails(() -> s.setRgb(0, 0, 0xFFFFFF), "请用 setIndex"));
        check("图形模式写字符 ⇒ 明确报错", fails(() -> s.setText(1, 1, 'B', 0, 0), "图形模式"));
        s.setMode(TrueColorScreen.Mode.TEXT);
        check("切回文本模式后字符还是原来那个（同一设备的两种解释）", s.textAt(1, 1) == 'A');
    }

    // ==================== 5. 锁与状态 ====================

    private static void locks() {
        final TrueColorScreen s = TrueColorScreen.of(16, 16, 8);
        s.setMode(TrueColorScreen.Mode.GRAPHICS);
        check("未锁 ⇒ 渲染并计帧", s.renderTick() && s.frames() == 1 && s.skipped() == 0);
        s.lock();
        check("锁住 ⇒ 跳过本帧（不等待、不阻塞）", !s.renderTick());
        check("跳过计在 skipped 上、frames 不增", s.frames() == 1 && s.skipped() == 1);
        s.renderTick();
        s.renderTick();
        check("连续锁住 N 帧 ⇒ skipped 累计 N（不丢计数）", s.skipped() == 3 && s.frames() == 1);
        check("状态快照与实际一致",
                s.status().locked() && s.status().skipped() == 3 && s.status().mode() == TrueColorScreen.Mode.GRAPHICS);
        s.unlock();
        check("解锁 ⇒ 下一帧正常渲染", s.renderTick() && s.frames() == 2);

        final TrueColorScreen text = TrueColorScreen.of(16, 16, 8);
        text.lock();
        check("文本模式下锁不参与（OC 的 gpu.set 照常显示）", text.renderTick() && text.frames() == 1);
        check("文本模式的锁状态仍可读（只是不影响刷新）", text.status().locked());
    }

    // ==================== 6. 拼合 ====================

    private static void groups() {
        final TrueColorScreen a = TrueColorScreen.of(128, 128, 8);
        final TrueColorScreen b = TrueColorScreen.of(128, 128, 8);
        a.setMode(TrueColorScreen.Mode.GRAPHICS);
        b.setMode(TrueColorScreen.Mode.GRAPHICS);
        final ScreenGroup wide = ScreenGroup.of(List.of(
                new ScreenGroup.Block("L", a, 0, 0),
                new ScreenGroup.Block("R", b, 128, 0)));
        check("2×1 拼合 ⇒ 总分辨率 256x128", wide.width() == 256 && wide.height() == 128);
        check("组坐标 x=200 落在右块、局部坐标 x=72",
                "R".equals(wide.blockAt(200, 10).id()) && wide.localOf(200, 10)[0] == 72);
        check("跨块写像素分派正确（右块局部 (0,0) 收到值）",
                passes(() -> wide.setIndex(128, 0, 7)) && b.rawAt(0, 0) == 7);
        check("显存 = 各块之和（分辨率和显存对应增加）",
                wide.totalVramBytes() == a.frameBytes() + b.frameBytes());

        final ScreenGroup tall = ScreenGroup.of(List.of(
                new ScreenGroup.Block("T", TrueColorScreen.of(64, 64, 8), 0, 0),
                new ScreenGroup.Block("B", TrueColorScreen.of(64, 64, 8), 0, 64)));
        check("1×2 拼合 ⇒ 64x128", tall.width() == 64 && tall.height() == 128);

        final ScreenGroup wall = ScreenGroup.of(List.of(
                new ScreenGroup.Block("00", TrueColorScreen.of(32, 32, 8), 0, 0),
                new ScreenGroup.Block("10", TrueColorScreen.of(32, 32, 8), 32, 0),
                new ScreenGroup.Block("01", TrueColorScreen.of(32, 32, 8), 0, 32),
                new ScreenGroup.Block("11", TrueColorScreen.of(32, 32, 8), 32, 32)));
        check("2×2 拼合 ⇒ 64x64 且四块各就位", wall.width() == 64 && wall.height() == 64
                && "11".equals(wall.blockAt(63, 63).id()));

        // 不同尺寸（宽屏 + 小屏上下排）
        final ScreenGroup mixed = ScreenGroup.of(List.of(
                new ScreenGroup.Block("big", TrueColorScreen.of(128, 64, 8), 0, 0),
                new ScreenGroup.Block("small", TrueColorScreen.of(128, 32, 8), 0, 64)));
        check("不同尺寸也能算对布局（128x96）", mixed.width() == 128 && mixed.height() == 96);

        // L 形：布局能算出来，但**必须被拒绝**（不是完整矩形）
        final List<ScreenGroup.Block> lShape = List.of(
                new ScreenGroup.Block("a", TrueColorScreen.of(64, 64, 8), 0, 0),
                new ScreenGroup.Block("b", TrueColorScreen.of(64, 64, 8), 64, 0),
                new ScreenGroup.Block("c", TrueColorScreen.of(64, 64, 8), 0, 64));
        final ScreenGroup.Bounds lBounds = ScreenGroup.layout(lShape);
        check("L 形布局：外接矩形 128x128（布局算法本身不挑形状）",
                lBounds.width() == 128 && lBounds.height() == 128);
        check("L 形拼合被拒绝（不连续/不是完整矩形）",
                fails(() -> ScreenGroup.of(lShape), "拼合不连续"));

        check("重叠拼合被拒绝", fails(() -> ScreenGroup.of(List.of(
                new ScreenGroup.Block("a", TrueColorScreen.of(64, 64, 8), 0, 0),
                new ScreenGroup.Block("b", TrueColorScreen.of(64, 64, 8), 32, 32))), "拼合重叠"));
        check("id 重复被拒绝", fails(() -> ScreenGroup.of(List.of(
                new ScreenGroup.Block("a", TrueColorScreen.of(32, 32, 8), 0, 0),
                new ScreenGroup.Block("a", TrueColorScreen.of(32, 32, 8), 32, 0))), "屏 id 重复"));
        check("空组被拒绝", fails(() -> ScreenGroup.of(List.of()), "空的屏组"));

        // 组级锁：任意一块锁 ⇒ 整组跳过，计在组上
        wide.lock();
        check("组级锁 ⇒ 整组跳过", !wide.renderTick() && wide.skipped() == 1 && wide.frames() == 0);
        wide.unlock();
        check("解锁后整组正常刷新", wide.renderTick() && wide.frames() == 1);
        check("组成员被单独锁住也算整组锁（不做单块子锁）",
                passes(() -> a.lock()) && wide.locked() && !wide.renderTick() && wide.skipped() == 2);
    }

    // ==================== 7. 非法输入一律明确报错 ====================

    private static void illegal() {
        check("非法色深（3bpp）明确报错", fails(() -> TrueColorScreen.of(8, 8, 3), "不支持的色深"));
        check("非法色深（12bpp）明确报错", fails(() -> TrueColorScreen.of(8, 8, 12), "不支持的色深"));
        check("尺寸为 0 明确报错", fails(() -> TrueColorScreen.of(0, 8, 8), "屏幕尺寸非法"));
        check("负尺寸明确报错", fails(() -> TrueColorScreen.of(8, -1, 8), "屏幕尺寸非法"));
        check("stride 宽度为 0 明确报错", fails(() -> TrueColorScreen.stride(0, 8), "宽度非法"));
        check("色深枚举齐全（1/2/4/8/16/24/32）", TrueColorScreen.Depth.values().length == 7);
    }

    // ==================== 工具 ====================

    private static boolean passes(Runnable action) {
        try {
            action.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean fails(Runnable action, String keyword) {
        try {
            action.run();
            return false;
        } catch (RuntimeException e) {
            return e.getMessage() != null && e.getMessage().contains(keyword);
        }
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
