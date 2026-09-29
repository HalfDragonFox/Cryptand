package com.hdf.cryptand.soc.fs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ===== 多卷（分区承载功能）沙盒自测（纯 Java 零 MC）=====
 *
 * <p>用户定案（2026-09-24）："完整 os 可以支持通过分区来实现功能，对应现代系统"。
 * 这条闸门钉住三件事：</p>
 * <ol>
 *   <li>分区 <b>label</b> 决定角色（顺序换不动系统卷是谁）；</li>
 *   <li>各卷是<b>真隔离</b>的（系统卷里看不到用户卷的文件，反之亦然），重开盘内容还在；</li>
 *   <li>不可用的分区<b>说明原因</b>（未格式化/挂载失败），绝不静默跳过、也绝不回落。</li>
 * </ol>
 */
public final class DiskVolumesSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        final Path base = Path.of("build", "tmp", "volumes-selftest");
        if (Files.exists(base)) {
            try (Stream<Path> s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(base);

        // ---- 1. label → 角色 ----
        check("label SYS ⇒ 系统卷", DiskVolumes.roleOf("SYS") == DiskVolumes.Role.SYSTEM);
        check("label boot ⇒ 系统卷（大小写/前缀都认）",
                DiskVolumes.roleOf("boot") == DiskVolumes.Role.SYSTEM);
        check("label home ⇒ 用户卷", DiskVolumes.roleOf("home") == DiskVolumes.Role.HOME);
        check("label USR ⇒ 程序卷", DiskVolumes.roleOf("USR") == DiskVolumes.Role.USR);
        check("label DATA ⇒ 数据卷", DiskVolumes.roleOf("DATA") == DiskVolumes.Role.DATA);
        check("认不出的 label ⇒ RAW（不静默当系统盘）",
                DiskVolumes.roleOf("WHATEVER") == DiskVolumes.Role.RAW);
        check("空 label ⇒ null（交给位置兜底）", DiskVolumes.roleOf("   ") == null);
        check("挂载点符合现代系统形态",
                "/usr".equals(DiskVolumes.Role.USR.mountPoint())
                        && "/home".equals(DiskVolumes.Role.HOME.mountPoint()));

        final long cap = 8L * 1024 * 1024;

        // ---- 2. 一块盘三个分区：**HOME 建在最前**（顺序故意与"第一个分区是系统"相反） ----
        final Path d1 = base.resolve("multi");
        DiskFormatTool.format(d1, cap, 2L * 1024 * 1024, "FAT12", "HOME");
        DiskFormatTool.format(d1, 0, 2L * 1024 * 1024, "FAT12", "SYS");
        DiskFormatTool.format(d1, 0, 1024L * 1024, "CRYPTAND", "USR");

        final List<DiskVolumes.Volume> vols = DiskVolumes.volumes(d1, cap, "d1", false);
        check("三个分区 = 三个卷", vols.size() == 3);
        check("每卷都挂上了", vols.stream().allMatch(DiskVolumes.Volume::available));
        check("角色认 label（SYS 与 HOME 都认出来了）",
                volumeOf(vols, DiskVolumes.Role.SYSTEM) != null
                        && volumeOf(vols, DiskVolumes.Role.HOME) != null);
        check("程序卷是 FOLDER 分区（CRYPTAND 文件树）",
                volumeOf(vols, DiskVolumes.Role.USR) != null
                        && volumeOf(vols, DiskVolumes.Role.USR).kind() == DiskPartitionTable.Kind.FOLDER);

        final DiskVolumes.Volume sysByLabel = DiskVolumes.system(d1, cap, "d1", false);
        check("系统卷 = label 为 SYS 的那一个（不是第一个分区）",
                sysByLabel != null && "SYS".equals(sysByLabel.label()));

        // ---- 3. 卷之间真隔离 + 重开持久 ----
        final DiskVolumes.Volume sys = volumeOf(vols, DiskVolumes.Role.SYSTEM);
        final DiskVolumes.Volume home = volumeOf(vols, DiskVolumes.Role.HOME);
        writeFile(sys.fs(), "/boot.txt", "system-partition");
        writeFile(home.fs(), "/user.txt", "home-partition");
        check("系统卷读到自己的文件", "system-partition".equals(readFile(sys.fs(), "/boot.txt")));
        check("用户卷读到自己的文件", "home-partition".equals(readFile(home.fs(), "/user.txt")));
        check("两个卷互相看不见（真分区隔离，不是同一个文件树）",
                !home.fs().exists("/boot.txt") && !sys.fs().exists("/user.txt"));

        final List<DiskVolumes.Volume> again = DiskVolumes.volumes(d1, cap, "d1", false);
        check("重开盘：系统卷内容仍在",
                "system-partition".equals(readFile(volumeOf(again, DiskVolumes.Role.SYSTEM).fs(), "/boot.txt")));
        check("重开盘：用户卷内容仍在",
                "home-partition".equals(readFile(volumeOf(again, DiskVolumes.Role.HOME).fs(), "/user.txt")));
        check("卷名可用于日志与节点地址",
                "part".equals(volumeOf(again, DiskVolumes.Role.HOME).name().substring(0, 4)));

        try (CryptandFileSystem viaOpen = DiskFileSystems.open(d1, cap, "d1", false)) {
            check("open() 给的也是系统卷（委托同一份选择逻辑，不是第一个分区）",
                    "system-partition".equals(readFile(viaOpen, "/boot.txt")));
        }

        // ---- 3.5 卷表（命令/MCP/日志共用同一份文本） ----
        final List<String> table = DiskVolumes.describe(vols);
        check("卷表：每个分区一行，带挂载点", table.size() == 3
                && table.stream().anyMatch(s -> s.contains("-> /home"))
                && table.stream().anyMatch(s -> s.contains("-> /usr")));
        check("卷表标明系统卷", table.stream().anyMatch(s -> s.contains("[系统卷]")));

        // ---- 4. 未格式化的系统分区：说明原因 + 明确失败，不回落 ----
        final Path d2 = base.resolve("unformatted");
        final DiskPartitionTable t2 = DiskPartitionTable.load(d2, 1024L * 1024);
        t2.create(DiskPartitionTable.Kind.IMAGE, 512L * 1024, "NONE", "SYS");
        final List<DiskVolumes.Volume> v2 = DiskVolumes.volumes(d2, 0, "d2", false);
        check("未格式化分区 ⇒ 占位卷不可用且带原因",
                v2.size() == 1 && !v2.get(0).available() && v2.get(0).note() != null);
        check("原因写明是『未格式化』（不是静默跳过）", v2.get(0).note().contains("not formatted"));
        boolean threw = false;
        try {
            DiskVolumes.system(d2, 0, "d2", false);
        } catch (IllegalStateException e) {
            threw = e.getMessage() != null && e.getMessage().contains("system volume unusable");
        }
        check("系统分区不可用 ⇒ 明确失败（不静默回落）", threw);
        check("卷表里也写明不可用原因（不是静默跳过）",
                DiskVolumes.describe(v2).get(0).contains("不可用"));

        // ---- 5. 无分区老盘 ⇒ 走"盘根 = 文件夹树"的老路 ----
        final Path d3 = base.resolve("legacy");
        Files.createDirectories(d3);
        check("无分区老盘：volumes() 为空", DiskVolumes.volumes(d3, 1024L * 1024, "d3", false).isEmpty());
        check("无分区老盘：system() = null（调用方回落文件夹树）",
                DiskVolumes.system(d3, 1024L * 1024, "d3", false) == null);
        try (CryptandFileSystem legacy = DiskFileSystems.open(d3, 1024L * 1024, "d3", false)) {
            writeFile(legacy, "/legacy.txt", "folder-tree");
            check("无分区老盘：open() 仍给文件夹树文件系统（老存档照常）",
                    Files.exists(d3.resolve("legacy.txt")));
        }

        // ---- 6. 命名空间：分区表 ⇒ 现代系统目录树（上层零改动就得到 /usr、/home） ----
        // ⚠ 用**同一批卷实例**建命名空间（of(vols) 重载）：每次重新枚举分区都会得到各自独立的
        //   文件系统实例，两块实例写同一块 FAT 镜像 ⇒ 互相看不见对方的写入。
        //   这也正是生产路径上"挂载必须有缓存"（OcDiskMounts.MOUNTED）的原因。
        try (VolumeNamespace ns = VolumeNamespace.of(vols)) {
            check("根目录能看见挂载点（合成目录项）",
                    hasEntry(ns.list("/"), "usr") && hasEntry(ns.list("/"), "home"));
            check("系统卷仍是根（/boot.txt 就在 / 下）",
                    "system-partition".equals(readFile(ns, "/boot.txt")));
            check("用户卷挂在 /home（按路径前缀路由）",
                    "home-partition".equals(readFile(ns, "/home/user.txt")));
            check("卷之间在命名空间里也互相看不见",
                    !ns.exists("/user.txt") && !ns.exists("/home/boot.txt"));
            check("挂载点是目录", ns.isDirectory("/home") && ns.isDirectory("/usr"));
            check("命名空间容量 = 各卷之和", ns.spaceTotal() > 2L * 1024 * 1024);

            writeFile(ns, "/home/via-ns.txt", "through-namespace");
            check("经命名空间写 ⇒ 真落在用户卷上",
                    "through-namespace".equals(readFile(home.fs(), "/via-ns.txt")));

            final int h = ns.open("/home/user.txt", FsMode.READ);
            final byte[] buf = new byte[(int) ns.getHandle(h).length()];
            final int n = ns.getHandle(h).read(buf);
            check("句柄跨卷工作（内容来自用户卷）",
                    n > 0 && "home-partition".equals(new String(buf, 0, n, StandardCharsets.UTF_8)));
            ns.getHandle(h).close();
            check("关闭句柄后失效（getHandle ⇒ null）", ns.getHandle(h) == null);

            boolean crossRefused = false;
            try {
                ns.rename("/boot.txt", "/home/moved.txt");
            } catch (FsException e) {
                crossRefused = true;
            }
            check("跨卷 rename 明确拒绝（不假成功）", crossRefused);
        }

        // ---- 生命周期契约：命名空间关闭后同样必须能被看出来（它就是多卷盘的缓存值）----
        final CryptandFileSystem nsLife = VolumeNamespace.of(DiskVolumes.volumes(d1, cap, "d1", false));
        check("生命周期: 命名空间关闭前 isOpen = true", nsLife.isOpen());
        nsLife.close();
        check("生命周期: 命名空间关闭后 isOpen = false", !nsLife.isOpen());

        System.out.println("[VOL] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static boolean hasEntry(String[] names, String name) {
        return names != null && java.util.Arrays.asList(names).contains(name + "/");
    }

    private static DiskVolumes.Volume volumeOf(List<DiskVolumes.Volume> vols, DiskVolumes.Role role) {
        for (final DiskVolumes.Volume v : vols) {
            if (v.role() == role) {
                return v;
            }
        }
        return null;
    }

    private static void writeFile(CryptandFileSystem fs, String path, String text) {
        final int h = fs.open(path, FsMode.WRITE);
        fs.getHandle(h).write(text.getBytes(StandardCharsets.UTF_8));
        fs.getHandle(h).close();
    }

    private static String readFile(CryptandFileSystem fs, String path) {
        if (!fs.exists(path)) {
            return "";
        }
        final int h = fs.open(path, FsMode.READ);
        final byte[] buf = new byte[(int) fs.getHandle(h).length()];
        final int n = fs.getHandle(h).read(buf);
        fs.getHandle(h).close();
        return new String(buf, 0, Math.max(0, n), StandardCharsets.UTF_8);
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
