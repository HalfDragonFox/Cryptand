/**
 * ===== Cryptand Boot 的宿主侧：BIOS 选盘 + BIOS 读盘服务（2026-09-17；2026-09-27 重塑）=====
 *
 * <p>用户定案（2026-09-27）：<b>"EEPROM 属于外部 BIOS 部分，然后虚拟机内为真实芯片模拟"</b>、
 * <b>"BIOS 类似现实做引导，负责从盘中读取文件加载到虚拟机内运行"</b>。
 * 于是本类只有两件事，都对应现实主板 BIOS 的一部分：</p>
 * <pre>
 *   ① 上电取指那一段（= INT 19h）：BIOS 枚举候选盘 → 按 boot order 选盘
 *        ↓ 把选中盘的 /boot/loader.bin 写进 guest ROM **引导区 0x0** → 启动虚拟机
 *        ↓ 把「引导盘是哪一块」交给程序（{@link #declareBootDisk(int)} = INT 19h 传 DL）
 *   ② 读盘服务（= INT 13h）：bootloader 请求"把引导盘上的某个文件读进我的内存"
 *        ↓ {@link #readFile(String, long)} —— 取哪个文件、放到哪个地址都由 guest 说
 *   之后 BIOS 的角色结束：程序跑什么、怎么跑，是虚拟机里那颗芯片的事
 * </pre>
 *
 * <p>⚠ "哪块盘能引导"只有一份规则，见 common 的 {@link BootPlan}；"第一有效文件"只有一份实现
 * （{@link BootPlan#entryOf}）。本类不做判据，只做"读字节 + 写 guest 内存"。</p>
 *
 * <p>⚠ <b>引导读的是盘里的程序</b>（{@code /boot/system.bin}），不是按盘的档位去资源表里查镜像 ——
 * "盘里装的是什么"由盘自己决定（用户 2026-09-18 定案；旧文档里"第 N 块硬盘 → 第 N 号系统"
 * 的映射早已删除，别按它理解）。</p>
 *
 * <p>⚠ 候选盘枚举**只有一份**（{@link #enumerateDisks}）：BIOS 链路（本类）与 MCU flash 分支
 * （{@code CryptandOcArchitecture.resolveMcuFlashImage}）共用它。</p>
 */
package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.neoforge.soc.content.SocPartItem;
import com.hdf.cryptand.neoforge.soc.content.SocPartKind;
import com.hdf.cryptand.soc.oc.BootLoaderService;

import net.minecraft.world.item.ItemStack;


import java.util.ArrayList;
import java.util.List;

public final class OcBootLoader implements BootLoaderService {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/opencomputers");

    /**
     * guest ROM 的**系统区**基址 = {@code 0x0001_0000}（{@code cryptand_os.ld} 的 ROM 布局）。
     *
     * <p>⚠ 2026-09-27 起它**不再由 BIOS 决定**：地址改由 guest 在 {@link #readFile(String, long)}
     * 里给出（"系统镜像要装在哪个地址"本来就是引导协议的契约，对照 Linux 的
     * {@code LOAD_PHYSICAL_ADDR}），本常量现在只用来**说明 ROM 布局**，并与
     * {@link BootPlan#SYSTEM_BASE} 同值。系统镜像（Cryptand OS / UI OS）都按
     * {@code cryptand_os.ld} 的 {@code ROM 0x0001_0000} 布局链接 ⇒ "载入地址 = 链接地址 = 入口"。</p>
     *
     * <p>⚠ <b>为什么不是 0x0</b>（2026-09-17 真机踩坑，务必别改回去）：{@code 0x0} 正是
     * Boot（EEPROM）**自己正在执行**的地方。系统镜像刷到 0x0，等于在 Boot 跑到一半时把
     * 它的指令逐条换掉 —— Boot 的 PC 继续往下走，执行的却是系统镜像的代码
     * （实测 guest PC 恒 {@code 0x6fc}，那正是 OS 的 {@code xTaskIncrementTick}）：
     * UART 一行不出、未映射设备访问每秒上千次、100 MHz 标称被拖到 0.03 MHz。</p>
     *
     * <p>踩过的坑（另一条）：一开始载到 RAM {@code 0x20010000}（RAM 镜像布局），系统是
     * 位置相关的，跳过去立刻跑飞（而且 UI OS 有 405KB，128KB RAM 也装不下）。</p>
     */
    public static final long LOAD_BASE = com.hdf.cryptand.soc.os.BootPlan.SYSTEM_BASE;

    /** guest 内存写入器（native 内核走它的写内存接口，Java 内核走 MemoryMap） */
    public interface MemoryWriter {
        void write(long addr, byte[] data);
    }

    private final MemoryWriter writer;

    /**
     * 机箱内的引导候选盘，**按 boot order 排好序**（软盘优先 → 槽位升序），构造时枚举一次。
     *
     * <p>顺序对齐官方 OC 的做法（Lua BIOS 也是按组件顺序遍历 filesystem 找引导文件），
     * 也是真实 BIOS 的 boot order：软盘优先于硬盘。</p>
     *
     * <p>⚠ 候选枚举**只有一份**：{@link #enumerateDisks} 是"机箱物品 → {@link BootPlan.Disk}"的
     * 唯一实现，本类（BIOS 链路）与 MCU flash 分支
     * （{@code CryptandOcArchitecture.resolveMcuFlashImage}）都调它 ——
     * 以前两处各写一遍"哪些算盘、按什么顺序"，改一处另一处会悄悄留在旧规则上（2026-09-27 收敛）。</p>
     */
    private final List<com.hdf.cryptand.soc.os.BootPlan.Disk> candidates;

    /**
     * MC 概念 → 纯数据：把物品翻成"这块盘的文件系统"。
     *
     * <p>为什么要传进来：引导决策本身是纯逻辑（见 common 的 {@code BootPlan}/{@code Programs}），
     * 而"盘在宿主上怎么挂载"只有 MC 侧知道（存档键 + 盘地址）。分开之后，
     * "从哪块盘引导"就能在沙盒里构造盘列表直接测。</p>
     */
    private final java.util.function.Function<ItemStack, com.hdf.cryptand.soc.fs.CryptandFileSystem> fsOf;

    /** 与 {@link #candidates} 平行：候选盘物品本身（读引导程序要用） */
    private final List<ItemStack> diskStacks = new ArrayList<>();

    /**
     * 承载引导记录的 EEPROM 容量（KB）—— 引导记录的字节上限由它决定（见 {@code BootPlan.limitBytes}）。
     *
     * <p>扫描机箱里的部件：插了 {@code SocPartKind.EEPROM} 就取它；
     * 没插则按最大档放行（OC 原版机箱 / 老存档可能没有我们的 EEPROM 部件，
     * 而它们此前能开机；引导区 64KB 这条物理边界仍然在拦）。</p>
     */
    private final int eepromKb;

    /**
     * BIOS 选定并已交过控制权的那块盘在候选表里的下标（{@link #readFile} 要用）。
     *
     * <p>为什么需要它：读盘服务里**没有"哪块盘"这个参数** —— 读的就是 BIOS 当前引导的那块盘，
     * 与 INT 13h 同一个道理（INT 19h 已经把 DL 设好，之后的 13h 默认读那块盘）。
     * 旧实现让固件自己带盘号（{@code BOOT_METHOD_LOAD(index)} 逐盘扫），那是 BIOS 落地之前的分工。
     * <b>-1</b> = 没经历过 BIOS 引导 ⇒ {@link #readFile} 明确报错，不猜一块盘来凑。</p>
     *
     * <p>（顺手清理：这里原来放的是 2026-09-18 的"重复兑现诊断"——那个 bug 已定性并修复
     * （根因是固件心跳写进了组件桥 ABI 偏移 0），诊断代码按"清理临时诊断"项移除。）</p>
     */
    private volatile int biosDiskIndex = -1;

    public OcBootLoader(List<ItemStack> components, MemoryWriter writer,
                        java.util.function.Function<ItemStack, com.hdf.cryptand.soc.fs.CryptandFileSystem> fsOf) {
        this.writer = writer;
        this.fsOf = fsOf;
        int eeprom = 0;
        for (final ItemStack stack : components) {
            if (stack != null && !stack.isEmpty() && stack.getItem() instanceof SocPartItem p
                    && p.kind() == SocPartKind.EEPROM) {
                eeprom = Math.max(eeprom, p.spec());
            }
        }
        this.eepromKb = eeprom > 0 ? eeprom : com.hdf.cryptand.soc.os.BootPlan.defaultEepromKb();
        LOG.info("[OpenComputers] Cryptand BIOS：{} ⇒ 引导记录上限 {} 字节",
                eeprom > 0 ? ("EEPROM " + eeprom + " KB") : ("机箱里没有 EEPROM 部件，按最大档 "
                        + this.eepromKb + " KB"),
                com.hdf.cryptand.soc.os.BootPlan.limitBytes(this.eepromKb));
        // 候选盘枚举：只有这一份实现（见 enumerateDisks）。挂载失败的盘在这里记一行并跳过 ——
        // "盘是坏的"与"盘里没系统"在 BIOS 眼里都是"不可引导"，但日志要能分清是哪一种。
        this.candidates = enumerateDisks(components, fsOf, msg -> LOG.info("[OpenComputers] {}", msg));
        for (final com.hdf.cryptand.soc.os.BootPlan.Disk d : this.candidates) {
            diskStacks.add(components.get(d.slot()));
        }
    }

    /**
     * ===== 候选盘枚举：**全项目唯一一份**（2026-09-27）=====
     *
     * <p>"机箱物品 → 纯数据 {@link BootPlan.Disk}"的翻译只有这一处：物品、槽位、显示名只有平台知道，
     * 而"哪些算盘、按什么顺序"必须唯一 —— 于是 BIOS 链路（本类）与 MCU flash 分支
     * （{@code CryptandOcArchitecture.resolveMcuFlashImage}）都调它，枚举不会再出现两份
     * （以前两处各写一遍"遍历机箱、软盘优先、槽位升序"）。</p>
     *
     * <p>顺序 = boot order：**先软盘（同类内槽位升序），再硬盘（同类内槽位升序）**。
     * 选盘规则本身仍只有 {@link BootPlan} 一份：本方法只做枚举与排序，**不判断"能不能引导"**
     * （C/RV32 看 {@code /boot/loader.bin}、Lua 看 {@code /init.lua}、MCU 看 {@code /boot/system.bin}）。</p>
     *
     * <p>文件系统**惰性挂载**：没格式化/没分区的盘连挂载都会抛 ⇒ 记一行日志并跳过它，
     * 而不是让整个引导流程崩掉。挂载失败的盘不进候选表 —— 它在两条链路里都确实不可引导。</p>
     *
     * @param log 每块候选/每次挂载失败的诊断输出（可为 null）
     */
    public static List<com.hdf.cryptand.soc.os.BootPlan.Disk> enumerateDisks(
            List<ItemStack> components,
            java.util.function.Function<ItemStack, com.hdf.cryptand.soc.fs.CryptandFileSystem> fsOf,
            java.util.function.Consumer<String> log) {
        final java.util.function.Consumer<String> out = log == null ? s -> {
        } : log;
        final List<com.hdf.cryptand.soc.os.BootPlan.Disk> disks = new ArrayList<>();
        // 两轮扫描：先软盘（高优先级），再硬盘 —— 这就是 boot order
        for (final boolean floppyPass : new boolean[]{true, false}) {
            for (int slot = 0; slot < components.size(); slot++) {
                final ItemStack stack = components.get(slot);
                if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof SocPartItem p)) {
                    continue;
                }
                final boolean isFloppy = p.kind() == SocPartKind.FLOPPY;
                final boolean isDisk = p.kind() == SocPartKind.FLASH;
                if (!((floppyPass && isFloppy) || (!floppyPass && isDisk))) {
                    continue;
                }
                final com.hdf.cryptand.soc.fs.CryptandFileSystem fs;
                try {
                    fs = fsOf.apply(stack);
                } catch (RuntimeException e) {
                    out.accept("引导候选：槽 " + slot + " 的盘「" + stack.getHoverName().getString()
                            + "」挂载失败（" + e + "）⇒ 视为不可引导");
                    continue;
                }
                out.accept("引导候选 #" + disks.size() + " = " + (isFloppy ? "软盘" : "硬盘")
                        + "（槽 " + slot + "，" + stack.getHoverName().getString() + "）");
                disks.add(new com.hdf.cryptand.soc.os.BootPlan.Disk(slot, isFloppy,
                        Math.max(1, Math.min(3, p.tier())), stack.getHoverName().getString(), fs));
            }
        }
        return disks;
    }

    // ==================== BootLoaderService：BIOS 的读盘服务（INT 13h）====================

    /**
     * 从 **BIOS 选定的那块盘**上按路径读一个文件，写进 guest 内存的 {@code loadAddr}。
     *
     * <p>这就是 <b>INT 13h 的等价物</b>：BIOS 已经把这块盘的引导记录（{@code /boot/loader.bin}）
     * 写进 0x0 并启动虚拟机了，bootloader 现在**请求** BIOS 把系统文件从同一块盘读进来 ——
     * <b>读哪个文件由 guest 说</b>（{@code path}），<b>放到哪个地址也由 guest 说</b>
     * （{@code loadAddr}，等价于 INT 13h 的 ES:BX）。宿主只做"打开盘、读字节、放进内存"。</p>
     *
     * <p>⚠ 为什么"读盘"留在宿主而不搬进 guest 固件：它不是策略，是<b>硬件能力</b>。
     * 我们的盘是 OC 的 filesystem 组件（open/read/seek，要经 {@code machine.invoke}），
     * 芯片侧没有这条总线 —— 与真实机器上"INT 13h 由 BIOS 提供、引导程序只管请求"完全同形。</p>
     *
     * <p>⚠ 唯一的边界检查是 {@code loadAddr} 不能落在**引导区** {@code 0x0..64KB}
     * （{@link BootPlan#LOADER_ROM_BYTES}）：那是引导记录自己**正在执行**的 ROM 区，写上去等于
     * 在它跑的时候换掉它的指令（2026-09-17 真机踩过：guest PC 恒 {@code 0x6fc}、UART 一行不出）。
     * 这是物理边界（{@code cryptand_boot.ld} 的 ROM 分段），不是兜底补丁。</p>
     */
    @Override
    public long[] readFile(String path, long loadAddr) {
        final int index = biosDiskIndex;
        if (index < 0 || index >= diskStacks.size()) {
            LOG.warn("[OpenComputers] Cryptand BIOS 读盘服务：没有引导上下文（biosDiskIndex={}）——"
                    + " 只有被 BIOS 选盘载入的程序才应当请求读盘", index);
            return null;
        }
        // ⚠ 拒绝写进引导区：见方法注释（真机踩过的"跑飞"现场）
        if (loadAddr < com.hdf.cryptand.soc.os.BootPlan.LOADER_ROM_BYTES) {
            LOG.warn("[OpenComputers] Cryptand BIOS 读盘服务：拒绝把 {} 载入 0x{} —— 它落在引导区 "
                            + "0x0..0x{}（引导记录此刻正在那里执行），换别的高地址",
                    path, Long.toHexString(loadAddr),
                    Long.toHexString(com.hdf.cryptand.soc.os.BootPlan.LOADER_ROM_BYTES));
            return null;
        }
        // ★ 2026-09-18 用户定案："空软盘 + 程序加载器" ⇒ **程序装在盘里**，引导读盘的内容，
        //   而不是按盘的档位去资源表里查（旧做法把盘当成了钥匙，换盘内容对引导毫无影响）。
        final ItemStack stack = diskStacks.get(index);
        final byte[] data;
        try {
            final com.hdf.cryptand.soc.fs.CryptandFileSystem fs = fsOf.apply(stack);
            data = com.hdf.cryptand.soc.os.Programs.installedAt(fs, path);
        } catch (RuntimeException e) {
            LOG.warn("[OpenComputers] Cryptand BIOS 读盘服务：引导盘 #{} 挂载失败（{}）",
                    index, e.toString());
            return null;
        }
        if (data.length == 0) {
            LOG.warn("[OpenComputers] Cryptand BIOS 读盘服务：引导盘 #{}（{}）上没有 {} —— 用程序加载器装一个",
                    index, stack.getHoverName().getString(), path);
            return null;
        }
        // 装到 guest 指定的地址（不改地址：那是 guest 的引导协议契约）
        writer.write(loadAddr, data);
        LOG.info("[OpenComputers] Cryptand BIOS 读盘服务（INT 13h）：引导盘 #{} → {}（{} 字节）读入 guest 0x{}",
                index, path, data.length, Long.toHexString(loadAddr));
        return new long[]{data.length, loadAddr};
    }

    // ==================== BIOS（第一级：宿主侧决定"从哪块盘、交哪一层"）====================

    /**
     * 走一遍 Cryptand BIOS：枚举候选盘 → 按 boot order 选盘 → 把**第一有效文件**写进 guest 0x0。
     *
     * <p>这是"上电取指那一段"（现实里 INT 19h 附近的活）：guest 还没跑起来，BIOS 决定
     * **从哪块盘读出什么、写进 0x0、然后启动虚拟机**。判据只有 {@link BootPlan#entryOf}
     * 一份（有 {@code /boot/loader.bin} 就用它；没有才退 {@code /boot/system.bin}）。</p>
     *
     * <p>与 {@link #readFile(String, long)} 的分工就是现实 BIOS 的两件事：
     * <b>这里 = INT 19h（选盘 + 交控制权）</b>，<b>{@link #readFile} = INT 13h（读盘服务）</b>。
     * 后者是 guest 跑起来之后**请求**才发生的，不是 BIOS 主动替它做。</p>
     *
     * <p>两级用的是同一份盘列表与同一套选盘规则（{@link BootPlan}），所以"引导顺序"
     * 全项目只有一处定义。</p>
     */
    public com.hdf.cryptand.soc.bios.CryptandBios.Result runBios(
            com.hdf.cryptand.soc.bios.BiosConfig config, java.util.function.Consumer<String> log) {
        // config == null ⇒ 走盘上的 CMOS（/boot/cmos.cfg）；盘上没有就用默认配置
        final com.hdf.cryptand.soc.bios.BiosConfig cfg = config == null ? cmosFromDisks(log) : config;
        // 显式传 DeviceSource（而不是 this::bootDisks）：引导记录上限要按这台机器的 EEPROM 容量算
        final com.hdf.cryptand.soc.bios.CryptandBios.Result result =
                com.hdf.cryptand.soc.bios.CryptandBios.run(
                new com.hdf.cryptand.soc.bios.CryptandBios.DeviceSource() {
                    @Override
                    public java.util.List<com.hdf.cryptand.soc.os.BootPlan.Disk> disks() {
                        return bootDisks();
                    }

                    @Override
                    public int eepromKb() {
                        return eepromKb;
                    }
                }, new Rv32Target(writer), cfg, log);
        // 记住"BIOS 从哪块盘起的"（= INT 19h 把 DL 交给程序）：之后的读盘服务读的就是这一块
        if (result.started() && result.decision() != null) {
            final int idx = indexOfCandidate(result.decision().slot());
            this.biosDiskIndex = idx;
            LOG.info("[OpenComputers] Cryptand BIOS：引导盘 = 候选 #{}（槽 {}）—— 读盘服务从它取文件（INT 13h）",
                    idx, result.decision().slot());
        }
        return result;
    }

    /**
     * 从候选盘里找 CMOS（{@code /boot/cmos.cfg}，见 {@code BiosCmos}）—— 一份都没有就用默认值。
     *
     * <p>与 EFI 的 {@code BOOTX64.CSV}、GRUB 的 {@code grub.cfg} 同思路：**启动介质自带引导配置**。
     * 按候选顺序（软盘优先 → 槽位升序，与 boot order 一致）取第一块带配置的盘；
     * 配置缺失不是错误（现实里 CMOS 没电/新机器也是这样，用默认值照开）。</p>
     */
    private com.hdf.cryptand.soc.bios.BiosConfig cmosFromDisks(java.util.function.Consumer<String> log) {
        final java.util.function.Consumer<String> out = log == null ? s -> {
        } : log;
        for (final com.hdf.cryptand.soc.os.BootPlan.Disk d : bootDisks()) {
            final com.hdf.cryptand.soc.bios.BiosConfig c =
                    com.hdf.cryptand.soc.bios.BiosCmos.read(d.fs());
            if (c != null) {
                out.accept("Cryptand BIOS: CMOS 取自槽 " + d.slot() + "（"
                        + com.hdf.cryptand.soc.bios.BiosCmos.CMOS_PATH + "，order=" + c.order()
                        + (c.hasForcedSlot() ? ", forcedSlot=" + c.forcedSlot() : "") + "）");
                return c;
            }
        }
        out.accept("Cryptand BIOS: 盘上没有 CMOS（" + com.hdf.cryptand.soc.bios.BiosCmos.CMOS_PATH
                + "）⇒ 用默认配置");
        return com.hdf.cryptand.soc.bios.BiosConfig.defaults();
    }

    /**
     * 声明"引导盘就是这一槽" —— <b>INT 19h 把 DL 交给程序的等价物</b>（**取值型**入口：
     * 它只回答"引导盘是哪一块"，**不装载任何东西**）。
     *
     * <p>现实对照：BIOS 的自举过程选好引导盘后，把盘号放进 <b>DL</b> 再跳到引导记录 ——
     * 引导记录因此知道该读哪块盘，而 BIOS 不替它读。这里就是那个 DL。</p>
     *
     * <p>用于 **MCU 档**（用户 2026-09-27 定案："虚拟机运行基本都是从 0x0 开始的，
     * 不管 mcu 还是 soc，如果 0x0 部分是 bootloader 就是 boot 启动" ⇒ 读盘服务**全档位统一挂上**）。
     *
     * <p>MCU 没有 BIOS 这一层（平台自己把第一块有效 flash 盘的内容放到 0x0），所以
     * {@link #biosDiskIndex} 不会由 {@link #runBios} 写进来 —— 平台知道它挑了哪块盘，就在这里告诉
     * 引导器。写的是**同一个字段**：全项目只有一份"引导盘是哪一块"的来源，两条链路（BIOS 分层 /
     * MCU 直载）不会各自记一份。</p>
     *
     * <p>⚠ 与 {@link #runBios} 的区别只有"谁做的决定"：BIOS 是 BIOS 自己选盘（INT 19h），
     * MCU 是平台定的 flash 盘 —— 但两者交给"读盘服务"的**都是同一块盘**，字段也只有一份。</p>
     *
     * @param slot 机箱槽位（MCU flash 盘的槽）
     * @return 该槽在候选表里的下标；{@code -1} = 该槽不在候选里（盘挂载失败被跳过）
     */
    public int declareBootDisk(int slot) {
        this.biosDiskIndex = indexOfCandidate(slot);
        return this.biosDiskIndex;
    }

    /**
     * BIOS 眼里的候选盘（构造时按 {@link #enumerateDisks} 枚举好的那一份，已按 boot order 排序）。
     *
     * <p>⚠ **public 是刻意的**：MCU flash 分支与 BIOS 链路必须看同一份枚举
     * （唯一实现 = {@link #enumerateDisks}）。以前 MCU 分支自己遍历了一遍机箱 —— 枚举两份、
     * 规则一份，是最典型的"改一处、另一处留旧规则"现场。</p>
     */
    public java.util.List<com.hdf.cryptand.soc.os.BootPlan.Disk> bootDisks() {
        return candidates;
    }

    /** 候选表里"槽位 → 下标"（{@code -1} = 该槽不在候选里，例如盘挂载失败被跳过） */
    private int indexOfCandidate(int slot) {
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).slot() == slot) {
                return i;
            }
        }
        return -1;
    }

    /**
     * ===== 引导介质 ⇄ 宿主 ABI 的**同源性护栏**（2026-09-27 真机事故的教训）=====
     *
     * <p><b>为什么必须有它</b>：放进 0x0 的**不是**内置镜像，而是"引导介质上的第一有效文件"
     * （{@link BootPlan#entryOf} —— 通常是那块盘的 {@code /boot/loader.bin}）。也就是说
     * <b>引导介质自带一份 bootloader，它是与宿主分开编译、分开烧录的另一份字节</b>。
     * 宿主 ABI 一变（2026-09-27 把 {@code loadSystem()} 重塑成 {@code readFile(path, addr)}），
     * 介质上那份旧 bootloader 仍按旧形状调用（method 0 + 无缓冲区）⇒ 宿主按 ABI 用错**拒答**
     * ⇒ 固件打出一句**与真相无关**的 {@code "host bridge not wired"} 并停机，
     * 排查方向被整条带偏（真机现场：宿主心跳 calls=0 / 回读 -1，看着像"桥没接"，
     * 实际是"0x0 上跑的是旧编译产物"）。</p>
     *
     * <p>⚠ <b>只报警、不改行为</b>（用户铁律：不做旧内容兼容、不加兜底/重试、只留一条路）：
     * 0x0 该放什么，判据仍然只有"介质上的第一有效文件"这一条；介质旧了就是旧了 ——
     * 但日志必须让人一眼看出**该去修哪块盘**，而不是再查一遍"桥到底接没接"。</p>
     *
     * <p>正常情形下两者**本来就是同一份字节**：{@code Programs.install()} 装盘时写进
     * {@code /boot/loader.bin} 的就是当前编译产物 {@code cryptand-boot.bin}（BIOS 的 0x0 覆盖
     * 因此才是"幂等"的）。所以这条日志平时**不出现** —— 一出现就说明介质需要重装。</p>
     *
     * @param program 会被放进 0x0 的那份字节（介质上的第一有效文件）
     * @param slot    介质所在机箱槽位（日志要指到具体哪一块盘）
     * @param what    它的来源（如 {@code /boot/loader.bin}），日志用
     */
    public static void warnStaleBootMedium(byte[] program, int slot, String what) {
        final byte[] built = com.hdf.cryptand.soc.os.Programs.readById(
                com.hdf.cryptand.soc.os.Programs.BOOTLOADER_ID);
        if (built.length == 0 || java.util.Arrays.equals(program, built)) {
            return;
        }
        LOG.warn("[OpenComputers] ⚠ 引导介质与宿主 ABI **不同源**：槽 {} 的 {} 是 {} 字节，"
                        + "而宿主（EEPROM）里的 {}.bin 是 {} 字节 —— 介质上那份是**另一次编译/烧录的产物**。"
                        + "0x0 执行的会是介质那一份，它按**旧 ABI** 调引导服务"
                        + "（宿主现为 boot.readFile(path, loadAddr)），于是以 \"host bridge not wired\" 停机"
                        + "（那句话与真相无关）。修法：把引导记录重装到这块盘"
                        + "（程序加载器，或 /cryptand soc disk install）后重新开机。",
                slot, what, program.length,
                com.hdf.cryptand.soc.os.Programs.BOOTLOADER_ID, built.length);
    }

    // ==================== Cryptand BIOS 的 RV32 启动实现（2026-09-24） ====================
    //
    // BIOS 已按用户定案搬到宿主侧（common 的 CryptandBios）：它枚举设备、按 boot order 选盘，
    // 再把选中的程序交给某个架构。MC 侧只剩这一件只有平台才知道的事：把字节写进 guest 内存。
    // 因此这里实现 CryptandBios.BootTarget —— 将来加 Lua / RV64 芯片时各自实现一个即可，BIOS 不动。
    public static final class Rv32Target implements com.hdf.cryptand.soc.bios.CryptandBios.BootTarget {

        private final MemoryWriter writer;

        public Rv32Target(MemoryWriter writer) {
            this.writer = writer;
        }

        @Override
        public String archName() {
            return "rv32imac";
        }

        @Override
        public boolean boot(com.hdf.cryptand.soc.os.BootPlan.Decision decision) {
            if (decision == null || decision.program().length == 0) {
                return false;
            }
            // 目标地址由决策决定：bootloader → ROM 引导固件区 0x0（与内置固件同一份字节，
            // 属幂等覆盖）；系统本体 → ROM 系统区 0x1_0000。
            // ⚠ 系统**绝不能**刷到 0x0：那是引导固件自己正在执行的地方（见 LOAD_BASE 的说明）。
            final long addr = decision.loadAddress();
            // ★ 0x0 上执行的是**介质那一份**（不是内置镜像）：先确认它与宿主 ABI 同源。
            //   只报警、不改行为 —— 见 warnStaleBootMedium 的说明（介质旧了就重装，不做兼容）。
            warnStaleBootMedium(decision.program(), decision.slot(), "第一有效文件");
            writer.write(addr, decision.program());
            LOG.info("[OpenComputers] Cryptand BIOS: slot {} bootloader ({} bytes) -> guest ROM 0x{}",
                    decision.slot(), decision.program().length, Long.toHexString(addr));
            return true;
        }
    }
}
