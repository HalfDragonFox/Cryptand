package com.hdf.cryptand.circuitsimulation.cache;

import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.circuitsimulation.netop.AsyncInteractionManager;
import com.hdf.cryptand.circuitsimulation.netop.GridMessage;
import com.hdf.cryptand.circuitsimulation.netop.NetOpExecutor;
import com.hdf.cryptand.circuitsimulation.netop.NetOpKind;
import com.hdf.cryptand.circuitsimulation.netop.NetOpRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 网络世界（2026-08-22 用户架构：核心扩展组件 —— 具体实例，统管会话全部）。
 * <p>
 * 一个 {@code NetworkWorld} = 一个【具体会话/世界】（MC 存档 "mc-overworld"、
 * EDA 电路场景 "eda-scene-1"），由单个实例统一管理：
 * <ul>
 *   <li><b>对话</b>：{@link AppLink}（与具体对象的对话，平台实现；缓存就绪/
 *       变化/释放时回调）；</li>
 *   <li><b>缓存数据</b>：本世界全部网络（{@link CachedNetwork}，ECS 只存数据）——
 *       导线/端点/连续段/元件/求解结果全在网络内；</li>
 *   <li><b>图算法</b>：加边合并/拆线分裂/设备建网/一致性重建（含 byPoint 索引）；</li>
 *   <li><b>消息</b>：submit/postDestroy/postTopology/postSolve —— 外部（主线程/
 *       EDA 前端）发"网络引用 + 操作" → 异步交互管理类（核心，已集成）串行处理。</li>
 * </ul>
 * <p>
 * 通过 {@link #create}（实例工厂）创建：绑定对话 + 自动创建/注册到全局管理器
 * （{@link NetworkWorldManager}）。纯核心组件，不依赖 Minecraft/EDA。
 * <p>
 * ECS 语义：网络（CachedNetwork）只存数据，行为（重建/拆合/求解）在核心执行器；
 * 本世界持有数据 + 消息 + 对话，是【唯一实体】。
 * <p>
 * <b>线程安全（2026-08-22）</b>：所有网络数据（networks/byPoint/graphVersion）
 * 的访问一律经 {@link #graphLock}（ReentrantReadWriteLock）——写（addEdge/remove
 * /addDevice/ensureComponentNetworks/createNetwork/removeNetwork/clear/dispose）
 * 持写锁，读（查询/allNetworks/getNetwork/notifyChanged 的版本）持读锁，保证
 * networks↔byPoint 索引原子一致（剪线/拆设备/重建时并发读不看到半状态）。
 * 对话回调（onCacheReady/onCacheChanged/onCacheDisposed）一律在锁外调用
 * （外部实现可自行同步其对象；同线程重入由 ReentrantReadWriteLock 保证）。
 * 返回的 {@link CachedNetwork} 引用内部自带 dataLock + topology 读写锁，可安全并发使用。
 */
public final class NetworkWorld {

    // ==================== 身份 / 对话 ====================

    /** 世界名（"mc-overworld" / "eda-scene-1"；外部引用 / 回调路由） */
    private final String name;
    /** 与具体对象的对话（EDA/MC 应用实现；可 null） */
    private final AppLink link;
    /** 已通知应用的最新缓存版本（版本去重） */
    private final AtomicLong lastNotifiedVersion = new AtomicLong(-1);

    /** 网格消息协议日志（2026-08-23 用户：无效消息必须打印） */
    private static final System.Logger GRID_LOG = System.getLogger("cryptand.gridmsg");

    // ==================== 网络数据（ECS 只存数据） ====================

    /** 全部网络（网络键 → CachedNetwork：导线/元件/结果/属性全在网络内） */
    private final Map<Object, CachedNetwork> networks = new ConcurrentHashMap<>();

    // ==================== 异步交互管理类（核心，已集成） ====================

    private final AsyncInteractionManager async;

    // ==================== 图算法（byPoint 索引 + 拆合/回归） ====================

    /** 端点 key → 所属网络（精确端子索引） */
    private final Map<String, CachedNetwork> byPoint = new HashMap<>();
    /** 全局结构版本（任何真实网络变化 +1） */
    private long graphVersion;
    private long nextNetworkId = 1;
    private final ReentrantReadWriteLock graphLock = new ReentrantReadWriteLock();

    /**
     * 图内端子标记（2026-08-23 用户：端子 = 连接器元件，仅标记）。
     * key = "x,y,z"（纯 Java 字符串，零 MC 依赖）；value = 该方块声明端子数。
     * 设备放置（addDevice）/接线/旧世界迁移时登记；构建（重建）时
     * {@code isDeviceTerminal} 直接读本表——不再运行时反查 DeviceParamCache
     *（空窗/键迁移敏感）；物理化 remap 时随坐标迁移（moveDeviceTerm）。
     * 端子元件（TerminalElement）= 连接器标签：仅标记连接点，不参与电学计算。
     */
    private final Map<String, Integer> deviceTerms = new ConcurrentHashMap<>();

    private NetworkWorld(String name, AppLink link, AsyncInteractionManager async) {
        this.name = name;
        this.link = link;
        this.async = async;
    }

    /** 包内测试构造器（async 可 null——不提交消息仅数据/图算法） */
    NetworkWorld(String name) {
        this(name, null, null);
    }

    // ==================== 实例工厂（生成一个具体实例） ====================

    /**
     * 创建实例（具体对象：EDA 或 MC）：绑定对话 + 创建/注册到全局管理器
     * （{@link NetworkWorldManager}，幂等：同名已存在返回现有）。创建后回调
     * {@link AppLink#onCacheReady}（对话建立）。
     *
     * @param name     实例名（"mc-overworld" / "eda-scene-1"）
     * @param link     与应用对象的对话（EDA/MC 实现，可 null = 无对话）
     * @param executor 平台网络操作执行器
     * @return 网络世界实例
     */
    public static NetworkWorld create(String name, AppLink link, NetOpExecutor executor) {
        return create(name, link, executor, TaskMode.NORMAL);
    }

    /** 创建实例（指定分配任务模式；如 MC 的 EXCLUSIVE 配置） */
    public static NetworkWorld create(String name, AppLink link, NetOpExecutor executor,
                                      TaskMode mode) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("world name required");
        }
        AsyncInteractionManager async = new AsyncInteractionManager(
                com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers.get(),
                executor, mode == null ? TaskMode.NORMAL : mode);
        NetworkWorld w = new NetworkWorld(name, link, async);
        NetworkWorld old = NetworkWorldManager.get().registerWorld(w);
        if (old == null) {
            w.notifyReady();
            return w;
        }
        return old;
    }

    // ==================== 对话回调 ====================

    /** 世界名（外部引用 / 缓存操作消息路由） */
    public String name() { return name; }

    /** 与具体对象的对话（EDA/MC；可 null） */
    public AppLink link() { return link; }

    /** 通知对话应用：缓存对象已就绪（实例创建时自动调用） */
    void notifyReady() {
        if (link != null) {
            try { link.onCacheReady(this); } catch (Throwable ignored) { }
        }
    }

    /** 通知对话应用：缓存结构变化（版本去重——实际版本变化才通知）。应用可据此
     *  同步其对象（MC 客户端图同步 / EDA 画布刷新 / 存档）。 */
    public void notifyChanged() {
        long v = version();
        long prev = lastNotifiedVersion.get();
        if (prev != v && lastNotifiedVersion.compareAndSet(prev, v)) {
            if (link != null) {
                try { link.onCacheChanged(this, v); } catch (Throwable ignored) { }
            }
        }
    }

    /** 释放世界实例：通知对话应用 + 清缓存数据 + 清异步记录表（一律持写锁一致清理） */
    void dispose() {
        if (link != null) {
            try { link.onCacheDisposed(this); } catch (Throwable ignored) { }
        }
        graphLock.writeLock().lock();
        try {
            for (CachedNetwork net : networks.values()) {
                try { net.topology().clear(); } catch (Throwable ignored) { }
            }
            networks.clear();
            byPoint.clear();
        } finally { graphLock.writeLock().unlock(); }
        if (async != null) {
            try { async.clear(); } catch (Throwable ignored) { }
        }
    }

    // ==================== 网络数据管理 ====================

    /** 创建网络（已存在返回现有；写锁） */
    public CachedNetwork createNetwork(Object key) {
        return createNetwork(key, null);
    }

    /** 创建网络（带名称；已存在返回现有；写锁） */
    public CachedNetwork createNetwork(Object key, String netName) {
        graphLock.writeLock().lock();
        try {
            return networks.computeIfAbsent(key, k -> new CachedNetwork(k, netName));
        } finally { graphLock.writeLock().unlock(); }
    }

    /** 查询网络（未创建 null；读锁） */
    public CachedNetwork getNetwork(Object key) {
        graphLock.readLock().lock();
        try { return networks.get(key); } finally { graphLock.readLock().unlock(); }
    }

    /** 移除网络（返回被移除的；不存在 null；写锁，同步清理 byPoint 索引） */
    public CachedNetwork removeNetwork(Object key) {
        graphLock.writeLock().lock();
        try {
            CachedNetwork net = networks.remove(key);
            if (net != null) {
                for (WirePoint p : net.points()) byPoint.remove(p.key);
            }
            return net;
        } finally { graphLock.writeLock().unlock(); }
    }

    // ===== 设备登记（2026-08-22 导出原理图核心扩展：虚拟电路 = 拓扑 + 设备元数据） =====
    // 键 = 方块 key；【与网络生命周期解耦】——网络合并/分裂/删除不丢失设备登记
    // （合并会删除被并入的网络对象，若登记挂在 CachedNetwork 上会随其丢失）。
    private final java.util.Map<String, com.hdf.cryptand.circuitsimulation.cache.DeviceInfo> devices =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 登记/更新设备信息（建网/参数同步阶段；幂等；不改变结构版本） */
    public void registerDevice(com.hdf.cryptand.circuitsimulation.cache.DeviceInfo info) {
        if (info != null) devices.put(info.blockKey(), info);
    }

    /** 查询设备信息（方块 key；未登记 null） */
    public com.hdf.cryptand.circuitsimulation.cache.DeviceInfo deviceInfo(String blockKey) {
        return blockKey == null ? null : devices.get(blockKey);
    }

    /** 注销设备信息（设备删除增量；幂等；不改变结构版本） */
    public void unregisterDevice(String blockKey) {
        if (blockKey != null) devices.remove(blockKey);
    }

    /** 全部设备登记（快照；导出用） */
    public java.util.List<com.hdf.cryptand.circuitsimulation.cache.DeviceInfo> deviceInfos() {
        return new java.util.ArrayList<>(devices.values());
    }

    /** 已登记设备数 */
    public int deviceCount() {
        return devices.size();
    }

    /** 全部网络（读锁；返回快照避免外部读锁外遍历不一致） */
    public Collection<CachedNetwork> allNetworks() {
        graphLock.readLock().lock();
        try { return new ArrayList<>(networks.values()); } finally { graphLock.readLock().unlock(); }
    }

    /** 网络数量（读锁） */
    public int networkCount() {
        graphLock.readLock().lock();
        try { return networks.size(); } finally { graphLock.readLock().unlock(); }
    }

    /** 清空本世界全部网络（写锁，同步清理 byPoint 索引与拓扑） */
    public void clear() {
        graphLock.writeLock().lock();
        try {
            for (CachedNetwork net : networks.values()) {
                try { net.topology().clear(); } catch (Throwable ignored) { }
            }
            networks.clear();
            byPoint.clear();
            graphVersion++;
        } finally { graphLock.writeLock().unlock(); }
    }

    // ==================== 消息（外部 → 核心；异步交互管理类集成） ====================

    /**
     * 提交网络操作消息到核心（任何线程可调，通常主线程/EDA 前端）。
     * 按网络键（networkKey）串行锁定：同一网络的操作在分配器线程逐条处理，
     * 同类型操作自动整合（拆合/重建/求解各合并为一条）。
     * <p>2026-08-23 用户协议统一：本便捷入口内部构造 {@link GridMessage}
     * （仅网络包，设备列表包为空）→ {@link #submit(GridMessage)}——协议校验/
     * 日志/分发统一，不再直连异步管理类。
     */
    public void submit(NetOpKind kind, Object networkKey, Object data) {
        if (async == null || kind == null || networkKey == null) return;
        submit(new GridMessage(networkKey,
                GridMessage.NetPackage.of(kind, data),
                GridMessage.DevicePackage.empty()));
    }

    /**
     * 提交统一网格消息（2026-08-23 用户协议；主线程/EDA 前端调用）：
     * 一条消息 = 网络包（NetPackage）+ 电气设备列表包（DevicePackage）。
     * <p>
     * 协议校验：
     *   - 消息/网络键缺失 → 无效（日志）；
     *   - <b>设备列表包非空而网络包缺失（net==null）→ 无效消息，日志打印并丢弃</b>；
     *   - 仅网络包（设备包空）→ 有效（第二条消息场景）；
     *   - 网络包 NOOP 且设备包空 → 无操作，忽略（不打印）。
     * <p>
     * 分发（同一网络键 → 异步交互管理类串行锁定）：
     *   1. 设备包非空 → DEVICE_DELTA（先入缓冲；执行顺序在重建之前——设备增量是
     *      重建输入）；
     *   2. 网络包按 ops 逐项投递（rebuild/solve 等；按缓冲顺序整合执行）。
     * <p>
     * 多网络操作分多次发送（一条消息仅一个 networkKey——协议保证同一网络）。
     */
    public void submit(GridMessage msg) {
        if (msg == null) {
            GRID_LOG.log(System.Logger.Level.WARNING,
                    "[GridMsg] INVALID: null message dropped");
            return;
        }
        if (msg.networkKey() == null) {
            GRID_LOG.log(System.Logger.Level.WARNING,
                    "[GridMsg] INVALID: networkKey missing, message={} dropped",
                    msg);
            return;
        }
        if (async == null) return;
        // ⚠ 协议：设备列表包非空时必须带网络包（即使 NOOP）；否则无效
        boolean devNonEmpty = msg.devices() != null && !msg.devices().isEmpty();
        if (msg.net() == null) {
            if (devNonEmpty) {
                GRID_LOG.log(System.Logger.Level.WARNING,
                        "[GridMsg] INVALID: device package present without net package "
                                + "(net==null); message dropped key={} devices={}",
                        msg.networkKey(), msg.devices());
            }
            return;
        }
        boolean netNoOp = msg.net().noOp();
        if (netNoOp && !devNonEmpty) {
            // 两个包都为空 → 无操作（允许，但不产生任何任务）
            return;
        }
        // 1) 设备列表包 → DEVICE_DELTA（先应用，重建前）
        if (devNonEmpty) {
            async.submit(msg.networkKey(),
                    new NetOpRequest(NetOpKind.DEVICE_DELTA, msg.devices()));
        }
        // 2) 网络包 → 按 ops 逐项投递（空 = NOOP 跳过）
        if (!netNoOp && msg.net().ops() != null) {
            for (NetOpKind op : msg.net().ops()) {
                if (op == null) continue;
                async.submit(msg.networkKey(), new NetOpRequest(op, msg.net().data()));
            }
        }
    }

    /** 网络内容破坏（设备拆除/导线剪切）：DESTROY + 拆合 + 重建（配套流程）。
     *  2026-08-23 统一协议：三条仅网络包消息（同网络，缓冲 FIFO 保序）。 */
    public void postDestroy(Object networkKey, Object data) {
        submit(GridMessage.ofOnlyNet(networkKey,
                GridMessage.NetPackage.of(NetOpKind.DESTROY, data)));
        submit(GridMessage.ofOnlyNet(networkKey,
                GridMessage.NetPackage.of(NetOpKind.SPLIT_MERGE, null)));
        submit(GridMessage.ofOnlyNet(networkKey,
                GridMessage.NetPackage.of(NetOpKind.REBUILD, new Object[]{null, true})));
    }

    /** 拓扑事件（接线/拆线/方块变化）：拆合（结构性）+ 重建 */
    public void postTopology(Object networkKey, boolean structural) {
        if (structural) {
            submit(GridMessage.ofOnlyNet(networkKey,
                    GridMessage.NetPackage.of(NetOpKind.SPLIT_MERGE, null)));
        }
        submit(GridMessage.ofOnlyNet(networkKey,
                GridMessage.NetPackage.of(NetOpKind.REBUILD,
                        new Object[]{null, structural})));
    }

    /** 求解节拍：SOLVE 请求（多条在缓冲中整合为一条） */
    public void postSolve(Object networkKey, Object data) {
        submit(GridMessage.ofOnlyNet(networkKey,
                GridMessage.NetPackage.of(NetOpKind.SOLVE, data)));
    }

    /** 异步交互管理类（诊断/扩展；可能 null 若未装配） */
    public AsyncInteractionManager async() { return async; }

    // ==================== 图算法（同方块合并/拆线分裂/一致性重建） ====================

    /** 全局结构版本（任何增删合并分裂 +1；重复幂等导入不涨） */
    public long version() {
        graphLock.readLock().lock();
        try { return graphVersion; } finally { graphLock.readLock().unlock(); }
    }

    /** 全部端点数量 */
    public int nodeCount() {
        graphLock.readLock().lock();
        try {
            int n = 0;
            for (CachedNetwork net : networks.values()) n += net.nodeCount();
            return n;
        } finally { graphLock.readLock().unlock(); }
    }

    /** 全部导线数量 */
    public int edgeCount() {
        graphLock.readLock().lock();
        try {
            int n = 0;
            for (CachedNetwork net : networks.values()) n += net.edgeCount();
            return n;
        } finally { graphLock.readLock().unlock(); }
    }

    /** 端点是否存在（任一网络） */
    public boolean contains(WirePoint p) {
        graphLock.readLock().lock();
        try { return p != null && byPoint.containsKey(p.key); } finally { graphLock.readLock().unlock(); }
    }

    /** 端点所属网络（不存在返回 null） */
    public CachedNetwork networkOf(WirePoint p) {
        graphLock.readLock().lock();
        try { return p == null ? null : byPoint.get(p.key); } finally { graphLock.readLock().unlock(); }
    }

    /** 端点 key 所属网络（不存在返回 null） */
    public CachedNetwork networkOf(String key) {
        graphLock.readLock().lock();
        try { return key == null ? null : byPoint.get(key); } finally { graphLock.readLock().unlock(); }
    }

    /** 某端点邻接边（跨网络查；不存在返回空集） */
    public Set<WireEdge> adjacent(WirePoint p) {
        graphLock.readLock().lock();
        try {
            CachedNetwork net = p == null ? null : byPoint.get(p.key);
            return net == null ? Collections.emptySet() : net.adjacent(p);
        } finally { graphLock.readLock().unlock(); }
    }

    /** 全量端点（副本） */
    public List<WirePoint> pointList() {
        graphLock.readLock().lock();
        try {
            List<WirePoint> out = new ArrayList<>(byPoint.size());
            for (String k : byPoint.keySet()) out.add(new WirePoint(k));
            return out;
        } finally { graphLock.readLock().unlock(); }
    }

    /** 全量导线（全局视图：图同步/材质/渲染） */
    public List<WireEdge> edgeList() {
        graphLock.readLock().lock();
        try {
            List<WireEdge> out = new ArrayList<>();
            for (CachedNetwork net : networks.values()) out.addAll(net.wires());
            return out;
        } finally { graphLock.readLock().unlock(); }
    }

    /** 全部分量（每个 = 该分量的节点集合） */
    public List<Set<WirePoint>> components() {
        graphLock.readLock().lock();
        try {
            List<Set<WirePoint>> out = new ArrayList<>();
            for (CachedNetwork net : networks.values()) {
                Set<WirePoint> comp = net.points();
                if (!comp.isEmpty()) out.add(comp);
            }
            return out;
        } finally { graphLock.readLock().unlock(); }
    }

    /** 全局连续段（各网络段汇总；统一温度/烧毁） */
    public List<com.hdf.cryptand.circuitsimulation.netgraph.WireSegment> segments() {
        graphLock.readLock().lock();
        try {
            List<com.hdf.cryptand.circuitsimulation.netgraph.WireSegment> out = new ArrayList<>();
            for (CachedNetwork net : networks.values()) out.addAll(net.segments());
            return out;
        } finally { graphLock.readLock().unlock(); }
    }

    // ==================== 图内端子标记（用户：端子=连接器元件仅标记） ====================

    private static final java.util.regex.Pattern NUM = java.util.regex.Pattern.compile("-?\\d+");

    /** "BBlockPos{x=.., y=.., z=..}#t" / "B(..,..,..)#t" → "x,y,z"；非 B/J → null */
    private static String posKeyOf(String pointKey) {
        try {
            if (pointKey == null || pointKey.length() < 2) return null;
            char c = pointKey.charAt(0);
            if (c != 'B' && c != 'J') return null;
            String body = pointKey.substring(1);
            int hash = body.indexOf('#');
            String posStr = hash >= 0 ? body.substring(0, hash) : body;
            java.util.regex.Matcher m = NUM.matcher(posStr);
            int[] xyz = new int[3];
            int i = 0;
            while (m.find() && i < 3) xyz[i++] = Integer.parseInt(m.group());
            if (i < 3) return null;
            return xyz[0] + "," + xyz[1] + "," + xyz[2];
        } catch (Throwable t) {
            return null;
        }
    }

    /** 登记设备端子标记（"x,y,z" → 端子数；max 保留）。设备放置/接线/迁移调用。 */
    public void markDevice(String posKey, int terms) {
        if (posKey == null || terms <= 0) return;
        deviceTerms.merge(posKey, terms, Math::max);
    }

    /** 注销设备端子标记（设备拆除/图清理；幂等） */
    public void unmarkDevice(String posKey) {
        if (posKey != null) deviceTerms.remove(posKey);
    }

    /** 该方块是否图内声明端子（构建 isDeviceTerminal 依据；0 = 无） */
    public int deviceTermCount(String posKey) {
        return posKey == null ? 0 : deviceTerms.getOrDefault(posKey, 0);
    }

    /** 物理化 remap：端子标记随坐标迁移（旧 key 清除、新 key 登记，保留端子数） */
    public void moveDeviceTerm(String oldKey, String newKey) {
        if (oldKey == null || newKey == null || oldKey.equals(newKey)) return;
        Integer v = deviceTerms.remove(oldKey);
        if (v != null) deviceTerms.merge(newKey, v, Math::max);
    }

    /** 当前端子标记数量（诊断） */
    public int deviceTermCount() {
        return deviceTerms.size();
    }

    /** 加入孤立点（设备悬空端子；新建单点网络）。幂等。 */
    public boolean addPoint(WirePoint p) {
        return p == null ? false : addDevice(java.util.List.of(p.key));
    }

    /** 设备放置：把该设备全部声明端子 key 并入【同一网络】（放下即建网）。幂等。
     *  同时登记图内端子标记（端子=连接器仅标记；构建 isDeviceTerminal 读它）。 */
    public boolean addDevice(Collection<String> terminalKeys) {
        if (terminalKeys == null || terminalKeys.isEmpty()) return false;
        // 端子标记（锁外登记即可：独立 map，线程安全）
        try {
            int maxTerm = terminalKeys.size();
            for (String k : terminalKeys) {
                String pk = posKeyOf(k);
                if (pk != null) markDevice(pk, maxTerm);
            }
        } catch (Throwable ignored) {
        }
        graphLock.writeLock().lock();
        boolean changed = false;
        try {
            boolean all = true;
            CachedNetwork n = null;
            for (String k : terminalKeys) {
                CachedNetwork existing = byPoint.get(k);
                if (existing == null) { all = false; continue; }
                if (n == null) n = existing;
            }
            if (all) return false;
            if (n == null) {
                n = new CachedNetwork(nextNetworkId, null, nextNetworkId);
                networks.put(n.key(), n);
                nextNetworkId++;
            }
            for (String k : terminalKeys) {
                if (byPoint.containsKey(k)) continue;
                WirePoint p = new WirePoint(k);
                n.topology().addPoint(p);
                byPoint.put(k, n);
                changed = true;
            }
            if (changed) graphVersion++;
            return changed;
        } finally { graphLock.writeLock().unlock(); }
    }

    /** 接线：加导线。找两端所属网络：同网络（环）→ 直接加边（幂等）；不同网络
     *  → 合并（merge）；都不在 → 新建网络。真实变化才 graphVersion++。 */
    public boolean addEdge(WireEdge e) {
        graphLock.writeLock().lock();
        boolean changed = false;
        try {
            CachedNetwork na = byPoint.get(e.a.key);
            CachedNetwork nb = byPoint.get(e.b.key);
            if (na != null && nb != null && na == nb) {
                changed = na.topology().addEdge(e);
            } else if (na != null && nb != null) {
                List<WirePoint> nbPoints = new ArrayList<>(nb.topology().points());
                na.topology().merge(nb.topology());
                for (WirePoint p : nbPoints) byPoint.put(p.key, na);
                networks.remove(nb.key());
                na.topology().addEdge(e);
                changed = true;
            } else {
                CachedNetwork n = na != null ? na : nb;
                if (n == null) {
                    n = new CachedNetwork(nextNetworkId, null, nextNetworkId);
                    networks.put(n.key(), n);
                    nextNetworkId++;
                }
                changed = n.topology().addEdge(e);
                byPoint.put(e.a.key, n);
                byPoint.put(e.b.key, n);
            }
            if (changed) graphVersion++;
        } finally { graphLock.writeLock().unlock(); }
        return changed;
    }

    /** 端点 key 的方块前缀（"BBlockPos{...}#t" → "BBlockPos{...}"；J 点同）。 */
    private static String prefixOf(String key) {
        if (key == null) return null;
        int i = key.indexOf('#');
        return i < 0 ? null : key.substring(0, i);
    }

    /** 拆线：移除 a-b 边。分裂移动后【必须重新分组】（ensureComponentNetworks）——
     *  同方块扩展可能把边另一端跨 bSide 拉入新网络而 byPoint 未更新，或剩余边使
     *  网络实际不分裂（双线回路剪一根仍连通）→ 由重新分组自动修正（byPoint=网络）。 */
    public boolean removeEdge(WirePoint a, WirePoint b) {
        graphLock.writeLock().lock();
        boolean changed = false;
        try {
            CachedNetwork net = byPoint.get(a.key);
            if (net == null) net = byPoint.get(b.key);
            if (net == null) return false;
            if (!net.topology().connected(a, b)) return false;
            Set<WirePoint> bSide = net.topology().removeEdgeSplit(a, b);
            if (!bSide.isEmpty()) {
                // 同方块端子扩展（设备端子保持同网络 → 正确分裂）
                for (WirePoint p : new ArrayList<>(bSide)) {
                    String prefix = prefixOf(p.key);
                    if (prefix == null) continue;
                    for (WirePoint q : net.topology().points()) {
                        if (q == p || bSide.contains(q)) continue;
                        if (prefix.equals(prefixOf(q.key))) bSide.add(q);
                    }
                }
                CachedNetwork newNet = new CachedNetwork(nextNetworkId, null, nextNetworkId);
                nextNetworkId++;
                for (WirePoint p : bSide) {
                    List<WireEdge> moved = net.topology().removeNode(p);
                    for (WireEdge ed : moved) newNet.topology().addEdge(ed);
                    byPoint.put(p.key, newNet);
                }
                networks.put(newNet.key(), newNet);
            }
            // 分裂后重新分组（治本）：跨边/设备分离/实际未分裂统一修正
            ensureComponentNetworks();
            changed = true;
            graphVersion++;
        } finally { graphLock.writeLock().unlock(); }
        return changed;
    }

    /** 设备拆除：移除端点及其全部导线。所属网络可能分裂 → 重新分组。 */
    public boolean removePoint(WirePoint p) {
        graphLock.writeLock().lock();
        boolean changed = false;
        try {
            CachedNetwork net = byPoint.get(p.key);
            if (net == null) return false;
            Set<WirePoint> allPoints = new HashSet<>(net.topology().points());
            List<WireEdge> allEdges = new ArrayList<>(net.topology().edges());
            net.topology().clear();
            networks.remove(net.key());
            byPoint.remove(p.key);
            Map<String, CachedNetwork> assigned = new HashMap<>();
            for (WireEdge e : allEdges) {
                if (e.a.equals(p) || e.b.equals(p)) continue;
                assignEdgeCached(e, assigned);
            }
            for (WirePoint q : allPoints) {
                if (q.equals(p)) continue;
                CachedNetwork n2 = assigned.get(q.key);
                if (n2 != null) {
                    byPoint.put(q.key, n2);
                } else {
                    n2 = new CachedNetwork(nextNetworkId, null, nextNetworkId);
                    nextNetworkId++;
                    n2.topology().addPoint(q);
                    byPoint.put(q.key, n2);
                    networks.put(n2.key(), n2);
                }
            }
            graphVersion++;
            changed = true;
        } finally { graphLock.writeLock().unlock(); }
        return changed;
    }

    /** 分组辅助：把边归入已分配网络 / 新建（调用方持写锁） */
    private void assignEdgeCached(WireEdge e, Map<String, CachedNetwork> assigned) {
        CachedNetwork na = assigned.get(e.a.key);
        CachedNetwork nb = assigned.get(e.b.key);
        if (na != null && nb != null && na == nb) {
            na.topology().addEdge(e);
        } else if (na != null && nb != null) {
            List<WirePoint> nbPoints = new ArrayList<>(nb.topology().points());
            na.topology().merge(nb.topology());
            for (WirePoint q : nbPoints) assigned.put(q.key, na);
            networks.remove(nb.key());
            na.topology().addEdge(e);
        } else {
            CachedNetwork n = na != null ? na : nb;
            if (n == null) {
                n = new CachedNetwork(nextNetworkId, null, nextNetworkId);
                networks.put(n.key(), n);
                nextNetworkId++;
            }
            n.topology().addEdge(e);
            assigned.put(e.a.key, n);
            assigned.put(e.b.key, n);
        }
    }

    /** 一致性重建：完全重新分组——导线连通分量 + 设备端子同网络 + 孤立点单点网络。
     *  结尾一致性收尾（byPoint 从网络重建，contains/adjacent 与 components/points
     *  永远一致）。幂等，可重复调用。 */
    public boolean ensureComponentNetworks() {
        graphLock.writeLock().lock();
        boolean changed = false;
        try {
            Set<WirePoint> allPoints = new HashSet<>();
            List<WireEdge> allEdges = new ArrayList<>();
            for (CachedNetwork net : networks.values()) {
                allPoints.addAll(net.points());
                allEdges.addAll(net.wires());
            }
            // 只在 byPoint、不在任何网络的点也要收集（防 clear 后丢失）
            for (String k : byPoint.keySet()) {
                allPoints.add(new WirePoint(k));
            }
            if (allPoints.isEmpty()) return false;
            for (CachedNetwork net : networks.values()) net.topology().clear();
            networks.clear();
            byPoint.clear();
            Map<String, CachedNetwork> assigned = new HashMap<>();
            for (WireEdge e : allEdges) assignEdgeCached(e, assigned);
            // 设备完整性：同方块端子同网络
            Map<String, List<WirePoint>> byPrefix = new HashMap<>();
            for (WirePoint q : allPoints) {
                String prefix = prefixOf(q.key);
                if (prefix != null) byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(q);
            }
            for (List<WirePoint> terms : byPrefix.values()) {
                if (terms.size() < 2) continue;
                CachedNetwork target = null;
                for (WirePoint q : terms) {
                    CachedNetwork n = assigned.get(q.key);
                    if (n != null) { target = n; break; }
                }
                if (target == null) {
                    target = new CachedNetwork(nextNetworkId, null, nextNetworkId);
                    networks.put(target.key(), target);
                    nextNetworkId++;
                }
                for (WirePoint q : terms) {
                    CachedNetwork old = assigned.get(q.key);
                    if (old != null && old != target) {
                        List<WirePoint> oldPoints = new ArrayList<>(old.topology().points());
                        target.topology().merge(old.topology());
                        for (WirePoint r : oldPoints) {
                            assigned.put(r.key, target);
                            byPoint.put(r.key, target); // merge 移入的点更新索引
                        }
                        networks.remove(old.key());
                    }
                    target.topology().addPoint(q); // 未 assigned 端子显式加入（幂等）
                    assigned.put(q.key, target);
                    byPoint.put(q.key, target);
                }
            }
            // 孤立点（无方块前缀，如 J 点）→ 单点网络
            for (WirePoint q : allPoints) {
                if (assigned.containsKey(q.key)) continue;
                CachedNetwork n2 = new CachedNetwork(nextNetworkId, null, nextNetworkId);
                nextNetworkId++;
                n2.topology().addPoint(q);
                byPoint.put(q.key, n2);
                networks.put(n2.key(), n2);
            }
            // 一致性收尾：byPoint 与网络 adjacency 完全一致（任何分支遗漏兜底）
            byPoint.clear();
            for (CachedNetwork net : networks.values()) {
                for (WirePoint p : net.points()) {
                    byPoint.put(p.key, net);
                }
            }
            changed = true;
            graphVersion++;
        } finally { graphLock.writeLock().unlock(); }
        return changed;
    }

    /** 网络拆合一致性收尾：移除空网络对象（无任何端点）。幂等。 */
    public boolean compactNetworks() {
        graphLock.writeLock().lock();
        try {
            boolean removed = networks.values().removeIf(n -> n.nodeCount() == 0);
            if (removed) graphVersion++;
            return removed;
        } finally { graphLock.writeLock().unlock(); }
    }

    /**
     * 批量端点 key 重命名（2026-08-23 航空学物理化：世界坐标 ⇄ 亚层 plot 坐标
     * 迁移）。纯字符串操作（common 零 MC 依赖）。
     * <p>全量快照 → 替换 key → 清图重放（addPoint/addEdge + 设备端子同方块合并 +
     * 孤立点单点网络），保证 byPoint/网络索引一致。仅当至少一个现存 key 命中
     * 映射才执行（无变化返回 false）。
     *
     * @param keyMap 旧 key → 新 key（仅需包含发生变化的端点）
     */
    public boolean remapKeys(Map<String, String> keyMap) {
        if (keyMap == null || keyMap.isEmpty()) return false;
        graphLock.writeLock().lock();
        try {
            Set<WirePoint> allPoints = new HashSet<>();
            List<WireEdge> allEdges = new ArrayList<>();
            for (CachedNetwork net : networks.values()) {
                allPoints.addAll(net.points());
                allEdges.addAll(net.wires());
            }
            // 只在 byPoint、不在任何网络的点也要收集（防 clear 后丢失）
            for (String k : byPoint.keySet()) {
                allPoints.add(new WirePoint(k));
            }
            boolean touched = false;
            for (WirePoint p : allPoints) {
                if (keyMap.containsKey(p.key)) { touched = true; break; }
            }
            if (!touched) return false;
            // 清空重放（与 ensureComponentNetworks 同构：连通分量 + 设备端子同网络）
            for (CachedNetwork net : networks.values()) net.topology().clear();
            networks.clear();
            byPoint.clear();
            Map<String, CachedNetwork> assigned = new HashMap<>();
            for (WireEdge e : allEdges) {
                String ka = keyMap.getOrDefault(e.a.key, e.a.key);
                String kb = keyMap.getOrDefault(e.b.key, e.b.key);
                assignEdgeCached(new WireEdge(new WirePoint(ka), new WirePoint(kb),
                        e.resistance, e.temperatureKey, e.length, e.rendererId,
                        e.colorOverride, e.selfPlaced, e.itemId), assigned);
            }
            // 设备完整性：同方块端子同网络（旧/新方块前缀都可能）
            Map<String, List<WirePoint>> byPrefix = new HashMap<>();
            for (WirePoint p : allPoints) {
                String nk = keyMap.getOrDefault(p.key, p.key);
                String prefix = prefixOf(nk);
                if (prefix != null) {
                    byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(new WirePoint(nk));
                }
            }
            for (List<WirePoint> terms : byPrefix.values()) {
                if (terms.size() < 2) continue;
                CachedNetwork target = null;
                for (WirePoint q : terms) {
                    CachedNetwork n = assigned.get(q.key);
                    if (n != null) { target = n; break; }
                }
                if (target == null) {
                    target = new CachedNetwork(nextNetworkId, null, nextNetworkId);
                    networks.put(target.key(), target);
                    nextNetworkId++;
                }
                for (WirePoint q : terms) {
                    CachedNetwork old = assigned.get(q.key);
                    if (old != null && old != target) {
                        List<WirePoint> oldPoints = new ArrayList<>(old.topology().points());
                        target.topology().merge(old.topology());
                        for (WirePoint r : oldPoints) {
                            assigned.put(r.key, target);
                            byPoint.put(r.key, target);
                        }
                        networks.remove(old.key());
                    }
                    target.topology().addPoint(q);
                    assigned.put(q.key, target);
                    byPoint.put(q.key, target);
                }
            }
            // 孤立点（无方块前缀，如 J 点）→ 单点网络
            for (WirePoint p : allPoints) {
                String nk = keyMap.getOrDefault(p.key, p.key);
                if (assigned.containsKey(nk)) continue;
                CachedNetwork n2 = new CachedNetwork(nextNetworkId, null, nextNetworkId);
                nextNetworkId++;
                n2.topology().addPoint(new WirePoint(nk));
                byPoint.put(nk, n2);
                networks.put(n2.key(), n2);
            }
            // 一致性收尾
            byPoint.clear();
            for (CachedNetwork net : networks.values()) {
                for (WirePoint p : net.points()) {
                    byPoint.put(p.key, net);
                }
            }
            graphVersion++;
            return true;
        } finally { graphLock.writeLock().unlock(); }
    }

    /** 清空图数据（世界卸载/重载）——网络对象清空但保留（元件/结果可复用） */
    public void clearGraph() {
        graphLock.writeLock().lock();
        try {
            for (CachedNetwork net : networks.values()) net.topology().clear();
            networks.clear();
            byPoint.clear();
            graphVersion++;
        } finally { graphLock.writeLock().unlock(); }
    }

    @Override
    public String toString() {
        return "NetworkWorld{" + name + ", link="
                + (link == null ? "null" : link.platform())
                + ", networks=" + networks.size()
                + ", nodes=" + nodeCount() + ", edges=" + edgeCount()
                + ", ver=" + version() + "}";
    }
}