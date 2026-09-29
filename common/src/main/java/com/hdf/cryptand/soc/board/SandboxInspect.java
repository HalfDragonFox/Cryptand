package com.hdf.cryptand.soc.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * ===== 沙箱内部快照 → 高级分析器面板 / MCP 工具共用的那一份数据（common，纯 Java 零 MC，2026-09-26）=====
 *
 * <p>用户定案（{@code repo/advanced-analyzer-2026-09-26.md}）：OC 原版 Analyzer 是**方块级别**的分析器
 * （它看不到 guest 内部）；高级分析器要看**沙箱里面** —— 挂载的设备与各自缓存、内存占用、
 * 缓存占用、执行状态。面板（LDLib2）与无人化 MCP 工具必须给出<b>同一份</b>数据：
 * 两处各算一遍，迟早会出现"界面上写着 45%、工具里写着 44%"这种对不上账的现象。</p>
 *
 * <h3>分层（照项目铁律）</h3>
 * <ul>
 *   <li>本类在 common 且**纯数据**：只吃 {@link Snapshot}，不反问任何 live 对象 ⇒ 能离线自测
 *       （{@code :common:runInspectTest}）；</li>
 *   <li>neoforge 侧只做"把现成的 live API 翻成 {@link Snapshot}"（零排版逻辑）；</li>
 *   <li>缺项一律显示 {@value #DASH}，**绝不编造** —— "查不到"和"真的是 0"是两件事。</li>
 * </ul>
 */
public final class SandboxInspect {

    /** 缺项占位（面板与文本共用；不许写 0 或空串冒充） */
    public static final String DASH = "-";

    /** 七个分区（顺序 = 显示顺序；面板按它分组，文本按它分段） */
    public static final String S_CPU = "处理器";
    public static final String S_EXEC = "执行";
    public static final String S_MEM = "内存";
    /**
     * **模块清单**（2026-09-27 任务 K）：虚拟机（硬件层）为这颗芯片**建立/装载的模块**。
     *
     * <p>与既有分区的关系（避免重复显示同一件事）：</p>
     * <ul>
     *   <li>{@link #S_MEM} 回答"这段内存在地址空间的**哪儿、多长**"（布局）；
     *       本分区回答"内存模块**装了多少**"（内存条容量 vs 映射字节）——一个是地图，一个是器件；</li>
     *   <li>{@link #S_DEV} 回答"**挂上了哪些 OC 设备**"（组件句柄/地址），本分区回答
     *       "虚拟机为芯片建了哪些**硬件模块**"（接口 + 速率 + 窗口 + 槽位）——
     *       设备是挂在模块下面的东西，两者不是同一层；</li>
     *   <li>{@link #S_CACHE} 回答"这些模块**此刻的计数**"（FIFO 使能/占用/溢出/状态位）。
     *       所以本分区只放**静态硬件参数**（深度、槽位计价、通道数、容量），
     *       运行期读数一律留在 {@link #S_CACHE} —— 同一个数字只出现一次。</li>
     * </ul>
     */
    public static final String S_MODULES = "模块清单";
    public static final String S_DEV = "设备表";
    public static final String S_CACHE = "设备缓存";
    public static final String S_MSG = "消息缓存区";

    /** 分区顺序（面板与断言都用这一份，不许各自写一遍） */
    public static final List<String> SECTIONS =
            List.of(S_CPU, S_EXEC, S_MEM, S_MODULES, S_DEV, S_CACHE, S_MSG);

    /** 一行 = 分区 + 名称 + 值（面板一行、MCP 文本一行，都是它） */
    public record Row(String section, String label, String value) {
    }

    /** 一段内存区（内存布局）：{@code used < 0} = 这一区不谈占用 */
    public record Region(String name, long base, long bytes, long used) {
    }

    /** 一个挂载的设备（设备表） */
    public record Device(String name, String address, String detail) {
    }

    /** 一个设备的缓存计数（设备缓存区）；{@code rows} 里是它自己的若干行 */
    public record Cache(String name, List<Row> rows) {
    }

    /**
     * 一个模块的**关键硬件参数**：名字 + 已经按硬件口径排好版的值。
     *
     * <p>为什么值在这里就排好：单位换算是**一份实现**（下面这些静态工厂），采集侧只挑工厂、
     * 自己一个字符串都不拼 —— 面板与 {@code soc_inspect} 因此不可能出现两套单位。
     * 负数/空白一律落成 {@link #DASH}（"查不到"与"真的是 0"是两件事：0 槽要显示成 0）。</p>
     */
    public record Cap(String name, String value) {

        /** 字节量（容量 / 深度 / 窗口跨度） */
        public static Cap bytes(String name, long v) {
            return new Cap(name, SandboxInspect.bytes(v));
        }

        /** 速率（字节/秒） */
        public static Cap rate(String name, long v) {
            return new Cap(name, v < 0 ? DASH : SandboxInspect.bytes(v) + "/s");
        }

        /** 地址 */
        public static Cap hex(String name, long v) {
            return new Cap(name, v < 0 ? DASH : SandboxInspect.hex(v));
        }

        /** 计数（通道数 / 条数 / 中断号…） */
        public static Cap count(String name, long v) {
            return new Cap(name, v < 0 ? DASH : String.valueOf(v));
        }

        /**
         * 占用的**组件槽位**（与 {@code PeripheralMap} 的槽位预算同一个账）。
         *
         * <p>0 槽必须显示成"0（不占）"而不是省略：片上模块（寄存器桥/定时器/调试口）与共享内存邮箱
         * 真的是 0 槽 —— 这与"查不到槽位口径"是两件事，省略会让人以为我们没算。</p>
         */
        public static Cap slots(int v) {
            return new Cap("占槽位", v < 0 ? DASH : (v == 0 ? "0（不占）" : String.valueOf(v)));
        }

        /** 文本类事实（分辨率 {@code 80x25} 这种本身就是文字的量） */
        public static Cap text(String name, String v) {
            return new Cap(name, orDash(v));
        }
    }

    /**
     * 虚拟机（硬件层）为芯片**建立/装载的一个模块**（设备树里的一条）。
     *
     * @param name  设备树里的模块名（{@code UART0} / {@code UART0.FIFO} / {@code RAM}…）
     * @param type  类型（接口名 / {@code 通用 FIFO 模块} / {@code 内存}…）
     * @param owner 归属（挂在哪个模块/卡下；没有 = {@code null}）
     * @param caps  关键硬件参数（见 {@link Cap}；空 = 只有名字与类型）
     */
    public record Module(String name, String type, String owner, List<Cap> caps) {
    }

    /**
     * 资源总账的一行：某个账目的**占用 vs 上限**（上限 {@code < 0} = 查不到 ⇒ 显示 {@link #DASH}）。
     *
     * <p>模块清单回答"装了什么"，总账回答"装得下吗"：装不下必须能看出**还差多少**
     * （与既有槽位模型同一条判据 —— 占用由 {@code PeripheralMap} 的口径算出，上限由装机方给）。
     * ⚠ 上限**查不到就写 {@code -}**，绝不用"看起来合理"的数凑：那会让人以为账已经结过了。</p>
     */
    public record Ledger(String name, String used, String limit, String diff) {

        /** 字节账（显存 / 内存条…） */
        public static Ledger ofBytes(String name, long usedBytes, long limitBytes) {
            if (usedBytes < 0) {
                return new Ledger(name, DASH, DASH, "");
            }
            if (limitBytes < 0) {
                return new Ledger(name, bytes(usedBytes), DASH, "（上限未报告）");
            }
            final long delta = usedBytes - limitBytes;
            return new Ledger(name, bytes(usedBytes), bytes(limitBytes),
                    delta > 0 ? "还差 " + bytes(delta) : "余 " + bytes(-delta));
        }

        /** 个数账（组件槽位…） */
        public static Ledger ofCount(String name, long used, long limit, String unit) {
            // 单位前留一个空格：{@code 28 槽} 才读得出来是"28 个槽位"（"28槽"会被读成一个词）
            final String u = unit == null || unit.isBlank() ? "" : " " + unit.trim();
            if (used < 0) {
                return new Ledger(name, DASH, DASH, "");
            }
            if (limit < 0) {
                return new Ledger(name, used + u, DASH, "（上限未报告）");
            }
            final long delta = used - limit;
            return new Ledger(name, used + u, limit + u,
                    delta > 0 ? "还差 " + delta + u : "余 " + (-delta) + u);
        }
    }

    /** 处理器：族/档位/位宽/ISA + 标称与实测频率 + 负载 */
    public record Cpu(String family, String tier, int bits, String isa, int nominalMhz, double actualMhz, double load) {
    }

    /**
     * 执行：状态 + 故障 + PC + 累计指令 + 轮次 + 心跳 + 实测窗口（毫秒）。
     *
     * <p>{@code fault} 为 {@code null} / 空白 ⇒ 显示 {@link #DASH}（"查不到"与"确实没有故障"是两件事：
     * 前者由采集侧传 null，后者传一个明确的文本，例如 {@code 无}）。</p>
     */
    public record Exec(String state, String fault, long pc, long instructions, long cycles, long heartbeats,
                       long windowMillis) {
    }

    /**
     * 消息缓存区（主线程 → 虚拟机）+ 邮箱未消化调用数。
     *
     * <p>字段全部来自宿主侧现成访问器（{@code msgPendingBytes()} 一族）：{@code seq} = 累计入队条数、
     * {@code consumedBytes} = 固件已消费字节、{@code flushes} = 发布进 guest RAM 的次数
     * ——"待发"只有瞬时值，"累计/发布"才能回答"宿主到底有没有在发"。</p>
     */
    public record Msg(long pendingBytes, long pendingMessages, long capacity, long dropped, long seq,
                      long consumedBytes, long flushes, long mailboxCalls) {
    }

    /** 一次快照：全部来自宿主侧现成 API（本类只负责排版，不负责采集） */
    public record Snapshot(Cpu cpu, Exec exec, List<Region> regions, List<Module> modules, List<Ledger> ledgers,
                           List<Device> devices, List<Cache> caches, Msg msg) {
    }

    /**
     * "什么都没有"的快照：所有分区都出现、所有值都是占位符 {@link #DASH}。
     *
     * <p>什么时候用它：目标不是 OC 机器 / OC 未加载 / 采集抛异常 —— 面板与工具都必须给出
     * **明确的"查不到"**，而不是空屏或编造的 0（空屏会让人以为面板坏了）。</p>
     */
    public static Snapshot empty() {
        return new Snapshot(null, null, null, null, null, null, null, null);
    }

    private SandboxInspect() {
    }

    // ==================== 排版（面板与 MCP 工具唯一的入口） ====================

    /**
     * 快照 → 行表（按 {@link #SECTIONS} 的顺序，每个分区**至少一行**）。
     *
     * <p>为什么"至少一行"：面板按分区折叠显示，空分区直接消失会让"这一台没有这个分区"
     * 和"这个分区是空的"看起来一样 —— 二者要能被区分开。</p>
     */
    public static List<Row> rows(Snapshot s) {
        final List<Row> out = new ArrayList<>();
        final Snapshot snap = s == null
                ? new Snapshot(null, null, List.of(), List.of(), List.of(), List.of(), List.of(), null) : s;

        final Cpu cpu = snap.cpu();
        add(out, S_CPU, "族 / 档位", cpu == null ? null : join(" · ", cpu.family(), cpu.tier()));
        add(out, S_CPU, "ISA / 位宽", cpu == null ? null
                : join(" · ", cpu.isa(), cpu.bits() <= 0 ? null : cpu.bits() + " 位"));
        add(out, S_CPU, "标称 MHz", cpu == null || cpu.nominalMhz() <= 0 ? null : String.valueOf(cpu.nominalMhz()));
        add(out, S_CPU, "实测 MHz", cpu == null ? null : mhz(cpu.actualMhz()));
        add(out, S_CPU, "负载", cpu == null ? null : percent(cpu.load()));

        final Exec ex = snap.exec();
        add(out, S_EXEC, "运行状态", ex == null ? null : ex.state());
        add(out, S_EXEC, "故障原因", ex == null ? null : ex.fault());
        add(out, S_EXEC, "PC", ex == null ? null : hex(ex.pc()));
        add(out, S_EXEC, "累计指令", ex == null || ex.instructions() < 0 ? null : String.valueOf(ex.instructions()));
        add(out, S_EXEC, "沙箱轮次", ex == null || ex.cycles() < 0 ? null : String.valueOf(ex.cycles()));
        add(out, S_EXEC, "心跳次数", ex == null || ex.heartbeats() < 0 ? null : String.valueOf(ex.heartbeats()));
        add(out, S_EXEC, "实测窗口", ex == null || ex.windowMillis() <= 0 ? null : ex.windowMillis() + " ms");

        final List<Region> regions = nullToEmpty(snap.regions());
        if (regions.isEmpty()) {
            add(out, S_MEM, "布局", null);
        }
        for (final Region rg : regions) {
            add(out, S_MEM, orDash(rg.name()),
                    join("   ", hex(rg.base()) + " + " + bytes(rg.bytes()),
                            rg.used() < 0 ? null : "已用 " + bytes(rg.used())));
        }

        // 模块清单（虚拟机为芯片建立/装载的硬件）：一个模块一行 + 资源总账
        final List<Module> modules = nullToEmpty(snap.modules());
        if (modules.isEmpty()) {
            add(out, S_MODULES, "模块清单", null);
        }
        for (final Module m : modules) {
            add(out, S_MODULES, orDash(m.name()),
                    join("   ", orDash(m.type()), caps(m.caps()),
                            m.owner() == null || m.owner().isBlank() ? null : "归属 " + m.owner().trim()));
        }
        for (final Ledger l : nullToEmpty(snap.ledgers())) {
            add(out, S_MODULES, "总账 · " + orDash(l.name()),
                    join("   ", orDash(l.used()) + " / " + orDash(l.limit()), l.diff()));
        }

        final List<Device> devices = nullToEmpty(snap.devices());
        if (devices.isEmpty()) {
            add(out, S_DEV, "设备表", null);
        }
        for (final Device d : devices) {
            add(out, S_DEV, orDash(d.name()), join("   ", d.address(), d.detail()));
        }

        boolean anyCache = false;
        for (final Cache c : nullToEmpty(snap.caches())) {
            final List<Row> inner = c.rows() == null ? List.of() : c.rows();
            if (inner.isEmpty()) {
                add(out, S_CACHE, c.name(), null);
                anyCache = true;
                continue;
            }
            for (final Row r : inner) {
                add(out, S_CACHE, orDash(c.name()) + " · " + orDash(r.label()), r.value());
                anyCache = true;
            }
        }
        if (!anyCache) {
            add(out, S_CACHE, "设备缓存", null);
        }

        final Msg m = snap.msg();
        add(out, S_MSG, "待发字节", m == null ? null : bytes(m.pendingBytes()) + " / " + bytes(m.capacity()));
        add(out, S_MSG, "待发条数", m == null ? null : String.valueOf(m.pendingMessages()));
        add(out, S_MSG, "因满丢弃", m == null ? null : String.valueOf(m.dropped()));
        add(out, S_MSG, "累计入队条数", m == null || m.seq() < 0 ? null : String.valueOf(m.seq()));
        add(out, S_MSG, "已消费字节", m == null || m.consumedBytes() < 0 ? null : bytes(m.consumedBytes()));
        add(out, S_MSG, "发布次数", m == null || m.flushes() < 0 ? null : String.valueOf(m.flushes()));
        add(out, S_MSG, "邮箱未消化调用", m == null || m.mailboxCalls() < 0 ? null : String.valueOf(m.mailboxCalls()));
        return out;
    }

    /** 行表 → 文本：面板的"复制/导出"与 MCP 工具返回的就是它（一份数据，两处显示） */
    public static String text(Snapshot s) {
        final StringBuilder sb = new StringBuilder();
        String section = null;
        for (final Row r : rows(s)) {
            if (!r.section().equals(section)) {
                section = r.section();
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append("== ").append(section).append(" ==\n");
            }
            sb.append(pad(r.label())).append(' ').append(r.value()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /**
     * 单个分区的文本块（行内已按显示宽度补位，**多行**）—— 面板一个分区一个多行 Label。
     *
     * <p>与 {@link #text(Snapshot)} 同源：都是 {@link #rows(Snapshot)} 排出来的 ⇒ 面板每一行的内容
     * 与无人化工具的文本块逐字一致（只是不带 {@code == 分区 ==} 标题，标题由面板的分组框提供）。</p>
     */
    public static String sectionText(Snapshot s, String section) {
        final StringBuilder sb = new StringBuilder();
        for (final Row r : rows(s)) {
            if (!r.section().equals(section)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(pad(r.label())).append(' ').append(r.value());
        }
        return sb.toString();
    }

    /**
     * 一次取全部六个分区的文本块（下标 = {@link #SECTIONS} 的下标）。
     *
     * <p>为什么要"一次取全"：面板与方块实体真正要回答的是**这一秒跟前一秒比有没有变**
     * ——那必须先拿到整份快照的六段文本再逐段比（见 {@link #sameSections(String[], String[])}）。
     * 逐段调用 {@link #sectionText(Snapshot, String)} 也能取到同样的字，但那是给"只想看一段"的
     * 调用方（例如以后的单分区复制），拿它拼六段会重排六遍行表。</p>
     */
    public static String[] sectionTexts(Snapshot s) {
        final String[] out = new String[SECTIONS.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = sectionText(s, SECTIONS.get(i));
        }
        return out;
    }

    /**
     * 两份分区文本是否**逐字**相同 —— 这就是"内容没变就不发同步包"的唯一判据。
     *
     * <p>为什么判据放在 common：它是纯粹的数据比较（零 MC、零 OC），必须能离线断言
     * （{@code :common:runInspectTest}）。判据一旦写松（"看着差不多就算没变"）就会该发的
     * 不发、面板数字不动；写紧（把采集时刻也算进内容）就会每秒把六个分区的全量文本推一遍。
     * 这两种症状在真机上都只表现为"面板怪怪的"，不放在能离线测的地方就查不动。</p>
     *
     * <p>长度不同 / 任意一段不同 / 有一侧是 {@code null}（另一侧不是）都判"不同"；
     * 两个引用相同或是两份逐字相同的数组判"相同"。</p>
     */
    public static boolean sameSections(String[] a, String[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!Objects.equals(a[i], b[i])) {
                return false;
            }
        }
        return true;
    }

    /** 行数（自测与面板标题用） */
    public static int count(Snapshot s) {
        return rows(s).size();
    }

    // ==================== 单位换算（真机与自测用同一份） ====================

    /** 数字：整数不带小数点，其余两位小数（"44.72" / "100"） */
    public static String num(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return DASH;
        }
        final double r = Math.rint(v);
        return Math.abs(v - r) < 1e-6 ? String.valueOf((long) r) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** 频率：{@code 100 MHz} */
    public static String mhz(double v) {
        final String n = num(v);
        return DASH.equals(n) ? DASH : n + " MHz";
    }

    /** 比例 → 百分数（0.4472 ⇒ {@code 45%}） */
    public static String percent(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio) || ratio < 0) {
            return DASH;
        }
        return Math.round(ratio * 100.0) + "%";
    }

    /** 字节 → B/KB/MB（1024 进制；整除时不带小数点，0 也是 {@code 0 B}） */
    public static String bytes(long n) {
        if (n < 0) {
            return DASH;
        }
        if (n < 1024L) {
            return n + " B";
        }
        if (n < 1024L * 1024L) {
            return scale(n, 1024L, "KB");
        }
        return scale(n, 1024L * 1024L, "MB");
    }

    /** 地址：{@code 0x20021800}（大写十六进制） */
    public static String hex(long v) {
        return "0x" + Long.toHexString(v).toUpperCase(Locale.ROOT);
    }

    // ==================== 内部 ====================

    private static String scale(long n, long unit, String suffix) {
        return (n % unit == 0 ? String.valueOf(n / unit) : String.format(Locale.ROOT, "%.1f", n / (double) unit))
                + " " + suffix;
    }

    /** 补位不补零：标签按显示宽度对齐（面板用等宽字体，文本给 MCP 看） */
    private static String pad(String label) {
        final String s = label == null ? DASH : label;
        final StringBuilder sb = new StringBuilder(s);
        while (displayWidth(sb.toString()) < 16) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /** 显示宽度：中文按 2 列算（面板是等宽字体，中文占两格） */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return w;
    }

    /** 模块的关键硬件参数 → 一行 {@code 名 值 · 名 值}（全空 = null，由 add 落成占位符） */
    private static String caps(List<Cap> caps) {
        if (caps == null || caps.isEmpty()) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        for (final Cap c : caps) {
            if (c == null || c.name() == null || c.name().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(c.name().trim()).append(' ').append(orDash(c.value()));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void add(List<Row> out, String section, String label, String value) {
        out.add(new Row(section, orDash(label), orDash(value)));
    }

    /** 拼接：空段直接跳过；全空 ⇒ null（由 add 落成 "-"，不编造） */
    private static String join(String sep, String... parts) {
        final StringBuilder sb = new StringBuilder();
        for (final String p : parts) {
            if (p == null || p.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(p.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String orDash(String s) {
        return s == null || s.isBlank() ? DASH : s;
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
