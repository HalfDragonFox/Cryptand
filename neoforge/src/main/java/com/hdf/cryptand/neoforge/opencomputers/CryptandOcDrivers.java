/**
 * ===== Cryptand 部件的 OC 驱动（2026-09-16，P1）=====
 *
 * <p>让 Cryptand 的部件**被 OC 机箱接受**：OC 判定"某物品能插哪个槽"完全走
 * {@link li.cil.oc.api.driver.DriverItem}（{@code worksWith / slot / tier / createEnvironment}），
 * 所以只要为我们的部件注册驱动，就能插进 OC 的机箱 / 服务器 / 机架。</p>
 *
 * <h3>槽位映射（对照 OC 的 Slot 常量）</h3>
 * <table border="1">
 *   <tr><th>Cryptand 部件</th><th>OC 槽位</th><th>能力接口</th></tr>
 *   <tr><td>处理器 chip_*（CHIP）</td><td>{@code Slot.CPU}</td>
 *       <td>{@link li.cil.oc.api.driver.item.Processor}（含 {@code architecture} ⇒ **指向我们的 C/RV32 架构**）</td></tr>
 *   <tr><td>内存条 ram_*（RAM）</td><td>{@code Slot.Memory}</td>
 *       <td>{@link li.cil.oc.api.driver.item.Memory}（容量按 KB→字节）</td></tr>
 *   <tr><td>硬盘 disk_*（FLASH）</td><td>{@code Slot.HDD}</td><td>—（文件系统能力留 P3）</td></tr>
 *   <tr><td>扩展卡 card_*（CARD_*）</td><td>{@code Slot.Card}</td><td>—</td></tr>
 *   <tr><td>底板 board_*（BOARD）</td><td>{@code Slot.ComponentBus}</td><td>—</td></tr>
 * </table>
 *
 * <h3>档位（tier）换算 —— ⚠ 最容易踩的坑</h3>
 * <p>OC 的档位是 <b>0 基</b>（{@code Tier.One = 0 … Tier.Four = 3}），Cryptand 部件的档位是 1 基（1..5）。
 * 对外暴露的 tier 必须走 {@link #ocTier}（减 1 并夹到 OC 槽位上限），否则部件会整体"高看一档"，
 * 表现为"内存条/显卡插不进 OC 机箱"（实测：slot 4 拒 {@code ram_ddr4}、slot 1 拒 {@code card_gpu_pro}）。</p>
 *
 * <p><b>关于 {@code createEnvironment}</b>：{@code DriverItem} 要求实现它（返回该物品在机器里
 * 提供的组件环境）。我们这些部件都**不提供环境**（CPU/内存在机器内部消化；硬盘/卡的文件系统
 * 能力留 P3），统一返回 {@code null}，OC 会当作"只占槽位、不注册组件"。</p>
 *
 * <p>⚠ <b>注册时机</b>：OC 的 {@code server/driver/Registry.scala} 会在 init 结束后上锁，
 * 之后再 {@code Driver.add} 会抛 {@code IllegalStateException("Please register all drivers in the init phase.")}。
 * 因此本类由 {@link OpenComputersEntry} 在子包 init 中调用，并对异常做兜底日志（不炸游戏）。</p>
 */
package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.neoforge.soc.content.SocPartItem;
import com.hdf.cryptand.neoforge.soc.content.SocPartKind;
import li.cil.oc.api.Driver;
import li.cil.oc.api.driver.DriverItem;
import li.cil.oc.api.driver.item.Slot;
import li.cil.oc.api.machine.Architecture;
import li.cil.oc.api.network.EnvironmentHost;
import li.cil.oc.api.network.ManagedEnvironment;
import net.minecraft.world.item.ItemStack;

public final class CryptandOcDrivers {

    /** ⚠ 用 log4j（而不是 System.out）：只有 log4j 会进 run/logs/latest.log，排查时才能看到 */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/opencomputers");

    private CryptandOcDrivers() {
    }

    // ==================== 公共取值 ====================

    /** Cryptand 部件（非部件返回 null） */
    private static SocPartItem part(ItemStack stack) {
        return stack != null && stack.getItem() instanceof SocPartItem p ? p : null;
    }

    /**
     * 组装台产出的**成品芯片**的规格（{@code SocSpec} 存在物品 CUSTOM_DATA 里）；不是成品芯片返回 null。
     *
     * <p>2026-09-28 用户定案：「SOC、MCU、CPU 都是放 oc 的 CPU 槽位」—— 成品芯片和逐档处理器物品
     * 一样要能插进 OC 机箱的 CPU 槽，所以处理器驱动要同时认这两种物品。</p>
     */
    private static com.hdf.cryptand.neoforge.soc.content.SocSpec assembledSpec(ItemStack stack) {
        return com.hdf.cryptand.neoforge.soc.content.ProcessorFacts.assembledSpec(stack);
    }

    /** 这是"能插 CPU 槽的处理器"吗：处理器物品（CHIP）**或**组装台产出的成品芯片。 */
    private static boolean isProcessor(ItemStack stack) {
        return com.hdf.cryptand.neoforge.soc.content.ProcessorFacts.isProcessor(stack);
    }

    /**
     * 处理器的 Cryptand 档位（1 基）——两种物品统一取值。
     *
     * <p>2026-09-29 合并口径：以前成品芯片按「主频落在哪个区间」另编 1..5 档，与逐档物品的族映射
     * （{@code SocCpuTiers.tierOf}：MCU=1 / SOC=3 / CPU=4）不一致 ⇒ 同一个 CPU 槽上两种物品的
     * {@code tier()/supportedComponents()} 语义不同。现在两者都走 {@code ProcessorFacts.tier}。</p>
     */
    private static int processorTier(ItemStack stack) {
        return com.hdf.cryptand.neoforge.soc.content.ProcessorFacts.tier(stack);
    }

    /** 处理器对外暴露给 OC 的档位（0 基 + 夹上限，口径同 {@link #ocTier}）。 */
    private static int processorOcTier(ItemStack stack) {
        return Math.max(OC_TIER_ONE, Math.min(OC_TIER_FOUR, processorTier(stack) - 1));
    }

    /** Cryptand 部件档位（1 基，1..5；部件自身的规格档位，我们自己的逻辑用它） */
    private static int cryptandTier(ItemStack stack) {
        final SocPartItem p = part(stack);
        return p == null ? 1 : Math.max(1, Math.min(5, p.tier()));
    }

    // ==================== 档位换算（★ 无人化测试抓到的坑，2026-09-17）====================

    /**
     * OC 的档位是 <b>0 基</b>：见 {@code li.cil.oc.common.Tier} ——
     * {@code One = 0 / Two = 1 / Three = 2 / Four = 3 / Five = 4 / Any = Int.MaxValue}。
     *
     * <p>最初我们直接把 Cryptand 的 1 基档位（1..5）交给了 OC，结果**整体高了一档**：
     * RAM 4/5 档与显卡 3 档统统插不进 OC 机箱（实测 slot 4 拒 {@code ram_ddr4}、
     * slot 1 拒 {@code card_gpu_pro}）。所以对外必须减 1 对齐。</p>
     */
    private static final int OC_TIER_ONE = 0;

    /** OC 机箱 / 服务器槽位的档位上限（{@code InventorySlots.computer(...)} 里最高只到 Tier.Four = 3） */
    private static final int OC_TIER_FOUR = 3;

    /**
     * 暴露给 OC 的档位：<b>先减 1 对齐 0 基</b>，再夹到 OC 槽位上限 {@link #OC_TIER_FOUR}。
     *
     * <p>夹上限的原因：OC 全部机箱（含创世 {@code casecreative}）的内存/硬盘/CPU 槽位最高就是
     * Tier.Four，若把 Cryptand 第 5 档原样映射成 OC 的 Tier.Five，部件会"哪儿都插不进"；
     * 因此第 5 档与第 4 档在 OC 侧同为顶档（Cryptand 内部的档位差不受影响）。</p>
     */
    private static int ocTier(ItemStack stack) {
        return Math.max(OC_TIER_ONE, Math.min(OC_TIER_FOUR, cryptandTier(stack) - 1));
    }

    private static int specOf(ItemStack stack) {
        final SocPartItem p = part(stack);
        return p == null ? 0 : p.spec();
    }

    /**
     * 这些部件都<b>不提供组件环境</b>（CPU/内存在机器内部消化，硬盘/卡的文件系统能力留 P3）——
     * 统一返回 {@code null}，OC 会把它们当作"只占槽位、不注册组件"。
     */
    private static ManagedEnvironment noEnvironment() {
        return null;
    }

    // ==================== 处理器（CPU / SOC）：★ 架构指向 C/RV32 ====================

    /**
     * 处理器驱动 —— 本类的 {@link #architecture} 就是"**架构为 C 而不是 Lua**"的开关：
     * OC 会反射构造我们返回的架构类（要求有 {@code (Machine)} 构造器）。
     */
    public static final class ProcessorDriver implements li.cil.oc.api.driver.item.Processor {

        @Override
        public boolean worksWith(ItemStack stack) {
            return isProcessor(stack);   // 逐档处理器物品 + 组装台成品芯片，两种都进 CPU 槽
        }

        @Override
        public String slot(ItemStack stack) {
            return Slot.CPU;
        }

        @Override
        public int tier(ItemStack stack) {
            return processorOcTier(stack);
        }

        /** 可挂多少组件（按档位递增，与 OC 原生 CPU 的口径一致） */
        @Override
        public int supportedComponents(ItemStack stack) {
            return 8 + processorTier(stack) * 8;   // 用 Cryptand 自己的档位口径（不受 OC 0 基换算影响）
        }

        /** ★ 我们的架构：RV32 + C 固件（不是 Lua） */
        @Override
        public Class<? extends Architecture> architecture(ItemStack stack) {
            return CryptandOcArchitecture.class;
        }

        @Override
        public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
            return noEnvironment();
        }
    }

    // ==================== 内存条：**不注册驱动**（2026-09-17） ====================
    //
    // 用户："注意下原版有内存条，不重复定义额外的"。
    // OC 原版内存条（ram1..ram6/ramcreative）自己有 DriverMemory ⇒ 机箱内存槽本来就认；
    // 我们的架构算内存时把"所有 Memory 驱动的 amount 求和"，所以插 OC 内存条一样有效。
    // 原先的 Cryptand MemoryDriver + cryptand:ram1..ram5 已删除。

    // ==================== 硬盘 ====================

    public static final class HddDriver implements DriverItem {

        @Override
        public boolean worksWith(ItemStack stack) {
            final SocPartItem p = part(stack);
            return p != null && p.kind() == SocPartKind.FLASH;
        }

        @Override
        public String slot(ItemStack stack) {
            return Slot.HDD;
        }

        @Override
        public int tier(ItemStack stack) {
            return ocTier(stack);
        }

        @Override
        public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
            // 盘走 OC（用户定案：「芯片内部模块走自管理，像硬盘软盘等还是走 OC」）——
            // 于是这里把 Cryptand 的文件系统包成 OC 的 filesystem 组件环境。
            // speed 照 OC 注释：软盘用默认 1，硬盘随档位（tier1 → 2、tier3 → 4）。
            return mountDisk(stack, host, 1 + Math.max(0, Math.min(3, ocTier(stack))));
        }
    }

    /**
     * 把一块 Cryptand 盘挂载成 OC 的 {@code filesystem} 组件环境（硬盘/软盘共用）。
     *
     * <p>三步：① 地址（首次使用时由**服务端**生成并写回物品，客户端不生成 ——
     * 否则同一个槽位在两边会指向不同目录）；② 经 {@link OcDiskMounts} 拿到该地址对应的
     * {@link com.hdf.cryptand.soc.fs.DiskFileSystem}（盘 = 宿主文件夹）；③ 用
     * {@code li.cil.oc.api.FileSystem.asManagedEnvironment} 包成组件环境，
     * 文件系统实现是 {@link OcFileSystemAdapter}（把 OC 的 {@code api.fs} 调用转给 Cryptand 侧）。</p>
     *
     * <p>⚠ 客户端返回 {@code noEnvironment()}：OC 的机器本来就在服务端跑，客户端挂载没有意义，
     * 而且地址只该在权威侧落定。</p>
     */
    private static ManagedEnvironment mountDisk(ItemStack stack, EnvironmentHost host, int speed) {
        final net.minecraft.world.level.Level level =
                host == null ? null : host.getEnvironmentLevel();
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
            return noEnvironment();
        }
        final com.hdf.cryptand.neoforge.soc.content.SocPartItem part =
                com.hdf.cryptand.neoforge.soc.content.SocPartItem.class.isInstance(stack.getItem())
                        ? (com.hdf.cryptand.neoforge.soc.content.SocPartItem) stack.getItem() : null;
        if (part == null) {
            return noEnvironment();
        }
        final String address = part.ensureAddress(stack, true);
        if (address.isEmpty()) {
            return noEnvironment();
        }
        // PE（Cryptand OS PE）要知道"这台机器上有哪些盘、每块盘在哪、多大" ——
        // 只有驱动层同时握有 level 与容量，所以在这里登记（见 OcPeBridge 的说明）。
        OcPeBridge.register(serverLevel, address, Math.max(1024L, (long) part.spec() * 1024L));
        // 容量 = 规格（KB）× 1024（照 cryptand-fs-design.md §3.5：机制同 OC、数值自定）
        final long capacityBytes = Math.max(1024L, (long) part.spec() * 1024L);
        // 文件系统类型由**分区表**决定（IMAGE+FAT → FatFileSystem；FOLDER → DiskFileSystem），
        // 所以这里只认接口 —— 上层（OC 组件 / 固件 ABI）对"盘上是 FAT 还是文件夹"零感知。
        // 非 final：系统软盘预装之后，锁定的盘要换一个"只读实例"（见 ③）
        com.hdf.cryptand.soc.fs.CryptandFileSystem fs;
        try {
            // 可写性 = **盘的锁标志**（OC 语义：锁即只读；见 SocPartItem.isLocked 的说明）。
            // ⚠ 不要在这里按 kind 硬编码：锁是物品组件，随盘走 —— 锁定的盘换到别的机器、别的存档
            //   插上仍然是只读；而显式解锁过的软盘应当能写。默认值（软盘带锁 / 硬盘不锁）在
            //   SocPartItem.isLocked 里统一给出，这里只读结论。
            final boolean locked = com.hdf.cryptand.neoforge.soc.content.SocPartItem.isLocked(stack);
            // ① 先以**可写**方式打开：系统软盘的首次预装必须在锁定之前做（锁定后 guest 侧只读）。
            fs = OcDiskMounts.of(serverLevel, address, capacityBytes, false);

            // ② 自带的三种系统软盘：**读取固定路径的资源包内的 bin**，把系统真装进盘里
            //    （用户 2026-09-18 定案）。这样"盘里装的是什么"由盘的内容决定，
            //    而不是引导时按档位去资源表里查 —— 后者会让"盘是空的"却照样能起系统。
            //    空软盘（floppy_b*）是 tier 0，不走这里，靠 /cryptand soc disk load 强制更换程序。
            // ② 系统软盘 = **安装介质**（用户 2026-09-26 定案）：
            //    盘上的 /boot/system.bin 是 **PE（装机环境）**，完整镜像放 /images/<id>.bin，
            //    开机先进 PE，由 PE 把镜像**拷贝安装**到目标硬盘（"完整镜像只需要 PE 运行时
            //    拷贝安装到具体硬盘即可"）。所以系统软盘可以反复用来装机，它自己不变。
            //    ⚠ 空软盘（tier 0）不走这里 —— "空软盘保证空"（见 SocContent 的说明）。
            if (part.kind() == com.hdf.cryptand.neoforge.soc.content.SocPartKind.FLOPPY
                    && part.tier() >= 1 && part.tier() <= 3) {
                final String imageId = switch (part.tier()) {
                    case 2 -> "cryptand-ui-os";
                    // tier 1 = 非 UI 的 Cryptand OS；tier 3 = 扩展位（当前同样装非 UI 系统）
                    default -> "cryptand-os";
                };
                final byte[] pe = com.hdf.cryptand.soc.os.Programs.readById(
                        com.hdf.cryptand.soc.os.Programs.PE_ID);
                if (pe.length == 0) {
                    LOG.warn("[OpenComputers] ⚠ 系统软盘 {} 无法预装：PE 固件缺失（cryptand-pe.bin）"
                            + "⇒ 先跑 excode/firmware/build-all.ps1", address);
                } else if (!java.util.Arrays.equals(com.hdf.cryptand.soc.os.Programs.installed(fs), pe)) {
                    // ⚠ 判据不是"没装过"，而是"**装的内容与当前固件不一致**"（2026-09-26 修）：
                    //   装机盘的意义就是跟着固件版本走 —— 升级 PE 之后，旧盘上那份必须被换掉，
                    //   否则"改了固件却还在跑老代码"，而屏幕上完全看不出来（实测踩过：banner
                    //   还是 0.1，PE 的所有新协议都没生效，排查了好几轮）。
                    //   放在**可写打开**阶段（锁定之前），所以只读盘也能这样自我更新。
                    LOG.info("[OpenComputers] 系统软盘 {} 的 PE 与当前固件不一致（盘上 {} B / 当前 {} B）"
                                    + "⇒ 重新装入 PE + 镜像 {}",
                            address, com.hdf.cryptand.soc.os.Programs.installed(fs).length,
                            pe.length, imageId);
                    // ⚠ 先确保盘上有**系统分区**（2026-09-26 修）：老做法直接往"盘根文件夹树"里
                    //   写文件 ⇒ 盘上根本没有分区表 ⇒ Installer.hasSystem（判据 = 有 SYSTEM 角色的
                    //   IMAGE+FAT 分区）恒为 false，PE 的 disks 于是把"装好系统的盘"报成 blank。
                    //   安装盘本身也该是一块正常的分区盘。
                    fs = ensureSystemPartition(serverLevel, address, capacityBytes, fs);
                    com.hdf.cryptand.soc.os.Programs.installInstallerMedia(fs, imageId);
                    LOG.info("[OpenComputers] 系统软盘 {} 首次使用：已装入 PE（{} 字节）+ 完整镜像 {}"
                            + " ⇒ 从这块盘开机会进入 PE，由 PE 把镜像装到目标硬盘",
                            address, pe.length, imageId);
                }
            }

            // ③ 需要只读（锁定的盘）⇒ 丢掉刚才那个可写实例，以只读重新挂载。
            //    ⚠ 必须换实例而不是"改标志"：只读是挂载时的属性，guest 侧看到的 fs 从这一刻起不可写。
            if (locked) {
                OcDiskMounts.unmount(address);
                fs = OcDiskMounts.of(serverLevel, address, capacityBytes, true);
                LOG.info("[OpenComputers] 盘 {} 处于锁定状态 ⇒ 以只读挂载（{}）", address,
                        stack.getHoverName().getString());
            }
        } catch (RuntimeException e) {
            // 未格式化 / 分区损坏：盘**明确不可用**（组件缺失），绝不回落成文件夹树 ——
            // 回落会让 guest 在错的介质上写数据，而用户看到的是"盘是空的"。
            LOG.warn("disk {} cannot be mounted: {}", address, e.toString());
            return noEnvironment();
        }
        // ★ 登记"这一个环境借用了这块盘"：OC 销毁环境时会经适配器归还
        //   （见 OcFileSystemAdapter.close / OcDiskMounts.release），挂载表只在**最后一个**
        //   借用者归还时才真正关闭实例。少了这一步就会出现"环境一销毁，这块盘永久变成死实例"
        //   ——2026-09-26 真机日志里的 filesystem already closed 就是它。
        OcDiskMounts.retain(address);
        final ManagedEnvironment inner = li.cil.oc.api.FileSystem.asManagedEnvironment(
                new OcFileSystemAdapter(fs), stack.getHoverName().getString(), host, "filesystem",
                Math.max(1, Math.min(6, speed)));
        // ★ 登记"这个 env 的节点地址 → 这块盘"，但**不能在这里读地址**（那一刻还没分配），
        //   也**不能**靠包一层 ManagedEnvironment 去挂钩 onConnect —— OC 内部保存的是 inner 自己，
        //   我们返回的包装对象根本不会被回调（实测：登记表恒为空 ⇒ forNode 永远找不到盘）。
        //   正解：登记一个**延迟求值**，等 dispatch 时（那时早已连网）再问 inner.node().address()。
        OcDiskMounts.registerLazy(fs, () -> {
            final var n = inner.node();
            return n == null ? "" : n.address();
        });
        // ★ 不再包一层 ManagedEnvironment：OC 内部持有的是 asManagedEnvironment 建的对象，
        //   我们返回的包装根本收不到 onConnect（实测：登记表恒为空）—— 上一版那层转发是死代码。
        //   节点地址映射一律走上面的 registerLazy（延迟求值：dispatch 时节点早已连网）。
        return inner;
    }

    /**
     * 确保一块**系统软盘**上有系统分区（IMAGE+FAT，label={@code SYS}），返回（可能重新挂载的）文件系统。
     *
     * <p>为什么要这一步（用户 2026-09-26 真机："有 id 的组件…"那一轮里发现 PE 的 disks 把
     * 装好系统的盘报成 blank）：老代码是直接往"盘根 = 文件夹树"里写 {@code /boot/system.bin}，
     * 盘上**没有分区表** ⇒ {@code Installer.hasSystem} 的判据（"有 SYSTEM 角色的 IMAGE+FAT 分区"）
     * 永远不成立。安装盘本身也应当是一块正常的分区盘。</p>
     *
     * <p>⚠ 格式化会换掉盘的结构 ⇒ 旧的 fs 实例作废，必须 unmount 后重新挂载再写。</p>
     */
    private static com.hdf.cryptand.soc.fs.CryptandFileSystem ensureSystemPartition(
            net.minecraft.server.level.ServerLevel level, String address, long capacityBytes,
            com.hdf.cryptand.soc.fs.CryptandFileSystem current) {
        try {
            final java.nio.file.Path dir = OcDiskMounts.directoryOf(level, address);
            if (java.nio.file.Files.isRegularFile(
                    dir.resolve(com.hdf.cryptand.soc.fs.DiskPartitionTable.META_FILE))) {
                return current;                     // 已经有分区表：保持原样
            }
            final String fmt = OcDiskFormat.format(level, address, capacityBytes, capacityBytes, null, "SYS");
            OcDiskMounts.unmount(address);
            LOG.info("[OpenComputers] 系统软盘 {} 还没有分区表 ⇒ 已分区并格式化（{}）", address, fmt);
            return OcDiskMounts.of(level, address, capacityBytes, false);
        } catch (Throwable t) {
            LOG.warn("[OpenComputers] 系统软盘 {} 分区失败（按盘根文件夹树继续）", address, t);
            return current;
        }
    }

    // ==================== 扩展卡 ====================

    public static final class CardDriver implements DriverItem {

        @Override
        public boolean worksWith(ItemStack stack) {
            final SocPartItem p = part(stack);
            return p != null && p.kind().name().startsWith("CARD_");
        }

        @Override
        public String slot(ItemStack stack) {
            return Slot.Card;
        }

        @Override
        public int tier(ItemStack stack) {
            return ocTier(stack);
        }

        /**
         * ★ 关键：把 Cryptand 显卡变成 **OC 的 gpu 组件**（2026-09-26 修）。
         *
         * <p>以前这里返回 {@code noEnvironment()} —— 于是"机箱里插了我们的显卡"在 OC 侧
         * <b>根本不是一个 gpu 组件</b>：宿主日志一直是
         * {@code ⚠ 没有可见的 gpu 组件（OC 显卡）⇒ 固件的画字调用会失败}，
         * Cryptand 架构的 {@code OcComponentBus} 找不到显卡去 bind/blit，屏幕永远空白
         * （真机症状 {@code flush row0=[Cryptand OS ] blit=FAIL}）。</p>
         *
         * <p>通道数取物品档位的 spec（{@code SocPartKind.CARD_GPU} 的规格就是"输出通道数"：
         * 1/2/4）—— 用户定案："1 个通道则支持 1 个屏幕（包括拼接的屏幕算一个），4 个则表示 4 个"。</p>
         */
        @Override
        public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
            final SocPartItem p = part(stack);
            if (p == null) {
                return noEnvironment();
            }
            final String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getPath();
            final int spec = Math.max(1, specOf(stack));
            return switch (p.kind()) {
                case CARD_GPU -> new CryptandGpuEnvironment(id, spec, SocPartKind.CARD_GPU.ocComponent(),
                        gpuBudgetSource(host));
                // 四张扩展卡都变成 OC 组件（用户 2026-09-26："组件 id 按原版 oc 显示"、
                // "调研原版 oc 看看组件哪些是有的我们也加上"）：GPIO⇒redstone、PWM⇒pwm、
                // ADC⇒adc、串口⇒serial，名字的单一来源是 SocPartKind.ocComponent()。
                case CARD_GPIO, CARD_PWM, CARD_ADC, CARD_UART ->
                        new CryptandCardEnvironment(p.kind(), id, spec, host);
                default -> noEnvironment();
            };
        }
    }

    /**
     * 这张卡所在机器的**显示预算**取值路径（架构侧唯一一份显存口径，见 {@code GpuMemory.Budget}）。
     *
     * <p>为什么要**延迟求值**（返回 Supplier 而不是当场取）：组件环境在机箱物品装载时就被建出来，
     * 那一刻 OC 还没有架构对象 —— 架构是 {@code Machine.start} 里按处理器反射构造的
     * （{@code Machine.scala:156} {@code clazz.getConstructor(Machine).newInstance(this)}），
     * 组装顺序上早于它的是"芯片插进机箱"。当场取只会拿到 null，于是内存闸门永远不会生效
     * （那正是"代码写了、实机没生效"的老毛病）。bind 时才求值，拿到的就是本机那一份。</p>
     *
     * <p>{@code null} = 本机不是 Cryptand 的 C/RV32 架构（如 OC 原版 Lua 架构）：没有"内存池 =
     * 内存条容量"这个口径可比，卡按 OC 显卡语义工作（{@code CryptandGpuEnvironment} 会明说一次）。</p>
     */
    private static java.util.function.Supplier<com.hdf.cryptand.soc.board.GpuMemory.Budget> gpuBudgetSource(
            EnvironmentHost host) {
        return () -> {
            if (host instanceof li.cil.oc.api.machine.MachineHost machineHost) {
                final li.cil.oc.api.machine.Machine machine = machineHost.machine();
                if (machine != null
                        && machine.architecture() instanceof CryptandOcArchitecture architecture) {
                    return architecture.gpuBudget();
                }
            }
            return null;
        };
    }

    // ==================== 底板（组件总线） ====================

    public static final class BoardDriver implements DriverItem {

        @Override
        public boolean worksWith(ItemStack stack) {
            final SocPartItem p = part(stack);
            return p != null && p.kind() == SocPartKind.BOARD;
        }

        @Override
        public String slot(ItemStack stack) {
            return Slot.ComponentBus;
        }

        @Override
        public int tier(ItemStack stack) {
            return ocTier(stack);
        }

        @Override
        public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
            return noEnvironment();
        }
    }

    // ==================== C 专属启动介质（EEPROM 槽） ====================

    /**
     * 启动介质驱动 —— 把我们自己的 {@code cryptand:eeprom_c} 放进 OC 的 **EEPROM 槽**。
     *
     * <p>OC 原生那颗 EEPROM 装的是 **Lua BIOS**（Lua 源码，Lua 架构解释执行）；我们的架构是
     * C/RV32，只能吃机器码 ⇒ 必须有自己的启动介质（承载 {@code cryptand-os.bin}，见
     * {@code firmware/cryptand-os/}）。</p>
     *
     * <p>槽位档位用 {@link #OC_TIER_ONE}（OC 的 {@code Slot.EEPROM} 就是 {@code Tier.One}，
     * 而 OC 的档位是 0 基 ⇒ 值是 0），这样任何机箱/服务器都接受它。</p>
     */
    public static final class EepromDriver implements DriverItem {

        @Override
        public boolean worksWith(ItemStack stack) {
            final SocPartItem p = part(stack);
            return p != null && p.kind() == SocPartKind.EEPROM;
        }

        @Override
        public String slot(ItemStack stack) {
            // ⚠ EEPROM 槽**不在** API 的 li.cil.oc.api.driver.item.Slot 里（那只列了 CPU/Memory/HDD/Card/…），
            //   它是 OC 内部常量 li.cil.oc.common.Slot.EEPROM = "eeprom" —— 这里按同值写字面量。
            return "eeprom";
        }

        @Override
        public int tier(ItemStack stack) {
            return OC_TIER_ONE;
        }

        /**
         * ★ 关键：把 EEPROM 变成 **OC 的 eeprom 组件**（2026-09-26 修）。
         *
         * <p>以前这里返回 {@code noEnvironment()} —— 于是 `component.list("eeprom")` 恒为空，
         * OC 的 Lua 架构（`lua/machine.lua:1493-1504`）直接走
         * `error("no bios found; install a configured EEPROM")`（用户截图的那条）。</p>
         *
         * <p>现在给出组件，内容 = **Cryptand 自有的 Lua BIOS**（`assets/cryptand/soc/bios.lua`）：
         * Lua 架构会 `get()` 它并 `load()` 执行；C 架构根本不读它（走宿主 BIOS + guest ROM）。
         * 这就是用户要的"EEPROM 既支持原版 OC 的 Lua 兼容启动，也支持 FATFS 盘找 bootloader 启动"。</p>
         */
        @Override
        public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
            // 容量按**部件自己的档位规格**算（specOf = KB），走 BootPlan 这唯一一处换算 ——
            // OC 的 eeprom.getSize() 要回答的是"这颗芯片自己有多大"，不是"引导记录最多占多少"：
            // ⚠ 以前这里用 limitBytes（= min(芯片容量, 引导区 64KB)，那是**引导判据**）⇒ 128KB 档
            //   会被报成 65536 字节，与物品规格对不上。两个量语义不同，这里该用的是芯片容量。
            return new com.hdf.cryptand.neoforge.opencomputers.CryptandEepromEnvironment(
                    luaBios(), com.hdf.cryptand.soc.os.BootPlan.eepromBytes(specOf(stack)));
        }
    }

    /**
     * Cryptand 的 Lua BIOS 源码（EEPROM 在 Lua 架构下的内容）。
     *
     * <p>⚠ 这是**我们自己写的**一份小程序（只做"找盘 → 找 /init.lua → 运行"），
     * 不是从 OpenComputers 复制/改编的 —— OC 的 AGENTS.md 明确不接受非平凡的 AI 贡献，
     * 我们既不引用它的实现，也不往它那边提交任何东西。</p>
     */
    private static byte[] luaBios() {
        try {
            final byte[] raw = com.hdf.cryptand.soc.os.Programs.read("bios.lua");
            if (raw.length > 0) {
                return raw;
            }
            LOG.warn("[OpenComputers] bios.lua 资源缺失（common 的 assets/cryptand/soc/）"
                    + "⇒ Lua 架构的 EEPROM 会是空的，机器会报 no bios found");
        } catch (Throwable t) {
            LOG.warn("[OpenComputers] 读取 bios.lua 失败", t);
        }
        return new byte[0];
    }

    // ==================== 系统软盘（floppy 槽） ====================

    /**
     * 软盘驱动 —— 把 {@code cryptand:floppy1/2/3}（**系统盘**）放进 OC 的 **floppy 槽**。
     *
     * <p>用户 2026-09-17："Cryptand OS 是放软盘的" ⇒ 系统装在软盘里，BIOS（Cryptand Boot）
     * 按"机箱软盘 → 外部软盘 → 机箱硬盘 → 外部硬盘"的顺序找可引导程序。</p>
     *
     * <p>OC 的 floppy 槽是 {@code Tier.One}（0 基 ⇒ 0），所以统一报 {@link #OC_TIER_ONE}。</p>
     */
    public static final class FloppyDriver implements DriverItem {

        @Override
        public boolean worksWith(ItemStack stack) {
            final SocPartItem p = part(stack);
            return p != null && p.kind() == SocPartKind.FLOPPY;
        }

        @Override
        public String slot(ItemStack stack) {
            return Slot.Floppy;
        }

        @Override
        public int tier(ItemStack stack) {
            return OC_TIER_ONE;
        }

        @Override
        public ManagedEnvironment createEnvironment(ItemStack stack, EnvironmentHost host) {
            // 软盘也是盘：走 OC 的 filesystem 组件（speed=1 是 OC 对软盘的默认档）
            return mountDisk(stack, host, 1);
        }
    }

    // ==================== 注册 ====================

    private static boolean registered;

    /**
     * 注册全部驱动（幂等）。
     *
     * <p>⚠ 必须在 OC 的 init 锁定前调用（见类注释）；失败只记日志，**不影响游戏启动**。</p>
     *
     * <p>⚠⚠ <b>自检不在这里调用</b>（2026-09-18 修正）：本方法跑在子包 init = mod 构造期
     * （{@code CryptandNeoForge.<init>} → {@code ModuleRegistry.initAll}），此时 NeoForge 的
     * 注册事件还没派发 ⇒ {@code DeferredHolder.get()} 抛
     * {@code NullPointerException: Trying to access unbound value: ...}。
     * 旧代码把 {@link #selfCheck()} 放在这里，异常被下面的 catch 吞成一行"自检异常（忽略）"，
     * <b>整张自检表一条都没打出来</b>（实测 latest.log：只有 WARN，没有任何 "自检 xxx：driver=..." 行）。
     * 现在改由 {@code OpenComputersEntry.commonSetup()}（FMLCommonSetup，注册绑定之后）触发。</p>
     */
    public static void register() {
        if (registered) {
            return;
        }
        try {
            Driver.add(new ProcessorDriver());
            // 内存条不注册驱动：OC 原版 ram1..ram8 自带 DriverMemory（见本类上方说明）
            Driver.add(new HddDriver());
            Driver.add(new CardDriver());
            Driver.add(new BoardDriver());
            Driver.add(new EepromDriver());
            Driver.add(new FloppyDriver());
            registered = true;
            LOG.info("[OpenComputers] 已注册 Cryptand 部件驱动 ×6"
                    + "（处理器→Slot.CPU 且架构= C/RV32；硬盘→Slot.HDD；"
                    + "扩展卡→Slot.Card；底板→Slot.ComponentBus；"
                    + "**C 启动盘→Slot.EEPROM**；**系统软盘→Slot.Floppy**）"
                    + "；内存条**不注册**（用 OC 原版 ram1..ram6）；档位按 OC 口径（0 基）换算：Cryptand 1..5 → OC "
                    + OC_TIER_ONE + ".." + OC_TIER_FOUR
                    + "；自检延后到 commonSetup（构造期物品尚未绑定，get() 必 NPE）");
        } catch (Throwable t) {
            LOG.warn("[OpenComputers] ⚠ Cryptand 部件驱动注册失败（通常是时机太晚，OC 的 driver 表已上锁）", t);
        }
    }

    // ==================== 真彩屏 → OC 的 screen 组件（2026-09-27 任务 C 已迁移）====================
    //
    // 旧的自研真彩屏（cryptand:truescreen，soc 子包）的 OC capability 注册 + BE 钩子安装
    // 已**整段删除**：真彩屏改由 OC 屏幕的 Scala 移植件提供，它自己注册
    // opencomputers:sided_environment capability（见
    // com.hdf.cryptand.neoforge.truescreen.TrueScreenContent.registerCapabilities）。
    //   · 移植件的 blockentity.Screen **本身就是** SidedEnvironment（不再需要 soc 子包那种
    //     "方块实体不 implements OC 接口 + 外挂钩子"的隔离层）；
    //   · ⚠ **绝不允许**两条 capability 注册同时存在：同一个方块实体类型注册两次会让
    //     OC 的网络看到一个随机的那份，症状是"节点时有时无"。
    // 任务 F-2（2026-09-27）：CryptandScreenEnvironment 已随旧自研真彩屏一并删除。

    /**
     * 注册后自检：把 OC 对 Cryptand 部件的识别结果打进日志（诊断"能否插进机箱"）。
     *
     * <p>调用时机：{@code OpenComputersEntry.commonSetup()}（FMLCommonSetup = 注册绑定之后）。
     * 自检表里的 id 必须与 {@code SocContent} 的注册 id 逐一对应 —— 改过名却没同步到这里时，
     * 会在日志里被点名（见 {@link #checkOne} 的单项保护），而不是静默少打几行。</p>
     */
    public static void selfCheck() {
        try {
            // ⚠ id 必须与 SocContent 的注册 id 一致（2026-09-18 起一律带位宽后缀 _32/_64；
            //   8051 用架构族名后缀 _8051）：改过名却没跟这里同步 ⇒ 自检会报"未注册/未绑定"，
            //   而真正的问题其实在自检本身（误导排查）。
            // 芯片：每种类型一颗示例（2026-09-29 起不再一档一个物品；档位表 SocCpuTiers 仍在）
            checkOne("chip_cpu", com.hdf.cryptand.neoforge.soc.content.SocContent.CHIP_CPU);
            checkOne("chip_gpu", com.hdf.cryptand.neoforge.soc.content.SocContent.CHIP_GPU);
            checkOne("soc", com.hdf.cryptand.neoforge.soc.content.SocContent.SOC_ASSEMBLED);
            checkOne("hdd2", com.hdf.cryptand.neoforge.soc.content.SocContent.DISK_2);
            checkOne("graphicscard2", com.hdf.cryptand.neoforge.soc.content.SocContent.CARD_GPU);
            checkOne("graphicscard3", com.hdf.cryptand.neoforge.soc.content.SocContent.CARD_GPU_PRO);
            checkOne("componentbus2", com.hdf.cryptand.neoforge.soc.content.SocContent.BOARD_ATX);
            checkOne("cbios", com.hdf.cryptand.neoforge.soc.content.SocContent.EEPROM_C);
        } catch (Throwable t) {
            LOG.warn("[OpenComputers] 自检异常（忽略）", t);
        }
    }

    private static void checkOne(String label,
                                 net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.world.item.Item, ?> holder) {
        final ItemStack stack;
        try {
            stack = new ItemStack(holder.get());
        } catch (Throwable t) {
            // 逐项隔离：某一项未注册/未绑定（例如改名漏同步）必须在日志里点名，且**不影响其余项**
            LOG.warn("[OpenComputers] 自检 {}：物品未注册 / 未绑定（{}）⇒ 检查 SocContent 的注册 id 与本自检表是否一致",
                    label, t.getClass().getSimpleName());
            return;
        }
        final DriverItem d = Driver.driverFor(stack);
        // 处理器顺便把 ISA / 位宽与内核状态打出来：一眼能看出"这颗芯片按几位跑、能不能跑"
        final SocPartItem p = part(stack);
        final String isaNote = p != null && p.isa() != null
                ? " ISA=" + p.isa().id() + "(" + p.isa().xlenBits() + " 位/" + p.isa().registerModel()
                        + ", " + p.isa().support() + ")"
                : "";
        if (d == null) {
            LOG.warn("[OpenComputers] 自检 {}：OC **不认**该部件（driverFor=null）⇒ 插不进机箱{}", label, isaNote);
        } else {
            LOG.info("[OpenComputers] 自检 {}：driver={} slot={} tier(OC 0 基)={} [Cryptand 档位={}]{}", label,
                    d.getClass().getSimpleName(), d.slot(stack), d.tier(stack), cryptandTier(stack), isaNote);
        }
    }

    public static boolean isRegistered() {
        return registered;
    }
}
