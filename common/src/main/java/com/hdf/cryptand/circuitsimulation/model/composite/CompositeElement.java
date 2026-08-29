package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementBinding;
import com.hdf.cryptand.circuitsimulation.model.ElementEventSink;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.ModelLink;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyState;
import com.hdf.cryptand.circuitsimulation.model.state.StateDriven;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;
import com.hdf.cryptand.circuitsimulation.solver.SolveMode;

/**
 * 复合元件基类（统一生命周期，2026-08-12 用户要求）。
 * <p>
 * 所有"由基础元件组合而成"的电路对象都继承本类：
 *   - 设备复合模型（电机/仪表/开关/变压器…）：{@link CompositeModel}
 *   - 导线连续段（电阻 + 温度模型）：{@link WireComposite}
 * <p>
 * 统一三个方法：
 *   - {@link #reset()}     —— 重置（网络重建/复位时：温度归环境、内部状态清零）
 *   - {@link #lossPower}   —— 计算（从端口相量电压算损耗功率 W）
 *   - {@link #update()}    —— 更新（求解完成后：算损耗 → 推进温度，散热与发热
 *                              同时算，解析解无条件稳定）
 * <p>
 * 网络收集所有复合元件（{@link com.hdf.cryptand.circuitsimulation.model.Network#composites()}），
 * 求解时 {@link #decompose()} 拆成最原始的基础元件进 MNA，求解完成后对每个
 * 复合元件调 {@link #update()} —— 外部只需持有基类引用即可统一操作。
 * <p>
 * 2026-08-15：实现 {@link EnergyState} 统一能量状态入口（固定节拍伪时域推进）
 * —— {@link #advanceState} 在固定节拍下推进温度/状态并计数版本；
 * {@link #isNonlinear()} 决定该网络是否进入固定节拍伪时域求解。
 */
public abstract class CompositeElement implements EnergyState {

    /** 组合的基础元件（节点已确定，可直接装配；decompose 展开） */
    protected final Element[] simple;

    /** 温度模型（可 null：不参与温度模拟） */
    private ThermalModel thermal;

    /**
     * 基础状态模型数组（2026-08-20 用户要求：多模型复合 = 分解为多个基础
     * 模型【分别调用】，无需注册表）。
     * <p>
     * 复合元件持有多个基础模型字段（温度/转速动力学/应力/自定义），构造时
     * 传入本数组；{@link #advanceState} 把它们【强转 StateDriven】逐个调用
     * ——基础模型统一实现 {@link StateDriven}（DynamicsModel/ThermalModel/
     * EnergyModel 都已实现），运算接口名统一为 advanceState。
     */
    private StateDriven[] baseModels = new StateDriven[0];

    /** 设置基础状态模型数组（构造时调用；可多次累加）。 */
    protected void setBaseModels(StateDriven... models) {
        if (models == null || models.length == 0) return;
        StateDriven[] merged = java.util.Arrays.copyOf(baseModels,
                baseModels.length + models.length);
        int off = baseModels.length;
        for (StateDriven m : models) if (m != null) merged[off++] = m;
        baseModels = java.util.Arrays.copyOf(merged, off);
    }

    /** 附加一个基础状态模型（公开 API，2026-08-20：外部装配时可注入，
     *  与构造时 setBaseModels 等价——强转 StateDriven 统一调用）。 */
    public void attachStateModel(StateDriven model) {
        setBaseModels(model);
    }

    /** 基础状态模型数组（只读视图）。 */
    public StateDriven[] baseModels() { return baseModels; }

    /**
     * 求解后节点电压数组（可选注入，2026-08-18 用户要求）：求解完成后由
     * {@code PhasorEngine.advancePseudoTime} 注入整个节点电压数组——子类
     * （MotorModel 等 R-L 绕组）用【内部节点电压】算【流过内部电阻的真实支路
     * 电流】（I = |V_a − V_x| / R），替代端口电压差/总阻抗估算。null = 未注入
     * （lossPower 走原估算路径兜底）。
     */
    protected Complex[] nodeVoltages;

    /** 注入求解后节点电压数组（advancePseudoTime 每轮求解后调用） */
    public void setNodeVoltages(Complex[] nv) { this.nodeVoltages = nv; }

    /** 内部节点电压（nodeVoltages 未注入/越界 → null） */
    public Complex nodeVoltage(int n) {
        if (nodeVoltages == null || n < 0 || n >= nodeVoltages.length) return null;
        return nodeVoltages[n];
    }

    /**
     * 统一电流法发热辅助（2026-08-18 用户要求：所有发热用【流过内部电阻的
     * 电流】而非压差）。返回平均损耗功率 I²·R/2：
     *  - 内部节点电压已注入（nodeVoltages）且电阻两端 ra/rb 有效 → 精确支路
     *    电流 I = |V_ra − V_rb| / r（R-L 串联/带 EMF 时 V_rb 已含电感/反电动
     *    势压降，天然正确，无需再减 EMF）
     *  - 未注入 → 用端口电压差 / 该支路阻抗 seriesZ 估算（串联 R-L 兜底）
     */
    protected double resistorLoss(Complex va, Complex vb, double omega,
                                  double r, int ra, int rb, double seriesZ) {
        if (r <= 0) return 0;
        double iPeak;
        Complex vRa = nodeVoltage(ra);
        Complex vRb = nodeVoltage(rb);
        if (vRa != null && vRb != null) {
            iPeak = vRa.sub(vRb).abs() / r;
        } else if (seriesZ > 1e-12) {
            iPeak = va.sub(vb).abs() / seriesZ;
        } else {
            return 0;
        }
        return iPeak * iPeak * r / 2.0;
    }

    /** 能量状态版本（2026-08-15：advanceState 每推进 +1，收敛/缓存判断用） */
    private long stateVersion;

    /** 去重 key（同物理设备在多网络 ctx 出现时只更新一次；null = 每 ctx 都更新） */
    private String compositeKey;

    /**
     * 参数绑定源（可 null，2026-08-12）：独立参数数据对象——计算时全局可调整，
     * 无论实际 MC 模型/BE 是否更新。虚拟设备（未加载区块参数快照）绑定后，
     * 外部调整绑定源参数 → {@link #applyBinding()} 在下一轮 {@link #update}
     * 时应用新参数 → 重解，无需重建网络。
     */
    private ElementBinding binding;

    /**
     * 事件接收器（可 null，2026-08-12 用户要求）：实际模型（MC 方块实体）
     * 实现 {@link ElementEventSink} 接收过热/爆炸事件——完全解耦，引擎不知道
     * BE 细节，BE 侧只接收事件（爆炸破坏方块等由实际模型处理）。
     */
    private ElementEventSink eventSink;

    /**
     * 双向模型绑定（可 null，2026-08-13 用户要求）：实际模型（MC 方块实体）
     * 实现 {@link ModelLink} 与复合元件互持引用、互发消息：
     *   - 实际模型破坏 → {@link #onModelDestroyed()} → adapter 定位网络请求重建
     *   - 元件被移除出网络 → {@link #notifyRemoved()} → 实际模型清理
     * 与 {@link #eventSink}（引擎→BE 单向事件）互补，形成完整双向通道。
     */
    private ModelLink modelLink;

    /** 过热通知一次性标记（温度回落后重置，避免每 tick 重复通知） */
    private boolean overheatNotified;

    protected CompositeElement(Element[] simple) {
        this(simple, null);
    }

    protected CompositeElement(Element[] simple, ThermalModel thermal) {
        this.simple = simple == null ? new Element[0] : simple;
        this.thermal = thermal;
        // 温度也是基础状态模型（2026-08-20 多模型统一）：自动注册进
        // baseModels，advanceState 统一强转调用（损耗→温度推进+过热事件）
        if (thermal != null) {
            setBaseModels(new StateDriven() {
                @Override
                public boolean advanceState(Complex va, Complex vb,
                                            double freqHz, double dt, SolveMode mode) {
                    if (thermal == null || dt <= 0) return false;
                    double omega = 2 * Math.PI * Math.max(freqHz, 0);
                    thermal.update(lossPower(va, vb, omega), dt);
                    if (thermal.overheated()) {
                        notifyOverheated();
                    } else {
                        overheatNotified = false;
                    }
                    return false;
                }
            });
        }
    }

    /** 展开为最原始的基础元件数组（网络装配/序列化/求解用） */
    public Element[] decompose() { return simple; }

    /** 基础元件数量 */
    public int elementCount() { return simple.length; }

    /** 温度模型（无温度模拟返回 null） */
    public ThermalModel thermal() { return thermal; }

    /** 设置/替换温度模型（外部装配时可注入） */
    protected void setThermal(ThermalModel t) { this.thermal = t; }

    /** 去重 key：同一物理设备在多网络 ctx 出现时只更新一次 */
    public String compositeKey() { return compositeKey; }

    /** 设置去重 key（如设备 BlockPos / 导线段路径签名） */
    public void setCompositeKey(String key) { this.compositeKey = key; }

    // ===== 参数绑定数据（2026-08-12 用户要求：计算时全局可调） =====

    /** 绑定参数数据源（可 null） */
    public ElementBinding binding() { return binding; }

    /** 绑定参数数据源（虚拟设备/外部参数对象） */
    public void bind(ElementBinding b) { this.binding = b; }

    /**
     * 应用绑定参数变动：绑定源标记变动（{@link ElementBinding#bindingDirty()}）
     * → 清标记 + 回调 {@link #onBindingChanged} 让子类把新参数应用到基础元件
     * （setResistance 等 → 自动发参数变化消息 → 重解）。在 {@link #update}
     * 开头调用——下一轮求解生效（<50ms，可接受）。
     */
    public void applyBinding() {
        ElementBinding b = binding;
        if (b != null && b.bindingDirty()) {
            b.bindingClearDirty();
            onBindingChanged(b);
        }
    }

    /** 绑定参数变动回调（子类覆写：setResistance 等应用到基础元件） */
    protected void onBindingChanged(ElementBinding b) {}

    // ===== 事件接收（2026-08-12 用户要求：实际模型接收爆炸，完全解耦） =====

    /** 事件接收器（实际模型实现；可 null） */
    public ElementEventSink eventSink() { return eventSink; }

    /** 绑定事件接收器（实际模型：如 {@code BeEventSink.of(be)}） */
    public void bindEvents(ElementEventSink sink) { this.eventSink = sink; }

    // ===== 双向模型绑定（2026-08-13 用户要求：实际模型 ↔ 复合元件） =====

    /** 双向模型绑定（实际模型实现；可 null） */
    public ModelLink modelLink() { return modelLink; }

    /** 绑定实际模型（双向：实际模型破坏/元件移除互发消息） */
    public void bindModel(ModelLink link) { this.modelLink = link; }

    /**
     * 实际模型被破坏 → 通知绑定的实际模型侧（adapter 据此定位网络请求重建，
     * 重建后该方块不存在 → 本元件自然不再建模 = 删除此元件）。
     */
    public void onModelDestroyed() {
        if (modelLink != null) modelLink.notifyModelDestroyed();
    }

    /**
     * 元件被移除出网络（设备被破坏/网络重建后本元件不再存在）→ 通知实际模型
     * 清理（温度模型/虚拟快照/爆炸效果）。由 adapter 在 ctx 重建/元件生命周期
     * 结束时调用。
     */
    public void notifyRemoved() {
        if (modelLink != null) modelLink.notifyCompositeRemoved();
    }

    /**
     * 过热事件通知：温度首次超限 → 调事件接收器 {@link ElementEventSink#onOverheated()}
     * （实际模型自行决定爆炸时机）。温度回落后标记重置（可再次通知）。
     */
    protected void notifyOverheated() {
        if (!overheatNotified) {
            overheatNotified = true;
            if (eventSink != null) eventSink.onOverheated();
        }
    }

    /**
     * 重置（网络重建/复位时调用）：温度归环境 + 内部状态清零 + 通知实际模型重置。
     * 子类可覆写清理自己的历史状态（电容电压/电感电流等）。
     */
    public void reset() {
        if (thermal != null) thermal.reset();
        overheatNotified = false;
        if (eventSink != null) eventSink.onReset();
    }

    /** 损耗端口（统一 update 用）；无单端口对（如 4 端口变压器）返回 -1 */
    public int nodeA() { return -1; }
    public int nodeB() { return -1; }

    /** 计算：从端口相量电压算【平均】损耗功率（W）。默认 0（无损耗/未覆写） */
    public double lossPower(Complex va, Complex vb, double omega) { return 0; }

    /**
     * 更新：求解完成后调用——先应用绑定参数变动（全局可调），再算损耗 → 推进
     * 温度（散热与发热同时算，解析解）；温度超限 → 通知实际模型（爆炸由实际
     * 模型处理，完全解耦）。无温度模型时仅应用参数（不浪费性能）。
     */
    public void update(Complex va, Complex vb, double omega, long nowNanos) {
        applyBinding();
        if (thermal != null) {
            thermal.advance(lossPower(va, vb, omega), nowNanos);
            if (thermal.overheated()) {
                notifyOverheated();
            } else {
                overheatNotified = false; // 温度回落 → 可再次通知
            }
        }
    }

    /**
     * 伪时域推进（2026-08-15，EnergyState 统一入口）：状态版本 +1；应用绑定
     * 参数；【逐个调用全部基础状态模型】{@link #baseModels}——复合元件把多
     * 个基础模型分解为 StateDriven 数组，这里强转统一调用（2026-08-20 多
     * 模型复合：无需注册表，运算接口统一为 advanceState，带求解器类型）。
     * 返回任意子推进报告"电学参数变化"（触发下轮重解）。
     */
    @Override
    public boolean advanceState(Complex va, Complex vb, double freqHz, double dt, SolveMode mode) {
        stateVersion++;
        applyBinding();
        boolean changed = false;
        for (StateDriven model : baseModels) {
            try {
                if (model.advanceState(va, vb, freqHz, dt, mode)) changed = true;
            } catch (Throwable ignored) {
                // 单模型推进失败不炸整个元件
            }
        }
        return changed;
    }

    @Override
    public long stateVersion() { return stateVersion; }

    @Override
    public double storedEnergy() { return 0; } // 储能元件覆写

    /** 是否有任何基础状态模型仍在变化（未到稳态，2026-08-20 稳态跳过）。
     *  无状态模型 → false（纯静态元件，天然稳态）。 */
    public boolean stateChanging() {
        for (StateDriven model : baseModels) {
            try {
                if (model instanceof com.hdf.cryptand.circuitsimulation.model.dynamics.DynamicsModel dm) {
                    if (!dm.isSteady()) return true;
                } else if (model instanceof EnergyState es) {
                    // 泛化兜底：EnergyState 实现多会自跟踪；这里只对已知类判定
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * 是否非线性（2026-08-15）：有热模型（状态随温度漂移）→ 非线性，该网络
     * 进入固定节拍伪时域；子类可覆写（带能量储能 → 恒 true；纯无源电阻组合
     * → false）。
     */
    public boolean isNonlinear() {
        return thermal != null;
    }

    /**
     * 复合元件类型（诊断用；默认 {@link ElementType#COMPOSITE}——求解/序列化
     * 只依赖 decompose 后的基础元件，不依赖此类型。子类可按需覆写，但【不要】
     * 为每种复合元件新增枚举值（种类多了枚举爆炸）。具体物理种类由类名/toString
     * 标识。
     */
    public ElementType type() { return ElementType.COMPOSITE; }

    /**
     * 是否为【有源复合元件】（电压源/电流源/发电机等）。2026-08-13 用户架构
     * 要求：基本接口提供基础判断函数，源复合元件通过继承重写返回 true——用于
     * 虚拟/EDA/拓扑判断（源组件绑定的模型未加载 → 临时不加入运算）。默认无源。
     */
    public boolean isSource() { return false; }
}
