/**
 * ===== BE 完全替换：原版设备电压读取接管（2026-08-13 完整闭环） =====
 *
 * 用户决策"原版内容全部通过转换层转换为本 mod 内容，完全接管后不需要写回
 * 原版节点"——原版设备 BE（灯/加热器/电机/仪表/变压器等）通过
 * OwnedFloatingNode.getVoltage() 读电压（ElectricWire.potentialDifference =
 * node1.getVoltage() - node2.getVoltage()，Gauge.getValue 等）。本 mixin 在
 * 自管模式（转换启用 + 自管图非空）下【接管 getVoltage()】：从自管宿主
 * （TerminalRegistry 端子测试点）返回精确电压，替代原版节点 savedStateValue。
 *
 * ⚠ 2026-08-13 崩溃修复：getVoltage() 声明在【父类 FloatingNode】（javap
 * 确认 OwnedFloatingNode 只有 endpoint 字段 + toString）。@Mixin 目标必须
 * 是声明方法所在的 FloatingNode；逻辑里 this instanceof OwnedFloatingNode
 * 才访问 endpoint 字段（其他 FloatingNode 子类无 endpoint → 不拦截回退原版）。
 *
 * 效果：无需重写 32+ 个设备 BE——所有原版设备自动从自管宿主读电压（与主
 * round 求解结果一致，且不依赖原版节点写回/网络维护）。
 *
 * 门控：PowerGridWireConverter.isEnabled() 且自管图非空。端子未注册/无效 →
 * 回退原版 getVoltage()（安全兜底，转换关闭/未构建时完全原版行为）。
 *
 * ⚠ 线程安全：主线程（方块 tick）读取，TerminalRegistry ConcurrentHashMap；
 * 自管宿主由 roundFromGraph 每 tick 刷新。volatile 端子电压。
 */
package com.hdf.cryptand.neoforge.powergrid.mixin;

import com.hdf.cryptand.neoforge.powergrid.adapter.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.adapter.TerminalRegistry;
import com.hdf.cryptand.circuitsimulation.model.TerminalElement;
import org.patryk3211.powergrid.electricity.sim.node.FloatingNode;
import org.patryk3211.powergrid.electricity.sim.node.OwnedFloatingNode;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = FloatingNode.class, remap = false)
public abstract class OwnedFloatingNodeGetVoltageMixin {

    /**
     * 自管模式接管 getVoltage()：从自管宿主端子测试点返回精确电压。
     * 端子未注册/无效/非 OwnedFloatingNode → 不拦截（回退原版 getStateValue，
     * 安全兜底）。
     */
    @Inject(method = "getVoltage", at = @At("HEAD"), cancellable = true)
    private void cryptand$voltageFromSelfHost(CallbackInfoReturnable<Double> cir) {
        try {
            if (!PowerGridWireConverter.isEnabled()) return;
            if (com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager.get().nodeCount() == 0) return;
            if (!((Object) this instanceof OwnedFloatingNode self)) return; // 非 OwnedFloatingNode → 原版
            if (self.endpoint == null) return;
            if (!(self.endpoint instanceof BlockWireEndpoint bep)) return;
            // 端子 → 自管宿主（TerminalRegistry 端子测试点，roundFromGraph 回填）
            TerminalElement te = TerminalRegistry.get(bep.getPos(), bep.getTerminal());
            if (te == null) return;
            // 无效（网络清零/求解失败/未建模）→ 返回 0（与自管清零语义一致）
            if (!te.valid()) {
                cir.setReturnValue(0.0);
                return;
            }
            // 端子测试点电压（RMS；DC 瞬时值）——引擎求解结果，天然正确
            double v = te.voltage();
            // 微小电压（<1mV 数值泄漏）→ 清零（与原版写回一致）
            if (Math.abs(v) < 1e-3) v = 0.0;
            cir.setReturnValue(v);
        } catch (Throwable ignored) {
            // 回退原版
        }
    }
}
