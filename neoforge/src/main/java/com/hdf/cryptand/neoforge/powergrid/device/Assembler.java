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
import com.hdf.cryptand.neoforge.powergrid.device.BeEventSink;
import com.hdf.cryptand.neoforge.powergrid.device.DeviceBinding;
import com.hdf.cryptand.neoforge.powergrid.device.thermal.ThermalDiffusionConfig;
import com.hdf.cryptand.neoforge.powergrid.state.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.engine.PhasorNetworkContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.patryk3211.powergrid.electricity.sim.ElectricalNetwork;

import java.util.List;
import java.util.Set;

public interface Assembler extends com.hdf.cryptand.engine.Assembler {

    /** ⚠ 2026-08-30 引擎分层（common 接口）：特征默认普通设备（平台子类覆写：
     *  变压器/接口/代理…） */
    @Override
    default String feature() { return "normal"; }

    /** ⚠ 2026-08-30 引擎绑定接口（common 仅提供 Binding 类——平台实现）：
     *  本平台绑定对象 = MC 设备绑定（DeviceBinding/BE）——真实绑定走
     *  {@link #bind(BlockEntity, CompositeElement, Set)}（MC 交互路径——
     *  需要 BE/网络）；common 抽象 bind(Binding) 提供空实现（引擎侧接口
     *  满足；平台绑定在装配方以 MC 路径调用）。 */
    @Override
    default void bind(com.hdf.cryptand.engine.Binding binding) {
        // 平台绑定实现见 bind(BlockEntity,...)（MC 路径）
    }

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

    /* ==================== 持久化信息（2026-09-15 用户）====================
     * 用户："保存时需要组装器记住绑定的数据的相关信息，可以给绑定接口添加信息接口，
     * 用于保存时返回必要信息。" / "数值可以是通用变量，K 为保存字符串，V 为通用变量。"
     *
     * 分工（谁最清楚谁负责）：
     *   - 组装器是**信息的真正提供者** —— 它建了这个设备的所有模型，也就最清楚
     *     "重建它需要什么"（节点 id、规格、运行状态……）；
     *   - {@link com.hdf.cryptand.circuitsimulation.model.ModelLink#persistInfo()}
     *     是**通道** —— MC 侧的 DeviceBinding 实现它，把调用转给组装器；
     *   - 编解码器只按【名称(K: String) → 值(V: 通用)】逐条做 KV 搬运，不看内容。
     *
     * 默认返回 null = 无可持久化信息（纯被动元件：导线/电阻/电容/电感走各自
     * 专用编码，不需要这条通道）。
     */

    /**
     * 保存：返回重建本设备所需的信息（KV）。
     * <ul>
     *   <li>至少应当包含重建模型的【节点 id】（如 {@code a}/{@code b}/{@code x}）——
     *       恢复时新的 Network 节点数量与顺序由解码器按存档重建，id 仍然有效；</li>
     *   <li>运行状态（转速/应力/储能……）按需包含；纯规格参数可由恢复时从
     *       DeviceParamCache 重新读取，不必重复保存。</li>
     * </ul>
     */
    default java.util.Map<String, Object> persistInfo(BlockPos pos, CompositeElement ce) {
        return null;
    }

    /**
     * 恢复：用保存时取走的 KV 重建复合元件（{@link #persistInfo} 的对称操作）。
     * 返回 null = 本组装器不负责该模型（解码器跳过该元件，其余电路照常恢复）。
     *
     * @param pos          设备坐标（由 compositeKey 解析而来："M"+pos 等）
     * @param compositeKey 原身份锚点（类型码 + 坐标）
     * @param className    原复合模型的类名（诊断/精确匹配）
     * @param expanded     展开元件（R/L/C/源，节点 id 指向正在恢复的 Network）
     * @param info         保存的 KV（名称 → 值）
     */
    default CompositeElement restoreFromInfo(BlockPos pos, String compositeKey,
                                             String className,
                                             com.hdf.cryptand.circuitsimulation.model
                                                     .Element[] expanded,
                                             java.util.Map<String, Object> info) {
        return null;
    }

    /**
     * 引擎消息 → 更新本组装器持有的信息（2026-09-15 用户："主线程发送消息发向网络，
     * 然后每次网络计算时不是有消息处理吗，处理时先更新到对应组装器即可"）。
     * <p>
     * 这是【间接交互】的落点：主线程侧的 BE 只发消息（pos + 纯数据），不直接改引擎
     * 对象；引擎侧在每轮消息处理阶段把消息先落到【对应设备的组装器】上，组装器更新
     * 自己持有的信息（参数/开关/目标值……），随后求解阶段只读组装器内的信息。
     * <p>
     * 默认空实现（无状态设备不需要）。
     *
     * @param pos     设备坐标（消息身份）
     * @param message 引擎消息（common，纯数据）
     */
    default void onMessage(BlockPos pos,
                           com.hdf.cryptand.engine.EngineMessage message) {
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
