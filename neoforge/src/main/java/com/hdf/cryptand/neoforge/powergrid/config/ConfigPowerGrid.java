package com.hdf.cryptand.neoforge.powergrid.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigPowerGrid —— 交错电网（PowerGrid）支持/接管 + 设备物理参数配置（2026-09-02 按子包拆分）。
 * <p>持有本域全部配置字段定义（ModConfigSpec）与 SPEC；文件路径由 ConfigLoad 总管理
 * （域 DOMAIN="powergrid"）。读取门面 bool/integer/real/string → ConfigLoad.readXxx(DOMAIN, key, def)。
 */
public final class ConfigPowerGrid
        implements CryptandConfigSpec {

    /** 配置域 id（ConfigLoad.domainFileName 映射）。 */
    public static final String DOMAIN = "powergrid";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigPowerGrid INSTANCE = new ConfigPowerGrid();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "powergrid.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    /** 子包自注册：把本配置注册到 core 注册器（core 只提供注册接口，不收集）。 */
    public static void register() {
        CryptandRegistries
                .registerConfig(DOMAIN, "powergrid.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /**
     * 变压器最低通过频率（Hz）。
     *
     * 变压器交流化改造：频率 ≥ 本阈值 → 交流变压（按原副边匝数比）；
     * 频率 &lt; 本阈值（含 0 / 直流 / 低频干扰）→ 隔直（副边完全隔离，无任何能量）。
     * 默认 5Hz。
     */
    /** 变压器通过频率（Hz）：0 = 不带最低频率设置（默认，变压组装器始终按匝数比
     *  交流变压，不隔直）；>0 = 频率低于该值时副边隔直(完全隔离)。 */
    public static final ModConfigSpec.DoubleValue TRANSFORMER_MIN_FREQUENCY_HZ = CK
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
    public static final ModConfigSpec.DoubleValue MOTOR_MAX_DRIVE_FREQUENCY_HZ = CK
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
    public static final ModConfigSpec.IntValue GENERATOR_MOTOR_MAX_RPM = CK
            .comment("发电机/电动机最大转速(RPM)",
                     "转子（发电机/电动机）转速上限，原版默认256",
                     "超过该值会过热并破坏方块",
                     "默认16384")
            .defineInRange("generatorMotorMaxRpm", 16384, 1, 1000000);

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
    public static final ModConfigSpec.DoubleValue MOTOR_LOAD_COUPLING = CK
            .comment("电动机负载耦合系数",
                     "以Create网络速度与转子速度之差施加反作用力(负载反馈闭环)",
                     "空载无反馈; 带载拖慢转子; 反向拖动→再生制动",
                     "设为0禁用负载反馈(原版行为)",
                     "默认1.0")
            .defineInRange("motorLoadCoupling", 1.0, 0.0, 10000.0);

    /*
     * =====================================================================
     *  Cryptand 完全接管/自管仿真配置（2026-08-14 补齐）
     *  ⚠ 以下字段曾被引用但未注册（工作区恢复不完整），默认值按代码语义推断，
     *     如有偏差请用户复核调整。
     * =====================================================================
     */

    /** 原版导线转换开关：true → 转换原版导线进自管 WireGraph 并删除原版实体（完全接管）。 */
    public static final ModConfigSpec.BooleanValue ENABLE_POWERGRID_CONVERSION = CK
            .comment("原版导线转换开关(完全接管)",
                     "true: 转换原版导线进自管WireGraph并删除原版导线实体",
                     "false: 保持原版导线",
                     "需要重启游戏生效")
            .define("enablePowergridConversion", true);

    /** 线圈直流电阻（Ω）：交流化线圈物理参数，用于 Z=√(R²+(2πfL)²)。 */
    public static final ModConfigSpec.DoubleValue COIL_DC_RESISTANCE_OHM = CK
            .comment("线圈直流电阻(Ω)",
                     "交流化线圈物理直流电阻(励磁绕组)",
                     "用于相量电流 Z=√(R²+(2πfL)²) 与温度系数基准",
                     "默认0.45")
            .defineInRange("coilDcResistanceOhm", 0.45, 0.001, 10000.0);

    /** 线圈有效电感（H）：交流化线圈物理电感（5kHz 时 XL≈15708Ω → 0.5H）。 */
    public static final ModConfigSpec.DoubleValue COIL_EFFECTIVE_INDUCTANCE_H = CK
            .comment("线圈有效电感(H)",
                     "交流化线圈物理电感，用于感抗限流",
                     "默认0.5")
            .defineInRange("coilEffectiveInductanceH", 0.5, 0.000001, 10000.0);

    /** 线圈每 tick 最大温升（°C）：温升速率钳制，失真/过载数据渐进温升而非瞬间爆炸。 */
    public static final ModConfigSpec.DoubleValue COIL_MAX_TEMP_RISE_PER_TICK = CK
            .comment("线圈每tick最大温升(°C)",
                     "温升速率钳制(渐进温升防瞬间爆炸)",
                     "默认20")
            .defineInRange("coilMaxTempRisePerTick", 20.0, 0.0, 1000.0);

    /** 线圈电阻温度系数 α：R(T)=R₀×(1+α×(T-22))，铜≈0.0039；0=关闭。 */
    public static final ModConfigSpec.DoubleValue COIL_RESISTANCE_TEMP_COEFF = CK
            .comment("线圈电阻温度系数α",
                     "R(T)=R0*(1+α*(T-22))，铜≈0.0039",
                     "0=关闭温度系数",
                     "默认0.0039")
            .defineInRange("coilResistanceTempCoeff", 0.0039, 0.0, 1.0);

    // ===== 过热爆炸效果（2026-09-12 用户："爆炸全部接管，按照配置来设置爆炸效果"）=====
    // 设备温度超过该温度模型的 maxTemp → 由 PipelinePostProcess.checkDeviceOverheat
    // 判定并销毁（自管爆炸）。原版 ThermalBehaviour 的过热爆炸已随原版温度演化一起
    // 停用；没有自管温度模型的方块因此不会爆炸（用户确认：这是预期行为）。

    /** 过热时产生爆炸效果（威力/破坏/火焰见下方三项；false = 只销毁设备不炸） */
    public static final ModConfigSpec.BooleanValue OVERHEAT_EXPLOSION_ENABLED = CK
            .comment("设备过热时产生爆炸效果",
                     "由自管温度模型判定过热 → 销毁设备并按下方参数爆炸",
                     "false=只销毁设备本身，不出爆炸效果",
                     "默认true")
            .define("overheatExplosionEnabled", true);

    /** 爆炸威力（≈爆炸半径，格）；0 = 只有轻微效果 */
    public static final ModConfigSpec.DoubleValue OVERHEAT_EXPLOSION_POWER = CK
            .comment("过热爆炸威力(≈半径,格)", "默认1.0")
            .defineInRange("overheatExplosionPower", 1.0, 0.0, 16.0);

    /** 爆炸是否破坏周围方块（false = 只有视觉效果，设备本身仍会被销毁） */
    public static final ModConfigSpec.BooleanValue OVERHEAT_EXPLOSION_DESTROY_BLOCKS = CK
            .comment("过热爆炸破坏方块",
                     "false=只有视觉效果，仅销毁过热设备本身",
                     "默认false")
            .define("overheatExplosionDestroyBlocks", false);

    /** 爆炸是否生成火焰 */
    public static final ModConfigSpec.BooleanValue OVERHEAT_EXPLOSION_FIRE = CK
            .comment("过热爆炸生成火焰", "默认false")
            .define("overheatExplosionFire", false);

    /** 原版直流(DC)电气设备最大工作频率（2026-08-22 用户需求：灯/风扇/铃/加热器
     *  等原版设备只支持 DC）。频率 &gt; 该值 → 温度【指数型】惩罚：轻微超出不明显，
     *  频率越高惩罚越严重（按超额倍数 o^3 放大）；0 = 关闭惩罚。默认 3Hz。 */
    public static final ModConfigSpec.DoubleValue DC_DEVICE_MAX_FREQUENCY_HZ = CK
            .comment("原版直流(DC)电气设备最大工作频率(Hz)",
                     "灯/风扇/铃/加热器等原版设备只支持DC: 频率超过该值→温度指数型惩罚",
                     "轻微超出不明显, 频率越高惩罚越严重(按超额倍数指数放大)",
                     "0=关闭惩罚",
                     "默认3")
            .defineInRange("dcDeviceMaxFrequencyHz", 3.0, 0.0, 100000.0);

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
    public static final ModConfigSpec.BooleanValue ENABLE_POWERGRID_SUPPORT = CK
            .comment("交错电网(PowerGrid)支持/接管开关",
                     "true: 用自研仿真核心替换/接管PowerGrid内容(Mixin注入+导线转换)",
                     "false: 完全原版PowerGrid,不注入任何替换Mixin",
                     "仿真核心(enableCryptandSolver)可单独开关",
                     "需要重启游戏生效")
            .define("enablePowergridSupport", true);

    /**
     * 万用表调试模式（2026-08-30 由 debug 域迁入——万用表属交错电网子包）。
     * true → 解除量程限制并显示网络频率等参数；false → 原版行为。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_MULTIMETER_DEBUG = CK
            .comment("万用表调试模式",
                     "true: 解除万用表显示量程限制并显示频率等参数",
                     "false: 万用表原版行为",
                     "需要重启游戏生效")
            .define("enableMultimeterDebug", false);

    /** 网络颜色显示调试开关（2026-08-30 由 debug 域迁入——网络着色属交错电网）。 */
    public static final ModConfigSpec.BooleanValue DEBUG_NETWORK_COLORS = CK
            .comment("网络颜色显示（调试）",
                     "true: 每个网络分配随机颜色，网络内元件方块显示该颜色外框",
                     "默认false")
            .define("debugNetworkColors", false);

    /* 2026-09-13 说明：原先的 `NETWORK_BOX_ALWAYS`（网络方框常驻）已删除 ——
     * 用户最终确定"客户端全量显示网络"由【debug 模式】独家控制：
     *   服务端 NetworkColorSyncMixin 每 10 tick 上传"网络 → 电气设备位置 + 颜色"
     *   （仅 DEBUG_NETWORK_COLORS 开启时），客户端 NetworkColorRenderer 渲染方框
     *   （同一个开关）。关闭时两端都零开销。 */

    /**
     * 自定义电机【应力倍数】（2026-09-13 用户："自定义电机需要通过公式来计算扭矩和
     * 速度，应力为可配置倍数×扭矩，反馈也是需要除以倍数"）。
     *
     * 语义：输出应力 σ[SU] = |T[N·m]| × 16 × 本倍数；反向（BE 上报的应力 → 负载
     * 转矩）除以同一倍数 ⇒ 对称可逆。默认 300（额定 σ≈15805SU，对齐 Create 16384
     * 满刻度量级）。
     */
    public static final ModConfigSpec.DoubleValue MOTOR_STRESS_SCALE = CK
            .comment("自定义电机应力倍数",
                     "应力 SU = |扭矩 N·m| × 16 × 本倍数；反馈按同一倍数还原为扭矩",
                     "默认 300",
                     "需要重启游戏生效")
            .defineInRange("motorStressScale", 300.0, 1.0, 100000.0);

    /** 本域 Spec（ConfigLoad 聚合注册）。 */
    public static final ModConfigSpec SPEC = CK.build();

    private ConfigPowerGrid() {
    }
}