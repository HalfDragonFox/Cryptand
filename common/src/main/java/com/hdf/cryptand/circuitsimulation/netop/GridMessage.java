package com.hdf.cryptand.circuitsimulation.netop;

import java.util.Collections;
import java.util.List;

/**
 * 主线程 → 核心 网格消息（2026-08-23 用户协议）。
 * <p>
 * 一条消息 = 【网络包】 + 【电气设备列表包】，两个包都可以为空包：
 * <ul>
 *   <li><b>网络包</b>（{@link NetPackage}）：网络操作信息——可设置【无需操作】
 *       （NOOP：ops 为空）；特殊情况可发送【网络直接重建以及求解请求】
 *       （{@link NetPackage#rebuildSolve}）；data = 网络操作附加数据（可 null）。</li>
 *   <li><b>设备列表包</b>（{@link DevicePackage}）：【增量更新】电气设备的组装器
 *       列表——删除/变更/新增（addOrUpdate / remove）。元素为平台层对象
 *       （如 neoforge {@code DeviceAssemblerUpdate} / {@code DeviceInfo}），
 *       核心协议不解释，由平台执行器（NetOpExecutor）解释应用。</li>
 * </ul>
 * <b>约束（用户协议）</b>：
 * <ul>
 *   <li>一条消息【仅包含同一网络】的内容（networkKey 单值）；多个网络的操作
 *       应【分多次发送】——第二条内容可以仅通过网络包实现（设备列表包为空）。</li>
 *   <li>同一条消息必然同时携带网络包与设备列表包（两个字段非 null），但两个
 *       包都可以是空包（NOOP / empty）。</li>
 *   <li><b>设备列表包非空时必须有网络包</b>（即使网络包为 NOOP）：net == null
 *       且设备包非空 → 无效消息，由 {@code NetworkWorld.submit(GridMessage)}
 *       校验并日志打印，消息丢弃。</li>
 * </ul>
 * 纯核心组件（common，零 MC 依赖）。
 */
public record GridMessage(
        Object networkKey,
        NetPackage net,
        DevicePackage devices) {

    /**
     * 网络包：网络操作信息。
     * <p>ops 为空 = 无需操作（NOOP/空包）；data = 网络操作附加数据（可 null）。
     */
    public record NetPackage(List<NetOpKind> ops, Object data) {

        /** 无需操作（NOOP 空包；设备列表包必须"加上网络包"时使用） */
        public static NetPackage noop() {
            return new NetPackage(Collections.emptyList(), null);
        }

        /** 网络直接重建 + 求解（特殊情况调用） */
        public static NetPackage rebuildSolve(Object data) {
            return new NetPackage(List.of(NetOpKind.REBUILD, NetOpKind.SOLVE), data);
        }

        /** 指定操作（空参 = NOOP） */
        public static NetPackage of(NetOpKind... ops) {
            return new NetPackage(ops == null || ops.length == 0
                    ? Collections.emptyList() : List.of(ops), null);
        }

        /** 指定操作 + 附加数据 */
        public static NetPackage of(NetOpKind op, Object data) {
            return new NetPackage(op == null ? Collections.emptyList()
                    : List.of(op), data);
        }

        /** 是否无需操作（NOOP = 空包） */
        public boolean noOp() {
            return ops == null || ops.isEmpty();
        }
    }

    /**
     * 电气设备列表包：增量更新组装器列表（删除/变更/新增）。
     * <p>元素为平台层对象（协议不解释）：neoforge 侧约定
     * {@code DeviceAssemblerUpdate(BlockPos pos, Object assembler)}
     * 或 {@code DeviceInfo}（新增/变更）与 {@code BlockPos}（删除）。
     */
    public record DevicePackage(
            List<Object> addOrUpdate,
            List<Object> remove) {

        public static DevicePackage empty() {
            return new DevicePackage(Collections.emptyList(), Collections.emptyList());
        }

        /** 是否空包（无任何增量） */
        public boolean isEmpty() {
            return (addOrUpdate == null || addOrUpdate.isEmpty())
                    && (remove == null || remove.isEmpty());
        }
    }

    /** 是否仅网络包（设备列表包为空；"第二条消息仅网络包"场景） */
    public boolean networkOnly() {
        return devices == null || devices.isEmpty();
    }

    /** 便捷：仅网络包消息（设备列表包为空；第二条消息场景；net 空 → NOOP） */
    public static GridMessage ofOnlyNet(Object networkKey, NetPackage net) {
        return new GridMessage(networkKey, net == null ? NetPackage.noop() : net,
                DevicePackage.empty());
    }

    /** 便捷：仅设备列表包（网络包自动 NOOP——协议要求设备包必须带网络包） */
    public static GridMessage ofOnlyDevices(Object networkKey, DevicePackage devices) {
        return new GridMessage(networkKey, NetPackage.noop(),
                devices == null ? DevicePackage.empty() : devices);
    }

    @Override
    public String toString() {
        return "GridMessage{key=" + networkKey
                + ", net=" + (net == null ? "null" : net)
                + ", devices=" + (devices == null ? "null" : "size="
                        + (devices.addOrUpdate == null ? 0 : devices.addOrUpdate.size())
                        + "/" + (devices.remove == null ? 0 : devices.remove.size()))
                + "}";
    }
}
