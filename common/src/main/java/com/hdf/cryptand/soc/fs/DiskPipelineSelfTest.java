package com.hdf.cryptand.soc.fs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * ===== 盘全链路沙盒自测（纯 Java，零 MC）=====
 *
 * <p>用户："格式化可以写成单独库，然后沙盒跑一遍，客户端太慢了" —— 这个闸门就是那一遍：
 * <b>盘目录 → 分区表 → 格式化 → 挂载分派 → 文件读写 → 重开持久</b>，
 * 全程不需要 Minecraft，跑一次十几秒。</p>
 *
 * <p>它验证的是"真机也会走的那份代码"：分派逻辑 {@link DiskFileSystems} 与格式化
 * {@link DiskFormatTool} 都在 common —— MC 侧只把存档与盘地址翻成目录路径。
 * 所以这里过了，真机上只剩"路径对不对"这一个变量。</p>
 */
public final class DiskPipelineSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        final Path base = Path.of("build", "tmp", "disk-pipeline");
        if (Files.exists(base)) {
            try (Stream<Path> s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(base);

        // ==================== 1. 老盘（没有分区）⇒ 文件夹树，行为不变 ====================
        final Path old = base.resolve("legacy");
        try (CryptandFileSystem fs = DiskFileSystems.open(old, 4L * 1024 * 1024, "addr-legacy", false)) {
            check("老盘（无分区）⇒ 文件夹树", fs instanceof DiskFileSystem);
            fs.open("/hello.txt", FsMode.WRITE);
            try (CryptandFileSystem ignored = fs) {
                // 写内容（句柄模式：写 → close 落盘）
            }
        }
        final Path oldFile = old.resolve("hello.txt");
        check("文件夹树写出来的就是宿主上的真实文件", Files.isRegularFile(oldFile));
        // ⚠ 反过来断言（2026-09-26 修正）：打开一块盘**不允许**在盘上留下任何东西。
        //   老断言写的是"同时生成了 disk.json"—— 那正是"读路径写盘"这个错误的固化：
        //   有了它，"插上机器"就等于"盘被写过"，空盘永远不空。
        check("打开老盘**不**产生 disk.json（读路径不写盘）",
                !Files.isRegularFile(old.resolve(DiskPartitionTable.META_FILE)));

        // ==================== 2. 格式化 ⇒ IMAGE + FAT12，挂载走 FAT ====================
        final Path fat = base.resolve("fat-disk");
        final long cap = 4L * 1024 * 1024;                       // 4MB ⇒ autoFat 推荐 FAT12
        final String desc = DiskFormatTool.format(fat, cap, cap, "", "SYS");
        check("格式化返回描述", desc.contains("FAT12") && desc.contains("4096KB"));
        check("分区目录已建", Files.isDirectory(fat.resolve("part1")));
        check("分区里是块文件（不是文件树）", Files.isDirectory(fat.resolve("part1").resolve("blocks")));

        try (CryptandFileSystem fs = DiskFileSystems.open(fat, cap, "addr-fat", false)) {
            check("IMAGE+FAT 分区 ⇒ FatFileSystem", fs instanceof FatFileSystem);
            check("容量 = 分区大小", fs.spaceTotal() == cap);
            // 新格式化的盘"已用 = 0"是对的：FAT 表/根目录属于文件系统元数据，不计入文件占用
            final long used0 = fs.spaceUsed();
            check("新格式化盘已用 = 0（元数据不算文件占用）", used0 == 0);

            final int h = fs.open("/note.txt", FsMode.WRITE);
            fs.getHandle(h).write("hello fat disk".getBytes(StandardCharsets.UTF_8));
            fs.getHandle(h).close();
            check("写入后 exists", fs.exists("/note.txt"));
            check("15 字节文件占用正好 1 簇（4KB）", fs.spaceUsed() == used0 + 4096);
            check("目录可见", java.util.Arrays.asList(fs.list("/")).contains("note.txt"));
            fs.makeDirectory("/docs");
            final int h2 = fs.open("/docs/a.bin", FsMode.WRITE);
            fs.getHandle(h2).write(new byte[]{7, 8, 9});
            fs.getHandle(h2).close();
            check("子目录可写", fs.exists("/docs/a.bin"));
        }

        // 重开（新实例、新 FAT 卷）⇒ 证明数据真在盘上
        try (CryptandFileSystem fs = DiskFileSystems.open(fat, cap, "addr-fat", false)) {
            check("重开仍是 FatFileSystem", fs instanceof FatFileSystem);
            check("重开后文件还在", fs.exists("/note.txt") && fs.exists("/docs/a.bin"));
            final int h = fs.open("/note.txt", FsMode.READ);
            final byte[] buf = new byte[32];
            final int n = fs.getHandle(h).read(buf);
            check("重开后内容一致", new String(buf, 0, n, StandardCharsets.UTF_8).equals("hello fat disk"));
            fs.getHandle(h).close();
        }

        // ==================== 3. 未格式化的 IMAGE 分区 ⇒ 明确失败，不回落 ====================
        final Path raw = base.resolve("raw-disk");
        DiskFormatTool.format(raw, cap, cap, "RAW", "RAWDISK");
        boolean refused = false;
        try (CryptandFileSystem ignored = DiskFileSystems.open(raw, cap, "addr-raw", false)) {
            refused = false;
        } catch (IllegalStateException e) {
            refused = e.getMessage().contains("not formatted");
        } catch (FsException e) {
            refused = false;
        }
        check("未格式化分区 ⇒ 明确失败（不回落文件夹树）", refused);

        // ==================== 4. FOLDER 分区 ⇒ 文件树，且玩家能直接看到 ====================
        final Path folder = base.resolve("folder-disk");
        DiskFormatTool.format(folder, cap, cap, "CRYPTAND", "FOLDERVOL");
        try (CryptandFileSystem fs = DiskFileSystems.open(folder, cap, "addr-folder", false)) {
            check("FOLDER 分区 ⇒ DiskFileSystem", fs instanceof DiskFileSystem);
            final int h = fs.open("/visible.txt", FsMode.WRITE);
            fs.getHandle(h).write("i am a real file".getBytes(StandardCharsets.UTF_8));
            fs.getHandle(h).close();
        }
        check("FOLDER 分区的文件在宿主上直接可见（part1/visible.txt）",
                Files.isRegularFile(folder.resolve("part1").resolve("visible.txt")));
        check("FOLDER 分区写入卷标文件", Files.isRegularFile(folder.resolve("part1").resolve(DiskFormatter.LABEL_FILE)));

        // ==================== 5. 容量 / 类型的硬约束 ====================
        boolean badType = false;
        try {
            DiskFormatTool.format(base.resolve("tiny"), 256L * 1024, 256L * 1024, "FAT32", "X");
        } catch (FsException e) {
            badType = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_ARGS;
        }
        check("256KB 盘选 FAT32 ⇒ ERR_BAD_ARGS（不静默降级）", badType);

        boolean tooBig = false;
        try {
            DiskFormatTool.format(base.resolve("small"), 1024L * 1024, 8L * 1024 * 1024, "FAT12", "X");
        } catch (FsException e) {
            tooBig = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_NO_SPACE;
        }
        check("1MB 盘要 8MB 分区 ⇒ ERR_NO_SPACE", tooBig);

        boolean unknownCap = false;
        try {
            DiskFormatTool.format(base.resolve("nometa"), 0, 256L * 1024, "FAT12", "X");
        } catch (IllegalArgumentException e) {
            unknownCap = e.getMessage().contains("unknown disk capacity");
        }
        check("容量未知（无 disk.json 又没给容量）⇒ 报错而不是猜", unknownCap);

        // ==================== 6. 分区表账目自洽 ====================
        final DiskPartitionTable t = DiskPartitionTable.load(folder, cap);
        check("分区表 1 个分区", t.partitions().size() == 1);
        check("未分配 = 容量 − Σ分区", t.unallocatedBytes() == cap - t.allocatedBytes());
        check("分区类型 = FOLDER", t.partitions().get(0).kind() == DiskPartitionTable.Kind.FOLDER);
        final DiskPartitionTable t2 = DiskPartitionTable.load(fat, cap);
        check("FAT 盘分区类型 = IMAGE 且 format=FAT12",
                t2.partitions().get(0).kind() == DiskPartitionTable.Kind.IMAGE
                        && t2.partitions().get(0).format().equals("FAT12"));

        // ==================== 7. 只读盘（锁定的软盘）：写操作一律被拒 ====================
        //
        // 语义来自 OC 源码（DriveData.scala：盘的"只读"就是"锁定"），默认值来自用户决定
        // （"软盘不能写只读"）。底层在两套文件系统里都要成立 —— 这里 FAT 与文件夹树各测一遍。
        final Path roFat = base.resolve("readonly-fat");
        DiskFormatTool.format(roFat, cap, cap, "FAT12", "ROVOL");
        try (CryptandFileSystem fs = DiskFileSystems.open(roFat, cap, "ro-fat", true)) {
            check("只读 FAT 盘：isReadOnly()=true", fs.isReadOnly());
            check("只读盘 spaceTotal()==0（照 OC：只读系统返回 0）", fs.spaceTotal() == 0);
            boolean writeRefused = false;
            try {
                fs.open("/x.txt", FsMode.WRITE);
            } catch (FsException e) {
                writeRefused = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_READ_ONLY;
            }
            check("只读盘 open(WRITE) ⇒ ERR_READ_ONLY", writeRefused);
            check("只读盘 makeDirectory ⇒ false", !fs.makeDirectory("/adir"));
            check("只读盘 delete ⇒ false", !fs.delete("/nope.txt"));
            check("只读盘 rename ⇒ false", !fs.rename("/a.txt", "/b.txt"));
            check("只读盘仍可读（list 不为 null）", fs.list("/") != null);
        }
        final Path roFolder = base.resolve("readonly-folder");
        try (CryptandFileSystem fs = DiskFileSystems.open(roFolder, cap, "ro-folder", true)) {
            check("只读文件夹树：isReadOnly()=true", fs.isReadOnly());
            boolean writeRefused = false;
            try {
                fs.open("/y.txt", FsMode.WRITE);
            } catch (FsException e) {
                writeRefused = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_READ_ONLY;
            }
            check("只读文件夹树 open(WRITE) ⇒ ERR_READ_ONLY", writeRefused);
        }

        System.out.println("[PIPE] " + passed + "/" + (passed + failed) + " checks passed");
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
