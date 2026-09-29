package com.hdf.cryptand.neoforge.powergrid.persistence;

import com.hdf.cryptand.circuitsimulation.db.NetlistDatabase;
import com.hdf.cryptand.circuitsimulation.db.NetlistRecord.*;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WireNetwork;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import com.hdf.cryptand.neoforge.powergrid.state.VirtualDevice;
import com.hdf.cryptand.neoforge.powergrid.state.VirtualDeviceStore;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;
import com.hdf.cryptand.neoforge.powergrid.device.wire.SaggingWireRegistry;
import com.hdf.cryptand.neoforge.core.wire.SaggingWireType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 仿真电路文件夹（2026-08-15 用户架构：世界目录下注册"仿真电路"文件夹）。
 * <p>
 * 目录结构：{@code <存档名>/cryptand/simulation_circuit/}（"仿真电路"的英文
 * 翻译 = <b>Simulation Circuit</b>；文件系统目录用 ASCII 名避免编码问题，
 * 注册表同时记录中文显示名）。数据库文件 {@code simulation_circuit.sqlite}
 * （网表数据库，“仿真电路.sqlite”的英文翻译）。
 * <p>
 * 职责：
 * <ul>
 *   <li><b>文件夹注册</b>：{@link #registerFolder} 注册文件夹元数据（key /
 *      中文名 / 英文名 / 目录名）——本类注册 {@code 仿真电路 / Simulation
 *      Circuit}，其他数据库可继续注册新文件夹。</li>
 *   <li><b>生命周期</b>：世界加载 {@link #onWorldLoad}（建目录 + 打开网表库 +
 *      恢复 + 重建稳定 id 映射）→ 世界保存 {@link #onWorldSave}（采集快照异步
 *      写）→ 世界卸载 {@link #onWorldUnload}（flush 落盘 + 关库）。</li>
 *   <li><b>快照采集</b> {@link #collectSnapshot}：从导线网络管理器/虚拟设备库/
 *      悬垂导线注册器采集四类数据（网络/组装器/导线/渲染器），组装器与导线经
 *      {@link CircuitIdRegistry} 分配【稳定 64 位 id】。</li>
 * </ul>
 * 多线程：写走 {@link NetlistDatabase} 单写线程异步队列 + 消息通知
 * （{@link com.hdf.cryptand.circuit.NetlistDbListener}），不阻塞主线程/求解线程池。
 */
public final class SimulationCircuitFolder {

    /** 文件夹注册键（工厂/网表库实例名） */
    public static final String KEY = "simulation_circuit";
    /** 中文显示名 */
    public static final String NAME_ZH = "仿真电路";
    /** 英文显示名（"仿真电路"的英文翻译） */
    public static final String NAME_EN = "Simulation Circuit";
    /** 文件系统目录名（ASCII，避免跨平台编码问题） */
    public static final String DIR_NAME = "simulation_circuit";

    /** 文件夹元数据 */
    public record FolderDef(String key, String zhName, String enName, String dirName) {
        public static FolderDef of(String key, String zhName, String enName, String dirName) {
            return new FolderDef(key, zhName, enName, dirName);
        }
    }

    /** 已注册文件夹（key → 元数据；注册顺序稳定） */
    private static final Map<String, FolderDef> FOLDERS = new LinkedHashMap<>();

    static {
        registerFolder(FolderDef.of(KEY, NAME_ZH, NAME_EN, DIR_NAME));
    }

    private SimulationCircuitFolder() {}

    // ==================== 文件夹注册 ====================

    /** 注册一个数据库文件夹（key 唯一；重复注册覆盖）。本类已注册
     *  {@code 仿真电路 / Simulation Circuit}，其他数据库可继续注册。 */
    public static synchronized void registerFolder(FolderDef def) {
        if (def != null && def.key() != null) FOLDERS.put(def.key(), def);
    }

    /** 取已注册文件夹（未注册 → null） */
    public static synchronized FolderDef folder(String key) {
        return key == null ? null : FOLDERS.get(key);
    }

    /** 全部已注册文件夹（不可变视图） */
    public static synchronized Collection<FolderDef> folders() {
        return List.copyOf(FOLDERS.values());
    }

    // ==================== 当前世界状态 ====================

    private static volatile Path currentDir;
    private static volatile NetlistDatabase current;
    private static volatile ServerLevel currentLevel;

    /** 组装器稳定 id 注册表（键 "x,y,z"） */
    private static final CircuitIdRegistry ASSEMBLER_IDS = new CircuitIdRegistry();
    /** 导线稳定 id 注册表（键 "a_key|b_key"） */
    private static final CircuitIdRegistry WIRE_IDS = new CircuitIdRegistry();

    /** 当前打开的网表数据库（未加载世界 → null） */
    public static NetlistDatabase current() {
        return current;
    }

    /** 当前数据库目录（{@code <world>/cryptand/simulation_circuit}；未加载 → null） */
    public static Path currentDir() {
        return currentDir;
    }

    /** 组装器稳定 id 注册表（外部分配 id 用） */
    public static CircuitIdRegistry assemblerIds() {
        return ASSEMBLER_IDS;
    }

    /** 导线稳定 id 注册表 */
    public static CircuitIdRegistry wireIds() {
        return WIRE_IDS;
    }

    // ==================== 生命周期（世界加载/保存/卸载） ====================

    /** 世界加载：建 {@code <world>/cryptand/simulation_circuit} 目录 + 打开
     *  网表数据库 + 恢复快照 + 重建稳定 id 映射（跨会话 id 稳定）。 */
    public static void onWorldLoad(ServerLevel level) {
        if (level == null) return;
        try {
            close();
            Path root = level.getServer().getWorldPath(LevelResource.ROOT);
            Path dir = root.resolve("cryptand").resolve(DIR_NAME);
            Files.createDirectories(dir);
            // 迁移（2026-08-15 用户要求：数据库名“仿真电路.sqlite”→英文翻译
            // simulation_circuit.sqlite）：旧名 netlist.sqlite 存在且新名不存在
            // → 改名为新名（含 WAL 附属文件），保留已保存的网表/缓存数据。
            Path legacy = dir.resolve(NetlistDatabase.LEGACY_FILE);
            Path currentFile = dir.resolve(NetlistDatabase.DEFAULT_FILE);
            if (Files.exists(legacy) && !Files.exists(currentFile)) {
                Files.move(legacy, currentFile);
                for (String suffix : new String[]{"-wal", "-shm"}) {
                    try {
                        Path side = dir.resolve(NetlistDatabase.LEGACY_FILE + suffix);
                        if (Files.exists(side)) {
                            Files.move(side, dir.resolve(NetlistDatabase.DEFAULT_FILE + suffix));
                        }
                    } catch (Throwable ignored) {
                    }
                }
                CryptandNeoForge.WAF_LOGGER.info(
                        "[SimFolder] migrated {} -> {}", legacy.getFileName(),
                        currentFile.getFileName());
            }
            NetlistDatabase db = NetlistDatabase.open(KEY, dir);
            currentDir = dir;
            current = db;
            currentLevel = level;
            // 恢复：读取全部表 → 重建稳定 id 映射（重启后同一方块/导线同一 id）
            NetlistSnapshot snap = db.restore();
            rebuildIdMaps(snap);
            // 网络求解缓存（2026-08-15 用户要求：缓存机制只在世界加载/保存时
            // 交互 SQLite）——加载时一次性读入内存，之后游戏期间纯内存命中。
            NetworkCacheManager.loadAll(db);
            // 2026-09-15 用户："BE 侧走 Sqlite 保存即可，接管 NBT 保存，保存组装器等信息"
            //  —— 设备信息（组装器给出的 KV：电机转速/应力、绕组内部节点 id……）
            //  与网络缓存同库同生命周期：加载时一次性读回内存，之后游戏期间纯内存。
            int dev = DeviceInfoStore.loadAll(db, dimOf(level));
            CryptandNeoForge.WAF_LOGGER.info(
                    "[SimFolder] device info rows loaded = {}", dev);
            CryptandNeoForge.WAF_LOGGER.info(
                    "[SimFolder] opened {} ({}) restored {}", dir, NAME_ZH, db.stats());
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.error(
                    "[SimFolder] onWorldLoad failed", t);
            close();
        }
    }

    /** 世界保存（ESC/自动保存）：采集快照 + 网络缓存，全部【异步】写
     *  （不阻塞主线程防卡顿；排队到单写线程，完成经消息通知）。 */
    public static void onWorldSave(ServerLevel level) {
        NetlistDatabase db = current;
        if (db == null || db.isClosed()) return;
        if (level != null) currentLevel = level;
        try {
            NetlistSnapshot snap = collectSnapshot(currentLevel);
            db.saveSnapshotAsync(snap);
            NetworkCacheManager.saveAllAsync(db);
            // 设备信息：先问组装器采集（绑定 → 组装器），再异步落库
            DeviceInfoStore.collect(dimOf(currentLevel));
            DeviceInfoStore.saveAsync(db);
        } catch (Throwable t) {
            CryptandNeoForge.WAF_LOGGER.warn(
                    "[SimFolder] onWorldSave failed", t);
        }
    }

    /** 世界卸载：先【同步】保存最终快照 + 网络缓存（立即落盘），再关库。 */
    public static void onWorldUnload() {
        NetlistDatabase db = current;
        if (db != null && !db.isClosed()) {
            try {
                NetlistSnapshot snap = collectSnapshot(currentLevel);
                db.saveSnapshotSync(snap);
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[SimFolder] onWorldUnload snapshot save failed", t);
            }
            NetworkCacheManager.saveAllSync(db);
            // 设备信息：退出存档【同步】保存（立即落盘，再关库）
            DeviceInfoStore.collect(dimOf(currentLevel));
            DeviceInfoStore.saveSync(db);
        }
        close();
    }

    /** 维度 id（设备信息表主键的一部分；取不到则空串，不阻断保存） */
    private static String dimOf(ServerLevel level) {
        try {
            return level == null ? "" : level.dimension().location().toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 关闭（flush 落盘 + 关库 + 清状态）。幂等。 */
    public static void close() {
        NetlistDatabase db = current;
        current = null;
        currentDir = null;
        currentLevel = null;
        ASSEMBLER_IDS.clear();
        WIRE_IDS.clear();
        NetworkCacheManager.clear();
        DeviceInfoStore.clear();
        if (db != null) {
            try {
                db.close();
            } catch (Throwable t) {
                CryptandNeoForge.WAF_LOGGER.warn(
                        "[SimFolder] close failed", t);
            }
        }
    }

    /** 从已恢复快照重建稳定 id 映射（组装器按 pos、导线按边签名） */
    private static void rebuildIdMaps(NetlistSnapshot snap) {
        if (snap == null) return;
        List<Map.Entry<String, Long>> asm = new ArrayList<>();
        for (AssemblerRecord r : snap.assemblers()) {
            if (r == null) continue;
            asm.add(new AbstractMap.SimpleEntry<>(posKey(r.x(), r.y(), r.z()), r.id()));
        }
        ASSEMBLER_IDS.rebuild(asm);
        List<Map.Entry<String, Long>> wire = new ArrayList<>();
        for (WireRecord r : snap.wires()) {
            if (r == null || r.aKey() == null || r.bKey() == null) continue;
            wire.add(new AbstractMap.SimpleEntry<>(edgeKey(r.aKey(), r.bKey()), r.id()));
        }
        WIRE_IDS.rebuild(wire);
    }

    // ==================== 快照采集 ====================

    /** 组装器位置键 */
    public static String posKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    /** 导线边签名键（无向） */
    public static String edgeKey(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    /** union-find 查根（用于卸载兜底时按端点重建网络分组） */
    private static String find(Map<String, String> parent, String k) {
        String root = k;
        while (true) {
            String next = parent.get(root);
            if (next == null || next.equals(root)) break;
            root = next;
        }
        return root;
    }

    /** union-find 合并 */
    private static void union(Map<String, String> parent, String a, String b) {
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }

    /**
     * 采集当前世界仿真电路快照：
     * <ul>
     *   <li>网络 + 导线 ← {@link WireNetworkManager}（每个物理连通分量一个网络，
     *       id = 网络 64 位 id；导线带稳定 64 位 id）。⚠ 世界卸载时 BE 卸载会先
     *       清空图（edges→0、网络分裂成单点）——此时回退
     *       {@code WireNetworkManager.lastSnapshot()}（最近一次非空快照）并按
     *       端点 union-find 重建网络分组，保证最终保存不丢导线。</li>
     *   <li>组装器 ← {@link VirtualDeviceStore}（设备参数快照；按 pos 分配稳定
     *       64 位 id；顺序按 VirtualDeviceStore 迭代序）</li>
     *   <li>渲染器 ← {@link SaggingWireRegistry}（全局导线类型参数）</li>
     * </ul>
     */
    public static NetlistSnapshot collectSnapshot(ServerLevel level) {
        NetlistDatabase db = current;
        if (db == null) return NetlistSnapshot.empty();
        String dim = level != null
                ? level.dimension().location().toString() : null;
        double freq = Math.max(0, ConfigCircuit.CRYPTAND_TOPOLOGY_FREQUENCY_HZ.get());

        List<NetworkRecord> nets = new ArrayList<>();
        List<WireRecord> wires = new ArrayList<>();

        // —— 网络 + 导线（自管导线网络管理器）——
        WireNetworkManager mgr = WireNetworkManager.get();
        List<WireEdge> allEdges = mgr.edgeList();
        boolean live = !allEdges.isEmpty();
        if (!live && !mgr.lastSnapshot().isEmpty()) {
            allEdges = mgr.lastSnapshot(); // 卸载中图已被 BE 卸载清空 → 用最近非空快照
        }
        if (!allEdges.isEmpty()) {
            if (live) {
                // 正常保存：按现有网络分组（跳过无边的空网络）
                for (WireNetwork net : mgr.networks()) {
                    List<WireEdge> nes = new ArrayList<>(net.edges());
                    if (nes.isEmpty()) continue;
                    nets.add(new NetworkRecord(
                            net.id, null, freq, 0, dim, 0.05, net.version(), null));
                    for (WireEdge e : nes) {
                        if (e == null || e.a == null || e.b == null) continue;
                        wires.add(new WireRecord(
                                WIRE_IDS.idFor(edgeKey(e.a.key, e.b.key), db::nextId),
                                net.id,
                                e.a.key, e.b.key,
                                e.resistance, e.length,
                                e.temperatureKey, e.rendererId,
                                e.colorOverride, e.selfPlaced, e.itemId));
                    }
                }
            } else {
                // 卸载兜底：图对象已清空 → 按端点 union-find 重建网络分组，
                // 每个分组一个网络记录（id 快照内一致即可，快照整体替换）
                Map<String, String> parent = new HashMap<>();
                for (WireEdge e : allEdges) {
                    if (e == null || e.a == null || e.b == null) continue;
                    union(parent, e.a.key, e.b.key);
                }
                Map<String, Long> netOf = new LinkedHashMap<>();
                for (WireEdge e : allEdges) {
                    if (e == null || e.a == null || e.b == null) continue;
                    String root = find(parent, e.a.key);
                    Long nid = netOf.get(root);
                    if (nid == null) {
                        nid = db.nextId();
                        netOf.put(root, nid);
                    }
                    wires.add(new WireRecord(
                            WIRE_IDS.idFor(edgeKey(e.a.key, e.b.key), db::nextId),
                            nid,
                            e.a.key, e.b.key,
                            e.resistance, e.length,
                            e.temperatureKey, e.rendererId,
                            e.colorOverride, e.selfPlaced, e.itemId));
                }
                for (Long nid : netOf.values()) {
                    nets.add(new NetworkRecord(nid, null, freq, 0, dim, 0.05, 0, null));
                }
            }
        }

        // —— 组装器（虚拟设备参数快照；每个 64 位 id 稳定识别）——
        List<AssemblerRecord> assemblers = new ArrayList<>();
        int order = 0;
        for (Map.Entry<BlockPos, VirtualDevice> entry : VirtualDeviceStore.all().entrySet()) {
            BlockPos pos = entry.getKey();
            VirtualDevice vd = entry.getValue();
            if (pos == null || vd == null || vd.deviceClass == null) continue;
            long aid = ASSEMBLER_IDS.idFor(posKey(pos.getX(), pos.getY(), pos.getZ()),
                    db::nextId);
            assemblers.add(new AssemblerRecord(
                    aid,
                    networkIdAt(level, pos),
                    order++,
                    vd.deviceClass,
                    pos.getX(), pos.getY(), pos.getZ(),
                    2,
                    paramsOf(vd)));
        }

        // —— 渲染器（悬垂导线注册器全局参数；networkId=0 表示全局默认）——
        List<RendererRecord> renderers = new ArrayList<>();
        for (SaggingWireType t : SaggingWireRegistry.all()) {
            if (t == null || t.id() == null) continue;
            renderers.add(new RendererRecord(
                    t.id(), 0,
                    t.texture() == null ? null : t.texture().toString(),
                    t.color(), t.sag(), t.thickness(), null));
        }

        return NetlistSnapshot.of(nets, assemblers, wires, renderers);
    }

    /** 组装器所在网络 id（该 pos 的端点所属网络；找不到 → -1 未归网） */
    private static long networkIdAt(ServerLevel level, BlockPos pos) {
        try {
            if (level != null) {
                WireNetwork net = WireNetworkManager.get().networkOf(
                        "B" + pos + "#0");
                if (net != null) return net.id;
            }
            // 兜底：扫全部网络找含该端点的（端子索引不确定时）
            for (WireNetwork net : WireNetworkManager.get().networks()) {
                if (net.points().stream().anyMatch(p ->
                        p.key.contains("BlockPos{" + pos.getX() + ", " + pos.getY()
                                + ", " + pos.getZ() + "}"))) {
                    return net.id;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** VirtualDevice → 参数快照字符串（简单 k=v 逗号分隔，可解析） */
    private static String paramsOf(VirtualDevice vd) {
        try {
            return "r=" + vd.resistance
                    + ",l=" + vd.inductance
                    + ",en=" + vd.enabled
                    + ",v=" + vd.voltage
                    + ",sr=" + vd.sourceResistance
                    + ",vs=" + vd.isVoltageSource;
        } catch (Throwable t) {
            return null;
        }
    }
}
