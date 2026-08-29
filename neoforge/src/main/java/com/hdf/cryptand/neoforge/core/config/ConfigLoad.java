/**
 * ===== 配置层 =====
 */

package com.hdf.cryptand.neoforge.core.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public class ConfigLoad {
    /** 主题 spec 构建器：common(modid 通用) / circuit-simulation(电路仿真) / powergrid(交错电网支持) / cee(CEE 铁路电气化) / aeronautics(飞艇/轮子物理) / debug(调试) */
    private static final ModConfigSpec.Builder CK_COMMON    = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CK_CIRCUIT   = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CK_POWERGRID = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CK_CEE       = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CK_AERO      = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CK_DEBUG     = new ModConfigSpec.Builder();

    /**
     * Aeronautics 轮子摩擦力→应力物理模型开关（2026-08-28 用户：
     * "轮子能否改造，改成和现实类似，根据实际摩擦力消耗来计算应力消耗"）。
     * <p>
     * true  → 替换 Offroad WheelMountBlockEntity.calculateStressApplied()：
     *         轮子应力消耗 = f(接触面摩擦 μ、单轮法向承载质量 N、车速 v、轮半径 r)
     *         ——摩擦越强/载重越大/速度越高 → 应力越快；微小摩擦面（轮子悬空/
     *         无接触）→ 应力近 0。纯公式链（铁律 5），无阈值 if/else 分支。
     * false → 恢复 Offroad 原版：静态 stressImpact（与重量/摩擦无关）。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_AERO_WHEEL_STRESS_FRICTION = CK_AERO
            .comment("Aeronautics轮子摩擦应力物理模型开关",
                     "true: 轮子应力消耗按实际摩擦力(摩擦系数x法向承载质量x速度)计算",
                     "false: 恢复原版静态stressImpact",
                     "需要重启游戏生效")
            .define("enableAeroWheelStressFriction", true);

    /**
     * 轮子空转固定应力（SU，2026-08-28 用户"空转也消耗一定"）。
     * <p>
     * 轮子【转动即消耗】（轴承+传动机构+风阻基础损耗），即使悬空/不接触
     * 地面也有——物理上任何旋转体空转都要付基础摩擦损耗。0 = 不启用
     * （空转 0 消耗，仅摩擦项）。默认 4 ≈ 一个小齿轮 impact 量级（8 轮车
     * 空转 32 SU，远小于电机容量，负载项主导时占比≈0.2%，合理）。
     * 计算公式：SU_fixed = baseSU × min(1, |ω|/ω_ref)（平滑，非硬开关）。
     */
    public static final ModConfigSpec.DoubleValue AERO_WHEEL_IDLE_STRESS_SU = CK_AERO
            .comment("轮子空转固定应力(SU)",
                     "轮子转动即消耗(轴承/传动/风阻基础损耗),即使悬空也有",
                     "0 = 不启用(空转0消耗)",
                     "默认4 ≈ 一个小齿轮impact量级")
            .defineInRange("aeroWheelIdleStressSU", 4.0, 0.0, 1.0E6);

    /**
     * 空转固定应力随转速平滑参考（rad/s，2026-08-28）。
     * 低于此转速 → 固定消耗按比例（ω/ω_ref）；高于 → 满额 baseSU。
     * 目的：低速启动（ω≈0）不完全消耗固定项，避免转速刚起就全额扣应力
     * （转速 0 时固定项自然为 0——不转不耗）。默认 1 rad/s ≈ 9.5RPM。
     */
    public static final ModConfigSpec.DoubleValue AERO_WHEEL_IDLE_OMEGA_REF = CK_AERO
            .comment("空转固定应力转速参考(rad/s)",
                     "低于此转速按比例消耗固定应力,高于则满额",
                     "默认1 rad/s≈9.5RPM")
            .defineInRange("aeroWheelIdleOmegaRef", 1.0, 0.01, 1.0E6);

    public static final ModConfigSpec.IntValue CONFIG_VERSIONS = CK_COMMON
            .comment("配置文件版本")
            .defineInRange("configVersions", 1, 1, Integer.MAX_VALUE);

    // ===== 导线超长检测（WireOverlengthChecker 引用，2026-08-26 补齐） =====
    public static final ModConfigSpec.BooleanValue WIRE_OVERLENGTH_ENABLE = CK_DEBUG
            .comment("导线超长检测开关",
                     "true: 打开，服务端周期扫描不存在导线超长(单段>64格)并告警断开",
                     "false: 关闭(默认)")
            .define("wireOverlengthEnable", false);

    public static final ModConfigSpec.IntValue WIRE_OVERLENGTH_CHECK_INTERVAL_TICKS = CK_DEBUG
            .comment("导线超长检测间隔(tick)",
                     "默认20≈1秒扫描一次",
                     "范围1~1200")
            .defineInRange("wireOverlengthCheckIntervalTicks", 20, 1, 1200);

    /**
     * 替换底层电路实现开关：用 Cryptand 自研引擎替换 PowerGrid 的 MNA 矩阵求解器。
     *
     * 打开（true）即替换底层电路运算：ElectricalNetworkMixin 把 mna 替换为
     * CryptandMna（内部用 common 引擎的 DenseRealLU 求解 J·x = rhs）。
     * 关闭（false）→ 保持 PowerGrid 原求解器（JavaMNA/NativeMNA）。
     *
     * 开启后<b>自动禁用</b> PowerGrid 的多线程并行求解（自研引擎自带多线程
     * 逻辑，避免双重多线程冲突——旧 enablePowergridAsyncNetwork 已随 {
     * 2026-08-20 移除后台并行求解死代码} 一并废除）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_CRYPTAND_SOLVER = CK_CIRCUIT
            .comment("替换底层电路实现开关",
                     "true: 打开，用Cryptand自研引擎替换PowerGrid的底层电路求解",
                     "false: 关闭，保持PowerGrid原求解器",
                     "开启后自动禁用PowerGrid多线程(自研引擎自带多线程)",
                     "改后重启游戏生效")
            .define("enableCryptandSolver", true);

    /**
     * 电路求解频率（Hz）——统一求解频率基准，2026-08-22 重新定义（由旧
     * “电路求解频率阈值 powergridFrequencyThresholdHz / phasorFrequencyThresholdHz”
     * 演进而来）。
     *
     * 功能（单一求解频率，同时承担两点）：
     *   1) <b>时域/相量模式切换</b>：电路频率 &lt; 本频率 → 实时时域求解
     *      （逐采样点，可追踪波形瞬态）；频率 ≥ 本频率 → 相量/频域求解
     *      （稳态幅值相位，不受 20TPS 采样率 Nyquist 限制）——SolverModeSelector
     *      决策；
     *   2) <b>求解节拍基准</b>：与服务端固定求解节拍（cryptandTopologyFrequencyHz）
     *      一致默认 100Hz；AC/DC 独立计算频率配置已随旧架构废除
     *      （2026-08-22：AC/DC 线程不再按独立频率配置推进，统一走本求解频率/节拍）。
     */
    public static final ModConfigSpec.DoubleValue CIRCUIT_SOLVE_FREQUENCY_HZ = CK_CIRCUIT
            .comment("电路求解频率(Hz)",
                     "同时是时域/相量切换基准: 电路频率低于本值→实时时域求解(采样点级)",
                     "高于等于本值→相量/频域求解(稳态幅值相位)",
                     "旧AC/DC独立计算频率已废除，统一按本求解频率/节拍推进",
                     "默认100Hz，改后重启游戏生效")
            .defineInRange("circuitSolveFrequencyHz", 100.0, 1.0, 100000.0);

    /**
     * AC 波形当前点分辨率（bit）。
     *
     * 决定频率当前点位置最大值：8bit → 0~255，10bit → 0~1023。
     * 初始位置 = 最大值的 1/5（8bit 时 = 51 = 25.5×2）。
     */
    public static final ModConfigSpec.IntValue AC_WAVEFORM_RESOLUTION_BITS = CK_CIRCUIT
            .comment("交流波形当前点分辨率(bit)",
                     "决定频率当前点位置最大值: 8bit=0~255, 10bit=0~1023",
                     "初始位置=最大值的1/5(8bit时=51=25.5*2)",
                     "默认8bit")
            .defineInRange("acWaveformResolutionBits", 8, 1, 16);

    /**
     * 万用表调试模式开关。
     *
     * true  → MultimeterItemMixin 生效：万用表不再受 multimeterVoltage /
     *          multimeterCurrent 量程限制（超高电压/电流直接显示实际值），
     *          并在测量提示中追加显示网络频率等参数。
     * false → 万用表保持原版行为（量程内正常显示，超量程显示 &gt;max）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_MULTIMETER_DEBUG = CK_DEBUG
            .comment("万用表调试模式",
                     "true: 解除万用表显示量程限制并显示频率等参数",
                     "false: 万用表原版行为",
                     "需要重启游戏生效")
            .define("enableMultimeterDebug", false);

    /**
     * 变压器最低通过频率（Hz）。
     *
     * 变压器交流化改造：频率 ≥ 本阈值 → 交流变压（按原副边匝数比）；
     * 频率 &lt; 本阈值（含 0 / 直流 / 低频干扰）→ 隔直（副边完全隔离，无任何能量）。
     * 默认 5Hz。
     */
    /** 变压器通过频率（Hz）：0 = 不带最低频率设置（默认，变压组装器始终按匝数比
     *  交流变压，不隔直）；>0 = 频率低于该值时副边隔直(完全隔离)。 */
    public static final ModConfigSpec.DoubleValue TRANSFORMER_MIN_FREQUENCY_HZ = CK_POWERGRID
            .comment("变压器通过频率(Hz)",
                     "0=不带最低频率设置(默认): 变压组装器始终按匝数比交流变压, 不隔直",
                     ">0: 频率低于该值→副边隔直(完全隔离)",
                     "默认0")
            .defineInRange("transformerMinFrequencyHz", 0.0, 0.0, 100000.0);

    /**
     * 电动机最大驱动频率(Hz)。换向器电机是直流电机：要求输入稳定电流，
     * 频率大于等于该值→平均转矩为0→驱动不了(转矩衰减到0)；频率越高铁耗越大发热越厉害。
     * 默认 3Hz（接近直流的低频仍可驱动）。
     */
    public static final ModConfigSpec.DoubleValue MOTOR_MAX_DRIVE_FREQUENCY_HZ = CK_POWERGRID
            .comment("电动机最大驱动频率(Hz)",
                     "换向器电机(直流)要求稳定电流",
                     "频率≥该值→平均转矩为0→驱动不了",
                     "频率越高→铁耗越大→发热越厉害",
                     "默认3Hz")
            .defineInRange("motorMaxDriveFrequencyHz", 3.0, 0.0, 100000.0);

    /**
     * 发电机/电动机最大转速（RPM）。PowerGrid 原版默认 256；
     * 覆盖 RotorBehaviour.getMaxRotationSpeed()，超速会破坏方块。
     */
    public static final ModConfigSpec.IntValue GENERATOR_MOTOR_MAX_RPM = CK_POWERGRID
            .comment("发电机/电动机最大转速(RPM)",
                     "转子（发电机/电动机）转速上限，原版默认256",
                     "超过该值会过热并破坏方块",
                     "默认16384")
            .defineInRange("generatorMotorMaxRpm", 16384, 1, 1000000);

    /**
     * 电动机额定电压(V)。换向器电枢电压相对该值的跌落程度决定驱动力衰减：
     *   电枢电压 ≥ 额定 → 正常驱动
     *   电枢电压跌落 → 驱动力线性衰减（电网过载/电源容量不足 → 电压被拉低）
     *   电枢电压 → 0 → 完全停转（短路 / 网络塌陷）
     * 仅作用于【电动机】模式（电枢驱动转子）；发电机由外力驱动，不受影响。
     * 默认 230V（市电/交流源默认幅值）。
     */
    public static final ModConfigSpec.DoubleValue MOTOR_RATED_VOLTAGE = CK_POWERGRID
            .comment("电动机额定电压(V)",
                     "换向器电枢电压相对该值的跌落程度决定驱动力衰减",
                     "电压跌到0→停转(网络过载/短路/电源容量不足)",
                     "默认230V")
            .defineInRange("motorRatedVoltage", 230.0, 1.0, 1000000.0);

    /**
     * 电动机负载耦合系数。
     *
     * 电动机模式下的负载反馈闭环：以 Create 网络实际速度（RPM 换算 rad/s）
     * 与转子角速度之差产生反作用力 force += loadK × (netRadS − rotorRadS)。
     *   - 空载：网络速度 ≈ 转子速度 → 无反馈
     *   - 带载：网络被拖慢 → 反向力 → 转子减速
     *   - 外部反向拖动：网络反转 → 大反向力 → 转子反转 → EMF 反向
     *     → 电流反向 → 再生制动 + I²R 发热
     * loadK ≤ 0 → 禁用负载反馈（原版行为，转速只由电磁力+摩擦决定）。
     */
    public static final ModConfigSpec.DoubleValue MOTOR_LOAD_COUPLING = CK_POWERGRID
            .comment("电动机负载耦合系数",
                     "以Create网络速度与转子速度之差施加反作用力(负载反馈闭环)",
                     "空载无反馈; 带载拖慢转子; 反向拖动→再生制动",
                     "设为0禁用负载反馈(原版行为)",
                     "默认1.0")
            .defineInRange("motorLoadCoupling", 1.0, 0.0, 10000.0);

    /*
     * =====================================================================
     *  Create（机械动力）应力上限 Mixin 配置
     * =====================================================================
     */

    /**
     * 机械动力应力上限 Mixin 注入开关。
     *
     * true  → CryptandMixinPlugin 在类加载阶段注入 KineticBlockEntityMixin：
     *         检测齿轮网络总应力（KineticNetwork.calculateStress()），
     *         超过阈值（默认 1024.0f）时销毁齿轮方块。
     * false → 跳过该 Mixin 注入，恢复 Create 原版行为（无应力上限逻辑）。
     *
     * 需要重启游戏生效。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_CREATE_STRESS_LIMIT = CK_CIRCUIT
            .comment("启用机械动力(create)应力上限Mixin注入",
                     "true: 注入KineticBlockEntityMixin，齿轮网络应力超过阈值时销毁方块",
                     "false: 跳过该Mixin注入，恢复原版行为",
                     "需要重启游戏生效")
            .define("enableCreateStressLimit", true);

    /**
     * 机械动力应力销毁阈值（应力单位）。
     *
     * Create 网络应力 = Σ(每个方块的 impact × |转速RPM|)，随转速线性增长。
     * 单个小齿轮 impact=4：256RPM → 4×256=1024（原硬编码阈值，导致 256RPM 就爆）。
     * 默认 65536 = 4 × 16384，即单齿轮在配置最大转速(16384RPM)时应力恰好不超限。
     * 多齿轮网络应力累加，转速上限会相应降低（物理合理）。
     *
     * 需要重启游戏生效（Mixin 类加载阶段读取）。
     */
    public static final ModConfigSpec.DoubleValue CREATE_STRESS_LIMIT_THRESHOLD = CK_CIRCUIT
            .comment("机械动力应力销毁阈值",
                     "齿轮网络应力(impact×转速RPM之和)超过该值→销毁齿轮",
                     "默认65536=单齿轮(impact4)在16384RPM时的应力",
                     "需要重启游戏生效")
            .defineInRange("createStressLimitThreshold", 65536.0, 1.0, 1.0E9);

    /*
     * =====================================================================
     *  Cryptand 完全接管/自管仿真配置（2026-08-14 补齐）
     *  ⚠ 以下字段曾被引用但未注册（工作区恢复不完整），默认值按代码语义推断，
     *     如有偏差请用户复核调整。
     * =====================================================================
     */

    /** 原版导线转换开关：true → 转换原版导线进自管 WireGraph 并删除原版实体（完全接管）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_POWERGRID_CONVERSION = CK_POWERGRID
            .comment("原版导线转换开关(完全接管)",
                     "true: 转换原版导线进自管WireGraph并删除原版导线实体",
                     "false: 保持原版导线",
                     "需要重启游戏生效")
            .define("enablePowergridConversion", true);

    /** Cryptand 仿真主开关：与 enableCryptandSolver 任一开启即启用导线转换接管。 */
    public static final ModConfigSpec.BooleanValue ENABLE_CRYPTAND_SIMULATION = CK_CIRCUIT
            .comment("Cryptand仿真主开关",
                     "与enableCryptandSolver任一为true即启用导线转换接管",
                     "默认true")
            .define("enableCryptandSimulation", true);

    /** 拓扑管理器异步调度线程开关（Cryptand-TopologyMgr 线程）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_CRYPTAND_TOPOLOGY_ASYNC = CK_CIRCUIT
            .comment("拓扑管理器异步调度开关",
                     "true: 启动调度线程按频率节拍驱动求解round",
                     "false: 每tick主线程直接round",
                     "默认true")
            .define("enableCryptandTopologyAsync", true);

    /**
     * 拓扑/求解统一节拍频率（Hz）。调度线程以此频率驱动求解 round。
     * 2026-08-15 伪时域：默认 100Hz（10ms 固定节拍）——非线性网络每轮强制求解
     * + 能量状态推进（温度/储能漂移被跟踪）；线性网络缓存命中零重解，开销小。
     */
    public static final ModConfigSpec.DoubleValue CRYPTAND_TOPOLOGY_FREQUENCY_HZ = CK_CIRCUIT
            .comment("拓扑/求解统一节拍频率(Hz)",
                     "调度线程以此频率驱动求解round",
                     "伪时域默认100Hz(10ms固定节拍)",
                     "非线性网络每轮求解+状态推进; 线性网络缓存命中零重解")
            .defineInRange("cryptandTopologyFrequencyHz", 100.0, 1.0, 1000.0);

    /** 稀疏求解切换阈值：0 = 始终走 SuperLU 稀疏求解（默认0）。 */
    public static final ModConfigSpec.IntValue SPARSE_SOLVER_THRESHOLD = CK_CIRCUIT
            .comment("稀疏求解切换阈值",
                     "0=始终走SuperLU稀疏求解",
                     "正数=节点数超过该值才用稀疏")
            .defineInRange("sparseSolverThreshold", 0, 0, 1000000);

    /** Cryptand 计算最大线程数：0 = 自动（CPU核数-1），正数 = 上限。 */
    public static final ModConfigSpec.IntValue CRYPTAND_MAX_THREADS = CK_CIRCUIT
            .comment("Cryptand计算最大线程数",
                     "0=自动(CPU核心数-1)，正数=上限",
                     "写入全局通用分配器ThreadDispatchers",
                     "需要重启游戏生效")
            .defineInRange("cryptandMaxThreads", 0, 0, 16384);

    /** 每个 Worker 线程最大管理的虚拟线程数量（2026-08-16 用户要求：默认最大 1000）。 */
    public static final ModConfigSpec.IntValue CRYPTAND_MAX_VIRTUAL_THREADS = CK_CIRCUIT
            .comment("每个Worker线程最大管理的虚拟线程数量",
                     "线程收到任务后若虚拟线程数量未满则创建虚拟线程执行，否则继续排队等待",
                     "默认1000；0=禁用虚拟线程(任务直接在Worker常驻线程执行)",
                     "写入全局通用分配器ThreadDispatchers",
                     "需要重启游戏生效")
            .defineInRange("cryptandMaxVirtualThreads", 1000, 0, 100000);

    /** 网络操作记录表 + 网表相关操作类（异步交互管理）开关（2026-08-16 用户架构）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_CRYPTAND_NETOP = CK_CIRCUIT
            .comment("网络操作记录表+网表相关操作类(异步交互管理)开关",
                     "true: 网络事件经主线程交互管理类→异步交互管理类(记录表+消息缓冲+整合+优先级)驱动",
                     "false: 回退原有调度线程直接驱动求解round",
                     "默认true")
            .define("enableCryptandNetOp", true);

    /** 网表相关操作类在分配器上的任务模式（2026-08-16 用户要求：独占/普通）。 */
    public static final ModConfigSpec.ConfigValue<String> CRYPTAND_NETOP_DISPATCH_MODE = CK_CIRCUIT
            .comment("网表相关操作类在分配器上的任务模式",
                     "NORMAL=走虚拟线程执行(默认)",
                     "EXCLUSIVE=独占模式跳过虚拟线程直接Worker线程执行(一般不要这样做,很容易卡死)",
                     "默认NORMAL")
            .define("cryptandNetOpDispatchMode", "NORMAL");

    /**
     * 导线悬空检测周期（tick；2026-08-19 用户需求：设备烧毁后导线不再被烧断，
     * 改由悬空检测平滑断开）。懒检测：每 N tick 全图扫描一次端点方块已消失的
     * 导线并断开（removeEdge 无爆炸）。0 = 禁用定期检测（仅设备爆炸时强制检测）。
     */
    public static final ModConfigSpec.IntValue CRYPTAND_WIRE_DANGLING_CHECK_INTERVAL_TICKS = CK_CIRCUIT
            .comment("导线悬空检测周期(tick)",
                     "设备被烧毁/爆炸破坏后,相连导线不再被烧断,由悬空检测发现端点方块消失后平滑断开",
                     "懒检测:每N tick扫描一次",
                     "0=禁用定期检测(默认,2026-08-20 用户要求:导线检测由元件移除/",
                     "  导线破坏消息触发,不再固定时间全图扫描)",
                     "默认0")
            .defineInRange("cryptandWireDanglingCheckIntervalTicks", 0, 0, 6000);

    /** 线圈直流电阻（Ω）：交流化线圈物理参数，用于 Z=√(R²+(2πfL)²)。 */
    public static final ModConfigSpec.DoubleValue COIL_DC_RESISTANCE_OHM = CK_POWERGRID
            .comment("线圈直流电阻(Ω)",
                     "交流化线圈物理直流电阻(励磁绕组)",
                     "用于相量电流 Z=√(R²+(2πfL)²) 与温度系数基准",
                     "默认0.45")
            .defineInRange("coilDcResistanceOhm", 0.45, 0.001, 10000.0);

    /** 线圈有效电感（H）：交流化线圈物理电感（5kHz 时 XL≈15708Ω → 0.5H）。 */
    public static final ModConfigSpec.DoubleValue COIL_EFFECTIVE_INDUCTANCE_H = CK_POWERGRID
            .comment("线圈有效电感(H)",
                     "交流化线圈物理电感，用于感抗限流",
                     "默认0.5")
            .defineInRange("coilEffectiveInductanceH", 0.5, 0.000001, 10000.0);

    /** 线圈每 tick 最大温升（°C）：温升速率钳制，失真/过载数据渐进温升而非瞬间爆炸。 */
    public static final ModConfigSpec.DoubleValue COIL_MAX_TEMP_RISE_PER_TICK = CK_POWERGRID
            .comment("线圈每tick最大温升(°C)",
                     "温升速率钳制(渐进温升防瞬间爆炸)",
                     "默认20")
            .defineInRange("coilMaxTempRisePerTick", 20.0, 0.0, 1000.0);

    /** 线圈电阻温度系数 α：R(T)=R₀×(1+α×(T-22))，铜≈0.0039；0=关闭。 */
    public static final ModConfigSpec.DoubleValue COIL_RESISTANCE_TEMP_COEFF = CK_POWERGRID
            .comment("线圈电阻温度系数α",
                     "R(T)=R0*(1+α*(T-22))，铜≈0.0039",
                     "0=关闭温度系数",
                     "默认0.0039")
            .defineInRange("coilResistanceTempCoeff", 0.0039, 0.0, 1.0);

    /** 温度计算节流（2026-08-21 用户要求可配置：每计算 N 次才推进一次温度）。
     *  1 = 每次求解都推进；>1 = 每 N 次求解才推进一次（省性能，温度更新变慢）。
     *  写入引擎 ThermalModel.temperatureComputeInterval。 */
    public static final ModConfigSpec.IntValue TEMPERATURE_COMPUTE_INTERVAL = CK_CIRCUIT
            .comment("温度计算节流间隔",
                     "每计算N次才推进一次温度(1=每次计算都推进)",
                     "默认1")
            .defineInRange("temperatureComputeInterval", 1, 1, 1000);

    /** 元件爆炸模式（2026-08-21 用户要求：可配置不破坏方块）。
     *  0 = 关闭爆炸和破坏（过热无后果）；1 = 仅爆炸不破坏（默认，方块保留）；
     *  2 = 爆炸和破坏都有。 */
    public static final ModConfigSpec.IntValue EXPLOSION_MODE = CK_CIRCUIT
            .comment("元件爆炸模式",
                     "0=关闭爆炸和破坏；1=仅爆炸不破坏(默认)；2=爆炸和破坏都有",
                     "默认1")
            .defineInRange("explosionMode", 1, 0, 2);

    /** SPICE 元件库开关（ComponentLibrary/PhasorNetworkBuilder）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_SPICE_LIBRARY = CK_CIRCUIT
            .comment("SPICE元件库开关",
                     "true: 启用SPICE格式元件库",
                     "默认false")
            .define("enableSpiceLibrary", false);

    /** 设备参数模型开关（变压器参数/电机参数等，PhasorNetworkBuilder 用）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_DEVICE_PARAMETER_MODELS = CK_POWERGRID
            .comment("设备参数模型开关",
                     "true: 变压器/电机等按物理参数模型建模",
                     "默认true")
            .define("enableDeviceParameterModels", true);

    /** 原版直流(DC)电气设备最大工作频率（2026-08-22 用户需求：灯/风扇/铃/加热器
     *  等原版设备只支持 DC）。频率 &gt; 该值 → 温度【指数型】惩罚：轻微超出不明显，
     *  频率越高惩罚越严重（按超额倍数 o^3 放大）；0 = 关闭惩罚。默认 3Hz。 */
    public static final ModConfigSpec.DoubleValue DC_DEVICE_MAX_FREQUENCY_HZ = CK_POWERGRID
            .comment("原版直流(DC)电气设备最大工作频率(Hz)",
                     "灯/风扇/铃/加热器等原版设备只支持DC: 频率超过该值→温度指数型惩罚",
                     "轻微超出不明显, 频率越高惩罚越严重(按超额倍数指数放大)",
                     "0=关闭惩罚",
                     "默认3")
            .defineInRange("dcDeviceMaxFrequencyHz", 3.0, 0.0, 100000.0);

    /** 网络颜色显示调试开关（2026-08-21 用户要求：可视化网络合并/拆分）。
     *  true → 每个自管图连通分量分配随机颜色，分量内所有元件方块渲染该颜色外框
     *  （NetworkColorRenderer，客户端 RenderLevelStageEvent + RenderType.lines）。 */
    public static final ModConfigSpec.BooleanValue DEBUG_NETWORK_COLORS = CK_DEBUG
            .comment("网络颜色显示（调试）",
                     "true: 每个网络分配随机颜色，网络内元件方块显示该颜色外框",
                     "默认false")
            .define("debugNetworkColors", false);

    /** 线程分发调试开关（ThreadDispatchDebugMixin，每秒打印 Worker 统计）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_THREAD_DISPATCH_DEBUG = CK_DEBUG
            .comment("线程分发调试开关",
                     "true: 每秒打印ThreadDispatcher各Worker任务数/负载",
                     "默认false")
            .define("enableThreadDispatchDebug", false);

    /** float 求解器开关（默认 double 求解优先）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_FLOAT_SOLVER = CK_DEBUG
            .comment("float求解器开关",
                     "true: 允许使用float求解器(精度换速度)",
                     "默认false")
            .define("enableFloatSolver", false);

    /** 非线性相量计算方法（2026-08-15 用户需求：让非线性也能直接相量计算）。
     *  混合架构：带能量状态的元件（电容/电感/温度）仍走固定节拍伪时域推进
     *  （advanceState），本方法只处理【无记忆相量非线性】元件（二极管等）的
     *  电气求解——增强相量算法 + 伪时域能量并存。 */
    public static final ModConfigSpec.ConfigValue<String> PHASOR_NONLINEAR_METHOD = CK_CIRCUIT
            .comment("非线性相量计算方法",
                     "NONE: 禁用(非线性电气走普通相量+伪时域能量, 现状)",
                     "PIECEWISE: 分段线性化(工作点牛顿迭代, 基波)",
                     "HARMONIC_BALANCE: 谐波平衡法(多谐波+时域采样DFT, 捕获谐波畸变)",
                     "DYNAMIC_PHASOR: 动态相量法(相量包络时间推进, 含L/C包络导数)",
                     "默认NONE")
            .define("phasorNonlinearMethod", "NONE");

    /** 谐波平衡法谐波阶数（含基波；HARMONIC_BALANCE 用） */
    public static final ModConfigSpec.IntValue PHASOR_HARMONICS = CK_CIRCUIT
            .comment("谐波平衡法谐波阶数(含基波)",
                     "默认5")
            .defineInRange("phasorHarmonics", 5, 1, 32);

    /** EDA 电路查看器开关（2026-08-17 用户要求：方便配置决定是否开关使用）。
     *  false → /cryptand eda 指令提示已禁用，不打开界面。 */
    public static final ModConfigSpec.BooleanValue ENABLE_EDA = CK_CIRCUIT
            .comment("启用 EDA 电路查看器(/cryptand eda)",
                     "true: 允许打开 LDLib2 EDA 电路查看器(浏览/保存电路图)",
                     "false: 指令提示已禁用",
                     "默认true")
            .define("enableEda", true);

    /*
     * =====================================================================
     *  交错电网（PowerGrid）支持/接管配置（cryptand/powergrid.toml）
     * =====================================================================
     */

    /**
     * 交错电网（PowerGrid）支持/接管【变更开关】（2026-08-22 用户要求：
     * "仿真核心可以单独开，如果开启交错电网支持功能则替换交错电网的内容"）。
     * <p>
     * true  → 用 Cryptand 仿真核心【替换/接管 PowerGrid 的内容】：注入
     *         powergrid 包全部 Mixin（求解器替换/相量回写/完全接管/电机交流化
     *         等）、原版导线转换进自管 WireGraph 并删除实体。仿真核心
     *         （enableCryptandSolver / enableCryptandSimulation）需另行开启。
     * false → 完全原版 PowerGrid：不注入任何 powergrid 替换 Mixin、不做导线
     *         转换。仿真核心仍可单独运行（自研网络/EDA 等不依赖 PowerGrid
     *         的独立仿真功能不受影响）。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_POWERGRID_SUPPORT = CK_POWERGRID
            .comment("交错电网(PowerGrid)支持/接管开关",
                     "true: 用自研仿真核心替换/接管PowerGrid内容(Mixin注入+导线转换)",
                     "false: 完全原版PowerGrid,不注入任何替换Mixin",
                     "仿真核心(enableCryptandSolver)可单独开关",
                     "需要重启游戏生效")
            .define("enablePowergridSupport", true);

    /*
     * =====================================================================
     *  CEE（Create-Electro-Energetics）支持/接管配置（cryptand/cee.toml）
     * =====================================================================
     */

    /**
     * CEE（Create-Electro-Energetics）受电弓/接触网内容 + 系统接管开关
     * （2026-08-22 用户要求："添加此mod支持，如果开启支持则替换该模组系统，
     *  并且该模组端子也能和交错电网混合，毕竟是自管的"）。
     * <p>
     * true  → 启用 Cryptand 自带的 CEE 铁路电气化内容（受电弓/接触网方块，
     *         始终由 Cryptand 自管 WireNetwork 供电）；并开启 CEE 兼容层：
     *         用 Cryptand 仿真核心【替换/接管】CEE 的电力系统，CEE 的端子
     *         （受电弓/电缆端子等）接入 Cryptand 自管交错电网统一求解。
     * false → 完全不加载 CEE 相关内容（含受电弓/接触网方块与接管 Mixin）。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_CEE_SUPPORT = CK_CEE
            .comment("CEE(Create-Electro-Energetics)支持/接管开关",
                     "true: 启用受电弓/接触网内容 + 用自研核心替换/接管CEE电力系统,",
                     "      CEE端子接入Cryptand自管交错电网",
                     "false: 完全不加载CEE相关内容",
                     "需要重启游戏生效")
            .define("enableCeeSupport", true);

    // ⚠ 必须在【全部】字段定义之后 build（2026-08-15 崩溃修复）：
    // Java 静态字段按声明顺序初始化。此前 build() 位于类中间，其后 14 个字段
    // （ENABLE_POWERGRID_CONVERSION / CRYPTAND_TOPOLOGY_FREQUENCY_HZ /
    // CRYPTAND_MAX_THREADS / COIL_* 等）定义在 build 之后 → 从未绑定 spec →
    // 调用 .get() 抛 NullPointerException "Cannot get config value before spec
    // is built"（WorldNetworksMixin.preTick 每 tick 读 CRYPTAND_MAX_THREADS
    // → 服务器 tick 崩溃）。
    // ===== 主题 Spec（2026-08-22 按 mod 分配：config/cryptand/<file>.toml） =====
    /** modid 通用 */
    public static final ModConfigSpec COMMON_SPEC = CK_COMMON.build();
    /** 电路仿真（求解/转换/拓扑/设备/相量/网络/EDA） */
    public static final ModConfigSpec CIRCUIT_SPEC = CK_CIRCUIT.build();
    /** 交错电网（PowerGrid）支持/接管 */
    public static final ModConfigSpec POWERGRID_SPEC = CK_POWERGRID.build();
    /** CEE 铁路电气化支持/接管 */
    public static final ModConfigSpec CEE_SPEC = CK_CEE.build();
    /** Aeronautics（飞艇/轮子物理） */
    public static final ModConfigSpec AERO_SPEC = CK_AERO.build();
    /** 调试 */
    public static final ModConfigSpec DEBUG_SPEC = CK_DEBUG.build();

    /** 全部主题 Spec（注册顺序：common/circuit/powergrid/cee/aeronautics/debug） */
    public static final ModConfigSpec[] ALL_SPECS = {
            COMMON_SPEC, CIRCUIT_SPEC, POWERGRID_SPEC, CEE_SPEC, AERO_SPEC, DEBUG_SPEC
    };

    /** 各主题 Spec 对应配置文件（config/cryptand/ 子目录，按 mod 分配；
     *  英文 id（本地化）：common / circuit-simulation / powergrid / cee /
     *  aeronautics / debug） */
    public static final String[] ALL_SPEC_FILES = {
            "cryptand/common.toml",
            "cryptand/circuit-simulation.toml",
            "cryptand/powergrid.toml",
            "cryptand/cee.toml",
            "cryptand/aeronautics.toml",
            "cryptand/debug.toml"
    };
}
