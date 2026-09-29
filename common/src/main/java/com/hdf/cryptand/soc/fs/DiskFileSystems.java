package com.hdf.cryptand.soc.fs;

import java.nio.file.Path;
import java.util.Locale;

/**
 * ===== 盘 → 文件系统的挂载分派（纯 Java，零 MC）=====
 *
 * <p>用户定案"全系统都走格式化这条路"，于是"这块盘上是什么文件系统"完全由分区表决定：</p>
 *
 * <table border="1">
 *   <tr><th>分区情况</th><th>走哪条路</th></tr>
 *   <tr><td>第一个分区 = IMAGE + {@code FATxx}</td><td>{@link FatFileSystem}（块设备 + FAT 驱动）</td></tr>
 *   <tr><td>第一个分区 = FOLDER</td><td>{@link DiskFileSystem}（宿主文件树，玩家也能直接改）</td></tr>
 *   <tr><td>分区存在但没格式化</td><td><b>明确失败</b> —— 绝不静默回落文件夹树</td></tr>
 *   <tr><td>没有分区（老盘）</td><td>旧行为：盘根目录即文件夹树</td></tr>
 * </table>
 *
 * <p>⚠ 这段逻辑放在 <b>common</b> 而不是 MC 侧：它是"盘上的数据怎么解释"的<b>唯一</b>实现，
 * 沙盒自测与真机走的是同一份代码 —— 否则"沙盒过了、真机不过"会变成常态。</p>
 */
public final class DiskFileSystems {

    private DiskFileSystems() {
    }

    /**
     * 按分区表打开盘。
     *
     * @param diskDir       盘目录
     * @param capacityBytes 容量（用于首次初始化 {@code disk.json}；已有文件则以文件里的为准）
     * @param address       盘地址（进 {@link DiskMount}，用于日志/校验）
     * @param readOnly      只读盘
     * @throws IllegalStateException 分区存在但未格式化（调用方应报"盘不可用"，不要回落）
     */
    public static CryptandFileSystem open(Path diskDir, long capacityBytes, String address, boolean readOnly) {
        // 挂的是**系统卷**（用户 2026-09-24："完整 os 可以支持通过分区来实现功能"）：
        // 多分区盘上"第一个分区"很可能是 /home，按顺序取会去用户数据区找系统。
        // 所以这里委托 DiskVolumes（角色认 label、无 label 才按位置兜底），两条路只此一份实现。
        final DiskVolumes.Volume sys = DiskVolumes.system(diskDir, capacityBytes, address, readOnly);
        if (sys != null) {
            return sys.fs();
        }
        // 没有分区 ⇒ 旧行为（盘根 = 文件夹树），保证老存档照常能读
        return new DiskFileSystem(new DiskMount(diskDir, address), capacityBytes, readOnly);
    }
}
