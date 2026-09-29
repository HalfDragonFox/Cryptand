package com.hdf.cryptand.neoforge.sable.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Sable 联动 + mixin 层配置门面（2026-09-02：主题 ConfigSable 按代码子包细化后，
 * 保留本类于 {@code com.hdf.cryptand.neoforge.sable} 联动 + mixin 层）。
 *
 * <p>对应代码子包 {@code com.hdf.cryptand.neoforge.sable}（Sable 联动坐标迁移 /
 * 引擎精度 / 各种 timing / 异步物理 mixin）。统一读写 {@code config/cryptand/sable.toml}——
 * 文件路径由 {@link ConfigLoad} 总管理（配置域 "sable"），本类只提供按 key 的读取 API，
 * 供类加载阶段的 mixin/plugin 与运行期使用（ModConfigSpec 未 build 时也能读）。
 *
 * <p>CryptandSable 核心字段已拆分到 {@link ConfigCryptandSable}、官方 Sable 兼容
 * 字段拆分到 {@link ConfigCryptandSableCompat}；语义化常量（spec 已加载）走
 * {@link ConfigLoad}（保留存量引用兼容）；新代码/类加载阶段推荐用本类的
 * {@link #bool}/{@link #integer}/{@link #real}/{@link #string}。
 */
public final class ConfigSable
        implements CryptandConfigSpec {

    /** 配置域 id（ConfigLoad.domainFileName 映射到 sable.toml）。 */
    public static final String DOMAIN = "sable";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigSable INSTANCE = new ConfigSable();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "sable.toml"; }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() { return SPEC; }

    /** 子包自注册：把本配置注册到 core 注册器（core 只提供注册接口，不收集）。 */
    public static void register() {
        CryptandRegistries
                .registerConfig(DOMAIN, "sable.toml", SPEC);
    }

    /** 本域字段构建器（build 前定义，见 ConfigLoad 2026-08-15 崩溃教训：build 必须放全部字段之后）。 */
    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    // =====================================================================
    //  Sable 联动 + mixin 层配置（cryptand/sable.toml，2026-08-30 用户）
    // =====================================================================

    /**
     * Sable（航空学物理亚层）联动总开关（2026-08-30 用户："配置选项增加sable的支持"）。
     * <p>
     * true  → 启用 Cryptand 与 Sable（Create: Aeronautics 的物理化核心）的联动：
     *         物理化装配时网络端点坐标迁移（SableAssemblyMoveMixin）、取消物理化
     *         时端点回迁（SableSubLevelObserver）等——飞艇/移动船体上的电力设备
     *         随船移动而不断网。仅当 Sable mod 已安装时生效（未装自动跳过）。
     * false → 不做任何坐标迁移/观察者注册（Sable 物理化时端点坐标保持世界原值，
     *         移动船体上的网络将不再跟随）。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */

    /**
     * Sable 物理引擎 64 位（Rapier64）开关（2026-08-30 用户：
     * "加一个是否开启64位引擎的配置来替换原本32位"）。
     * <p>
     * Sable 的物理后端是嵌入的 Rapier 3D（Rust JNI natives，f32 默认）。
     * Rapier64 = 用 64 位浮点（rapier3d-f64）编译的原生 DLL——大船/远离原点
     * 坐标下数值更稳、不抖不爆（代价：内存翻倍、约 30% 性能损耗）。
     * <p>
     * true  → 期望加载 64 位引擎 natives（Rapier64 jar，见 .ai_cache/
     *         sable_rapier64/ 构建产物）；启动时 Cryptand 会校验并记录
     *         实际生效的引擎精度（日志/告警），不匹配时提示但不崩溃。
     * false → 使用官方默认 32 位引擎 natives（f32）。
     * <p>⚠ 服务端与客户端必须一致（浮点精度不同的引擎间物理不同步）。
     * 需要重启游戏生效（原生库加载为类加载阶段决定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_RAPIER64 = CK
            .comment("Sable物理引擎64位(Rapier64)开关",
                     "true: 期望使用64位浮点(native DLL)编译的Rapier引擎,大船/远坐标更稳",
                     "      代价:内存翻倍+约30%性能损耗; 需安装Rapier64版sable natives",
                     "false: 使用官方默认32位引擎(f32)",
                     "需要重启游戏生效; 服务端与客户端必须一致")
            .define("enableSableRapier64", false);

    /**
     * Sable 体素烘焙计时探针（2026-08-30 评估"主线程预快照方案"前先量化用）。
     * <p>
     * 给 {@code RapierPhysicsPipeline.handleChunkSectionAddition}（超大结构
     * 装配/加载时的 4096 格体素烘焙）加 HEAD/TAIL 计时日志：
     *   [SableChunkTiming] chunk(x,y,z) total=XX.XX ms (bake exec)
     * 用于判断②（MC 读 BlockState/邻域）与③（碰撞形状烘焙/缓存查询）占比，
     * 决定并行化/预快照方案是否值得。纯测量开关，不改变任何行为。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    /**
     * Sable 物理 tick 计时探针（2026-08-30 评估"重叠/卡土里"卡顿用）。
     * <p>
     * 注入 {@code SubLevelPhysicsSystem.tickPipelinePhysics} HEAD/TAIL，包住
     * 整个物理循环（所有子层 × 所有 substeps），输出总耗时：
     *   [SableTiming] PhysTick dt=XX.XX ms
     * 用于验证"重叠时主线程几千 ms"是否来自物理求解（与渲染探针交叉）。
     * 纯测量，不改变行为。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_PHYSICS_TIMING = CK
            .comment("Sable物理tick计时探针(评估用)",
                     "true: 打印整个物理循环(所有子层x所有substeps)耗时日志",
                     "      ([SableTiming] PhysTick dt=XXms), 验证重叠卡顿是否来自物理",
                     "false: 关闭(默认), 零开销",
                     "需要重启游戏生效")
            .define("enableSablePhysicsTiming", false);

    /**
     * Sable 渲染帧计时探针（2026-08-30 评估"渲染压力大/FPS低"用）。
     * <p>
     * 注入 {@code GameRenderer.renderLevel} HEAD/TAIL（仅客户端），输出：
     *   [SableTiming] Render ms=XX fps=XX frameGap=XXms [SLOW]
     * - ms: renderLevel 自身耗时 → 渲染管线压力
     * - fps: 当前帧率
     * - frameGap: 帧间隔（ms 高但渲染耗时低 → 物理/主线程阻塞）
     * 用于判断 FPS 低是【渲染压力】还是【物理阻塞主线程】。
     * 纯测量，不改变行为。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_RENDER_TIMING = CK
            .comment("Sable渲染帧计时探针(评估用,仅客户端)",
                     "true: 打印renderLevel渲染耗时+当前FPS+帧间隔",
                     "      ([SableTiming] Render ms=XX fps=XX frameGap=XXms),",
                     "      判断FPS低是渲染压力还是物理阻塞主线程",
                     "false: 关闭(默认), 零开销",
                     "需要重启游戏生效")
            .define("enableSableRenderTiming", false);

    /**
     * 动态迭代（2026-08-30 用户：重叠时走小迭代，其余正常迭代）。
     * <p>
     * 核心问题：物理化大结构/重叠时 Rapier 求解迭代次数（solverIterations=18）
     * 每次 tick 全量跑 → 物理 1s+（重叠时接触数×迭代爆炸）。
     * 开启后：每次 tick 前检测【亚层间 AABB 相交（重叠/卡土里）】：
     *   - 检测到重叠 → 迭代降到 {@link #SABLE_OVERLAP_ITERATIONS}（小迭代，快速松解）；
     *   - 无重叠 → 用用户在主界面节点/命令设置的 solverIterations（正常迭代）。
     * 每 tick 动态切换（重叠解除自动恢复），默认关闭（不影响官方行为）。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    /**
     * 重叠时的小迭代数（配合 enableSableDynamicIteration=true；默认 6）。
     */
    /**
     * 异步物理（2026-08-30 用户：参考仿真引擎，主线程只发消息）。
     * <p>
     * 核心问题：物理化大结构/重叠时 Rapier step 在主线程跑 1s+（2s/tick）。
     * 开启后：native 物理 step（Rapier3D.step）从主线程移到
     * ThreadDispatchers 的 PHYSICS_HIGH（常驻平台线程直算 —— JNI 回调需要平台
     * 线程；虚拟线程不能 attach JNI）。主线程只提交 + 消费结果，不等待。
     * <p>
     * 已知代价：物理 tick 滞后 1 tick（50ms 帧延迟）；JNI 回调读 belt 速度可能
     * 轻微过期。关闭（默认）→ 官方主线程物理，零行为变化。
     * <p>需要重启游戏生效（Mixin 注入为类加载阶段决定）。
     */
    /**
     * 物理 step 超时（2026-08-30 用户：默认 10s，可配置 ms）。
     * 超时 → 放弃此 step 处理并重置状态（重新开始）；【绝不】兜底回原版（主线程同步物理）。
     */
    /**
     * 异步物理 Hz（2026-08-30：物理核心解耦，Hz 可配，默认 20/s = 原主 tick 频率）。
     */
    /**
     * 异步物理每 tick 计算配额（2026-08-30：时钟机制，主线程每 tick 推进多少次计算，
     * 防止主线程超时）。worker 按配额执行物理计算（配额耗尽等下一主 tick 时钟）。");
     */
    private ConfigSable() {
    }

    /**
     * 官方 Sable 集成总开关（2026-08-30 由 core 域迁入 sable 子包——用户：所有配置
     * 放到相关子包）。
     * <p>消费方：sable 子包自身（SableEntry.enabled）；依赖方（cryptandsable /
     * cryptandsable_compat）在 {@code dependencies()} 声明 "sable" 由加载器处理；
     * cee×sable 联动、mixin plugin 等【运行时】经
     * {@code SubpackageRegistry.isEnabled("sable")} 查询（不直接引用本类）。
     * <p>false = 不做任何官方 Sable 联动。需要重启游戏生效。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_SUPPORT = CK
            .comment("官方Sable集成总开关",
                     "true(默认): 启用与官方Sable的联动(坐标迁移/端点回迁/亚层观察者等)",
                     "false: 不做任何官方Sable联动",
                     "需要重启游戏生效")
            .define("enableSableSupport", true);

    // =====================================================================
    //  力学可视化（2026-09-14 用户：KSP 风格球 + 力箭头）
    //  数据流：客户端主动请求 → 服务端被动响应（服务端永远不主动广播）
    // =====================================================================

    /**
     * 力学可视化总开关（服务端与客户端【同一个选项】）。
     * <p>服务端：false → 收到请求只回 supported=false，不采集、不打开逐点力记录（零开销）；
     * true → 被动响应客户端请求，采集请求者附近结构的力并回复，同时对"有人要看"的结构
     * 打开官方 {@code enableIndividualQueuedForcesTracking}（请求停止 8s 后自动关闭）。
     * <p>客户端：false → 不请求、不渲染；true → 主动按间隔拉取并渲染（需服务端同样为 true）。
     * <p>默认 false —— 方便服务器不开此接口；单人游戏或需要显示的服务器设为 true 即可。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_FORCE_DISPLAY = CK
            .comment("Sable力学可视化总开关(服务端与客户端同一选项)",
                     "服务端: false(默认)=不提供力数据接口, 客户端请求一律回'不支持', 零开销",
                     "         true=被动响应客户端请求, 采集其附近结构的力并回复",
                     "客户端: false=不请求不渲染; true=主动拉取并渲染(需服务端同样为true)",
                     "单人游戏: 设为 true 即可")
            .define("enableSableForceDisplay", false);

    /** 服务端：只采集该距离内的结构（格）。 */
    public static final ModConfigSpec.IntValue SABLE_FORCE_DISPLAY_MAX_DISTANCE = CK
            .comment("力学可视化: 服务端采集距离上限(格)", "默认64")
            .defineInRange("sableForceDisplayMaxDistance", 64, 4, 512);

    /** 服务端：单次响应最多包含的结构数。 */
    public static final ModConfigSpec.IntValue SABLE_FORCE_DISPLAY_MAX_STRUCTURES = CK
            .comment("力学可视化: 单次响应最多结构数", "默认8")
            .defineInRange("sableForceDisplayMaxStructures", 8, 1, 64);

    /** 服务端：每结构最多力样本数（防包爆炸）。 */
    public static final ModConfigSpec.IntValue SABLE_FORCE_DISPLAY_MAX_POINTS = CK
            .comment("力学可视化: 每结构力样本上限(详细模式防包爆炸)", "默认64")
            .defineInRange("sableForceDisplayMaxPoints", 64, 1, 1024);

    /** 服务端：是否允许客户端请求详细模式（点力）。 */
    public static final ModConfigSpec.BooleanValue SABLE_FORCE_DISPLAY_ALLOW_DETAILED = CK
            .comment("力学可视化: 服务端是否允许详细模式(点力)请求", "默认true")
            .define("sableForceDisplayAllowDetailed", true);

    /** 客户端：详细模式（每个力源一个小球 + 箭头）——false = 简化模式（每力组一个球 + 总力箭头）。 */
    public static final ModConfigSpec.BooleanValue SABLE_FORCE_DISPLAY_DETAILED = CK
            .comment("力学可视化: 详细模式",
                     "true: 每个力源(螺旋桨/悬浮簇…)一个小球+箭头",
                     "false(默认): 简化 —— 每个力组一个球(力中心)+总力箭头, 另加质心球")
            .define("sableForceDisplayDetailed", false);

    /** 客户端：与 F3+B（碰撞箱）联动显示。 */
    public static final ModConfigSpec.BooleanValue SABLE_FORCE_DISPLAY_FOLLOW_HITBOX = CK
            .comment("力学可视化: 与F3+B(碰撞箱显示)联动", "默认true: 开碰撞箱时同时显示力")
            .define("sableForceDisplayFollowHitbox", true);

    /** 客户端：强制显示（忽略 F3+B 与联动开关）。 */
    public static final ModConfigSpec.BooleanValue SABLE_FORCE_DISPLAY_FORCE_SHOW = CK
            .comment("力学可视化: 强制显示(忽略F3+B)", "默认false")
            .define("sableForceDisplayForceShow", false);

    /** 客户端：请求间隔（tick；20 = 1 秒一次，一般 1s 一次即可）。 */
    public static final ModConfigSpec.IntValue SABLE_FORCE_DISPLAY_REQUEST_INTERVAL = CK
            .comment("力学可视化: 客户端请求间隔(tick)",
                     "默认20 (=1秒一次; 力显示不需要更高频率)")
            .defineInRange("sableForceDisplayRequestInterval", 20, 2, 100);

    /**
     * 【每会话】每秒请求上限（服务端；按玩家会话独立计数）。
     * <p>超限的请求被【静默丢弃】（不回包、不报错）—— 客户端保持自己的 1s 节奏即可，
     * 恶意刷包由这一项兜住。多玩家各自独立计数，互不影响。
     */
    public static final ModConfigSpec.IntValue SABLE_FORCE_DISPLAY_MAX_REQUESTS_PER_SECOND = CK
            .comment("力学可视化: 每个客户端会话每秒请求上限(服务端)",
                     "默认1 (=与客户端1s一次匹配); 超限静默丢弃",
                     "多玩家各自独立计数")
            .defineInRange("sableForceDisplayMaxRequestsPerSecond", 1, 1, 20);

    /** 客户端：箭头长度缩放。 */
    public static final ModConfigSpec.DoubleValue SABLE_FORCE_DISPLAY_ARROW_SCALE = CK
            .comment("力学可视化: 箭头长度缩放", "默认1.0")
            .defineInRange("sableForceDisplayArrowScale", 1.0D, 0.1D, 8.0D);

    /**
     * 颜色覆盖表（注册式）：{@code "力id=0xRRGGBB"}，如 {@code "sable:lift=0x7FD8E8"}。
     * <p>未列出的力用框架内置默认色（重力黄/气动粉/浮力蓝/升力青/推进紫…）；
     * 其它 mod 注册的力也能在此覆盖。
     */
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> SABLE_FORCE_DISPLAY_COLOR_OVERRIDES = CK
            .comment("力学可视化: 颜色覆盖表(每项 '力id=0xRRGGBB', 如 sable:lift=0x7FD8E8)")
            .defineList("sableForceDisplayColorOverrides", java.util.List.of(), o -> o instanceof String);

    // =====================================================================
    //  轮胎拟真（2026-09-14 用户："现在打开拟真的轮胎开关"）
    //  参数为【归一化】形式（官方 TireLike 无胎宽/胎压/刚度数据源，见
    //  ai_memory/repo/sable-tire-simulation-constraints.md C2）
    // =====================================================================

    /**
     * 轮胎拟真总开关（默认 **true** —— 用户要求打开）。
     * <p>true → 用真实轮胎模型（滑移率/侧偏角 + 摩擦圆）修正 offroad 轮子的摩擦与驱动分量
     * （悬挂力保持官方实现不变）；false → 完全恢复官方简化模型（线性阻尼 + 转速开环驱动）。
     * <p>不影响应力账本（SU 仍走独立路径）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_TIRE_REALISM = CK
            .comment("轮胎拟真总开关",
                     "true(默认): 用真实轮胎模型(滑移率/侧偏角/摩擦圆)修正轮子摩擦与驱动",
                     "false: 恢复官方简化模型(线性阻尼+转速开环)",
                     "悬挂力保持官方实现; 应力账本不受影响")
            .define("enableSableTireRealism", true);

    /** 轮胎模型 id（注册式：见 TireRegistry；默认内置线性饱和模型）。 */
    public static final ModConfigSpec.ConfigValue<String> SABLE_TIRE_MODEL = CK
            .comment("轮胎模型 id(注册式)", "默认 cryptand:linear_saturation")
            .define("sableTireModel", "cryptand:linear_saturation");

    /** 侧向刚度系数 Cy（1/rad；Fy = −Cy·曲线(α)·N）。 */
    public static final ModConfigSpec.DoubleValue SABLE_TIRE_LATERAL_STIFFNESS = CK
            .comment("轮胎侧向刚度系数Cy(1/rad)", "默认6.0; 越大越咬地, 但更容易突然失稳")
            .defineInRange("sableTireLateralStiffness", 6.0D, 0.1D, 60.0D);

    /** 纵向刚度系数 Cx（1/滑移率；Fx = Cx·曲线(κ)·N）。 */
    public static final ModConfigSpec.DoubleValue SABLE_TIRE_LONGITUDINAL_STIFFNESS = CK
            .comment("轮胎纵向刚度系数Cx", "默认4.0")
            .defineInRange("sableTireLongitudinalStiffness", 4.0D, 0.1D, 60.0D);

    /** 峰值侧偏角（度）：超过后力按 peak/α 衰减（抓地饱和）。 */
    public static final ModConfigSpec.DoubleValue SABLE_TIRE_PEAK_SLIP_ANGLE_DEG = CK
            .comment("轮胎峰值侧偏角(度)", "默认8.0; 超过后侧向力衰减(打滑)")
            .defineInRange("sableTirePeakSlipAngleDeg", 8.0D, 0.5D, 45.0D);

    /** 峰值滑移率：超过后纵向力衰减（空转/抱死饱和）。 */
    public static final ModConfigSpec.DoubleValue SABLE_TIRE_PEAK_SLIP_RATIO = CK
            .comment("轮胎峰值滑移率", "默认0.15; 超过后纵向力衰减")
            .defineInRange("sableTirePeakSlipRatio", 0.15D, 0.01D, 1.0D);

    /** 滚阻系数（F = Crr·N，与纵向速度反向）。 */
    public static final ModConfigSpec.DoubleValue SABLE_TIRE_ROLLING_RESISTANCE = CK
            .comment("轮胎滚阻系数", "默认0.015")
            .defineInRange("sableTireRollingResistance", 0.015D, 0.0D, 0.5D);

    /** μ 修正（× 地面摩擦系数）。 */
    public static final ModConfigSpec.DoubleValue SABLE_TIRE_MU_SCALE = CK
            .comment("轮胎μ修正(×地面摩擦系数)", "默认1.0")
            .defineInRange("sableTireMuScale", 1.0D, 0.05D, 5.0D);

    /** 轮胎拟真诊断日志（每 N 次打印 κ/α/N/μ/力）。 */
    public static final ModConfigSpec.BooleanValue SABLE_TIRE_DEBUG = CK
            .comment("轮胎拟真诊断日志", "默认false; 开启后打印 [Tire] κ/α/N/μ/Fx/Fy")
            .define("sableTireDebug", false);

    /**
     * 轮胎拟真时：SU 应力是否切到【真实摩擦功率】（用户 2026-09-14 拍板：切）。
     * <p>true → `SU = P / sableTireWattPerSU`（P = |Fx·vx| + |Fy·vy|，W；来自轮胎模型）；
     * 功率缓存未命中（未跑轮胎拟真 / 结构无轮子）→ 自动回退原 v9 公式。
     * <p>false → 始终用 v9 公式（系数 × |RPM|）。
     */
    public static final ModConfigSpec.BooleanValue SABLE_TIRE_SU_FROM_FRICTION = CK
            .comment("轮胎拟真: SU应力切真实摩擦功率",
                     "true(默认): SU = 摩擦功率P / wattPerSU (更真实, 需标定)",
                     "false: 用原v9公式(系数×|RPM|)")
            .define("sableTireSuFromFriction", true);

    /**
     * 功率→应力标定：每 1 SU 对应多少瓦（W/SU）。
     * <p>⚠ 标定项：太小 → 永远不超载（应力机制失效）；太大 → 一动就超载降速。
     * 默认 0.2（≈ 每 800 W 得 4000 SU，与原版 256RPM 基线同量级，按典型载重估算）。
     */
    public static final ModConfigSpec.DoubleValue SABLE_TIRE_WATT_PER_SU = CK
            .comment("轮胎拟真: 功率→应力标定(W per SU)",
                     "默认0.2; 调大=更容易超载降速, 调小=更不容易",
                     "参考: 1000kg 载重 5m/s 巡航约 700~900W")
            .defineInRange("sableTireWattPerSU", 0.2D, 0.001D, 100.0D);

    /** 力显示诊断日志（服务端：每次响应的样本构成；客户端：渲染统计，节流）。 */
    public static final ModConfigSpec.BooleanValue SABLE_FORCE_DISPLAY_DEBUG = CK
            .comment("力学可视化诊断日志",
                     "true: 服务端打印每次响应的结构与样本构成; 客户端打印渲染球/箭头数(节流)",
                     "false(默认)")
            .define("sableForceDisplayDebug", false);

    /** 本域 Spec（ConfigLoad 聚合注册）。 */
    public static final ModConfigSpec SPEC = CK.build();
}