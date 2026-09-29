package com.hdf.cryptand.serial;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 串口实例管理类（2026-09-08 串口对接库：生命周期记账管理）。
 * <p>
 * 使用方式：向管理类【申请】实例（{@link #acquire}）→ 管理类创建实例 + 分配
 * {@link UUID} + 归入组 id，一并登记到内部列表；实例删除（{@link #release} /
 * {@link #releaseGroup}）时【先关闭端口再移除记账】；退出游戏时调用
 * {@link #shutdown()} 遍历列表把全部实例关闭。
 * <pre>{@code
 * SerialManager mgr = SerialManager.DEFAULT;
 *
 * // 申请实例（默认组 "default"）
 * SerialPort port = mgr.acquire(SerialConfig.of("COM3", 115200));
 *
 * // 申请到指定组（同组可整组释放）
 * SerialPort gps = mgr.acquire("GPS", SerialConfig.of("COM5", 9600));
 *
 * // 删除单个实例：先 close 再移出列表
 * mgr.release(port);
 *
 * // 整组释放（先逐个关闭再移除）
 * mgr.releaseGroup("GPS");
 *
 * // 退出游戏：遍历列表全部关闭（平台层在 ServerStopping 调用）
 * mgr.shutdown();
 * }</pre>
 * 线程安全：acquire/release/shutdown 内部同步；查询方法并发安全。
 * shutdown 后管理类进入终态：后续 acquire 抛 {@link IllegalStateException}。
 */
public final class SerialManager {

    /** 全局默认管理类（平台层退出游戏时调用 {@link #shutdown()}） */
    public static final SerialManager DEFAULT = new SerialManager();

    /** 默认组 id */
    public static final String DEFAULT_GROUP = "default";

    private final Object lock = new Object();
    private final Map<UUID, ManagedPort> ports = new ConcurrentHashMap<>();
    private final SerialPortBackend backend; // null = 跟随 SerialPortProvider 全局后端
    private volatile boolean shutdown;

    /** 创建管理类（后端跟随 {@link SerialPortProvider} 全局后端） */
    public SerialManager() {
        this(null);
    }

    /** 创建管理类（指定后端；测试/平台注入用） */
    public SerialManager(SerialPortBackend backend) {
        this.backend = backend;
    }

    // ==================== 申请（acquire） ====================

    /** 申请实例（默认组）：创建 + 分配 UUID + 登记 + 打开；失败抛 IOException 且不登记 */
    public SerialPort acquire(SerialConfig config) throws IOException {
        return acquire(DEFAULT_GROUP, config);
    }

    /** 申请实例到指定组 */
    public SerialPort acquire(String groupId, SerialConfig config) throws IOException {
        if (groupId == null || groupId.isBlank()) {
            throw new IllegalArgumentException("groupId 不能为空");
        }
        synchronized (lock) {
            if (shutdown) {
                throw new IllegalStateException("SerialManager 已 shutdown，不能继续申请");
            }
            SerialPort port = backend().create(config);
            try {
                port.open();
            } catch (IOException | RuntimeException e) {
                try { port.close(); } catch (Throwable ignored) { }
                throw e;
            }
            UUID id = UUID.randomUUID();
            ports.put(id, new ManagedPort(id, groupId, port));
            return port;
        }
    }

    /** 便捷：默认组 + 仅端口名/波特率（其余默认参数） */
    public SerialPort acquire(String portName, int baudRate) throws IOException {
        return acquire(DEFAULT_GROUP, SerialConfig.of(portName, baudRate));
    }

    // ==================== 释放（release：先关闭再移除） ====================

    /** 释放实例：先 {@code close()} 再移出列表；未登记的实例返回 false */
    public boolean release(SerialPort port) {
        if (port == null) return false;
        synchronized (lock) {
            for (ManagedPort m : ports.values()) {
                if (m.port == port) {
                    closeAndRemove(m);
                    return true;
                }
            }
            return false;
        }
    }

    /** 按 UUID 释放：先 {@code close()} 再移出列表 */
    public boolean release(UUID id) {
        if (id == null) return false;
        synchronized (lock) {
            ManagedPort m = ports.get(id);
            if (m == null) return false;
            closeAndRemove(m);
            return true;
        }
    }

    /** 整组释放（逐个先关闭再移除）；返回释放数量 */
    public int releaseGroup(String groupId) {
        if (groupId == null) return 0;
        synchronized (lock) {
            List<ManagedPort> list = new ArrayList<>();
            for (ManagedPort m : ports.values()) {
                if (groupId.equals(m.groupId)) list.add(m);
            }
            for (ManagedPort m : list) closeAndRemove(m);
            return list.size();
        }
    }

    // ==================== 查询 ====================

    /** 当前登记实例数 */
    public int size() {
        return ports.size();
    }

    /** 是否无登记实例 */
    public boolean isEmpty() {
        return ports.isEmpty();
    }

    /** 是否已 shutdown（退出游戏后为 true，此后 acquire 被拒绝） */
    public boolean isShutdown() {
        return shutdown;
    }

    /** 全部登记实例（按 UUID 顺序） */
    public List<SerialPort> ports() {
        List<SerialPort> r = new ArrayList<>(ports.size());
        for (ManagedPort m : ports.values()) r.add(m.port);
        return r;
    }

    /** 指定组的全部实例 */
    public List<SerialPort> portsOf(String groupId) {
        List<SerialPort> r = new ArrayList<>();
        if (groupId == null) return r;
        for (ManagedPort m : ports.values()) {
            if (groupId.equals(m.groupId)) r.add(m.port);
        }
        return r;
    }

    /** 实例的登记 UUID（未登记返回 null） */
    public UUID idOf(SerialPort port) {
        if (port == null) return null;
        for (ManagedPort m : ports.values()) {
            if (m.port == port) return m.id;
        }
        return null;
    }

    /** 实例所属组 id（未登记返回 null） */
    public String groupOf(SerialPort port) {
        if (port == null) return null;
        for (ManagedPort m : ports.values()) {
            if (m.port == port) return m.groupId;
        }
        return null;
    }

    // ==================== 退出游戏/卸载：遍历列表全部关闭 ====================

    /**
     * 关闭全部登记实例并清空列表（非终态——closeAll 后仍可继续 acquire）。
     * 子包 unload（懒加载释放资源）时调用。
     */
    public void closeAll() {
        synchronized (lock) {
            for (ManagedPort m : ports.values()) {
                try {
                    m.port.close();
                } catch (Throwable ignored) {
                }
            }
            ports.clear();
        }
    }

    /**
     * 关闭全部登记实例并清空列表；之后管理类进入终态
     * （{@link #isShutdown()} 为 true，{@link #acquire} 抛 IllegalStateException）。
     * 平台层在退出游戏（ServerStopping）时调用。
     */
    public void shutdown() {
        synchronized (lock) {
            if (shutdown) return;
            shutdown = true;
            closeAll();
        }
    }

    // ==================== 内部 ====================

    private SerialPortBackend backend() {
        return backend != null ? backend : SerialPortProvider.backend();
    }

    private void closeAndRemove(ManagedPort m) {
        try {
            m.port.close(); // 先关闭
        } catch (Throwable ignored) {
        }
        ports.remove(m.id, m); // 再移除记账
    }

    /** 登记条目：实例 + UUID + 组 id */
    private static final class ManagedPort {
        final UUID id;
        final String groupId;
        final SerialPort port;

        ManagedPort(UUID id, String groupId, SerialPort port) {
            this.id = id;
            this.groupId = groupId;
            this.port = port;
        }
    }
}
