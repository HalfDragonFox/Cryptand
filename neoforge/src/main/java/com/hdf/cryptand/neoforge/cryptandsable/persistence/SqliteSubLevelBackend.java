/**
 * ===== 亚层持久化 SQLite 后端（2026-08-31） =====
 *
 * 复用项目已有 {@code SqliteStore}（WAL + 单写线程 + 批量事务），表结构：
 *
 *   CREATE TABLE IF NOT EXISTS sable_sublevels (
 *     level_key  TEXT PRIMARY KEY,   -- "dimension:uuid"
 *     uuid       TEXT NOT NULL,      -- 亚层 uuid（查询方便）
 *     data       BLOB NOT NULL       -- PersistedSubLevel.toTag() NBT 字节
 *   );
 *
 * 优点：单文件、事务性、大批量亚层规模下（查/删）快——用户："大规模可能sqlite好"。
 * 位置：世界存档根目录 {@code <save>/sable_sublevels.sqlite}（与维度无关；level_key
 * 带维度名区分）。
 */
package com.hdf.cryptand.neoforge.cryptandsable.persistence;

import com.hdf.cryptand.core.storage.SqliteStore;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SqliteSubLevelBackend implements ISubLevelPersistenceBackend {

    private static final Logger LOGGER = LogManager.getLogger("cryptand");

    private SqliteStore store;
    private final Path dbFile;

    public SqliteSubLevelBackend(Path dbFile) {
        this.dbFile = dbFile;
    }

    private SqliteStore store() {
        if (store == null) {
            try {
                store = new SqliteStore(dbFile);
                store.execute(this::createTable);
            } catch (Exception e) {
                LOGGER.error("[SubLevelPersistence] sqlite open failed for {}", dbFile, e);
            }
        }
        return store;
    }

    private void createTable(Connection conn) throws Exception {
        try (var st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS sable_sublevels (" +
                    "level_key TEXT PRIMARY KEY, " +
                    "uuid TEXT NOT NULL, " +
                    "data BLOB NOT NULL)");
        }
    }

    private static String levelKey(ServerLevel level, UUID uuid) {
        return level.dimension().location() + ":" + uuid;
    }

    private static String dimensionKey(ServerLevel level) {
        return level.dimension().location().toString();
    }

    @Override
    public void save(ServerLevel level, PersistedSubLevel data) {
        final SqliteStore s = store();
        if (s == null || data == null) return;
        try {
            final byte[] blob = nbtToBytes(data.toTag());
            s.asyncWrite(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO sable_sublevels(level_key, uuid, data) VALUES(?,?,?)")) {
                    ps.setString(1, levelKey(level, data.subLevelId()));
                    ps.setString(2, data.subLevelId().toString());
                    ps.setBytes(3, blob);
                    ps.executeUpdate();
                }
            });
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sqlite save failed for {}", data.subLevelId(), e);
        }
    }

    @Override
    public PersistedSubLevel load(ServerLevel level, UUID subLevelId) {
        final SqliteStore s = store();
        if (s == null || subLevelId == null) return null;
        try {
            return s.query(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT data FROM sable_sublevels WHERE level_key=?")) {
                    ps.setString(1, levelKey(level, subLevelId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return PersistedSubLevel.fromTag(bytesToNbt(rs.getBytes(1)));
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sqlite load failed for {}", subLevelId, e);
            return null;
        }
    }

    @Override
    public List<PersistedSubLevel> loadAll(ServerLevel level) {
        final SqliteStore s = store();
        if (s == null) return List.of();
        try {
            return s.query(conn -> {
                List<PersistedSubLevel> out = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT uuid, data FROM sable_sublevels WHERE level_key LIKE ?")) {
                    ps.setString(1, dimensionKey(level) + ":%");
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            try {
                                out.add(PersistedSubLevel.fromTag(bytesToNbt(rs.getBytes(2))));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
                return out;
            });
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sqlite loadAll failed", e);
            return List.of();
        }
    }

    @Override
    public void delete(ServerLevel level, UUID subLevelId) {
        final SqliteStore s = store();
        if (s == null || subLevelId == null) return;
        try {
            s.asyncWrite(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM sable_sublevels WHERE level_key=?")) {
                    ps.setString(1, levelKey(level, subLevelId));
                    ps.executeUpdate();
                }
            });
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sqlite delete failed for {}", subLevelId, e);
        }
    }

    @Override
    public void clearAll(ServerLevel level) {
        final SqliteStore s = store();
        if (s == null) return;
        try {
            s.asyncWrite(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM sable_sublevels WHERE level_key LIKE ?")) {
                    // level_key 格式：<dimension>:<uuid>；前缀 = <dimension>:
                    ps.setString(1, dimensionKey(level) + ":%");
                    ps.executeUpdate();
                }
            });
        } catch (Exception e) {
            LOGGER.error("[SubLevelPersistence] sqlite clearAll failed", e);
        }
    }

    @Override
    public void flush() {
        if (store != null) store.flush();
    }

    @Override
    public void close() {
        if (store != null) {
            store.flush();
            store.close();
            store = null;
        }
    }

    private static byte[] nbtToBytes(CompoundTag tag) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CompoundTag bytesToNbt(byte[] bytes) {
        try {
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes), net.minecraft.nbt.NbtAccounter.create(0x7FFFFFFF));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
