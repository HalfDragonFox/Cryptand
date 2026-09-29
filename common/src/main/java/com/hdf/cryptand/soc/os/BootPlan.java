package com.hdf.cryptand.soc.os;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * ===== 引导决策（common，纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-18）：盘里**真的装着程序**，"空软盘 + 程序加载器"写完盘插进机箱就能跑 ——
 * 所以"装什么系统"由<b>盘的内容</b>决定，不再由盘的档位查表决定。</p>
 *
 * <p>用户定案（2026-09-24，引导分层）：<b>EEPROM 里的 Cryptand BIOS 只认引导记录</b> ——</p>
 * <pre>
 *   Cryptand BIOS（宿主 Java，不进沙箱）
 *        ↓ 选中盘上的引导记录 /boot/loader.bin，刷进 guest ROM 引导区 0x0 并交控制权
 *   bootloader（同一个 cryptand-boot 固件）
 *        ↓ 通过引导服务 ABI 请求宿主把系统刷进系统区 0x1_0000
 *   系统本体（/boot/system.bin）
 * </pre>
 *
 * <p>⚠ <b>判据只有一条：盘上有 bootloader</b>（用户 2026-09-25："只认 bootloader 保持现实一致"）。
 * 真实 BIOS 不解析文件系统、更不会"绕过引导记录直接跑系统本体"—— 没有引导记录就是
 * <i>no bootable device</i>，用户得自己去装。所以这里<b>没有</b>"旧盘直接引导系统"的兼容分支：
 * 那种盘在现实里等价于"分区里放了系统但没有引导记录"，本来就起不来。</p>
 *
 * <p>顺序对齐真实 BIOS 的 boot order：<b>软盘优先于硬盘</b>，同类内按机箱槽位升序；
 * 逐块检查引导记录，第一块有效盘胜出。</p>
 *
 * <p>⚠ 这段逻辑放 common 而不是 MC 侧：它是"该从哪块盘引导"的<b>唯一</b>实现，
 * 沙盒可以直接构造盘列表来测 —— 不需要机箱、物品、世界。</p>
 */
public final class BootPlan {

    /**
     * 一块候选盘。
     *
     * @param slot  机箱槽位（同类内按它升序）
     * @param floppy 是否软盘（软盘优先）
     * @param tier  盘档位 1/2/3（只用于显示与日志）
     * @param label 显示名
     * @param fs    盘的文件系统（读引导记录用）
     */
    public record Disk(int slot, boolean floppy, int tier, String label, CryptandFileSystem fs) {
    }

    /** guest 引导区基址 = {@code 0x0}（{@code cryptand_boot.ld} 的 ROM 布局） */
    public static final long LOADER_BASE = 0x0L;

    /**
     * 引导区大小 = 64KB（{@code cryptand_boot.ld}：Boot 占 ROM {@code 0x0..0x1_0000}，
     * 系统从 {@code 0x1_0000} 开始）。
     *
     * <p>⚠ 这是**硬边界**而不是"建议大小"：超过它的引导记录在刷进 guest 时会盖住系统区，
     * 表现成"系统跑着跑着飞了"。mini OS 的 &lt;4KB 是体积约定（见 {@link Programs#LOADER_PATH}），
     * 这条则是不能再退的物理边界。</p>
     */
    public static final int LOADER_ROM_BYTES = 64 * 1024;

    /** guest 系统区基址 = {@code 0x1_0000}（{@code cryptand_os.ld} 的 ROM 布局；第二级引导用它） */
    public static final long SYSTEM_BASE = 0x1_0000L;

    /**
     * 没有插 EEPROM 部件时按**最大档**放行（{@code 128 KB}）。
     *
     * <p>⚠ 这是一个**明确的兼容决策**，不是静默兜底：引导记录的体积上限在现实里由
     * "承载它的那颗芯片有多大"决定（{@link com.hdf.cryptand.soc.part.SocCapacities#EEPROM_KB}）。
     * 但老机器/OC 原版机箱上可能没有插我们的 EEPROM 部件，而它们此前是能开机的 ——
     * 升级后突然"不可引导"比"按最大档放行"糟得多。日志会写明这一点（见 BIOS）。</p>
     */
    public static int defaultEepromKb() {
        final int[] tiers = com.hdf.cryptand.soc.part.SocCapacities.EEPROM_KB;
        return tiers[tiers.length - 1];
    }

    /**
     * 决策结果：从哪块盘引导、引导记录字节是什么。
     *
     * <p>{@link #loadAddress()} = 引导记录的**链接地址 = 载入地址 = 入口**（镜像按 ROM 布局链接）。
     * 刷错地址会让 guest 立刻跑飞，所以这个换算只有一份。</p>
     */
    public record Decision(int slot, String label, byte[] program) {

        /** 引导记录应当刷进 guest 的地址 */
        public long loadAddress() {
            return LOADER_BASE;
        }
    }

    private BootPlan() {
    }

    /**
     * 启动顺序（BIOS Setup 里可配置的那一项）。
     *
     * <p>默认 {@link #FLOPPY_FIRST} 对齐用户规格："先从软盘开始，再搜索硬盘，
     * 然后对第一个有效程序进行加载"。</p>
     */
    public enum BootOrder {
        /** 软盘优先（默认，也是真机 BIOS 的历史默认） */
        FLOPPY_FIRST,
        /** 硬盘优先 */
        HDD_FIRST,
        /** 纯按槽位顺序（忽略介质类型） */
        SLOT_ORDER
    }

    /** 按默认 boot order 排序（软盘优先，其次槽位升序）—— 也用于日志与界面显示候选顺序 */
    public static List<Disk> ordered(List<Disk> disks) {
        return ordered(disks, BootOrder.FLOPPY_FIRST);
    }

    /** 按**配置的** boot order 排序 */
    public static List<Disk> ordered(List<Disk> disks, BootOrder order) {
        final List<Disk> out = new ArrayList<>(disks);
        final BootOrder o = order == null ? BootOrder.FLOPPY_FIRST : order;
        switch (o) {
            case HDD_FIRST -> out.sort(Comparator.comparing(Disk::floppy).thenComparingInt(Disk::slot));
            case SLOT_ORDER -> out.sort(Comparator.comparingInt(Disk::slot));
            default -> out.sort(Comparator.comparing((Disk d) -> !d.floppy()).thenComparingInt(Disk::slot));
        }
        return out;
    }

    /**
     * 选引导盘：boot order 上第一块**有有效引导记录**的盘胜出。
     *
     * @return {@code null} = 没有任何可引导的盘。调用方应当报"无引导盘"，
     *         <b>不要</b>静默跑一个内置镜像 —— 那会让"盘没装好"表现成"跑的是旧系统"。
     */
    public static Decision choose(List<Disk> disks) {
        return choose(disks, BootOrder.FLOPPY_FIRST);
    }

    /** 按**配置的** boot order 选盘（引导记录上限 = 最大 EEPROM 档） */
    public static Decision choose(List<Disk> disks, BootOrder order) {
        return choose(disks, order, defaultEepromKb());
    }

    /**
     * 按**配置的** boot order 选盘，并带上这台机器的 EEPROM 容量。
     *
     * @param eepromKb 承载引导记录的那颗 EEPROM 的容量（KB）；{@code <= 0} ⇒ 用最大档
     */
    public static Decision choose(List<Disk> disks, BootOrder order, int eepromKb) {
        for (final Disk d : ordered(disks, order)) {
            final Decision hit = on(d, eepromKb);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 这块盘的引导记录（可引导 = 有 {@link Programs#LOADER_PATH} 且体积不超过引导区）。
     *
     * <p>这段逻辑**只有这一份**：BIOS 的强制槽位与 boot order 扫描都走它 ——
     * 免得改了一处、另一处还在按老规则选盘。</p>
     *
     * @return {@code null} = 这块盘不可引导（原因见 {@link #explain(Disk)}）
     */
    public static Decision on(Disk d) {
        return on(d, defaultEepromKb());
    }

    /**
     * 这块盘的引导记录，按"这颗 EEPROM 装得下多少"来判。
     *
     * <p>上限取两者的**较小值</b>：① 引导区 64KB（{@link #LOADER_ROM_BYTES}，物理边界）；
     * ② EEPROM 容量（现实里引导固件就烧在那颗芯片上）。</p>
     */
    public static Decision on(Disk d, int eepromKb) {
        final byte[] loader = Programs.installedLoader(d.fs());
        if (loader.length == 0 || loader.length > limitBytes(eepromKb)) {
            return null;
        }
        return new Decision(d.slot(), d.label(), loader);
    }

    /** 引导记录的字节上限 = min(引导区, EEPROM 容量)；{@code eepromKb <= 0} ⇒ 按最大档 */
    public static int limitBytes(int eepromKb) {
        final int kb = eepromKb <= 0 ? defaultEepromKb() : eepromKb;
        return (int) Math.min((long) kb * 1024L, (long) LOADER_ROM_BYTES);
    }

    /**
     * 这颗 EEPROM 的**芯片容量（字节）** = 档位规格 KB × 1024。
     *
     * <p>⚠ 与 {@link #limitBytes(int)} 是**两个不同的量**，谁也不许替代谁：</p>
     * <ul>
     *   <li>{@code limitBytes} = "**引导记录**最多能占多少" = min(芯片容量, 引导区 64KB)
     *       —— 它是**引导判据**（超过它刷进去会盖住系统区）；</li>
     *   <li>本方法 = "这颗芯片**自己**有多大" —— OC 的 {@code eeprom.getSize()} 问的是这个。</li>
     * </ul>
     * <p>拿 {@code limitBytes} 回答容量就是答错了题：128KB 档会报成 65536 字节，和物品规格
     * 对不上（容量表唯一来源 {@link com.hdf.cryptand.soc.part.SocCapacities#EEPROM_KB}）。</p>
     *
     * @throws IllegalArgumentException 档位 &lt;= 0（"这颗芯片多大"没有默认档：编一个数就是第二份口径）
     * @throws ArithmeticException      超出 int 字节范围（明确报错，不静默溢出成负数）
     */
    public static int eepromBytes(int specKb) {
        if (specKb <= 0) {
            throw new IllegalArgumentException("EEPROM 容量档位必须 > 0 KB（实际 " + specKb + "）");
        }
        return Math.multiplyExact(specKb, 1024);
    }

    /**
     * 这块盘**为什么**不可引导（BIOS 日志用）。
     *
     * <p>判据只有一条之后，"不可引导"有好几种成因（空盘 / 只有系统没有引导记录 / 引导记录超界）。
     * 现实中 BIOS 只会说一句 "no bootable device"，我们没必要跟着含糊 ——
     * 用户需要知道该修哪一块盘、怎么修。</p>
     */
    public static String explain(Disk d) {
        return explain(d, defaultEepromKb());
    }

    /** 同 {@link #explain(Disk)}，但按这台机器的 EEPROM 容量判定 */
    public static String explain(Disk d, int eepromKb) {
        final byte[] loader = Programs.installedLoader(d.fs());
        if (loader.length == 0) {
            final byte[] system = Programs.installed(d.fs());
            return system.length == 0
                    ? "空盘（既没有 " + Programs.LOADER_PATH + " 也没有 " + Programs.BOOT_PATH + "）"
                    : "有系统本体但没有引导记录 " + Programs.LOADER_PATH
                            + " —— 用 /cryptand soc disk install 重装一次即可";
        }
        if (loader.length > LOADER_ROM_BYTES) {
            return "引导记录 " + loader.length + " 字节，超过引导区 " + LOADER_ROM_BYTES
                    + " 字节（刷进去会盖住系统区）";
        }
        if (loader.length > limitBytes(eepromKb)) {
            return "引导记录 " + loader.length + " 字节，超过这颗 EEPROM 的容量 "
                    + limitBytes(eepromKb) + " 字节 —— 换更大档的 EEPROM，或用更小的引导固件";
        }
        return "可引导";
    }

    // ==================== 虚拟机的 0x0：全档位同一条「第一有效文件」规则 ====================
    //
    // 用户 2026-09-27 定案：
    //   · "虚拟机运行基本都是从 0x0 开始的，不管 mcu 还是 soc，如果 0x0 部分是 bootloader
    //      就是 boot 启动，虚拟机只需要从 0x0 开始执行代码即可"；
    //   · "eeprom 只需要找到**第一有效文件**然后把内容加载到虚拟机里然后就不需要管了"。
    //
    // ⇒ **档位只决定「谁把什么放到 0x0」，不改变"放到 0x0 的是什么"这条规则**：
    //     · 第一有效文件 = 有 {@link Programs#LOADER_PATH} 就用它（0x0 上是 bootloader ⇒ boot 启动：
    //       由它经引导服务回来要系统本体）；没有才退到 {@link Programs#BOOT_PATH}（0x0 直接就是系统 = 单段）；
    //     · 谁去取它：SOC/CPU = 宿主 BIOS（枚举 → 选第一有效盘 → 加载进 0x0 并交接）；
    //       MCU = 平台直接把第一块有效盘当 flash（取指即读盘，同样从 0x0 起）；
    //     · 两种档位都从 0x0 开始执行（复位向量 0x0）；"0x0 算 ROM 还是 RAM"不是设计问题
    //       —— 内存归虚拟机自己管，内存条只是指示器。
    //    ⇒ 以前"MCU 不认引导记录、只有 BIOS 才认"的两套判据，现在**只剩一份选择函数**（{@link #entryOf(Disk)}）：
    //       MCU 直接用它（flash 的 0x0 就是它）；BIOS 路径只用它的**第一分支**（引导记录）—— "BIOS 认不认"这件事本身没变，
    //       变的是"第一有效文件到底指哪个"只有一处定义；只有系统本体的盘在两档结论不同（BIOS 拒绝、MCU 单段跑系统）。
    //
    // ⚠ 为什么 flash 窗口由调用方给（不在这里写死）：窗口 = 这台机器 guest ROM 的实际大小
    //   （{@code OcBoardLayout.ROM_BYTES}）。在本文件再写一个"512KB"就是第二份数值。

    /**
     * **第一有效文件**（用户 2026-09-27 定案的那一个）：路径 + 字节。
     *
     * <p>它就是"虚拟机会从 0x0 执行的东西"。@see #entryOf(Disk)</p>
     */
    public record Entry(String path, byte[] bytes) {
    }

    /**
     * 这块盘上的**第一有效文件**（全档位唯一一份规则）。
     *
     * <p>有引导记录就用引导记录（0x0 上是 bootloader ⇒ boot 启动）；没有才退到程序本体
     * （0x0 直接是系统 ⇒ 单段）。两条档位（MCU 的 flash / SOC·CPU 的 BIOS）都调这一个方法 ——
     * 判据只允许有一份，否则"同一块盘两个档位结论不同"会变成常态。</p>
     *
     * @return {@code null} = 这块盘上没有任何可执行入口（0x0 无内容 ⇒ 不可引导）
     */
    public static Entry entryOf(Disk d) {
        final byte[] loader = Programs.installedLoader(d.fs());
        if (loader.length > 0) {
            return new Entry(Programs.LOADER_PATH, loader);
        }
        final byte[] system = Programs.installed(d.fs());
        return system.length > 0 ? new Entry(Programs.BOOT_PATH, system) : null;
    }

    /**
     * MCU 档的引导结果：**第一块有第一有效文件的盘**就是这颗 MCU 的 flash。
     *
     * <p>{@link #entryPath()} 说明 0x0 处放的是哪个文件（引导记录 ⇒ 由它 boot 启动；程序本体 ⇒ 单段）。</p>
     *
     * <p>{@link #loadAddress()} = 镜像的载入地址 = 链接地址 = 入口 = 0x0。镜像按 ROM 布局链接
     * （首字节即 {@code .text.start}），所以"入口"不是一个要额外算出来的数 —— 与现有引导记录
     * 同一份约定。</p>
     */
    public record Flash(int slot, String label, String entryPath, byte[] program) {

        /** flash 的载入地址：ROM 起始 0x0（= {@link #LOADER_BASE}，同一份 ROM 布局，不是第二个数值） */
        public long loadAddress() {
            return LOADER_BASE;
        }

        /** 入口地址 = 载入地址（镜像首字节就是 {@code .text.start}） */
        public long entryAddress() {
            return loadAddress();
        }
    }

    /**
     * MCU 的"有效盘"判据：盘上有**第一有效文件**，且装得进 flash 窗口。
     *
     * <p>判据就是 {@link #entryOf(Disk)} —— 与 BIOS 链路同一份（见上面那段定案说明）。</p>
     */
    public static boolean flashable(Disk d, int flashBytes) {
        final Entry entry = entryOf(d);
        return entry != null && entry.bytes().length <= flashBytes;
    }

    /**
     * 选 flash 盘：boot order 上第一块**有第一有效文件**的盘胜出（MCU 档的引导决策）。
     *
     * @param flashBytes flash 窗口字节数（= 这台机器的 guest ROM 大小；{@code <= 0} ⇒ 明确报错）
     * @return {@code null} = 一块有效盘都没有。调用方应当**拒绝开机**并说明原因 ——
     *         MCU 没有 BIOS 可退，绝不能静默跑一个内置镜像（那会让"没装程序"表现成"跑的是旧程序"）
     */
    public static Flash flash(List<Disk> disks, BootOrder order, int flashBytes) {
        if (flashBytes <= 0) {
            throw new IllegalArgumentException("flash window must be > 0 (got " + flashBytes + ")");
        }
        for (final Disk d : ordered(disks, order)) {
            final Entry entry = entryOf(d);
            if (entry != null && entry.bytes().length <= flashBytes) {
                return new Flash(d.slot(), d.label(), entry.path(), entry.bytes());
            }
        }
        return null;
    }

    /**
     * 这块盘**为什么**不能当 MCU 的 flash（拒绝开机时逐块写进日志）。
     *
     * <p>与 BIOS 的 {@link #explain(Disk, int)} 分开写：两者的**判据已经合并**（都是第一有效文件），
     * 但"拒绝的理由"要按档位说清楚 —— BIOS 那边拒绝是"没有可引导入口 / 引导记录装不下 EEPROM"，
     * MCU 这边是"0x0 没有任何可执行内容 / 第一有效文件装不进 flash 窗口"，合成一句话必然有一边说不清。</p>
     */
    public static String flashExplain(Disk d, int flashBytes) {
        final Entry entry = entryOf(d);
        if (entry == null) {
            return "空盘（既没有 " + Programs.LOADER_PATH + " 也没有 " + Programs.BOOT_PATH + "）";
        }
        if (entry.bytes().length > flashBytes) {
            return "第一有效文件 " + entry.path() + "（" + entry.bytes().length + " 字节）超过 flash 窗口 "
                    + flashBytes + " 字节（0x0 起）—— 换更大的处理器档位，或裁小它";
        }
        return "可作 flash（0x0 ← " + entry.path() + "）";
    }

    // ==================== 引导目标（架构）：Lua 与 C/RV32 并列，按架构选 ====================
    //
    // 用户定案（2026-09-27，交接文档 §二十二 队列第 6 项）："EEPROM 既支持原版 OC 的 Lua 兼容启动，
    // 也支持从 FATFS 盘找 bootloader 启动"；"引导目标枚举里把 Lua 视作一种目标，与 C/RV32 那条并列
    // （按架构选择，不引入第二套并行链路）"。
    //
    // 现实类比：**同一块盘、同一套 BIOS 选盘顺序**，差别只在"把哪一层交给 CPU"：
    //   · C/RV32 ⇒ 盘上的 /boot/loader.bin（RV32 镜像）刷进 guest ROM 0x0（见 #on/#choose）；
    //   · Lua    ⇒ 盘上的 /init.lua（Lua 文本）交给 Lua 解释器（见 #luaOn/#chooseLua）。
    // ⚠ Lua 侧"执行者"是 EEPROM 里那段 bios.lua（Lua 解释器不能 import 本类），
    //   本类里的 Lua 判据是**同一份规则的可断言表达**（离线闸门 + 诊断工具），不是第二套链路。

    /**
     * Lua 目标的入口候选（顺序即优先级）。
     *
     * <p>⚠ 与 {@code assets/cryptand/soc/bios.lua} 的 {@code CANDIDATES} 是**同一张表**：
     * Lua 侧不能 import Java，所以两侧靠"这张表 + 离线闸门"pin 住（闸门会断言 bios.lua 文本里
     * 这三个路径都在、且 {@link Programs#LOADER_PATH} 也在）。改这里就必须同时改 bios.lua。</p>
     */
    public static final List<String> LUA_INIT_PATHS =
            List.of("/init.lua", "/boot/init.lua", "/usr/init.lua");

    /**
     * 引导目标（架构）：{@link #C_RV32} 与 {@link #LUA} 并列。
     *
     * <p>同一块盘、同一套选盘顺序（{@link #ordered}/{@link #choose}），差别只在
     * <b>把哪一层交给 CPU</b>：</p>
     * <table border="1">
     *   <caption>两个目标的分工</caption>
     *   <tr><th>目标</th><th>判据（盘上的入口）</th><th>交给谁</th></tr>
     *   <tr><td>{@link #C_RV32}</td><td>{@link Programs#LOADER_PATH}（RV32 镜像）</td>
     *       <td>宿主 BIOS 刷进 guest ROM 引导区 0x0，再由它拉 {@link Programs#BOOT_PATH}</td></tr>
     *   <tr><td>{@link #LUA}</td><td>{@link #LUA_INIT_PATHS}（Lua 文本）</td>
     *       <td>EEPROM 里的 {@code bios.lua}（Lua 解释层）—— 见 {@link #luaOn}</td></tr>
     * </table>
     *
     * <p>⚠ <b>Lua 目标没有 Java 调用点</b>：Lua 架构下 Cryptand 的架构类根本不实例化，
     * "执行者"就是 EEPROM 的内容（{@code bios.lua}）本身。所以这里的 Lua 判据是
     * <b>规则的可断言表达</b>（离线闸门与诊断共用），两个目标共用"哪块盘、什么顺序"（本类），
     * 只在"认哪个入口"上分叉 —— 这正是"不引入第二套并行链路"的含义。</p>
     */
    public enum Target {

        /** C/RV32 目标：判据 = 引导记录 {@link Programs#LOADER_PATH}，刷进 guest ROM 0x0 */
        C_RV32("rv32imac", List.of(Programs.LOADER_PATH)),

        /** Lua 目标：判据 = Lua 入口 {@link #LUA_INIT_PATHS}，由 EEPROM 里的 bios.lua 解释执行 */
        LUA("lua", LUA_INIT_PATHS);

        private final String archName;
        private final List<String> entryPaths;

        Target(String archName, List<String> entryPaths) {
            this.archName = archName;
            this.entryPaths = List.copyOf(entryPaths);
        }

        /** 架构名（日志/诊断用；与 {@code CryptandBios.BootTarget#archName()} 同一口径） */
        public String archName() {
            return archName;
        }

        /** 该目标的入口候选（顺序即优先级） */
        public List<String> entryPaths() {
            return entryPaths;
        }

        /** 该目标的主入口（第一个候选；日志/摘要用） */
        public String entryPath() {
            return entryPaths.get(0);
        }

        /**
         * 按**架构类名**选目标（用户定案："按架构选择"）。
         *
         * <p>判据只此一处（照 {@code isMcuFamily} 回查档位表的思路）：名字含 {@code lua} ⇒ {@link #LUA}；
         * 含 {@code cryptand} 或 {@code rv32} ⇒ {@link #C_RV32}。两者都不含（未知架构）或
         * <b>都含</b>（歧义）⇒ {@code null} —— 调用方必须明确报错，不许默认成某一个（那是静默降级）。</p>
         */
        public static Target ofArchitecture(String archClassName) {
            if (archClassName == null) {
                return null;
            }
            final String name = archClassName.toLowerCase(java.util.Locale.ROOT);
            final boolean lua = name.contains("lua");
            final boolean c = name.contains("cryptand") || name.contains("rv32");
            if (lua == c) {
                return null;
            }
            return lua ? LUA : C_RV32;
        }

        /** 同 {@link #ofArchitecture(String)}，直接给架构类（如 {@code CryptandOcArchitecture.class}） */
        public static Target ofArchitecture(Class<?> archClass) {
            return archClass == null ? null : ofArchitecture(archClass.getName());
        }

        /** 这块盘能不能被本目标引导（C/RV32 还要受 EEPROM 容量与引导区上限约束） */
        public boolean canBoot(Disk d, int eepromKb) {
            return this == C_RV32 ? on(d, eepromKb) != null : luaOn(d) != null;
        }

        /** 本目标在这块盘上的入口字节（C/RV32 = 引导记录；Lua = Lua 入口源码）；{@code null} = 不能引导 */
        public byte[] entryBytes(Disk d, int eepromKb) {
            if (this == C_RV32) {
                final Decision hit = on(d, eepromKb);
                return hit == null ? null : hit.program();
            }
            final LuaEntry hit = luaOn(d);
            return hit == null ? null : hit.source();
        }

        /** 这块盘对本目标为什么能/不能引导（逐目标说清：同一块盘在两个目标下的结论可以不同） */
        public String explain(Disk d, int eepromKb) {
            return this == C_RV32 ? BootPlan.explain(d, eepromKb) : luaExplain(d);
        }
    }

    /**
     * Lua 目标的引导结果：盘上的 <b>Lua 入口</b>（文本），交给 Lua 解释器执行。
     *
     * <p>为什么不是 {@link Decision}：那个 record 的语义是"刷进 guest ROM 的镜像 + 载入地址"
     * （{@link Decision#loadAddress()} = 0x0）。Lua 入口**不刷 ROM、没有载入地址** ——
     * 它是被 {@code bios.lua} 用 {@code load()} 编译执行的文本。硬塞进 Decision 会让
     * "载入地址 0x0"在一半场景下变成假话。</p>
     */
    public record LuaEntry(int slot, String label, String path, byte[] source) {
    }

    /**
     * 这块盘的 Lua 入口：按 {@link #LUA_INIT_PATHS} 顺序取**第一个存在且非空**的。
     *
     * <p>与 {@link #on} 同一形状（读不到/空 ⇒ {@code null}），于是"这块盘能不能被某个目标引导"
     * 在两个目标上是同一种写法。空文件与不存在同样处理：都算"没有入口"（不静默当成空程序跑起来）。</p>
     *
     * @return {@code null} = 这块盘没有 Lua 入口（原因见 {@link #luaExplain(Disk)}）
     */
    public static LuaEntry luaOn(Disk d) {
        for (final String path : LUA_INIT_PATHS) {
            final byte[] text = Programs.installedAt(d.fs(), path);
            if (text.length > 0) {
                return new LuaEntry(d.slot(), d.label(), path, text);
            }
        }
        return null;
    }

    /** 按默认 boot order 选 Lua 入口：第一块有 Lua 入口的盘胜出 */
    public static LuaEntry chooseLua(List<Disk> disks) {
        return chooseLua(disks, BootOrder.FLOPPY_FIRST);
    }

    /**
     * 按**配置的** boot order 选 Lua 入口（Lua 目标的 {@link #choose}）。
     *
     * <p>⚠ 与 C/RV32 侧的差别只有"认哪个入口"。顺序仍由 {@link #ordered} 唯一决定 ——
     * 但真机上 Lua 侧的磁盘扫描顺序是 <b>OC 组件注册顺序</b>（Lua 看不见"软盘/硬盘"这个属性，
     * 见 bios.lua）：两侧对"同一块盘能不能引导"的结论一致，而"多块盘同时可引导时先选哪块"
     * 在 Lua 侧只能按组件顺序 —— 这一点写在 bios.lua 的注释里，不假装两边完全一样。</p>
     */
    public static LuaEntry chooseLua(List<Disk> disks, BootOrder order) {
        for (final Disk d : ordered(disks, order)) {
            final LuaEntry hit = luaOn(d);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 这块盘对 **Lua 目标**为什么不能引导（BIOS/诊断用）。
     *
     * <p>与 C/RV32 的 {@link #explain(Disk, int)} 分开写：同一块盘两边结论可以不同 ——
     * 一块只装了 {@link Programs#LOADER_PATH} 的盘在 C/RV32 眼里"可引导"、在 Lua 眼里
     * 恰恰是"这块盘不是给你用的"。合成一句话必然有一边说错，所以逐目标各答一次。</p>
     */
    public static String luaExplain(Disk d) {
        final LuaEntry hit = luaOn(d);
        if (hit != null) {
            return "可引导（Lua 入口 " + hit.path() + "，" + hit.source().length + " 字节）";
        }
        final byte[] loader = Programs.installedLoader(d.fs());
        if (loader.length > 0) {
            return "只有 C/RV32 引导记录 " + Programs.LOADER_PATH + "（" + loader.length + " 字节），没有 Lua 入口 "
                    + LUA_INIT_PATHS.get(0) + " —— 这块盘是给 C/RV32 处理器引导的，Lua 架构的 CPU 不解释它"
                    + "（换 RV32 处理器，或把 Lua 入口装进这块盘）";
        }
        return "空盘（既没有 Lua 入口 " + LUA_INIT_PATHS.get(0) + " 也没有引导记录 "
                + Programs.LOADER_PATH + "）";
    }
}
