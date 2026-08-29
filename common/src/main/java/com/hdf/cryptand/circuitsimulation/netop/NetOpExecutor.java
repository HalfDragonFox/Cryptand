package com.hdf.cryptand.circuitsimulation.netop;

/**
 * 网络操作执行器（2026-08-16 用户架构；游戏层实现）。
 * <p>
 * 网表相关操作类（{@link NetlistOperation}）把整合后的操作交给本接口执行——
 * 核心类不依赖具体网络实现，由平台/游戏层按网络类型实现实际的拆合/重建/求解
 * （如 PowerGrid 的 WireNetworkManager / 相量引擎）。
 * <p>
 * 约定：
 *   - 本接口方法在分配器分配的线程 C 上调用（普通模式为虚拟线程）；
 *   - 同一网络的操作已被网表相关操作类串行锁定（缓冲 + 记录表），实现方无需
 *     再加锁，但内部仍需保证线程安全（可能与其他网络的执行并发）。
 */
public interface NetOpExecutor {

    /**
     * 执行网络内容破坏（设备方块拆除/导线剪切）。
     * 主线程已确认目标确实被移除（区块重载等假删除已过滤），本方法在异步线程
     * 只操作自管图（内部有锁），不碰 level/BE。
     *
     * @param networkKey 网络键（网络操作记录表主键，= 所属 NetlistOperation 的键）
     * @param data       附加数据：设备方块 BlockPos 或导线 WireEdge（可 null）
     * @return true = 已处理
     */
    boolean executeDestroy(Object networkKey, Object data);

    /**
     * 执行网络拆分/合并操作。
     *
     * @param networkKey 网络键（网络操作记录表主键，= 所属 NetlistOperation 的键）
     * @param data       附加数据（可 null）
     * @return true = 已处理
     */
    boolean executeSplitMerge(Object networkKey, Object data);

    /**
     * 执行网络重建。
     *
     * @return true = 重建过程中检测到需要网络拆合 → 重建完成后由网表相关操作类
     *         再执行一次 {@link #executeSplitMerge}
     */
    boolean executeRebuild(Object networkKey, Object data);

    /**
     * 执行网络求解。
     *
     * @param networkKey 网络键
     * @param data       附加数据（可 null，如 forceInit 标记）
     */
    void executeSolve(Object networkKey, Object data);

    /**
     * 执行电气设备列表包增量更新（2026-08-23 用户协议：删除/变更/新增组装器列表）。
     * <p>
     * data = {@link GridMessage.DevicePackage}（元素为平台层对象：
     * neoforge 侧 {@code DeviceAssemblerUpdate(BlockPos, Assembler)} / 
     * {@code DeviceInfo}（新增/变更）与 {@code BlockPos}（删除））。
     * 平台实现解释并应用（登记/取消设备元数据、失效/预注册设备缓存）；
     * 本方法在重建之前执行（设备增量是重建输入）。
     *
     * @param networkKey 网络键
     * @param data       GridMessage.DevicePackage（可 null）
     * @return true = 已处理
     */
    default boolean executeDeviceDelta(Object networkKey, Object data) {
        return false;
    }
}
