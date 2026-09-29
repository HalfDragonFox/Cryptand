package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.soc.fs.DiskFormatTool;
import net.minecraft.server.level.ServerLevel;

/**
 * ===== 格式化工具（MC 侧适配，逻辑在 common）=====
 *
 * <p>用户要求"格式化可以写成单独库，然后沙盒跑一遍" —— 这里只剩一件事：<b>把「存档 + 盘地址」
 * 翻成盘目录</b>，其余（建分区、写 BPB/FAT/根目录、容量与类型的硬校验）全在
 * {@link DiskFormatTool}，纯 Java、离线可测。</p>
 */
public final class OcDiskFormat {

    private OcDiskFormat() {
    }

    /**
     * 格式化一块盘上的一个新分区。
     *
     * @param capacityBytes &lt;= 0 ⇒ 沿用盘目录里已有 {@code disk.json} 的容量（盘的真实容量）
     * @return 人类可读的结果（给命令/AI 工具回显）
     */
    public static String format(ServerLevel level, String address, long capacityBytes, long sizeBytes,
                                String fsName, String label) {
        return DiskFormatTool.format(OcDiskMounts.directoryOf(level, address),
                capacityBytes, sizeBytes, fsName, label);
    }
}
