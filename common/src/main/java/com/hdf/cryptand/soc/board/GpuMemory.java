package com.hdf.cryptand.soc.board;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ===== GPU 与屏幕的内存占用（common，纯 Java 零 MC，2026-09-18）=====
 *
 * <p>用户定案："没有 GPU 则获取不到屏幕"、"屏幕必须有 GPU，并且 GPU 空间占用内存，
 * 屏幕为 0 则只有基础占用，屏幕越多占用越多，最大则是支持 GPU 上限"、"VRAM 与内存同占用"。</p>
 *
 * <p>所以显存**不是免费的额外空间**，它跟内存条容量是同一本账：</p>
 * <ul>
 *   <li>GPU 自身有**基础占用**（寄存器组 / 命令队列 / FIFO）—— 一块屏都不挂也要吃；</li>
 *   <li>每块屏按分辨率与形态吃 VRAM：字符屏 = 头 + 三平面（code/fg/bg）；真彩 = 头 + w×h×bpp/8；</li>
 *   <li>每个 GPU 能带的屏数有**上限**（由 GPU 型号决定），超了明确报错，不静默截断；</li>
 *   <li>总需求超池容量 ⇒ **在显存（设备）侧**拒绝这一次装配/挂载并写明差多少 KB。</li>
 * </ul>
 *
 * <p>⚠ 口径与**归属**（用户 2026-09-27 定案："内存归虚拟机（沙箱）接管管理，虚拟机是一台完整电脑，
 * 外部比如内存只是<b>指示器</b>"；"内存不够会导致<b>渲染</b>出问题"）：</p>
 * <ul>
 *   <li>池的数值来自内存条这个<b>指示器</b>（内存条只声明"这台虚拟机有多少内存"），本类不做别的账；</li>
 *   <li>容量**校验发生在显存（设备）侧**—— {@code CryptandGpuEnvironment.bind} 挂屏那一刻按本类口径
 *       算一次，放不下就回错误 + 日志，机器照常跑；</li>
 *   <li><b>不在引导/EEPROM 阶段校验</b>：那里的内存账已随定案删除（内存归虚拟机自己管）。
 *       所以本类的方法**只是校验工具**，谁在哪里调用它决定"表现点"—— 表现点在渲染侧。</li>
 * </ul>
 *
 * <p>与 {@link DisplayWindow} 的关系：显存窗口的字节数就是这里的"每屏占用"
 * （字符屏走 {@code DisplayWindow.textWindowBytes}），**单一来源**，不另算一份。</p>
 */
public final class GpuMemory {

    /** GPU 基础占用（KB）：寄存器组 + 命令队列 + FIFO，哪怕 0 屏也要吃 */
    public static final int GPU_BASE_KB = 64;

    /**
     * 平台能造出的**单卡通道数上限**（超过这个数的档位规格是配错了，要报错）。
     *
     * <p>⚠ 单卡实际上能挂几块屏由**卡自己的通道数**决定（用户 2026-09-26："显卡有定义通道，
     * 1 个通道则支持 1 个屏幕（包括拼接的屏幕算一个），4 个则表示 4 个"）——
     * 物品规格里那一栏就是通道数（{@code graphicscard1/2/3} = 1/2/4）。这里是平台天花板，
     * 不是"每块卡都 4 块"。</p>
     */
    public static final int MAX_CHANNELS_PER_CARD = 4;

    /**
     * 一张显卡：名字（屏用它声明归属）+ **通道数**（能同时驱动几块逻辑屏）+ 种类。
     *
     * @param name     卡的名字（与 {@link DisplayTopology.Screen#gpuName()} 对应，也是日志/UI 里的名字）
     * @param channels 通道数（1/2/4…）：1 个通道 = 1 块逻辑屏；拼接屏算一块
     * @param kind     卡的种类（OC 原版只能驱字符屏；Cryptand 两种都能驱）
     */
    public record Card(String name, int channels, DisplayTopology.GpuKind kind) {
    }

    private GpuMemory() {
    }

    /** 一块屏的 VRAM 字节数（字符屏 = 头 + 三平面；真彩 = 头 + w*h*bpp/8） */
    public static int screenVramBytes(DisplayTopology.ScreenKind kind, int cols, int rows, int bpp) {
        if (kind == null) {
            throw new IllegalArgumentException("screen kind must not be null");
        }
        return switch (kind) {
            case TEXT -> DisplayWindow.textWindowBytes(cols, rows);
            case TRUE_COLOR -> DisplayWindow.HEADER_BYTES + (cols * rows * bpp + 7) / 8;
        };
    }

    /** 一台机器的显存总需求（字节）= Σ 各卡基础 + Σ 各屏 VRAM（按通道表算的版本） */
    public static long totalVramBytes(List<Card> cards, List<DisplayTopology.Screen> screens) {
        return totalVramBytes(cards == null ? 0 : cards.size(), screens);
    }

    /** 一台机器的显存总需求（字节）= GPU 数 × 基础 + Σ 各屏 VRAM */
    public static long totalVramBytes(int gpuCount, List<DisplayTopology.Screen> screens) {
        if (gpuCount < 0) {
            throw new IllegalArgumentException("gpuCount must be >= 0");
        }
        long sum = (long) gpuCount * GPU_BASE_KB * 1024L;
        if (screens != null) {
            for (final DisplayTopology.Screen s : screens) {
                sum += screenVramBytes(s.kind(), s.cols(), s.rows(), s.bpp());
            }
        }
        return sum;
    }

    /**
     * 装配校验：屏数上限 + 内存容量。
     *
     * @throws IllegalStateException 屏数超过 GPU 上限 / 内存不够（报错里带差多少 KB）
     */
    public static void validate(int memoryKb, List<Card> cards, List<DisplayTopology.Screen> screens) {
        final List<Card> gpus = cards == null ? List.of() : cards;
        final List<DisplayTopology.Screen> list = screens == null ? List.of() : screens;
        for (final Card c : gpus) {
            if (c.channels() < 1 || c.channels() > MAX_CHANNELS_PER_CARD) {
                throw new IllegalStateException("显卡 " + c.name() + " 的通道数不合法：" + c.channels()
                        + "（平台上限 " + MAX_CHANNELS_PER_CARD + "）");
            }
        }
        // ① 通道**按卡算**：屏按 gpuName 归类，**拼接屏算一块**（调用方给的就是逻辑屏）。
        //    ⚠ 卡名是**型号名**（物品 id 的 path，如 graphicscard1），不是实例编号
        //    （屏只带 gpuName 这一栏，没有实例号可用）⇒ 同名卡的通道必须**合并**成
        //    "这个型号的通道池"再比：机箱里插两块 graphicscard1、各挂一块屏是合法的，
        //    逐张卡各比一次会把第二块屏误判成"通道不够"。
        final Map<String, Integer> channelsByName = new LinkedHashMap<>();
        final Map<String, Integer> countByName = new LinkedHashMap<>();
        for (final Card c : gpus) {
            channelsByName.merge(c.name(), c.channels(), Integer::sum);
            countByName.merge(c.name(), 1, Integer::sum);
        }
        for (final Map.Entry<String, Integer> group : channelsByName.entrySet()) {
            int attached = 0;
            for (final DisplayTopology.Screen s : list) {
                if (group.getKey().equals(s.gpuName())) {
                    attached++;
                }
            }
            if (attached > group.getValue()) {
                throw new IllegalStateException("通道不够：" + group.getKey() + " 共 " + group.getValue()
                        + " 个输出通道（同名卡 " + countByName.get(group.getKey()) + " 块，能驱 "
                        + group.getValue() + " 块逻辑屏，拼接的算一块），实际挂了 " + attached
                        + " 块 —— 换通道更多的显卡，或减屏");
            }
        }
        // ② 屏必须挂在真的存在的卡上（没有 GPU 就没有显示区域，不许悬空）
        for (final DisplayTopology.Screen s : list) {
            boolean known = false;
            for (final Card c : gpus) {
                if (c.name().equals(s.gpuName())) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                throw new IllegalStateException("屏 " + s.id() + " 声明挂在 " + s.gpuName()
                        + " 上，但这台机器没有这块显卡（没有 GPU 就看不见显示区域）");
            }
        }
        final long need = totalVramBytes(gpus, list);
        final long have = (long) memoryKb * 1024L;
        if (need > have) {
            throw new IllegalStateException("内存不够放显存：需要 " + (need / 1024) + " KB（GPU 基础 "
                    + (gpus.size() * GPU_BASE_KB) + " KB + 各屏 VRAM "
                    + ((need - (long) gpus.size() * GPU_BASE_KB * 1024L) / 1024)
                    + " KB），内存条只有 " + memoryKb + " KB，差 " + ((need - have) / 1024) + " KB —— "
                    + "显存与内存同占用：加内存条、减屏，或换更小的分辨率");
        }
    }

    /** 一行摘要（日志/UI/无人化断言） */
    public static String summary(int memoryKb, List<Card> cards, List<DisplayTopology.Screen> screens) {
        final List<Card> gpus = cards == null ? List.of() : cards;
        final List<DisplayTopology.Screen> list = screens == null ? List.of() : screens;
        final long need = totalVramBytes(gpus, list);
        int channels = 0;
        for (final Card c : gpus) {
            channels += c.channels();
        }
        return "vram: " + (need / 1024) + "KB / mem " + memoryKb + "KB（GPU×" + gpus.size()
                + " 基础 " + (gpus.size() * GPU_BASE_KB) + "KB + 屏×" + list.size()
                + "，通道 " + list.size() + "/" + channels + "）";
    }

    /**
     * 一台机器的**显示预算**：内存池（KB）+ 卡表 + 已挂上的逻辑屏。
     *
     * <p>为什么要有这个对象（口径只有一个，但"谁算、谁用"分在两处）：池与卡表只有**架构侧**
     * 算得出来（那里才看得到机箱里的内存条与显卡物品），而 gpu 组件在 {@code bind} 时要拿
     * <b>同一份</b>数去判"这块屏放得下吗"。让 gpu 组件自己再翻一遍机箱就是第二份口径
     * ——两处迟早一处改了另一处没改，而症状（屏幕亮不亮）最难查。于是架构把这份预算
     * <b>交给</b>组件，组件只读不算。</p>
     *
     * <p>屏表也放在这里（而不是每块卡各记一份）：内存池是**整机**的，两块卡各自的屏都要从
     * 同一个池里扣；各卡只算自己那几块的话，两块卡就能一起把池占爆而谁都发现不了。</p>
     */
    public static final class Budget {

        private final int memoryKb;

        private final List<Card> cards;

        private final List<DisplayTopology.Screen> screens = new ArrayList<>();

        /**
         * @param memoryKb 内存池（KB）= 这台机器内存条容量（架构侧从 OC 的 Memory 组件求和得到）
         * @param cards    本机卡表（架构侧扫描机箱得到；含 OC 原版显卡）
         */
        public Budget(int memoryKb, List<Card> cards) {
            this.memoryKb = Math.max(0, memoryKb);
            this.cards = List.copyOf(cards == null ? List.of() : cards);
        }

        /** 内存池（KB）—— 显存从这里扣 */
        public int memoryKb() {
            return memoryKb;
        }

        /** 本机卡表（不可变快照） */
        public List<Card> cards() {
            return cards;
        }

        /** 已经挂上、且已记进内存口径的逻辑屏 */
        public List<DisplayTopology.Screen> screens() {
            synchronized (screens) {
                return List.copyOf(screens);
            }
        }

        /**
         * 记一块已经挂上的屏。
         *
         * <p>同 id 幂等：组件对象**跨重启存活**（OC 只在机箱卸载时才销毁组件环境），
         * 而架构每次开机都换一份新预算（屏表从零开始）⇒ 组件会把自己已经 bind 的屏重新登记
         * 一遍，不能因此记成两块。</p>
         */
        public void attach(DisplayTopology.Screen screen) {
            if (screen == null) {
                return;
            }
            synchronized (screens) {
                for (final DisplayTopology.Screen s : screens) {
                    if (s.id().equals(screen.id())) {
                        return;
                    }
                }
                screens.add(screen);
            }
        }

        /** 显存总需求（字节）= Σ 卡基础 + Σ 已挂屏 VRAM */
        public long vramNeedBytes() {
            return totalVramBytes(cards, screens());
        }

        /**
         * bind 前的闸门：把**候选屏**也算进去校验一次。
         *
         * @throws IllegalStateException 内存不够（报错里带差多少 KB）/ 通道不够 / 屏悬空
         */
        public void validateWith(DisplayTopology.Screen candidate) {
            final List<DisplayTopology.Screen> all = new ArrayList<>(screens());
            if (candidate != null) {
                all.add(candidate);
            }
            validate(memoryKb, cards, all);
        }

        /** 一行摘要（含候选屏；bind 成功时进日志，实机上一眼能看出 vram/mem 口径） */
        public String summaryWith(DisplayTopology.Screen candidate) {
            final List<DisplayTopology.Screen> all = new ArrayList<>(screens());
            if (candidate != null) {
                all.add(candidate);
            }
            return summary(memoryKb, cards, all);
        }
    }
}
