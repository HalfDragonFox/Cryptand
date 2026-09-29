package com.hdf.cryptand.soc.fs;

import com.hdf.cryptand.soc.oc.OcAbi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * ===== 磁盘配额（两级硬闸门，2026-09-18 用户定案）=====
 *
 * <p>用户定的配置语义（与项目惯例一致：<b>0 = 关闭限制</b>）：</p>
 * <pre>
 *   [disks]
 *     saveQuotaGb    = 20    # 整个存档允许的磁盘总量；0 = 不限制
 *     machineQuotaGb = 8     # 单台机器的上限；0 = 不限制
 * </pre>
 *
 * <p>超配额时**写入返回 ERR_NO_SPACE**（固件看到「磁盘满」，语义照 OC 的
 * {@code IOException("not enough space")}）—— <b>宿主的真实磁盘永不超配额</b>：
 * 这是硬闸门，不是警告。没有这道闸门，玩家（或一个跑飞的固件）设一块 60G 的盘就能把
 * 存档目录写爆。</p>
 *
 * <p>计量口径：都用 {@link DiskImage#usedBytes()} 的求和（= 块文件实际大小之和），所以
 * 数字既反映逻辑占用也反映真实磁盘占用 —— 这正是选择「分块文件」而不是「稀疏单文件」
 * 的原因之一。</p>
 */
public final class DiskQuota {

    /** <= 0 表示不限制 */
    private final long saveQuotaBytes;
    /** <= 0 表示不限制 */
    private final long machineQuotaBytes;

    public DiskQuota(long saveQuotaBytes, long machineQuotaBytes) {
        this.saveQuotaBytes = saveQuotaBytes;
        this.machineQuotaBytes = machineQuotaBytes;
    }

    /** GB → 字节；**0 或负数映射成 -1（不限制）**，避免调用方重复写这个判断 */
    public static long gbToBytes(long gb) {
        return gb <= 0 ? -1L : gb * 1024L * 1024L * 1024L;
    }

    public boolean saveUnlimited() {
        return saveQuotaBytes <= 0;
    }

    public boolean machineUnlimited() {
        return machineQuotaBytes <= 0;
    }

    public long saveQuotaBytes() {
        return saveQuotaBytes;
    }

    public long machineQuotaBytes() {
        return machineQuotaBytes;
    }

    /**
     * 单机闸门：这台机器当前已用 + 本次要写的增量，是否还在上限内。
     *
     * @throws FsException {@code ERR_NO_SPACE}（超限；固件据此看到「磁盘满」）
     */
    public void checkMachine(long machineUsedBytes, long deltaBytes) {
        if (machineQuotaBytes <= 0 || deltaBytes <= 0) {
            return;
        }
        if (machineUsedBytes + deltaBytes > machineQuotaBytes) {
            throw FsException.noSpace(deltaBytes, Math.max(0, machineQuotaBytes - machineUsedBytes));
        }
    }

    /**
     * 存档闸门：整个存档所有盘的实际占用 + 增量。
     *
     * @throws FsException {@code ERR_NO_SPACE}
     */
    public void checkSave(long saveUsedBytes, long deltaBytes) {
        if (saveQuotaBytes <= 0 || deltaBytes <= 0) {
            return;
        }
        if (saveUsedBytes + deltaBytes > saveQuotaBytes) {
            throw FsException.noSpace(deltaBytes, Math.max(0, saveQuotaBytes - saveUsedBytes));
        }
    }

    /** 人类可读的两级配额（日志/工具提示用） */
    @Override
    public String toString() {
        return "DiskQuota[save=" + (saveUnlimited() ? "unlimited" : (saveQuotaBytes / 1024 / 1024 / 1024) + "GB")
                + ", machine=" + (machineUnlimited() ? "unlimited" : (machineQuotaBytes / 1024 / 1024 / 1024) + "GB" + "]");
    }

    // ==================== 计量 ====================

    /**
     * 一台机器（一个盘的挂载目录）的实际占用。
     *
     * <p>直接扫 {@code blocks/} 下的文件大小求和 —— 不依赖任何缓存，因此不存在
     * 「计数器漂了」的问题（与 {@link DiskFileSystem} 的配额策略 A 同一思路）。</p>
     */
    public static long usedByDisk(Path diskDir) {
        final Path blocks = diskDir.resolve("blocks");
        if (!Files.isDirectory(blocks)) {
            return 0;
        }
        long sum = 0;
        try (Stream<Path> s = Files.list(blocks)) {
            for (final Path p : (Iterable<Path>) s::iterator) {
                if (Files.isRegularFile(p)) {
                    sum += Files.size(p);
                }
            }
        } catch (IOException ignored) {
            // 读不到按 0：配额宁松不误杀（真正的闸门在写入路径上）
        }
        return sum;
    }

    /** 整个存档（{@code cryptand/disks/<worldId>/} 下所有盘）的实际占用 */
    public static long usedBySave(Path worldDiskRoot) {
        if (!Files.isDirectory(worldDiskRoot)) {
            return 0;
        }
        long sum = 0;
        try (Stream<Path> s = Files.list(worldDiskRoot)) {
            for (final Path disk : (Iterable<Path>) s::iterator) {
                if (Files.isDirectory(disk)) {
                    sum += usedByDisk(disk);
                }
            }
        } catch (IOException ignored) {
            // 同上
        }
        return sum;
    }

    /** 超配额时的统一错误（把"哪一级超了"写清楚，便于固件/日志定位） */
    public FsException noSpace(String level, long need, long free) {
        return new FsException(OcAbi.ERR_NO_SPACE,
                "disk quota exceeded (" + level + "): need " + need + " bytes, free " + free);
    }
}
