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
    private final CoreNetOpExecutor executor = new CoreNetOpExecutor(registry);

    private volatile AsyncInteractionManager async;
    private volatile boolean started;

    /** 网络操作任务模式（默认普通模式=虚拟线程；EXCLUSIVE 直算仅测试用） */
    private volatile TaskMode dispatchMode = TaskMode.NORMAL;

    /**
     * 可多实例（2026-08-22 用户架构：核心支持多对象隔离——MC 世界 / 每个 EDA
     * 客户端会话各一个独立核心实例，离线时释放销毁清缓存）。各实例持有独立的
     * 网络注册表 / 执行器 / 异步交互管理类，互不干扰。
     */
    public SimulationCore() {
        this("core-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    /** 命名实例（诊断 / 会话管理用） */
    public SimulationCore(String name) {
        this.name = name == null || name.isBlank() ? "core" : name;
    }

    /** 实例名（会话管理 / 诊断） */
    public String name() { return name; }

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
    }

    /** 是否已启动 */
    public boolean isStarted() { return started; }

    /** 停止核心：清空网络操作记录表（正在执行的操作自然完成）。 */
    public synchronized void stop() {
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

    // ===== 组件访问（诊断/扩展） =====

    /** 网络注册表（直接注册/查询网络与结果） */
    public NetworkRegistry registry() { return registry; }

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
