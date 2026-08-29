package com.hdf.cryptand.core.storage;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SQLite 工厂类（2026-08-15 用户架构：核心扩展支持——多实例 SQLite 管理）。
 * <p>
 * 需求：所有数据库默认创建在世界目录 {@code <world>/cryptand} 下；SQLite 使用
 * 时从本工厂返回一个【可操作对象】（{@link SqliteStore}），方便【多个实例】同时
 * 使用，并且通过【消息机制】天然支持多线程：
 * <ul>
 *   <li><b>多实例</b>：按名称管理多个 {@link SqliteStore}（每个实例独立连接 +
 *      独立单写线程）。同名重复 {@link #open} 幂等返回已存在实例（不重复建连接）。</li>
 *   <li><b>消息机制 / 多线程</b>：每个 {@link SqliteStore} 自带单写线程异步队列
 *      （写不阻塞主线程/求解线程池）；写完成经 {@link AsyncOp} 的
 *      {@code thenRun/whenComplete} 回调通知。本工厂另提供全局 {@link Listener}
 *      消息（open/close 通知），供订阅方（如网表数据库、UI、日志）解耦响应。</li>
 *   <li><b>生命周期</b>：世界卸载/服务端停止调 {@link #closeAll}——逐个 flush
 *      落盘 + 关连接（线程安全，可重复调用）。</li>
 * </ul>
 * 使用：
 * <pre>
 *   SqliteStore s = SqliteFactory.open("netlist", dir.resolve("simulation_circuit.sqlite"));
 *   s.asyncWrite(...).thenRun(() -> log("done"));   // 消息通知，不阻塞
 *   SqliteFactory.close("netlist");
 * </pre>
 * 线程安全：全部操作经 ConcurrentHashMap/CopyOnWriteArrayList，任何线程可调。
 */
public final class SqliteFactory {

    /** 已打开的命名存储（名称 → 存储实例） */
    private static final ConcurrentHashMap<String, SqliteStore> STORES =
            new ConcurrentHashMap<>();
    /** 已打开存储的文件路径（名称 → 路径；诊断/重开用） */
    private static final ConcurrentHashMap<String, Path> PATHS =
            new ConcurrentHashMap<>();
    /** 全局生命周期监听器（消息机制；CopyOnWrite 可安全增删） */
    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<>();

    private SqliteFactory() {}

    /** 工厂生命周期监听器（消息机制：open/close 通知，各线程异步触发） */
    public interface Listener {
        /** 某命名存储已打开 */
        default void onOpened(String name, Path file) {}
        /** 某命名存储已关闭 */
        default void onClosed(String name) {}
    }

    /** 订阅工厂消息（open/close 通知；可多个订阅者） */
    public static void addListener(Listener l) {
        if (l != null) LISTENERS.add(l);
    }

    /** 取消订阅 */
    public static void removeListener(Listener l) {
        if (l != null) LISTENERS.remove(l);
    }

    /**
     * 打开（或返回已存在）命名 SQLite 存储。
     * <p>
     * 多实例：不同 name 各自独立连接 + 独立写线程（可并行写不同文件）；
     * 同 name 重复调用幂等返回既有实例（不重复建连接/不丢排队写）。
     *
     * @param name 实例名（唯一标识；如 "netlist"、"device"）
     * @param file 数据库文件路径（世界目录 {@code <world>/cryptand/...} 下）
     * @return 可操作存储对象
     */
    public static SqliteStore open(String name, Path file) throws SQLException {
        if (name == null) throw new SQLException("SqliteFactory.open: name is null");
        SqliteStore existing = STORES.get(name);
        if (existing != null) return existing; // 幂等：已打开直接返回
        synchronized (STORES) {
            existing = STORES.get(name);
            if (existing != null) return existing;
            Path p = file == null ? null : file.toAbsolutePath();
            SqliteStore s = new SqliteStore(p);
            STORES.put(name, s);
            PATHS.put(name, p);
            for (Listener l : LISTENERS) {
                try {
                    l.onOpened(name, p);
                } catch (Throwable ignored) {
                }
            }
            return s;
        }
    }

    /** 取已打开的命名存储（未打开 → null） */
    public static SqliteStore get(String name) {
        return name == null ? null : STORES.get(name);
    }

    /** 是否已打开 */
    public static boolean isOpen(String name) {
        return name != null && STORES.containsKey(name);
    }

    /** 已打开存储的文件路径（未打开 → null） */
    public static Path pathOf(String name) {
        return name == null ? null : PATHS.get(name);
    }

    /** 全部已打开实例名（只读快照） */
    public static Set<String> names() {
        return Collections.unmodifiableSet(STORES.keySet());
    }

    /** 已打开实例数（诊断） */
    public static int count() {
        return STORES.size();
    }

    /**
     * 关闭指定命名存储：flush 所有排队写落盘 + 关连接。
     * 未打开 → 无操作（幂等）。
     */
    public static void close(String name) {
        if (name == null) return;
        SqliteStore s = STORES.remove(name);
        if (s == null) return;
        PATHS.remove(name);
        try {
            s.close();
        } catch (Throwable ignored) {
        }
        for (Listener l : LISTENERS) {
            try {
                l.onClosed(name);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 关闭全部命名存储（世界卸载/服务端停止用；可重复调用） */
    public static void closeAll() {
        for (String name : STORES.keySet()) {
            close(name);
        }
    }
}
