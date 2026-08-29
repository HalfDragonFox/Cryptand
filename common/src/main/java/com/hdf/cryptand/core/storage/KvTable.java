package com.hdf.cryptand.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 高级键值存储接口（2026-08-13 用户架构：存储提供更高级的方式）。
 * <p>
 * 通用 KV 表（{@code kv(k TEXT PRIMARY KEY, v BLOB)}）：任意二进制值按 key
 * 存取，支持：
 *   - 异步写 {@link #put}（不阻塞）/ 批量事务 {@link #putBatch}
 *   - 同步点查 {@link #get} / {@link #getString}
 *   - 前缀范围查询 {@link #queryPrefix}（B-tree 索引）
 *   - 删除 {@link #delete} / 清空 {@link #clear}
 * <p>
 * 适用于高性能元数据/索引/缓存（相比 NBT 整块读写，可随机访问单条记录）。
 * 线程安全（经 {@link SqliteStore}）。
 */
public final class KvTable extends SqliteTable {

    public KvTable(SqliteStore store) throws SQLException {
        super(store, "kv",
                "CREATE TABLE IF NOT EXISTS kv ("
                        + "k TEXT PRIMARY KEY, "
                        + "v BLOB NOT NULL)");
    }

    /** 异步写单条（不阻塞调用方；追求性能，写排队到单写线程）。返回 future 可
     *  wait（join/get(timeout)）或消息通知（thenRun/whenComplete）。 */
    public AsyncOp put(String key, byte[] value) {
        if (key == null) return AsyncOp.done();
        final byte[] v = value == null ? new byte[0] : value;
        return store.asyncWrite((Connection c) -> putNow(c, key, v));
    }

    /** 同步写单条（立即生效/落盘）。返回已完成的 AsyncOp——与异步 put 统一
     *  wait/waitUs 接口，调用方可直接互换（同步立即完成）。 */
    public AsyncOp putSync(String key, byte[] value) throws SQLException {
        if (key == null) return AsyncOp.done();
        final byte[] v = value == null ? new byte[0] : value;
        store.execute((Connection c) -> putNow(c, key, v));
        return AsyncOp.done();
    }

    private static void putNow(Connection c, String key, byte[] v) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO kv(k, v) VALUES(?, ?)")) {
            ps.setString(1, key);
            ps.setBytes(2, v);
            ps.executeUpdate();
        }
    }

    /** 异步批量写（单事务一次提交，不阻塞调用方）。返回 future 可 wait/通知。 */
    public AsyncOp putBatch(Map<String, byte[]> entries) {
        if (entries == null || entries.isEmpty()) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> putBatchNow(c, entries));
    }

    /** 同步批量写（单事务一次提交，立即生效）。返回已完成的 AsyncOp（可互换）。 */
    public AsyncOp putBatchSync(Map<String, byte[]> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) return AsyncOp.done();
        store.transaction((Connection c) -> putBatchNow(c, entries));
        return AsyncOp.done();
    }

    private static void putBatchNow(Connection c, Map<String, byte[]> entries) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO kv(k, v) VALUES(?, ?)")) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (e.getKey() == null) continue;
                ps.setString(1, e.getKey());
                ps.setBytes(2, e.getValue() == null ? new byte[0] : e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 同步点查（无 → null） */
    public byte[] get(String key) {
        if (key == null) return null;
        try {
            return store.query((Connection c) -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT v FROM kv WHERE k = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getBytes("v") : null;
                    }
                }
            });
        } catch (SQLException e) {
            return null;
        }
    }

    /** 同步点查（字符串便捷版） */
    public String getString(String key) {
        byte[] v = get(key);
        return v == null ? null : new String(v, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 前缀范围查询（B-tree 索引；返回 key→value 有序列表） */
    public List<Map.Entry<String, byte[]>> queryPrefix(String prefix) {
        List<Map.Entry<String, byte[]>> out = new ArrayList<>();
        if (prefix == null) return out;
        try {
            store.query((Connection c) -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT k, v FROM kv WHERE k >= ? AND k < ? ORDER BY k")) {
                    ps.setString(1, prefix);
                    ps.setString(2, prefix + "\uffff");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new java.util.AbstractMap.SimpleEntry<>(
                                    rs.getString("k"), rs.getBytes("v")));
                        }
                    }
                }
                return null;
            });
        } catch (SQLException ignored) {
        }
        return out;
    }

    /** 删除单条（异步写，不阻塞调用方）。返回 future 可 wait/通知。 */
    public AsyncOp delete(String key) {
        if (key == null) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM kv WHERE k = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        });
    }

    /** 删除单条（同步，立即生效）。返回已完成的 AsyncOp（可互换）。 */
    public AsyncOp deleteSync(String key) throws SQLException {
        if (key == null) return AsyncOp.done();
        store.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM kv WHERE k = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        });
        return AsyncOp.done();
    }

    /** 清空表（异步写，不阻塞调用方）。返回 future 可 wait/通知。 */
    public AsyncOp clear() {
        return store.asyncWrite((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kv")) {
                ps.executeUpdate();
            }
        });
    }

    /** 清空表（同步，立即生效）。返回已完成的 AsyncOp（可互换）。 */
    public AsyncOp clearSync() throws SQLException {
        store.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kv")) {
                ps.executeUpdate();
            }
        });
        return AsyncOp.done();
    }

    /** 表内条目数（同步） */
    public int count() {
        try {
            return store.query((Connection c) -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM kv");
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            });
        } catch (SQLException e) {
            return 0;
        }
    }
}
