package com.hdf.cryptand.neoforge.powergrid.device;

/**
 * 电气设备增量条目（2026-08-23 用户协议：主线程 → 核心消息的【设备列表包】元素）。
 * <p>
 * 表示【新增/变更】的电气设备与其组装器：位置 + 组装器引用（可 null = 仅占位，
 * 由执行器解释——如预注册组装器缓存 {@code cacheFor}）。删除走
 * {@link net.minecraft.core.BlockPos} 元素（{@code DevicePackage.remove}）。
 * <p>
 * 纯数据（主线程构造，异步执行器只读解释），不持有 BE/Level 引用。
 */
public record DeviceAssemblerUpdate(net.minecraft.core.BlockPos pos, Object assembler) {

    /** 位置（诊断/协议日志用） */
    public net.minecraft.core.BlockPos position() {
        return pos;
    }

    @Override
    public String toString() {
        return "DeviceAssemblerUpdate{" + pos + ", asm="
                + (assembler == null ? "null" : assembler.getClass().getSimpleName())
                + "}";
    }
}
