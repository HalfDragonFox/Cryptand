package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.circuitsimulation.model.ElementEventSink;
import com.hdf.cryptand.neoforge.powergrid.engine.EngineBus;
import com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.base.ElectricBlockEntity;

/**
 * 实际模型（BE）事件接收器（2026-08-12 用户要求）：
 * <p>
 * 把引擎元件事件（过热/爆炸）转发到 MC 侧实际模型。
 * 实际模型（ElectricBlockEntity）只需在构建时把本接收器绑定到复合元件
 * （{@code composite.bindEvents(BeEventSink.of(be))}），引擎检测到过热时回调
 * 本接收器 → 加入销毁队列（2026-08-13 用户架构点 5：异步销毁 + 网络重建 +
 * 模型未加载等加载后销毁）。完全解耦：引擎不知道 BE 细节，BE 侧只接收事件。
 */
public final class BeEventSink implements ElementEventSink {

    private final BlockEntity be;
    private final BlockPos pos;

    private BeEventSink(BlockEntity be, BlockPos pos) {
        this.be = be;
        this.pos = pos;
    }

    /** 构造接收器；非电气设备（或 null）返回 null（不绑定） */
    public static BeEventSink of(BlockEntity be) {
        return (be instanceof ElectricBlockEntity) ? new BeEventSink(be, be.getBlockPos()) : null;
    }

    /** 后台 pos-only 接收器（2026-08-15 完全异步：不持 BE，爆炸经 pos 反查） */
    public static BeEventSink ofPos(BlockPos pos) {
        return pos == null ? null : new BeEventSink(null, pos);
    }

    @Override public void onOverheated() { explode(); }
    @Override public void onExplode() { explode(); }

    /** 销毁：发送销毁消息（2026-08-15 完全异步）——不再在此同步访问 level/
     *  getBlockEntity（可能被后台线程调用：advanceState 后台推进温度 → 过热），
     *  只发消息（携带 pos + BE 引用 + 组装器经绑定反查）→ 主线程 EngineBus.process
     *  统一执行破坏（世界副作用只能主线程）。
     *  ⚠ 2026-08-21 爆炸模式配置：0 = 关闭爆炸和破坏（过热无后果，不请求销毁）。 */
    private void explode() {
        try {
            // 2026-08-21 用户要求：元件爆炸可配置（0=关闭爆炸和破坏）
            int mode = ConfigCircuit.EXPLOSION_MODE.get();
            if (mode <= 0) return;
            // 创造设备（创造电阻/创造源）过热不销毁不爆炸——玩家测试用无限设备
            if (be != null) {
                String cn = be.getClass().getName();
                if (cn.contains("CreativeResistor") || cn.contains("CreativeSource")
                        || cn.contains("AcCreativeSource")) {
                    return;
                }
            }
            BlockPos p = pos != null ? pos : (be != null ? be.getBlockPos() : null);
            if (p == null) return;
            // 2026-09-15 去引用：消息只带 pos + 纯数据，BE 由主线程消费时按 pos 取
            EngineBus.post(EngineBus.Type.DESTROY_DEVICE, p, "overheat");
        } catch (Throwable ignored) {
        }
    }
}
