/**
 * Accessor：读取 ElectricalNetwork 的 protected 字段
 *   - outerHooks（万用表频率显示）
 *   - wires（网络内导线集合 —— 相量构建合并用；mixin 字段访问在 Java 21 强封装下
 *     比 setAccessible 反射可靠）
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.customcore;

import org.patryk3211.powergrid.electricity.sim.AbstractElectricWire;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;
import org.patryk3211.powergrid.electricity.sim.solver.IOuterHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Set;

@Mixin(value = ElectricalNetwork.class, remap = false)
public interface ElectricalNetworkAccessor {

    @Accessor("outerHooks")
    Set<IOuterHook> cryptand$outerHooks();

    @Accessor("wires")
    Set<AbstractElectricWire> cryptand$wires();

    /** dirty 标志写访问：Cryptand 禁用 prepare（清 dirty 唯一路径）后，任何
     *  addNode/addWire 置位的 dirty 永不清除 → ElectricalNetwork.setValue 第 0 行
     *  `if (dirty) return` 静默失败 → 节点电压不回写 → 悬空端 0V → 烧线。
     *  PhasorWriteback 回写前必须清 dirty 恢复 setValue 生效。 */
    @Accessor("dirty")
    void cryptand$setDirty(boolean dirty);
}
