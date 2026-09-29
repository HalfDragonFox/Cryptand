package com.hdf.cryptand.circuitsimulation.core;

import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatcher;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.netop.AsyncInteractionManager;
import com.hdf.cryptand.circuitsimulation.netop.NetOpKind;
import com.hdf.cryptand.circuitsimulation.netop.NetOpRequest;
import com.hdf.cryptand.circuitsimulation.solver.SolveResult;

/**
 * 仿真核心（2026-08-16 用户架构：参考架构图，核心与 MC 解耦）。
 * <p>
 * 所有仿真相关操作（网络重建、求解、拆分合并网络等）都提交到本核心处理：
 * 核心向分配器（{@link ThreadDispatchers}，线程 A）请求分配线程（线程 B/C），
 * 由网表相关操作类（{@link com.hdf.cryptand.circuitsimulation.netop.NetlistOperation}）
 * 串行锁定每个网络并持续处理缓冲消息。
 * <p>
 * 架构对应 all.drawio：
 *   - 主线程交互管理类 = 本核心的门面方法（{@link #submitSolve} 等），任何线程可调；
 *   - 异步线程 A = 分配器（{@link ThreadDispatchers#get()}，必占一线程）；
 *   - 线程 B = 异步交互管理类（{@link AsyncInteractionManager}）；
 *   - 线程 C = 网表相关操作类 + 核心执行器（{@link CoreNetOpExecutor}）。
 * <p>
 * 用途：
 *   - MC 启动时直接启动内核（{@link #start()}），仿真操作统一走核心，拆分核心
 *     与 MC 的绑定——核心不依赖任何 MC/游戏类；
 *   - 独立运行（{@link #main}）：不启动 MC 也能跑仿真核心（自测/服务端）。
 */
public final class SimulationCore {

    /** 默认实例（向后兼容；用 {@link #SimulationCore(String)} 创建更多隔离实例） */
    private static final SimulationCore INSTANCE = new SimulationCore("default");

    public static SimulationCore get() { return INSTANCE; }

    // ===== 组合件（每个实例独立 = 网络注册表/求解器/异步交互管理类全部隔离） =====
    private final String name;
    private final NetworkRegistry registry = new NetworkRegistry();
    /** ⚠ 2026-08-30 每实例时间基准（用户：核心实例连接 EDA/MC 各自独立时间）：
     *  默认独立 SimClock（真实流逝）；EDA/仿真场景注入 SimulatedTimeBase（固定
     *  步长）。与求解器共享——executor 求解前设 net.dt = timeBase.advance()。 */
    private final com.hdf.cryptand.circuitsimulation.solver.TimeBase timeBase;
    private final CoreNetOpExecutor executor;

    /** ⚠ 2026-08-30 实例组装器工厂（用户：注册工厂属于核心具体实例——对不同
     *  对象创建单独的隔离存储）：每实例一个——注册回调把创建的每个网络注册进
     *  本实例的网络注册表（隔离——与其他实例互不干扰）。引擎独立完整电路仿真
     *  核心：预制/自定义组装器 + 绑定——单设备天然成网；批量含导线一起注册。 */
    private final com.hdf.cryptand.engine.AssemblerFactory factory;
    private final java.util.concurrent.atomic.AtomicLong factoryKey =
            new java.util.concurrent.atomic.AtomicLong(0);

    private volatile AsyncInteractionManager async;
    private volatile boolean started;
    /** ⚠ 2026-08-30 扩展组件（ECS Component）——外部接口扩展（TCP/UDP/消息等）
     *  经 attach 接入本实例；start/stop 时统一启停。每实例独立扩展列表。 */
    private final java.util.concurrent.CopyOnWriteArrayList<
            com.hdf.cryptand.circuitsimulation.core.extensions.CoreExtension> extensions =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 网络操作任务模式（默认普通模式=虚拟线程；EXCLUSIVE 直算仅测试用） */
    private volatile TaskMode dispatchMode = TaskMode.NORMAL;

    /**
     * 可多实例（2026-08-22 用户架构：核心支持多对象隔离——MC 世界 / 每个 EDA
     * 客户端会话各一个独立核心实例，离线时释放销毁清缓存）。各实例持有独立的
     * 网络注册表 / 执行器 / 异步交互管理类 / 时间基准，互不干扰。
     */
    public SimulationCore() {
        this("core-" + java.util.UUID.randomUUID().toString().substring(0, 8),
                new com.hdf.cryptand.circuitsimulation.solver.SimClock());
    }

    /** 命名实例（诊断 / 会话管理用） */
    public SimulationCore(String name) {
        this(name, new com.hdf.cryptand.circuitsimulation.solver.SimClock());
    }

    /** 命名实例 +【每实例时间基准】（EDA 传 SimulatedTimeBase 固定步长 /
     *  MC 传共享 SimClock 真实流逝；null → 独立 SimClock）。 */
    public SimulationCore(String name,
                          com.hdf.cryptand.circuitsimulation.solver.TimeBase timeBase) {
        this.name = name == null || name.isBlank() ? "core" : name;
        this.timeBase = timeBase == null
                ? new com.hdf.cryptand.circuitsimulation.solver.SimClock() : timeBase;
        // 实例组装器工厂：注册回调 → 本实例网络注册表（引擎网络上下文——图+
        // 组装器+绑定——完整求解；每实例隔离存储）
        this.factory = new com.hdf.cryptand.engine.AssemblerFactory(
                ctx -> registry.registerCtx(factoryKey.incrementAndGet(), ctx));
        this.executor = new CoreNetOpExecutor(registry, this.timeBase);
    }

    /** 实例名（会话管理 / 诊断） */
    public String name() { return name; }

    /** ⚠ 2026-08-30 每实例时间基准（只读；EDA 注入仿真时钟 / MC 注入真实时钟） */
    public com.hdf.cryptand.circuitsimulation.solver.TimeBase timeBase() {
        return timeBase;
    }

    // ===== 生命周期 =====

    /**
     * 启动核心：获取全局分配器（线程 A）+ 创建异步交互管理类（线程 B）。
     * 幂等。启动后即可提交网络操作。
     */
    public synchronized void start() {
        if (started) return;
        ThreadDispatcher dispatcher = ThreadDispatchers.get();
        async = new AsyncInteractionManager(dispatcher, executor, dispatchMode);
        started = true;
        // 扩展启动（外部接口监听等）
        for (com.hdf.cryptand.circuitsimulation.core.extensions.CoreExtension e : extensions) {
            try { e.start(); } catch (Throwable ignored) { }
        }
    }

    /** 是否已启动 */
    public boolean isStarted() { return started; }

    /** 停止核心：清空网络操作记录表（正在执行的操作自然完成）。 */
    public synchronized void stop() {
        // 扩展停止（外部接口关闭监听）
        for (com.hdf.cryptand.circuitsimulation.core.extensions.CoreExtension e : extensions) {
            try { e.stop(); } catch (Throwable ignored) { }
        }
        if (async != null) {
            try { async.clear(); } catch (Throwable ignored) { }
        }
        started = false;
        async = null;
    }

    /** 设置网络操作任务模式（须在 {@link #start()} 前调用才生效） */
    public void setDispatchMode(TaskMode mode) {
        this.dispatchMode = mode == null ? TaskMode.NORMAL : mode;
    }

    // ===== 扩展组件（ECS Component）管理 =====

    /** 接入扩展（TCP/UDP/消息等外部接口扩展；onAttach 后 start 时启动）。
     *  链式返回本实例。 */
    public SimulationCore attach(com.hdf.cryptand.circuitsimulation.core.extensions.CoreExtension ext) {
        if (ext != null) {
            ext.onAttach(this);
            extensions.add(ext);
            if (started) {
                try { ext.start(); } catch (Throwable ignored) { }
            }
        }
        return this;
    }

    /** 断开扩展（stop 后 onDetach） */
    public SimulationCore detach(com.hdf.cryptand.circuitsimulation.core.extensions.CoreExtension ext) {
        if (ext != null && extensions.remove(ext)) {
            try { ext.stop(); } catch (Throwable ignored) { }
            ext.onDetach(this);
        }
        return this;
    }

    /** 已接入扩展列表（诊断/遍历） */
    public java.util.List<com.hdf.cryptand.circuitsimulation.core.extensions.CoreExtension> extensions() {
        return new java.util.ArrayList<>(extensions);
    }

    // ===== 会话管理（2026-08-30 用户：注册实例后客户拿句柄，消息经句柄交互） =====

    /** 活跃会话（sessionId → handle；每前端一个会话） */
    private final java.util.concurrent.ConcurrentHashMap<String,
            com.hdf.cryptand.circuitsimulation.core.extensions.SessionHandle> sessions =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 注册会话：客户获得 {@link SessionHandle}，后续消息经句柄交互
     *  （register/submit/solve/result/unregister/close 全走句柄）。 */
    public com.hdf.cryptand.circuitsimulation.core.extensions.SessionHandle openSession() {
        com.hdf.cryptand.circuitsimulation.core.extensions.SessionHandle h =
                new com.hdf.cryptand.circuitsimulation.core.extensions.SessionHandle(this);
        sessions.put(h.sessionId(), h);
        return h;
    }

    /** 关闭会话（SessionHandle.close 调用） */
    public void closeSession(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    /** 活跃会话数（诊断） */
    public int sessionCount() { return sessions.size(); }

    // ===== 组件访问（诊断/扩展） =====

    /** 网络注册表（直接注册/查询网络与结果） */
    public NetworkRegistry registry() { return registry; }

    /** ⚠ 2026-08-30 推进步长倍率（用户：可配置每步长倍率——不同速度下推进的
     *  真实情况；默认 1× = 20tick/s。倍率 × 0.05 = 每步推进时长——温度/能量/
     *  动力学推进统一使用）。设置作用于本实例全部注册网络。 */
    public void setSimSpeed(double multiplier) {
        double m = multiplier > 0 ? multiplier : 1.0;
        for (NetworkRegistry.Entry e : registry.entries()) {
            if (e.ctx != null) e.ctx.simSpeed = m;
        }
    }

    /** 实例组装器工厂（每实例一个——创建预制/自定义组装器 + 绑定 → 注册进本
     *  实例网络注册表——隔离存储；单设备天然成网、批量含导线一起注册） */
    public com.hdf.cryptand.engine.AssemblerFactory factory() { return factory; }

    /** 核心执行器（同步求解 API 等） */
    public CoreNetOpExecutor executor() { return executor; }

    /** 异步交互管理类（未启动返回 null） */
    public AsyncInteractionManager async() { return async; }

    // ===== 直接 API：仿真相关操作都走核心（向分配器请求线程持续处理） =====

    /** 网络拆分/合并请求（最高优先；data 可传拆合指令，见执行器） */
    public void submitSplitMerge(Object networkKey, Object data) {
        ensureStarted();
        async.submit(networkKey, new NetOpRequest(NetOpKind.SPLIT_MERGE, data));
    }

    /** 网络重建请求（次优先；data=Boolean true → 重建后执行拆合） */
    public void submitRebuild(Object networkKey, Object data) {
        ensureStarted();
        async.submit(networkKey, new NetOpRequest(NetOpKind.REBUILD, data));
    }

    /** 网络求解请求（最低优先；data 可传 forceInit 等标记） */
    public void submitSolve(Object networkKey, Object data) {
        ensureStarted();
        async.submit(networkKey, new NetOpRequest(NetOpKind.SOLVE, data));
    }

    /** 便捷：网络求解请求（无附加数据） */
    public void submitSolve(Object networkKey) {
        submitSolve(networkKey, null);
    }

    /** ⚠ 2026-08-30 通用网络操作提交（扩展/外部接口用：TcpEndpoint 等经此
     *  提交任意 NetOpKind 请求；与 submitSplitMerge/submitRebuild/submitSolve
     *  等价，只是把 kind 参数化）。 */
    public void submit(Object networkKey, NetOpRequest request) {
        if (request == null || networkKey == null) return;
        ensureStarted();
        async.submit(networkKey, request);
    }

    /** 当前待处理/处理中的网络操作记录数（诊断） */
    public int pendingOperations() {
        AsyncInteractionManager a = async;
        return a == null ? 0 : a.size();
    }

    // ===== 网络注册便捷 API =====

    /** 注册网络（核心统一管理；已存在替换） */
    public void registerNetwork(Object key, Network net) {
        registry.register(key, net);
    }

    /** 注销网络 */
    public Network unregisterNetwork(Object key) {
        return registry.unregister(key);
    }

    /** 查询网络 */
    public Network network(Object key) {
        return registry.get(key);
    }

    /** 最近求解结果（未求解/未注册 null） */
    public SolveResult result(Object key) {
        return registry.result(key);
    }

    /** 同步求解（直接调核心执行器，不走分配器——适合一次性/测试/直接 API 调用） */
    public SolveResult solveNow(Object key) {
        Network net = registry.get(key);
        if (net == null) return null;
        SolveResult r = executor.solve(net);
        registry.setResult(key, r);
        return r;
    }

    private void ensureStarted() {
        if (!started) start();
    }

    @Override
    public String toString() {
        return "SimulationCore{started=" + started + ", " + registry
                + ", pending=" + pendingOperations() + "}";
    }

    // ===== 独立运行入口 =====
    // 运行仿真核心类（不启动 MC）：./gradlew :common:runSimCoreTest
    // 或直接 java -cp ... com.hdf.cryptand.circuitsimulation.core.SimulationCore

    public static void main(String[] args) throws Exception {
        SimulationCoreSelfTest.main(args);
    }
}
