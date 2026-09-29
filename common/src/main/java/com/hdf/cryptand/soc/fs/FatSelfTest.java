package com.hdf.cryptand.soc.fs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * FAT 读写驱动离线闸门（不需要 MC）。
 *
 * <p>三件事必须被证明，否则"格式化 + MCU 用 FatFS 读"这条链路就不能算通：</p>
 * <ol>
 *   <li><b>自洽</b>：写进去的东西自己读得回来（含跨簇文件、LFN 长名、子目录）</li>
 *   <li><b>持久</b>：关掉再打开，内容还在（证明"写穿"是真的落盘了，不是只在缓存里对着自己笑）</li>
 *   <li><b>三类型</b>：FAT12/16/32 用同一套代码路径，表项位宽不同但语义一致</li>
 * </ol>
 */
public final class FatSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        final Path base = Path.of("build", "tmp", "fat-selftest");
        if (Files.exists(base)) {
            try (Stream<Path> s = Files.walk(base)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(base);

        runCase("FAT12-8MB", base.resolve("fat12"), 8L << 20, DiskFormatter.Fs.FAT12, FatVolume.FatType.FAT12);
        runCase("FAT16-128MB", base.resolve("fat16"), 128L << 20, DiskFormatter.Fs.FAT16, FatVolume.FatType.FAT16);
        runCase("FAT32-1GB", base.resolve("fat32"), 1L << 30, DiskFormatter.Fs.FAT32, FatVolume.FatType.FAT32);

        // ---- 生命周期契约：关闭后必须能被"缓存方"看出来（挂载表靠 isOpen 判缓存是否还有效）----
        try (DiskImage img = DiskImage.open(base.resolve("lifecycle"), 4L << 20)) {
            DiskFormatter.formatImage(img, DiskFormatter.Fs.FAT12, "LIFECYCLE");
            try (FatVolume vol = FatVolume.mount(img)) {
                final FatFileSystem fs = new FatFileSystem(vol);
                check("生命周期: 关闭前 isOpen = true", fs.isOpen());
                fs.close();
                check("生命周期: 关闭后 isOpen = false", !fs.isOpen());
                boolean bad = false;
                try {
                    fs.open("/x.txt", FsMode.WRITE);
                } catch (FsException e) {
                    bad = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_HANDLE;
                }
                check("生命周期: 关闭后再 open ⇒ ERR_BAD_HANDLE", bad);
            }
        }

        System.out.println("[FAT] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 每个类型都跑同一套操作 —— 这样"某个类型特有问题"一定会暴露成某组 FAIL */
    private static void runCase(String tag, Path dir, long capacity, DiskFormatter.Fs fmt, FatVolume.FatType want) {
        try (DiskImage img = DiskImage.open(dir, capacity)) {
            DiskFormatter.formatImage(img, fmt, "TESTVOL");
            try (FatVolume vol = FatVolume.mount(img)) {
                check(tag + ": 类型识别", vol.type() == want);
                check(tag + ": 根目录初始为空", vol.list("/").isEmpty());
                check(tag + ": 初始空闲 ≈ 全部簇（FAT32 根目录自己占一簇）",
                        vol.freeClusters() >= vol.clusterCount() - 1);

                // ---- 8.3 短名 ----
                final byte[] hello = "hello fat".getBytes(StandardCharsets.UTF_8);
                final long free0 = vol.freeClusters();
                vol.write("/HELLO.TXT", hello);
                check(tag + ": 写后 exists", vol.exists("/HELLO.TXT"));
                check(tag + ": 读回内容", java.util.Arrays.equals(vol.read("/HELLO.TXT"), hello));
                check(tag + ": size 正确", vol.size("/HELLO.TXT") == hello.length);
                check(tag + ": 大小写不敏感", java.util.Arrays.equals(vol.read("/hello.txt"), hello));
                check(tag + ": list 能看到", vol.list("/").size() == 1
                        && vol.list("/").get(0).name().equalsIgnoreCase("HELLO.TXT"));
                check(tag + ": 写入精确占用一个簇", vol.freeClusters() == free0 - 1);

                // ---- LFN 长名（含空格与小写）----
                final byte[] lfndata = "long name payload".getBytes(StandardCharsets.UTF_8);
                vol.write("/Config Backup 2026.txt", lfndata);
                check(tag + ": LFN exists", vol.exists("/Config Backup 2026.txt"));
                check(tag + ": LFN 读回", java.util.Arrays.equals(vol.read("/Config Backup 2026.txt"), lfndata));
                check(tag + ": LFN 原名列出", names(vol.list("/")).contains("Config Backup 2026.txt"));

                // ---- 子目录 ----
                vol.mkdir("/sub");
                check(tag + ": 子目录 exists", vol.isDirectory("/sub"));
                vol.write("/sub/inner.bin", new byte[]{1, 2, 3, 4, 5});
                check(tag + ": 子目录文件读回", java.util.Arrays.equals(vol.read("/sub/inner.bin"), new byte[]{1, 2, 3, 4, 5}));
                check(tag + ": 子目录 list", names(vol.list("/sub")).contains("inner.bin"));
                check(tag + ": 根目录不含子目录内容", names(vol.list("/")).size() == 3);

                // ---- 跨簇大文件（默认 4KB 簇 ⇒ 20KB 需要 5 个簇）----
                final byte[] big = new byte[20 * 1024];
                for (int i = 0; i < big.length; i++) {
                    big[i] = (byte) (i * 31 + 7);
                }
                vol.write("/big.dat", big);
                check(tag + ": 大文件读回一致（跨簇链）", java.util.Arrays.equals(vol.read("/big.dat"), big));
                check(tag + ": 大文件占用 5 簇", vol.freeClusters() <= vol.clusterCount() - 6);
                // 覆盖写更小内容 ⇒ 旧链必须释放干净（否则空间泄漏）
                final long freeBefore = vol.freeClusters();
                vol.write("/big.dat", new byte[]{9});
                check(tag + ": 覆盖后旧簇被释放", vol.freeClusters() >= freeBefore + 4);
                check(tag + ": 覆盖后内容正确", java.util.Arrays.equals(vol.read("/big.dat"), new byte[]{9}));

                // ---- 删除 ----
                final long freeBeforeRemove = vol.freeClusters();
                vol.remove("/HELLO.TXT");
                check(tag + ": 删除后不存在", !vol.exists("/HELLO.TXT"));
                check(tag + ": 删除后簇回收", vol.freeClusters() == freeBeforeRemove + 1);
                boolean notEmpty = false;
                try {
                    vol.remove("/sub");
                } catch (FsException e) {
                    notEmpty = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_ARGS;
                }
                check(tag + ": 非空目录删除被拒", notEmpty);
                vol.remove("/sub/inner.bin");
                vol.remove("/sub");
                check(tag + ": 清空后可删目录", !vol.exists("/sub"));

                // ---- 错误路径 ----
                boolean notFound = false;
                try {
                    vol.read("/nope.txt");
                } catch (FsException e) {
                    notFound = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_NOT_FOUND;
                }
                check(tag + ": 读不存在的文件 ⇒ ERR_NOT_FOUND", notFound);
                boolean parentMissing = false;
                try {
                    vol.write("/nodir/file.txt", new byte[]{1});
                } catch (FsException e) {
                    parentMissing = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_NOT_FOUND;
                }
                check(tag + ": 父目录不存在 ⇒ ERR_NOT_FOUND", parentMissing);

                // ---- CryptandFileSystem 句柄契约（OC / ABI 都走这层）----
                try (FatFileSystem fs = new FatFileSystem(vol)) {
                    check(tag + ": fs 容量/已用", fs.spaceTotal() == capacity && fs.spaceUsed() > 0);
                    final int h = fs.open("/Config Backup 2026.txt", FsMode.READ);
                    final CryptandFileSystem.Handle hh = fs.getHandle(h);
                    check(tag + ": 句柄 length", hh.length() == lfndata.length);
                    final byte[] buf = new byte[4];
                    check(tag + ": 句柄 read", hh.read(buf) == 4 && new String(buf, StandardCharsets.UTF_8).equals("long"));
                    check(tag + ": 句柄 seek 到 5 再读", hh.seek(5) == 5 && hh.read(new byte[9]) == 9);
                    // 还剩 3 字节：先返回 3，再下一次才是 -1（读到结尾返回 -1，没到就返回实际字节数）
                    check(tag + ": 读到底部返回剩余字节数", hh.read(new byte[8]) == 3);
                    check(tag + ": 再读返回 -1（OC 契约）", hh.read(new byte[8]) == -1);
                    hh.close();

                    final int hw = fs.open("/WRITE.TXT", FsMode.WRITE);
                    fs.getHandle(hw).write("abcd".getBytes(StandardCharsets.UTF_8));
                    fs.getHandle(hw).close();
                    check(tag + ": 句柄写入落盘", vol.exists("/WRITE.TXT")
                            && new String(vol.read("/WRITE.TXT"), StandardCharsets.UTF_8).equals("abcd"));

                    final int ha = fs.open("/WRITE.TXT", FsMode.APPEND);
                    fs.getHandle(ha).write("EF".getBytes(StandardCharsets.UTF_8));
                    fs.getHandle(ha).close();
                    check(tag + ": append 追加到末尾", new String(vol.read("/WRITE.TXT"), StandardCharsets.UTF_8).equals("abcdEF"));

                    // append 模式即使被 seek 过，写也只是追加（OC 语义）
                    final int ha2 = fs.open("/WRITE.TXT", FsMode.READ_APPEND);
                    fs.getHandle(ha2).seek(0);
                    fs.getHandle(ha2).write("!".getBytes(StandardCharsets.UTF_8));
                    fs.getHandle(ha2).close();
                    check(tag + ": READ_APPEND 忽略 seek 仍追加",
                            new String(vol.read("/WRITE.TXT"), StandardCharsets.UTF_8).equals("abcdEF!"));

                    check(tag + ": list/mkdir 契约", fs.makeDirectory("/fsdir")
                            && java.util.Arrays.asList(fs.list("/")).contains("fsdir"));
                    check(tag + ": list 不存在的目录 ⇒ null", fs.list("/nope") == null);
                    check(tag + ": lastModified > 0", fs.lastModified("/WRITE.TXT") > 0);
                    check(tag + ": setLastModified 明确不支持", !fs.setLastModified("/WRITE.TXT", 1L));
                    check(tag + ": delete 契约", fs.delete("/fsdir") && !fs.exists("/fsdir"));
                    check(tag + ": rename 契约", fs.rename("/WRITE.TXT", "/RENAMED.TXT")
                            && fs.exists("/RENAMED.TXT") && !fs.exists("/WRITE.TXT"));

                    boolean badHandle = false;
                    try {
                        fs.getHandle(999);
                    } catch (FsException e) {
                        badHandle = e.errCode() == com.hdf.cryptand.soc.oc.OcAbi.ERR_BAD_HANDLE;
                    }
                    check(tag + ": 坏句柄 ⇒ ERR_BAD_HANDLE", badHandle);
                }
            }
        } catch (Exception e) {
            check(tag + ": 未抛异常（" + e + "）", false);
            return;
        }

        // ---- 重新挂载：证明数据真的在盘上 ----
        try (DiskImage img = DiskImage.open(dir, capacity); FatVolume vol = FatVolume.mount(img)) {
            check(tag + ": 重开后类型识别", vol.type() == want);
            check(tag + ": 重开后 LFN 文件还在", vol.exists("/Config Backup 2026.txt"));
            check(tag + ": 重开后内容一致", new String(vol.read("/Config Backup 2026.txt"), StandardCharsets.UTF_8)
                    .equals("long name payload"));
            // 重开后应剩 Config Backup 2026.txt 与被覆盖成 1 字节的 big.dat
            check(tag + ": 重开后目录结构完整", names(vol.list("/")).size() == 3
                    && names(vol.list("/")).contains("Config Backup 2026.txt")
                    && names(vol.list("/")).contains("RENAMED.TXT")
                    && names(vol.list("/")).contains("big.dat"));
            check(tag + ": 重开后小文件内容正确", java.util.Arrays.equals(vol.read("/big.dat"), new byte[]{9}));
        } catch (Exception e) {
            check(tag + ": 重开未抛异常（" + e + "）", false);
        }
    }

    /** 临时诊断：直接看盘上根目录的前几个 32 字节目录项 */
    private static void dumpRoot(DiskImage img, FatVolume vol) {
        final BlockIo io = new BlockIo(img);
        final byte[] boot = io.readSector(0);
        final int reserved = BlockIo.u16(boot, 14);
        final int fatCount = BlockIo.u8(boot, 16);
        final int fatSectors = BlockIo.u16(boot, 22);
        final long rootStart = reserved + (long) fatCount * fatSectors;
        System.out.println("  --- dump: root at sector " + rootStart + " ---");
        for (int i = 0; i < 6; i++) {
            final byte[] sec = io.readSector(rootStart + (i / 16));
            final int off = (i % 16) * 32;
            final StringBuilder sb = new StringBuilder("    slot " + i + ": ");
            for (int k = 0; k < 11; k++) {
                final int b = BlockIo.u8(sec, off + k);
                sb.append(b >= 32 && b < 127 ? (char) b : '.').append(' ');
            }
            sb.append(" attr=0x").append(Integer.toHexString(BlockIo.u8(sec, off + 11)));
            sb.append(" first=0x").append(Integer.toHexString(i * 0));
            sb.append(" nameLen=").append(BlockIo.u8(sec, off));
            System.out.println(sb);
        }
        System.out.println("  --- list: " + names(vol.list("/")) + " ---");
    }

    private static List<String> names(List<FatVolume.Entry> entries) {
        return entries.stream().map(FatVolume.Entry::name).toList();
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
