/**
 * ===== 组装器（Assembler）—— 用户架构（2026-08-13） =====
 *
 * 概念分层：
 *   - 【虚拟元件】= 引擎网表里的所有元件（Element/CompositeElement，纯计算
 *     数据，不依赖 Minecraft）。所有元件都叫虚拟元件。
 *   - 【组装器（Assembler）】= 能和实体（实际模型/MC 方块实体）绑定的类。
 *     组装器把实际模型【组装】成真正的【实际元件】（= 虚拟元件 + 绑定 + 温度
 *     等附属能力）。
 *   - 所有实际模型都通过组装器组建真正的实际元件。
 *
 * 组装器职责：
 *   - {@link #assemble}：把方块组装成虚拟复合元件（无复合 → null，装配方走
 *     {@link #stamp} 基础元件路径）。
 *   - {@link #bind}：建立【虚拟元件 ↔ 实际模型】双向绑定（DeviceBinding/
 *     ModelLink）+ 事件接收（BeEventSink）+ 温度模型/虚拟快照维护。
 *   - {@link #registerParams}：注册可调参数源（值变化 → 重解不重建）。
 *   - {@link #isSource}：有源判断（源组件未加载 → 临时不组装，防无限功率）。
 */

package com.hdf.cryptand.neoforge.powergrid.device;

import com.hdf.cryptand.circuitsimulation.model.Network;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeElement;
import com.hdf.cryptand.circuitsimulation.model.composite.CompositeModel;
import com.hdf.cryptand.neoforge.powergrid.adapter.BeEventSink;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceBinding;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.adapter.PhasorNetworkContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;

import java.util.List;
import java.util.Set;

public interface Assembler {

    /** 是否为【有源设备】（电池/创造源/发电机等）。源组件未加载 → 不组装
     *  （临时不加入运算，防无限功率）。默认无源。 */
    default boolean isSource() { return false; }

    /** 是否为原版直流(DC)设备（灯/风扇/铃/加热器等，2026-08-22）：原版电气
     *  设备只支持 DC——网络频率超过 dcDeviceMaxFrequencyHz 时对该设备温度施加
     *  指数型惩罚（轻微超频不明显，越高越严重）。默认非 DC-only。 */
    default boolean dcOnly() { return false; }

    /**
     * 温度扩散模型（2026-08-18 用户需求）：返回 {@link ThermalDiffusionConfig}；
     * null = 不扩散（默认）。【只有实现温度扩散模型的组装器】才能向周围元件
     * 传递温度——普通设备（电阻/电机等）不扩散，不会被周围高温设备影响。
     * <p>扩散分多种类型（见 {@link ThermalDiffusionType}）：
     *   1. 仅输出（OUTPUT_ONLY）——只向周围传热，不接受外部输入
     *   2. 仅输入（INPUT_ONLY）——只接受外部传热，不向周围输出
     *   3. 双向（BIDIRECTIONAL）——可双向交互（热 → 冷）
     * 且可设置方向（默认全方向）。未接入回路的设备温度只由自身电学发热决定。
     */
    default ThermalDiffusionConfig thermalDiffusion() { return null; }

    /**
     * 声明端子数（2026-08-13 完全接管端子：声明式端子数）。
     * <p>
     * Cryptand 决定每个设备创建几个端子（引擎端子 + 注册表），【不依赖原版
     * buildCircuit / getTerminal 探测】——buildCircuit 延迟/重进世界未完成时，
     * 引擎照样按声明创建端子建模。原版端子节点仅作电压写回目标（有则映射，
     * 无则等重建，不阻塞引擎建模）。
     * <p>
     * 默认 2（两端口设备）。多端子设备覆写：万用表 3、变压器 4（不走本接口，
     * 见 PhasorNetworkBuilder 声明函数）、可编程元件 = 引脚数（同见声明函数）。
     */
    default int terminalCount() { return 2; }

    /**
     * 组装：把方块组装成虚拟【复合】元件（由基础元件组合，可能含温度模型）。
     * 返回非 null 时装配方展开元件并记录到上下文（求解后推进温度）；默认 null
     * （设备只用简单元件走 {@link #stamp}）。
     */
    default CompositeModel assemble(BlockEntity be, int a, int b, Network net) {
        return null;
    }

    /** 装配基础元件（assemble 返回 null 时由装配方调用，供纯基础元件设备） */
    default void stamp(BlockEntity be, int a, int b, Network net) {
    }

    /**
     * 注册可调参数源（碳堆 trim / 变阻器滑片 → setResistance）。paramSources
     * 每轮调用——值变化经元件参数变化消息触发重解（不重建网络）。默认无。
     * 2026-08-15 去 level：签名改 BlockPos（后台构建无 BE），参数刷新读缓存。
     */
    default void registerParams(BlockPos pos, CompositeModel cm,
                                List<PhasorNetworkContext.ParamSource> paramSources) {
    }

    /**
     * 绑定：建立【虚拟元件 ↔ 实际模型】双向绑定（DeviceBinding/ModelLink）+
     * 事件接收（BeEventSink）+ 温度模型/虚拟快照维护（组装器统一提供）。
     * 组装完成后由装配方调用。设备组装器可覆写本方法自定义绑定；默认委托给
     * 通用实现 {@link #bindAll}。
     */
    default void bind(BlockEntity be, CompositeElement ce, Set<ElectricalNetwork> nets) {
        bindAll(be, ce, nets);
    }

    /**
     * 通用绑定实现（static，供直接处理类/无组装器路径调用，如电容/电感/电阻）：
     *   - DeviceBinding 注册 + attach 网络集合 + composite.bindModel（破坏/移除互发消息）
     *   - 虚拟快照由主线程 DeviceParamCache.sync 统一同步（后台不深读 BE）
     */
    static void bindAll(BlockEntity be, CompositeElement ce, Set<ElectricalNetwork> nets) {
        if (be == null) return;
        bindAllPos(be.getBlockPos(), ce, nets);
    }

    /**
     * 后台 pos-only 绑定（2026-08-15 完全异步：不持 BE 深引用，交互走消息/pos
     * 反查——DeviceBinding.ofPos 只记坐标；VirtualDevice 主线程同步；过热爆炸
     * 经 BeEventSink.ofPos 发 EngineBus 消息）。
     */
    static void bindAllPos(BlockPos pos, CompositeElement ce, Set<ElectricalNetwork> nets) {
        if (pos == null || ce == null) return;
        try {
            DeviceBinding db = DeviceBinding.ofPos(pos);
            if (db != null) {
                db.attach(nets, ce);
                ce.bindModel(db);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (DeviceThermalStore.thermalFor(pos).overheated()) {
                BeEventSink sink = BeEventSink.ofPos(pos);
                if (sink != null) sink.onExplode();
            }
        } catch (Throwable ignored) {
        }
    }
}
