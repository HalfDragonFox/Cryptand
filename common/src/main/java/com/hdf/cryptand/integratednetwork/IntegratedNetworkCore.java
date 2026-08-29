package com.hdf.cryptand.integratednetwork;

import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;

/**
 * 集成网络核心（Integrated Network Core，2026-08-26 用户架构命名：
 * "集成网络核心" / INC）。
 * <p>
 * 一套【完全后台异步】的通用传输/物流/信号核心——【独立实现的单独核心】，
 * 与电路仿真核心（{@link com.hdf.cryptand.circuitsimulation.core.SimulationCore}）
 * 平级、解耦（位于独立的 {@code com.hdf.cryptand.integratednetwork} 包，
 * 不在 circuitsimulation 命名空间内；仅复用通用线程分配器
 * {@link com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers}）。面向
 * "内容流动"（物品 / 流体 / 能量 / 无线电信号），后续可支撑物流管道、无线电、
 * 自动化网络等内容：
 * <ul>
 *   <li>【主线程零计算】——只有 4 种消息：{@link #submitDestroy} /
 *       {@link #submitTopology} / {@link #submitRoute} / {@link #submitTick}；
 *       所有路由 / 吞吐 / 延迟 / 丢包运算都在核心（虚拟线程）完成；</li>
 *   <li>【每网串行锁定】——{@link AsyncTransportManager} 记录表 + 缓冲 + 消息整合，
 *       同一传输网的操作单独锁定直到完成；不同传输网并行；</li>
 *   <li>【TICK 批量合并】——主线程每个 tick 投一帧，核心合并后一次执行多步
 *       （帧数累加），彻底把管道/物流计算移出主线程——替代其他 mod 严格主线程
 *       卡顿的架构基础；</li>
 *   <li>【核心铁律】——核心只操作纯虚拟 {@link TransportGraph}，绝不写回
 *       Level/BlockEntity；结果经帧缓存/监听器交由主线程同步应用；</li>
 *   <li>【多实例隔离】——{@link IntegratedNetworkCoreRegistry} 可创建命名实例
 *       （MC 世界 / 每个客户端/子系统各一个），各自图与结果隔离，离线释放。</li>
 * </ul>
 * 线程模型（同 all.drawio）：
 *   - 线程 A = 分配器（{@link ThreadDispatchers#get()}，共享）；
 *   - 线程 B = 异步传输管理类（{@link AsyncTransportManager}）；
 *   - 线程 C = 传输操作类 + 执行器（{@link TransportOperation} /
 *       {@link CoreTransportExecutor}）。
 */
public final class IntegratedNetworkCore {

    /** 默认实例（向后兼容；可用 {@link #IntegratedNetworkCore(String)} 建更多隔离实例） */
    private static final IntegratedNetworkCore INSTANCE = new IntegratedNetworkCore("default");

    public static IntegratedNetworkCore get() {
        return INSTANCE;
    }

    // ===== 组合件（每个实例独立 = 传输图/结果/异步管理类全部隔离） =====
    private final String name;
    /** 内置默认执行器（纯虚拟图）；可用 {@link #setExecutor} 替换为平台实现 */
    private final CoreTransportExecutor defaultExecutor = new CoreTransportExecutor();

    private volatile TransportExecutor executor;
    private volatile AsyncTransportManager async;
    private volatile boolean started;

    /** 传输操作任务模式（默认普通=虚拟线程；EXCLUSIVE 直算仅测试用） */
    private volatile TaskMode dispatchMode = TaskMode.NORMAL;

    /** 默认可多实例（核心支持多对象隔离——MC 世界 / 每个子系统各一个核心实例） */
    public IntegratedNetworkCore() {
        this("inc-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    /** 命名实例（诊断 / 隔离管理用） */
    public IntegratedNetworkCore(String name) {
        this.name = name == null || name.isBlank() ? "inc" : name;
    }

    /** 实例名（隔离管理 / 诊断） */
    public String name() {
        return name;
    }

    // ===== 生命周期 =====

    /** 启动核心：获取全局分配器（线程 A）+ 创建异步传输管理类（线程 B）。幂等。 */
    public synchronized void start() {
        if (started) return;
        ThreadDispatcher dispatcher = ThreadDispatchers.get();
        if (executor == null) executor = defaultExecutor;
        async = new AsyncTransportManager(dispatcher, executor, dispatchMode);
        started = true;
    }

    /** 是否已启动 */
    public boolean isStarted() {
        return started;
    }

    /** 停止核心：清空传输操作记录表（正在执行的操作自然完成）。 */
    public synchronized void stop() {
        if (async != null) {
            try {
                async.clear();
            } catch (Throwable ignored) {
            }
        }
        started = false;
        async = null;
    }

    /** 设置传输操作任务模式（须在 {@link #start()} 前调用才生效） */
    public void setDispatchMode(TaskMode mode) {
        this.dispatchMode = mode == null ? TaskMode.NORMAL : mode;
    }

    /**
     * 设置传输执行器（平台实现，如对接实际管道方块 / 无线电部件；默认内置
     * {@link CoreTransportExecutor}）。须在 {@link #start()} 前调用。
     */
    public void setExecutor(TransportExecutor ex) {
        if (started) throw new IllegalStateException("已在运行，无法替换执行器");
        this.executor = ex;
    }

    /**
     * 设置帧监听器（核心线程回调）；内置执行器场景（普通模式）生效。
     * 回调内只做事件收集/入队，勿做 MC 主线程调用。
     */
    public void setListener(TransportListener l) {
        defaultExecutor.setListener(l);
        if (executor instanceof CoreTransportExecutor ce) ce.setListener(l);
    }

    // ===== 组件访问（诊断/扩展） =====

    /** 当前生效的执行器（未启动未设置时为默认内置执行器） */
    public TransportExecutor executor() {
        return executor == null ? defaultExecutor : executor;
    }

    /** 异步传输管理类（未启动返回 null） */
    public AsyncTransportManager async() {
        return async;
    }

    /** 内置默认执行器（始终可用；图/结果访问在不换执行器时经它） */
    public CoreTransportExecutor coreExecutor() {
        return defaultExecutor;
    }

    // ===== 直接 API：传输操作都走核心（向分配器请求线程持续处理） =====

    /** 拓扑破坏请求（最高优先；data = TransportChange 或 List） */
    public void submitDestroy(Object key, Object data) {
        ensureStarted();
        async.submit(key, new TransportOpRequest(TransportOpKind.DESTROY, data));
    }

    /** 拓扑变更请求（data = TransportChange 或 List） */
    public void submitTopology(Object key, Object data) {
        ensureStarted();
        async.submit(key, new TransportOpRequest(TransportOpKind.TOPOLOGY, data));
    }

    /** 拓扑变更请求（单条） */
    public void submitTopology(Object key, TransportChange change) {
        submitTopology(key, (Object) change);
    }

    /** 路由重算请求（可选显式触发；拓扑脏时 TICK 自动重算） */
    public void submitRoute(Object key) {
        ensureStarted();
        async.submit(key, new TransportOpRequest(TransportOpKind.ROUTE));
    }

    /** 流动推进请求（默认 1 帧；多条 TICK 在核心合并累加帧数一次执行） */
    public void submitTick(Object key, int frames) {
        ensureStarted();
        async.submit(key, new TransportOpRequest(TransportOpKind.TICK,
                Math.max(1, frames)));
    }

    /** 流动推进请求（1 帧） */
    public void submitTick(Object key) {
        submitTick(key, 1);
    }

    /** 当前待处理/处理中的传输操作记录数（诊断） */
    public int pendingOperations() {
        AsyncTransportManager a = async;
        return a == null ? 0 : a.size();
    }

    // ===== 传输图注册/查询（内置执行器） =====

    /** 注册/替换已建好的传输图 */
    public void registerGraph(Object key, TransportGraph g) {
        defaultExecutor.register(key, g);
    }

    /** 注销传输图 */
    public TransportGraph unregisterGraph(Object key) {
        return defaultExecutor.unregister(key);
    }

    /** 查询传输图（默认内置执行器） */
    public TransportGraph graph(Object key) {
        return coreExecutorEffective().graph(key);
    }

    /** 查询最近帧结果（未运行/未注册 null） */
    public TransportResult result(Object key) {
        return coreExecutorEffective().result(key);
    }

    /** 累计送达负载数（跨步长期累计；未运行 0） */
    public long delivered(Object key) {
        return coreExecutorEffective().delivered(key);
    }

    /** 累计丢弃负载数（跨步长期累计；未运行 0） */
    public long dropped(Object key) {
        return coreExecutorEffective().dropped(key);
    }

    /** 累计移入在途的总量（跨步长期累计；未运行 0） */
    public double moved(Object key) {
        return coreExecutorEffective().moved(key);
    }

    private CoreTransportExecutor coreExecutorEffective() {
        if (executor instanceof CoreTransportExecutor ce) return ce;
        return defaultExecutor;
    }

    // ===== 生产入口（线程安全轻量注入） =====

    /**
     * 注入负载到源节点缓冲（任意线程可调，同步缓冲；容量超限拒绝）。
     * 生产侧（仓储/发射器）调它入网；拓扑变更请走 {@link #submitTopology}。
     *
     * @return 是否成功入缓冲
     */
    public boolean inject(Object key, Object sourceNodeId, TransportPayload p) {
        TransportGraph g = coreExecutorEffective().graph(key);
        return g != null && g.inject(sourceNodeId, p);
    }

    // ===== 同步 API（直接调核心执行器，不走分配器；一次性/测试/EDA 直调） =====

    /** 同步推进指定传输网 N 帧；返回最近帧结果（未注册 null）。 */
    public TransportResult tickNow(Object key, int frames) {
        TransportGraph g = coreExecutorEffective().graph(key);
        if (g == null) return null;
        coreExecutorEffective().executeTick(key, Math.max(1, frames));
        return coreExecutorEffective().result(key);
    }

    private void ensureStarted() {
        if (!started) start();
    }

    @Override
    public String toString() {
        return "IntegratedNetworkCore{name=" + name + ", started=" + started
                + ", graphs=" + (executor == null ? defaultExecutor.graphCount() : "?")
                + ", pending=" + pendingOperations() + "}";
    }

    // ===== 独立运行入口 =====
    // 运行传输网络核心自测（不启动 MC）：./gradlew :common:runTransportTest

    public static void main(String[] args) throws Exception {
        IntegratedNetworkCoreSelfTest.main(args);
    }
}