package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * ===== 动态盘 + 配额自测（离线，2026-09-18）=====
 *
 * <p>覆盖用户定的两条硬要求：</p>
 * <ul>
 *   <li>「设置 60G 则开始分配为 0G，根据安装实际情况动态扩展」→ 初始占用必须**真的是 0 字节**，
 *       写过的块才存在；</li>
 *   <li>「存档总量上限 / 单台上限，0 = 关闭限制」→ 超配额抛 {@code ERR_NO_SPACE}（硬闸门）。</li>
 * </ul>
 *
 * <p>跑法：{@code gradlew :common:runDiskImageTest}</p>
 */
public final class DiskImageSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.out.println("=== Disk image (thin provisioning) + quota self test, no MC ===");
        final Path tmp = Files.createTempDirectory("cryptand-diskimg");
        try {
            thinProvisioning(tmp);
            blockSizeAdaptive();
            quotaGate();
        } finally {
            deleteRecursively(tmp);
        }
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** ① 初始 0 占用 + 按需分配 + 稀疏读 0 + 释放 */
    private static void thinProvisioning(Path tmp) {
        final Path dir = tmp.resolve("disk-a");
        final long capacity = 64L * 1024 * 1024;                 // 64MB
        final DiskImage img = DiskImage.create(dir, capacity);   // 造盘 = 显式动作 ⇒ create

        check("容量按打开时给定（64MB）", img.capacityBytes() == capacity, img.capacityBytes() + " bytes");
        check("块大小自适应：64MB ⇒ 4MB 块", img.blockSize() == 4 * 1024 * 1024, img.blockSize() + " bytes");
        check("逻辑块数 = 容量/块大小", img.blockCount() == 16, img.blockCount() + " blocks");
        check("★ 初始实际占用 = 0 字节（thin provisioning）", img.usedBytes() == 0, img.usedBytes() + " bytes");
        check("初始已分配块 = 空", img.allocatedBlocks().isEmpty(), img.allocatedBlocks().toString());

        final byte[] zero = img.readBlock(7);
        boolean allZero = true;
        for (final byte b : zero) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        check("读未分配的块返回全 0（稀疏语义）", allZero && zero.length == img.blockSize(), zero.length + " bytes");

        final byte[] data = "CRYPTAND".getBytes(StandardCharsets.UTF_8);
        img.writeBlock(7, data);
        check("写一个块后：占用 > 0", img.usedBytes() > 0, img.usedBytes() + " bytes");
        check("已分配块 = [7]", img.allocatedBlocks().equals(List.of(7)), img.allocatedBlocks().toString());
        check("读回内容一致",
                "CRYPTAND".equals(new String(img.readBlock(7), 0, 8, StandardCharsets.UTF_8)),
                new String(img.readBlock(7), 0, 8, StandardCharsets.UTF_8));

        final long before = img.usedBytes();
        check("释放该块（TRIM 语义）", img.freeBlock(7), "");
        check("释放后占用回落", img.usedBytes() < before && img.allocatedBlocks().isEmpty(),
                img.usedBytes() + " bytes");

        // 重新打开：容量与块大小从 disk.json 读回（盘是物品，换机器插回来必须同尺寸）
        img.writeBlock(3, data);
        final DiskImage reopened = DiskImage.open(dir, capacity);
        check("重新打开后容量一致（disk.json 生效）", reopened.capacityBytes() == capacity, reopened.capacityBytes() + "");
        check("重新打开后块大小一致", reopened.blockSize() == img.blockSize(), reopened.blockSize() + "");
        check("重新打开后已分配块仍在", reopened.allocatedBlocks().equals(List.of(3)), reopened.allocatedBlocks().toString());
        check("disk.json 真的落盘了", Files.isRegularFile(dir.resolve(DiskImage.META_FILE)),
                dir.resolve(DiskImage.META_FILE).toString());
    }

    /** ② 块大小随容量自适应（避免 60G 盘产生 6 万个块文件） */
    private static void blockSizeAdaptive() {
        check("8MB 以内用 1MB 块", DiskImage.blockSizeFor(8L * 1024 * 1024) == 1024 * 1024, "1MB");
        check("512MB 以内用 4MB 块", DiskImage.blockSizeFor(512L * 1024 * 1024) == 4 * 1024 * 1024, "4MB");
        check("60G 用 16MB 块（约 3840 块，目录扫描可接受）",
                DiskImage.blockSizeFor(60L * 1024 * 1024 * 1024) == 16 * 1024 * 1024,
                (60L * 1024 * 1024 * 1024 / (16 * 1024 * 1024)) + " blocks");
    }

    /** ③ 配额硬闸门：0 = 不限；超限 ⇒ ERR_NO_SPACE */
    private static void quotaGate() {
        final DiskQuota unlimited = new DiskQuota(0, 0);
        check("0 = 关闭限制（GB→字节映射成 -1）",
                DiskQuota.gbToBytes(0) == -1 && unlimited.saveUnlimited() && unlimited.machineUnlimited(), "-1");
        check("不限时任意大增量都通过（不抛）",
                noThrow(() -> unlimited.checkMachine(1_000_000_000L, 999_999_999L)), "");

        final DiskQuota quota = new DiskQuota(DiskQuota.gbToBytes(20), DiskQuota.gbToBytes(1));
        check("单机上限 1GB，已用 900MB + 200MB ⇒ ERR_NO_SPACE",
                errCode(() -> quota.checkMachine(900L * 1024 * 1024, 200L * 1024 * 1024), OcAbi.ERR_NO_SPACE),
                "ERR_NO_SPACE");
        check("单机上限内（900MB + 50MB）通过",
                noThrow(() -> quota.checkMachine(900L * 1024 * 1024, 50L * 1024 * 1024)), "");
        check("存档上限 20GB，已用 19.9GB + 200MB ⇒ ERR_NO_SPACE",
                errCode(() -> quota.checkSave((long) (19.9 * 1024 * 1024 * 1024), 200L * 1024 * 1024),
                        OcAbi.ERR_NO_SPACE), "ERR_NO_SPACE");

        // 计量：从目录真实求和
        final Path tmp2 = Path.of(System.getProperty("java.io.tmpdir")).resolve("cryptand-quota-" + System.nanoTime());
        final DiskImage a = DiskImage.open(tmp2.resolve("d1"), 64L * 1024 * 1024);
        final DiskImage b = DiskImage.open(tmp2.resolve("d2"), 64L * 1024 * 1024);
        check("两个新盘占用合计 0", DiskQuota.usedByDisk(tmp2.resolve("d1")) + DiskQuota.usedByDisk(tmp2.resolve("d2")) == 0, "0");
        a.writeBlock(0, new byte[]{1, 2, 3});
        b.writeBlock(1, new byte[]{4, 5, 6});
        final long sum = DiskQuota.usedBySave(tmp2);
        check("写入后存档级合计 = 两块之和且 > 0", sum > 0 && sum == a.usedBytes() + b.usedBytes(), sum + " bytes");
        deleteRecursively(tmp2);
    }

    // ==================== 工具 ====================

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : "  " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }

    private static boolean noThrow(Runnable r) {
        try {
            r.run();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
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
