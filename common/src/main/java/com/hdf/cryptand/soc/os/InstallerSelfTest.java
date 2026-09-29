package com.hdf.cryptand.soc.os;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.fs.DiskFormatTool;
import com.hdf.cryptand.soc.fs.DiskPartitionTable;
import com.hdf.cryptand.soc.fs.FsMode;
import com.hdf.cryptand.soc.fs.DiskFileSystems;
import com.hdf.cryptand.soc.fs.DiskVolumes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * ===== 安装器沙盒自测（纯 Java 零 MC）=====
 *
 * <p>把"装系统"这条链路整条跑一遍：空盘 → 建分区 + 格式化 + 装程序 → 重开可读 →
 * 换系统重装 → 装不下时<b>盘上原系统完好</b>。</p>
 */
public final class InstallerSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        final Path base = Path.of("build", "tmp", "installer-selftest");
        if (Files.exists(base)) {
            try (Stream<Path> s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(base);

        final byte[] boot = Programs.readById("boot");
        final byte[] os = Programs.readById("cryptand-os");
        final byte[] uiOs = Programs.readById("cryptand-ui-os");
        check("三个程序都能从 common 读到", boot.length > 0 && os.length > 0 && uiOs.length > 0);

        // ---- 1. 空盘安装 ----
        final Path d1 = base.resolve("blank");
        final long cap = 4L * 1024 * 1024;
        check("装之前 hasSystem = false", !Installer.hasSystem(d1, cap));
        final Installer.Report r1 = Installer.install(d1, cap, cap, "", os, "SYSDISK");
        check("安装报告：FAT12 + 清空 0 项", r1.filesystem().equals("FAT12") && r1.clearedEntries() == 0);
        check("安装后 hasSystem = true", Installer.hasSystem(d1, cap));
        try (CryptandFileSystem fs = DiskFileSystems.open(d1, cap, "d1", false)) {
            check("盘上真有程序且校验通过", java.util.Arrays.equals(Programs.installed(fs), os));
        // 引导分层（2026-09-24）：程序加载器装出来的盘必须是**分层盘** ——
        // BIOS 交控制权给 /boot/loader.bin，loader 再按 ABI 拉 /boot/system.bin。
        check("盘上同时装了 bootloader（/boot/loader.bin 与 ROM 里那份同一产物）",
                java.util.Arrays.equals(Programs.installedLoader(fs), boot));
        check("安装报告里带上了 bootloader 字节数", r1.loaderBytes() == boot.length);
        final BootPlan.Decision layered = BootPlan.on(new BootPlan.Disk(4, true, 1, "SYSDISK", fs));
        check("装出来的盘带引导记录（BIOS 判据只认它，所以必须一起装）",
                layered != null && layered.loadAddress() == 0L && layered.program().length > 0);
        }

        // ---- 2. 换系统（重装另一个程序）----
        final Installer.Report r2 = Installer.install(d1, cap, cap, "", uiOs, "SYSDISK");
        check("换装 UI OS（406KB）成功", r2.programBytes() == uiOs.length);
        try (CryptandFileSystem fs = DiskFileSystems.open(d1, cap, "d1", false)) {
            check("盘上现在是 UI OS", java.util.Arrays.equals(Programs.installed(fs), uiOs));
        }

        // ---- 3. 装不下 ⇒ 报错，且**盘上原系统完好**（本类最要紧的一条）----
        final long smallCap = 256L * 1024;
        final Path d2 = base.resolve("small");
        Installer.install(d2, smallCap, smallCap, "", boot, "SMALL");
        boolean refused = false;
        try {
            // 256KB 的盘装 406KB 的程序：必须在**动盘之前**就拒绝（否则旧系统已经没了）
            Installer.install(d2, smallCap, smallCap, "", uiOs, "SMALL");
        } catch (com.hdf.cryptand.soc.fs.FsException e) {
            refused = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_NO_SPACE;
        }
        check("装不下 ⇒ ERR_NO_SPACE", refused);
        try (CryptandFileSystem fs = DiskFileSystems.open(d2, smallCap, "d2", false)) {
            check("被拒绝后盘上的原系统完好无损", java.util.Arrays.equals(Programs.installed(fs), boot));
        }

        // ---- 4. 重装复用分区（不会越装分区越多）----
        final Installer.Report r4 = Installer.install(d1, cap, cap, "", boot, "SYSDISK");
        // 重装会先格式化目标分区 ⇒ 挂载时盘本来就空，所以 clearedEntries = 0 是对的
        check("重装成功（格式化已清空，故清空条目数为 0）",
                r4.programBytes() == boot.length && r4.clearedEntries() == 0);
        final com.hdf.cryptand.soc.fs.DiskPartitionTable t4 =
                com.hdf.cryptand.soc.fs.DiskPartitionTable.load(d1, cap);
        check("重装后分区数仍是 1（复用而非新建）", t4.partitions().size() == 1);
        try (CryptandFileSystem fs = DiskFileSystems.open(d1, cap, "d1", false)) {
            check("重装后盘上是新程序", java.util.Arrays.equals(Programs.installed(fs), boot));
        }

        // ---- 5. 可用空间估算：单调且保守 ----
        check("估算随容量单调增加",
                Installer.usableBytes(1024 * 1024, com.hdf.cryptand.soc.fs.DiskFormatter.Fs.FAT12)
                        > Installer.usableBytes(256 * 1024, com.hdf.cryptand.soc.fs.DiskFormatter.Fs.FAT12));
        check("估算 < 分区大小（给元数据留了余量）",
                Installer.usableBytes(1024 * 1024, com.hdf.cryptand.soc.fs.DiskFormatter.Fs.FAT12) < 1024 * 1024);
        check("文件夹树分区不扣元数据",
                Installer.usableBytes(1024 * 1024, com.hdf.cryptand.soc.fs.DiskFormatter.Fs.CRYPTAND) == 1024 * 1024);

        // ---- 多分区盘：install 必须认**系统角色**，不能把 /home 分区当目标 ----
        // （用户 2026-09-24："完整 os 可以支持通过分区来实现功能" ⇒ 一块盘上会同时有
        //   /home、/usr 这类 FAT 分区，"第一个 FAT 分区"完全可能是用户数据区。）
        final Path d4 = base.resolve("multipart");
        final long cap4 = 8L * 1024 * 1024;
        DiskFormatTool.format(d4, cap4, 2L * 1024 * 1024, "FAT12", "HOME");
        {
            final java.util.List<DiskVolumes.Volume> vols = DiskVolumes.volumes(d4, cap4, "d4", false);
            final CryptandFileSystem homeFs = vols.get(0).fs();
            final int h = homeFs.open("/keep.txt", FsMode.WRITE);
            homeFs.getHandle(h).write("do-not-touch".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            homeFs.getHandle(h).close();
            homeFs.close();
        }
        check("只有 /home 的盘 ⇒ hasSystem = false（有 FAT 分区 ≠ 装了系统）",
                !Installer.hasSystem(d4, cap4));
        boolean noSystemVolume = false;
        try {
            DiskFileSystems.open(d4, cap4, "d4", false);
        } catch (IllegalStateException e) {
            noSystemVolume = e.getMessage() != null && e.getMessage().contains("no system volume");
        }
        check("只有 /home 的盘 ⇒ 打开时明确报错（不拿 /home 顶替系统卷）", noSystemVolume);

        final Installer.Report multiReport = Installer.install(d4, cap4, 2L * 1024 * 1024, "", os, "SYS");
        check("多分区盘：装系统成功且报告了程序大小", multiReport.programBytes() == os.length);
        final java.util.List<DiskVolumes.Volume> after = DiskVolumes.volumes(d4, cap4, "d4", false);
        check("多分区盘：分区表里有两个分区（新建 SYS，而不是复用 HOME）",
                DiskPartitionTable.load(d4, cap4).partitions().size() == 2);
        check("多分区盘：系统装在 SYS 分区上",
                after.stream().anyMatch(v -> v.role() == DiskVolumes.Role.SYSTEM
                        && java.util.Arrays.equals(Programs.installed(v.fs()), os)));
        check("多分区盘：/home 里的原文件没被动过",
                after.stream().filter(v -> v.role() == DiskVolumes.Role.HOME).findFirst()
                        .map(v -> readText(v.fs(), "/keep.txt")).orElse("").equals("do-not-touch"));
        check("多分区盘：hasSystem 认系统角色", Installer.hasSystem(d4, cap4));

        System.out.println("[INST] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String readText(CryptandFileSystem fs, String path) {
        if (!fs.exists(path)) {
            return "";
        }
        final int h = fs.open(path, FsMode.READ);
        final byte[] buf = new byte[(int) fs.getHandle(h).length()];
        final int n = fs.getHandle(h).read(buf);
        fs.getHandle(h).close();
        return new String(buf, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8);
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
