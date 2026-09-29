package com.hdf.cryptand.neoforge.powergrid.persistence;

import com.hdf.cryptand.core.storage.KvTable;
import com.hdf.cryptand.neoforge.core.storage.NbtTable;
import com.hdf.cryptand.core.storage.SqliteStore;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkTable;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceCacheTable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本 mod 的 SQLite 存储入口（2026-08-13 用户架构：提供 SQLite 存储接口，适用于
 * 其他 mod；与现有多线程融合——写走 SqliteStore 单写线程异步队列，不阻塞主线程/
 * 求解线程池）。彻底取消 NBT 存档（NBT 会导致 NBT 过大等问题）——缓存走强类型表。
 * <p>
 * 生命周期（neoforge 平台集成）：
 *   - 世界加载（LevelEvent.Load）→ {@link #open}：打开 {@code <world>/cryptand/data.sqlite}，
 *     建 KV/NBT/设备缓存表。
 *   - 世界保存（LevelEvent.Save）→ {@link #deviceCache()} 采集未加载区虚拟设备
 *     参数写入（异步批量，见 {@link DeviceCacheTable}）。
 *   - 世界卸载/服务端停止 → {@link #close}：flush 等待所有排队写落盘 + 关连接。
 * <p>
 * 对外接口：
 *   - {@link #kv()}：高级键值存储（KvTable，同步/异步双模式）
 *   - {@link #nbt()}：类 NBT 接口（NbtTable——类似 NBT 保存操作的接口，开发者
 *     可把原 CompoundTag 存取直接替换为 putNbt/getNbt，底层换 SQLite，数据逻辑
 *     不变；同步/异步双模式）
 *   - {@link #deviceCache()}：设备缓存表（强类型列存储，无 NBT；电路缓存专用）
 * 其他 mod 可直接调用（static 单例，当前世界作用域）。
 */
public final class CryptandSqlite {

    private static volatile SqliteStore store;
    private static volatile KvTable kv;
    private static volatile NbtTable nbt;
    private static volatile DeviceCacheTable deviceCache;
    private static volatile WireNetworkTable wireNetwork;

    private CryptandSqlite() {}

    /** 打开（或创建）当前世界的 SQLite 数据库。世界加载时调用。 */
    public static void open(ServerLevel level) {
        close();
        if (level == null) return;
        try {
            Path root = level.getServer().getWorldPath(LevelResource.ROOT);
            Path dir = root.resolve("cryptand");
            Files.createDirectories(dir);
            store = new SqliteStore(dir.resolve("data.sqlite"));
            kv = new KvTable(store);
            nbt = new NbtTable(store);
            deviceCache = new DeviceCacheTable(store);
            wireNetwork = new WireNetworkTable(store);
            CryptandNeoForge.WAF_LOGGER.info("[Sqlite] opened {} ({} bytes)",
                    dir.resolve("data.sqlite"), Files.exists(dir.resolve("data.sqlite"))
                            ? Files.size(dir.resolve("data.sqlite")) : 0);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.error("[Sqlite] open failed", t);
            close();
        }
    }

    /** 关闭：flush 所有排队写落盘 + 关连接。世界卸载/服务端停止时调用。
     *  ⚠ 诊断（2026-08-14 保存世界卡住定位）：进入/结束/耗时打印。 */
    public static void close() {
        SqliteStore s = store;
        store = null;
        kv = null;
        nbt = null;
        deviceCache = null;
        wireNetwork = null;
        if (s != null) {
            long t0 = System.currentTimeMillis();
            CryptandNeoForge.WAF_LOGGER.info("[Sqlite] close begin");
            try {
                s.close();
                CryptandNeoForge.WAF_LOGGER.info("[Sqlite] closed in {}ms",
                        System.currentTimeMillis() - t0);
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER.error("[Sqlite] close failed", t);
            }
        }
    }

    /** 是否已打开（当前世界作用域） */
    public static boolean isOpen() { return store != null; }

    /** 上次 open 尝试时间（失败冷却：防每 tick 刷屏 + 浪费） */
    private static volatile long lastOpenAttemptMs = 0;

    /** 兜底打开（2026-08-14 重进丢失根因：LevelEvent.Load 可能未触发 open，
     *  导致 SQLite 从未创建/导线从未恢复）。ServerTick 每 tick 调用：
     *  已打开 → 直接返回（零开销）；未打开 → open。
     *  ⚠ 全 try-catch + 失败冷却 5s：早期环境/类加载问题不崩溃、不刷屏。 */
    public static void ensureOpen(ServerLevel level) {
        if (store == null && level != null) {
            long now = System.currentTimeMillis();
            if (now - lastOpenAttemptMs < 5000) return; // 失败冷却 5s
            lastOpenAttemptMs = now;
            try {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[Sqlite] ensureOpen: store null, opening on ServerTick fallback");
                open(level);
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[Sqlite] ensureOpen failed (retry in 5s): {}", t);
            }
        }
    }

    /** 高级键值存储（未打开 → null） */
    public static KvTable kv() { return kv; }

    /** 类 NBT 接口（未打开 → null；可选 API，不用于电路缓存——缓存走强类型表） */
    public static NbtTable nbt() { return nbt; }

    /** 设备缓存表（强类型列存储，无 NBT；未打开 → null） */
    public static DeviceCacheTable deviceCache() { return deviceCache; }

    /** 自管导线网络表（强类型列存储；未打开 → null） */
    public static WireNetworkTable wireNetwork() { return wireNetwork; }
}
