package com.hdf.cryptand.circuitsimulation.export;

import com.hdf.cryptand.circuitsimulation.cache.NetworkWorld;

/**
 * 原理图导出器扩展点（2026-08-22 用户需求：导出原理图作为核心功能的
 * 【扩展功能】——可插拔格式）。
 * <p>
 * 每个格式一个实现：{@link #format()} 声明格式，{@link #export} 直接
 * 从虚拟电路（{@link NetworkWorld} 的 CachedNetwork 数据）生成原理图。
 * 新格式只要注册进 {@link SchematicExporters} 即可被请求路由。
 */
public interface SchematicExporter {

    /** 本导出器支持的格式（唯一） */
    SchematicFormat format();

    /**
     * 导出原理图。
     * <p>
     * ⚠ 只读虚拟电路数据（WirePoint/WireEdge/DeviceInfo），【禁止】读取
     * 任何 Minecraft Level / BlockEntity / 其他平台对象——这是核心扩展的
     * 铁律（核心与主线程/平台完全隔离）。
     *
     * @param world 目标网络世界实例（虚拟电路数据源，非 null）
     * @param req   导出请求（world/networkKey/format/options）
     */
    SchematicExportResult export(NetworkWorld world, SchematicExportRequest req);
}