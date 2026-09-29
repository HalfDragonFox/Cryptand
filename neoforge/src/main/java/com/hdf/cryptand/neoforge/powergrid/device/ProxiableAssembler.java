package com.hdf.cryptand.neoforge.powergrid.device;
import com.hdf.cryptand.neoforge.powergrid.device.cache.SourceCacheAssembler;

/**
 * ===== 可被代理组装器（2026-08-30 用户：继承普通组装器 → 代理/可被代理两类） =====
 *
 * 标记接口（extends {@link SourceCacheAssembler}）：实现本接口的组装器对应的
 * 设备【可被设备接线柱（DeviceConnector → {@code ProxyConnectorAssembler}）
 * 贴靠代理】——代理组装器【仅检测本接口的组装器】（用户："代理组装器仅检测
 * 可被代理组装器"），非可被代理设备 → 接线柱不代理（不贴靠/失效）。
 *
 * 对应原版 {@code IAcceptConnector}（加热器/电磁铁/绕组/太阳能/接触器等）。
 */
public interface ProxiableAssembler extends SourceCacheAssembler {
    // 标记接口：设备可被接线柱代理（端子以接线柱位置入网）
}
