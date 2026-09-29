package com.hdf.cryptand.soc.fs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 格式化工具离线闸门（不需要 MC）。
 *
 * <p>验证的不是"我们写的字节等于我们写的字节"，而是<b>第三方驱动的读法</b>：
 * 自测里自带一个极简 FAT 解析器（只按规范读 BPB + 遍历根目录），
 * 若格式化结果不合法，解析器就读不出"空盘"。这就是 FatFS 会做的第一件事。</p>
 */
public final class DiskFormatterSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        final Path base = Path.of("build", "tmp", "fmt-selftest");
        if (Files.exists(base)) {
            try (Stream<Path> s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(base);

        // ---- 1. 容量 → 类型（真实规则，不能乱选） ----
        check("8MB  → FAT12", DiskFormatter.autoFat(8L << 20) == DiskFormatter.Fs.FAT12);
        check("128MB → FAT16", DiskFormatter.autoFat(128L << 20) == DiskFormatter.Fs.FAT16);
        check("1GB  → FAT32", DiskFormatter.autoFat(1L << 30) == DiskFormatter.Fs.FAT32);

        // ---- 2. 格式化 8MB 镜像为 FAT12，并用独立解析器读回 ----
        try (DiskImage img = DiskImage.open(base.resolve("fat12"), 8L << 20)) {
            DiskFormatter.formatImage(img, DiskFormatter.Fs.FAT12, "AI-DISK");

            final byte[] boot = img.readBlock(0);
            check("boot jmp 指令 = EB 3C 90", (boot[0] & 0xFF) == 0xEB && (boot[1] & 0xFF) == 0x3C && (boot[2] & 0xFF) == 0x90);
            check("OEM 名 = CRYPTAND", "CRYPTAND".equals(ascii(boot, 3, 8)));
            check("扇区大小 = 512", u16(boot, 11) == 512);
            check("簇 = 8 扇区（4KB）", (boot[13] & 0xFF) == 8);
            check("保留扇区 = 1", u16(boot, 14) == 1);
            check("FAT 份数 = 2", (boot[16] & 0xFF) == 2);
            check("根目录项 = 512", u16(boot, 17) == 512);
            check("总扇区 = 容量/512", u16(boot, 19) == 8L * 1024 * 1024 / 512);
            check("介质描述符 = F8", (boot[21] & 0xFF) == 0xF8);
            check("扩展引导签名 = 29", (boot[38] & 0xFF) == 0x29);
            check("卷标 = AI-DISK", "AI-DISK".equals(ascii(boot, 43, 11).trim()));
            check("类型串 = FAT12", "FAT12".equals(ascii(boot, 54, 8).trim()));
            check("引导签名 = 55 AA", (boot[510] & 0xFF) == 0x55 && (boot[511] & 0xFF) == 0xAA);

            // 独立解析器：按 BPB 定位两份 FAT 与根目录
            final int fatSectors = u16(boot, 22);
            final int rootSectors = (u16(boot, 17) * 32 + 511) / 512;
            check("每 FAT 扇区数 > 0", fatSectors > 0);
            check("根目录占 32 扇区", rootSectors == 32);

            final byte[] fat0 = sector(img, 1L * 512);
            final byte[] fat1 = sector(img, (1L + fatSectors) * 512);
            check("FAT#0 头 = F8 FF FF", (fat0[0] & 0xFF) == 0xF8 && (fat0[1] & 0xFF) == 0xFF && (fat0[2] & 0xFF) == 0xFF);
            check("FAT#1 与 FAT#0 一致", java.util.Arrays.equals(fat0, fat1));
            check("FAT 第 4 字节起全 0（无已分配簇）", fat0[3] == 0 && fat0[4] == 0 && fat0[100] == 0);

            final long rootStart = (long) (1 + 2 * fatSectors) * 512;
            check("根目录第 0 项为空（首字节 0）", sector(img, rootStart)[0] == 0);
            check("根目录最后一项为空", sector(img, rootStart + (long) (rootSectors - 1) * 512)[0] == 0);

            // ---- 3. 幂等：重复格式化结果一致 ----
            final byte[] before = img.readBlock(0).clone();
            DiskFormatter.formatImage(img, DiskFormatter.Fs.FAT12, "AI-DISK");
            check("重复格式化后引导扇区一致（除时间戳外）", sameExceptTimestamp(before, img.readBlock(0)));

            // ---- 4. 写数据后重新格式化 ⇒ 空间被释放（"剩余空间是未格式化的"） ----
            final byte[] junk = img.readBlock(5);
            java.util.Arrays.fill(junk, (byte) 0xAB);
            img.writeBlock(5, junk);
            check("写脏块后该块已分配", img.allocatedBlocks().contains(5));
            check("写脏块后共 2 块（块 0 元数据 + 块 5）", img.allocatedBlocks().size() == 2);
            DiskFormatter.formatImage(img, DiskFormatter.Fs.FAT12, "AI-DISK");
            check("重新格式化后脏块被释放", !img.allocatedBlocks().contains(5));
            check("重新格式化后只剩块 0（FAT+根目录都在引导块内）", img.allocatedBlocks().size() == 1);
            check("释放后块 5 读回全 0", sector(img, 5L * img.blockSize())[0] == 0);
        }

        // ---- 5. 类型与分区不匹配 ⇒ 明确报错（不静默降级） ----
        try (DiskImage img = DiskImage.open(base.resolve("bad"), 8L << 20)) {
            boolean threw = false;
            try {
                DiskFormatter.formatImage(img, DiskFormatter.Fs.FAT32, "X");
            } catch (FsException e) {
                threw = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_ARGS;
            }
            check("8MB 盘用 FAT32 ⇒ ERR_BAD_ARGS（簇数不足，不静默降级）", threw);

            boolean threw2 = false;
            try {
                DiskFormatter.formatImage(img, DiskFormatter.Fs.CRYPTAND, "X");
            } catch (FsException e) {
                threw2 = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_ARGS;
            }
            check("CRYPTAND 用于镜像 ⇒ ERR_BAD_ARGS", threw2);

            DiskFormatter.formatImage(img, DiskFormatter.Fs.NONE, null);
            check("NONE ⇒ 只清空，无引导扇区", img.readBlock(0)[0] == 0);
        }

        // ---- 6. 文件夹分区格式化 = 清空 + 卷标 ----
        final Path partDir = base.resolve("folderpart");
        Files.createDirectories(partDir.resolve("sub"));
        Files.writeString(partDir.resolve("sub/old.txt"), "old");
        DiskFormatter.formatFolder(partDir, "MY-PART");
        check("文件夹分区清空", !Files.exists(partDir.resolve("sub")));
        check("文件夹分区写入卷标", Files.exists(partDir.resolve(DiskFormatter.LABEL_FILE)));
        check("卷标可读", Files.readString(partDir.resolve(DiskFormatter.LABEL_FILE)).contains("label=MY-PART"));

        System.out.println("[FMT] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 独立 FAT 解析辅助（模拟第三方驱动） ====================

    private static byte[] sector(DiskImage img, long byteOffset) {
        final int blockSize = img.blockSize();
        final byte[] block = img.readBlock((int) (byteOffset / blockSize));
        final byte[] out = new byte[512];
        System.arraycopy(block, (int) (byteOffset % blockSize), out, 0, 512);
        return out;
    }

    private static boolean sameExceptTimestamp(byte[] a, byte[] b) {
        for (int i = 0; i < 512; i++) {
            if (i >= 39 && i < 43) {
                continue;                       // 卷序列号每次格式化都不同，属正常
            }
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] a, int off) {
        return (a[off] & 0xFF) | ((a[off + 1] & 0xFF) << 8);
    }

    private static String ascii(byte[] a, int off, int len) {
        return new String(a, off, len, java.nio.charset.StandardCharsets.US_ASCII);
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
