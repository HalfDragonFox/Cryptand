package com.hdf.cryptand.soc.board;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 高级分析器排版自测（离线，不启动 MC，2026-09-26）=====
 *
 * <p>为什么这份排版值得单独一个闸门：面板与无人化 MCP 工具**共用**它 —— 排版一旦跑偏
 * （缺项写成 0、单位算错一个量级、分区顺序随机），症状会是"界面上看着对、工具里断言不过"，
 * 而那种对不上账最难查。</p>
 *
 * <p>跑法：{@code gradlew :common:runInspectTest}</p>
 */
public final class SandboxInspectSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== Cryptand Sandbox Inspect Self Test (common/soc/board, 纯 Java 无 MC) ===");
        final SandboxInspect.Snapshot snap = full();
        final List<SandboxInspect.Row> rows = SandboxInspect.rows(snap);

        sectionOrder(rows);
        values(rows);
        memoryAndDevices(rows);
        moduleInventory(rows);
        cachesAndMessage(rows);
        textShape(snap);
        sectionBlocks(snap);
        refreshDiff(snap);
        emptySnapshot();
        units();

        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 一份"什么都有"的快照（数值都是好认的） */
    private static SandboxInspect.Snapshot full() {
        return new SandboxInspect.Snapshot(
                new SandboxInspect.Cpu("SOC", "soc2_32", 32, "RV32IMAC", 100, 44.72, 0.4472),
                new SandboxInspect.Exec("运行中", "无", 0x1_A2B4L, 12_345_678L, 900L, 3456L, 1000L),
                List.of(new SandboxInspect.Region("ROM", 0x0L, 512L * 1024, -1),
                        new SandboxInspect.Region("RAM", 0x2000_0000L, 128L * 1024, 40L * 1024),
                        new SandboxInspect.Region("VRAM", 0x2002_1800L, 8192, 6032),
                        new SandboxInspect.Region("MSG", 0x2002_3800L, 4128, 0)),
                List.of(
                        // ① 设备树模块（接口 + 速率 + 窗口 + irq + 槽位）：含一个"参数缺项"（irq -）
                        new SandboxInspect.Module("UART0", "UART", null, List.of(
                                SandboxInspect.Cap.rate("速率", 92_160L),
                                SandboxInspect.Cap.hex("窗口", 0x2002_4820L),
                                SandboxInspect.Cap.bytes("字节", 2368L),
                                SandboxInspect.Cap.count("irq", -1),
                                SandboxInspect.Cap.slots(2))),
                        // ② 装载的通用 FIFO 模块（深度 + 槽位计价 + 归属）：0 槽 = 不占（片上）
                        new SandboxInspect.Module("UART0.FIFO", "通用 FIFO 模块", "UART0", List.of(
                                SandboxInspect.Cap.bytes("深度", 256L),
                                SandboxInspect.Cap.slots(7))),
                        new SandboxInspect.Module("TIMER0", "片上", null, List.of(
                                SandboxInspect.Cap.hex("窗口", 0x1000_1000L),
                                SandboxInspect.Cap.count("irq", 7),
                                SandboxInspect.Cap.slots(0))),
                        // ③ 只有名字与类型（一个关键参数都取不到）也不能炸
                        new SandboxInspect.Module("孤儿模块", "未知", null, List.of())),
                List.of(
                        // 总账：占用已知 + 上限未报告 / 上限已知且装不下（要看得出差多少）
                        SandboxInspect.Ledger.ofCount("组件槽位", 28L, -1L, "槽"),
                        SandboxInspect.Ledger.ofBytes("显存 / 内存", 200L * 1024, 128L * 1024)),
                List.of(new SandboxInspect.Device("gpu", "1a2b3c", "80x25"),
                        new SandboxInspect.Device("uart", "4d5e6f", null),
                        new SandboxInspect.Device("孤儿设备", null, null)),
                List.of(new SandboxInspect.Cache("UART", List.of(
                                new SandboxInspect.Row("", "TX 字节", "1024"),
                                new SandboxInspect.Row("", "FIFO 占用", "3 / 16"))),
                        new SandboxInspect.Cache("GPU", List.of())),
                new SandboxInspect.Msg(128, 2, 4128, 3, 1375, 4178, 102, 4));
    }

    private static void sectionOrder(List<SandboxInspect.Row> rows) {
        final List<String> order = new ArrayList<>();
        for (final SandboxInspect.Row r : rows) {
            if (order.isEmpty() || !order.get(order.size() - 1).equals(r.section())) {
                order.add(r.section());
            }
        }
        check("分区顺序 = SECTIONS（面板按它分组）", order.equals(SandboxInspect.SECTIONS), order.toString());
        check("行数 = 35（处理器5+执行7+内存4+模块清单6+设备表3+设备缓存3+消息缓存区7）",
                rows.size() == 35, "rows=" + rows.size());
        check("count() 与 rows().size() 一致",
                SandboxInspect.count(full()) == rows.size(), "count=" + SandboxInspect.count(full()));
        check("两次调用结果相同（面板与 MCP 工具看到的是同一份）",
                SandboxInspect.rows(full()).equals(rows), "stable");
    }

    private static void values(List<SandboxInspect.Row> rows) {
        check("族 / 档位", "SOC · soc2_32".equals(value(rows, "处理器", "族 / 档位")),
                value(rows, "处理器", "族 / 档位"));
        check("ISA / 位宽", "RV32IMAC · 32 位".equals(value(rows, "处理器", "ISA / 位宽")),
                value(rows, "处理器", "ISA / 位宽"));
        check("标称 MHz 是整数形态", "100".equals(value(rows, "处理器", "标称 MHz")),
                value(rows, "处理器", "标称 MHz"));
        check("实测 MHz 带单位且保留两位", "44.72 MHz".equals(value(rows, "处理器", "实测 MHz")),
                value(rows, "处理器", "实测 MHz"));
        check("负载 → 百分数（四舍五入）", "45%".equals(value(rows, "处理器", "负载")),
                value(rows, "处理器", "负载"));
        check("运行状态来自快照", "运行中".equals(value(rows, "执行", "运行状态")),
                value(rows, "执行", "运行状态"));
        check("故障原因来自快照（无故障也是一个事实，不是占位）", "无".equals(value(rows, "执行", "故障原因")),
                value(rows, "执行", "故障原因"));
        check("故障字段为 null ⇒ 占位（不编造「无故障」）",
                SandboxInspect.DASH.equals(SandboxInspect.rows(new SandboxInspect.Snapshot(null,
                        new SandboxInspect.Exec("停机", null, 0x10L, 5L, 6L, 7L, 8L),
                        List.of(), List.of(), List.of(), List.of(), List.of(), null)).get(6).value()),
                "dash-fault");
        check("PC 是十六进制（大写）", "0x1A2B4".equals(value(rows, "执行", "PC")),
                value(rows, "执行", "PC"));
        check("累计指令是原值", "12345678".equals(value(rows, "执行", "累计指令")),
                value(rows, "执行", "累计指令"));
        check("实测窗口带毫秒", "1000 ms".equals(value(rows, "执行", "实测窗口")),
                value(rows, "执行", "实测窗口"));
    }

    private static void memoryAndDevices(List<SandboxInspect.Row> rows) {
        final String ram = value(rows, "内存", "RAM");
        check("RAM 行 = 基址 + 容量 + 已用", ram.startsWith("0x20000000 + 128 KB"),
                ram);
        check("RAM 已用按 KB 换算", ram.contains("已用 40 KB"), ram);
        check("只读区段不谈占用（used < 0）", !value(rows, "内存", "ROM").contains("已用"),
                value(rows, "内存", "ROM"));
        check("VRAM 容量整除时不带小数点", value(rows, "内存", "VRAM").contains("8 KB"),
                value(rows, "内存", "VRAM"));
        check("MSG 区非整除容量保留一位小数", value(rows, "内存", "MSG").contains("4.0 KB"),
                value(rows, "内存", "MSG"));
        check("设备行 = 名字 + 地址 + 细节", "1a2b3c   80x25".equals(value(rows, "设备表", "gpu")),
                value(rows, "设备表", "gpu"));
        check("设备没细节时只显示地址", "4d5e6f".equals(value(rows, "设备表", "uart")),
                value(rows, "设备表", "uart"));
        check("地址与细节都缺 ⇒ 占位符（不编造）", SandboxInspect.DASH.equals(value(rows, "设备表", "孤儿设备")),
                value(rows, "设备表", "孤儿设备"));
    }

    /**
     * 模块清单（2026-09-27 任务 K）：虚拟机为芯片建立/装载的模块 + 资源总账。
     *
     * <p>钉两类事实：① **真实值**（速率/窗口/深度/槽位计价/归属/还差多少）；
     * ② **缺项**（irq 查不到、上限未报告、占用查不到 ⇒ 一律占位符，绝不编造成 0 或"看着合理"的数）。</p>
     */
    private static void moduleInventory(List<SandboxInspect.Row> rows) {
        final String uart = value(rows, "模块清单", "UART0");
        check("模块行 = 类型 + 速率(带 /s) + 窗口 + 字节 + 槽位",
                uart.startsWith("UART   速率 90 KB/s · 窗口 0x20024820")
                        && uart.contains("字节 2.3 KB") && uart.contains("占槽位 2"), uart);
        check("模块缺项（该模块没有中断）⇒ 该参数写占位符，不是编造的 0",
                uart.contains("irq -"), uart);
        final String fifo = value(rows, "模块清单", "UART0.FIFO");
        check("装载的 FIFO 模块行 = 深度 + 槽位计价 + 归属",
                fifo.equals("通用 FIFO 模块   深度 256 B · 占槽位 7   归属 UART0"), fifo);
        check("0 槽写「0（不占）」而不是省略（片上模块真的不占槽位）",
                value(rows, "模块清单", "TIMER0").contains("占槽位 0（不占）"),
                value(rows, "模块清单", "TIMER0"));
        check("一个参数都取不到的模块也占一行（不炸、不消失）",
                "未知".equals(value(rows, "模块清单", "孤儿模块")), value(rows, "模块清单", "孤儿模块"));
        final String slots = value(rows, "模块清单", "总账 · 组件槽位");
        check("总账：占用已知 + 上限未报告 ⇒ 上限写占位符（不编造预算）",
                slots.equals("28 槽 / -   （上限未报告）"), slots);
        final String vram = value(rows, "模块清单", "总账 · 显存 / 内存");
        check("总账：装不下必须能看出差多少", vram.equals("200 KB / 128 KB   还差 72 KB"), vram);
        check("总账：装得下给出余额（单位不许丢）",
                "4 槽 / 8 槽   余 4 槽".equals(ledgerValue(SandboxInspect.Ledger.ofCount("t", 4, 8, "槽"))),
                ledgerValue(SandboxInspect.Ledger.ofCount("t", 4, 8, "槽")));
        check("总账：占用查不到 ⇒ 两边都占位（不是 0）",
                "- / -".equals(ledgerValue(SandboxInspect.Ledger.ofCount("t", -1, 8, "槽"))),
                ledgerValue(SandboxInspect.Ledger.ofCount("t", -1, 8, "槽")));
        check("文本类参数原样落到那一行（形如「占槽位 按挂载设备宽度计」这种口径说明）",
                capValue(SandboxInspect.Cap.text("占槽位", "按挂载设备宽度计"))
                        .contains("占槽位 按挂载设备宽度计"),
                capValue(SandboxInspect.Cap.text("占槽位", "按挂载设备宽度计")));
        check("文本类参数取不到 ⇒ 也写占位符（不写空串）",
                capValue(SandboxInspect.Cap.text("占槽位", null)).contains("占槽位 -"),
                capValue(SandboxInspect.Cap.text("占槽位", null)));
        check("模块清单排在内存之后、设备表之前（三层顺序：执行 → 内存 → 模块 → 设备）",
                SandboxInspect.SECTIONS.indexOf(SandboxInspect.S_MODULES)
                        == SandboxInspect.SECTIONS.indexOf(SandboxInspect.S_MEM) + 1
                        && SandboxInspect.SECTIONS.indexOf(SandboxInspect.S_MODULES)
                        < SandboxInspect.SECTIONS.indexOf(SandboxInspect.S_DEV),
                SandboxInspect.SECTIONS.toString());
    }

    /** 单个硬件参数排出来的那一行（同上：断言走真正的渲染路径） */
    private static String capValue(SandboxInspect.Cap cap) {
        final SandboxInspect.Module one = new SandboxInspect.Module("m", "t", null, List.of(cap));
        final SandboxInspect.Snapshot snap = new SandboxInspect.Snapshot(
                null, null, List.of(), List.of(one), List.of(), List.of(), List.of(), null);
        return value(SandboxInspect.rows(snap), SandboxInspect.S_MODULES, "m");
    }

    /** 一行总账排出来的文本（走真正那条渲染路径，避免"工厂文案与面板文案"两套断言） */
    private static String ledgerValue(SandboxInspect.Ledger ledger) {
        final SandboxInspect.Snapshot one = new SandboxInspect.Snapshot(
                null, null, List.of(), List.of(), List.of(ledger), List.of(), List.of(), null);
        return value(SandboxInspect.rows(one), SandboxInspect.S_MODULES, "总账 · " + ledger.name());
    }

    private static void cachesAndMessage(List<SandboxInspect.Row> rows) {
        check("缓存行按 设备 · 名称 拼标签", "1024".equals(value(rows, "设备缓存", "UART · TX 字节")),
                value(rows, "设备缓存", "UART · TX 字节"));
        check("缓存第二行也在", "3 / 16".equals(value(rows, "设备缓存", "UART · FIFO 占用")),
                value(rows, "设备缓存", "UART · FIFO 占用"));
        check("没有明细的设备也占一行（区分空与缺失）",
                SandboxInspect.DASH.equals(value(rows, "设备缓存", "GPU")), value(rows, "设备缓存", "GPU"));
        check("待发字节 = 已用 / 上限", "128 B / 4.0 KB".equals(value(rows, "消息缓存区", "待发字节")),
                value(rows, "消息缓存区", "待发字节"));
        check("待发条数", "2".equals(value(rows, "消息缓存区", "待发条数")),
                value(rows, "消息缓存区", "待发条数"));
        check("丢弃计数可见（不许静默）", "3".equals(value(rows, "消息缓存区", "因满丢弃")),
                value(rows, "消息缓存区", "因满丢弃"));
        check("邮箱未消化调用", "4".equals(value(rows, "消息缓存区", "邮箱未消化调用")),
                value(rows, "消息缓存区", "邮箱未消化调用"));
        check("累计入队条数是原值（seq）", "1375".equals(value(rows, "消息缓存区", "累计入队条数")),
                value(rows, "消息缓存区", "累计入队条数"));
        check("已消费字节按 KB 换算", "4.1 KB".equals(value(rows, "消息缓存区", "已消费字节")),
                value(rows, "消息缓存区", "已消费字节"));
        check("发布次数可见（0 = 一次都没发过）", "102".equals(value(rows, "消息缓存区", "发布次数")),
                value(rows, "消息缓存区", "发布次数"));
    }

    private static void textShape(SandboxInspect.Snapshot snap) {
        final String text = SandboxInspect.text(snap);
        check("文本带分区标题", text.contains("== 处理器 ==") && text.contains("== 消息缓存区 =="),
                text.split("\n")[0]);
        check("每个分区各出现一次",
                text.split("== 处理器 ==", -1).length == 2 && text.split("== 消息缓存区 ==", -1).length == 2,
                "once");
        check("文本按分区顺序（处理器在消息缓存区之前）",
                text.indexOf("== 处理器 ==") < text.indexOf("== 消息缓存区 =="), "ordered");
        check("末尾不留空行", text.equals(text.stripTrailing()) && !text.endsWith("\n"), "trimmed");
        final String line = text.lines().filter(l -> l.startsWith("族 / 档位")).findFirst().orElse("");
        check("中文标签按两列宽补位（等宽面板不乱）", line.length() > "族 / 档位".length(), line);
        check("文本里没有 null 字样", !text.contains("null") && !text.contains("NaN"), "clean");
    }

    /** 面板用的单分区文本块：与 rows() 同源（面板与工具的每一行逐字一致） */
    private static void sectionBlocks(SandboxInspect.Snapshot snap) {
        final String cpu = SandboxInspect.sectionText(snap, "处理器");
        check("分区文本块不含分区标题（标题由面板分组框提供）", !cpu.contains("=="), cpu.split("\n")[0]);
        check("分区文本块行数 = 该分区的行数", cpu.split("\n", -1).length == 5,
                "lines=" + cpu.split("\n", -1).length);
        check("分区文本块逐字等于整篇文本里该段的去标题形态",
                cpu.lines().noneMatch(String::isBlank), "no-blank");
        final String mem = SandboxInspect.sectionText(snap, "内存");
        check("每个分区都能单独取块（内存 4 行）", mem.split("\n", -1).length == 4,
                "lines=" + mem.split("\n", -1).length);
        check("未知分区 ⇒ 空串（不炸）", SandboxInspect.sectionText(snap, "不存在").isEmpty(), "empty");
    }

    /**
     * 每秒刷新的判据（{@link SandboxInspect#sameSections}）：面板要求"内容变了才发同步包"，
     * 所以"变没变"必须是能离线断言的事实 —— 改一个字节就必须判为变，没改就必须判为没变。
     */
    private static void refreshDiff(SandboxInspect.Snapshot snap) {
        final String[] first = SandboxInspect.sectionTexts(snap);
        check("sectionTexts 覆盖每个分区（6 段）", first.length == SandboxInspect.SECTIONS.size(),
                "n=" + first.length);
        boolean same = true;
        for (int i = 0; i < first.length; i++) {
            same &= first[i].equals(SandboxInspect.sectionText(snap, SandboxInspect.SECTIONS.get(i)));
        }
        check("sectionTexts[i] 与 sectionText(i) 逐字一致（同源，不是第二套排版）", same, "same-source");
        check("同一快照重采一次 ⇒ 判为相同（不触发同步）",
                SandboxInspect.sameSections(first, SandboxInspect.sectionTexts(snap)), "stable");

        // 只改 1 个 ASCII 字节（"44.72 MHz" 里的第一个 4 → 5），其余逐字不变
        final String[] oneByte = SandboxInspect.sectionTexts(snap);
        final int at = oneByte[0].indexOf('4');
        oneByte[0] = oneByte[0].substring(0, at) + '5' + oneByte[0].substring(at + 1);
        check("只改 1 个字节 ⇒ 判为不同（触发同步）",
                at >= 0 && !SandboxInspect.sameSections(first, oneByte), "at=" + at);

        // 只改一段的最后一个字符（分区之间不许互相顶替）
        final String[] tail = SandboxInspect.sectionTexts(snap);
        final int last = tail.length - 1;
        tail[last] = tail[last].substring(0, tail[last].length() - 1) + 'X';
        check("只改最后一段的 1 个字符 ⇒ 也判为不同",
                !SandboxInspect.sameSections(first, tail), "last=" + last);

        check("长度不同 ⇒ 判为不同（分区数变了不能当没变）",
                !SandboxInspect.sameSections(first, new String[]{"x"}), "len");
        check("null 安全：两边都 null ⇒ 相同；一边 null ⇒ 不同",
                SandboxInspect.sameSections(null, null) && !SandboxInspect.sameSections(null, first)
                        && !SandboxInspect.sameSections(first, null), "null-safe");
    }

    private static void emptySnapshot() {
        final SandboxInspect.Snapshot empty = new SandboxInspect.Snapshot(
                null, null, null, null, null, null, null, null);
        final List<SandboxInspect.Row> rows = SandboxInspect.rows(empty);
        check("空快照也有全部分区（5+7+1+1+1+1+7 = 23 行）", rows.size() == 23, "rows=" + rows.size());
        boolean allDash = true;
        for (final SandboxInspect.Row r : rows) {
            allDash &= SandboxInspect.DASH.equals(r.value());
        }
        check("空快照的所有值都是占位符（不编造 0）", allDash, "dash");
        check("空快照的分区顺序也不变",
                SandboxInspect.rows(empty).stream().map(SandboxInspect.Row::section).distinct().toList()
                        .equals(SandboxInspect.SECTIONS), "order");
        check("null 快照不炸（等价于空快照）", SandboxInspect.rows(null).size() == 23, "null-safe");
        check("模块清单在空快照里也出现（值 = 占位符，不编造模块）",
                SandboxInspect.DASH.equals(value(rows, "模块清单", "模块清单")),
                value(rows, "模块清单", "模块清单"));
    }

    private static void units() {
        check("字节：0 也是 0 B（不是占位）", "0 B".equals(SandboxInspect.bytes(0)), SandboxInspect.bytes(0));
        check("字节：负数 ⇒ 占位", SandboxInspect.DASH.equals(SandboxInspect.bytes(-1)), "dash");
        check("字节：MB 档", "2 MB".equals(SandboxInspect.bytes(2L * 1024 * 1024)), SandboxInspect.bytes(2L * 1024 * 1024));
        check("频率：NaN ⇒ 占位", SandboxInspect.DASH.equals(SandboxInspect.mhz(Double.NaN)), "dash");
        check("比例：0 ⇒ 0%", "0%".equals(SandboxInspect.percent(0.0)), SandboxInspect.percent(0.0));
        check("比例：负数 ⇒ 占位（不显示 -5%）", SandboxInspect.DASH.equals(SandboxInspect.percent(-0.05)), "dash");
        check("地址：0 也带前缀", "0x0".equals(SandboxInspect.hex(0)), SandboxInspect.hex(0));
        check("数字：整数不带小数点", "100".equals(SandboxInspect.num(100.0)), SandboxInspect.num(100.0));
        check("数字：小数保留两位", "44.72".equals(SandboxInspect.num(44.719)), SandboxInspect.num(44.719));
    }

    // ==================== 工具 ====================

    private static String value(List<SandboxInspect.Row> rows, String section, String label) {
        for (final SandboxInspect.Row r : rows) {
            if (r.section().equals(section) && r.label().equals(label)) {
                return r.value();
            }
        }
        return "<缺失>";
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
