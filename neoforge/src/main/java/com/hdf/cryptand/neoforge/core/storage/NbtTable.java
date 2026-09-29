package com.hdf.cryptand.neoforge.core.storage;

import com.hdf.cryptand.core.storage.AsyncOp;
import com.hdf.cryptand.core.storage.SqliteStore;
import com.hdf.cryptand.core.storage.SqliteTable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * 类 NBT 接口（2026-08-30 从 common 移出：核心只存 SQLite、与 MC 无关——
 * 本表封装 MC NBT（CompoundTag）序列化存 SQLite，属 MC 集成层，故在 neoforge）。
 * 依赖 common 的纯 SQLite 基础（SqliteStore/SqliteTable/AsyncOp）。
 * 类 NBT 接口（2026-08-13 用户架构：存储提供和原版一样类 NBT 的接口）。
 * <p>
 * 把原版 {@link CompoundTag} 完整序列化为 BLOB 存进 SQLite（表 {@code nbt(k
 * TEXT PRIMARY KEY, v BLOB)}）——读取回的 CompoundTag 与原版存档 NBT 完全
 * 兼容（经 {@link NbtIo}），可自由嵌套任意 NBT 结构。
 * <p>
 * 线程安全 + 高性能：写 {@link #putNbt} 走 {@link SqliteStore} 单写线程异步
 * 队列（批量 {@link #putNbtBatch} 走事务一次提交）；读 {@link #getNbt} 同步
 * 点查；前缀查询 {@link #queryNbtByPrefix}（B-tree 索引，范围扫描）。
 */
public final class NbtTable extends SqliteTable {

    public NbtTable(SqliteStore store) throws SQLException {
        super(store, "nbt",
                "CREATE TABLE IF NOT EXISTS nbt ("
                        + "k TEXT PRIMARY KEY, "
                        + "v BLOB NOT NULL)");
    }

    /** 存入 NBT（异步写，不阻塞调用方；追求性能） */
    public AsyncOp putNbt(String key, CompoundTag tag) {
        if (key == null || tag == null) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> putNbtNow(c, key, tag));
    }

    /** 存入 NBT（同步，立即生效/落盘）。返回已完成的 AsyncOp（可互换）。 */
    public AsyncOp putNbtSync(String key, CompoundTag tag) throws SQLException {
        if (key == null || tag == null) return AsyncOp.done();
        store.execute((Connection c) -> putNbtNow(c, key, tag));
        return AsyncOp.done();
    }

    private static void putNbtNow(Connection c, String key, CompoundTag tag) throws Exception {
        final byte[] blob = serialize(tag);
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO nbt(k, v) VALUES(?, ?)")) {
            ps.setString(1, key);
            ps.setBytes(2, blob);
            ps.executeUpdate();
        }
    }

    /** 批量存入 NBT（单事务一次提交，异步不阻塞调用方；性能提升） */
    public AsyncOp putNbtBatch(List<KeyTag> entries) {
        if (entries == null || entries.isEmpty()) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> putNbtBatchNow(c, entries));
    }

    /** 批量存入 NBT（单事务一次提交，同步立即生效）。返回已完成的 AsyncOp。 */
    public AsyncOp putNbtBatchSync(List<KeyTag> entries) throws SQLException {
        if (entries == null || entries.isEmpty()) return AsyncOp.done();
        store.transaction((Connection c) -> putNbtBatchNow(c, entries));
        return AsyncOp.done();
    }

    private static void putNbtBatchNow(Connection c, List<KeyTag> entries) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO nbt(k, v) VALUES(?, ?)")) {
            for (KeyTag e : entries) {
                if (e == null || e.key == null || e.tag == null) continue;
                ps.setString(1, e.key);
                ps.setBytes(2, serialize(e.tag));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /** 读取 NBT（同步点查；无 → null） */
    public CompoundTag getNbt(String key) {
        if (key == null) return null;
        try {
            return store.query((Connection c) -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT v FROM nbt WHERE k = ?")) {
                    ps.setString(1, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            byte[] blob = rs.getBytes("v");
                            return blob == null ? null : deserialize(blob);
                        }
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            return null;
        }
    }

    /** 前缀查询（B-tree 索引范围扫描；返回 key→NBT 有序列表） */
    public List<KeyTag> queryNbtByPrefix(String prefix) {
        List<KeyTag> out = new ArrayList<>();
        if (prefix == null) return out;
        try {
            store.query((Connection c) -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT k, v FROM nbt WHERE k >= ? AND k < ? ORDER BY k")) {
                    ps.setString(1, prefix);
                    ps.setString(2, prefix + "\uffff"); // 上界：前缀 + 最大字符
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            byte[] blob = rs.getBytes("v");
                            if (blob != null) {
                                out.add(new KeyTag(rs.getString("k"), deserialize(blob)));
                            }
                        }
                    }
                }
                return null;
            });
        } catch (SQLException ignored) {
        }
        return out;
    }

    /** 删除单个 key（异步写，不阻塞调用方） */
    public AsyncOp deleteNbt(String key) {
        if (key == null) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM nbt WHERE k = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        });
    }

    /** 删除单个 key（同步，立即生效）。返回已完成的 AsyncOp（可互换）。 */
    public AsyncOp deleteNbtSync(String key) throws SQLException {
        if (key == null) return AsyncOp.done();
        store.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM nbt WHERE k = ?")) {
                ps.setString(1, key);
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
                        "SELECT COUNT(*) FROM nbt");
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            });
        } catch (SQLException e) {
            return 0;
        }
    }

    // ===== NBT <-> BLOB =====

    private static byte[] serialize(CompoundTag tag) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream dos = new DataOutputStream(baos)) {
                NbtIo.write(tag, dos);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static CompoundTag deserialize(byte[] blob) {
        try {
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(blob)));
        } catch (IOException e) {
            return null;
        }
    }

    /** key → NBT 条目（批量/前缀查询结果） */
    public record KeyTag(String key, CompoundTag tag) {
    }
}