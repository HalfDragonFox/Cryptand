package com.hdf.cryptand.neoforge.aeronautics.config;

import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigAero —— Aeronautics（飞艇/轮子物理）配置（2026-09-02 按子包拆分）。
 * <p>持有本域全部配置字段定义（ModConfigSpec）与 SPEC；文件路径由 ConfigLoad 总管理
 * （域 DOMAIN="aero"）。读取门面 bool/integer/real/string → ConfigLoad.readXxx(DOMAIN, key, def)。
 */
public final class ConfigAero
        implements CryptandConfigSpec {

    /** 配置域 id（ConfigLoad.domainFileName 映射）。 */
    public static final String DOMAIN = "aero";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigAero INSTANCE = new ConfigAero();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "aeronautics.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    /** 子包自注册：把本配置注册到 core 注册器（core 只提供注册接口，不收集）。 */
    public static void register() {
        CryptandRegistries
                .registerConfig(DOMAIN, "aeronautics.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

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
    public static final ModConfigSpec.BooleanValue ENABLE_AERO_WHEEL_STRESS_FRICTION = CK
            .comment("Aeronautics轮子摩擦应力物理模型开关",
                     "true: 轮子应力消耗按实际摩擦力(摩擦系数x法向承载质量x速度)计算",
                     "false: 恢复原版静态stressImpact",
                     "需要重启游戏生效")
            .define("enableAeroWheelStressFriction", true);



    /** 轮子基础系数（每转速 SU；WheelStressAccess 引用；SU = base×|rpm| + N×wkg×|rpm|） */
    public static final ModConfigSpec.DoubleValue AERO_WHEEL_BASE_IMPACT = CK
            .comment("轮子基础系数(每转速SU)",
                     "SU=(baseImpact+载重kg×weightPerKg)×|rpm|",
                     "默认16=原版1转速16应力")
            .defineInRange("aeroWheelBaseImpact", 16.0, 0.0, 1.0E6);

    /** 每 kg 载荷额外系数（WheelStressAccess 引用；默认 0.01 = 100kg → 系数+1） */
    public static final ModConfigSpec.DoubleValue AERO_WHEEL_WEIGHT_PER_KG = CK
            .comment("每kg载荷额外系数",
                     "SU=(baseImpact+载重kg×weightPerKg)×|rpm|",
                     "默认0.01=100kg→系数+1")
            .defineInRange("aeroWheelWeightPerKg", 0.01, 0.0, 1.0E6);



    /**
     * 外设拉杆是否启用（默认 false）。与船舵同样的【强制校验】：未同步开启游戏输入库 → 报错。
     */
    public static boolean peripheralLeverEnabled() {
        return peripheralsEnabled();
    }



    /** 外设模拟传动器是否启用（统一委托到外设总开关） */
    public static boolean peripheralTransmissionEnabled() {
        return peripheralsEnabled();
    }

    /** 外设模拟传动器（按键）是否启用（统一委托到外设总开关） */
    public static boolean peripheralTransmissionKeyEnabled() {
        return peripheralsEnabled();
    }

    /** 外设栏杆是否启用（统一委托到外设总开关） */
    public static boolean peripheralRailingEnabled() {
        return peripheralsEnabled();
    }

    // ===== 外设会话（2026-09-13 用户定稿：客户端主动端 / 服务端被动端）=====

    /**
     * 客户端<b>异步核心</b>执行频率（Hz）。
     * <p>外设的全部计算都在客户端异步核心线程里完成：每个周期<b>串行遍历</b>所有外设方块，
     * 走"接收处理 → 计算 → 发送"流水线。默认 40 = 每秒 40 次（与上行频率解耦：
     * 核心跑 40Hz，上行默认 20Hz，服务端再按自己的上限限流）。
     */
    public static final ModConfigSpec.IntValue PERIPHERAL_CLIENT_CORE_HZ = CK
            .comment("外设客户端异步核心频率(Hz)",
                     "客户端异步核心线程每秒执行次数：串行遍历所有外设方块做 接收→计算→发送",
                     "默认40（上行频率由 peripheralClientSendHz 单独控制）",
                     "需要重启游戏生效")
            .defineInRange("peripheralClientCoreHz", 40, 1, 200);

    /**
     * 客户端上行频率（Hz，每个外设方块）。
     * <p>客户端是主动端：外设计算全在客户端完成，按此频率往服务端发数据或空包（保活会话）。
     * 默认 20 = 每 tick 一次。
     */
    public static final ModConfigSpec.IntValue PERIPHERAL_CLIENT_SEND_HZ = CK
            .comment("外设会话上行【最高速率】(Hz/方块)",
                     "数据有变化时（含变化后的加速窗口内）按此速率上行，默认20 = 每tick一次",
                     "⚠ 值一旦真的变化会【立刻】发一包，不等这个间隔",
                     "没有变化时改用 peripheralClientIdleHz 的普通速率保活",
                     "需要重启游戏生效")
            .defineInRange("peripheralClientSendHz", 20, 1, 100);

    /**
     * 客户端上行【普通速率】（Hz/方块，无变化时的保活节奏）。
     * <p>用户 2026-09-14 定稿："添加普通速率，比如 20hz 下，正常按照 4hz 传输一次，然后一旦数据
     * 有更新就立马加速到最快（如果更新很快的话）就按照 20tick，保证一有数据立马传输，保证实时性。"
     */
    public static final ModConfigSpec.IntValue PERIPHERAL_CLIENT_IDLE_HZ = CK
            .comment("外设会话上行【普通速率】(Hz/方块)",
                     "没有数据变化时的稳态速率，默认2 = 每秒2次（省流量）",
                     "一旦数据有更新：立刻发一包 + 在加速窗口内改用 peripheralClientSendHz 最高速率",
                     "0 = 纯事件驱动：稳态一个字都不发，只在参数更新时上传",
                     "需要重启游戏生效")
            .defineInRange("peripheralClientIdleHz", 2, 0, 100);

    /** 上行【加速保持时长】（ms）：数据变化后维持最高速率的时长，连续变化会不断续期。 */
    public static final ModConfigSpec.IntValue PERIPHERAL_CLIENT_BURST_HOLD_MS = CK
            .comment("外设上行【加速保持时长】(ms)",
                     "数据变化后保持最高速率的时长；连续变化会不断续期（保证实时性）",
                     "默认500；0 = 只有变化那一包走快车道",
                     "需要重启游戏生效")
            .defineInRange("peripheralClientBurstHoldMs", 500, 0, 10000);

    /**
     * 服务端接收上限（Hz/玩家）。
     * <p>服务端是被动端：只维护会话窗口 + 限流 + 回 sable 数据。
     * 客户端发得比这个快时，<b>多余的包直接丢弃</b>（不排队、不反压）。
     */
    public static final ModConfigSpec.IntValue PERIPHERAL_SERVER_RECV_HZ = CK
            .comment("外设会话接收上限(Hz/方块)",
                     "服务端是被动端：每个外设方块每秒最多接收这么多上行包，超出的直接丢弃",
                     "⚠ 按【方块】独立计数（不是按玩家总额）——各方块互不挤占，与客户端每方块上行对齐",
                     "默认20",
                     "需要重启游戏生效")
            .defineInRange("peripheralServerRecvHz", 20, 1, 100);

    /** 客户端异步核心频率（运行期读；配置未加载时用默认 40）。 */
    public static int peripheralClientCoreHz() {
        try {
            return SPEC.isLoaded() ? PERIPHERAL_CLIENT_CORE_HZ.get() : 40;
        } catch (final Throwable ignored) {
            return 40;
        }
    }

    /** 客户端上行【最高速率】（运行期读；配置未加载时用默认 20）。 */
    public static int peripheralClientSendHz() {
        try {
            return SPEC.isLoaded() ? PERIPHERAL_CLIENT_SEND_HZ.get() : 20;
        } catch (final Throwable ignored) {
            return 20;
        }
    }

    /** 客户端上行【普通速率】（运行期读；配置未加载时用默认 2；0 = 只在参数更新时上传）。 */
    public static int peripheralClientIdleHz() {
        try {
            return SPEC.isLoaded() ? PERIPHERAL_CLIENT_IDLE_HZ.get() : 2;
        } catch (final Throwable ignored) {
            return 2;
        }
    }

    /** 上行【加速保持时长】ms（运行期读；配置未加载时用默认 500）。 */
    public static long peripheralClientBurstHoldMs() {
        try {
            return SPEC.isLoaded() ? PERIPHERAL_CLIENT_BURST_HOLD_MS.get() : 500;
        } catch (final Throwable ignored) {
            return 500;
        }
    }

    /** 服务端接收上限（运行期读；配置未加载时用默认 20）。 */
    public static int peripheralServerRecvHz() {
        try {
            return SPEC.isLoaded() ? PERIPHERAL_SERVER_RECV_HZ.get() : 20;
        } catch (final Throwable ignored) {
            return 20;
        }
    }

    /** 本域 Spec（ConfigLoad 聚合注册）。 */
    public static final ModConfigSpec SPEC = CK.build();

    /**
     * 库内【游戏输入】后端是否开启（gameinput.toml#enableGameInput；默认 true）。
     * <p>用 ConfigLoad 预读（不引用 gameinput 子包类——保持子包可删除/可摘除）。
     */
    public static boolean gameInputEnabled() {
        try {
            return ConfigLoad.preloadBoolean("gameinput", "enableGameInput", true);
        } catch (final Throwable ignored) {
            return true;
        }
    }

    /**
     * 外设船舵是否启用（默认 false）。开启时【强制校验】gameinput 依赖：
     * 未同步开启游戏输入库 → 打印 ERROR 并抛异常（用户要求："开启时需要同步开启
     * 我们库内的外设相关的库，否则会报错"）。
     *
     * @return true = 应注册外设船舵内容
     * @throws IllegalStateException enablePeripherals=true 但 enableGameInput=false
     */
    public static boolean peripheralHelmEnabled() {
        return peripheralsEnabled();
    }

    /**
     * ===== 外设支持开关（2026-09-14 用户定稿：外设统一使用一个配置）=====
     * <p>
     * 原先船舵 / 拉杆 / 栏杆（按钮）/ 模拟传动器各有独立开关，现已合并为这一个总开关。
     * <p>
     * true  → 一次性注册全部外设方块（内容各自仍受其依赖可用性约束）；
     * false（默认）→ 全部不注册。
     * <p>
     * ⚠ 全部外设都依赖库内【游戏输入】子包（config/cryptand/gameinput.toml#enableGameInput=true）：
     *   未同步开启 → 构造期直接报错，不静默降级。
     * <p>
     * ⚠ 外设模拟传动器额外依赖航空学（modid simulated，展示名 Create: Aeronautics）的
     *   ExtraKinetics 双 KBE 机制 —— 该 mod 不在时其方块无法加载。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_PERIPHERALS = CK
            .comment("外设支持开关（默认关闭）",
                     "true: 注册全部外设（船舵 / 拉杆 / 栏杆（按钮） / 模拟传动器）",
                     "false(默认): 全部不注册",
                     "!! 必须同时开启 gameinput 库: gameinput.toml#enableGameInput=true",
                     "需要重启游戏生效")
            .define("enablePeripherals", false);

    /**
     * 外设总开关判定（四个外设的 XXXEnabled() 全部委托到这里）。
     * <p>依赖缺失时抛 IllegalStateException（用户要求"开启时需要同步开启我们库内的外设相关的库，否则会报错"）。
     */
    public static boolean peripheralsEnabled() {
        boolean enabled;
        try {
            enabled = SPEC.isLoaded()
                    ? ENABLE_PERIPHERALS.get()
                    : ConfigLoad.preloadBoolean("aero", "enablePeripherals", false);
        } catch (final Throwable ignored) {
            enabled = false;
        }
        if (!enabled) {
            return false;
        }
        if (!gameInputEnabled()) {
            final String msg = "[外设] 配置错误：aeronautics.toml#enablePeripherals=true，"
                    + "但库内游戏输入后端未开启（config/cryptand/gameinput.toml#enableGameInput=false）。"
                    + "全部外设的输入源都是 gameinput 库——请同步开启 enableGameInput，"
                    + "或把 enablePeripherals 改回 false。";
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(msg);
            throw new IllegalStateException(msg);
        }
        return true;
    }
    private ConfigAero() {
    }
}
