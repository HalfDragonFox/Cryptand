package com.hdf.cryptand.neoforge.cryptandsable.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * CryptandSable 核心子包配置门面（2026-09-02 从 ConfigSable 拆出：/cryptandsable 核心）。
 *
 * <p>对应代码子包 {@code com.hdf.cryptand.neoforge.cryptandsable}（CryptandSable 独立
 * 物理核心）。统一读写 {@code config/cryptand/cryptand-sable.toml}——文件路径由
 * {@link ConfigLoad} 总管理（配置域 "cryptandsable"），本类只提供按 key 的读取 API，
 * 供类加载阶段的 mixin/plugin 与运行期使用（ModConfigSpec 未 build 时也能读）。
 *
 * <p>语义化常量（spec 已加载）走 {@link ConfigLoad}（保留存量引用兼容）；
 * 新代码/类加载阶段推荐用本类的 {@link #bool}/{@link #integer}/{@link #real}/{@link #string}。
 */
public final class ConfigCryptandSable
        implements CryptandConfigSpec {

    /** 配置域 id（ConfigLoad.domainFileName 映射到 cryptand-sable.toml）。 */
    public static final String DOMAIN = "cryptandsable";

    /** 接口实例（子包自治注册用）。 */
    public static final ConfigCryptandSable INSTANCE = new ConfigCryptandSable();

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public String fileName() {
        return "cryptand-sable.toml";
    }

    @Override
    public net.neoforged.neoforge.common.ModConfigSpec spec() {
        return SPEC;
    }

    /** 子包自注册：把本配置注册到 core 注册器（core 只提供注册接口，不收集）。 */
    public static void register() {
        CryptandRegistries
                .registerConfig(DOMAIN, "cryptand-sable.toml", SPEC);
    }

    /** 本域字段构建器（build 前定义，见 ConfigLoad 2026-08-15 崩溃教训：build 必须放全部字段之后）。 */
    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    // =====================================================================
    //  CryptandSable 核心配置（cryptand/cryptand-sable.toml，2026-08-30 用户）
    // =====================================================================

    /**
     * ★ CryptandSable 物理核心【总开关】（2026-09-06 用户："cryptandsable需要有配置
     * 以便是否开启此核心，关闭则不加载"）。
     * <p>
     * true（默认） → 正常启用 CryptandSable 物理核心：安装生命周期钩子（世界加载启动
     * worker/引擎、每 tick 心跳）、支持结构物理化（装配器 → 异步 Rapier 仿真核心）。
     * false → 【完全不加载】物理核心：不安装任何 Level/ServerTick 事件钩子、不启动
     * worker/引擎、物理化入口直接拒绝（不创建亚层/刚体）——模组其他功能（电路/电网
     * 等）不受影响，物理化功能整体禁用。
     * <p>需要重启游戏生效。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_CRYPTAND_SABLE_CORE = CK
            .comment("CryptandSable物理核心总开关",
                     "true(默认): 启用异步物理核心(世界加载启动worker/引擎,支持结构物理化)",
                     "false: 完全不加载物理核心(不装事件钩子/不启动引擎/物理化直接拒绝)",
                     "模组其他功能(电路/电网等)不受影响; 需要重启游戏生效")
            .define("enableCryptandSableCore", true);

    /**
     * 亚层持久化后端选择（2026-08-31 用户："持久化交给一个类处理，可以选择
     * sqlite 还是 sable 原版的方法(大规模可能sqlite好)"；再次确认默认 sable 原版）。
     * <p>sable → {@code SableStyleSubLevelBackend}（每亚层一个 NBT 文件，与官方一致，默认）
     * sqlite → {@code SqliteSubLevelBackend}（单文件事务，规模大时快）
     */
    public static final ModConfigSpec.ConfigValue<String> SABLE_PERSISTENCE = CK
            .comment("亚层持久化后端(sable / sqlite)",
                     "sable: 每亚层一个NBT文件(与sable原版一致,默认)",
                     "sqlite: 单文件+事务(大规模亚层时更快)")
            .define("persistence", "sable");

    /**
     * Sable 动态多引擎分发总开关（2026-09-02 用户："增加config配置，添加启用动态物理
     * 引擎加载，启动时启动自动分发类…允许同时加载多种引擎（f32/f64）…对接多种Java层的
     * JNI接口（F32/F64/Box3D）"）。
     * <p>
     * true  → 启动时构建引擎分发器（EngineDispatcher）：按 {@link #SABLE_ENGINE_LIST}
     *         同时装载多台物理引擎，按路由规则把参数分发到不同引擎 JNI；替换"单活引擎"
     *         的底层获取接口（EngineManager 由分发器扮演 active）。
     * false → 完全走原有单活逻辑（官方引擎开关 {@link #ENABLE_SABLE_OFFICIAL_ENGINE}
     *         + 默认 f64 引擎），本功能不介入。
     * <p>需要重启游戏生效。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_ENGINE_DISPATCH = CK
            .comment("Sable动态多引擎分发开关",
                     "true: 启动时同时装载多台物理引擎,按路由规则分发参数到不同引擎JNI",
                     "      (替换单活引擎底层; 小坐标→F32,大坐标→F64可配)",
                     "false: 完全走原有单活逻辑,本功能不介入(默认)",
                     "需要重启游戏生效")
            .define("enableSableEngineDispatch", false);

    /**
     * 本次要同时装载的引擎 id 列表（逗号分隔；2026-09-02）。
     * <p>内置 id：rapier-f64 / rapier-f32。其余引擎（如 Box3D）注册后即可加入。
     * 顺序有意义：首个可用引擎为默认引擎（未绑定 scene/回退时使用）。
     * <p>注意事项：f32/f64 当前共享同一 JNI 门面类（Rapier3D），JVM 一条 native
     * 方法只绑定一个库 → 同进程无法真正同时 native 激活；分发器会检测门面互斥并把
     * 后者标记 unavailable（路由回退默认引擎）。要真正同持需各引擎独立 JNI 门面类
     * （Rust 导出独立符号集 + 独立 Java 门面），架构已预留。
     */
    public static final ModConfigSpec.ConfigValue<String> SABLE_ENGINE_LIST = CK
            .comment("多引擎分发:本次同时装载的引擎id列表(逗号分隔)",
                     "内置: rapier-f64(高精度) / rapier-f32(低精度)",
                     "      (f32/f64共享JNI门面,同进程只能native激活其一,其余路由回退)",
                     "自定义引擎注册到EngineRegistry后加入即可")
            .define("sableEngineList", "rapier-f64,rapier-f32");

    /**
     * 多引擎分发：是否按世界坐标路由（2026-09-02 用户："坐标小时使用F32模型，
     * 坐标大时采用F64"）。false → 全部路由到默认引擎（等于装载了但单引擎分发）。
     */
    public static final ModConfigSpec.BooleanValue SABLE_ENGINE_COORD_DISPATCH = CK
            .comment("多引擎分发:按世界坐标路由",
                     "true(默认): max(|x|,|y|,|z|)>=阈值→高精度引擎(f64),否则低精度(f32)",
                     "false: 全部路由到默认引擎(仅多引擎装载不分发)")
            .define("sableEngineCoordDispatch", true);

    /** 多引擎按坐标路由：小坐标使用的低精度引擎 id。 */
    public static final ModConfigSpec.ConfigValue<String> SABLE_ENGINE_LOW_PRECISION_ID = CK
            .comment("按坐标路由:小坐标用的低精度引擎id",
                     "默认 rapier-f32(性能好,近原点数值差可忽略)")
            .define("sableEngineLowPrecisionId", "rapier-f32");

    /** 多引擎按坐标路由：大坐标使用的高精度引擎 id。 */
    public static final ModConfigSpec.ConfigValue<String> SABLE_ENGINE_HIGH_PRECISION_ID = CK
            .comment("按坐标路由:大坐标用的高精度引擎id",
                     "默认 rapier-f64(大坐标数值稳,不抖不爆)")
            .define("sableEngineHighPrecisionId", "rapier-f64");

    /** 多引擎按坐标路由：坐标阈值（世界格；|坐标|&gt;=此值 → 高精度引擎）。 */
    public static final ModConfigSpec.DoubleValue SABLE_ENGINE_COORD_THRESHOLD = CK
            .comment("按坐标路由:坐标阈值(世界格)",
                     "|坐标|>=阈值 → 高精度引擎(f64); 小于 → 低精度(f32)",
                     "默认 1000000 (sable大坐标可达千万级)")
            .defineInRange("sableEngineCoordThreshold", 1_000_000.0, 0.0, 1.0e12);

    /**
     * ★ 2026-09-03 引擎存放目录（相对于游戏运行目录；2026-09-03 用户："增加配置，
     * 此目录用于存放引擎"）。
     * <p>自定义/第三方物理引擎二进制（DLL/JAR 等）存放目录（对应代码子包
     * {@code com.hdf.cryptand.neoforge.cryptandsable_engines} 的实现）；相对运行目录
     * 解析（如默认 config/cryptand/engines/ → &lt;gameDir&gt;/config/cryptand/engines/）。
     * 引擎实现加载二进制时按本配置查找（参考 SableNativeLoader 从资源加载的机制，
     * 本配置供"外置引擎目录"模式使用）。为空字符串 → 禁用/不启用外置目录加载。
     * <p>需要重启游戏生效。
     */
    public static final ModConfigSpec.ConfigValue<String> SABLE_ENGINE_DIR = CK
            .comment("引擎存放目录(相对游戏运行目录; 2026-09-03)",
                     "自定义/第三方物理引擎二进制(DLL/JAR等)存放目录",
                     "对应代码子包 cryptandsable_engines 的引擎实现",
                     "默认: config/cryptand/engines/ (为空则禁用外置目录加载)",
                     "需要重启游戏生效")
            .define("engineDir", "config/cryptand/engines/");

    /**
     * Sable 官方 Rapier 物理引擎核心开关（2026-09-01 用户："物理核心能参考原版sable
     * 搬过来吗，然后进行适配"）。
     * <p>
     * true → CryptandSable 物理核心用【官方 sable_rapier 引擎】（Rapier3D JNI +
     * 官方 DLL /natives/sable/）：完整 6DOF 刚体（横移/旋转）、体素碰撞（含世界
     * 静态方块）、冲量/约束、碰撞事件——官方自研模拟器缺的（"物理不完整，只有上下，
     * 左右无法偏移，没有碰撞，没有实体"）。
     * false → 回退自研 SableSimulator（纯 Java 半隐式欧拉 + 体素密度）。
     * <p>需要重启游戏生效（DLL 类加载阶段决定）。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_SABLE_OFFICIAL_ENGINE = CK
            .comment("Sable官方Rapier物理引擎核心开关",
                     "true: 使用官方sable_rapier引擎(完整6DOF+碰撞+实体交互)",
                     "      原生DLL从资源 /natives/sable/ 加载(需游戏重启)",
                     "false: 回退自研纯Java模拟器(仅竖直积分,无横向/碰撞)",
                     "需要重启游戏生效")
            .define("enableOfficialEngine", true);

    /**
     * Sable 世界碰撞收集半径（2026-09-01 用户："可以通过配置修改物理化收集周围世界
     * 内容的范围，应该是个圆形区域"）。
     * <p>
     * 结构物理化时，以【结构区域中心】为圆心、本半径 [格] 内的世界方块（静态地形）
     * 上传到官方 Rapier 引擎作为静态碰撞体——结构落地/被世界方块挡住（防坠入虚空）。
     * 圆形区域：水平 dx²+dz² ≤ r² 判定（竖直方向 ±r 内的 section 一并上传）。
     * <p>半径越大越贴近官方行为（官方全世界上传），消耗越大；小结构默认 16 格足够。
     * 需要重启/重新物理化生效（每个结构的收集为物理化时一次上传）。
     */
    public static final ModConfigSpec.IntValue SABLE_WORLD_COLLISION_RADIUS = CK
            .comment("Sable世界碰撞收集半径(圆形区域,格)",
                     "结构物理化时以其区域中心为圆心收集半径内世界方块作为静态碰撞",
                     "圆形: 水平dx²+dz²≤r²; 竖直±r内section一并上传",
                     "值越大越接近官方全世界上传, 消耗越大; 默认16适合小结构",
                     "设置后需重新物理化结构生效")
            .defineInRange("worldCollisionRadius", 16, 0, 512);

    /**
     * ★ 原生 shape 直接碰撞开关（2026-09-03 用户定案："走原生 shape 直接碰撞，就像
     * 真正世界那种，而不是检测参数；物理空间 = 创建一个真实世界/房间，里面加入各种
     * 形状的物理结构然后做碰撞"）。
     * <p>
     * true → 结构 = dynamic 刚体 + 每格一个完整 1×1×1 box collider（createShapeBody +
     * addShapeColliderAt）；世界/陪体 = fixed 刚体（或 trimesh）静态承接。全部放进一个
     * 物理空间，Rapier 原生 box↔trimesh/box 直接碰撞——相邻贴合/静止由引擎天然处理，
     * 不靠体素检测参数（prediction/配对半径/octree 对齐）。shape 体用主世界小坐标，
     * 无需 far 换算。
     * <p>
     * false → 回退旧体素管线（createBody + addChunkToBody + ensureStaticCompanion +
     * postTopology 检测）。
     * <p>需要重启游戏生效（新配置读取多数发生在结构物理化时）。
     */
    public static final ModConfigSpec.BooleanValue SABLE_SHAPE_COLLISION = CK
            .comment("原生shape直接碰撞开关(每格完整box直接碰撞)",
                     "true: 结构=dynamic刚体+每格1x1x1 box collider; 世界/fixed承接",
                     "      走Rapier原生碰撞, 不靠体素检测参数(更稳定)",
                     "      shape体用主世界小坐标, 无需far换算",
                     "false: 回退旧体素检测管线",
                     "需重启/重新物理化生效")
            .define("shapeCollision", true);

    /**
     * ★ 轴合并（凹形组合）开关（2026-09-05 用户定案："按照凹形组合进行修改，其他方法
     * 全部先丢到弃置区，保证只有凹形组合的代码适配"）。
     * <p>
     * true（默认，唯一适配）= 结构本体 / 世界陪体都建模为【凹形组合】：相邻实心块按
     * 轴贪心合并成若干大 box（{@link #SABLE_MERGE_MAX_BOX} 上限）挂到一个刚体上——
     * box 数 O(外表面)，保留房间/门洞，Rapier 原生 box↔box 碰撞，无体素内存灾难、
     * 无每格 box 爆炸。false = 走【弃置】旧体素管线（仅调试/回退）。
     * <p>需重启/重新物理化生效。
     */
    public static final ModConfigSpec.BooleanValue SABLE_MERGE_AXIS_ALIGNED = CK
            .comment("轴合并(凹形组合)开关",
                     "true(默认): 结构/陪体=轴合并大box凹形组合(唯一适配)",
                     "false: 走弃置旧体素管线(仅调试/回退)",
                     "需重启/重新物理化生效")
            .define("mergeAxisAligned", true);

    /**
     * ★ 每合并体单轴最大边长（2026-09-05 用户："SABLE_MERGE_MAX_BOX(每合并体上限,默认
     * 32x32x32)"）。轴合并时任一 box 的 X/Y/Z 边长最多 maxBox 格（超出的实心区另起 box），
     * 防止单个 collider 过大影响宽相/窄相效率。&lt;=0 = 不限。
     */
    public static final ModConfigSpec.IntValue SABLE_MERGE_MAX_BOX = CK
            .comment("每合并体单轴最大边长(格)",
                     "轴合并box的X/Y/Z边长上限; 默认32x32x32; <=0=不限",
                     "需重启/重新物理化生效")
            .defineInRange("mergeMaxBox", 32, 0, 512);

    /**
     * ★ shape 陪体缓存上限（2026-09-04 用户："rust部分可以设置最大缓存的物理碰撞空间
     * 上限，对于不频繁的或者小结构物理化结构缓存优先删除；可动态设置值：设置变小→执行
     * 一次缓存删除，扩大/相等→只扩大不删除"）。
     * <p>值 = 最多缓存的 shape 陪体（fixed 静态承接体）个数；-1=不限；0=禁用（全部淘汰）。
     * 淘汰按【权重低（不频繁·被动增加，u8 最高 255 封顶不再增加）+ footprint 小】优先，
     * 每次至少删 {@link #SABLE_SHAPE_CACHE_MIN_EVICT} 个。
     * <p>动态改：/cryptand sable shapecache &lt;limit&gt;（或命令）；缩小→立即淘汰一次。
     */
    public static final ModConfigSpec.LongValue SABLE_SHAPE_CACHE_LIMIT = CK
            .comment("shape陪体缓存上限(个数)",
                     "-1=不限; 0=禁用(全部淘汰); N=最多缓存N个shape陪体",
                     "超限淘汰: 权重低(不频繁)+footprint小优先, 至少删minEvict个",
                     "动态缩小->立即淘汰一次; 扩大/相等->不删",
                     "需重启/重新物理化生效")
            .defineInRange("shapeCacheLimit", -1L, -1L, 1_000_000L);

    /**
     * ★ 每次淘汰最小批量删除数（2026-09-04 用户："可以增加最小缓存删除，比如 3 个就是
     * 每次缓存删除至少删除 3 个缓存空间"）。1=每次删 1；N=每次至少删 N 个。
     */
    public static final ModConfigSpec.LongValue SABLE_SHAPE_CACHE_MIN_EVICT = CK
            .comment("每次淘汰最小批量删除数(shape陪体)",
                     "N=每次缓存淘汰至少删除N个空间(权重低的优先); 1=每次删1",
                     "需重启/重新物理化生效")
            .defineInRange("shapeCacheMinEvict", 3L, 1L, 1_000_000L);

    /**
     * Sable 世界碰撞收集超时次数（2026-09-01 用户："每次计算后向主线程发收集指令，
     * 超时次数：超过指定次数没收到主线程数据就停止运行；1=每次计算都必须接收；0=无限"）。
     * <p>
     * 物理体每次计算后 worker 发【世界收集查询】→ 主线程收集（WorldChunkUploader）
     * → 回传解冻。若主线程迟迟不收集（主线程忙/阻塞），本计数值用于【停止运行】：
     * 超过上限次数的未收集 → 该体从冻结集移除（继续运行但不带世界碰撞，防卡死）。
     * <ul>
     *   <li>1 = 每次计算都必须收到（严格；主线程卡住即停止该体）</li>
     *   <li>0 = 无限次数（永不因收集卡住而停止；主线程忙时无限重试）</li>
     *   <li>N = 允许 N 次未收集（超过停止）</li>
     * </ul>
     */
    public static final ModConfigSpec.IntValue SABLE_COLLECT_TIMEOUT = CK
            .comment("Sable世界碰撞收集超时次数",
                     "物理体每次计算后向主线程发世界收集查询; 超上限未收到收集则停止运行",
                     "1=每次计算都必须接收(主线程忙即停)", "0=无限次数(永不因收集而停)",
                     "N=允许N次未收集后停止")
            .defineInRange("worldCollectTimeout", 1, 0, 1000000);

    /**
     * Sable pull 渲染：服务端每秒最大位姿回复次数（2026-09-01 用户："能定义客户端和
     * 服务端每秒最大请求次数，超过则跳过直到下一秒请求"）。
     * <p>客户端渲染主动请求（SablePoseRequestPayload）→ 服务端回复（SablePoseResponse）。
     * 本值限制服务端每秒最多回复客户端请求次数；超过 → 跳过（下一秒窗口恢复）。
     * 0 = 不限（每请求都回；默认 20 = 与物理 tick 同步）。
     */
    public static final ModConfigSpec.IntValue SABLE_POSE_SERVER_MAX_PER_SEC = CK
            .comment("Sable渲染位姿服务端每秒最大回复次数",
                     "pull渲染: 客户端请求->服务端回复; 超过每秒上限跳过直到下一秒",
                     "0=不限(每请求都回); 默认20与物理tick同步; 调大更流畅消耗更多带宽")
            .defineInRange("poseServerMaxPerSecond", 20, 0, 1000000);

    /**
     * Sable pull 渲染：客户端每秒最大位姿请求次数（2026-09-01 用户：同上）。
     * <p>客户端渲染每帧主动请求；本值限制每秒最多请求次数；超过 → 跳过（下一秒恢复）。
     * 0 = 不限（每帧都请求；默认 20）。通常与服务端上限一致（或略低防抖）。
     */
    public static final ModConfigSpec.IntValue SABLE_POSE_CLIENT_MAX_PER_SEC = CK
            .comment("Sable渲染位姿客户端每秒最大请求次数",
                     "pull渲染: 客户端每帧请求; 超过每秒上限跳过直到下一秒",
                     "0=不限(每帧都请求); 默认20; 与服务端上限一致或略低")
            .defineInRange("poseClientMaxPerSecond", 20, 0, 1000000);

    /**
     * Sable 世界碰撞收集发送间隔（计算次数，2026-09-01 用户："每次发送按照超时时间发送，
     * 比如3次计算发送一次"）。
     * <p>worker 每【N 次物理计算】发一次世界收集查询（主线程回传；空列表=周围无方块也算）。
     * 1 = 每次计算都发；3 = 每 3 次计算发一次。
     */
    public static final ModConfigSpec.IntValue SABLE_COLLECT_SEND_INTERVAL_STEPS = CK
            .comment("Sable世界碰撞收集发送间隔(计算次数)",
                     "worker每N次物理计算发一次世界收集查询",
                     "1=每次计算都发; 3=每3次计算发一次(与物理tick同步较合理)",
                     "设置后重新物理化生效")
            .defineInRange("worldCollectSendIntervalSteps", 3, 1, 1000000);

    /**
     * ★ 2026-09-05 速度动态收集频率（用户："飞行速度越快的结构更新请求越快，静止或
     * 缓速物体慢速更新；配合目前设置更新速度配置来配合使用"）。
     * <p>true → worker 对每个结构每轮计算其【当前运动速度】，快于 FAST_SPEED 时用
     * SABLE_COLLECT_SEND_INTERVAL_STEPS（快速更新）；否则用其 × SLOW_MULT（慢速更新）。
     * false → 一律用 SABLE_COLLECT_SEND_INTERVAL_STEPS（静态固定，原行为）。
     */
    public static final ModConfigSpec.BooleanValue SABLE_COLLECT_DYNAMIC_INTERVAL = CK
            .comment("Sable速度动态收集频率开关",
                     "true:速度快→快速更新(基础间隔), 静止/慢速→慢速更新(基础间隔×倍数)",
                     "false:一律用基础间隔(静态固定)")
            .define("worldCollectDynamicInterval", true);

    /** ★ 2026-09-05 速度阈值（块/秒）：结构运动速度高于此值视为"快"→快速收集。 */
    public static final ModConfigSpec.DoubleValue SABLE_COLLECT_FAST_SPEED = CK
            .comment("Sable速度动态收集:快速阈值(块/秒)",
                     "结构运动速度(线性)高于此值视为快→用基础间隔收集",
                     "默认0.5块/秒(高于人走路2.5的一半; 低速/静止视为慢)")
            .defineInRange("worldCollectFastSpeed", 0.5, 0.0, 1000.0);

    /** ★ 2026-09-05 慢速倍数：结构速度低于阈值时的收集间隔倍数（越慢越省）。 */
    public static final ModConfigSpec.IntValue SABLE_COLLECT_SLOW_MULT = CK
            .comment("Sable速度动态收集:慢速间隔倍数",
                     "结构速度低于快速阈值时,收集间隔=基础间隔×该倍数",
                     "默认4(即静止结构每4×基础间隔才收集一次)",
                     "1=不减速; 值越大越省但响应越慢")
            .defineInRange("worldCollectSlowMult", 4, 1, 1000000);

    /**
     * ★ 2026-09-06 【空间 BFS 扫描间隔（秒）】物理空间矩形 BFS 全量重建的间隔。
     * <p>用户："BFS改成几秒或更长时间，不需要一直扫，可以配置选项中定义BFS扫描时间"。
     * 0 = 禁用周期扫描（仅成员变动/首建时扫描）；>0 = 每 N 秒对全部空间扫描一次
     * （从 seed 出发 BFS 重建矩形成员集，处理超距离成员并入）。
     */
    public static final ModConfigSpec.DoubleValue SABLE_SPACE_BFS_INTERVAL_SEC = CK
            .comment("Sable空间BFS扫描间隔(秒)",
                     "物理空间矩形从seed出发的BFS成员重建间隔",
                     "0=禁用周期扫描(仅成员变动/首建时扫); >0=每N秒全量扫一次",
                     "建议2~10秒; 越短越即时但越耗性能")
            .defineInRange("spaceBfsIntervalSec", 3.0, 0.0, 3600.0);

    /**
     * ★ 2026-09-06 【空间扫描步长（格）】BFS 相邻成员并入判定的最大间距。
     * <p>用户："A边正前方512格扫描到物理化结构，那就扩散扫描区域"。
     * 相邻成员距离 <= 该值 → 判定同空间链（并入）；链上成员超此距离暂不并入。
     */
    public static final ModConfigSpec.DoubleValue SABLE_SPACE_SCAN_STEP = CK
            .comment("Sable空间BFS扫描步长(格)",
                     "相邻物理结构间距小于该值才判定为同一空间的链",
                     "可扫描到远处结构并经矩形扩展加入计算",
                     "默认512格")
            .defineInRange("spaceScanStep", 512.0, 1.0, 1000000.0);

    /**
     * ★ 2026-09-06 【空间拆分距离（格）】成员离 seed 原点超过该距离 → 拆分出原空间。
     * <p>用户："允许物理结构超过扫描距离但加入计算，因为这个不是很影响体验"——
     * 距离超過扫描距離但 ≤ 该宽限值 → 保留在原空间计算（不拆分）；> 宽限 → 拆分。
     * 默认 65536（64k 格 = 1024×64；扫描步长的超大扩展）。
     * 0 → 严格按矩形边界拆分（= 旧行为）。
     */
    public static final ModConfigSpec.DoubleValue SABLE_SPACE_SPLIT_DIST = CK
            .comment("Sable空间拆分距离(格)",
                     "成员离原点结构(seed)超过该距离→拆分出原空间(单开/并入)",
                     "超过扫描距离但未超该值→保留原空间继续计算(体验优先)",
                     "默认65536; 0=严格按矩形边界拆分")
            .defineInRange("spaceSplitDist", 65536.0, 0.0, 1.0e9);

    /**
     * Sable 世界碰撞收集超时步骤（计算次数，2026-09-01 用户："超时机制最好大于比如物理计算
     * 100次计算，20tick为主线程默认，则超时为100/20*3"）。
     * <p>发送后等待【N 次计算】未收到主线程回复 → 再发一次（重置等待）；循环。
     * 默认 15 = 100/20*3（100 次计算/20 tick = 5 次计算/tick，×3 超时）。
     */
    public static final ModConfigSpec.IntValue SABLE_COLLECT_TIMEOUT_STEPS = CK
            .comment("Sable世界碰撞收集超时步骤(计算次数)",
                     "发送后等待N次计算未收到主线程回复则再发一次(重置等待,循环)",
                     "默认15=100/20*3(5次计算/tick×3超时)",
                     "设置后重新物理化生效")
            .defineInRange("worldCollectTimeoutSteps", 15, 1, 10000000);

    /**
     * ★ 2026-09-07 【空间操作周期超时（毫秒）】物理空间周期内任一子阶段（等待主线程
     * 收集回复 / 前处理 / 等待引擎）超过该时间仍未完成 → 视为操作失败，向核心发送
     * 操作失败请求并移回普通表；失败后到达的数据一律丢弃（消息特征不再匹配）。
     * <p>默认 5000ms（5 秒）；0 = 禁用超时（不推荐，等待回复可能永久挂起）。
     */
    public static final ModConfigSpec.IntValue SABLE_SPACE_OP_TIMEOUT_MS = CK
            .comment("Sable空间操作周期超时(毫秒)",
                     "物理空间周期内任一子阶段超过该时间未完成视为操作失败→移回普通表",
                     "失败后到达的数据丢弃(消息特征不匹配)",
                     "0=禁用超时; 默认5000ms")
            .defineInRange("spaceOpTimeoutMs", 5000, 0, 3600000);

    /**
     * ★ 2026-09-02 调试：强制冻结物理（结构保持不动，不做任何积分）。
     * <p>用于隔离问题：冻结状态下结构纹丝不动 → 证明收集/上传/渲染链路正常，
     * 问题只在物理计算阶段。开启后 OfficialRapierEngine.step() 只发收集查询
     * 并直接返回（不 tick/step）；结构位姿保持 createBody 时的初始 pose。
     */
    public static final ModConfigSpec.BooleanValue SABLE_FREEZE_PHYSICS = CK
            .comment("Sable调试:强制冻结物理计算",
                     "true:结构保持不动(不做物理积分,只发收集查询)",
                     "用于隔离问题:冻结时结构不动=>收集/上传/渲染正常,问题在物理计算")
            .define("freezePhysics", false);

    /**
     * ★ 2026-09-02 调试：高亮被收集上传的世界碰撞方块（红色粒子）。
     */
    public static final ModConfigSpec.BooleanValue SABLE_DEBUG_HIGHLIGHT = CK
            .comment("Sable调试:高亮被收集的世界碰撞方块",
                     "true:被收集上传的世界块表面闪红色粒子",
                     "用于确认世界碰撞数据是否已上传")
            .define("debugHighlight", false);

    /**
     * ★ 2026-09-03 debug：绘制每个物理结构的【扫描收集范围】方框（客户端线框，无需重启）。
     * <p>每帧为每个 active 结构以当前物理位姿为中心、SABLE_WORLD_COLLISION_RADIUS 为半径画
     * 一个立方体线框（同 NetworkColorRenderer 机制），直观确认世界碰撞收集覆盖范围。
     * 读取方：cryptandsable/debug/SableScopeBoxDebugRenderer。
     */
    public static final ModConfigSpec.BooleanValue SABLE_DEBUG_SCOPE_BOX = CK
            .comment("Sable调试:显示物理结构扫描收集范围方框(客户端)",
                     "true: 每个物理结构以当前位姿为中心、收集半径为半径画立方体线框",
                     "     (结构自身包围盒用白色, 扫描收集范围用黄色; 无需重启即时生效)",
                     "false: 关闭(默认)",
                     "仅客户端生效")
            .define("debugScopeBox", false);

    /**
     * 损坏内容清理修复（2026-08-30 用户："加载了其他mod的内容但没有相应mod可能就会这样"）。
     * <p>
     * 世界存档残留【未加载 mod 的内容】（如 createbigcannons 物品/BlockAttachedEntity
     * 大坐标实体）→ 加载时报 Unknown registry key / invalid position → 【保存世界时
     * 卡住】（无效实体序列化阻塞）。打开后：
     *   1) 世界保存前（LevelEvent.Save / ServerStoppingEvent）扫描主世界所有
     *      {@code BlockAttachedEntity}，位置非法（Sable plot 大坐标 ≥ 2400 万 或
     *      区块未加载）→ 移除（丢弃无效实体，防止保存卡死）；
     *   2) 记录移除数量日志（便于诊断）。
     * ⚠ 只移除【无效/悬空】实体（位置非法），不碰正常实体；默认关闭（保守）。
     * 需要重启游戏生效。
     */
    private ConfigCryptandSable() {
    }

    /** 本域 Spec（ConfigLoad 聚合注册）。 */
    public static final ModConfigSpec SPEC = CK.build();
}