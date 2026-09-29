package com.hdf.cryptand.circuitsimulation.db;

import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.AssemblerRecord;
import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.DeviceInfoRecord;
import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.NetlistSnapshot;
import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.NetworkCacheRecord;
import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.NetworkRecord;
import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.RendererRecord;
import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.WireRecord;
import com.hdf.cryptand.core.storage.AsyncOp;
import com.hdf.cryptand.core.storage.SqliteFactory;
import com.hdf.cryptand.core.storage.SqliteStore;

import java.io.Closeable;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网表数据库（2026-08-15 用户架构：仿真电路核心内容的 SQLite 持久化管理类）。
 * <p>
 * 一个 {@code NetlistDatabase} = 一个 {@code <world>/cryptand/simulation_circuit/}
 * 下的 SQLite 文件（“仿真电路”的英文翻译 = Simulation Circuit；文件名
 * {@code simulation_circuit.sqlite}），内含按【具体网络】组织的表：
 * <ul>
 *   <li><b>meta</b>        —— 元数据（schema 版本 + 64 位 id 盐/计数器持久化）</li>
 *   <li><b>network</b>     —— 网络核心数据表（频率/接地节点/维度/时间步长/版本/扩展）</li>
 *   <li><b>assembler</b>   —— 网络组装器表（64 位 id + 组合顺序 order + 设备类/坐标/
 *                             端子数/参数——按序可再组合回来）</li>
 *   <li><b>wire</b>        —— 网络导线表（双端点键 + 电阻/长度/温度/渲染引用/染色）</li>
 *   <li><b>renderer</b>    —— 渲染器表（材质/颜色/悬垂率/粗细/扩展参数）</li>
 * </ul>
 * <b>多实例 + 多线程（消息机制）</b>：
 * <ul>
 *   <li>经 {@link SqliteFactory} 打开（命名实例）——多个数据库可并存，各自独立
 *      单写线程。</li>
 *   <li>写一律异步排队（{@code saveXxx} 返回 {@link AsyncOp}）：不阻塞主线程/求解
 *      线程池；完成经 {@code thenRun/whenComplete} 或 {@link NetlistDbListener}
 *      消息通知（订阅者多线程安全）。</li>
 *   <li>读同步点查（{@code loadXxx}）：SQLite WAL 下读不阻塞写。</li>
 *   <li>快照保存走单事务（{@link #saveSnapshotAsync}），崩溃安全。</li>
 * </ul>
 * 本类无 Minecraft 依赖（common 核心），任一模组平台可直接使用。
 */
public final class NetlistDatabase implements Closeable {

    /** 默认实例名（工厂内标识） */
    public static final String DEFAULT_NAME = "netlist";
    /** 默认数据库文件名（2026-08-15 用户要求：数据库名“仿真电路.sqlite”，
     *  “仿真电路”英文翻译 = Simulation Circuit → snake_case 文件名；
     *  旧名 netlist.sqlite 由适配层在世界加载时自动迁移） */
    public static final String DEFAULT_FILE = "simulation_circuit.sqlite";
    /** 旧数据库文件名（迁移用） */
    public static final String LEGACY_FILE = "netlist.sqlite";

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("cryptand");

    /** schema 版本（表结构变更 +1；旧库自动 ALTER 补列策略见各表） */
    private static final int SCHEMA_VERSION = 1;

    /** meta 键 */
    private static final String META_SCHEMA = "schema_version";
    private static final String META_ID_SALT = "id_salt";
    private static final String META_ID_COUNTER = "id_counter";

    private final String name;
    private final SqliteStore store;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 64 位 id 生成器（盐/计数器持久化于 meta，跨会话续用） */
    private final SimulationIdGen idGen;

    /** 表访问器（各表一个，读写走 store） */
    private final NetworkCoreTable networks = new NetworkCoreTable();
    private final AssemblerTable assemblers = new AssemblerTable();
    private final WireTable wires = new WireTable();
    private final RendererTable renderers = new RendererTable();

    /** 消息监听器（CopyOnWrite：可多线程安全增删） */
    private final CopyOnWriteArrayList<NetlistDbListener> listeners =
            new CopyOnWriteArrayList<>();

    /** 已打开实例（按名单例：id 盐/计数器状态共享，避免多包装器 ID 冲突） */
    private static final ConcurrentHashMap<String, NetlistDatabase> INSTANCES =
            new ConcurrentHashMap<>();

    /**
     * 打开（或返回已存在）命名网表数据库。
     * <p>
     * 同名字符串幂等返回【同一实例】（共享 {@link SimulationIdGen} 状态，不重复
     * 建连接/不丢排队写）；关闭后重开则重建。
     *
     * @param name 实例名（工厂标识）
     * @param dir  数据库目录（应为 {@code <world>/cryptand/simulation_circuit}）
     */
    public static NetlistDatabase open(String name, Path dir) throws SQLException {
        NetlistDatabase existing = INSTANCES.get(name);
        if (existing != null && !existing.isClosed()) return existing;
        synchronized (INSTANCES) {
            existing = INSTANCES.get(name);
            if (existing != null && !existing.isClosed()) return existing;
            try {
                java.nio.file.Files.createDirectories(dir);
            } catch (java.io.IOException e) {
                throw new SQLException("NetlistDatabase open: create dir failed " + dir, e);
            }
            SqliteStore store = SqliteFactory.open(name, dir.resolve(DEFAULT_FILE));
            NetlistDatabase db = new NetlistDatabase(name, store);
            INSTANCES.put(name, db);
            return db;
        }
    }

    /** 打开（默认实例名） */
    public static NetlistDatabase open(Path dir) throws SQLException {
        return open(DEFAULT_NAME, dir);
    }

    /** 取已打开实例（未打开 → null） */
    public static NetlistDatabase current(String name) {
        NetlistDatabase db = INSTANCES.get(name);
        return db != null && !db.isClosed() ? db : null;
    }

    /** 取已打开实例（默认实例名；未打开 → null） */
    public static NetlistDatabase current() {
        return current(DEFAULT_NAME);
    }

    private NetlistDatabase(String name, SqliteStore store) throws SQLException {
        this.name = name;
        this.store = store;
        // 建表（幂等）+ 恢复 id 盐/计数器
        store.transaction((Connection c) -> {
            MetaTable.create(c);
            networks.create(c);
            assemblers.create(c);
            wires.create(c);
            renderers.create(c);
            NetworkCacheTable.create(c);
            // 2026-09-15：设备信息表（组装器语境下的 per-方块 KV）
            DeviceInfoTable.create(c);
        });
        long salt = MetaTable.getLong(store, META_ID_SALT, -1);
        long counter = MetaTable.getLong(store, META_ID_COUNTER, 0);
        if (salt < 0) {
            salt = SimulationIdGen.randomSalt();
            MetaTable.putLong(store, META_ID_SALT, salt);
            MetaTable.putLong(store, META_ID_COUNTER, 0);
            counter = 0;
        }
        this.idGen = new SimulationIdGen(salt, counter);
        for (NetlistDbListener l : listeners) {
            try {
                l.onOpened(this);
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 生命周期 ====================

    /** 实例名 */
    public String name() {
        return name;
    }

    /** 底层存储（高级用法；一般不需要） */
    public SqliteStore store() {
        return store;
    }

    /** 是否已关闭 */
    public boolean isClosed() {
        return closed.get();
    }

    /** 是否已打开（工厂内仍注册） */
    public boolean isOpen() {
        return !closed.get() && SqliteFactory.isOpen(name);
    }

    /**
     * 关闭：flush 所有排队写落盘 + 关连接（工厂注销）。
     * 可重复调用（幂等）。关闭后所有 save 返回失败 AsyncOp。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        INSTANCES.remove(name, this);
        SqliteFactory.close(name);
        for (NetlistDbListener l : listeners) {
            try {
                l.onClosed(this);
            } catch (Throwable ignored) {
            }
        }
        LOGGER.info("[NetlistDb] closed {}", name);
    }

    // ==================== 消息机制（监听器） ====================

    /** 订阅消息（异步操作完成/打开/关闭通知；多线程安全） */
    public void addListener(NetlistDbListener l) {
        if (l != null) listeners.add(l);
    }

    /** 取消订阅 */
    public void removeListener(NetlistDbListener l) {
        if (l != null) listeners.remove(l);
    }

    private void notifySave(NetlistSnapshot snap) {
        for (NetlistDbListener l : listeners) {
            try {
                l.onSaveComplete(this, snap);
            } catch (Throwable ignored) {
            }
        }
    }

    private void notifyLoad(NetlistSnapshot snap) {
        for (NetlistDbListener l : listeners) {
            try {
                l.onLoadComplete(this, snap);
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 64 位 ID ====================

    /** 下一个网络 id */
    public long nextNetworkId() {
        return idGen.next();
    }

    /** 下一个组装器 id（每个组装器一个 64 位 id，全局唯一） */
    public long nextAssemblerId() {
        return idGen.next();
    }

    /** 下一个导线 id */
    public long nextWireId() {
        return idGen.next();
    }

    /** 下一个通用 64 位 id（任意实体；稳定注册表分配用） */
    public long nextId() {
        return idGen.next();
    }

    /** 当前盐（持久化值；诊断） */
    public long idSalt() {
        return idGen.salt();
    }

    /** 已分配 id 数（诊断） */
    public long idCounter() {
        return idGen.counter();
    }

    /** 持久化 id 计数器（每次保存快照后调用，防跨会话重复） */
    public void persistIdCounter() {
        try {
            MetaTable.putLong(store, META_ID_COUNTER, idGen.counter());
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] persist id counter failed", e);
        }
    }

    // ==================== 网络核心数据表 ====================

    /** 保存（插入或更新）网络核心数据。异步写，返回句柄可 wait/消息通知。 */
    public AsyncOp saveNetwork(NetworkRecord rec) {
        if (rec == null || closed.get()) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> networks.upsert(c, rec));
    }

    /** 按 id 读网络核心数据（无 → null；同步点查） */
    public NetworkRecord loadNetwork(long id) {
        try {
            return store.query((Connection c) -> networks.get(c, id));
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadNetwork {} failed", id, e);
            return null;
        }
    }

    /** 读全部网络核心数据（同步） */
    public List<NetworkRecord> loadAllNetworks() {
        try {
            return store.query(NetworkCoreTable::all);
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadAllNetworks failed", e);
            return new ArrayList<>();
        }
    }

    /** 删除网络及其全部从属数据（组装器/导线；渲染器保留全局）。异步写。 */
    public AsyncOp deleteNetwork(long id) {
        if (closed.get()) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> {
            networks.delete(c, id);
            assemblers.deleteByNetwork(c, id);
            wires.deleteByNetwork(c, id);
        });
    }

    // ==================== 网络组装器表 ====================

    /** 保存（插入或更新）组装器。异步写。 */
    public AsyncOp saveAssembler(AssemblerRecord rec) {
        if (rec == null || closed.get()) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> assemblers.upsert(c, rec));
    }

    /** 按 id 读组装器（无 → null） */
    public AssemblerRecord loadAssembler(long id) {
        try {
            return store.query((Connection c) -> assemblers.get(c, id));
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadAssembler {} failed", id, e);
            return null;
        }
    }

    /** 读某网络全部组装器（按 order 排序——组合顺序，可再组合回来；同步） */
    public List<AssemblerRecord> loadAssemblers(long networkId) {
        try {
            return store.query((Connection c) -> assemblers.byNetwork(c, networkId));
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadAssemblers {} failed", networkId, e);
            return new ArrayList<>();
        }
    }

    /** 删除组装器（异步） */
    public AsyncOp deleteAssembler(long id) {
        if (closed.get()) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> assemblers.delete(c, id));
    }

    // ==================== 网络导线表 ====================

    /** 保存（插入或更新）导线。异步写。 */
    public AsyncOp saveWire(WireRecord rec) {
        if (rec == null || closed.get()) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> wires.upsert(c, rec));
    }

    /** 按 id 读导线（无 → null） */
    public WireRecord loadWire(long id) {
        try {
            return store.query((Connection c) -> wires.get(c, id));
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadWire {} failed", id, e);
            return null;
        }
    }

    /** 读某网络全部导线（同步） */
    public List<WireRecord> loadWires(long networkId) {
        try {
            return store.query((Connection c) -> wires.byNetwork(c, networkId));
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadWires {} failed", networkId, e);
            return new ArrayList<>();
        }
    }

    // ==================== 渲染器表 ====================

    /** 保存（插入或更新）渲染器。异步写。 */
    public AsyncOp saveRenderer(RendererRecord rec) {
        if (rec == null || closed.get()) return AsyncOp.done();
        return store.asyncWrite((Connection c) -> renderers.upsert(c, rec));
    }

    /** 按 id 读渲染器（无 → null） */
    public RendererRecord loadRenderer(String id) {
        try {
            return store.query((Connection c) -> renderers.get(c, id));
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadRenderer {} failed", id, e);
            return null;
        }
    }

    /** 读全部渲染器（同步；含全局 networkId=0 与各网络覆盖） */
    public List<RendererRecord> loadAllRenderers() {
        try {
            return store.query(RendererTable::all);
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadAllRenderers failed", e);
            return new ArrayList<>();
        }
    }

    // ==================== 网络求解缓存表（2026-08-15） ====================

    /**
     * 异步全量替换网络缓存表（ESC/自动保存时批量落库，不阻塞主线程）。
     * <p>
     * 缓存机制约定（用户要求）：正常游戏期间【零 SQLite 交互】——求解结果先
     * 只进内存（{@code NetworkCacheManager}），世界保存时一次性批量写；世界
     * 加载时一次性全量读回内存。单事务清空+全量写 = 缓存表为当前世界最新
     * 快照（已消失网络的陈旧缓存自动清理）。
     */
    public AsyncOp saveNetworkCachesAsync(List<NetworkCacheRecord> list) {
        if (closed.get()) return AsyncOp.done();
        if (list == null) list = java.util.Collections.emptyList();
        final List<NetworkCacheRecord> l = list;
        return store.asyncWrite((Connection c) -> NetworkCacheTable.replaceAll(c, l));
    }

    /** 同步全量替换网络缓存表（退出世界时调用：立即落盘，不依赖 flush 时机）。 */
    public AsyncOp saveNetworkCachesSync(List<NetworkCacheRecord> list) throws SQLException {
        if (closed.get()) return AsyncOp.done();
        if (list == null) list = java.util.Collections.emptyList();
        final List<NetworkCacheRecord> l = list;
        store.transaction((Connection c) -> NetworkCacheTable.replaceAll(c, l));
        return AsyncOp.done();
    }

    /** 同步读全部网络缓存（世界加载时一次性载入内存）。 */
    public List<NetworkCacheRecord> loadAllNetworkCaches() {
        try {
            return store.query(NetworkCacheTable::all);
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadAllNetworkCaches failed", e);
            return new ArrayList<>();
        }
    }

    // ==================== 设备信息表（2026-09-15 用户） ====================

    /**
     * 设备信息：异步全量替换（世界保存/ESC 自动保存时调用，不阻塞主线程）。
     * <p>
     * 与网络缓存同一约定（用户要求）：游戏期间【零 SQLite 交互】——设备信息先在
     * 内存里累积，世界保存时一次性批量写；世界加载时一次性全量读回。
     * 单事务清空 + 全量写 = 表内容始终是当前世界的最新快照（消失设备的陈旧行自动清理）。
     */
    public AsyncOp saveDeviceInfosAsync(List<DeviceInfoRecord> list) {
        if (closed.get()) return AsyncOp.done();
        List<DeviceInfoRecord> l = list == null
                ? java.util.Collections.emptyList() : list;
        return store.asyncWrite((Connection c) -> DeviceInfoTable.replaceAll(c, l));
    }

    /** 设备信息：同步全量替换（退出世界时调用：立即落盘，不依赖 flush 时机）。 */
    public AsyncOp saveDeviceInfosSync(List<DeviceInfoRecord> list) throws SQLException {
        if (closed.get()) return AsyncOp.done();
        List<DeviceInfoRecord> l = list == null
                ? java.util.Collections.emptyList() : list;
        store.transaction((Connection c) -> DeviceInfoTable.replaceAll(c, l));
        return AsyncOp.done();
    }

    /** 设备信息：同步读全部（世界加载时一次性载入内存）。 */
    public List<DeviceInfoRecord> loadAllDeviceInfos() {
        try {
            return store.query(DeviceInfoTable::all);
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] loadAllDeviceInfos failed", e);
            return new ArrayList<>();
        }
    }

    // ==================== 快照（整库保存/恢复） ====================

    /**
     * 快照异步保存：四表【单事务】全量替换（先清后写，表 = 快照，无残留）。
     * 不阻塞调用方（写线程执行）；完成经返回的 {@link AsyncOp} 或监听器消息
     * {@link NetlistDbListener#onSaveComplete} 通知。
     */
    public AsyncOp saveSnapshotAsync(NetlistSnapshot snap) {
        if (closed.get()) return AsyncOp.done();
        if (snap == null) snap = NetlistSnapshot.empty();
        final NetlistSnapshot s = snap;
        AsyncOp op = store.asyncWrite((Connection c) -> {
            saveSnapshotNow(c, s);
            persistIdCounterNow(c);
        });
        op.thenRun(() -> notifySave(s));
        return op;
    }

    /** 快照同步保存（单事务全量替换，立即落盘）。返回已完成的 AsyncOp。 */
    public AsyncOp saveSnapshotSync(NetlistSnapshot snap) throws SQLException {
        if (closed.get()) return AsyncOp.done();
        if (snap == null) snap = NetlistSnapshot.empty();
        final NetlistSnapshot s = snap;
        store.transaction((Connection c) -> {
            saveSnapshotNow(c, s);
            persistIdCounterNow(c);
        });
        notifySave(s);
        return AsyncOp.done();
    }

    private void saveSnapshotNow(Connection c, NetlistSnapshot snap) throws Exception {
        ReplaceAll.networks(c, snap.networks());
        ReplaceAll.assemblers(c, snap.assemblers());
        ReplaceAll.wires(c, snap.wires());
        ReplaceAll.renderers(c, snap.renderers());
    }

    private void persistIdCounterNow(Connection c) throws Exception {
        MetaTable.putLongNow(c, META_ID_COUNTER, idGen.counter());
    }

    /**
     * 快照同步恢复：读取全部表 → 组装成 {@link NetlistSnapshot}（不可变）→
     * 通知监听器。进世界时调用（数据立即可用；再组合由调用方按
     * {@code AssemblerRecord.order()} 重建）。
     */
    public NetlistSnapshot restore() {
        if (closed.get()) return NetlistSnapshot.empty();
        try {
            NetlistSnapshot snap = store.query((Connection c) -> new NetlistSnapshot(
                    networks.all(c), assemblers.all(c), wires.all(c), renderers.all(c)));
            notifyLoad(snap);
            return snap;
        } catch (SQLException e) {
            LOGGER.warn("[NetlistDb] restore failed", e);
            return NetlistSnapshot.empty();
        }
    }

    // ==================== 统计（诊断） ====================

    /** 各表行数（诊断；返回 "network=..,assembler=..,wire=..,renderer=.."） */
    public String stats() {
        try {
            return store.query((Connection c) -> "network=" + networks.count(c)
                    + ",assembler=" + assemblers.count(c)
                    + ",wire=" + wires.count(c)
                    + ",renderer=" + renderers.count(c));
        } catch (SQLException e) {
            return "stats-error";
        }
    }

    // ==================== meta 表 ====================

    private static final class MetaTable {
        private static void create(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS meta ("
                            + "k TEXT PRIMARY KEY, v TEXT NOT NULL)")) {
                ps.executeUpdate();
            }
            // 初始化 schema 版本（无 → 写当前版本）
            if (getLongNow(c, META_SCHEMA, -1) < 0) {
                putLongNow(c, META_SCHEMA, SCHEMA_VERSION);
            }
        }

        private static long getLong(SqliteStore store, String key, long def) {
            try {
                return store.query((Connection c) -> getLongNow(c, key, def));
            } catch (SQLException e) {
                return def;
            }
        }

        private static long getLongNow(Connection c, String key, long def) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("SELECT v FROM meta WHERE k = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        try {
                            return Long.parseLong(rs.getString("v"));
                        } catch (NumberFormatException e) {
                            return def;
                        }
                    }
                }
            }
            return def;
        }

        private static void putLong(SqliteStore store, String key, long v) throws SQLException {
            store.execute((Connection c) -> putLongNow(c, key, v));
        }

        private static void putLongNow(Connection c, String key, long v) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO meta(k, v) VALUES(?, ?)")) {
                ps.setString(1, key);
                ps.setString(2, Long.toString(v));
                ps.executeUpdate();
            }
        }
    }

    // ==================== 网络核心数据表 ====================

    private static final class NetworkCoreTable {
        private static void create(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS network ("
                            + "id INTEGER PRIMARY KEY, "
                            + "name TEXT, "
                            + "frequency REAL NOT NULL DEFAULT 0, "
                            + "ground_node INTEGER NOT NULL DEFAULT 0, "
                            + "dimension TEXT, "
                            + "dt REAL NOT NULL DEFAULT 0.05, "
                            + "version INTEGER NOT NULL DEFAULT 0, "
                            + "extra TEXT, "
                            + "updated_at INTEGER NOT NULL DEFAULT 0)")) {
                ps.executeUpdate();
            }
        }

        private static void upsert(Connection c, NetworkRecord r) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO network"
                            + "(id,name,frequency,ground_node,dimension,dt,version,extra,updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, r.id());
                ps.setString(2, r.name());
                ps.setDouble(3, r.frequency());
                ps.setInt(4, r.groundNode());
                ps.setString(5, r.dimension());
                ps.setDouble(6, r.dt());
                ps.setLong(7, r.version());
                ps.setString(8, r.extra());
                ps.setLong(9, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }

        private static NetworkRecord get(Connection c, long id) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,name,frequency,ground_node,dimension,dt,version,extra "
                            + "FROM network WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            }
        }

        private static List<NetworkRecord> all(Connection c) throws Exception {
            List<NetworkRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,name,frequency,ground_node,dimension,dt,version,extra "
                            + "FROM network ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        }

        private static NetworkRecord row(ResultSet rs) throws Exception {
            return new NetworkRecord(
                    rs.getLong("id"),
                    rs.getString("name"),
                    rs.getDouble("frequency"),
                    rs.getInt("ground_node"),
                    rs.getString("dimension"),
                    rs.getDouble("dt"),
                    rs.getLong("version"),
                    rs.getString("extra"));
        }

        private static void delete(Connection c, long id) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM network WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }

        private static int count(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM network");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ==================== 网络组装器表 ====================

    private static final class AssemblerTable {
        private static void create(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS assembler ("
                            + "id INTEGER PRIMARY KEY, "
                            + "network_id INTEGER NOT NULL, "
                            + "ord INTEGER NOT NULL DEFAULT 0, "
                            + "device_class TEXT, "
                            + "x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, "
                            + "terminal_count INTEGER NOT NULL DEFAULT 2, "
                            + "params TEXT, "
                            + "updated_at INTEGER NOT NULL DEFAULT 0)")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE INDEX IF NOT EXISTS idx_assembler_net ON assembler(network_id, ord)")) {
                ps.executeUpdate();
            }
        }

        private static void upsert(Connection c, AssemblerRecord r) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO assembler"
                            + "(id,network_id,ord,device_class,x,y,z,terminal_count,params,updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, r.id());
                ps.setLong(2, r.networkId());
                ps.setInt(3, r.order());
                ps.setString(4, r.deviceClass());
                ps.setInt(5, r.x());
                ps.setInt(6, r.y());
                ps.setInt(7, r.z());
                ps.setInt(8, r.terminalCount());
                ps.setString(9, r.params());
                ps.setLong(10, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }

        private static AssemblerRecord get(Connection c, long id) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,network_id,ord,device_class,x,y,z,terminal_count,params "
                            + "FROM assembler WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            }
        }

        /** 按网络读全部组装器（按 ord 排序 = 组合顺序，可再组合回来） */
        private static List<AssemblerRecord> byNetwork(Connection c, long networkId)
                throws Exception {
            List<AssemblerRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,network_id,ord,device_class,x,y,z,terminal_count,params "
                            + "FROM assembler WHERE network_id = ? ORDER BY ord, id")) {
                ps.setLong(1, networkId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(row(rs));
                }
            }
            return out;
        }

        private static List<AssemblerRecord> all(Connection c) throws Exception {
            List<AssemblerRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,network_id,ord,device_class,x,y,z,terminal_count,params "
                            + "FROM assembler ORDER BY network_id, ord, id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        }

        private static AssemblerRecord row(ResultSet rs) throws Exception {
            return new AssemblerRecord(
                    rs.getLong("id"),
                    rs.getLong("network_id"),
                    rs.getInt("ord"),
                    rs.getString("device_class"),
                    rs.getInt("x"),
                    rs.getInt("y"),
                    rs.getInt("z"),
                    rs.getInt("terminal_count"),
                    rs.getString("params"));
        }

        private static void delete(Connection c, long id) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM assembler WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }

        private static void deleteByNetwork(Connection c, long networkId) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM assembler WHERE network_id = ?")) {
                ps.setLong(1, networkId);
                ps.executeUpdate();
            }
        }

        private static int count(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM assembler");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ==================== 网络导线表 ====================

    private static final class WireTable {
        private static void create(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS wire ("
                            + "id INTEGER PRIMARY KEY, "
                            + "network_id INTEGER NOT NULL, "
                            + "a_key TEXT NOT NULL, b_key TEXT NOT NULL, "
                            + "resistance REAL NOT NULL DEFAULT 0, "
                            + "length REAL NOT NULL DEFAULT 0, "
                            + "temperature_key TEXT, "
                            + "renderer_id TEXT, "
                            + "color_override INTEGER NOT NULL DEFAULT 0, "
                            + "self_placed INTEGER NOT NULL DEFAULT 0, "
                            + "item_id TEXT, "
                            + "updated_at INTEGER NOT NULL DEFAULT 0)")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE INDEX IF NOT EXISTS idx_wire_net ON wire(network_id)")) {
                ps.executeUpdate();
            }
        }

        private static void upsert(Connection c, WireRecord r) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO wire"
                            + "(id,network_id,a_key,b_key,resistance,length,temperature_key,"
                            + "renderer_id,color_override,self_placed,item_id,updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, r.id());
                ps.setLong(2, r.networkId());
                ps.setString(3, r.aKey());
                ps.setString(4, r.bKey());
                ps.setDouble(5, r.resistance());
                ps.setDouble(6, r.length());
                ps.setString(7, r.temperatureKey());
                ps.setString(8, r.rendererId());
                ps.setInt(9, r.colorOverride());
                ps.setInt(10, r.selfPlaced() ? 1 : 0);
                ps.setString(11, r.itemId());
                ps.setLong(12, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }

        private static WireRecord get(Connection c, long id) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,network_id,a_key,b_key,resistance,length,temperature_key,"
                            + "renderer_id,color_override,self_placed,item_id FROM wire WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            }
        }

        private static List<WireRecord> byNetwork(Connection c, long networkId)
                throws Exception {
            List<WireRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,network_id,a_key,b_key,resistance,length,temperature_key,"
                            + "renderer_id,color_override,self_placed,item_id "
                            + "FROM wire WHERE network_id = ? ORDER BY id")) {
                ps.setLong(1, networkId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) out.add(row(rs));
                }
            }
            return out;
        }

        private static List<WireRecord> all(Connection c) throws Exception {
            List<WireRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,network_id,a_key,b_key,resistance,length,temperature_key,"
                            + "renderer_id,color_override,self_placed,item_id FROM wire ORDER BY id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        }

        private static WireRecord row(ResultSet rs) throws Exception {
            return new WireRecord(
                    rs.getLong("id"),
                    rs.getLong("network_id"),
                    rs.getString("a_key"),
                    rs.getString("b_key"),
                    rs.getDouble("resistance"),
                    rs.getDouble("length"),
                    rs.getString("temperature_key"),
                    rs.getString("renderer_id"),
                    rs.getInt("color_override"),
                    rs.getInt("self_placed") != 0,
                    rs.getString("item_id"));
        }

        private static void deleteByNetwork(Connection c, long networkId) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM wire WHERE network_id = ?")) {
                ps.setLong(1, networkId);
                ps.executeUpdate();
            }
        }

        private static int count(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM wire");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ==================== 渲染器表 ====================

    private static final class RendererTable {
        private static void create(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS renderer ("
                            + "id TEXT PRIMARY KEY, "
                            + "network_id INTEGER NOT NULL DEFAULT 0, "
                            + "texture TEXT, "
                            + "color INTEGER NOT NULL DEFAULT -1, "
                            + "sag REAL NOT NULL DEFAULT 2.0, "
                            + "thickness REAL NOT NULL DEFAULT 0.0625, "
                            + "params TEXT, "
                            + "updated_at INTEGER NOT NULL DEFAULT 0)")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE INDEX IF NOT EXISTS idx_renderer_net ON renderer(network_id)")) {
                ps.executeUpdate();
            }
        }

        private static void upsert(Connection c, RendererRecord r) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR REPLACE INTO renderer"
                            + "(id,network_id,texture,color,sag,thickness,params,updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, r.id());
                ps.setLong(2, r.networkId());
                ps.setString(3, r.texture());
                ps.setInt(4, r.color());
                ps.setDouble(5, r.sag());
                ps.setDouble(6, r.thickness());
                ps.setString(7, r.params());
                ps.setLong(8, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }

        private static RendererRecord get(Connection c, String id) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,network_id,texture,color,sag,thickness,params "
                            + "FROM renderer WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? row(rs) : null;
                }
            }
        }

        private static List<RendererRecord> all(Connection c) throws Exception {
            List<RendererRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,network_id,texture,color,sag,thickness,params "
                            + "FROM renderer ORDER BY network_id, id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(row(rs));
            }
            return out;
        }

        private static RendererRecord row(ResultSet rs) throws Exception {
            return new RendererRecord(
                    rs.getString("id"),
                    rs.getLong("network_id"),
                    rs.getString("texture"),
                    rs.getInt("color"),
                    rs.getDouble("sag"),
                    rs.getDouble("thickness"),
                    rs.getString("params"));
        }

        private static int count(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM renderer");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // ==================== 设备信息表 ====================

    private static final class DeviceInfoTable {
        private static void create(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS device_info ("
                            + "dim TEXT NOT NULL DEFAULT '', "
                            + "x INTEGER NOT NULL, "
                            + "y INTEGER NOT NULL, "
                            + "z INTEGER NOT NULL, "
                            + "device_class TEXT, "
                            + "info BLOB, "
                            + "updated_at INTEGER NOT NULL DEFAULT 0, "
                            + "PRIMARY KEY (dim, x, y, z))")) {
                ps.executeUpdate();
            }
        }

        /** 全量替换（清空 + 批量写；世界保存时单事务内调用） */
        private static void replaceAll(Connection c, List<DeviceInfoRecord> list)
                throws Exception {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM device_info");
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT OR REPLACE INTO device_info"
                                 + "(dim,x,y,z,device_class,info,updated_at)"
                                 + " VALUES(?,?,?,?,?,?,?)")) {
                del.executeUpdate();
                for (DeviceInfoRecord r : list) {
                    if (r == null || r.dim() == null) continue;
                    ps.setString(1, r.dim());
                    ps.setInt(2, r.x());
                    ps.setInt(3, r.y());
                    ps.setInt(4, r.z());
                    ps.setString(5, r.deviceClass());
                    ps.setBytes(6, r.info());
                    ps.setLong(7, r.updatedAt());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        private static List<DeviceInfoRecord> all(Connection c) throws Exception {
            List<DeviceInfoRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT dim,x,y,z,device_class,info,updated_at FROM device_info");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DeviceInfoRecord(
                            rs.getString("dim"),
                            rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                            rs.getString("device_class"),
                            rs.getBytes("info"),
                            rs.getLong("updated_at")));
                }
            }
            return out;
        }
    }

    // ==================== 网络求解缓存表 ====================

    private static final class NetworkCacheTable {
        private static void create(Connection c) throws Exception {
            try (PreparedStatement ps = c.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS network_cache ("
                            + "signature TEXT PRIMARY KEY, "
                            + "network_id INTEGER NOT NULL DEFAULT -1, "
                            + "seed_key TEXT, "
                            + "frequency REAL NOT NULL DEFAULT 0, "
                            + "node_count INTEGER NOT NULL DEFAULT 0, "
                            + "mode TEXT, "
                            + "voltages TEXT, "
                            + "complex TEXT, "
                            + "structure TEXT, "
                            + "mapping TEXT, "
                            + "updated_at INTEGER NOT NULL DEFAULT 0)")) {
                ps.executeUpdate();
            }
            // 2026-08-19 兼容旧库：补列（CREATE TABLE IF NOT EXISTS 不会加新列）
            try {
                c.createStatement().executeUpdate(
                        "ALTER TABLE network_cache ADD COLUMN structure TEXT");
            } catch (Throwable ignored) { }
            try {
                c.createStatement().executeUpdate(
                        "ALTER TABLE network_cache ADD COLUMN mapping TEXT");
            } catch (Throwable ignored) { }
        }

        /** 全量替换（清空 + 批量写；世界保存时单事务内调用） */
        private static void replaceAll(Connection c, List<NetworkCacheRecord> list)
                throws Exception {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM network_cache");
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT OR REPLACE INTO network_cache"
                                 + "(signature,network_id,seed_key,frequency,node_count,mode,"
                                 + "voltages,complex,structure,mapping,updated_at)"
                                 + " VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                del.executeUpdate();
                for (NetworkCacheRecord r : list) {
                    if (r == null || r.signature() == null) continue;
                    ps.setString(1, r.signature());
                    ps.setLong(2, r.networkId());
                    ps.setString(3, r.seedKey());
                    ps.setDouble(4, r.frequency());
                    ps.setInt(5, r.nodeCount());
                    ps.setString(6, r.mode());
                    ps.setString(7, r.voltages());
                    ps.setString(8, r.complex());
                    ps.setString(9, r.structure());
                    ps.setString(10, r.mapping());
                    ps.setLong(11, r.updatedAt());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        private static List<NetworkCacheRecord> all(Connection c) throws Exception {
            List<NetworkCacheRecord> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT signature,network_id,seed_key,frequency,node_count,mode,"
                            + "voltages,complex,structure,mapping,updated_at FROM network_cache");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new NetworkCacheRecord(
                            rs.getString("signature"),
                            rs.getLong("network_id"),
                            rs.getString("seed_key"),
                            rs.getDouble("frequency"),
                            rs.getInt("node_count"),
                            rs.getString("mode"),
                            rs.getString("voltages"),
                            rs.getString("complex"),
                            rs.getString("structure"),
                            rs.getString("mapping"),
                            rs.getLong("updated_at")));
                }
            }
            return out;
        }
    }

    // ==================== 全表替换（快照保存用） ====================

    /** 网络表全量替换（清空 + 批量写；单事务内调用） */
    private static final class ReplaceAll {
        private ReplaceAll() {}

        static void networks(Connection c, List<NetworkRecord> list) throws Exception {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM network");
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT OR REPLACE INTO network"
                                 + "(id,name,frequency,ground_node,dimension,dt,version,extra,updated_at) "
                                 + "VALUES(?,?,?,?,?,?,?,?,?)")) {
                del.executeUpdate();
                for (NetworkRecord r : list) {
                    if (r == null) continue;
                    ps.setLong(1, r.id());
                    ps.setString(2, r.name());
                    ps.setDouble(3, r.frequency());
                    ps.setInt(4, r.groundNode());
                    ps.setString(5, r.dimension());
                    ps.setDouble(6, r.dt());
                    ps.setLong(7, r.version());
                    ps.setString(8, r.extra());
                    ps.setLong(9, System.currentTimeMillis());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        static void assemblers(Connection c, List<AssemblerRecord> list) throws Exception {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM assembler");
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT OR REPLACE INTO assembler"
                                 + "(id,network_id,ord,device_class,x,y,z,terminal_count,params,updated_at) "
                                 + "VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                del.executeUpdate();
                for (AssemblerRecord r : list) {
                    if (r == null) continue;
                    ps.setLong(1, r.id());
                    ps.setLong(2, r.networkId());
                    ps.setInt(3, r.order());
                    ps.setString(4, r.deviceClass());
                    ps.setInt(5, r.x());
                    ps.setInt(6, r.y());
                    ps.setInt(7, r.z());
                    ps.setInt(8, r.terminalCount());
                    ps.setString(9, r.params());
                    ps.setLong(10, System.currentTimeMillis());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        static void wires(Connection c, List<WireRecord> list) throws Exception {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM wire");
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT OR REPLACE INTO wire"
                                 + "(id,network_id,a_key,b_key,resistance,length,temperature_key,"
                                 + "renderer_id,color_override,self_placed,item_id,updated_at) "
                                 + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                del.executeUpdate();
                for (WireRecord r : list) {
                    if (r == null) continue;
                    ps.setLong(1, r.id());
                    ps.setLong(2, r.networkId());
                    ps.setString(3, r.aKey());
                    ps.setString(4, r.bKey());
                    ps.setDouble(5, r.resistance());
                    ps.setDouble(6, r.length());
                    ps.setString(7, r.temperatureKey());
                    ps.setString(8, r.rendererId());
                    ps.setInt(9, r.colorOverride());
                    ps.setInt(10, r.selfPlaced() ? 1 : 0);
                    ps.setString(11, r.itemId());
                    ps.setLong(12, System.currentTimeMillis());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }

        static void renderers(Connection c, List<RendererRecord> list) throws Exception {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM renderer");
                 PreparedStatement ps = c.prepareStatement(
                         "INSERT OR REPLACE INTO renderer"
                                 + "(id,network_id,texture,color,sag,thickness,params,updated_at) "
                                 + "VALUES(?,?,?,?,?,?,?,?)")) {
                del.executeUpdate();
                for (RendererRecord r : list) {
                    if (r == null || r.id() == null) continue;
                    ps.setString(1, r.id());
                    ps.setLong(2, r.networkId());
                    ps.setString(3, r.texture());
                    ps.setInt(4, r.color());
                    ps.setDouble(5, r.sag());
                    ps.setDouble(6, r.thickness());
                    ps.setString(7, r.params());
                    ps.setLong(8, System.currentTimeMillis());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    }
}
