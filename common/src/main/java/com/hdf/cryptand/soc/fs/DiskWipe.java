package com.hdf.cryptand.soc.fs;

/**
 * ===== 清空盘（common，纯 Java 零 MC）=====
 *
 * <p>用户 2026-09-18 定案：命令要能"让指定 id 的软盘**强制清空**加载" ——
 * 所以"清空"是一个一等动作，和格式化并列：格式化重写文件系统元数据，清空只删内容。</p>
 *
 * <p>⚠ 两个都留着是有理由的：换程序（清空）比换文件系统（格式化）便宜得多，
 * 而调试循环里 99% 的情况只是"换一个程序"。</p>
 */
public final class DiskWipe {

    private DiskWipe() {
    }

    /**
     * 递归删除盘上所有内容（保留文件系统本身）。
     *
     * @return 删掉的条目数（文件 + 目录）
     */
    public static int wipe(CryptandFileSystem fs) {
        return wipeDir(fs, "/");
    }

    private static int wipeDir(CryptandFileSystem fs, String path) {
        final String[] names = fs.list(path);
        if (names == null) {
            return 0;                       // 路径不存在/不是目录：没有可删的
        }
        int removed = 0;
        for (final String name : names) {
            final String child = path.endsWith("/") ? path + name : path + "/" + name;
            if (fs.isDirectory(child)) {
                removed += wipeDir(fs, child);
            }
            if (fs.delete(child)) {
                removed++;
            }
        }
        return removed;
    }
}
