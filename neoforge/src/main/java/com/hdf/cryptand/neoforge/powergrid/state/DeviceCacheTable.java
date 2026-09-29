package com.hdf.cryptand.neoforge.powergrid.state;

import com.hdf.cryptand.core.storage.AsyncOp;
import com.hdf.cryptand.core.storage.SqliteStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 设备缓存表（2026-08-13 用户架构：彻底取消 NBT——不用 SavedData/NBT BLOB 存
 * 电路缓存，改用 SQLite 强类型列存储，根治"NBT 过大"）。
 * <p>
 * 表 {@code device_cache(x,y,z,cls,r,l,en,v,sr,vs)}——每个电气参数一个强类型
 * 列（PRIMARY KEY 三列坐标），无 NBT 序列化：
 *   - {@link #save}：世界保存时采集【未加载区块】设备（虚拟）参数 → 单事务
 *     清空+全量写（表 = 当前未加载区快照，无残留）。
 *   - {@link #restore}：进世界时同步读全部 → VirtualDeviceStore.loadAll——
 *     未加载区设备参数立即可用（虚拟建模不等区块加载扫描）。
 * <p>
 * 同步/异步双模式（用户要求）：saveAsync 走 SqliteStore 单写线程异步队列
 * （不阻塞主线程/求解线程池）；saveSync/restore 同步立即生效。
 */
public final class DeviceCacheTable {

    private final SqliteStore store;
    private final String name = "device_cache";

    public DeviceCacheTable(SqliteStore store) throws SQLException {
        this.store = store;
        store.execute((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS " + name + " ("
                            + "x INT NOT NULL, y INT NOT NULL, z INT NOT NULL, "
                            + "cls TEXT, r REAL, l REAL, en INT, "
                            + "v REAL, sr REAL, vs INT, "
                            + "PRIMARY KEY(x, y, z))")) {
                ps.executeUpdate();
            }
        });
    }

    // ===== 保存（采集未加载区虚拟设备 → SQLite） =====

    /** 异步保存（单事务清空+全量写，不阻塞调用方）。返回 AsyncOp 可
     *  wait（毫秒）/waitUs（微秒）或消息通知（thenRun/whenComplete）。 */
    public AsyncOp saveAsync(ServerLevel level) {
        Map<BlockPos, VirtualDevice> keep = collectUnloaded(level);
        return store.asyncWrite((Connection c) -> saveNow(c, keep));
    }

    /** 同步保存（单事务清空+全量写，立即生效/落盘）。返回已完成的 AsyncOp。 */
    public AsyncOp saveSync(ServerLevel level) throws SQLException {
        Map<BlockPos, VirtualDevice> keep = collectUnloaded(level);
        store.transaction((Connection c) -> saveNow(c, keep));
        return AsyncOp.done();
    }

    private static Map<BlockPos, VirtualDevice> collectUnloaded(ServerLevel level) {
        Map<BlockPos, VirtualDevice> keep = new HashMap<>();
        for (Map.Entry<BlockPos, VirtualDevice> e : VirtualDeviceStore.all().entrySet()) {
            try {
                if (level == null || !level.isLoaded(e.getKey())) {
                    keep.put(e.getKey(), e.getValue()); // 只留未加载区（虚拟）
                }
            } catch (Throwable ignored) {
            }
        }
        return keep;
    }

    private static void saveNow(Connection c, Map<BlockPos, VirtualDevice> keep)
            throws Exception {
        try (PreparedStatement del = c.prepareStatement("DELETE FROM device_cache");
             PreparedStatement ps = c.prepareStatement(
                     "INSERT OR REPLACE INTO device_cache(x,y,z,cls,r,l,en,v,sr,vs) "
                             + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            del.executeUpdate();
            for (Map.Entry<BlockPos, VirtualDevice> e : keep.entrySet()) {
                BlockPos pos = e.getKey();
                VirtualDevice pd = e.getValue();
                ps.setInt(1, pos.getX());
                ps.setInt(2, pos.getY());
                ps.setInt(3, pos.getZ());
                ps.setString(4, pd.deviceClass);
                ps.setDouble(5, pd.resistance);
                ps.setDouble(6, pd.inductance);
                ps.setInt(7, pd.enabled ? 1 : 0);
                ps.setDouble(8, pd.voltage);
                ps.setDouble(9, pd.sourceResistance);
                ps.setInt(10, pd.isVoltageSource ? 1 : 0);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ===== 恢复（同步读全部 → VirtualDeviceStore） =====

    /** 进世界：从 SQLite 恢复全部设备参数快照到 VirtualDeviceStore */
    public void restore() {
        try {
            store.query((Connection c) -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT x,y,z,cls,r,l,en,v,sr,vs FROM device_cache");
                     ResultSet rs = ps.executeQuery()) {
                    Map<BlockPos, VirtualDevice> map = new HashMap<>();
                    while (rs.next()) {
                        try {
                            BlockPos pos = new BlockPos(rs.getInt("x"),
                                    rs.getInt("y"), rs.getInt("z"));
                            VirtualDevice pd = new VirtualDevice(
                                    rs.getString("cls"),
                                    rs.getDouble("r"),
                                    rs.getDouble("l"),
                                    rs.getInt("en") != 0,
                                    rs.getDouble("v"),
                                    rs.getDouble("sr"),
                                    rs.getInt("vs") != 0);
                            if (pd.deviceClass != null) map.put(pos, pd);
                        } catch (Throwable ignored) {
                        }
                    }
                    VirtualDeviceStore.loadAll(map);
                }
                return null;
            });
        } catch (SQLException e) {
            // 表不存在/损坏 → 忽略（worldSynchronize 重建对齐）
        }
    }

    /** 表内条目数（诊断） */
    public int count() {
        try {
            return store.query((Connection c) -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(*) FROM device_cache");
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            });
        } catch (SQLException e) {
            return 0;
        }
    }

    /** 清空（异步）。返回 AsyncOp 可 wait/waitUs/通知。 */
    public AsyncOp clear() {
        return store.asyncWrite((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM device_cache")) {
                ps.executeUpdate();
            }
        });
    }

    public String name() { return name; }
}
