package com.hdf.cryptand.soc.os;

import com.hdf.cryptand.soc.fs.CryptandFileSystem;
import com.hdf.cryptand.soc.fs.DiskFileSystems;
import com.hdf.cryptand.soc.fs.DiskFormatTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * ===== 程序库 + 装盘器沙盒自测（纯 Java 零 MC）=====
 *
 * <p>用户："优先 common 跑测试成功"。这个闸门证明三件事，全在沙盒里，不用开客户端：</p>
 * <ol>
 *   <li>common 能读到固件字节（资源下放对了 —— 之前只有 neoforge 读得到）</li>
 *   <li>程序能装进<b>两种盘</b>（文件夹树 / FAT 镜像）并回读一致</li>
 *   <li>装盘器的校验真的会拦错（空程序、未装的盘）</li>
 * </ol>
 */
public final class ProgramsSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        final Path base = Path.of("build", "tmp", "programs-selftest");
        if (Files.exists(base)) {
            try (Stream<Path> s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(base);

        // ---- 1. 固件字节在 common 侧可见（这次迁移的意义所在） ----
        final var avail = Programs.available();
        System.out.println("  可用程序: " + avail.stream().map(Programs.Program::id).toList());
        check("至少编译出了 boot 与 cryptand-os",
                avail.stream().anyMatch(p -> p.id().equals("boot"))
                        && avail.stream().anyMatch(p -> p.id().equals("cryptand-os")));
        final byte[] boot = Programs.readById("boot");
        check("boot 镜像非空", boot.length > 0);
        check("boot 镜像头部不是空白（真程序）", boot[0] != 0 || boot[1] != 0 || boot[2] != 0 || boot[3] != 0);
        final byte[] os = Programs.readById("cryptand-os");
        check("cryptand-os 镜像比 boot 大（系统镜像）", os.length > boot.length);
        // ⚠ 已移除 linux 程序项（用户 2026-09-25："不要写 linux 挡位之类的"）
        check("未知程序 id ⇒ 空数组而不是异常", Programs.readById("no-such-program").length == 0);
        check("按 id 找不到 ⇒ null", Programs.byId("nope") == null);

        // ---- 2. 装进文件夹树盘（老盘 / FOLDER 分区） ----
        final Path folderDisk = base.resolve("folder");
        try (CryptandFileSystem fs = DiskFileSystems.open(folderDisk, 4L * 1024 * 1024, "p-folder", false)) {
            check("未装时 installed() 为空", Programs.installed(fs).length == 0);
            Programs.install(fs, os);
            check("已装时 installed() = 原字节", java.util.Arrays.equals(Programs.installed(fs), os));
            check("盘上真实出现 /boot/system.bin",
                    Files.isRegularFile(folderDisk.resolve("boot").resolve("system.bin")));
            Programs.install(fs, boot);                       // 再装一次（换程序）
            check("重装（换程序）后读回的是新程序", java.util.Arrays.equals(Programs.installed(fs), boot));
        }

        // ---- 3. 装进 FAT 镜像盘（IMAGE + FAT12 分区，Boot 未来实际读的那种） ----
        final Path fatDisk = base.resolve("fat");
        final long cap = 4L * 1024 * 1024;
        DiskFormatTool.format(fatDisk, cap, cap, "FAT12", "PROG");
        try (CryptandFileSystem fs = DiskFileSystems.open(fatDisk, cap, "p-fat", false)) {
            Programs.install(fs, boot);
            check("FAT 盘装盘 + 读回一致", java.util.Arrays.equals(Programs.installed(fs), boot));
            check("FAT 盘上路径正确", fs.exists(Programs.BOOT_PATH));
            check("FAT 盘容量够（4MB 装 2.5KB）", fs.spaceUsed() > 0 && fs.spaceUsed() < cap);
        }
        // 重新挂载（新 FAT 卷实例）⇒ 程序真的在盘上
        try (CryptandFileSystem fs = DiskFileSystems.open(fatDisk, cap, "p-fat", false)) {
            check("重挂载后程序还在且一致", java.util.Arrays.equals(Programs.installed(fs), boot));
        }

        // ---- 3.5 强制清空装入（命令 \cryptand disk load 的核心） ----
        final Path clearDisk = base.resolve("clear");
        try (CryptandFileSystem fs = DiskFileSystems.open(clearDisk, 4L * 1024 * 1024, "p-clear", false)) {
            final int h0 = fs.open("/junk.txt", com.hdf.cryptand.soc.fs.FsMode.WRITE);
            fs.getHandle(h0).write("old junk".getBytes());
            fs.getHandle(h0).close();
            fs.makeDirectory("/olddir");
            check("清空前盘上有旧内容", fs.exists("/junk.txt") && fs.exists("/olddir"));

            final int removed = Programs.installCleared(fs, os);
            check("强制清空装盘 ⇒ 返回清掉的条目数", removed >= 2);
            check("旧内容已被清空", !fs.exists("/junk.txt") && !fs.exists("/olddir"));
            check("新程序已就位且校验通过", java.util.Arrays.equals(Programs.installed(fs), os));

            // 换成 boot（更小）⇒ 同样清空重装
            Programs.installCleared(fs, boot);
            check("再次清空换程序 ⇒ 只留新程序", java.util.Arrays.equals(Programs.installed(fs), boot)
                    && !fs.exists("/junk.txt"));
        }

        // ---- 3.6 空间不够 ⇒ 明确报错，且**不毁掉盘上原有内容** ----
        final Path smallDisk = base.resolve("small-fs");
        final byte[] uiOs = Programs.readById("cryptand-ui-os");   // 406KB：塞不进小盘
        check("UI OS 镜像已编译（406KB 量级）", uiOs.length > 300_000);
        try (CryptandFileSystem fs = DiskFileSystems.open(smallDisk, 64L * 1024, "p-small", false)) {
            Programs.install(fs, boot);                            // 先放一个装得下的
            boolean noSpace = false;
            try {
                Programs.installCleared(fs, uiOs);                 // 406KB 的程序塞 64KB 的盘
            } catch (com.hdf.cryptand.soc.fs.FsException e) {
                noSpace = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_NO_SPACE;
            }
            check("程序大于盘容量 ⇒ ERR_NO_SPACE", noSpace);
            check("报错时盘上原有内容完好（先查空间再清空）",
                    java.util.Arrays.equals(Programs.installed(fs), boot));
        }

        // ---- 3.7 ★ mini OS：让最低配的盘也有系统可跑 ----
        //    cryptand-os 约 15KB ⇒ 装不进 8KB 档的 hdd1；mini OS 约 2KB ⇒ 装得下。
        //    这就是 mini OS 存在的理由，所以这条断言比"它编译出来了"更重要。
        final byte[] mini = Programs.readById("mini-os");
        check("mini OS 已编译", mini.length > 0);
        check("mini OS 远小于 8KB 档（" + mini.length + " 字节）", mini.length < 8 * 1024);
        try (CryptandFileSystem fs = DiskFileSystems.open(base.resolve("hdd1-8k"), 8L * 1024, "p-8k", false)) {
            Programs.install(fs, mini);
            check("8KB 盘装得下 mini OS", java.util.Arrays.equals(Programs.installed(fs), mini));
        }
        boolean osTooBig = false;
        try (CryptandFileSystem fs = DiskFileSystems.open(base.resolve("hdd1-8k-b"), 8L * 1024, "p-8k-b", false)) {
            Programs.install(fs, os);
        } catch (com.hdf.cryptand.soc.fs.FsException e) {
            osTooBig = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_NO_SPACE;
        }
        check("同一块 8KB 盘装不下 cryptand-os（对比：mini OS 的意义）", osTooBig);

        // ---- 3.8 引导分层：bootloader 路径与体积约定（用户：装 mini OS 的盘 bootloader < 4KB）----
        final byte[] loader = Programs.readById("boot");     // 现有 cryptand-boot.bin 就是候选 bootloader
        check("bootloader 候选 < 4KB（mini 档也装得下）", loader.length > 0 && loader.length < 4 * 1024);
        try (CryptandFileSystem fs = DiskFileSystems.open(base.resolve("loader-disk"), 4L * 1024 * 1024, "p-loader", false)) {
            Programs.installLoader(fs, loader);
            check("bootloader 写到 " + Programs.LOADER_PATH + " 并可读回",
                    java.util.Arrays.equals(Programs.installedLoader(fs), loader));
            check("loader 与 system 在同一盘上互不干扰（此时盘上还没有 system）",
                    Programs.installed(fs).length == 0);
            Programs.install(fs, mini);
            check("两者共存：loader 与 system 各就各位",
                    java.util.Arrays.equals(Programs.installedLoader(fs), loader)
                            && java.util.Arrays.equals(Programs.installed(fs), mini));
        }

        // ---- 3.9 ★ 系统软盘 = 安装介质（用户 2026-09-26）----
        //    盘上 /boot/system.bin 是 **PE（装机环境）**，而**完整镜像**放 /images/<id>.bin；
        //    开机先进 PE，由 PE 把镜像**拷贝安装**到目标硬盘。所以系统软盘可以反复装机、自己不变。
        final byte[] pe = Programs.readById(Programs.PE_ID);
        check("PE（Cryptand OS PE）已编译", pe.length > 0);
        check("PE 装得进最小的系统软盘档（512KB）", pe.length > 0 && pe.length < 512 * 1024);
        try (CryptandFileSystem fs = DiskFileSystems.open(base.resolve("sys-floppy"),
                512L * 1024, "p-sysfloppy", false)) {
            Programs.installInstallerMedia(fs, "cryptand-os");
            check("系统软盘的引导程序 = PE（不是系统本体）",
                    java.util.Arrays.equals(Programs.installed(fs), pe));
            check("第二级 bootloader 也在（BIOS 认的引导记录）",
                    java.util.Arrays.equals(Programs.installedLoader(fs), loader));
            final int hImg = fs.open(Programs.IMAGES_DIR + "/cryptand-os.bin",
                    com.hdf.cryptand.soc.fs.FsMode.READ);
            final byte[] buf = new byte[64 * 1024];
            final int n = fs.getHandle(hImg).read(buf);
            fs.getHandle(hImg).close();
            check("完整镜像在 " + Programs.IMAGES_DIR + "/cryptand-os.bin（PE 运行时拷贝它）",
                    n == os.length && java.util.Arrays.equals(java.util.Arrays.copyOf(buf, n), os));
        }

        // ---- 4. 校验会拦错 ----
        boolean emptyRejected = false;
        try {
            Programs.install(DiskFileSystems.open(base.resolve("x"), 1024L * 1024, "x", false), new byte[0]);
        } catch (IllegalArgumentException e) {
            emptyRejected = true;
        }
        check("空程序被拒（不静默写空文件）", emptyRejected);

        System.out.println("[PROG] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
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
