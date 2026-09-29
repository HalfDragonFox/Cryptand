package com.hdf.cryptand.soc.fs;

/**
 * ===== Cryptand 的盘文件系统契约（照 OC 的 {@code li.cil.oc.api.fs.FileSystem}，2026-09-18）=====
 *
 * <p>为什么逐条照抄 OC 的签名与语义（源码 {@code api/fs/FileSystem.java}）：</p>
 * <ul>
 *   <li>我们的盘迟早要给 OC 的 Lua 程序用（{@code filesystem} 组件的语义就是这份契约）；
 *       现在照抄，将来"让 Lua 也能读写 Cryptand 盘"就只是包一层的事；</li>
 *   <li>固件侧的 POSIX 层（{@code fopen/opendir}）最终落到同一套语义上 ——
 *       契约只有一份，两边不会各自理解出不同的"EOF 是什么"。</li>
 * </ul>
 *
 * <h3>必须记住的三条反直觉约定（都是 OC 原文）</h3>
 * <ol>
 *   <li><b>查询类绝不抛</b>：{@code exists/size/isDirectory/lastModified} 遇到非法路径返回
 *       {@code false}/{@code 0}，不抛异常（{@code FileSystem.java:60/73/85/101}）；</li>
 *   <li><b>{@code list} 返回子项名</b>（不是全路径），**子目录带尾斜杠**；空目录返回空数组；
 *       <b>不存在或不是目录返回 {@code null}</b>（{@code :121}）—— 不是空数组，是 null；</li>
 *   <li><b>路径是"该文件系统根下的绝对路径"</b>，不含 {@code .}/{@code ..}
 *       （接口头注释 {@code :12-17}）。直调实现时由调用方负责规范化；本实现会自己再兜一层
 *       （{@link DiskMount#resolve}），但不要依赖它去纠正"本该由上层消解"的路径。</li>
 * </ol>
 *
 * <p>句柄（{@link Handle}）的语义同样照 OC：{@code read} 把数据写进**调用方给的数组**、
 * 返回读到的字节数、<b>EOF 返回 -1</b>；{@code write} 是"全部写完"；{@code close} 之后
 * 任何操作都抛 {@code ERR_BAD_HANDLE}；句柄 id <b>随机生成</b>（降低复合文件系统里的冲突概率，
 * {@code OutputStreamFileSystem.scala:28}）。</p>
 */
public interface CryptandFileSystem extends AutoCloseable {

    // ==================== 元信息 ====================

    /** 只读**指示器**（真正的强制在各 mutating 方法里自己做，照 OC {@code :30}） */
    boolean isReadOnly();

    /** 总容量（字节）。**只读系统返回 0**；不限制容量返回负值（照 OC {@code :40}） */
    long spaceTotal();

    /** 已用字节（照 OC 的 {@code computeSize} 口径：每条目 {@code FILE_COST} + 文件实际字节） */
    long spaceUsed();

    // ==================== 查询（绝不抛） ====================

    boolean exists(String path);

    /** 文件返回真实字节数，**目录返回 0**，非法/不存在返回 0 */
    long size(String path);

    boolean isDirectory(String path);

    /** Unix 毫秒；非法/不存在返回 0 */
    long lastModified(String path);

    /** 子项名数组（子目录带尾斜杠）；空目录 = 空数组；不存在/不是目录 = <b>null</b> */
    String[] list(String path);

    // ==================== 变更 ====================

    /** 删除单文件或**空**目录；不存在/非空/只读 → false */
    boolean delete(String path);

    /** **只建一层**（父目录不存在 → false，已存在 → false，照 OC {@code :154}）。
     *  需要"递归建"用 {@link #makeDirectories(String)}（那是组件层的行为，不是本契约的）。 */
    boolean makeDirectory(String path);

    /** 递归建目录（对齐 OC **组件层** {@code :120-127} 的行为；ABI 的 FS_MKDIR 用这个） */
    default boolean makeDirectories(String path) {
        String p = DiskMount.normalizeGuestPath(path);
        if (p.isEmpty()) {
            return false;
        }
        boolean ok = true;
        final StringBuilder acc = new StringBuilder();
        for (final String seg : p.split("/")) {
            if (seg.isEmpty()) {
                continue;
            }
            acc.append('/').append(seg);
            if (!exists(acc.toString())) {
                ok &= makeDirectory(acc.toString());
            }
        }
        return ok;
    }

    /** 源不存在（文件或目录都不是）→ 抛 {@code ERR_NOT_FOUND}（照 OC 抛 FileNotFoundException） */
    boolean rename(String from, String to);

    /** 设置修改时间（用户侧不可达，初始化已知时间用；只读系统可忽略，照 OC {@code :185}） */
    boolean setLastModified(String path, long time);

    // ==================== 句柄 ====================

    /**
     * 打开文件并返回**随机句柄 id（> 0）**。
     *
     * @throws FsException {@code ERR_NOT_FOUND}（不存在 + 模式要求已存在 / 目标是目录 /
     *                     被写句柄占用）、{@code ERR_NO_SPACE}（新建或截断后超配额）、
     *                     {@code ERR_READ_ONLY}、{@code ERR_TOO_MANY_HANDLES}、{@code ERR_BAD_MODE}
     */
    int open(String path, FsMode mode);

    /** 无此句柄返回 <b>null</b>（绝不抛，照 OC {@code :228}） */
    Handle getHandle(int handle);

    /**
     * 这个实例**还活着吗**（未被 {@link #close()} 关掉）。
     *
     * <p>为什么契约里要有这条：实例是**共享**的（一个盘 = 一个实例，按盘地址缓存），
     * 而"谁把它关掉了"与"谁还拿着它"是两件独立的事。任何**缓存**都必须能回答
     * "我手里这份还活着吗" —— 否则缓存会把一个已经死掉的实例继续发给新调用方，
     * 症状就是一句看不出根因的 {@code filesystem already closed}
     * （2026-09-26 真机：OC 销毁环境时把共享实例关了，挂载表里却留着 ⇒ 再挂同一块盘必炸）。</p>
     */
    boolean isOpen();

    /** 关闭全部句柄（断开网络节点/拔盘时调用） */
    @Override
    void close();

    // ==================== 句柄（照 OC 的 api/fs/Handle） ====================

    interface Handle extends AutoCloseable {

        /** 当前文件内位置 */
        long position();

        /** 文件总长 */
        long length();

        /**
         * 尽量填满 {@code into}，返回读到的字节数；**EOF 返回 -1**。
         *
         * @throws FsException {@code ERR_BAD_HANDLE}（已关闭 / 模式不可读）、
         *                     {@code ERR_BAD_MODE}（seek 越界由实现决定，见下）
         */
        int read(byte[] into);

        /** 返回**跳转后**的位置；负数 / 不支持 → 抛 {@code ERR_BAD_MODE} */
        long seek(long to);

        /** **全部写完**；只读模式 / 空间不足 / 已关闭 → 抛 {@code FsException} */
        void write(byte[] value);

        /** 关闭后任何 read/write 都应抛 {@code ERR_BAD_HANDLE} */
        @Override
        void close();
    }
}
