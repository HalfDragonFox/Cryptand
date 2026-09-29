package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.soc.oc.ComponentBus;
import com.hdf.cryptand.soc.os.PeDispatcher;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===== PE 服务（Cryptand OS PE）的平台侧接线（2026-09-26）=====
 *
 * <p>用户定案：系统软盘进入的是 **Cryptand OS PE**（命令行安装环境），提供分区 / 格式化 /
 * 安装（含一键安装）命令。PE 跑在 guest 里，"真正动手"的 {@link PeDispatcher} 在 common
 * （纯 Java、可离线测），本类只负责回答它需要知道的**平台事实**：这台机器上有哪些盘、
 * 每块盘的目录与容量。</p>
 *
 * <p>⚠ "这台机器上有哪些盘"的答案来自**挂载登记表**（{@link #register}）：
 * 盘是在驱动层（{@code CryptandOcDrivers.mountDisk}）被挂载的，那里才知道 level 与容量。
 * 这么做还顺带把语义钉死：**PE 能操作的目标盘 = 这台机器上真实挂载过的盘**，
 * 而不是"宿主上所有存在过的盘目录"（后者会让 PE 去动别的世界的盘）。</p>
 */
public final class OcPeBridge {

    /** 一台机器上盘 → (存档, 容量)。⚠ 地址是全局唯一的，这里按地址登记即可 */
    private record Mounted(ServerLevel level, long capacityBytes) {
    }

    private static final Map<String, Mounted> MOUNTED = new ConcurrentHashMap<>();

    /** PE 的固定组件地址（与 {@code OcAbi.HANDLE_PE} 的映射一致） */
    public static final String ADDRESS = "pe";

    private static final PeDispatcher DISPATCHER = new PeDispatcher(new PeDispatcher.DiskLocator() {

        @Override
        public Path dirOf(String address) {
            final Mounted m = MOUNTED.get(address);
            if (m == null) {
                throw new IllegalArgumentException("disk not mounted on this machine: " + address);
            }
            return OcDiskMounts.directoryOf(m.level(), address);
        }

        @Override
        public long capacityBytesOf(String address) {
            final Mounted m = MOUNTED.get(address);
            if (m == null) {
                throw new IllegalArgumentException("disk not mounted on this machine: " + address);
            }
            return m.capacityBytes();
        }

        @Override
        public List<String> addresses() {
            return new ArrayList<>(MOUNTED.keySet());
        }
    });

    private OcPeBridge() {
    }

    /** 盘挂载时登记（由 {@code CryptandOcDrivers.mountDisk} 调用；重复挂载覆盖为最新） */
    public static void register(ServerLevel level, String address, long capacityBytes) {
        if (level != null && address != null && !address.isBlank()) {
            MOUNTED.put(address, new Mounted(level, Math.max(0L, capacityBytes)));
        }
    }

    /** 卸下一块盘（机器拔盘/换盘时调用；不调用也只是登记表过期，不影响正确性 —— 每一次 PE 调用都会重新校验） */
    public static void unregister(String address) {
        if (address != null) {
            MOUNTED.remove(address);
        }
    }

    /** PE 调用入口（组件名 = "pe"） */
    public static ComponentBus.Result invoke(int methodId, List<Object> args, byte[] buffer) {
        return DISPATCHER.invoke(methodId, args, buffer);
    }
}
