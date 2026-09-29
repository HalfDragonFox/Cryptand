package com.hdf.cryptand.neoforge.simulator.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigCircuit —— 电路仿真（求解/转换/拓扑/设备/相量/网络/导线检测/EDA）配置。
 * <p>⚠ 2026-08-30 用户架构：core 仅保留注册 lib 功能——本配置属【仿真器子包】
 * （simulator），随子包迁移；core 不再定义任何仿真配置（导线检测等全部归此）。
 * <p>持有本域全部配置字段定义（ModConfigSpec）与 SPEC；文件路径由 ConfigLoad 总管理
 * （域 DOMAIN="circuit"）。读取门面 bool/integer/real/string → ConfigLoad.readXxx(DOMAIN, key, def)。
 */
public final class ConfigCircuit
        implements CryptandConfigSpec {

    /** 配置域 id（ConfigLoad.domainFileName 映射）。 */
    public static final String DOMAIN = "circuit";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigCircuit INSTANCE = new ConfigCircuit();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "circuit-simulation.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    /** 子包自注册：把本配置注册到 core 注册器（core 只提供注册接口，不收集）。 */
    public static void register() {
        CryptandRegistries
                .registerConfig(DOMAIN, "circuit-simulation.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

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
    public static final ModConfigSpec.BooleanValue ENABLE_CRYPTAND_SOLVER = CK
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
    public static final ModConfigSpec.DoubleValue CIRCUIT_SOLVE_FREQUENCY_HZ = CK
            .comment("电路求解频率(Hz)",
                     "同时是时域/相量切换基准: 电路频率低于本值→实时时域求解(采样点级)",
                     "高于等于本值→相量/频域求解(稳态幅值相位)",
                     "旧AC/DC独立计算频率已废除，统一按本求解频率/节拍推进",
                     "默认100Hz，改后重启游戏生效")
            .defineInRange("circuitSolveFrequencyHz", 100.0, 1.0, 100000.0);

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
    // （enableCreateStressLimit 已迁到 create 子包：ConfigCreate / create.toml —— 见 2026-09-13 归属修正）

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
    // （createStressLimitThreshold 已迁到 create 子包：ConfigCreate / create.toml）

    /** Cryptand 仿真主开关：与 enableCryptandSolver 任一开启即启用导线转换接管。 */
    public static final ModConfigSpec.BooleanValue ENABLE_CRYPTAND_SIMULATION = CK
            .comment("Cryptand仿真主开关",
                     "与enableCryptandSolver任一为true即启用导线转换接管",
                     "默认true")
            .define("enableCryptandSimulation", true);

    /**
     * 网络消息处理缓存上限（每条网络条目数，默认 20）。
     * 全局池只存【无状态消息】；网络计算前压缩合并（同位置保留最新）后放入该网络的
     * 消息处理缓存；超上限 → 仍无法减小 → 丢弃最旧变更并打印 [MsgPool]。
     */
    public static final ModConfigSpec.IntValue MESSAGE_POOL_LIMIT = CK
            .comment("网络消息处理缓存上限（每网络条目数）",
                     "全局池（无状态消息）在计算前压缩合并后放入网络缓存",
                     "超上限：先合并 → 仍超则丢弃最旧变更并打印 [MsgPool]",
                     "默认 20")
            .defineInRange("messagePoolLimit", 20, 1, 1000);

    /**
     * 拓扑/求解统一节拍频率（Hz）。调度线程以此频率驱动求解 round。
     * 2026-08-15 伪时域：默认 100Hz（10ms 固定节拍）——非线性网络每轮强制求解
     * + 能量状态推进（温度/储能漂移被跟踪）；线性网络缓存命中零重解，开销小。
     */
    public static final ModConfigSpec.DoubleValue CRYPTAND_TOPOLOGY_FREQUENCY_HZ = CK
            .comment("拓扑/求解统一节拍频率(Hz)",
                     "调度线程以此频率驱动求解round",
                     "伪时域默认100Hz(10ms固定节拍)",
                     "非线性网络每轮求解+状态推进; 线性网络缓存命中零重解")
            .defineInRange("cryptandTopologyFrequencyHz", 100.0, 1.0, 1000.0);

    /** 稀疏求解切换阈值：0 = 始终走 SuperLU 稀疏求解（默认0）。 */
    public static final ModConfigSpec.IntValue SPARSE_SOLVER_THRESHOLD = CK
            .comment("稀疏求解切换阈值",
                     "0=始终走SuperLU稀疏求解",
                     "正数=节点数超过该值才用稀疏")
            .defineInRange("sparseSolverThreshold", 0, 0, 1000000);

    /** Cryptand 计算最大线程数：0 = 自动（CPU核数-1），正数 = 上限。 */
    public static final ModConfigSpec.IntValue CRYPTAND_MAX_THREADS = CK
            .comment("Cryptand计算最大线程数",
                     "0=自动(CPU核心数-1)，正数=上限",
                     "写入全局通用分配器ThreadDispatchers",
                     "需要重启游戏生效")
            .defineInRange("cryptandMaxThreads", 0, 0, 16384);

    /** 每个 Worker 线程最大管理的虚拟线程数量（2026-08-16 用户要求：默认最大 1000）。 */
    public static final ModConfigSpec.IntValue CRYPTAND_MAX_VIRTUAL_THREADS = CK
            .comment("每个Worker线程最大管理的虚拟线程数量",
                     "线程收到任务后若虚拟线程数量未满则创建虚拟线程执行，否则继续排队等待",
                     "默认1000；0=禁用虚拟线程(任务直接在Worker常驻线程执行)",
                     "写入全局通用分配器ThreadDispatchers",
                     "需要重启游戏生效")
            .defineInRange("cryptandMaxVirtualThreads", 1000, 0, 100000);

    /** 网表相关操作类在分配器上的任务模式（2026-08-16 用户要求：独占/普通）。 */
    public static final ModConfigSpec.ConfigValue<String> CRYPTAND_NETOP_DISPATCH_MODE = CK
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
    public static final ModConfigSpec.IntValue CRYPTAND_WIRE_DANGLING_CHECK_INTERVAL_TICKS = CK
            .comment("导线悬空检测周期(tick)",
                     "设备被烧毁/爆炸破坏后,相连导线不再被烧断,由悬空检测发现端点方块消失后平滑断开",
                     "懒检测:每N tick扫描一次",
                     "0=禁用定期检测(默认,2026-08-20 用户要求:导线检测由元件移除/",
                     "  导线破坏消息触发,不再固定时间全图扫描)",
                     "默认0")
            .defineInRange("cryptandWireDanglingCheckIntervalTicks", 0, 0, 6000);

    /** 元件爆炸模式（2026-08-21 用户要求：可配置不破坏方块）。
     *  0 = 关闭爆炸和破坏（过热无后果）；1 = 仅爆炸不破坏（默认，方块保留）；
     *  2 = 爆炸和破坏都有。 */
    public static final ModConfigSpec.IntValue EXPLOSION_MODE = CK
            .comment("元件爆炸模式",
                     "0=关闭爆炸和破坏；1=仅爆炸不破坏(默认)；2=爆炸和破坏都有",
                     "默认1")
            .defineInRange("explosionMode", 1, 0, 2);

    /** SPICE 元件库开关（ComponentLibrary/PhasorNetworkBuilder）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_SPICE_LIBRARY = CK
            .comment("SPICE元件库开关",
                     "true: 启用SPICE格式元件库",
                     "默认false")
            .define("enableSpiceLibrary", false);

    /** 非线性相量计算方法（2026-08-15 用户需求：让非线性也能直接相量计算）。
     *  混合架构：带能量状态的元件（电容/电感/温度）仍走固定节拍伪时域推进
     *  （advanceState），本方法只处理【无记忆相量非线性】元件（二极管等）的
     *  电气求解——增强相量算法 + 伪时域能量并存。 */
    public static final ModConfigSpec.ConfigValue<String> PHASOR_NONLINEAR_METHOD = CK
            .comment("非线性相量计算方法",
                     "NONE: 禁用(非线性电气走普通相量+伪时域能量, 现状)",
                     "PIECEWISE: 分段线性化(工作点牛顿迭代, 基波)",
                     "HARMONIC_BALANCE: 谐波平衡法(多谐波+时域采样DFT, 捕获谐波畸变)",
                     "DYNAMIC_PHASOR: 动态相量法(相量包络时间推进, 含L/C包络导数)",
                     "默认NONE")
            .define("phasorNonlinearMethod", "NONE");

    /** 谐波平衡法谐波阶数（含基波；HARMONIC_BALANCE 用） */
    public static final ModConfigSpec.IntValue PHASOR_HARMONICS = CK
            .comment("谐波平衡法谐波阶数(含基波)",
                     "默认5")
            .defineInRange("phasorHarmonics", 5, 1, 32);

    /*
     * =====================================================================
     *  INC（集成网络核心）配置已迁出 → inc 子包（inc.toml）
     *  ⚠ 2026-08-30 用户架构：所有配置放到相关子包（com.hdf.cryptand.neoforge.inc.config.ConfigInc）
     * =====================================================================
     */

    /**
     * 导线超长检测开关（2026-08-30 由 debug 域迁入——用户："导线检测是仿真器子包
     * 的就放仿真器子包"）。WireOverlengthChecker 引用。
     */
    public static final ModConfigSpec.BooleanValue WIRE_OVERLENGTH_ENABLE = CK
            .comment("导线超长检测开关",
                     "true: 打开，服务端周期扫描不存在导线超长(单段>64格)并告警断开",
                     "false: 关闭(默认)")
            .define("wireOverlengthEnable", false);

    /** 导线超长检测间隔（tick）。 */
    public static final ModConfigSpec.IntValue WIRE_OVERLENGTH_CHECK_INTERVAL_TICKS = CK
            .comment("导线超长检测间隔(tick)",
                     "默认20≈1秒扫描一次", "范围1~1200")
            .defineInRange("wireOverlengthCheckIntervalTicks", 20, 1, 1200);

    /**
     * 求解后端精度选择（2026-09-11 用户：后端需同时支持 double 与 float 引擎，
     * 因此本项【保留】——不是可删的引擎特性，而是后端选择）。
     * <p>true → 允许 float 后端（`FloatComplexMnaSolver` / `FloatRealMnaSolver`，精度换速度）；
     * false → double 后端（`ComplexMnaSolver` / `RealMnaSolver`，默认）。
     * 实际选择：`Solvers.floatEnabled && canFloat(net, mode)`（非线性/超范围网络自动回落 double）。
     */
    public static final ModConfigSpec.ConfigValue<String> SOLVER_BACKEND = CK
            .comment("求解后端：直接写名称（double / float；便于扩展其他类型）",
                     "double(默认): ComplexMnaSolver / RealMnaSolver",
                     "float: FloatComplexMnaSolver / FloatRealMnaSolver（精度换速度）",
                     "实际每网络再经 canFloat(net,mode) 判定，不适合的自动回落 double",
                     "未知名称 → 回落 double 并在启动日志告警")
            .define("solverBackend", "double");

    /** 本域 Spec（ConfigLoad 聚合注册）。 */
    public static final ModConfigSpec SPEC = CK.build();

    private ConfigCircuit() {
    }
}