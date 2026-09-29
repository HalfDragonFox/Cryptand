package com.hdf.cryptand.neoforge.net;

import com.hdf.cryptand.integratednetwork.NetworkInterface;
import com.hdf.cryptand.integratednetwork.TransferType;
import com.hdf.cryptand.integratednetwork.TransportPayload;

import java.util.List;

/**
 * 网络设备基础接口（2026-08-29 P2 neoforge 平台）：任何「传输设备/管道」方块实体
 * （Pipez / XNet / 自家物流等）实现本接口即可被接入 INC 虚拟网络。
 * <p>
 * 主线程侧操作（遵守铁律：核心只算、不碰 Level/BE）：
 * <ol>
 *   <li>{@link #collect}：主线程收集输入内容（可为物品/流体/能量包装成
 *       {@link TransportPayload}，data 携带真实容器引用）——组表用；</li>
 *   <li>{@link #executeExtract} / {@link #executeWrite}：主线程收到核心产出的
 *       {@link com.hdf.cryptand.integratednetwork.TransferExecutionTable} 后，
 *       按 containerId 对真实容器【直接输入/输出】。</li>
 * </ol>
 * 端口描述用 common 的 {@link NetworkInterface}（输入/输出 + 方向 + 速率），
 * 图中的节点 id = {@code networkKey() + "|" + interface.id}。
 */
public interface INetworkDevice {

    /** 设备能力（单网络单能力：物品/流体/气体/能量/信号/通用） */
    TransferType networkCapability();

    /** 该设备在维度内唯一的网络键（single-pipe-one-network：一个设备 = 一个网络） */
    Object networkKey();

    /** 输入接口列表（端口 → 方向/速率） */
    List<NetworkInterface> inputInterfaces();

    /** 输出接口列表（端口 → 方向/速率） */
    List<NetworkInterface> outputInterfaces();

    /** 设备默认搬运速率（单位/帧；≤0 = 无限制，交给边吞吐） */
    double defaultRate();

    /**
     * 每 tick 最大预提取/传输速率（单位/tick；2026-08-29 用户：每 tick 按传输
     * 速率限量发送，防止一次性全量缓存）。主线程预提取与交付均以本速率封顶。
     * 默认 = {@link #defaultRate()}（≤0 视为不限，交给网络边缘/带宽约束）。
     */
    default double transferRate() {
        return defaultRate();
    }

    /**
     * 传输分配规则（2026-08-29 用户：完全接管原版行为——解析 Pipez 的
     * {@code Distribution} 配置映射到 {@link DistributionRule}）。
     * 默认 NEAREST（Pipez 原版默认模式）；实现方返回实际配置（如 Pipez
     * getDistribution(dir,type) → NEAREST/FURTHEST/ROUND_ROBIN/RANDOM）。
     */
    default com.hdf.cryptand.integratednetwork.DistributionRule transferRule() {
        return com.hdf.cryptand.integratednetwork.DistributionRule.NEAREST;
    }

    /**
     * 主线程收集输入：当前该输入接口可传输的内容追加到 sink。
     *
     * @return true = 有内容（该接口作为输入参与本次传输）
     */
    boolean collect(Object interfaceId, TransferType type, List<TransportPayload> sink);

    /**
     * 主线程按执行表执行：从该容器键移除 amount（真实 IO，可自由使用 MC 对象）。
     *
     * @return 实际移除量
     */
    double executeExtract(Object containerId, double amount);

    /**
     * 主线程按执行表执行：向该容器键写入 amount（真实 IO）。
     *
     * @return 实际写入量
     */
    double executeWrite(Object containerId, double amount);

    /**
     * 预提取（2026-08-29 用户方案 B：预提取 → 网络缓存所有权 → 交付）。
     * 主线程把该输入接口对应【源容器】的真实内容提取出来，移交网络缓存
     * （物品进入缓冲即归属网络，核心异步期间源被拿走/清空不再影响——竞态消除）。
     * 在向核心上报传输请求【之前】对每个输入接口调用一次。
     *
     * @param interfaceId 输入接口 id（缓存量按设备网络 key 汇总）
     * @param maxAmount   本 tick 最多预提取量（单位：物品个数/mB/FE）
     * @return 实际提取到的真实内容列表（ITEM=ItemStack 等；平台层解析），可空
     */
    java.util.List<Object> pull(Object interfaceId, double maxAmount);

    /**
     * 交付（2026-08-29 用户方案 B）：把网络缓存中的真实内容写入目标容器。
     * 目标满 → 写不进去的内容作为【剩余】返回，调用方 {@link NetworkBuffer#putBack}
     * 原样回池（物品不丢），下轮重新上报重试。
     *
     * @param containerId 目标容器键
     * @param contents    真实内容列表（从网络缓存取出）
     * @return 未写入的剩余内容列表（空 = 全部写入成功）
     */
    java.util.List<Object> deliveryWrite(Object containerId, java.util.List<Object> contents);

    /**
     * 本 tick 的接口/容器【变化上报】（2026-08-29 用户：更改输入和输出时也要上报
     * 接口改变；输出口应报告【真实连接的容器】而非猜相邻方块）。
     * <p>
     * 主线程每 tick 调用一次：实现方重新扫描自身接口/容器集合（如 Pipez
     * {@code getConnections()}），与上次快照比较，返回差异为
     * {@code NetworkReport}（ADD/REMOVE_INTERFACE + ADD/REMOVE_CONTAINER）；
     * 空 = 无变化。维度管理器收到非空列表 → {@code core.submitReport} 更新虚拟网络。
     */
    default java.util.List<com.hdf.cryptand.integratednetwork.NetworkReport> tickReports() {
        return java.util.List.of();
    }

    /**
     * 该接口是否已【被卸载】应跳过处理（2026-08-29 用户：输入/输出口连接的容器
     * 被卸载后标记该管道接口为已卸载，处理时跳过此接口）。默认 false。
     */
    default boolean isDisabled(Object interfaceId) {
        return false;
    }

    /**
     * 该输出容器是否已【全满】（不能插入任何物品/容量耗尽）。
     * 2026-08-29 用户：如果输出的容器都满了则不要进行传输——主线程在预提取/
     * 上报前检测，全满则整体跳过（不抽不入缓存/不提交）。
     * 默认 false（由实现方按真实容器饱和度判定）。
     */
    default boolean isOutputFull(Object containerId) {
        return false;
    }

    /**
     * 本次检测到的【消失接口 id】增量（2026-08-29 用户：管道方块变更时发送增量
     * 信息，方便网络查看是否分割/合并）。主线程据此前在 {@link DimensionNetworkManager}
     * 里 submitDestroy 移除旧节点，核心图按连通分量自动拆分/合并。默认空。
     */
    default java.util.Set<Object> removedInterfaceIds() {
        return java.util.Set.of();
    }
}