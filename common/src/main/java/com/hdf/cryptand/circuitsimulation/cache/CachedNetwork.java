package com.hdf.cryptand.circuitsimulation.cache;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WireNetwork;
import com.hdf.cryptand.circuitsimulation.netgraph.WirePoint;
import com.hdf.cryptand.circuitsimulation.netgraph.WireSegment;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 网络缓存组件（2026-08-22 用户架构：缓存组件系统，ECS 风格【只存数据】）。
 * <p>
 * 一个网络 = 完整的网络数据容器，包含网络相关的全部数据：
 * <ul>
 *   <li><b>导线数据</b>：网络内全部导线（{@link WireEdge}：端点/电阻/温度/长度/
 *       渲染/染色/物品），端点（{@link WirePoint}），连续段（{@link WireSegment}）；
 *       —— 委托 {@link WireNetwork}（netgraph 现成拓扑算法，零 MC 依赖）。</li>
 *   <li><b>元件数据</b>：求解元件（{@link Element}）与复合元件（{@link CompositeElement}）。</li>
 *   <li><b>求解结果</b>：最近一次 {@link SolveResult} + 求解版本。</li>
 *   <li><b>网络属性</b>：频率 / 时间 / 步长 / 参考节点 / 稳态标记。</li>
 * </ul>
 * <p>
 * ECS 语义：本类【只存数据】——重建/拆合/求解等【行为】全部在核心
 * （NetOpExecutor / 平台执行器）完成，本类不实现任何图算法以外的行为。
 * <p>
 * 线程安全：导线拓扑由内部 {@link WireNetwork} 读写锁保证；元件/结果用
 * 本类读写锁（{@link #dataLock}）+ volatile。
 */
public final class CachedNetwork {

    /** 网络键（网络 ID / 世界坐标 / 前端电路 ID，任意稳定对象） */
    private final Object key;
    /** 网络名称（可选，诊断/前端引用） */
    private volatile String name;
    /** 网络 id（连通分量自动分配；显式创建时 = key.toString 哈希兜底） */
    public final long id;

    /** 导线拓扑（netgraph 现成算法：端点/边/连续段/连通） */
    private final WireNetwork topology;

    /** 元件数据（求解用；ECS 纯数据） */
    private final List<Element> elements = new ArrayList<>();
    /** 复合元件数据（设备复合模型/导线段；ECS 纯数据） */
    private final List<CompositeElement> composites = new ArrayList<>();

    /** 最近求解结果（volatile 跨线程发布） */
    private volatile SolveResult result;
    /** 求解版本（每次求解 +1） */
    private volatile long solvedVersion;
    /** 元件/属性数据版本（导线拓扑版本在 {@link WireNetwork#version()}） */
    private long metaVersion;

    private final ReentrantReadWriteLock dataLock = new ReentrantReadWriteLock();

    // ===== 网络属性（求解参数；ECS 数据） =====

    /** 网络交流频率（Hz）。0 = DC / 时域；&gt;0 = AC 相量 */
    public volatile double frequency = 0;
    /** 当前仿真时间（秒） */
    public volatile double time = 0;
    /** 时间步长（秒，时域瞬态） */
    public volatile double dt = 0.05;
    /** 参考节点（地），默认 0 */
    public volatile int groundNode = 0;
    /** 状态是否已稳定（稳态电路跳过求解） */
    public volatile boolean stateSettled = true;

    /** 自动 id 兜底序号（显式创建未指定 id 时） */
    private static final java.util.concurrent.atomic.AtomicLong AUTO_ID =
            new java.util.concurrent.atomic.AtomicLong();

    public CachedNetwork(Object key) {
        this(key, null, AUTO_ID.incrementAndGet());
    }

    public CachedNetwork(Object key, String name) {
        this(key, name, AUTO_ID.incrementAndGet());
    }

    public CachedNetwork(Object key, String name, long id) {
        if (key == null) throw new IllegalArgumentException("CachedNetwork key must not be null");
        this.key = key;
        this.name = name;
        this.id = id;
        this.topology = new WireNetwork(id);
    }

    // ===== 身份 =====

    public Object key() { return key; }

    public String name() { return name; }

    public void setName(String n) { this.name = n; }

    /** 内部导线拓扑（NetworkCache 图算法用；只读行为委托——图操作走本类方法） */
    public WireNetwork topology() { return topology; }

    // ===== 导线拓扑（委托 WireNetwork，ECS 数据） =====

    /** 加入导线（自动补端点；幂等） */
    public void addWire(WireEdge e) {
        if (e != null) topology.addEdge(e);
    }

    /** 移除导线（按端点对；不存在忽略） */
    public void removeWire(WirePoint a, WirePoint b) {
        topology.removeEdge(a, b);
    }

    /** 加入孤立端点（设备悬空端子；幂等） */
    public void addPoint(WirePoint p) {
        if (p != null) topology.addPoint(p);
    }

    /** 移除端点及其全部导线 */
    public void removePoint(WirePoint p) {
        if (p != null) topology.removeNode(p);
    }

    /** 全部端点（不可变视图） */
    public Set<WirePoint> points() { return topology.points(); }

    /** 全部导线（不可变视图） */
    public Set<WireEdge> wires() { return topology.edges(); }

    /** 某端点的邻接导线（不可变；端点不存在返回空集） */
    public Set<WireEdge> adjacent(WirePoint p) { return topology.adjacent(p); }

    /** 连续段（度≠2/边界节点之间的连续导线路径） */
    public List<WireSegment> segments() { return topology.segments(); }

    /** 端点是否在网络中 */
    public boolean contains(WirePoint p) { return topology.contains(p); }

    /** 端点数量 */
    public int nodeCount() { return topology.nodeCount(); }

    /** 导线数量 */
    public int edgeCount() { return topology.edgeCount(); }

    // ===== 元件数据（ECS 纯数据） =====

    /** 加入基础元件（求解装配用） */
    public void addElement(Element e) {
        if (e == null) return;
        dataLock.writeLock().lock();
        try {
            elements.add(e);
            metaVersion++;
        } finally { dataLock.writeLock().unlock(); }
    }

    /** 加入复合元件（设备复合模型/导线段；求解后统一 update） */
    public void addComposite(CompositeElement c) {
        if (c == null) return;
        dataLock.writeLock().lock();
        try {
            composites.add(c);
            metaVersion++;
        } finally { dataLock.writeLock().unlock(); }
    }

    /** 全部基础元件（快照） */
    public List<Element> elements() {
        dataLock.readLock().lock();
        try { return new ArrayList<>(elements); } finally { dataLock.readLock().unlock(); }
    }

    /** 全部复合元件（快照） */
    public List<CompositeElement> composites() {
        dataLock.readLock().lock();
        try { return new ArrayList<>(composites); } finally { dataLock.readLock().unlock(); }
    }

    // ===== 求解结果 =====

    /** 记录最近求解结果（核心执行器调用） */
    public void setResult(SolveResult r) {
        this.result = r;
        this.solvedVersion++;
    }

    /** 最近求解结果（未求解 null） */
    public SolveResult result() { return result; }

    /** 求解版本（每次求解 +1） */
    public long solvedVersion() { return solvedVersion; }

    // ===== 版本 =====

    /** 结构版本（导线拓扑版本 + 元件/属性版本；任何数据变化 +1） */
    public long version() {
        return topology.version() + metaVersion;
    }

    @Override
    public String toString() {
        return "CachedNetwork{key=" + key + ", name=" + name
                + ", nodes=" + nodeCount() + ", edges=" + edgeCount()
                + ", elements=" + elements.size() + ", composites=" + composites.size()
                + ", ver=" + version() + "}";
    }
}
