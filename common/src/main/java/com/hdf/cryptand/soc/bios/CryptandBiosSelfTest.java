package com.hdf.cryptand.soc.bios;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.fs.DiskFileSystems;
import com.hdf.cryptand.soc.os.BootPlan;
import com.hdf.cryptand.soc.os.Programs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ===== Cryptand BIOS 沙盒自测（纯 Java 零 MC）=====
 *
 * <p>把 BIOS 流程整条走一遍：枚举 → 按 boot order 选盘 → 交给架构。三条边界都要钉住：</p>
 * <ol>
 *   <li>有机箱但盘里都没程序 ⇒ **不启动**（绝不偷偷跑内置镜像）；</li>
 *   <li>机箱里一块盘都没有 ⇒ **不启动**；</li>
 *   <li>软盘优先于硬盘（用户规格），且交给架构的程序就是选中的那一块盘里的。</li>
 * </ol>
 */
public final class CryptandBiosSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        final Path base = Path.of("build", "tmp", "bios-selftest");
        if (Files.exists(base)) {
            try (Stream<Path> s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(base);

        final byte[] boot = Programs.readById("boot");
        final byte[] os = Programs.readById("cryptand-os");

        // ---- 1. 软盘优先（即使硬盘槽位更小）----
        try (CryptandFileSystem floppy = disk(base.resolve("floppy"), "floppy-1");
             CryptandFileSystem hdd = disk(base.resolve("hdd"), "hdd-1")) {
            Programs.install(floppy, boot);
            Programs.install(hdd, os);
            final List<String> logs = new ArrayList<>();
            final byte[][] handed = new byte[1][];
            final CryptandBios.Result r = CryptandBios.run(
                    () -> List.of(new BootPlan.Disk(2, false, 1, "硬盘A", hdd),
                            new BootPlan.Disk(7, true, 1, "软盘A", floppy)),
                    target("rv32imac", handed, true), logs::add);
            check("软盘优先：从槽 7 启动", r.started() && r.decision().slot() == 7);
            check("交给架构的正是软盘里的程序", java.util.Arrays.equals(handed[0], boot));
            check("日志里记下了选中的槽位", logs.stream().anyMatch(s -> s.contains("slot 7")));
            check("日志里带上架构名", logs.stream().anyMatch(s -> s.contains("rv32imac")));
        }

        // ---- 2. 有盘但都没程序 ⇒ 不启动 ----
        try (CryptandFileSystem empty = disk(base.resolve("empty"), "empty-1")) {
            final CryptandBios.Result r = CryptandBios.run(
                    () -> List.of(new BootPlan.Disk(1, true, 1, "空盘", empty)),
                    target("rv32imac", new byte[1][], true), null);
            check("盘里没程序 ⇒ 不启动（decision=null）", !r.started() && r.decision() == null);
        }

        // ---- 3. 一块盘都没有 ⇒ 不启动 ----
        final CryptandBios.Result none = CryptandBios.run(List::of, target("lua", new byte[1][], true), null);
        check("机箱里没盘 ⇒ 不启动", !none.started() && none.decision() == null);
        check("架构名照样回显（多架构支持）", none.arch().equals("lua"));

        // ---- 4. 架构拒绝启动 ⇒ 如实上报（不假装成功）----
        try (CryptandFileSystem floppy = disk(base.resolve("floppy2"), "floppy-2")) {
            Programs.install(floppy, os);
            final CryptandBios.Result r = CryptandBios.run(
                    () -> List.of(new BootPlan.Disk(3, true, 1, "软盘B", floppy)),
                    target("rv32imac", new byte[1][], false), null);
            check("架构拒绝启动时 started=false（不假装成功）", !r.started() && r.decision() != null);
        }

        // ==================== 5. BIOS 配置（= CMOS）：启动顺序 + 强制槽位 + 文本往返 ====================
        try (CryptandFileSystem floppy = disk(base.resolve("cfg-floppy"), "floppy-3");
             CryptandFileSystem hdd = disk(base.resolve("cfg-hdd"), "hdd-3")) {
            Programs.install(floppy, boot);
            Programs.install(hdd, os);
            final List<BootPlan.Disk> two = List.of(
                    new BootPlan.Disk(2, false, 1, "硬盘A", hdd),
                    new BootPlan.Disk(7, true, 1, "软盘A", floppy));

            final CryptandBios.Result hddFirst = CryptandBios.run(() -> two,
                    target("rv32imac", new byte[1][], true),
                    com.hdf.cryptand.soc.bios.BiosConfig.defaults().withOrder(BootPlan.BootOrder.HDD_FIRST), null);
            check("配置 HDD_FIRST ⇒ 从硬盘（槽 2）启动", hddFirst.started() && hddFirst.decision().slot() == 2);

            final CryptandBios.Result forced = CryptandBios.run(() -> two,
                    target("rv32imac", new byte[1][], true),
                    com.hdf.cryptand.soc.bios.BiosConfig.defaults().withForcedSlot(7), null);
            check("强制槽位 7 ⇒ 忽略 boot order，从软盘启动", forced.started() && forced.decision().slot() == 7);

            final CryptandBios.Result badForced = CryptandBios.run(() -> two,
                    target("rv32imac", new byte[1][], true),
                    com.hdf.cryptand.soc.bios.BiosConfig.defaults().withForcedSlot(5), null);
            check("强制一个不存在的槽位 ⇒ 退回 boot order（不静默失败）",
                    badForced.started() && badForced.decision().slot() == 7);

            final com.hdf.cryptand.soc.bios.BiosConfig cfg =
                    com.hdf.cryptand.soc.bios.BiosConfig.defaults()
                            .withOrder(BootPlan.BootOrder.SLOT_ORDER).withForcedSlot(4).withLabel("MY-PC");
            final com.hdf.cryptand.soc.bios.BiosConfig back =
                    com.hdf.cryptand.soc.bios.BiosConfig.fromText(cfg.toText());
            check("BIOS 配置文本往返（CMOS 持久化）", back.equals(cfg));
            check("配置损坏 ⇒ 退回默认而不抛（机器不该因为 CMOS 坏而起不来）",
                    com.hdf.cryptand.soc.bios.BiosConfig.fromText("garbage\norder=NOPE\nforcedSlot=xx")
                            .order() == BootPlan.BootOrder.FLOPPY_FIRST);
        }

        // ---- 4. 只认引导记录（用户 2026-09-25："只认 bootloader 保持现实一致"）----
        try (CryptandFileSystem bootable = disk(base.resolve("layered"), "floppy-4")) {
            Programs.install(bootable, os);
            Programs.installLoader(bootable, boot);
            final BootPlan.Decision[] seen = new BootPlan.Decision[1];
            final List<String> logs = new ArrayList<>();
            final CryptandBios.Result r = CryptandBios.run(
                    () -> List.of(new BootPlan.Disk(4, true, 1, "系统盘", bootable)),
                    probe(seen), logs::add);
            check("有引导记录的盘 ⇒ BIOS 交出控制权", r.started() && seen[0] != null);
            check("引导记录刷进 guest 引导区 0x0（不是系统区）",
                    seen[0] != null && seen[0].loadAddress() == 0L);
            check("日志写明引导记录与目标地址",
                    logs.stream().anyMatch(s -> s.contains("bootloader") && s.contains("guest 0x0")));
            check("系统本体原地不动（交给引导记录自己按 ABI 去拉）",
                    java.util.Arrays.equals(Programs.installed(bootable), os));
        }

        // ---- 5. 只有系统本体、没有引导记录 ⇒ **不可引导**（现实里同样是 no bootable device）----
        try (CryptandFileSystem noLoader = disk(base.resolve("legacy"), "floppy-5")) {
            // 手工只放系统本体（安装器现在一定会写引导记录，所以这种现场只能自己造）
            noLoader.makeDirectories("/boot");
            writeRaw(noLoader, Programs.BOOT_PATH, os);
            final BootPlan.Decision[] seen = new BootPlan.Decision[1];
            final List<String> logs = new ArrayList<>();
            final CryptandBios.Result r = CryptandBios.run(
                    () -> List.of(new BootPlan.Disk(5, true, 1, "无引导记录盘", noLoader)),
                    probe(seen), logs::add);
            check("没有引导记录 ⇒ 不启动（不绕过引导记录去跑系统本体）",
                    !r.started() && seen[0] == null);
            check("BIOS 逐盘说明为什么不可引导（不只是一句 no bootable device）",
                    logs.stream().anyMatch(s -> s.contains("没有引导记录")));
            check("BIOS 日志点明需要哪个文件", logs.stream().anyMatch(s -> s.contains(Programs.LOADER_PATH)));
        }

        // ---- 6. EEPROM 容量决定引导记录上限（现实一致：引导固件就烧在那颗芯片上）----
        try (CryptandFileSystem smallChip = disk(base.resolve("smallchip"), "floppy-9")) {
            Programs.installLoader(smallChip, new byte[6 * 1024]);
            final BootPlan.Disk chipDisk = new BootPlan.Disk(6, true, 1, "6KB引导记录盘", smallChip);
            final List<String> logs = new ArrayList<>();
            final CryptandBios.Result r = CryptandBios.run(new CryptandBios.DeviceSource() {
                @Override
                public java.util.List<BootPlan.Disk> disks() {
                    return List.of(chipDisk);
                }

                @Override
                public int eepromKb() {
                    return 4;   // 4KB 的 EEPROM：装不下 6KB 的引导记录
                }
            }, probe(new BootPlan.Decision[1]), logs::add);
            check("4KB EEPROM + 6KB 引导记录 ⇒ 不启动", !r.started());
            check("日志写出 EEPROM 容量与上限", logs.stream().anyMatch(s -> s.contains("eeprom=4KB")));
            check("日志逐盘说明原因（EEPROM 装不下）",
                    logs.stream().anyMatch(s -> s.contains("超过这颗 EEPROM 的容量")));
        }

        // ---- 7. CMOS 在启动盘上：/boot/cmos.cfg（容错是核心）----
        try (CryptandFileSystem cmosDisk = disk(base.resolve("cmos"), "floppy-10")) {
            check("盘上没有 CMOS ⇒ read() 返回 null（让调用方去找下一块盘）",
                    BiosCmos.read(cmosDisk) == null);
            final com.hdf.cryptand.soc.bios.BiosConfig cfg =
                    com.hdf.cryptand.soc.bios.BiosConfig.defaults()
                            .withOrder(BootPlan.BootOrder.HDD_FIRST).withForcedSlot(7).withLabel("LAB");
            BiosCmos.write(cmosDisk, cfg);
            check("写进 /boot/cmos.cfg 且回读校验通过",
                    cmosDisk.exists(BiosCmos.CMOS_PATH) && cfg.equals(BiosCmos.read(cmosDisk)));
            check("读回来的是同一份配置（order/forcedSlot/label 三项都在）",
                    BiosCmos.read(cmosDisk).order() == BootPlan.BootOrder.HDD_FIRST
                            && BiosCmos.read(cmosDisk).forcedSlot() == 7
                            && "LAB".equals(BiosCmos.read(cmosDisk).label()));
            // 损坏的 CMOS：退回默认值而不是抛（真机 CMOS 没电就是这个表现）
            final int hc = cmosDisk.open(BiosCmos.CMOS_PATH, com.hdf.cryptand.soc.fs.FsMode.WRITE);
            cmosDisk.getHandle(hc).write("garbage\norder=NOPE\nforcedSlot=xx".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            cmosDisk.getHandle(hc).close();
            check("CMOS 损坏 ⇒ 退回默认值（不抛、机器照开）",
                    BiosCmos.read(cmosDisk).order() == BootPlan.BootOrder.FLOPPY_FIRST);
        }

        // ---- 8. 设置项解析（BIOS Setup 命令层与将来的界面共用这一份）----
        final com.hdf.cryptand.soc.bios.BiosConfig b0 = com.hdf.cryptand.soc.bios.BiosConfig.defaults();
        check("设置 order=hdd 生效", b0.withSetting("order", "hdd").order() == BootPlan.BootOrder.HDD_FIRST);
        check("设置 forcedslot=3 生效", b0.withSetting("forcedslot", "3").forcedSlot() == 3);
        check("forcedslot=none ⇒ 取消强制", !b0.withSetting("forcedslot", "none").hasForcedSlot());
        check("设置 label 生效", "MY-PC".equals(b0.withSetting("label", "MY-PC").label()));
        check("回显文本含 order/forcedSlot/label 三项",
                b0.withSetting("order", "slot").describe().contains("order=SLOT_ORDER")
                        && b0.describe().contains("forcedSlot=") && b0.describe().contains("label="));
        boolean badKey = false;
        boolean badVal = false;
        try {
            b0.withSetting("nope", "x");
        } catch (IllegalArgumentException e) {
            badKey = true;
        }
        try {
            b0.withSetting("order", "sideways");
        } catch (IllegalArgumentException e) {
            badVal = true;
        }
        check("未知设置项明确拒绝（不静默忽略）", badKey);
        check("非法值明确拒绝（不静默忽略）", badVal);

        // ---- 9. Setup 面板的数据模型（渲染层将来只做"摆控件 + 喂点击"）----
        final java.util.List<com.hdf.cryptand.soc.bios.BiosSetupModel.Option> opts =
                com.hdf.cryptand.soc.bios.BiosSetupModel.options(b0);
        check("Setup 有三个设置项（引导顺序 / 强制槽位 / 机器标签）", opts.size() == 3);
        check("引导顺序的可选值 = floppy/hdd/slot",
                opts.get(0).key().equals("order")
                        && opts.get(0).values().equals(java.util.List.of("floppy", "hdd", "slot")));
        check("当前值反映配置（默认 ⇒ floppy / none）",
                "floppy".equals(opts.get(0).current()) && "none".equals(opts.get(1).current()));
        check("改配置后模型跟着变",
                "hdd".equals(com.hdf.cryptand.soc.bios.BiosSetupModel
                        .options(b0.withSetting("order", "hdd")).get(0).current()));
        check("apply 与 withSetting 同语义（三条入口不可能分叉）",
                com.hdf.cryptand.soc.bios.BiosSetupModel.apply(b0, "order", "slot").order()
                        == BootPlan.BootOrder.SLOT_ORDER);
        boolean modelRejected = false;
        try {
            com.hdf.cryptand.soc.bios.BiosSetupModel.apply(b0, "order", "sideways");
        } catch (IllegalArgumentException e) {
            modelRejected = true;
        }
        check("模型层同样明确拒绝非法值", modelRejected);

        System.out.println("[BIOS] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static CryptandBios.BootTarget target(String arch, byte[][] handed, boolean accepted) {
        return new CryptandBios.BootTarget() {
            @Override
            public String archName() {
                return arch;
            }

            @Override
            public boolean boot(BootPlan.Decision decision) {
                handed[0] = decision.program();
                return accepted;
            }
        };
    }

    /** 记录 BIOS 交出去的决策（用于断言阶段与目标地址） */
    private static CryptandBios.BootTarget probe(BootPlan.Decision[] seen) {
        return new CryptandBios.BootTarget() {
            @Override
            public String archName() {
                return "rv32imac";
            }

            @Override
            public boolean boot(BootPlan.Decision decision) {
                seen[0] = decision;
                return true;
            }
        };
    }

    /** 不经安装器、直接往盘上写一个文件（造"手工放进去的系统"这种现场） */
    private static void writeRaw(CryptandFileSystem fs, String path, byte[] bytes) {
        final int h = fs.open(path, com.hdf.cryptand.soc.fs.FsMode.WRITE);
        fs.getHandle(h).write(bytes);
        fs.getHandle(h).close();
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
