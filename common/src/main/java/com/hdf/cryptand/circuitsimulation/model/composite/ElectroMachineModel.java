package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementBinding;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.elements.AcVoltageSource;
import com.hdf.cryptand.circuitsimulation.model.elements.Inductor;
import com.hdf.cryptand.circuitsimulation.model.dynamics.DynamicsModel;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyDevice;
import com.hdf.cryptand.circuitsimulation.model.energy.EnergyModel;
import com.hdf.cryptand.circuitsimulation.model.state.StateDriven;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.Complex;

/**
 * 机电复合模型（2026-08-12 最新架构适配：原版电动机/发电机）。
 * <p>
 * 组合（基础元件，串联链，可逆电机统一模型）：
 *   a --AcVoltageSource(EMF E, 内阻 R)-- x --Inductor(L)-- b
 *   （电动机/发电机同构：源幅值 E 都为正，a 端为+。端口方程 V_ab = E + I·Z）
 *   - 电动机：外部电源 V_ab 高 → I>0（电→机械）；转速升 → E=K·ω 升 → 空载
 *     电流 I=(V−E)/Z 降 → 导线不烧
 *   - 发电机：外力使 ω 高 → E>V_ab → I<0（机械→电）
 * <p>
 * 集成最新统一架构（多模型复合，2026-08-20）：
 *   - {@link ThermalModel}（温度）→ 基类内置（过热事件语义）
 *   - {@link EnergyModel}（电荷）→ {@link EnergyDevice}，StateNode 额外同步
 *   - 机械转速动力学（{@link DynamicsModel}）→ 注册为 {@link StateDriven}
 *     状态模型（{@link #addStateModel}），由基类 {@code advanceState}
 *     统一遍历推进——新增模型无需覆写 advanceState 手动组合。
 *   - {@link ElementBinding}（绑定数据）+ {@link ElementEventSink}（事件）
 * <p>
 * 参数为固定量（魔法数字）：R/L 由方块线圈读取，EMF 由转速×磁场（adapter
 * 层刷新，setEmf → 参数变化消息 → 重解不重建）。
 */
public class ElectroMachineModel extends CompositeModel implements EnergyDevice {

    /** 两端口 + R-L 串联内部节点（外部分配） */
    public final int a, b, x;
    /** 绕组铜阻（Ω）、电感（H）——固定参数 */
    public final double resistance, inductance;
    /** 温度模型（可 null） */
    public final ThermalModel thermal;
    /** 能量模型（电能↔机械能状态） */
    public final EnergyModel energy;
    /** 是否发电机模式（EMF 源 + 内阻） */
    public final boolean generatorMode;
    /** 发电机 EMF 源（仅 generatorMode 非 null） */
    private final AcVoltageSource emfSource;

    // ===== 涌流（Inrush）经验模型（2026-08-23 用户：不兜底，启动大电流=正常
    // 几倍，按现实经验公式） =====
    // 现实经验（DOL 直接启动）：堵转/启动电流 ≈ 满载电流的 5~8 倍（小电机取大、
    // 大电机取小）。物理根源：堵转感抗 ≈ 漏抗（远小于运行主磁通电抗）。等效到
    // 本模型：内部绕组电感按转速缩放——
    //   L_eff(ω) = L · [1/k + (1 − 1/k) · min(1, |ω|/ω_nominal)]
    // 启动（ω≈0）→ L_eff = L/k → 堵转电流 ≈ k×；转速爬升 → 感抗恢复全值 + EMF
    // 上升（K·ω）→ 电流回落到正常（EMF 主导, I=(V−E)/Z ≈ 额定）——不持续烧线。
    // L=0（无读数）→ 纯阻：启动电流=V/R 由电路自然决定（EMF 上升后电流降，
    // 比例同样由经验公式保证）。
    /** 涌流倍数 k（经验 5~8，默认 6 ≈ NEMA Design B；可经设备/配置调整） */
    public volatile double inrushK = 6.0;
    /** 额定角速度（rad/s；涌流模型归一化用，经验 157 ≈ 1500rpm） */
    public volatile double ratedOmega = 157.0;
    /** 内部绕组电感引用（L>0 时；涌流动态调值 → 参数变化消息 → 重解不重建） */
    private Inductor inductorRef;

    /** 设置涌流倍数（须 >1，否则忽略） */
    public void setInrushK(double k) { if (k > 1) inrushK = k; }

    /** 设置额定角速度（rad/s） */
    public void setRatedOmega(double w) { if (w > 0) ratedOmega = w; }

    // ===== 电机规格参数（2026-08-23 用户：普通/恒速/伺服） =====
    //   - 普通电机：额定 230V → 最大转速 256RPM（空载 EMF≈V：E=K·ω，K=额定电压/
    //     额定角速度）；最大应力 16384；>258V 爆炸
    //   - 恒速电机：电压越高总功率越高（应力×设置转速）；最大应力 16384；>258V 爆炸
    //   - 伺服电机：类似恒速；45V 最大电压；最大应力 1024
    /** 额定电压（V）：230 / 45 */
    public volatile double ratedVoltage = 230.0;
    /** 最大电压（V，超限爆炸）：258 / 45 */
    public volatile double maxVoltage = 258.0;
    /** 最大应力（Create SU，饱和上限）：16384 / 1024 */
    public volatile double maxStress = 16384.0;
    /** 额定转速（rad/s；普通 256RPM→26.81；恒速/伺服=设置转速） */
    public volatile double ratedRadS = 256.0 * 2.0 * Math.PI / 60.0;
    /** 恒速模式（转速=用户设置，功率=应力×设置转速；普通=EMF 电压闭环） */
    public volatile boolean constantSpeed;
    /** 设置转速（RPM；恒速/伺服由 BE 提供） */
    public volatile double setSpeedRPM = 256.0;
    /** 启动转速值（rad/s，2026-08-27 用户启动参数；有电流转速 0 → 强制起步
     *  防乘 0 死锁；默认 1 rad/s；可调小/大控制启动冲量） */
    public volatile double startupRadS = 1.0;
    /** 加速时间常数 τ（s，2026-08-27 用户：启动参数值调节电机加速；默认 1.5s；
     *  小=快、大=慢——调控转速惯性爬升速度） */
    public volatile double accelTauS = 1.5;

    /** 设置启动参数（2026-08-27 用户："通过启动参数值调节电机加速"）。
     *  - startupRadS：有电流转速为 0 时强制的起步转速（rad/s；默认 1）
     *  - accelTauS：加速时间常数（s；τ 小=加速快，τ 大=加速慢/惯性大） */
    public void setStartupParams(double startupRadSVal, double accelTauSVal) {
        if (startupRadSVal > 0) startupRadS = startupRadSVal;
        if (accelTauSVal > 0) accelTauS = accelTauSVal;
    }

    /**
     * 应用电机规格（组装器/主线程缓存驱动，后台纯数据）：
     *   - 普通：K_EMF = 额定电压/额定角速度（230V→256RPM 空载满速；空载平衡
     *     电流由摩擦项 b·ω/K_T 保证 >0 → 供电检测持续，不“掉电”）
     *   - 恒速/伺服：K_EMF = 0（无 EMF 抵消）——用户规格“电压越高总功率越高
     *     （应力×设置转速）”：I=V/Z 随电压，应力=K_T·I，功率=应力×设置转速。
     *     若 K 取额定/额定转速 → 满速 EMF=230V≈电源 → I≈0 → 应力=0、掉电 →
     *     转速/应力清零（“接入后过一会没转速和应力”根因）
     *   - 涌流归一化转速 = 额定转速
     */
    public void setSpecs(double ratedVoltageV, double maxVoltageV, double maxStressSU,
                         double ratedRPM, boolean isConstantSpeed, double speedRPMVal) {
        if (ratedVoltageV > 0) ratedVoltage = ratedVoltageV;
        if (maxVoltageV > 0) maxVoltage = maxVoltageV;
        if (maxStressSU > 0) maxStress = maxStressSU;
        if (ratedRPM > 0) {
            ratedRadS = ratedRPM * 2.0 * Math.PI / 60.0;
            ratedOmega = ratedRadS; // 涌流归一化（启动→额定匹配）
        }
        constantSpeed = isConstantSpeed;
        if (speedRPMVal > 0) setSpeedRPM = speedRPMVal;
        // EMF 常数：
        //   - 普通电机：K = 额定电压/额定角速度 → 空载 EMF≈V（230V→256RPM）
        //   - 恒速/伺服：K = 额定电压×(1−1/k)/额定角速度（k=涌流倍数）→ EMF =
        //     (1−1/k)·V_rated = 0.833·V_rated（k=6 经验）→ 额定电压下正常电流 =
        //     (V−E)/Z = V/(k·Z) = 【启动的 1/k】（用户经验公式：启动=正常×k；
        //     且正常电流>0 不“掉电”；电压↑→电流↑→功率↑）。
        if (!generatorMode && ratedRadS > 1e-6) {
            emfConstant = constantSpeed
                    ? (ratedVoltage * (1.0 - 1.0 / inrushK)) / ratedRadS
                    : (ratedVoltage / ratedRadS);
        }
    }

    /** 设置转速（RPM；恒速/伺服） */
    public void setSetSpeedRPM(double rpm) { if (rpm > 0) setSpeedRPM = rpm; }

    /** 伺服模式（2026-08-23 用户：伺服电机【立马开、立马停，没有过程】——
     *  目标转速直接到达/归零，无惯性加速/减速过程） */
    public volatile boolean servoMode;

    /** 设置伺服模式（须同时恒定转速） */
    public void setServoMode(boolean s) { servoMode = s; }

    /** 过压温度惩罚基准功率（W，2026-08-23）：与 DC 超频惩罚同机制——
     *  P = (V/Vmax)³ − 1 × 基准，每推进步 addHeat → 温度渐升 → 到限爆炸
     * （模拟现实：过压→绕组过载发热→过热，延时爆炸，不瞬爆）。默认 2000W。 */
    public volatile double overVoltPenaltyBaseW = 2000.0;

    /** 设置过压惩罚基准功率（W） */
    public void setOverVoltPenaltyBaseW(double w) { if (w >= 0) overVoltPenaltyBaseW = w; }

    /** 当前有效电感（按转速：启动 → L/k；运行 → 恢复 L）。
     *  恒速/伺服：转速恒定（无启动爬升阶段）→ 全值（EMF 系数已给正常电流）。 */
    public double effectiveInductance() {
        if (inductance <= 0) return 0;
        if (constantSpeed || servoMode) return inductance;
        return inductance * inrushFactor();
    }

    /** 当前有效绕组电阻（2026-08-23 用户规格：固定物理参数 25.6Ω/12.8Ω——
     *  不做涌流缩放；启动电流由 EMF 系数保证（恒速/伺服 (1−1/k)·V）与
     *  电感缩放（普通启动 L/k）体现。之前每 tick 随 ω 缩放 → 参数变化→重解
     *  抖动 → 电机瞬开瞬停 + 电学震荡）。 */
    public double effectiveResistance() {
        return resistance;
    }

    /** 涌流系数 f(ω) = 1/k + (1−1/k)·min(1,|ω|/ω_rated)：启动 k 倍 → 运行 1 */
    private double inrushFactor() {
        double frac = Math.min(1.0, Math.abs(rotorSpeedRadS) / Math.max(ratedOmega, 1e-6));
        return Math.max(1.0 / inrushK + (1.0 - 1.0 / inrushK) * frac, 1e-6);
    }

    /** 应用涌流：按当前转速更新内部电感（L_eff=L·[1/k+(1−1/k)·f(ω)]）；
     *  EMF 源内阻【固定物理参数】（25.6/12.8，不再随 ω 缩放——避免每 tick
     *  参数变化→重解抖动→瞬开瞬停）。 */
    private void applyInrush() {
        try {
            if (inductorRef != null) {
                double le = effectiveInductance();
                if (Math.abs(inductorRef.inductance - le) > 1e-12) {
                    inductorRef.setInductance(le);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ===== 虚拟机电系统（2026-08-15 用户设计：一切模拟都放入虚拟计算，
    // 与 MC 的交互全走消息） =====
    // 转速/转矩/EMF 完全引擎侧自治（后台线程，纯 Java），不读 BE 任何字段；
    // 引擎算完转速 → 事件回调（neoforge 侧实现 → 发 EngineBus 消息 → BE 应用）；
    // 外力驱动（Create 拖动发电机）由 BE 侧消息写入 externalDriveRadS。

    /** 转子转速（rad/s，引擎侧自治，后台推进） */
    public volatile double rotorSpeedRadS;
    /** 转动惯量（kg·m²） */
    public volatile double inertia = 1.0;
    /** 摩擦系数（N·m·s/rad）。2026-08-27 现实模型 b=0.05：空载稳态 252.2RPM/
     *  16143SU（≈99.5% 满速，K_E=8.58 精确自洽）；断电后摩擦滑行 τ=J/b=20s
     *  （30s 后 ~57RPM 接近停，惯性即负载消耗——现实 DC 电机）。 */
    public volatile double friction = 0.05;
    /** 负载转矩（N·m，电动机带载；负 = 发电制动） */
    public volatile double loadTorque;

    /**
     * 负载率 λ（2026-08-25 用户：应力消耗发回 → 引擎建模负载）。
     * 定义：λ = Create 网络实际应力消耗 / 源容量（0..1；>1 = 过应力堵转）。
     * 引擎用它：
     *   - 启动：负载重（λ 大）→ 启动转矩不足 → 【堵转】（ω=0、大电流、持续发热）
     *   - 停机：τ = 30s / max(λ, 小量)（负载越重停得越快；空载 λ≈0 → 30s 慢停）
     * 由组装器 registerParams 从 BE 每 tick 上报消息写入（主线程→后台）。
     */
    public volatile double loadRatio = 0.0;

    /**
     * 设置负载率（0..1；>1 钳制为 1——过应力=满载+有余量）。值变化才写。
     */
    public void setLoadRatio(double lambda) {
        double v = Math.min(Math.max(lambda, 0.0), 1.0);
        if (Math.abs(loadRatio - v) > 1e-6) loadRatio = v;
    }

    /**
     * 网络应力消耗（SU，2026-08-26 用户：BE 每 tick 上报"需要消耗的应力" → 异步
     * 引擎算停机加速度）。Create 网络实际挂载负载的应力总消耗（calculateStress，
     * 主线程读 → sendToEngine → 组装器 registerParams 写入本字段）。
     * 断电滑行用它：α_load = networkStressSU / J（负载力矩/惯量 = 角加速度），
     * 应力消耗越大停得越快（负载越重、惯量越小 → 加速停）。
     */
    public volatile double networkStressSU = 0.0;

    public void setNetworkStressSU(double stressSU) {
        double v = Double.isFinite(stressSU) && stressSU > 0 ? stressSU : 0;
        if (Math.abs(networkStressSU - v) > 1e-3) networkStressSU = v;
    }

    /**
     * 本机负载应力（SU，2026-08-28 架构：由具体电机组装器按现实计算/分摊后注入——
     * 模型只读该输入计算负载转矩，不直接读网络总应力消耗 → 多电机并联各摊其份）。
     */
    public volatile double motorLoadStressSU = 0.0;

    /** 设置本机负载应力（SU；组装器注入；非有限/负→0；值变化才写） */
    public void setMotorLoadStressSU(double su) {
        double v = Double.isFinite(su) ? su : 0;
        if (v < 0) v = 0;
        if (Math.abs(motorLoadStressSU - v) > 1e-3) motorLoadStressSU = v;
    }

    /**
     * 当前网络转速（rad/s，2026-08-26 用户：发送帮助检测堵转）。由 BE 每 tick
     * 读 Create 网络当前转速（kb.getSpeed()→rad/s）经消息上报 → 组装器写入本
     * 字段。断电滑行用它判【过载堵转】：网络转速≈0 且仍有负载 → 机械能被过载
     * 吸收 → 应力应归零（否则断电过载后应力不归零）。
     */
    public volatile double networkRadS = 0.0;

    public void setNetworkRadS(double w) {
        double v = Double.isFinite(w) ? w : 0;
        if (Math.abs(networkRadS - v) > 1e-4) networkRadS = v;
    }

    /**
     * 网络供电标志（2026-08-27 用户「接入后转不动」修复）：BE 每 tick 上报
     * 有网消息 [λ,stress,cap,netRadS] = 供电；空心跳（无网/断电）= 0。
     * ⚠ 供电检测用本标志（而非 iFlow——EMF 自洽后稳态 iFlow≈0 会误判断电；
     * 端口电压 vabMag 断电时残留 EMF 残压也无法区分）。BE 上报 hasNet 最干净。
     */
    public volatile boolean networkConnected = false;

    public void setNetworkConnected(boolean c) { networkConnected = c; }
    /** 外力驱动转速（rad/s，发电机由 Create 拖动输入；≤0 = 无外力） */
    public volatile double externalDriveRadS;
    /** 转矩常数 K_T（N·m/A，电动机）。2026-08-27 现实模型：K_T=10 → 空载稳态
     *  257.3RPM / σ=16384（摩擦 b=0.05 影响小）；K_T 大=转矩大=贴近理想转速。 */
    public volatile double torqueConstant = 10.0;
    /** ⚠ 2026-08-27 七修：K_T 已【物理严格 = K_E】(emfConstant，advanceShaft 直接
     *  取 emfConstant)，此字段仅保留供诊断/向后兼容，不再参与计算。真实永磁 DC
     *  电机 K_T=K_E（SI 一致）→ 功率严格守恒；启动转矩因 K_T 变小（89.8→77 N·m）
     *  更贴合小功率电机现实。 */
    /** 最大输出功率上限（W；0 = 自动 = maxStress×额定角速度 ≈ 439.3kW）。
     *  2026-08-27 用户"添加最大功率限制"：输出规格映射后封顶在它（P_max）。 */
    public volatile double maxPowerW = 0.0;

    /** 设置最大输出功率上限（W；≤0 = 恢复自动 maxStress×ω_r） */
    public void setMaxPowerW(double w) { if (w >= 0) maxPowerW = w; }
    /** EMF 常数 K_EMF（V·s/rad）。普通电机默认 = 额定电压/额定角速度
     *  （230V/26.81rad/s ≈ 8.58 → 空载 EMF≈V，平衡转速≈256RPM）。
     *  ⚠ 2026-08-26 修复：旧默认 1.0 使 wTarget=|Vab|/K 错到 230rad/s(≈2196RPM)，
     *    该点 I≈0 → !powered → 滑行掉速 → 再加速 → 应力/转速往复振荡 + Pout 爆表。
     *  发电机由组装器覆盖为 EMF_K；恒速/伺服经 setSpecs 按涌流系数调整。 */
    public volatile double emfConstant = ratedVoltage / Math.max(ratedRadS, 1e-9);

    /**
     * 普通电机【输出功率值】（W，2026-08-23 用户：应力和转速根据此值输出）。
     * 0 = 未显式设定 → 按端口电压推导：P = (maxStress×ratedRadS) × min(1, V/额定电压)
     * （额定 230V → P_max = 16384×26.81 ≈ 439.3kW → 满应力满转速）。
     * 组装器/参数消息可调（setOutputPower）。
     */
    public volatile double outputPowerW;
    /** 实际输出功率（advanceShaft 每步计算值；未设置 outputPowerW 时为
     *  pMax×min(1,V/额定) —— 诊断用：应力 = lastOutputPowerW/额定角速度） */
    public volatile double lastOutputPowerW;
    /** 最近一次 EMF 幅值（setEmf 记录；2026-08-24 诊断） */
    public volatile double lastEmfV;
    /** 最近一次端口电压 |V_ab|（advanceShaft 记录；2026-08-24 诊断） */
    public volatile double lastVabV;
    /** 转向（±1，滞回记忆：只在 |re|>1V 更新；2026-08-24 防重建翻转） */
    public volatile double motorDir = 1.0;
    /** 净驱动压差 |V_ab − E|（扣反电动势后的内部阻抗压降；2026-08-24 用户：
     *  比电流更直接——电流 = 驱动压差/Z，受 Z（电感/频率）调制；驱动压差
     *  本身稳定，对应实际驱动强度） */
    public volatile double lastDriveV;
    /** 绕组电流缓存（2026-08-23 用户：求解时编写——advanceShaft 每步写入净驱动
     *  电流 A；供应力判断/外部读取，不依赖事后反退）。 */
    public volatile double lastCurrentA;
    /** 当前应力缓存（SU；到额定电压=最大值，线性） */
    public volatile double lastStressSU;

    /** [MotorAdv] 引擎推进诊断节流（2026-08-24） */
    private static volatile long ADVB_DBG_LAST;

    /** 设置输出功率（W；≤0 忽略 → 恢复按电压推导） */
    public void setOutputPower(double w) { if (w > 0) outputPowerW = w; }
    /** 转子跟随外力的一阶时间常数（s，发电机） */
    public volatile double shaftTimeConstant = 0.1;
    /** 上次事件通知转速（rad/s，节流：变化超阈值才发） */
    private volatile double lastEmittedRadS = Double.NaN;
    /** 上次事件通知时间戳（ms；2026-08-26 心跳：转速不变也 250ms 发一次） */
    private volatile long lastEmitMs = 0;
    /** 转速事件接收器（引擎→BE；可 null；实现方只发消息，不直接碰 MC） */
    public volatile MachineShaftListener shaftListener;

    /**
     * 转速动力学模型（2026-08-20 用户要求：模拟转速从低到高爬升）。
     * <p>
     * 通用一阶动力学（与热模型同构）：τ = J/b（转动惯量/摩擦阻尼）。
     *   - 目标稳态 ω_ss = (τ_net − τ_load)/b（净转矩/阻尼）
     *   - 解析解 ω(t) = ω_ss + (ω₀−ω_ss)·exp(−t/τ) → 转速平滑爬升到稳态，
     *     不再瞬时跳变；停机（断电）时按 τ 指数衰减到 0。
     *   - 大惯量（重转子/飞轮）→ τ 大 → 启动慢、停机也慢（真实物理）。
     * 状态同步：{@link #rotorSpeedRadS} = 本模型的当前值（读/写保持一致）。
     */
    public final DynamicsModel shaftInertia;

    /**
     * 断电滑行应力弛豫变量（2026-08-26 用户：空转 = 两次计算时间差 × 固定消耗）。
     * 用 {@code DynamicsModel#advanceLinear}：每次被调直接算距上次调用的时间差 dt，
     * 按固定每秒空转消耗（maxStress/30）线性减——不真实时间推进（非 advanceReal
     * 指数/去重），随每次计算增减。target 恒 0（断电应力归零）。
     */
    public final DynamicsModel coastStress = new DynamicsModel(10.0, 0.0);

    /** 事件接收器（引擎→BE；可 null；实现方只发消息，不直接碰 MC） */
    public interface MachineShaftListener {
        /** 引擎算出新转速 → 通知（rad/s 与 rpm） */
        void onRotorSpeed(double radS, double rpm);
    }

    /** 绑定转速事件接收器（只发消息的适配层） */
    public void bindShaftListener(MachineShaftListener l) { this.shaftListener = l; }

    /** 设置外力驱动转速（BE→引擎 消息写入；Create 拖动发电机） */
    public void setExternalDrive(double radS) { this.externalDriveRadS = radS; }

    /**
     * 机械推进（2026-08-20 多模型复合）：包装为 {@link StateDriven} 作为
     * 基础状态模型传入基类 {@link #setBaseModels}，由基类 advanceState 统一
     * 遍历推进（分解为多个基础模型分别调用）。推进内容 = {@link #advanceShaft}
     * （转速 → EMF → 事件）。
     */
    private final StateDriven shaftState = new StateDriven() {
        @Override
        public boolean advanceState(Complex va, Complex vb, double freqHz, double dt,
                                    com.hdf.cryptand.circuitsimulation.solver.SolveMode mode) {
            double before = rotorSpeedRadS;
            advanceShaft(va, vb, freqHz, dt);
            // 2026-08-27 用户「数万台电机性能」+ 稳态报告修复：公式已是纯增量
            // （ω += dω·dt，不用 shaftInertia）→ isSteady() 恒不更新（陈旧状态）
            // → 误报稳态。改报告【真实变化】：转速/应力/功率变化超阈值 → true
            // （网络继续求解）；已稳态 → false（下轮跳过，省算力——万台电机
            // 稳态时每 tick 只做微判）。节流靠 advancePseudoTime 的 stateChanging。
            return Math.abs(rotorSpeedRadS - before) > 1e-6
                    || Math.abs(lastOutputPowerW - before) > 1e-3;
        }
    };

    /**
     * 转速推进（伪时域固定节拍 dt，显式欧拉）：
     *   - 发电机：外力驱动 ω（一阶跟随 Create 拖动）→ EMF = K_EMF·ω（引擎侧算）
     *   - 电动机：转矩 T = K_T·I → dω/dt = (T − T_load − 摩擦·ω)/J；单向旋转
     * 电流统一用【扣除反电动势的净驱动电流】I = |(V_ab − E)/Z|（相量减，E 为
     * 实部与 V_ab 同相——电动机功率因数≈1）。启动 E=0 → 堵转大电流；转速升 →
     * I 降。超同步（E>V）→ I≈0（发电机工况由另一分支处理）。
     * 转速变化超阈值 → 事件回调（neoforge 转消息应用 BE）。
     */
    public void advanceShaft(Complex va, Complex vb, double freqHz, double dt) {
        // 2026-08-26 用户：dt==0 也推进（与温度一致——ThermalModel 每次计算都随
        // 自身时间戳增减，不受外部固定步长 0 阻断；此前 if(dt<=0) return 让断电
        // 滑行在步长为 0 时整段跳过 → 应力每秒只降 0.x，而温度却正常）。
        if (dt < 0) return;
        // 2026-08-24 引擎推进诊断（节流 5s）：确认 advanceShaft 是否被持续调用
        //（dt>0）——若缺失 → 动力学未推进 → 转速不演化（SIM_CLOCK/advancePseudoTime
        // 断链排查）
        // ⚠ 2026-08-26 修复：System.Logger 用 MessageFormat（{0} 而非 {}）——
        // 原 {} 占位符永不替换 → 真实 dt/omega/vab 全部丢失（诊断盲区根因）。
        long advNow = System.currentTimeMillis();
        if (advNow - ADVB_DBG_LAST >= 5000) {
            ADVB_DBG_LAST = advNow;
            String vabStr = (va != null && vb != null)
                    ? String.format("%.2f", va.sub(vb).abs()) : "null";
            System.getLogger("cryptand.motor").log(System.Logger.Level.INFO,
                    "[MotorAdv] adv dt=" + String.format("%.4f", dt)
                            + " omega=" + String.format("%.2f", rotorSpeedRadS)
                            + " rpm=" + String.format("%.1f",
                            rotorSpeedRadS * 60.0 / (2.0 * Math.PI))
                            + " stress=" + String.format("%.0f", lastStressSU)
                            + " vab=" + vabStr
                            + " load=" + String.format("%.3f", loadRatio));
        }
        // 方向 dir（motorDir 滞回：只在端口实部显著（|re|>1V）时更新，断电/重建
        // 不翻转；正反接只影响转向，2026-08-24 修复）
        if (va != null && vb != null) {
            double re = va.sub(vb).re;
            if (re > 1.0) motorDir = 1.0;
            else if (re < -1.0) motorDir = -1.0;
        }
        // 2026-08-27 统一公式公共量：端口电压实部/幅值、内部节点电压、反馈应力
        double vabMag = (va != null && vb != null) ? va.sub(vb).abs() : 0;
        lastVabV = vabMag; // 诊断：端口电压
        double vabSigned = (va != null && vb != null) ? va.sub(vb).re : 0;
        Complex vA = nodeVoltage(a);
        int eNode = (inductance > 0) ? this.x : this.b; // EMF 源终点（L>0→x；L=0→b）
        Complex vE = nodeVoltage(eNode);
        double sFb = Math.max(0, networkStressSU); // 网络应力（SU）
        double iFlow; // 电枢电流（戴维南/兜底，下方公式链写）
        // ⚠ 2026-08-27 同一化：电动机统一公式即将覆盖 emf/i/lastCurrentA/lastDriveV
        //（下方现实 DC 公式链）；此处仅保留发电机分支独立计算。
        if (generatorMode) {
            // 发电机：外力驱动 ω（Create 拖动 → 消息输入），EMF 引擎侧算。
            // 转速经惯性模型平滑（τ = shaftTimeConstant）：跟随目标不跳变。
            double target = externalDriveRadS;
            double w;
            if (target > 0) {
                w = shaftInertia.advance(target, dt);
            } else {
                // 无外力：摩擦自由衰减（指数趋近 0）——advanceReal（时间强相关）
                w = shaftInertia.advanceReal(0, System.nanoTime());
            }
            rotorSpeedRadS = w;
            // EMF 恢复（2026-08-25）：发电机 EMF = K·ω（外部机械驱动 → 发电）
            setEmf(emfConstant * rotorSpeedRadS);
        } else {
            // ===== 2026-08-27 用户：统一现实 DC 电机公式（电动机一条公式）=====
            //  唯一状态：ω（转子转速 rad/s，时间强相关 / 温度模型同构）
            //  ── 公式链（一条，模式差异 = 2 参数：BE/目标角速度一致性）──
            //   1) EMF = K_E·ω（K_E=emfConstant；恒速/伺服已按设定转速配 K_E）
            //   2) I   = (V_ab − EMF)/R（电枢电流；供电→I>0 自动涌现；断电/放置
            //                          →Vab≈EMF → I≈0 → 不转，无滞回/无补丁）
            //   3) T_em = K_T·I（电磁转矩）
            //   4) J·dω/dt = T_em − T_load − b·ω（惯性；断电 T_em=0 → 摩擦滑行）
            //   5) P = T_em·ω；σ = 换算（恒转矩→带载 ω 降 σ 保持）
            //  ── 模式参数 ──
            //   wFbRated（额定角速度）= ratedRadS；伺服 = 小惯量（瞬达）。
            //  ────────────────────────────────────────────────────────────
            double vMax = Math.max(maxStress, 1e-6);
            double wFbRated = Math.abs(ratedRadS); // 额定角速度（rad/s）
            double kE = emfConstant;                    // K_E（V·s/rad）
            // ⚠ 2026-08-27 七修（用户同意功率严格守恒、转矩小更现实）：K_T=K_E。
            //  真实永磁 DC 电机 K_T 与 K_E 相等（SI）；旧 K_T=10≠K_E=8.579 造成
            //  功率守恒差额（V·I ≠ T·ω+I²R，差 ~5W）。统一后严格守恒，启动转矩
            //  略降（89.8→77 N·m）更符合小功率电机。
            double kT = Math.max(emfConstant, 1e-6);   // K_T = K_E（物理严格）
            double bF = Math.max(friction, 0.001);      // b（N·m·s/rad）
            double jI = Math.max(inertia, 0.01);        // J（kg·m²）
            if (servoMode) jI = Math.max(inertia * 0.02, 0.01); // 伺服：瞬达（小惯量）
            // 负载转矩 T_load = λ×T_rated（λ=networkStressSU/maxStress；现实恒转矩）
            double tRated = kT * (ratedVoltage / Math.max(resistance, 1e-9)); // 启动矩
            double tLoad = Math.min(1.0, sFb / vMax) * tRated; // λ×T_rated 负载矩
            // ---- 统一公式链（一条，现实物理）----
            //  1) EMF（反电动势）= K_E·ω（带转向方向一致）
            double emfReal = kE * rotorSpeedRadS; // 带符号（方向跟随）
            //  2) 电枢电流 I = (V_ab − EMF)/R（端口电压扣除反电动势；戴维南）
            //     ⚠ 2026-08-27 五修（用户"接电 199.96V 但 conn=false 不转"实锤）：
            //       networkConnected 由 BE 上报 capacity>0 判定（Create 机械 sources
            //       map 读电机容量）——但普通电动机不在机械 sources（它是消耗方非
            //       机械源）→ capacity 恒 0 → conn 恒 false → 真实供电被截断不转。
            //       修复：供电判据改用【电气端口电压】vabMag 合理范围（>0.5V 且
            //       <5×maxV）= 有源供电；EMF 与端口同相位（反接 sign=-1 → EMF 反向
            //       → 反向转对称，且超速 |EMF|>|Vab| 时 iReal 反向制动防失控）。
            boolean powered = vabMag > 0.5
                    && vabMag < 5.0 * Math.max(maxVoltage, 1e-9)
                    && Double.isFinite(vabMag);
            double eSign = (vabSigned >= 0) ? 1.0 : -1.0; // EMF 与端口同相
            double iReal;
            if (powered && vA != null && vE != null && emfSource != null
                    && Double.isFinite(vA.sub(vE).abs())) {
                double eDrive = eSign * Math.abs(emfSource.amplitude); // 恒正幅值×方向
                iReal = (vA.sub(vE).re - eDrive) / Math.max(resistance, 1e-9); // 带符号
            } else if (!powered) {
                iReal = 0; // 无源/断电无回路 → 摩擦滑行（惯性）
            } else {
                iReal = (vabSigned - eSign * Math.abs(emfReal))
                        / Math.max(resistance, 1e-9);
            }
            iFlow = iReal;
            //  3) 电磁转矩 T_em = K_T·I（带符号：正=驱动，负=发电制动）
            double tEm = kT * iReal;
            //  4) 惯性方程 dω/dt = (T_em − T_load − b·ω)/J（时间强相关推进）
            double dOmega = (tEm - tLoad - bF * rotorSpeedRadS) / jI;
            // 超速软钳（2026-08-27 防失控兜底）：|ω|>3×ω_r → 强摩擦制动
            //（防历史脏状态/瞬态网络的残留大转速二次失控）
            double wLim = 3.0 * wFbRated;
            double wCur = rotorSpeedRadS;
            if (Math.abs(wCur) > wLim) {
                dOmega -= 0.3 * (Math.abs(wCur) - wLim) * Math.signum(wCur) / jI;
            }
            double wReal = wCur + dOmega * Math.max(dt, 0.01);
            //  5) 应力输出（SU）：σ = σ_max × min(1, |ω|/ω_r)（1RPM=64SU，线性）
            //     ⚠ 2026-08-27 修正：原 pReal/wFbRated 把物理瓦特当应力（塌缩 1.3SU）。
            double sigmaReal = vMax * Math.min(1.0,
                    Math.abs(wReal) / Math.max(wFbRated, 1e-9));
            sigmaReal = Math.min(vMax, Math.max(0, sigmaReal));
            //  6) 输出功率（W）：规格映射【非线性 T·ω 抛物线】
            //     ⚠ 2026-08-27 六修（用户"曲线像二次函数有最高点 + 消耗应力多反而
            //       功率大"）：物理 P=T·ω 是抛物线——堵转区 ω 降但 T 升 → 负载增
            //       功率反增，且高转速反而往下跌；不符合 Create「功率随转速单调升、
            //       负载越重输出越少、封顶 P_max」直觉。
            //     改：P = min(P_max, σ×ω_r)（σ 线性 → P 线性单调、满速达 P_max）
            double pMaxW = (maxPowerW > 0) ? maxPowerW : (vMax * wFbRated);
            double pReal = Math.min(pMaxW,
                    sigmaReal * Math.max(wFbRated, 1e-9));
            // 更新状态（现实模型直接写物理量）
            rotorSpeedRadS = wReal;
            // EMF 源幅值【恒正】（方向由 ω 符号传达；2026-08-27 防负幅值巨电流）
            setEmf(Math.abs(emfReal));
            lastOutputPowerW = pReal;
            lastStressSU = sigmaReal;
            lastCurrentA = Math.abs(iReal);
            // 过压热惩罚（现实保护：端口电压超 rated/max → 额外加热 → 过热爆炸）
            // (vabMag/maxVoltage)³−1 增益 + 电流 I²R 热
            // ⚠ 2026-08-27 三修：vabMag 非有限/超合理上限（>10×maxVoltage）=
            //  悬空节点漂移（单根线 MNA GMIN 兜底）→ 不惩罚（防假过压爆）。
            if (thermal != null && vabMag > maxVoltage
                    && Double.isFinite(vabMag)
                    && vabMag < 10.0 * Math.max(maxVoltage, 1e-9)) {
                double ov = (vabMag / Math.max(maxVoltage, 1e-9)) - 1.0; // 超压比
                double penaltyW = overVoltPenaltyBaseW * Math.max(0, ov * ov * ov);
                thermal.addHeat(penaltyW * Math.max(dt, 0.01));
            }
            if (shaftListener != null) {
                try {
                    shaftListener.onRotorSpeed(rotorSpeedRadS,
                            rotorSpeedRadS * 60.0 / (2.0 * Math.PI));
                } catch (Throwable ignored) {
                }
            }
        }
        // 事件节流：转速变化超阈值才通知（→ 消息应用 BE，不直接碰 MC）
        // ⚠ 2026-08-26 修复"put 只触发一次"：原条件 |s - last| > 0.05 在转速
        //   **不变**（恒 0 / 恒稳态）时永不触发 → 引擎→BE 消息断流 → BE 收不到
        //   更新（转速不写回、诊断无日志）。加【心跳】：转速变化 >0.05 立即发；
        //   不变也每 250ms 发一次（确认链路活着、BE 值不被旧值覆盖）。
        if (shaftListener != null) {
            double s = rotorSpeedRadS;
            long nowMs = System.currentTimeMillis();
            if (Double.isNaN(lastEmittedRadS)
                    || Math.abs(s - lastEmittedRadS) > 0.05
                    || nowMs - lastEmitMs >= 250) {
                lastEmittedRadS = s;
                lastEmitMs = nowMs;
                try {
                    shaftListener.onRotorSpeed(s, s * 60.0 / (2.0 * Math.PI));
                } catch (Throwable ignored) {
                }
            }
        }
        // 涌流经验模型：转速变化 → 更新内部电感（启动 L/k → 运行 L）
        applyInrush();
    }

    /** 当前转速（rad/s，诊断） */
    public double rotorSpeed() { return rotorSpeedRadS; }

    // ===== 统一接口：get 输出（2026-08-27 用户：set 输入 → compute → get 输出）=====
    // 组装器/求解器只读本组方法；参数经 setXxx 写入、状态经 advanceShaft
    // （compute）推进。温度嵌入 ThermalModel（getTemperatureC 附加输出）。

    /** 输出：转子转速（rad/s） */
    public double getRotorSpeed() { return rotorSpeedRadS; }

    /** 输出：转子转速（RPM = rad/s × 60/2π） */
    public double getRotorRPM() {
        return rotorSpeedRadS * 60.0 / (2.0 * Math.PI);
    }

    /** 输出：当前机械应力（SU） */
    public double getStressSU() { return lastStressSU; }

    /** 输出：当前输出功率（W = 应力×转速） */
    public double getOutputPowerW() { return lastOutputPowerW; }

    /** 输出：当前绕组电流（A，绝对） */
    public double getCurrentA() { return lastCurrentA; }

    /** 输出：内部温度（K；嵌入 ThermalModel；无温度模型 → 环境 293.15K=20°C） */
    public double getTemperatureK() {
        return (thermal != null) ? thermal.getTemperature() : 293.15;
    }

    /** 输出：内部温度（°C；嵌入 ThermalModel） */
    public double getTemperatureC() {
        return (thermal != null) ? thermal.tempCelsius()
                : (293.15 - 273.15);
    }

    /** 输出：是否过热（嵌入 ThermalModel；无温度模型 → false） */
    public boolean isOverheated() { return thermal != null && thermal.overheated(); }

    /** 跨重建状态恢复（2026-08-24 用户：断线/网络重建后惯性滑行继续——转速/EMF
     *  不归零；由适配层 MotorStateStore 在组装时调用，纯数据无 MC 依赖）。
     *  ⚠ 2026-08-27 扩展：断电滑行需恢复【应力】。重建（剪线→网络重建→新模型）
     *  若不恢复应力，新模型 lastStressSU=0 → 滑行从 0 开始（= 断电瞬停）。
     *  MotorStateStore 存 {radS, emf, stress}；本方法恢复 radS/emf，应力由
     *  restoreStress 恢复（分开避免 API 语义混淆）。 */
    public void restoreMotorState(double radS, double emf) {
        if (Double.isNaN(radS) || Double.isInfinite(radS)) return;
        if (Double.isNaN(emf) || Double.isInfinite(emf)) return;
        try {
            rotorSpeedRadS = radS; // 保留符号（双向；2026-08-24 修复）
            if (shaftInertia != null) shaftInertia.reset(rotorSpeedRadS);
            // ⚠ 2026-08-27 EMF 幅值恒正（方向由 ω 符号；防负幅值巨电流爆炸）
            if (emfSource != null) emfSource.setAmplitude(Math.abs(emf));
            lastEmittedRadS = rotorSpeedRadS; // 不重复通知（保持当前值）
        } catch (Throwable ignored) {
        }
    }

    /** 恢复断电滑行应力（2026-08-27）：新模型从断电前应力继续线性衰减，
     *  而非从 0 重来（否则剪线重建 → 应力瞬 0）。 */
    public void restoreStress(double stress) {
        if (Double.isNaN(stress) || Double.isInfinite(stress)) return;
        try {
            lastStressSU = Math.max(0, stress);
            coastStress.setValue(lastStressSU);
        } catch (Throwable ignored) {
        }
    }

    public ElectroMachineModel(int a, int b, int x, double resistance, double inductance,
                               ThermalModel thermal, EnergyModel energy,
                               boolean generatorMode, double emfVoltage) {
        super(build(a, b, x, resistance, inductance, generatorMode, emfVoltage), thermal);
        this.a = a;
        this.b = b;
        this.x = x;
        this.resistance = resistance;
        this.inductance = inductance;
        this.thermal = thermal;
        this.energy = energy == null ? new EnergyModel(1.0) : energy;
        this.generatorMode = generatorMode;
        // 转速惯性模型：τ = J/b（初始 = 1.0/0.05 = 20s，后可按设备重设）
        this.shaftInertia = new DynamicsModel(inertia / Math.max(friction, 1e-6), 0);
        // ⚠ 2026-08-24 根因修复：minValue=0 单向 → 用户接线（电源正极在 b 端）
        // 端口相量为负 → dir=-1 → 目标转速负 → 被 clamp 到 0 → ω 恒 0 → EMF 恒 0
        // → dDrive=Vab 恒大 → 9A 持续 → 过温爆炸；且正反接不对称。
        // 电机【双向旋转】：正反接只影响转向方向，其他一致。
        this.shaftInertia.minValue = Double.NEGATIVE_INFINITY;
        // 多模型复合（2026-08-20）：机械推进作为基础状态模型传入基类数组
        setBaseModels(shaftState);
        AcVoltageSource emf = null;
        for (Element e : decompose()) {
            if (e instanceof AcVoltageSource av) {
                if (emf == null) emf = av;
            }
            if (e instanceof Inductor in) {
                inductorRef = in;
            }
        }
        this.emfSource = emf;
        applyInrush(); // 初始 ω=0 → 涌流感抗 L/k（堵转电流 ≈ k×稳态）
    }

    /**
     * 组合基础元件：串联链（可逆电机统一模型，电动机/发电机同构）
     *   a --AcVoltageSource(EMF E, 内阻 r)-- x --Inductor(L)-- b
     * 源幅值 E 一律【正】（a 端为+）。端口方程 V_ab = E + I·Z：
     *   - 电动机：外部电源 V_ab 高 → I=(V_ab−E)/Z > 0（电→机械）；启动 ω=0 →
     *     E=0 → 堵转大电流（物理合理）；转速升 → E 升 → 空载电流小 → 导线不烧
     *   - 发电机：外力使 ω 高 → E>V_ab → I<0（机械→电）
     * ⚠ 2026-08-20 修复"测 10MΩ"（L=0 断链）：原实现 L=0 时只加 AcVoltageSource(a,x)
     *   + 不加 Inductor(x,b) → b 节点【完全悬空】（无任何元件）→ MNA 只有 GMIN
     *   兜底（1e-7 S = 10MΩ）→ 电机看似"开路"。L=0 时 EMF 源必须直接连 a-b。
     * ⚠ 2026-08-19 二次修正：v2 曾把电动机 E 取负（−emf）——错误！取负后电路
     *    I=(V_ab+E)/Z 正反馈（转速越高电流越大 → 绕组向网络注入能量 → 电压异常
     *    =“网络计算出错”）；且 advanceShaft 却用 (V_ab−E)/Z 算转矩（矛盾 →
     *    E≥V 时 i=0 → “电机不转”）。正负统一后两处一致，物理正确。
     * ⚠ v1 错误（并联拓扑）：Resistor(a,x,r) + AcVoltageSource(x,b,·) 再并联
     *   Inductor(x,b) —— EMF 源与 L 并联 = 电压源短路电感，绕组只剩 R。
     */
    private static Element[] build(int a, int b, int x, double r, double l,
                                   boolean gen, double emf) {
        java.util.List<Element> els = new java.util.ArrayList<>();
        if (l > 0) {
            els.add(new AcVoltageSource(a, x, emf, 0, r > 0 ? r : 1e-4));
            els.add(new Inductor(x, b, l));
        } else {
            // L=0：EMF 源直接 a-b（x 节点弃用）。否则 b 悬空 → GMIN 10MΩ。
            els.add(new AcVoltageSource(a, b, emf, 0, r > 0 ? r : 1e-4));
        }
        return els.toArray(new Element[0]);
    }

    /**
     * 更新 EMF/反电动势幅值（参数变化 → 重解，不重建网络）：
     * 电动机/发电机统一【正幅值】（a 端为+）。电动机的"反"体现在端口方程
     * V_ab = E + I·Z 中 E 与 V 同号 → 电流 I=(V−E)/Z 随 E 升而降（不取负）。
     */
    public void setEmf(double v) {
        lastEmfV = v; // 诊断：EMF 幅值（2026-08-24）
        if (emfSource != null) emfSource.setAmplitude(v);
    }

    /** 更新绕组电阻（可变参数：碳堆/变阻器等）——绕组电阻是 EMF 源【内阻】 */
    public void setResistance(double r) {
        if (r <= 0) return;
        if (emfSource != null) emfSource.setSeriesResistance(r);
    }

    /** 当前绕组阻抗 |Z| = √(R_eff² + (ω·L_eff)²)（涌流有效值） */
    public double impedanceAt(double omega) {
        double xl = omega * effectiveInductance();
        double r = effectiveResistance();
        return Math.sqrt(r * r + xl * xl);
    }

    // ===== 统一复合元件生命周期（CompositeElement） =====
    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }

    /**
     * 绕组铜耗（平均，电流法 2026-08-18/19 用户要求）：I²·R/2，其中 I 是流过
     * 内部电阻 R（EMF 源内阻）的【真实电流】而非端口压差。
     * 戴维南：V_a − V_e = E + I·R → I = |V_a − V_e − E|/R，其中 V_e 是 EMF 源
     * 终点节点电压（L>0 → 内部节点 x；L=0 → 端口 b，2026-08-20 修复）。
     * nodeVoltages 注入后精确；未注入 → 端口压差扣除 E 后 / 阻抗兜底。
     */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        if (resistance <= 0) return 0;
        // ⚠ 2026-08-27 五修：供电判据用电气端口电压（与 advanceShaft 一致——
        //   networkConnected 不可靠会把真实供电判成断电则不发热）；无源/断电
        //   （端口无压差）→ 0 热（摩擦滑行不发热）。
        double pm = (va != null && vb != null && Double.isFinite(va.sub(vb).abs()))
                ? va.sub(vb).abs() : 0;
        boolean powered = pm > 0.5 && pm < 5.0 * Math.max(maxVoltage, 1e-9);
        if (!powered) return 0;
        double z = impedanceAt(omega);
        double iPeak;
        Complex vA = nodeVoltage(a);
        // EMF 源终点节点：L>0 = 内部节点 x（电感前）；L=0 = 端口 b（直连）
        Complex vE = nodeVoltage(inductance > 0 ? x : b);
        if (vA != null && vE != null && emfSource != null) {
            // 带符号净驱动：I = (V_a − V_e).re − E（负=发电，热按平方，与
            // advanceShaft 一致；EMF 与端口同相 → 反接对称）
            double eSign = (va.sub(vb).re >= 0) ? 1.0 : -1.0;
            double eDrive = eSign * Math.abs(emfSource.amplitude);
            double dv = vA.sub(vE).re - eDrive;
            iPeak = Double.isFinite(dv) ? dv / resistance : 0;
        } else if (z > 1e-12 && va != null && vb != null) {
            double eSign = (va.sub(vb).re >= 0) ? 1.0 : -1.0;
            double emfMag = eSign * Math.abs(emfSource == null
                    ? 0 : emfSource.amplitude);
            double dv = va.sub(vb).re - emfMag;
            iPeak = Double.isFinite(dv) ? dv / z : 0;
        } else {
            return 0;
        }
        return iPeak * iPeak * resistance / 2.0;
    }

    // ===== 能量模型（EnergyDevice：电能↔机械能统一入口） =====
    @Override public EnergyModel energy() { return energy; }

    /**
     * 状态是否仍在变化（2026-08-27 覆写修复）：旧版查 shaftInertia.isSteady()——
     * 但公式已不用 shaftInertia（ω 直接增量推进，shaftInertia 从未更新 →
     * isSteady 恒真 → 电机误判稳态 → 不推进 → ω 冻结）。改查【公式真实变化】：
     * 转速/功率帧间变化超阈值 → true（未稳态继续推进）；空载稳态不变 → false
     * （下轮跳过——每 tick 只做轻判，数万台电机省算力）。
     */
    @Override
    public boolean stateChanging() {
        try {
            double w = rotorSpeedRadS;
            double p = lastOutputPowerW;
            boolean changing = Double.isNaN(lastStateW)
                    || Math.abs(w - lastStateW) > 1e-4
                    || Math.abs(p - lastStateP) > 1e-3;
            lastStateW = w;
            lastStateP = p;
            return changing;
        } catch (Throwable t) {
            return false;
        }
    }

    /** stateChanging 帧间缓存（2026-08-27：趋势判定需上次值） */
    private volatile double lastStateW = Double.NaN;
    private volatile double lastStateP = Double.NaN;

    /** 求解后同步储能状态：记录端口电压（等效电气储能 V=Q/C 参考） */
    @Override
    public void syncCharge(Complex va, Complex vb) {
        if (va == null || vb == null) return;
        energy.charge = energy.capacitance * va.sub(vb).abs();
    }

    /** 重置：转速惯性归零 + 父类（温度/状态）重置 */
    @Override
    public void reset() {
        rotorSpeedRadS = 0;
        if (shaftInertia != null) shaftInertia.reset(0);
        lastEmittedRadS = Double.NaN;
        super.reset();
    }

    // ===== 绑定参数（ElementBinding：外部全局调整，不依赖 MC 模型） =====
    // ⚠ 2026-08-24 用户规格（"和现实一样"）：电机内部元件【电阻固定物理参数】
    // （普通/恒速 25.6Ω、伺服 12.8Ω）——不随绑定/外部调整变化；可变的只有
    // 电感（涌流模型 L/k）等。原实现绑定变化会 setResistance（改 EMF 源内阻）
    // → 违反规格（电阻漂移 → 电流/应力异常）。
    @Override
    protected void onBindingChanged(ElementBinding b) {
        // 忽略 boundResistance：电机绕组电阻恒定（不调用 setResistance）
    }

    @Override
    public ElementType type() { return ElementType.COMPOSITE; }

    @Override
    public String toString() {
        return "ElectroMachineModel{" + (generatorMode ? "GEN" : "MOTOR")
                + " " + a + "-" + b + " R=" + resistance + " L=" + inductance
                + " x=" + x + "}";
    }
}
