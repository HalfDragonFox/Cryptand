package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ===== 分区表自测（离线，2026-09-18）=====
 *
 * <p>照用户定案：「可以分区多个分区就是多个不同文件，都在同一硬盘的下面」+
 * 「剩余空间是未格式化的」。覆盖：未分配计算、两类分区（镜像块设备 / 文件夹树）、
 * 超容量拒绝、disk.json 持久化往返、删分区后编号连续、卷标与格式可改。</p>
 *
 * <p>跑法：{@code gradlew :common:runPartitionTest}</p>
 */
public final class DiskPartitionSelfTest {

    private static int passed;
    private static int failed;

    private static final long MB = 1024L * 1024L;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Disk partition table self test, no MC ===");
        final Path tmp = Files.createTempDirectory("cryptand-part");
        try {
            final Path disk = tmp.resolve("disk-1");
            basics(disk);
            persistence(disk);
            removal(disk);
            blankStaysBlank(tmp);
        } finally {
            deleteRecursively(tmp);
        }
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** ① 建分区 / 未分配计算 / 两类分区 / 超容量 */
    private static void basics(Path disk) {
        final DiskPartitionTable t = DiskPartitionTable.load(disk, 8 * MB);
        check("新盘容量 = 8MB", t.capacityBytes() == 8 * MB, t.capacityBytes() + " bytes");
        check("新盘没有分区", t.partitions().isEmpty(), t.partitions().size() + " partitions");
        check("新盘未分配 = 容量", t.unallocatedBytes() == 8 * MB, t.unallocatedBytes() + " bytes");

        final DiskPartitionTable.Partition p1 =
                t.create(DiskPartitionTable.Kind.IMAGE, 4 * MB, "NONE", "boot");
        check("建镜像分区 #1（4MB, 未格式化）", p1.index() == 1
                && p1.kind() == DiskPartitionTable.Kind.IMAGE && p1.sizeBytes() == 4 * MB,
                p1.toString());
        check("未分配 = 容量 − 4MB", t.unallocatedBytes() == 4 * MB, t.unallocatedBytes() + " bytes");
        check("分区目录 part1/ 已建出", Files.isDirectory(t.partitionDir(1)), t.partitionDir(1).toString());

        final DiskPartitionTable.Partition p2 =
                t.create(DiskPartitionTable.Kind.FOLDER, 1 * MB, "NONE", "home");
        check("建文件夹分区 #2（1MB）", p2.index() == 2
                && p2.kind() == DiskPartitionTable.Kind.FOLDER, p2.toString());
        check("两个分区起点顺序分配（#2 从 4MB 开始）", p2.startBytes() == 4 * MB, p2.startBytes() + " bytes");
        check("未分配 = 容量 − 5MB", t.unallocatedBytes() == 3 * MB, t.unallocatedBytes() + " bytes");

        check("超容量建分区 ⇒ ERR_NO_SPACE",
                errCode(() -> t.create(DiskPartitionTable.Kind.IMAGE, 4 * MB, "NONE", "too-big"),
                        OcAbi.ERR_NO_SPACE), "ERR_NO_SPACE");
        check("大小 <= 0 ⇒ ERR_BAD_ARGS",
                errCode(() -> t.create(DiskPartitionTable.Kind.IMAGE, 0, "NONE", "zero"), OcAbi.ERR_BAD_ARGS),
                "ERR_BAD_ARGS");
        check("disk.json 已落盘", Files.isRegularFile(disk.resolve(DiskPartitionTable.META_FILE)),
                disk.resolve(DiskPartitionTable.META_FILE).toString());
    }

    /** ② 持久化往返：分区表要能原样读回来（盘是物品，换机器插回来必须一致） */
    private static void persistence(Path disk) {
        final DiskPartitionTable t = DiskPartitionTable.load(disk, 8 * MB);
        check("重新载入后容量不变（以文件为准）", t.capacityBytes() == 8 * MB, t.capacityBytes() + "");
        final List<DiskPartitionTable.Partition> ps = t.partitions();
        check("重新载入后仍是 2 个分区", ps.size() == 2, ps.size() + " partitions");
        check("分区 #1 类型/大小/卷标都对",
                ps.get(0).kind() == DiskPartitionTable.Kind.IMAGE
                        && ps.get(0).sizeBytes() == 4 * MB
                        && "boot".equals(ps.get(0).label()),
                ps.get(0).toString());
        check("分区 #2 类型/卷标都对",
                ps.get(1).kind() == DiskPartitionTable.Kind.FOLDER
                        && "home".equals(ps.get(1).label()),
                ps.get(1).toString());

        check("update 改格式与卷标", t.update(1, "FAT12", "BOOT"));
        final DiskPartitionTable again = DiskPartitionTable.load(disk, 8 * MB);
        check("改完落盘生效（format=FAT12 label=BOOT）",
                "FAT12".equals(again.get(1).format()) && "BOOT".equals(again.get(1).label()),
                again.get(1).format() + "/" + again.get(1).label());
    }

    /** ③ 删分区：目录一起删、编号保持连续、未分配回落 */
    private static void removal(Path disk) {
        final DiskPartitionTable t = DiskPartitionTable.load(disk, 8 * MB);
        check("删 #1（镜像分区）", t.remove(1));
        check("删完未分配 = 8MB − 1MB", t.unallocatedBytes() == 7 * MB, t.unallocatedBytes() + " bytes");
        check("只剩 1 个分区且编号重排为 1",
                t.partitions().size() == 1 && t.partitions().get(0).index() == 1,
                t.partitions().toString());
        check("原 #2 的类型仍是 FOLDER（内容语义没丢）",
                t.partitions().get(0).kind() == DiskPartitionTable.Kind.FOLDER,
                t.partitions().get(0).kind().toString());
        check("旧 part2/ 目录已消失", !Files.exists(disk.resolve("part2")), disk.resolve("part2").toString());
        check("删不存在的分区返回 false", !t.remove(9), "false");

        final DiskPartitionTable reloaded = DiskPartitionTable.load(disk, 8 * MB);
        check("删完的编号也落盘了", reloaded.partitions().size() == 1
                && reloaded.partitions().get(0).index() == 1, reloaded.partitions().toString());
    }

    // ==================== 工具 ====================

    /** 不带细节的断言（免得每处都补一个空串） */
    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : "  " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }

    /**
     * ④ 空盘保持空（用户 2026-09-26："<b>空软盘创建时为空</b>"）
     *
     * <p>读路径（挂载 / 引导扫描 / 只读查看）<b>不允许</b>在盘上留下任何东西。
     * 早先 {@code DiskPartitionTable.load()} 在缺 {@code disk.json} 时会 {@code save()}，
     * 于是"把一块空盘插进机箱"这一下就写出了一个分区表文件：空盘不再空，而且所有
     * {@code Files.isRegularFile(…/disk.json)} 的"这块盘初始化过没有"判断<b>恒为真</b>
     * （程序加载器的"空盘自动格式化"因此永远不触发）。</p>
     */
    private static void blankStaysBlank(Path tmp) {
        final Path blank = tmp.resolve("blank-floppy");
        final DiskPartitionTable t = DiskPartitionTable.load(blank, 1 * MB);
        check("空盘载入后没有分区", t.partitions().isEmpty(), t.partitions().size() + " partitions");
        check("空盘载入后未分配 = 容量", t.unallocatedBytes() == 1 * MB, t.unallocatedBytes() + " bytes");
        check("空盘：读路径不写 disk.json",
                !Files.isRegularFile(blank.resolve(DiskPartitionTable.META_FILE)), blank.toString());
        long files = -1;
        try (Stream<Path> walk = Files.walk(blank)) {
            files = walk.filter(Files::isRegularFile).count();
        } catch (Exception e) {
            check("空盘目录可遍历", false, e.toString());
        }
        check("空盘目录里一个文件都没有（只有目录）", files == 0, files + " files");

        final CryptandFileSystem fs = DiskFileSystems.open(blank, 1 * MB, "blank-addr", false);
        check("空盘按文件夹树打开（内容由用户决定）", fs != null, "opened");
        check("打开之后依然没有 disk.json",
                !Files.isRegularFile(blank.resolve(DiskPartitionTable.META_FILE)), blank.toString());

        // 分区表只能由**显式动作**创建 —— 这才是"这块盘被格式化过"的唯一来源
        DiskPartitionTable.load(blank, 1 * MB)
                .create(DiskPartitionTable.Kind.FOLDER, 1 * MB, "NONE", "sys");
        check("显式建分区之后 disk.json 才出现",
                Files.isRegularFile(blank.resolve(DiskPartitionTable.META_FILE)), blank.toString());
    }

    private static boolean errCode(Runnable r, int expected) {
        try {
            r.run();
            return false;
        } catch (FsException e) {
            return e.errCode() == expected;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
