package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.soc.fs.DiskFileSystem;
import com.hdf.cryptand.soc.fs.DiskMount;

import net.minecraft.server.level.ServerLevel;

import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===== 盘挂载表：地址 → 宿主文件夹 → {@link DiskFileSystem}（2026-09-18）=====
 *
 * <p>一块盘 = 一个宿主文件夹（{@code <gameDir>/cryptand/disks/<worldId>/<address>/}），
 * 地址存在盘物品上（{@link com.hdf.cryptand.neoforge.soc.content.SocPartItem#ensureAddress}），
 * 这个类负责"地址 → 文件系统实例"的唯一映射。</p>
 *
 * <h3>为什么按地址缓存而不是每次新建</h3>
 * <ul>
 *   <li><b>句柄必须跨调用有效</b>：固件 {@code FS_OPEN} 拿到的句柄要在后续 {@code FS_READ/WRITE}
 *       里认得出来 —— 每次新建实例的话句柄表就丢了；</li>
 *   <li><b>打开中的文件不能被"另一份实例"绕过</b>：同一个文件夹被两个 {@code DiskFileSystem}
 *       盯上，"打开中不可删"这条保护会失效；</li>
 *   <li>地址是 UUID，一块盘换个槽位/换个机器还是同一块盘 ⇒ **同一个实例本来就正确**
 *       （拔出来再插回来，句柄语义连续）。</li>
 * </ul>
 *
 * <p>⚠ 容量与只读是**首次挂载时固定**的：盘物品的规格（{@code spec}）不该在运行中变；
 * 换盘 = 换地址 = 新实例。真要改容量就走格式化（后续阶段的分区表）。</p>
 */
public final class OcDiskMounts {

    /** address（盘物品的 UUID）→ 文件系统（JVM 级缓存；地址是 UUID，不会跨世界撞） */
    private static final Map<String, com.hdf.cryptand.soc.fs.CryptandFileSystem> MOUNTED = new ConcurrentHashMap<>();

    /**
     * OC **节点地址** → 文件系统。
     *
     * <p>为什么要这张表：固件用"组件表下标"寻址（handle=3），宿主把它翻译成 OC 的
     * **节点地址**（{@code c65d099e-…}），而我们的盘是按**盘物品 UUID**（{@code 82d4861d-…}）
     * 缓存的 —— 两套地址互不相等，必须在挂载时建立映射（{@link #registerNode}），
     * 否则文件系统调用到了平台侧会找不到是"哪块盘"。</p>
     */
    private static final Map<String, com.hdf.cryptand.soc.fs.CryptandFileSystem> BY_NODE = new ConcurrentHashMap<>();

    /**
     * 每个盘**当前有多少个活着的借用者**（= OC 环境数），见 {@link #retain} / {@link #release}。
     *
     * <p>为什么需要它：实例是**共享**的（一块盘 = 一个实例），而 OC 环境的生命周期是**各自**的
     * —— 销毁一个环境不等于"这块盘以后不再用"（真机实测：环境销毁后挂载表里还留着那个实例，
     * 下次挂同一块盘拿到的是**已关闭**的实例，症状是 "filesystem already closed"）。
     * 所以"谁都不能单方面关掉共享实例"必须有个对账点：**最后一个借用者归还时才真正卸载**。</p>
     */
    private static final Map<String, Integer> BORROWERS = new ConcurrentHashMap<>();

    private OcDiskMounts() {
    }

    /**
     * 取（或首次挂载）某块盘的文件系统。
     *
     * @param level         用于推导 worldId（存档名），保证同一块盘在不同世界互不串内容
     * @param address       盘地址（UUID 字符串，来自物品）
     * @param capacityBytes 容量（字节；由盘规格换算，见 {@code SocPartKind}）
     * @param readOnly      只读盘（盘物品的锁定标记；阶段 6.1）
     */
    public static com.hdf.cryptand.soc.fs.CryptandFileSystem of(ServerLevel level, String address, long capacityBytes,
                                                      boolean readOnly) {
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("disk address must not be empty");
        }
        final com.hdf.cryptand.soc.fs.CryptandFileSystem cached = MOUNTED.get(address);
        if (cached != null) {
            // 不变量：挂载表**只持有活着的实例**（关闭只发生在 unmount 里，而它会同时摘表）。
            // 这里不是"兜底重开"：违反了就必须炸出来说清是哪块盘 —— 把死实例继续发出去，
            // 症状会退化成一句看不懂的 bad handle（2026-09-26 真机就是这么烧掉几轮排查的）。
            if (!cached.isOpen()) {
                throw new IllegalStateException("disk " + address + " is cached but already closed"
                        + " (borrowers=" + BORROWERS.getOrDefault(address, 0) + ")");
            }
            return cached;
        }
        return MOUNTED.computeIfAbsent(address, addr -> open(level, addr, capacityBytes, readOnly));
    }

    /**
     * 登记"多了一个借用者"（每个 OC 环境建盘组件时调一次，必须与 {@link #release} 配对）。
     *
     * <p>⚠ 调用点只有一个：{@code CryptandOcDrivers} 建环境时。谁再"顺手 of() 一下"都不算借用者
     * —— 借用者是**环境**，不是调用。</p>
     */
    public static void retain(String address) {
        if (address == null || address.isEmpty()) {
            return;
        }
        BORROWERS.merge(address, 1, Integer::sum);
    }

    /**
     * 归还借用（OC 销毁环境时经 {@link OcFileSystemAdapter#close} 调进来）。
     *
     * <p>按**对象身份**认归属：只有挂载表当前持有的那个实例才谈得上"归还给这个盘"。
     * 卷文件系统（{@code <盘地址>/<角色>}）与已经被替换掉的旧实例都不归属任何环境，
     * 直接忽略（它们由所属的命名空间负责关闭）。</p>
     */
    public static void release(com.hdf.cryptand.soc.fs.CryptandFileSystem fs) {
        if (fs == null) {
            return;
        }
        String owner = null;
        for (final Map.Entry<String, com.hdf.cryptand.soc.fs.CryptandFileSystem> e : MOUNTED.entrySet()) {
            if (e.getValue() == fs) {
                owner = e.getKey();
                break;
            }
        }
        if (owner == null) {
            return;
        }
        if (BORROWERS.merge(owner, -1, Integer::sum) <= 0) {
            unmount(owner);                 // 最后一个借用者走人 ⇒ 落盘 + 关句柄 + 清三条索引
        }
    }

    /**
     * 按分区表打开盘。
     *
     * <p>⚠ 分派逻辑**不在这里**：它是 common 的 {@code DiskFileSystems}（沙盒自测与真机共用同一份实现）。
     * MC 侧只负责把「存档 + 盘地址」翻成目录路径 —— 两处各写一遍分派，迟早会出现
     * 「沙盒过了、真机不过」。</p>
     */
    private static com.hdf.cryptand.soc.fs.CryptandFileSystem open(ServerLevel level, String addr,
                                                    long capacityBytes, boolean readOnly) {
        final Path dir = directoryOf(level, addr);
        // 机器看到的是**多卷命名空间**（用户 2026-09-24："完整 os 可以支持通过分区来实现功能"）：
        // 系统卷在 /，其余分区挂到 /usr、/home…。固件仍只认"一个盘句柄 + 绝对路径"，
        // 前缀由宿主路由到对应卷 ⇒ guest 不需要理解分区表。
        // ⚠ 一定要走这条**缓存**路径（MOUNTED）：同一块盘重复挂载会得到各自独立的实例，
        //   两块实例写同一块 FAT 镜像 ⇒ 互相看不见对方的写入（沙盒里刚踩到）。
        final java.util.List<com.hdf.cryptand.soc.fs.DiskVolumes.Volume> vols =
                com.hdf.cryptand.soc.fs.DiskVolumes.volumes(dir, capacityBytes, addr, readOnly);
        final com.hdf.cryptand.soc.fs.VolumeNamespace ns =
                com.hdf.cryptand.soc.fs.VolumeNamespace.of(vols);
        if (ns.mountPoints().size() <= 1) {
            // 单卷盘（只有根）：直接交付**上面已经枚举到的**那个系统卷实例。
            // ⚠ 不能再调 DiskFileSystems.open：那会把同一块 FAT 镜像再挂第二份 ——
            //   两份实例各自缓存、互相看不见对方的写入（本文件上方第 42 轮沙盒抓到的正是这个坑），
            //   而且那份实例还会被直接丢掉（句柄与解析白做一遍）。
            for (final com.hdf.cryptand.soc.fs.DiskVolumes.Volume v : vols) {
                if (v.available() && v.role() == com.hdf.cryptand.soc.fs.DiskVolumes.Role.SYSTEM) {
                    return v.fs();
                }
            }
            // 真的没有分区（老盘）⇒ 保持"盘根 = 文件夹树"的老行为，老存档照常能读。
            // 这里仍走 DiskFileSystems.open：那是"没有分区怎么办"的**唯一**实现。
            return com.hdf.cryptand.soc.fs.DiskFileSystems.open(dir, capacityBytes, addr, readOnly);
        }
        // 每个**非系统**卷登记成独立可寻址的节点（<盘地址>/<角色短名>，如 82d4…/home）：
        // 于是"卷"在 OC 侧就是普通 filesystem 组件，按地址就能取到（Lua/工具/将来的固件挂载表都用它）。
        // ⚠ 排除系统卷：它已由主实例（MOUNTED 里那个）代表 —— 再挂一份就会出现
        //   "两个实例写同一块 FAT、互相看不见对方写入"（第 42 轮沙盒抓到过的坑）。
        for (final com.hdf.cryptand.soc.fs.DiskVolumes.Volume v : vols) {
            if (v.available() && v.role() != com.hdf.cryptand.soc.fs.DiskVolumes.Role.SYSTEM) {
                registerNode(nodeAddressOf(addr, v.role()), v.fs());
            }
        }
        return ns;
    }

    /**
     * 盘在宿主上的根目录。
     *
     * <p>⚠ 挂载与**格式化**必须用同一份算法：如果两处各算一遍，就会出现"格式化成功但挂载看不到"
     * 这种最难查的错（两份路径只差一个世界键或一个大小写）。</p>
     */
    public static Path directoryOf(ServerLevel level, String address) {
        return DiskMount.rootDir(FMLPaths.GAMEDIR.get(), worldIdOf(level), address);
    }

    /**
     * 一块盘上的**所有卷**（用户 2026-09-24："完整 os 可以支持通过分区来实现功能，对应现代系统"）。
     *
     * <p>分派逻辑仍然只有一份：角色的判定与挂载在 common 的 {@code DiskVolumes}，
     * 这里只负责"存档 + 盘地址 → 目录路径"。</p>
     */
    public static java.util.List<com.hdf.cryptand.soc.fs.DiskVolumes.Volume> volumes(
            ServerLevel level, String address, long capacityBytes, boolean readOnly) {
        return com.hdf.cryptand.soc.fs.DiskVolumes.volumes(
                directoryOf(level, address), capacityBytes, address, readOnly);
    }

    /**
     * 卷的节点地址 = 盘地址 + 角色短名（如 {@code 82d4861d-…/home}）。
     *
     * <p>固件侧因此看到的是"另一块可挂载的盘"：VFS 可以把 {@code /home} 挂到这个节点上，
     * 而不需要理解分区表 —— 分区语义留在宿主，guest 只认挂载点。</p>
     */
    public static String nodeAddressOf(String diskAddress, com.hdf.cryptand.soc.fs.DiskVolumes.Role role) {
        return diskAddress + "/" + role.key();
    }

    /** 卷表（命令 / MCP / 日志共用 common 的那一份文本） */
    public static java.util.List<String> describeVolumes(ServerLevel level, String address,
                                                         long capacityBytes, boolean readOnly) {
        return com.hdf.cryptand.soc.fs.DiskVolumes.describe(
                volumes(level, address, capacityBytes, readOnly));
    }

    /** 挂载时登记"OC 节点地址 → 文件系统"（见 {@link #BY_NODE} 的说明） */
    public static void registerNode(String nodeAddress, com.hdf.cryptand.soc.fs.CryptandFileSystem fs) {
        if (nodeAddress != null && !nodeAddress.isEmpty() && fs != null) {
            BY_NODE.put(nodeAddress, fs);
        }
    }

    /** 按 OC 节点地址取盘；没有对应盘返回 {@code null}（调用方应报"未知组件"） */
    /** 已登记的 OC 节点地址（诊断用：与核心给的地址对不上时，一眼看出问题在哪一边） */
    public static java.util.Set<String> registeredNodes() {
        return java.util.Set.copyOf(BY_NODE.keySet());
    }

    /**
     * 延迟登记的映射：盘 → 「取该盘 OC 节点地址」的求值器。
     *
     * <p>为什么不直接登记地址：OC 的 {@code Node.address()} 是**节点连网后**才分配的，
     * 而 {@code createEnvironment} 那一刻还没分配（拿到空串）。而"包一层 ManagedEnvironment 挂钩
     * onConnect"也不行 —— OC 内部持有的是 {@code asManagedEnvironment} 建的对象，我们返回的包装
     * 不会被回调（实测登记表恒为空）。所以存一个求值器，等 {@link #forNode} 被调用时再问地址。</p>
     */
    private static final Map<com.hdf.cryptand.soc.fs.CryptandFileSystem, java.util.function.Supplier<String>> LAZY =
            new ConcurrentHashMap<>();

    /** 登记延迟映射（在 mountDisk 里调用） */
    public static void registerLazy(com.hdf.cryptand.soc.fs.CryptandFileSystem fs, java.util.function.Supplier<String> nodeAddress) {
        if (fs != null && nodeAddress != null) {
            LAZY.put(fs, nodeAddress);
        }
    }

    public static com.hdf.cryptand.soc.fs.CryptandFileSystem forNode(String nodeAddress) {
        if (nodeAddress != null) {
            for (final Map.Entry<com.hdf.cryptand.soc.fs.CryptandFileSystem, java.util.function.Supplier<String>> e : LAZY.entrySet()) {
                if (nodeAddress.equals(e.getValue().get())) {
                    return e.getKey();
                }
            }
        }
        return BY_NODE.get(nodeAddress);
        // 上面已经先查过延迟表；这里只剩"按登记过的静态地址查"这一条路
    }

    /**
     * 卸载一块盘（清掉三条索引并关句柄）。
     *
     * <p>⚠ <b>格式化之后必须卸载</b>：旧实例还指着格式化之前的文件系统（甚至还是文件夹树那条路），
     * 不卸载就会出现"格式化成功了，但机器读到的还是老内容"这种看起来像缓存 bug 的现象。</p>
     */
    public static void unmount(String address) {
        BORROWERS.remove(address);
        final com.hdf.cryptand.soc.fs.CryptandFileSystem fs = MOUNTED.remove(address);
        if (fs == null) {
            return;
        }
        try {
            fs.close();
        } catch (Throwable ignored) {
            // 关闭失败没有补救动作；三条索引已经清掉了
        }
        BY_NODE.entrySet().removeIf(e -> e.getValue() == fs);
        LAZY.keySet().removeIf(k -> k == fs);
        // ⚠ 卷节点是按**地址前缀**登记的（<盘地址>/<角色短名>），而卷文件系统与这个命名空间
        // 不是同一个对象 ⇒ 靠"对象身份"清不掉它们（格式化后就会留下陈旧实例，
        // 表现成"格完了还能读到老内容"）。所以这里按前缀清。
        final String prefix = address + "/";
        BY_NODE.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 按盘地址取已挂载的文件系统（诊断/工具用；没有则 null） */
    public static com.hdf.cryptand.soc.fs.CryptandFileSystem mounted(String address) {
        return MOUNTED.get(address);
    }

    /** 已挂载的盘地址（诊断/日志用） */
    public static java.util.Set<String> mountedAddresses() {
        return java.util.Set.copyOf(MOUNTED.keySet());
    }

    /** 关机/换世界时统一收摊：关掉所有句柄并清空缓存（否则文件夹句柄会一直被占着） */
    public static void shutdown() {
        for (final com.hdf.cryptand.soc.fs.CryptandFileSystem fs : new ArrayList<>(MOUNTED.values())) {
            try {
                fs.close();
            } catch (Throwable ignored) {
                // 关闭失败没有补救动作；句柄已从表里摘掉
            }
        }
        MOUNTED.clear();
        BY_NODE.clear();
        LAZY.clear();
        BORROWERS.clear();
    }

    /**
     * 推导"世界键"（拼在盘目录里的那一层）。
     *
     * <p>用**存档名**而不是维度 id：同一块盘在两个存档里必须是两个目录（否则单机开两个世界
     * 会互相看到对方的文件）。取不到时退化成常量（宁可所有世界共享，也不要抛异常把挂载打断）。</p>
     */
    private static String worldIdOf(ServerLevel level) {
        try {
            final String name = level.getServer().getWorldData().getLevelName();
            return name == null || name.isBlank() ? "world" : name;
        } catch (Throwable t) {
            return "world";
        }
    }
}
