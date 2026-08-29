package com.hdf.cryptand.neoforge.powergrid.adapter;

import com.hdf.cryptand.circuitsimulation.cache.CacheOp;
import com.hdf.cryptand.circuitsimulation.cache.NetworkWorld;
import com.hdf.cryptand.circuitsimulation.cache.NetworkWorldManager;
import com.hdf.cryptand.circuitsimulation.compute.TaskMode;
import com.hdf.cryptand.circuitsimulation.netop.AsyncInteractionManager;
import com.hdf.cryptand.circuitsimulation.netop.NetOpKind;
import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;

/**
 * 主线程交互管理类（2026-08-16 用户架构，对应架构图"主线程交互管理类"）。
 * <p>
 * 主线程以及各种事件（接线/拆线/方块变化/求解节拍）→ 本类 → 缓存操作消息
 * （{@link CacheOp}）→ 网络世界（{@link NetworkWorld}，"mc-overworld"）→
 * 异步交互管理类（已在网络世界内，分配器线程串行处理）。本类运行在主线程，
 * 只做消息转发（不执行网络操作），保证 Level 线程安全边界。
 * <p>
 * 2026-08-22 用户架构对接：网络世界 = 具体实例，统一管理对话（{@link McAppLink}）
 * + 缓存数据 + 消息；由 {@link NetworkWorldManager} 全局统一管理。
 * <p>
 * 网络操作记录表按网络键锁定（借用 Rust 所有权：每个网络同时只有一个网表相关
 * 操作类，操作未完成前该网络一直被占用）：
 *   - 自管模式（WireNetworkManager）：整个自管图视为一个逻辑网络，记录表按
 *     全局键锁定（所有网络操作串行锁定直到完成）；
 *   - 原版模式：按 {@link ElectricalNetwork} 对象分键锁定（null → 全局键）。
 */
public final class MainThreadInteractionManager {

    private static final MainThreadInteractionManager INSTANCE =
            new MainThreadInteractionManager();

    public static MainThreadInteractionManager get() {
        return INSTANCE;
    }

    /** 自管模式全局网络键（记录表主键） */
    private static final Object GLOBAL_NETWORK = new Object() {
        @Override
        public String toString() {
            return "GLOBAL_NETWORK";
        }
    };

    private final Object initLock = new Object();
    private volatile NetworkWorld world;

    /**
     * 懒初始化网络世界（"mc-overworld"）：首次转发消息时创建。⚠ 必须先
     * {@code PhasorEngine.init()}（幂等）——它把线程数/虚拟线程数配置写入全局
     * 分配器单例（首次 get() 前生效），再取分配器，防止过早创建分配器导致配置失效。
     * 2026-08-22 网络世界 = 具体实例：统一管理对话（{@link McAppLink}）+ 缓存数据
     * + 消息；由 {@link NetworkWorldManager} 全局统一管理。
     */
    private NetworkWorld lazyWorld() {
        NetworkWorld w = world;
        if (w != null) return w;
        synchronized (initLock) {
            w = world;
            if (w != null) return w;
            PhasorEngine.init();
            TaskMode mode = TaskMode.NORMAL;
            try {
                String s = ConfigLoad.CRYPTAND_NETOP_DISPATCH_MODE.get();
                if (s != null && s.trim().equalsIgnoreCase("EXCLUSIVE")) mode = TaskMode.EXCLUSIVE;
            } catch (Throwable ignored) {
            }
            w = NetworkWorld.create(WireNetworkManager.CACHE_NAME,
                    McAppLink.INSTANCE, CryptandNetOpExecutor.get(), mode);
            world = w;
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[CacheMgr] mc world ready {} worlds={}",
                    w, NetworkWorldManager.get().names());
            return w;
        }
    }

    /** 是否启用网络操作记录表/网表相关操作类（配置开关） */
    public static boolean enabled() {
        try {
            return ConfigLoad.ENABLE_CRYPTAND_NETOP.get();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自管模式（求解可后台线程安全执行） */
    private static boolean isSelfManaged() {
        try {
            return PowerGridWireConverter.isEnabled()
                    && WireNetworkManager.get().nodeCount() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 网络键：自管模式 → 全局键；否则按网络对象分键（null → 全局键） */
    private static Object networkKey(ElectricalNetwork net) {
        if (isSelfManaged()) return GLOBAL_NETWORK;
        return net != null ? net : GLOBAL_NETWORK;
    }

    /**
     * 网络内容破坏（设备方块拆除/导线剪切，主线程调用，2026-08-22 用户架构）：
     * 发 DESTROY 消息 → 异步线程移除自管图对应端子/导线，随后拆合 + 重建。
     * 主线程不再直接删图（避免区块重载误删/竞态），只发消息交给核心缓存实例。
     * <p>2026-08-23 统一协议：走 {@link #postGrid}（GridMessage：网络包 + 设备包）。
     *
     * @param target 破坏目标：设备方块 {@link net.minecraft.core.BlockPos} 或
     *               导线 {@link com.hdf.cryptand.circuitsimulation.netgraph.WireEdge}
     */
    public void postDestroy(Object target) {
        if (!enabled() || target == null) return;
        Object key = networkKey(null);
        postGrid(new com.hdf.cryptand.circuitsimulation.netop.GridMessage(key,
                com.hdf.cryptand.circuitsimulation.netop.GridMessage.NetPackage.of(
                        NetOpKind.DESTROY, target),
                com.hdf.cryptand.circuitsimulation.netop.GridMessage.DevicePackage.empty()));
    }

    /**
     * 拓扑事件（接线/拆线/方块变化，主线程调用）：拆合（结构性）+ 重建请求。
     * <p>2026-08-23 统一协议：两条仅网络包消息（preTopology → {@link #postGrid}）。
     *
     * @param net        受影响网络（可 null = 全局）
     * @param structural true = 物理连通性变化（接线合并/拆线分裂）→ 提交拆合操作
     */
    public void postTopology(ElectricalNetwork net, boolean structural) {
        if (!enabled()) return;
        Object key = networkKey(net);
        if (structural) {
            postGrid(com.hdf.cryptand.circuitsimulation.netop.GridMessage.ofOnlyNet(key,
                    com.hdf.cryptand.circuitsimulation.netop.GridMessage.NetPackage.of(
                            NetOpKind.SPLIT_MERGE, net)));
        }
        postGrid(com.hdf.cryptand.circuitsimulation.netop.GridMessage.ofOnlyNet(key,
                com.hdf.cryptand.circuitsimulation.netop.GridMessage.NetPackage.of(
                        NetOpKind.REBUILD, new Object[]{net, structural})));
    }

    /**
     * 求解节拍（异步调度频率到点，主线程/调度线程调用）：SOLVE 请求——
     * 多条求解请求在缓冲中整合为一条（一次 roundFromGraph）。
     * <p>2026-08-23 统一协议：仅网络包消息（{@link #postGrid}）。
     *
     * @param forceInit 进入世界初始化（全量重建+参数更新，不求解写回）
     */
    public void postSolve(boolean forceInit) {
        if (!enabled()) return;
        postGrid(com.hdf.cryptand.circuitsimulation.netop.GridMessage.ofOnlyNet(
                networkKey(null),
                com.hdf.cryptand.circuitsimulation.netop.GridMessage.NetPackage.of(
                        NetOpKind.SOLVE, forceInit)));
    }

    // ==================== 网格消息协议（2026-08-23 用户） ====================
    // 一条消息 = 【网络包】 + 【电气设备列表包】；两个包都可以为空包：
    //   - 网络包：网络操作信息（可 NOOP 无需操作；特殊情况【直接重建+求解】）；
    //   - 设备列表包：增量更新【电气设备的组装器列表】（删除/变更/新增）。
    // 约束（协议）：
    //   - 一条消息仅包含【同一网络】内容；多网络分多次发送（第二条仅网络包）。
    //   - 设备列表包非空时【必须加上网络包】（即使 NOOP），否则无效（日志打印）。

    /** 提交统一网格消息（协议校验/无效打印在 NetworkWorld.submit(GridMessage)） */
    public void postGrid(com.hdf.cryptand.circuitsimulation.netop.GridMessage msg) {
        if (!enabled() || msg == null) return;
        lazyWorld().submit(msg);
    }

    /** 网络直接重建 + 求解请求（特殊情况调用；网络包，设备包为空） */
    public void postRebuildSolve(Object data) {
        if (!enabled()) return;
        lazyWorld().submit(new com.hdf.cryptand.circuitsimulation.netop.GridMessage(
                networkKey(null),
                com.hdf.cryptand.circuitsimulation.netop.GridMessage.NetPackage.rebuildSolve(data),
                com.hdf.cryptand.circuitsimulation.netop.GridMessage.DevicePackage.empty()));
    }

    /** 电气设备列表包增量更新（删除/变更/新增组装器列表）。
     *  ⚠ 协议：设备包非空时必须带网络包 → 这里带 NOOP 网络包（无需操作）。
     *  @param addOrUpdate 新增/变更条目：{@link DeviceAssemblerUpdate} 或
     *                     {@link com.hdf.cryptand.circuitsimulation.cache.DeviceInfo}
     *  @param remove      删除条目：{@link net.minecraft.core.BlockPos} */
    public void postDeviceDelta(java.util.List<Object> addOrUpdate,
                                java.util.List<Object> remove) {
        if (!enabled()) return;
        lazyWorld().submit(new com.hdf.cryptand.circuitsimulation.netop.GridMessage(
                networkKey(null),
                com.hdf.cryptand.circuitsimulation.netop.GridMessage.NetPackage.noop(),
                new com.hdf.cryptand.circuitsimulation.netop.GridMessage.DevicePackage(
                        addOrUpdate, remove)));
    }

    /** 一条消息：设备列表包增量 + 网络包【重建+求解】（设备增量是重建输入，
     *  常用更新路径） */
    public void postRebuildSolveWithDevices(Object data,
                                            java.util.List<Object> addOrUpdate,
                                            java.util.List<Object> remove) {
        if (!enabled()) return;
        lazyWorld().submit(new com.hdf.cryptand.circuitsimulation.netop.GridMessage(
                networkKey(null),
                com.hdf.cryptand.circuitsimulation.netop.GridMessage.NetPackage.rebuildSolve(data),
                new com.hdf.cryptand.circuitsimulation.netop.GridMessage.DevicePackage(
                        addOrUpdate, remove)));
    }

    /** 网络世界（"mc-overworld"；可能 null 未创建）——诊断/数据对接 */
    public NetworkWorld world() {
        return world;
    }

    /** 异步交互管理类（诊断；首次调用触发懒初始化） */
    public AsyncInteractionManager asyncManager() {
        NetworkWorld w = lazyWorld();
        return w == null ? null : w.async();
    }

    /** 记录表大小（诊断：当前被占用/锁定的网络数） */
    public int recordSize() {
        NetworkWorld w = world;
        AsyncInteractionManager a = w == null ? null : w.async();
        return a == null ? 0 : a.size();
    }

    /** 世界切换/关闭 → 清空记录表（释放所有网络锁） */
    public void clearRecords() {
        NetworkWorld w = world;
        AsyncInteractionManager a = w == null ? null : w.async();
        if (a != null) a.clear();
    }

    /** 世界切换/关闭 → 释放网络世界实例（对话 + 缓存数据 + 记录表；全局管理器移除） */
    public void releaseCache() {
        NetworkWorld w = world;
        world = null;
        if (w != null) {
            NetworkWorldManager.get().removeWorld(w.name());
        }
    }
}
