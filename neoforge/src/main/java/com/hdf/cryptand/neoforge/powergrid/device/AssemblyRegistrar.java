package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.circuitsimulation.model.composite.ThermalDevice;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import com.hdf.cryptand.engine.Binding;
import com.hdf.cryptand.neoforge.powergrid.device.Assembler;
import com.hdf.cryptand.neoforge.powergrid.element.ElementMapContext;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceParamCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Map;
import java.util.function.Consumer;

/**
 * ===== 装配登记组合件 =====
 *
 * <p>元件映射器（{@link ElementMapper} 子类）装配完设备后，要向引擎上下文登记
 * 四类"侧表"：温度设备、储能设备、组装器、绑定器，外加一次"实际模型 ↔ 复合
 * 元件"的绑定。这些动作原先在每个映射器里各写一遍（`deviceThermals.put` /
 * `energyDevices.put` / `Assembler.bindAll` / `bindings.put` …），既重复又让
 * "登记规则"散落在六处。
 *
 * <p>本类用**组合**替代重复：映射器持有本件（{@link ElementMapContext#reg()}），
 * 以「登记意图」调用——`reg.thermal(be, model)` 而不是「往第几张表里 put 什么」。
 * 登记规则（空表守卫、异常吞噬、kind 过滤）集中在这里，改一次处处生效。
 *
 * <p>线程：只在构建线程（调度线程）使用，各表本身是并发容器。
 */
public final class AssemblyRegistrar {

    private final ElementMapContext c;

    public AssemblyRegistrar(ElementMapContext c) {
        this.c = c;
    }

    /** 温度设备登记：求解后由 round 统一推进温度（I²R / 铜损）。 */
    public void thermal(BlockEntity be, ThermalDevice td) {
        if (be == null || td == null) return;
        c.deviceThermals.put(be.getBlockPos(), td);
    }

    /** 储能设备登记：求解后统一同步储能状态（电荷 / 磁链 / 机电能量）。 */
    public void energy(BlockEntity be, EnergyDevice ed) {
        if (be == null || ed == null) return;
        c.energyDevices.put(be.getBlockPos(), ed);
    }

    /**
     * 组装器登记（用户 2026-08-30：取消全局表——组装器按网络存储）。
     * 处理（温度/能量/策略）按本网络的这张表遍历。
     */
    public void assembler(BlockEntity be, Assembler model) {
        if (be == null || model == null || c.assemblers == null) return;
        try {
            c.assemblers.put(be.getBlockPos(), model);
        } catch (Throwable ignored) {
        }
    }

    /** 绑定器登记：引擎消息（求解完成）→ 平台侧处理。 */
    public void binding(BlockEntity be) {
        if (be == null || c.bindings == null) return;
        try {
            DeviceBinding db = DeviceBinding.forPos(be.getBlockPos());
            if (db != null) c.bindings.put(be.getBlockPos(), db);
        } catch (Throwable ignored) {
        }
    }

    /** 组装器统一绑定：实际模型 ↔ 复合元件（破坏感知 / 移除通知）。 */
    public void bindAll(BlockEntity be, CompositeModel cm) {
        if (be == null || cm == null) return;
        Assembler.bindAll(be, cm, c.pgNets);
    }

    /** 组装器自定义绑定（设备组装器可覆写 bind）。 */
    public void bindDevice(Assembler model, BlockEntity be, CompositeModel cm) {
        if (model == null || be == null || cm == null) return;
        model.bind(be, cm, c.pgNets);
    }

    /**
     * 参数刷新源：值变化消息到达时读缓存快照 → 命中该 kind 才应用。
     * 把「null 检查 + kind 匹配」的统一模式收在一处，调用方只写"怎么应用"。
     */
    public void paramSource(BlockPos pos, DeviceParamCache.Kind kind,
                     Consumer<DeviceParamCache.Entry> apply) {
        if (pos == null || kind == null || apply == null) return;
        c.paramSources.add(() -> {
            DeviceParamCache.Entry e = DeviceParamCache.get(pos);
            if (e != null && e.kind == kind) apply.accept(e);
        });
    }

    /** 已登记的组装器表（仅 AssemblerDeviceMapper 的占位判断用）。 */
    Map<BlockPos, Assembler> assemblers() {
        return c.assemblers;
    }

    /** 已登记的绑定表（供需要判断"是否需要登记"的调用方）。 */
    Map<BlockPos, Binding> bindings() {
        return c.bindings;
    }
}
