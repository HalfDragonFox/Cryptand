package com.hdf.cryptand.soc.board;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== GPU / 显存内存占用闸门（纯 Java 零 MC，2026-09-18；通道数 2026-09-26 补）=====
 *
 * <p>用户定案："没有 GPU 则获取不到屏幕"、"屏幕必须有 GPU"、"GPU 空间占用内存，屏幕为 0 则只有
 * 基础占用，屏幕越多占用越多"、"VRAM 与内存同占用"、"一个屏幕如果被拼起来算一个"，
 * 以及 2026-09-26 的通道口径：<b>"显卡有定义通道，1 个通道则支持 1 个屏幕（包括拼接的屏幕算一个），
 * 4 个则表示 4 个"</b>。</p>
 *
 * <p>跑法：{@code ./gradlew :common:runGpuMemoryTest}</p>
 */
public final class GpuMemorySelfTest {

    private static int passed;
    private static int failed;

    private static final DisplayTopology.ScreenKind TEXT = DisplayTopology.ScreenKind.TEXT;
    private static final DisplayTopology.ScreenKind RGB = DisplayTopology.ScreenKind.TRUE_COLOR;

    public static void main(String[] args) {
        basics();
        vram();
        channels();
        memoryBudget();
        merge();
        summaryText();
        budget();

        System.out.println("[GPUMEM] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 一张卡（名字 / 通道数 / 种类） */
    private static GpuMemory.Card card(String name, int channels) {
        return new GpuMemory.Card(name, channels, DisplayTopology.GpuKind.CRYPTAND);
    }

    private static DisplayTopology.Screen text(String id, String gpu) {
        return new DisplayTopology.Screen(id, TEXT, 80, 25, 0, gpu);
    }

    // ==================== 1. 基础占用 ====================

    private static void basics() {
        check("0 屏 ⇒ 只有基础占用（1 块 GPU = 64KB）",
                GpuMemory.totalVramBytes(1, List.of()) == 64L * 1024);
        check("2 块 GPU ⇒ 基础翻倍", GpuMemory.totalVramBytes(2, List.of()) == 128L * 1024);
        check("没有 GPU（0 块）⇒ 显存需求为 0（因为根本取不到屏幕）",
                GpuMemory.totalVramBytes(0, List.of()) == 0);
    }

    // ==================== 2. 每屏 VRAM ====================

    private static void vram() {
        check("80x25 字符屏 VRAM = 32 + 3*2000（与显存窗口一致）",
                GpuMemory.screenVramBytes(TEXT, 80, 25, 0) == 32 + 3 * 80 * 25);
        check("1 GPU + 1 字符屏 = 64KB + 6032B",
                GpuMemory.totalVramBytes(1, List.of(text("s0", "GPU0"))) == 64L * 1024 + 32 + 3 * 80 * 25);
        check("2 块字符屏 > 1 块（占用随屏数线性增长）",
                GpuMemory.totalVramBytes(1, List.of(text("s0", "GPU0"), text("s1", "GPU0")))
                        > GpuMemory.totalVramBytes(1, List.of(text("s0", "GPU0"))));
        check("160x50 @32bpp 真彩 = 32 + 160*50*4",
                GpuMemory.screenVramBytes(RGB, 160, 50, 32) == 32 + 160 * 50 * 4);
        check("真彩屏比字符屏更吃显存（同分辨率）",
                GpuMemory.screenVramBytes(RGB, 160, 50, 32) > GpuMemory.screenVramBytes(TEXT, 160, 50, 0));
    }

    // ==================== 3. 通道数（用户 2026-09-26 口径）====================

    private static void channels() {
        final List<GpuMemory.Card> oneChannel = List.of(card("graphicscard1", 1));
        final List<GpuMemory.Card> fourChannels = List.of(card("graphicscard3", 4));

        check("1 通道卡挂 1 块屏 ⇒ 通过",
                passes(() -> GpuMemory.validate(4096, oneChannel, List.of(text("s0", "graphicscard1")))));
        check("1 通道卡挂 2 块屏 ⇒ 报『通道不够』",
                fails(() -> GpuMemory.validate(4096, oneChannel,
                        List.of(text("s0", "graphicscard1"), text("s1", "graphicscard1"))), "通道不够"));
        check("4 通道卡挂 4 块屏 ⇒ 通过",
                passes(() -> GpuMemory.validate(4096, fourChannels,
                        List.of(text("s0", "graphicscard3"), text("s1", "graphicscard3"),
                                text("s2", "graphicscard3"), text("s3", "graphicscard3")))));
        check("4 通道卡挂 5 块独立屏 ⇒ 报『通道不够』",
                fails(() -> GpuMemory.validate(4096, fourChannels,
                        List.of(text("s0", "graphicscard3"), text("s1", "graphicscard3"),
                                text("s2", "graphicscard3"), text("s3", "graphicscard3"),
                                text("s4", "graphicscard3"))), "通道不够"));

        // 通道是**每块卡各算各的**：两张 1 通道卡各挂 1 块屏 ⇒ 合法
        final List<GpuMemory.Card> twoCards = List.of(card("gpuA", 1), card("gpuB", 1));
        check("两张卡各挂 1 块屏 ⇒ 合法（通道按卡算，不相加也不互借）",
                passes(() -> GpuMemory.validate(4096, twoCards,
                        List.of(text("a", "gpuA"), text("b", "gpuB")))));
        check("两张卡时把 2 块屏都挂在同一张上 ⇒ 报『通道不够』",
                fails(() -> GpuMemory.validate(4096, twoCards,
                        List.of(text("a", "gpuA"), text("b", "gpuA"))), "通道不够"));

        check("屏挂在机器上没有的卡上 ⇒ 明确报错（没有 GPU 就没有显示区域）",
                fails(() -> GpuMemory.validate(4096, oneChannel, List.of(text("s0", "ghost"))),
                        "没有这块显卡"));
        check("通道数超过平台上限（配错了）⇒ 明确报错",
                fails(() -> GpuMemory.validate(4096, List.of(card("bad", 8)),
                        List.of(text("s0", "bad"))), "通道数不合法"));
    }

    // ==================== 4. 内存预算（VRAM 与内存同占用）====================

    private static void memoryBudget() {
        final List<GpuMemory.Card> gpus = List.of(card("gpu", 4));
        final List<DisplayTopology.Screen> big =
                List.of(new DisplayTopology.Screen("big", RGB, 400, 200, 32, "gpu"));
        String msg = "";
        try {
            GpuMemory.validate(64, gpus, big);      // 只有 64KB 内存，放不下 320KB 的真彩屏
        } catch (IllegalStateException e) {
            msg = e.getMessage();
        }
        check("内存不够 ⇒ 报『内存不够放显存』并说明显存与内存同占用",
                msg.contains("内存不够放显存") && msg.contains("显存与内存同占用"));
        check("报错里带差多少 KB", msg.contains("差 "));
        check("内存够 ⇒ 通过",
                passes(() -> GpuMemory.validate(4096, gpus, big)));
    }

    // ==================== 5. 拼接算一块 ====================

    private static void merge() {
        final DisplayTopology.Screen merged = DisplayTopology.merge("big", TEXT, 80, 25, 2, 2, 0, "gpu");
        check("拼接后分辨率 = 拼合总分辨率（160x50）", merged.cols() == 160 && merged.rows() == 50);
        check("4 块拼成一块 ⇒ **1 通道卡**也装得下（拼接只算 1 个通道）",
                passes(() -> GpuMemory.validate(4096, List.of(card("gpu", 1)), List.of(merged))));
        check("显存按拼合后总分辨率算（160x50，而不是 4 块 80x25 各算一份）",
                GpuMemory.totalVramBytes(1, List.of(merged))
                        == 64L * 1024 + GpuMemory.screenVramBytes(TEXT, 160, 50, 0));
        check("物理块数不影响通道占用（拼合体就是一块逻辑屏）",
                merged.gpuName().equals("gpu") && merged.kind() == TEXT);
    }

    // ==================== 6. 摘要（UART/UI 断言用）====================

    private static void summaryText() {
        final String s = GpuMemory.summary(512, List.of(card("gpu", 1)), List.of(text("s0", "gpu")));
        check("摘要含显存 / 内存 / GPU 基础 / 屏数",
                s.contains("vram:") && s.contains("mem 512KB") && s.contains("GPU×1") && s.contains("屏×1"));
        check("摘要含『通道 已用/总数』（一眼看出还剩几个通道）", s.contains("通道 1/1"), s);
    }

    // ==================== 7. 显示预算（架构侧算、gpu 组件用的那一份）====================
    //
    //  用户 2026-09-26："显卡占用内存，内存不够会导致渲染出问题" ⇒ 装配时按"池 - 显存"扣，
    //  bind 一块屏之前还要拿**这块屏的真实分辨率**再算一次。Budget 就是这两处共用的同一份口径。

    private static void budget() {
        // 池 64KB、1 块 4 通道卡、0 屏 ⇒ 恰好被 GPU 基础吃满（边界：不超就通过）
        final GpuMemory.Budget tight = new GpuMemory.Budget(64, List.of(card("graphicscard3", 4)));
        check("预算：池 64KB 刚好等于 1 块卡的基础占用（0 屏）⇒ 通过",
                passes(() -> tight.validateWith(null)));
        check("预算：0 屏时显存需求就是 GPU 基础", tight.vramNeedBytes() == 64L * 1024);

        // 同一份池再挂一块 80x25 字符屏（6032 字节）⇒ 差 5KB，必须写明
        String msg = "";
        try {
            tight.validateWith(new DisplayTopology.Screen("s0", TEXT, 80, 25, 0, "graphicscard3"));
        } catch (IllegalStateException e) {
            msg = e.getMessage();
        }
        check("预算：池放不下候选屏 ⇒ 报『内存不够放显存』",
                msg.contains("内存不够放显存"));
        check("预算：候选屏按**真实分辨率**算并写明差多少 KB（80x25 字符屏 = 差 5KB）",
                msg.contains("差 " + (6032 / 1024) + " KB"));

        final GpuMemory.Budget roomy = new GpuMemory.Budget(4096, List.of(card("graphicscard3", 4)));
        roomy.attach(new DisplayTopology.Screen("s0", TEXT, 80, 25, 0, "graphicscard3"));
        roomy.attach(new DisplayTopology.Screen("s0", TEXT, 80, 25, 0, "graphicscard3"));
        check("预算：同一块屏重复登记只记一次（组件跨重启会重新登记）", roomy.screens().size() == 1);
        check("预算：已登记的屏算进显存需求（64KB 基础 + 6032B）",
                roomy.vramNeedBytes() == 64L * 1024 + 32 + 3 * 80 * 25);
        check("预算：放得下时 validateWith 不抛（候选屏一起算，真彩按 w*h*bpp/8）",
                passes(() -> roomy.validateWith(
                        new DisplayTopology.Screen("s1", RGB, 160, 50, 32, "graphicscard3"))));
        check("预算摘要：带候选屏时写明『通道 已用/总数』",
                roomy.summaryWith(new DisplayTopology.Screen("s1", TEXT, 80, 25, 0, "graphicscard3"))
                        .contains("通道 2/4"));

        // 两块**同型号**1 通道卡：通道按型号合并（回归：逐张卡各比一次会把第二块屏误判成通道不够）
        final GpuMemory.Budget twin = new GpuMemory.Budget(4096,
                List.of(card("graphicscard1", 1), card("graphicscard1", 1)));
        twin.attach(text("a", "graphicscard1"));
        check("预算：两块同名 1 通道卡各挂一块屏 ⇒ 通过（通道池 = 2）",
                passes(() -> twin.validateWith(text("b", "graphicscard1"))));
        twin.attach(text("b", "graphicscard1"));
        check("预算：同名卡合计 2 个通道，第 3 块屏 ⇒ 报『通道不够』",
                fails(() -> twin.validateWith(text("c", "graphicscard1")), "通道不够"));
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
        } catch (IllegalStateException e) {
            return e.getMessage() != null && e.getMessage().contains(keyword);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name + (detail.isEmpty() ? "" : "  " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }
}
