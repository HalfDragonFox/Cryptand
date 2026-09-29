package com.hdf.cryptand.neoforge.net;

import com.hdf.cryptand.integratednetwork.NetworkInterface;

import java.util.List;

/**
 * 设备接口增量 diff（2026-09-07 由 pipez 适配器嵌套类型提升为 net 平台类型）：
 * 结构化设备（IStructuredDevice）每 tick 的接口/结构新增与移除快照差异——
 * 平台管理器据此发 TransportChange 增量（核心按连通分量自动拆/合）。
 */
public record InterfaceDiff(
        List<NetworkInterface> addedInputs,
        List<Object> removedInputs,
        List<NetworkInterface> addedOutputs,
        List<Object> removedOutputs,
        List<NetworkInterface> addedStructs,
        List<Object> removedStructs) {

    public static InterfaceDiff empty() {
        return new InterfaceDiff(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return addedInputs.isEmpty() && removedInputs.isEmpty()
                && addedOutputs.isEmpty() && removedOutputs.isEmpty()
                && addedStructs.isEmpty() && removedStructs.isEmpty();
    }
}
