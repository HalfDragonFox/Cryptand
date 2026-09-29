package com.hdf.cryptand.soc.os;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.fs.DiskFileSystems;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ===== 引导决策沙盒自测（纯 Java 零 MC）=====
 *
 * <p>证明"该从哪块盘引导"这条规则是可测的：软盘优先、同类按槽位、盘里没程序就跳过、
 * 一块都没有 ⇒ 明确返回 null（而不是偷偷跑内置镜像）。</p>
 */
public final class BootPlanSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        final Path base = Path.of("build", "tmp", "bootplan-selftest");
        if (Files.exists(base)) {
            try (Stream<Path> s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(base);

        final byte[] boot = Programs.readById("boot");
        final byte[] os = Programs.readById("cryptand-os");
        check("固件可从 common 读到（boot/os 都非空）", boot.length > 0 && os.length > 0);
        // Lua 目标的入口：与游戏里同一份字节（assets/cryptand/soc/init.lua，Cryptand 自写救援 shell）
        final byte[] luaInit = Programs.read("init.lua");
        final String biosText = new String(Programs.read("bios.lua"), java.nio.charset.StandardCharsets.UTF_8);
        check("Lua 入口资源（init.lua）与 bios.lua 都能从 common 读到",
                luaInit.length > 0 && !biosText.isEmpty());

        try (CryptandFileSystem floppy = disk(base.resolve("floppy"), "floppy-1");
             CryptandFileSystem hdd = disk(base.resolve("hdd"), "hdd-1");
             CryptandFileSystem empty = disk(base.resolve("empty"), "empty-1")) {

            // ---- 空盘：谁都没装 ⇒ 明确无引导盘 ----
            check("全部为空 ⇒ 无引导盘（不偷偷跑内置镜像）",
                    BootPlan.choose(List.of(
                            new BootPlan.Disk(5, true, 1, "软盘A", floppy),
                            new BootPlan.Disk(6, false, 1, "硬盘A", hdd))) == null);

            // ---- 软盘优先于硬盘（即使硬盘槽位在前） ----
            Programs.install(floppy, boot);
            Programs.install(hdd, os);
            final BootPlan.Decision d1 = BootPlan.choose(List.of(
                    new BootPlan.Disk(2, false, 1, "硬盘A", hdd),
                    new BootPlan.Disk(7, true, 1, "软盘A", floppy)));
            check("软盘优先于硬盘（与槽位无关）", d1 != null && d1.slot() == 7);
            check("选中的是软盘上的引导记录", d1 != null && java.util.Arrays.equals(d1.program(), boot));

            // ---- 软盘没程序 ⇒ 落到硬盘 ----
            try (CryptandFileSystem floppyEmpty = disk(base.resolve("floppy-empty"), "floppy-2")) {
                final BootPlan.Decision d2 = BootPlan.choose(List.of(
                        new BootPlan.Disk(2, false, 1, "硬盘A", hdd),
                        new BootPlan.Disk(7, true, 1, "空软盘", floppyEmpty)));
                check("空软盘被跳过 ⇒ 落到硬盘", d2 != null && d2.slot() == 2);
                check("拿到的是硬盘上的引导记录（判据只认它，不是系统本体）",
                        d2 != null && java.util.Arrays.equals(d2.program(), Programs.installedLoader(hdd)));
            }

            // ---- 两块软盘都有 ⇒ 槽位小的胜出 ----
            try (CryptandFileSystem floppy2 = disk(base.resolve("floppy2"), "floppy-3")) {
                Programs.install(floppy2, os);
                final BootPlan.Decision d3 = BootPlan.choose(List.of(
                        new BootPlan.Disk(9, true, 2, "软盘B", floppy2),
                        new BootPlan.Disk(3, true, 1, "软盘A", floppy)));
                check("两块软盘都有程序 ⇒ 槽位小的胜出", d3 != null && d3.slot() == 3);
            }

            // ---- 顺序函数 ----
            final List<BootPlan.Disk> ordered = BootPlan.ordered(List.of(
                    new BootPlan.Disk(4, false, 1, "硬盘", hdd),
                    new BootPlan.Disk(8, true, 1, "软盘B", floppy),
                    new BootPlan.Disk(1, true, 1, "软盘A", floppy)));
            check("ordered()：先软盘（槽位升序）再硬盘",
                    ordered.get(0).slot() == 1 && ordered.get(1).slot() == 8 && ordered.get(2).slot() == 4);

            // ---- 换程序立即生效（"不关机换盘调试"的核心） ----
            Programs.install(floppy, os);
            final BootPlan.Decision d4 = BootPlan.choose(List.of(
                    new BootPlan.Disk(3, true, 1, "软盘A", floppy)));
            check("重新装盘 ⇒ 下一次决策读到新的引导记录",
                    d4 != null && java.util.Arrays.equals(d4.program(), Programs.installedLoader(floppy)));

            // ---- 只认引导记录（用户 2026-09-25："只认 bootloader 保持现实一致"）----
            try (CryptandFileSystem layered = disk(base.resolve("layered"), "floppy-4")) {
                Programs.install(layered, os);
                Programs.installLoader(layered, boot);
                final BootPlan.Decision d5 = BootPlan.choose(List.of(
                        new BootPlan.Disk(4, true, 1, "系统盘", layered)));
                check("有引导记录 ⇒ 可引导", d5 != null);
                check("引导记录刷进 guest 引导区 0x0（不是系统区 0x10000）",
                        d5 != null && d5.loadAddress() == 0L);
                check("拿到的是引导记录本体（不是系统镜像）",
                        d5 != null && d5.program().length > 0 && !java.util.Arrays.equals(d5.program(), os));
                check("系统本体原地不动（第二级由引导记录按 ABI 去拉）",
                        java.util.Arrays.equals(Programs.installed(layered), os));
                check("explain 对可引导的盘也给出结论", "可引导".equals(
                        BootPlan.explain(new BootPlan.Disk(4, true, 1, "系统盘", layered))));
            }

            // ---- 手工只放系统本体（绕过安装器）⇒ 仍然**不可引导**：判据只认引导记录 ----
            //   这种盘现实里就是"分区里有系统、但没有引导记录"，装了也起不来；
            //   它也是唯一还能造出"缺引导记录"状态的办法（安装器现在一定会写引导记录）。
            try (CryptandFileSystem manual = disk(base.resolve("manual"), "floppy-8")) {
                manual.makeDirectories("/boot");
                writeRaw(manual, Programs.BOOT_PATH, os);
                final BootPlan.Disk onlySystem = new BootPlan.Disk(3, true, 1, "手工放系统的盘", manual);
                check("只有系统本体、没有引导记录 ⇒ 不可引导（不绕过引导记录去跑系统本体）",
                        BootPlan.choose(List.of(onlySystem)) == null);
                check("explain 点明缺的是引导记录并给出修法",
                        BootPlan.explain(onlySystem).contains("没有引导记录")
                                && BootPlan.explain(onlySystem).contains("/cryptand soc disk install"));
            }

            // ---- 空盘：explain 必须把"空盘"与"缺引导记录"分开说 ----
            try (CryptandFileSystem blank = disk(base.resolve("blank"), "floppy-6")) {
                final BootPlan.Disk blankDisk = new BootPlan.Disk(9, true, 1, "空盘", blank);
                check("空盘不可引导", BootPlan.choose(List.of(blankDisk)) == null);
                check("空盘的 explain 说的是空盘", BootPlan.explain(blankDisk).contains("空盘"));
            }

            // ---- 引导记录超过引导区（64KB）⇒ 不可引导（刷进去会盖住系统区）----
            try (CryptandFileSystem big = disk(base.resolve("bigloader"), "floppy-7")) {
                Programs.installLoader(big, new byte[BootPlan.LOADER_ROM_BYTES + 1]);
                final BootPlan.Disk oversized = new BootPlan.Disk(10, true, 1, "超界引导记录", big);
                check("超过引导区的引导记录 ⇒ 不可引导", BootPlan.choose(List.of(oversized)) == null);
                check("explain 说明它超过了引导区", BootPlan.explain(oversized).contains("超过引导区"));
            }

            // ---- EEPROM 容量决定引导记录上限（用户 2026-09-25 的档位 + 现实一致）----
            try (CryptandFileSystem sixKb = disk(base.resolve("sixkb"), "floppy-9")) {
                Programs.installLoader(sixKb, new byte[6 * 1024]);
                final BootPlan.Disk d9 = new BootPlan.Disk(11, true, 1, "6KB引导记录", sixKb);
                check("EEPROM 4KB 装不下 6KB 引导记录 ⇒ 不可引导",
                        BootPlan.choose(List.of(d9), BootPlan.BootOrder.FLOPPY_FIRST, 4) == null);
                check("同一块盘，EEPROM 8KB 就能引导（上限确实来自芯片容量）",
                        BootPlan.choose(List.of(d9), BootPlan.BootOrder.FLOPPY_FIRST, 8) != null);
                check("explain 说明是 EEPROM 容量不够并给出两条出路",
                        BootPlan.explain(d9, 4).contains("超过这颗 EEPROM 的容量")
                                && BootPlan.explain(d9, 4).contains("换更大档的 EEPROM"));
                check("上限取 min(EEPROM, 引导区)：128KB EEPROM 也被 64KB 引导区卡住",
                        BootPlan.limitBytes(128) == BootPlan.LOADER_ROM_BYTES);
                // ---- 芯片容量 ≠ 引导记录上限（2026-09-27：EEPROM 组件的 getSize 用错了量）----
                //   "这颗芯片多大"（eepromBytes）与"引导记录最多占多少"（limitBytes）是两个量，
                //   驱动侧曾经拿后者回答 getSize ⇒ 128KB 档报成 65536 字节，与物品规格对不上。
                check("芯片容量：4KB 档 = 4096 字节", BootPlan.eepromBytes(4) == 4 * 1024);
                check("芯片容量：128KB 档 = 131072 字节（**不是**引导记录上限 65536）",
                        BootPlan.eepromBytes(128) == 128 * 1024
                                && BootPlan.eepromBytes(128) != BootPlan.limitBytes(128));
                check("芯片容量：档位 <= 0 ⇒ 明确抛（没有'默认档'这回事，编一个数就是第二份口径）",
                        throwsIae(() -> BootPlan.eepromBytes(0))
                                && throwsIae(() -> BootPlan.eepromBytes(-8)));
                check("芯片容量：容量表六档逐一自洽（KB×1024，且都 >= 引导记录上限）",
                        capacityTiersConsistent());
                check("默认（机箱里没有我们的 EEPROM）按最大档放行：6KB 记录仍可引导",
                        BootPlan.choose(List.of(d9)) != null);
            }
            // ==================== MCU 档：flash 直载（与 BIOS 共用同一条「第一有效文件」规则）====
            //   用户定案（2026-09-27）："虚拟机运行基本都是从 0x0 开始的，不管 mcu 还是 soc，
            //   如果 0x0 部分是 bootloader 就是 boot 启动"；"eeprom 只需要找到**第一有效文件**"。
            //   ⇒ 本节钉死：**选择函数只有 BootPlan.entryOf 一份**（loader 优先 → 退 system）；
            //     MCU 直接用它（flash 的 0x0），BIOS 路径只用它的第一分支（引导记录）。
            //   窗口在真机 = guest ROM 大小（OcBoardLayout.ROM_BYTES）；闸门里取一个明确值即可。
            final int flashWindow = BootPlan.LOADER_ROM_BYTES;
            final BootPlan.BootOrder mcuOrder = BootPlan.BootOrder.SLOT_ORDER;
            try (CryptandFileSystem flashDisk = disk(base.resolve("flash"), "floppy-10");
                 CryptandFileSystem loaderOnly = disk(base.resolve("loader-only"), "floppy-11");
                 CryptandFileSystem bigFlash = disk(base.resolve("big-flash"), "floppy-12")) {

                // ---- 没有有效盘 ⇒ 明确拒绝（null），绝不偷偷跑内置镜像 ----
                check("MCU：一块盘都没有 ⇒ 拒绝（不偷偷跑内置镜像）",
                        BootPlan.flash(List.of(), mcuOrder, flashWindow) == null);
                final BootPlan.Disk blankFlash = new BootPlan.Disk(3, true, 1, "空软盘", empty);
                check("MCU：空盘 ⇒ 拒绝，原因写明空盘（既没有 loader 也没有程序本体）",
                        BootPlan.flash(List.of(blankFlash), mcuOrder, flashWindow) == null
                                && BootPlan.flashExplain(blankFlash, flashWindow).contains("空盘"));

                // ---- 只有引导记录：两档都认为可引导（判据已合并 = 第一有效文件）----
                loaderOnly.makeDirectories(Programs.BOOT_DIR);
                writeRaw(loaderOnly, Programs.LOADER_PATH, boot);
                final BootPlan.Disk onlyLoader = new BootPlan.Disk(4, true, 1, "只有引导记录", loaderOnly);
                check("MCU：只有 /boot/loader.bin ⇒ 命中（0x0 上是 bootloader ⇒ boot 启动）",
                        BootPlan.flash(List.of(onlyLoader), mcuOrder, flashWindow) != null);
                check("★ 全档位同一条规则：同一块盘 BIOS 与 MCU 都认为可引导",
                        BootPlan.choose(List.of(onlyLoader), BootPlan.BootOrder.FLOPPY_FIRST) != null
                                && BootPlan.flash(List.of(onlyLoader), mcuOrder, flashWindow) != null);
                check("MCU：有引导记录时 0x0 ← " + Programs.LOADER_PATH + "（bootloader 优先）",
                        BootPlan.flash(List.of(onlyLoader), mcuOrder, flashWindow).entryPath()
                                .equals(Programs.LOADER_PATH));

                // ---- 有第一有效文件 ⇒ 第一块有效盘胜出；载入地址 = 入口 = 0x0 ----
                Programs.install(flashDisk, os);
                final BootPlan.Disk flashable_ = new BootPlan.Disk(7, true, 1, "flash 盘", flashDisk);
                final BootPlan.Flash f1 = BootPlan.flash(List.of(flashable_), mcuOrder, flashWindow);
                check("MCU：盘上有程序 ⇒ 命中", f1 != null && f1.slot() == 7);
                check("MCU：0x0 ← **第一有效文件** = " + Programs.LOADER_PATH + "（盘上两者都有 ⇒ bootloader 优先）",
                        f1 != null && f1.entryPath().equals(Programs.LOADER_PATH)
                                && java.util.Arrays.equals(f1.program(), Programs.installedLoader(flashDisk)));
                check("★ MCU：载入地址 = 入口 = ROM 起始 0x0（镜像首字节即 .text.start）",
                        f1 != null && f1.loadAddress() == 0L && f1.entryAddress() == 0L);
                check("MCU：explain 对有效盘给出结论（并点明 0x0 放的是哪个文件）",
                        BootPlan.flashExplain(flashable_, flashWindow).startsWith("可作 flash")
                                && BootPlan.flashExplain(flashable_, flashWindow).contains(Programs.LOADER_PATH));
                check("MCU：不需要 EEPROM/引导记录也照样命中（判据 = 有第一有效文件）",
                        BootPlan.flash(List.of(flashable_), mcuOrder, flashWindow) != null);

                // ---- 第一块无效盘被跳过 ⇒ 落到第一块有第一有效文件的盘（顺序仍由 BootPlan 唯一决定）----
                try (CryptandFileSystem second = disk(base.resolve("flash-2"), "floppy-13")) {
                    second.makeDirectories(Programs.BOOT_DIR);
                    writeRaw(second, Programs.BOOT_PATH, boot);
                    final BootPlan.Flash f2 = BootPlan.flash(List.of(
                                    blankFlash,
                                    new BootPlan.Disk(5, true, 1, "第二块有程序", second)),
                            mcuOrder, flashWindow);
                    check("MCU：第一块空盘被跳过 ⇒ 落到第一块有第一有效文件的盘", f2 != null && f2.slot() == 5);
                    check("MCU：只有系统本体的盘 ⇒ 0x0 直接是 " + Programs.BOOT_PATH + "（单段）",
                            f2 != null && f2.entryPath().equals(Programs.BOOT_PATH));
                }

                // ---- 程序超过 flash 窗口 ⇒ 拒绝，并说明窗口大小 ----
                bigFlash.makeDirectories(Programs.BOOT_DIR);
                writeRaw(bigFlash, Programs.BOOT_PATH, new byte[flashWindow + 1]);
                final BootPlan.Disk oversizedFlash = new BootPlan.Disk(8, true, 1, "超窗口程序", bigFlash);
                check("MCU：程序超过 flash 窗口 ⇒ 拒绝",
                        BootPlan.flash(List.of(oversizedFlash), mcuOrder, flashWindow) == null);
                check("MCU：原因写明超过 flash 窗口（不是笼统的'不可引导'）",
                        BootPlan.flashExplain(oversizedFlash, flashWindow).contains("超过 flash 窗口"));

                // ---- 窗口必须显式给：<= 0 明确抛错（不静默当成 0 容量）----
                check("MCU：flash 窗口 <= 0 ⇒ 明确抛 IllegalArgumentException",
                        throwsIae(() -> BootPlan.flash(List.of(), mcuOrder, 0)));
            }

            // ==================== 引导目标：Lua 与 C/RV32 并列（按架构选）====================
            //   用户定案（2026-09-27）："EEPROM 既支持原版 OC 的 Lua 兼容启动，也支持从 FATFS 盘
            //   找 bootloader 启动"；"引导目标枚举里把 Lua 视作一种目标，与 C/RV32 那条并列
            //   （按架构选择，不引入第二套并行链路）"。本节钉死两件事：
            //     ① 目标从**架构**选（未知/歧义 ⇒ null，绝不默认成某一个）；
            //     ② 同一块盘在两个目标下结论可以不同，而"哪块盘、什么顺序"只有一份（本类）。

            // ---- ① 目标选择只有一处判据 ----
            check("目标：OC 原生 Lua 架构 ⇒ Lua 目标",
                    BootPlan.Target.ofArchitecture("li.cil.oc.server.machine.luac.NativeLua53Architecture")
                            == BootPlan.Target.LUA);
            check("目标：OC 的 LuaJ 架构 ⇒ 同样是 Lua 目标",
                    BootPlan.Target.ofArchitecture("li.cil.oc.server.machine.luaj.LuaJLuaArchitecture")
                            == BootPlan.Target.LUA);
            check("目标：我们的 C/RV32 架构 ⇒ C/RV32 目标",
                    BootPlan.Target.ofArchitecture(
                            "com.hdf.cryptand.neoforge.opencomputers.CryptandOcArchitecture")
                            == BootPlan.Target.C_RV32);
            check("目标：未知架构 ⇒ null（调用方必须明确报错，不许默认成一个）",
                    BootPlan.Target.ofArchitecture("com.example.SomeOtherArchitecture") == null);
            check("目标：名字里 lua 与 rv32 都出现（歧义）⇒ null，不猜",
                    BootPlan.Target.ofArchitecture("com.example.LuaRv32HybridArchitecture") == null);
            check("目标：null 架构 ⇒ null（不抛、也不默认）",
                    BootPlan.Target.ofArchitecture((String) null) == null
                            && BootPlan.Target.ofArchitecture((Class<?>) null) == null);
            check("目标入口：C/RV32 只认引导记录；Lua 认三个 Lua 入口（顺序固定）",
                    BootPlan.Target.C_RV32.entryPaths().equals(List.of(Programs.LOADER_PATH))
                            && BootPlan.Target.LUA.entryPaths().equals(
                                    List.of("/init.lua", "/boot/init.lua", "/usr/init.lua")));
            check("目标主入口：C/RV32 = /boot/loader.bin、Lua = /init.lua",
                    "/boot/loader.bin".equals(BootPlan.Target.C_RV32.entryPath())
                            && "/init.lua".equals(BootPlan.Target.LUA.entryPath()));
            // ★ 同一张表：bios.lua 是 Lua 目标的执行者（Lua 不能 import Java）⇒ 路径必须写在它里面
            boolean biosHasLuaPaths = true;
            for (final String path : BootPlan.LUA_INIT_PATHS) {
                biosHasLuaPaths &= biosText.contains('"' + path + '"');
            }
            check("bios.lua 与 BootPlan.LUA_INIT_PATHS 是同一张表（三个路径都在那段 Lua 里）",
                    biosHasLuaPaths);
            check("bios.lua 也认识 C/RV32 的引导记录路径（用来区分'空盘'与'CPU 装错了'）",
                    biosText.contains('"' + Programs.LOADER_PATH + '"'));
            // ★ 体积闸门：EEPROM 的内容就是这段 Lua，装不下就是"Lua 架构开不了机"。
            //   8KB = OC 默认 eepromSize，也是我们能接受的最小可用档（4KB 档装不下，运行期会明确报错）。
            check("bios.lua 装得进 8KB 档 EEPROM（OC 的默认 eepromSize 也是 8192）",
                    biosText.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 8 * 1024);

            // ---- ② 同一块盘、两个目标：结论可以不同，规则只有一份 ----
            try (CryptandFileSystem dual = disk(base.resolve("dual"), "floppy-14");
                 CryptandFileSystem rv32only = disk(base.resolve("rv32-only"), "floppy-15")) {
                // 双模介质 = 系统软盘（/boot/loader.bin 与 /init.lua 都在同一块盘上）
                Programs.installInstallerMedia(dual, "pe");
                final BootPlan.Disk dualDisk = new BootPlan.Disk(1, true, 1, "系统软盘", dual);
                check("双模介质：C/RV32 目标可引导（盘上有 /boot/loader.bin）",
                        BootPlan.Target.C_RV32.canBoot(dualDisk, 8));
                check("双模介质：Lua 目标也可引导（同一块盘上有 /init.lua）",
                        BootPlan.Target.LUA.canBoot(dualDisk, 8));
                check("双模介质：两个目标拿到的是**不同**的入口字节（各是各自那份）",
                        !java.util.Arrays.equals(BootPlan.Target.C_RV32.entryBytes(dualDisk, 8),
                                BootPlan.Target.LUA.entryBytes(dualDisk, 8)));
                final BootPlan.LuaEntry dualLua = BootPlan.luaOn(dualDisk);
                check("双模介质：Lua 入口就是盘上的 /init.lua，字节与读盘结果一致",
                        dualLua != null && "/init.lua".equals(dualLua.path())
                                && java.util.Arrays.equals(dualLua.source(), luaInit));
                check("Lua 目标的 explain 对双模介质说'可引导'并写出入口路径",
                        BootPlan.luaExplain(dualDisk).contains("可引导")
                                && BootPlan.luaExplain(dualDisk).contains("/init.lua"));

                // 只有 C/RV32 引导记录：C 眼里可引导、Lua 眼里必须说清"不是给你用的"
                rv32only.makeDirectories(Programs.BOOT_DIR);
                writeRaw(rv32only, Programs.LOADER_PATH, boot);
                final BootPlan.Disk onlyRv32 = new BootPlan.Disk(2, true, 1, "RV32 引导盘", rv32only);
                check("只有引导记录：C/RV32 目标可引导", BootPlan.Target.C_RV32.canBoot(onlyRv32, 8));
                check("★ 同一块盘：Lua 目标明确拒绝（不假装能跑 RV32 镜像）",
                        !BootPlan.Target.LUA.canBoot(onlyRv32, 8) && BootPlan.luaOn(onlyRv32) == null);
                check("Lua 的 explain 点明盘上是 C/RV32 引导记录并给出两条出路",
                        BootPlan.luaExplain(onlyRv32).contains("C/RV32")
                                && BootPlan.luaExplain(onlyRv32).contains("/boot/loader.bin")
                                && BootPlan.luaExplain(onlyRv32).contains("换 RV32 处理器"));
                check("Lua 的 explain 与 C 的 explain 不是同一句话（逐目标各答一次）",
                        !BootPlan.luaExplain(onlyRv32).equals(BootPlan.explain(onlyRv32, 8)));
                check("Target.explain 走各自那份判据（同一块盘两个结论）",
                        BootPlan.Target.C_RV32.explain(onlyRv32, 8).equals(BootPlan.explain(onlyRv32, 8))
                                && BootPlan.Target.LUA.explain(onlyRv32, 8).equals(BootPlan.luaExplain(onlyRv32)));
                check("Target.entryBytes：不能引导时返回 null（不是空数组冒充程序）",
                        BootPlan.Target.LUA.entryBytes(onlyRv32, 8) == null);
            }

            // ---- ③ Lua 入口的选择：候选顺序固定、boot order 复用、空盘/空文件拒绝 ----
            try (CryptandFileSystem luaFallback = disk(base.resolve("lua-fallback"), "floppy-16");
                 CryptandFileSystem luaUsr = disk(base.resolve("lua-usr"), "floppy-17");
                 CryptandFileSystem luaHdd = disk(base.resolve("lua-hdd"), "floppy-18")) {
                luaFallback.makeDirectories("/boot");
                writeRaw(luaFallback, "/boot/init.lua", luaInit);
                final BootPlan.Disk fallbackDisk = new BootPlan.Disk(3, true, 1, "备选入口", luaFallback);
                check("Lua 入口顺序：没有 /init.lua 时用 /boot/init.lua",
                        "/boot/init.lua".equals(BootPlan.luaOn(fallbackDisk).path()));
                writeRaw(luaFallback, "/init.lua", luaInit);
                check("补上 /init.lua 之后它优先（候选顺序是固定的）",
                        "/init.lua".equals(BootPlan.luaOn(fallbackDisk).path()));

                luaUsr.makeDirectories("/usr");
                writeRaw(luaUsr, "/usr/init.lua", luaInit);
                final BootPlan.Disk usrDisk = new BootPlan.Disk(4, true, 1, "只有 usr", luaUsr);
                check("只有 /usr/init.lua 的盘也可引导（第三个候选不是摆设）",
                        "/usr/init.lua".equals(BootPlan.luaOn(usrDisk).path()));

                final BootPlan.Disk blankLua = new BootPlan.Disk(5, true, 1, "空盘", empty);
                check("空盘对 Lua 目标不可引导，且 explain 说的是空盘",
                        BootPlan.luaOn(blankLua) == null && BootPlan.luaExplain(blankLua).contains("空盘"));
                // 0 字节的 /init.lua 不算入口（不静默跑一个空程序）——先造出来，再看判据
                writeRaw(luaUsr, "/empty-init.lua", new byte[0]);
                try (CryptandFileSystem onlyEmpty = disk(base.resolve("lua-empty"), "floppy-19")) {
                    writeRaw(onlyEmpty, "/init.lua", new byte[0]);
                    final BootPlan.Disk emptyEntry = new BootPlan.Disk(6, true, 1, "空入口", onlyEmpty);
                    check("0 字节的 /init.lua 不算 Lua 入口（空文件 ≠ 可引导）",
                            BootPlan.luaOn(emptyEntry) == null);
                }

                // ---- ④ Lua 的选盘：boot order 仍是同一份实现（软盘优先 → 槽位升序）----
                luaHdd.makeDirectories("/boot");
                writeRaw(luaHdd, "/boot/init.lua", luaInit);
                final BootPlan.Disk hddLua = new BootPlan.Disk(9, false, 1, "有入口的硬盘", luaHdd);
                final BootPlan.LuaEntry pick = BootPlan.chooseLua(List.of(hddLua, fallbackDisk));
                check("Lua 选盘：软盘优先于硬盘（与 C/RV32 共用 BootPlan.ordered）",
                        pick != null && pick.slot() == 3);
                final BootPlan.LuaEntry pickSlot = BootPlan.chooseLua(List.of(
                        new BootPlan.Disk(9, false, 1, "硬盘B", luaHdd),
                        new BootPlan.Disk(2, true, 1, "软盘A", luaUsr)));
                check("Lua 选盘：同类按槽位升序", pickSlot != null && pickSlot.slot() == 2);
                final BootPlan.LuaEntry pickSkip = BootPlan.chooseLua(List.of(blankLua, hddLua));
                check("Lua 选盘：跳过没有 Lua 入口的盘 ⇒ 落到有入口的那块",
                        pickSkip != null && pickSkip.slot() == 9);
                check("Lua 选盘：一块可引导的都没有 ⇒ null（不偷偷跑内置程序）",
                        BootPlan.chooseLua(List.of(blankLua)) == null);
            }
        }

        System.out.println("[BOOT] " + passed + "/" + (passed + failed) + " checks passed");

        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 不经安装器、直接往盘上写一个文件（用来造"手工放进去的系统"这种现场） */
    private static void writeRaw(CryptandFileSystem fs, String path, byte[] bytes) {
        final int h = fs.open(path, com.hdf.cryptand.soc.fs.FsMode.WRITE);
        fs.getHandle(h).write(bytes);
        fs.getHandle(h).close();
    }

    /** 容量表六档逐一：eepromBytes = KB×1024，且引导记录上限不超过芯片容量 */
    private static boolean capacityTiersConsistent() {
        for (final int kb : com.hdf.cryptand.soc.part.SocCapacities.EEPROM_KB) {
            if (BootPlan.eepromBytes(kb) != kb * 1024
                    || BootPlan.limitBytes(kb) > BootPlan.eepromBytes(kb)) {
                return false;
            }
        }
        return true;
    }

    /** 某段代码是否明确抛 IllegalArgumentException（"参数非法"必须是异常，不是静默兜底） */
    private static boolean throwsIae(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        } catch (Throwable other) {
            return false;
        }
    }

    private static CryptandFileSystem disk(Path dir, String address) {
        return DiskFileSystems.open(dir, 4L * 1024 * 1024, address, false);
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
