package com.hdf.cryptand.neoforge.net;

import com.hdf.cryptand.integratednetwork.NetworkInterface;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

/**
 * 结构化设备扩展接口（2026-09-07 core 平台化）：
 * 设备除输入/输出接口外，还暴露【纯结构节点】（如 Pipez 管道段 p-p 边）与
 * 接口增量 diff——供维度网络管理器绘制/更新段网络。实现方 = 各传输模组适配器
 * （如 pipez 子包的 PipezDeviceAdapter）。core/net 平台只依赖本接口与
 * {@link InterfaceDiff}，不 import 任何子包实现类。
 */
public interface IStructuredDevice {

    /** 结构节点列表（仅网络结构信息；不参与搬运/交付） */
    List<NetworkInterface> structureNodes();

    /** 本 tick 的接口/结构增量 diff（空 = 无变化） */
    InterfaceDiff interfaceDiff();

    /** 接口 id → 所属管道段 pos（输入/输出锚点） */
    Map<Object, BlockPos> ifaceAnchor();

    /** 段间物理边（相邻管道段对；p-p 边） */
    List<BlockPos[]> pipeEdges();
}
