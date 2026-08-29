package com.hdf.cryptand.core.storage;

import com.hdf.cryptand.Cryptand;

import java.io.Closeable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SQLite 存储接口（2026-08-13 用户架构：本 mod 提供 SQLite 存储，也适用于其他
 * mod；与现有多线程技术融合提高性能）。
 * <p>
 * 设计：
 *   - <b>单写线程 + 异步队列</b>：所有写操作 {@link #asyncWrite} 排队到独立
 *     写线程（守护，Cryptand-Sqlite-Writer）串行执行——SQLite 单写者要求满足，
 *     且不阻塞主线程/求解线程池（与 WorkStealingPool 并行，互不等待）。
 *   - <b>批量事务</b> {@link #transaction}：多表/多行写入一次提交，减少磁盘
 *     fsync，性能提升明显。
 *   - <b>同步读</b> {@link #query}：主线程快速点查（SQLite WAL 下读不阻塞写）。
 *   - <b>WAL 模式 + synchronous=NORMAL + busy_timeout</b>：读写并发 + 崩溃安全
 *     + 死锁规避。
 *   - <b>flush()</b>：等待所有排队写完成（世界保存/卸载前调用，保证落盘）。
 * <p>
 * 线程安全：连接访问统一 synchronized(lock) 串行化（SQLite 单文件本身串行写）。
 */
public final class SqliteStore implements Closeable {

    /** 独立 logger（2026-08-14：不引用 common Cryptand 类——该类在 neoforge 运行时
     *  类加载失败（NoClassDefFoundError），引用其 WAF_LOGGER 会使所有 SQLite 操作崩溃） */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("cryptand");

    private final Connection conn;
    private final Object lock = new Object();
    private final ExecutorService writer;
    private final AtomicLong pending = new AtomicLong();
    private volatile boolean closed;

    /** 打开 SQLite 连接（2026-08-14 服务器实证修复：绕开 DriverManager）：
     *  Loom dev 时驱动经 system classloader 加载（mod loader 看不到 java.class.path
     *  上的 sqlite-jdbc），但 DriverManager.getConnection 的 isDriverAllowed 检查会
     *  因驱动不在调用者(mod)类加载器可见范围而【跳过该驱动】→ "No suitable driver
     *  found"。直接用 Driver.connect(url, props) 不经 DriverManager，无此限制。 */
    private static Connection openConnection(Path dbFile) throws SQLException {
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        // 1) 正常（生产 include 打包）：驱动在 mod classpath
        try {
            Class<?> cls = Class.forName("org.sqlite.JDBC");
            return ((java.sql.Driver) cls.getDeclaredConstructor().newInstance())
                    .connect(url, new java.util.Properties());
        } catch (ClassNotFoundException ignored) {
            // 回退 system classloader（Loom dev：java.class.path 里有驱动）
        } catch (ReflectiveOperationException e) {
            throw new SQLException("SQLite driver init failed", e);
        }
        try {
            Class<?> cls = Class.forName("org.sqlite.JDBC", true,
                    ClassLoader.getSystemClassLoader());
            java.sql.Driver d = (java.sql.Driver) cls.getDeclaredConstructor().newInstance();
            java.sql.Connection c = d.connect(url, new java.util.Properties());
            LOGGER.info("[Sqlite] connected via system classloader driver (Loom dev fallback)");
            return c;
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC driver not found", e);
        } catch (ReflectiveOperationException e) {
            throw new SQLException("SQLite driver init failed", e);
        }
    }

    /** 打开（或创建）SQLite 数据库文件。 */
    public SqliteStore(Path dbFile) throws SQLException {
        this.conn = openConnection(dbFile);
        this.writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Cryptand-Sqlite-Writer");
            t.setDaemon(true);
            return t;
        });
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("PRAGMA busy_timeout=5000");
        }
    }

    /** 同步读（主线程）：串行锁保护，WAL 下不阻塞写。 */
    public <T> T query(ThrowingFunction<Connection, T> f) throws SQLException {
        synchronized (lock) {
            try {
                return f.apply(conn);
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                throw new SQLException(e);
            }
        }
    }

    /** 同步执行（建表/DDL/少量写）。 */
    public void execute(ThrowingConsumer<Connection> op) throws SQLException {
        synchronized (lock) {
            try {
                op.accept(conn);
            } catch (SQLException e) {
                throw e;
            } catch (Exception e) {
                throw new SQLException(e);
            }
        }
    }

    /** 异步写：排队到单写线程串行执行，不阻塞调用方（与求解线程池并行）。
     *  返回 {@link CompletableFuture} 供等待完成（2026-08-13 用户要求：
     *  异步需要 wait 相关函数）：
     *   - 等待：{@code future.join()}（无限）或 {@code future.get(timeout, unit)}（限时）
     *   - 消息通知：{@code future.thenRun(() -> {...})} / {@code whenComplete(...)}
     *   - 异常：异步写失败 → future 以异常完成（{@code join()} 抛 CompletionException）
     *  调用方可不接收返回值（纯异步，忽略即可）。 */
    public AsyncOp asyncWrite(ThrowingConsumer<Connection> op) {
        if (closed) {
            return new AsyncOp(CompletableFuture.failedFuture(
                    new IllegalStateException("SqliteStore closed")));
        }
        pending.incrementAndGet();
        CompletableFuture<Void> fut = new CompletableFuture<>();
        writer.submit(() -> {
            try {
                synchronized (lock) {
                    op.accept(conn);
                }
                fut.complete(null);
            } catch (Throwable t) {
                LOGGER.error("[SqliteStore] async write failed", t);
                fut.completeExceptionally(t);
            } finally {
                pending.decrementAndGet();
            }
        });
        return new AsyncOp(fut);
    }

    /** 批量事务：单线程执行一组写操作，一次提交（性能提升）。 */
    public void transaction(ThrowingConsumer<Connection> ops) throws SQLException {
        synchronized (lock) {
            boolean oldAuto;
            try {
                oldAuto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                ops.accept(conn);
                conn.commit();
                conn.setAutoCommit(oldAuto);
            } catch (SQLException e) {
                try { conn.rollback(); } catch (SQLException ignored) { }
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
                throw e;
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException ignored) { }
                try { conn.setAutoCommit(true); } catch (SQLException ignored) { }
                throw new SQLException(e);
            }
        }
    }

    /** 等待所有排队写完成（世界保存/卸载前调用，保证数据落盘）。
     *  ⚠ 带超时（默认 10s）防无限忙等卡死：写线程异常/队列卡住时 flush
     *  不会永久阻塞主线程（退出保存世界卡住的修复，2026-08-14）。 */
    public void flush() {
        flush(10_000);
    }

    /** 带超时的 flush：等待 pending 写全部完成；超时放弃（警告 + 强制继续）。
     *  ⚠ 2026-08-14 修复：等待条件【不能含 !closed】——close() 先置 closed=true
     *  再调 flush()，原条件使循环立即退出、不等待排队写 → 写线程在 conn.close()
     *  后执行失败 → 导线/设备缓存从未落盘（wire_graph 0 行实证）。超时兜底已防
     *  无限等待，无需 closed 条件。 */
    public void flush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (pending.get() > 0) {
            if (System.currentTimeMillis() > deadline) {
                LOGGER.warn(
                        "[SqliteStore] flush timeout (pending={}), skip remaining",
                        pending.get());
                break;
            }
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        flush();
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // 2026-08-14 修复"退出再进卡在加载世界"：conn.close() 无超时可永久
        // 阻塞（integrated server 下第二次 close 实证卡死 Server thread）。
        // 用临时线程 close + 5s 超时放弃——WAL 已落盘，放弃 close 无害
        // （进程退出时 OS 释放文件句柄）。
        try {
            java.util.concurrent.Future<?> f = java.util.concurrent.Executors
                    .newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "Cryptand-Sqlite-Closer");
                        t.setDaemon(true);
                        return t;
                    })
                    .submit(() -> {
                        try {
                            conn.close();
                        } catch (SQLException ignored) {
                        }
                    });
            f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("[SqliteStore] conn.close timeout, abandoned");
        }
    }

    @FunctionalInterface
    public interface ThrowingFunction<T, R> {
        R apply(T t) throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }
}
