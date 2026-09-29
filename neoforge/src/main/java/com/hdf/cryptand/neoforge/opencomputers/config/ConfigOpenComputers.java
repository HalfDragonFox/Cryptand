/**
 * ===== OpenComputers 联动 · 配置域（2026-09-16）=====
 *
 * <p>子包自治注册（与 sot.toml / aeronautics.toml 同规矩）：本域只承载**联动侧**开关与限额；
 * RV32 核心与组件总线在 common 的 {@code com.hdf.cryptand.soc.oc}（纯 Java，零 MC 依赖）。</p>
 *
 * <p>关闭语义（{@code enableOpenComputersIntegration=false}）：本子包不注册任何联动内容，
 * 但 **Cryptand 芯片仍可独立使用**（soc 子包与本子包互不依赖）。</p>
 */
package com.hdf.cryptand.neoforge.opencomputers.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class ConfigOpenComputers implements CryptandConfigSpec {

    public static final String DOMAIN = "opencomputers";

    public static final ConfigOpenComputers INSTANCE = new ConfigOpenComputers();

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public String fileName() {
        return "opencomputers.toml";
    }

    @Override
    public ModConfigSpec spec() {
        return SPEC;
    }

    /** 子包自治注册（由 OpenComputersEntry.registerConfigs 调用） */
    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "opencomputers.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /** 联动总开关（OC 未加载时本子包无论如何都不初始化） */
    public static final ModConfigSpec.BooleanValue ENABLE = CK
            .comment("OpenComputers 联动总开关（需 OC 已加载；OC 不在场时本子包不初始化）",
                    "false(默认, 2026-09-30 用户要求先关闭): 不注册任何联动内容（Cryptand 芯片仍可独立使用）",
                    "true: 允许 Cryptand 芯片插进 OC 机箱，并由我们的 C/RV32 架构执行",
                    "需要重启游戏生效")
            .define("enableOpenComputersIntegration", false);

    /**
     * CPU 主频（MHz）—— **唯一口径**（用户 2026-09-25）。
     *
     * <p>"cpu 频率 10 MHz 的话 1 秒就给 10M 指令周期"：本值在开机时<b>配置一次</b>
     * （{@code SandboxVm.clock}），此后沙箱按自己的内部时钟推进与限速，宿主每 tick 只发一次心跳，
     * 不再计算也不再下发"每 tick 多少周期"。0 表示**自动**：按插在机箱里的处理器规格取
     * （档位表在 {@code SocContent}：MCU 1–64 MHz / SOC 32–1000 MHz / CPU 500–2000 MHz）。</p>
     *
     * <p>⚠ 旧的"每 tick 周期预算"口径（{@code cyclesPerTick}）与"宿主喂预算"执行模型
     * 已按用户 2026-09-25 定案<b>整条删除</b>：只有一套操作，没有回退路径。</p>
     */
    public static final ModConfigSpec.IntValue CLOCK_MHZ = CK
            .comment("CPU 主频（MHz）：沙箱每秒周期数 = MHz × 1_000_000（开机时配置一次，此后沙箱自管理）",
                    "0(默认): 自动按处理器规格（MCU 1–64 / SOC 32–1000 / CPU 500–2000 MHz；"
                            + "64 位与 8051 同频展示，但内核未实现 ⇒ 会拒绝开机）",
                    ">0: 强制覆盖所有处理器的频率（调试/对照用）")
            .defineInRange("clockMhz", 0, 0, 2000);

    /** 是否用 Cryptand 线程分配器推进（核心脱离主线程） */
    public static final ModConfigSpec.BooleanValue USE_THREAD_ALLOCATOR = CK
            .comment("用 Cryptand 线程分配器（ThreadDispatchers / PinnedWorker）推进芯片沙箱",
                    "true(默认): 沙箱跑在独立线程，主线程零计算（与 soc 子包同一纪律）",
                    "false: 退化为主线程 tick 内推进（仅调试用）")
            .define("useThreadAllocator", true);

    /** 单次 OC 组件调用等待上限（毫秒） */
    public static final ModConfigSpec.IntValue CALL_TIMEOUT_MS = CK
            .comment("单次 OC 组件调用等待上限（毫秒）—— 组件兑现需跨到主线程，超时则该次调用报错",
                    "默认 1000")
            .defineInRange("componentCallTimeoutMs", 1_000, 50, 60_000);

    /** 是否允许 Cryptand 部件插入 OC 机箱（P1 用；关掉则只做架构联动，不动 OC 槽位规则） */
    public static final ModConfigSpec.BooleanValue ALLOW_PART_IN_OC_CASE = CK
            .comment("允许把 Cryptand 的处理器/内存条/硬盘等部件插入 OC 机箱",
                    "true(默认): 按 OC 的 Slot 规则注册驱动（Slot.CPU / Memory / HDD …）",
                    "false: 只做架构联动，不改 OC 机箱的槽位接受规则")
            .define("allowCryptandPartsInOcCase", true);

    /**
     * 内核实现选择（用户 2026-09-17："直接一步到 C++ 内核"）。
     *
     * <p>C++ 内核在 {@code excode/cryptand-rv32/}，编成 {@code cryptand_rv32.dll}，
     * 放 {@code config/cryptand/engines/} 或由 {@code -Dcryptand.rv32.lib} 指定。
     * 库缺失时**自动回落**到纯 Java 内核（不影响游戏）。</p>
     */
    public static final ModConfigSpec.BooleanValue USE_NATIVE_KERNEL = CK
            .comment("用 C++ native 内核（cryptand_rv32.dll）替代纯 Java 解释器",
                    "true(默认): 优先 native；库不存在时自动回落 Java 并记日志",
                    "false: 强制纯 Java 内核（对照/排查用）")
            .define("useNativeKernel", true);

    // ==================== 磁盘（用户 2026-09-18 定案）====================

    /**
     * 整个存档允许的磁盘总量（GB）；**0 = 关闭限制**（与项目惯例一致）。
     *
     * <p>配套的是「动态盘」（thin provisioning）：设置 60G 的盘初始占用 0 字节，写到哪里才
     * 分配到哪里。<b>但正因为动态，才必须有闸门</b> —— 否则一块 60G 的盘就能把存档目录真写掉
     * 60G。超限时写入返回 {@code ERR_NO_SPACE}（固件看到「磁盘满」，语义照 OC 的
     * not enough space）。</p>
     */
    public static final ModConfigSpec.IntValue SAVE_QUOTA_GB = CK
            .comment("整个存档允许的磁盘总量（GB）；0 = 不限制",
                    "动态盘初始占用 0、按写入增长 —— 这道闸门保证宿主的真实磁盘不被写爆",
                    "超限时固件侧看到 ERR_NO_SPACE（磁盘满）")
            .defineInRange("saveQuotaGb", 20, 0, 100_000);

    /** 单台机器的磁盘上限（GB）；**0 = 关闭限制** */
    public static final ModConfigSpec.IntValue MACHINE_QUOTA_GB = CK
            .comment("单台机器的磁盘上限（GB）；0 = 不限制",
                    "与 saveQuotaGb 组成两级闸门：这一级专防单台机器吃掉整个存档预算")
            .defineInRange("machineQuotaGb", 8, 0, 100_000);

    /**
     * 空心跳间隔（秒）—— 用户 2026-09-28 定案："心跳可配置默认发送速度，建议 3s 发送一次"。
     *
     * <p>宿主至少每隔这么久给虚拟机发一次空心跳（没有内容也算）。虚拟机是独立机器：
     * 宿主卡顿它不管，但这条心跳断了超过 {@link #SILENCE_TIMEOUT_SECONDS}，
     * 它就会自己暂停（说明宿主已经不在了）。</p>
     */
    public static final ModConfigSpec.IntValue HEARTBEAT_SECONDS = CK
            .comment("空心跳间隔（秒）：宿主至少每隔这么久给虚拟机发一次心跳（无内容也算）",
                    "3(默认): 建议值 —— 配合 10 秒超时可容忍连续丢 3 次心跳",
                    "范围 1~60")
            .defineInRange("heartbeatSeconds", 3, 1, 60);

    /**
     * 心跳超时（秒）：心跳断了这么久，虚拟机**自己暂停**（用户 2026-09-28 定案）。
     *
     * <p>暂停后任何一条宿主消息（心跳/命令/设备兑现）都会让它自动恢复。0 不允许：
     * 关闭看门狗请用足够大的值。</p>
     */
    public static final ModConfigSpec.IntValue SILENCE_TIMEOUT_SECONDS = CK
            .comment("心跳超时（秒）：心跳断了这么久虚拟机就自己暂停（宿主已经不在）",
                    "10(默认) —— 说明宿主没了（世界卸载 / 服务端停 / 崩溃），别让它空转 CPU",
                    "范围 2~600")
            .defineInRange("silenceTimeoutSeconds", 10, 2, 600);

    // ==================== 真彩屏帧同步（2026-09-29 用户定案）====================
    // 背景：服务端是**被动**的 —— 可见性与距离由客户端自己判断并发包；
    // 但客户端可以被伪造（对任意屏地址刷请求 = 扫描探针 + 资源放大），
    // 所以准入门槛留在服务端：最大显示范围 + 每玩家请求速率。

    /**
     * 真彩屏**最大显示范围**（格）—— 服务端准入闸，防伪造请求扫描（用户 2026-09-29：
     * "服务端可以额外设置最大显示范围，max 上限就是无限制，最好添加限制防止被注入扫描，安全性"）。
     *
     * <p>超范围的帧请求**直接丢弃**（不回帧、不回任何地址信息）。这**不是** LOD：
     * 距离只用于"准不准"，不用于选采样步长 —— LOD 与渲染全在客户端。</p>
     */
    public static final ModConfigSpec.DoubleValue TRUESCREEN_MAX_VIEW_DISTANCE = CK
            .comment("真彩屏最大显示范围（格）：玩家离屏超过这个距离的帧请求一律丢弃",
                    "-1(默认): 无限制（等于 max）—— 单机/本地测试用",
                    "例如 128: 只有 128 格内的客户端能拉到帧（防扫描/防资源放大）",
                    "注意：这只管\"准不准\"，不管画质；LOD 由客户端自己实现，服务端不因此降采样")
            .defineInRange("trueScreenMaxViewDistance", -1.0, -1.0, 1_000_000.0);

    /**
     * 客户端**请求间隔**（毫秒）—— 客户端请求速率上限（用户 2026-09-29：
     * "服务器可以设置单个请求速率上限以及客户端请求速率上限"）。
     *
     * <p>客户端是"可见即拉取"模型（渲染被调用 = 屏在视野里），这个间隔就是它的节流阀；
     * 参考外设船舵的节流手法（变化阈值 + 时间间隔），帧这里没有"变化"信号可依，所以只用时间间隔。</p>
     */
    public static final ModConfigSpec.IntValue TRUESCREEN_CLIENT_REQUEST_INTERVAL_MS = CK
            .comment("真彩屏客户端请求间隔（毫秒）：越小越跟手、越费带宽与服务端算力",
                    "50(默认): 20 次/秒，肉眼够顺滑",
                    "范围 10~5000")
            .defineInRange("trueScreenClientRequestIntervalMs", 50, 10, 5_000);

    /**
     * 服务端**每玩家**请求速率上限（次/秒）—— 令牌桶速率（用户 2026-09-29）。
     *
     * <p>速率按**玩家**计桶（不是按屏）：一个玩家同时盯十块屏也不该把服务端刷爆。</p>
     */
    public static final ModConfigSpec.DoubleValue TRUESCREEN_SERVER_REQUESTS_PER_SECOND = CK
            .comment("真彩屏服务端限流：每个玩家每秒允许的帧请求数（超过就丢弃）",
                    "200(默认): 够 4 块屏 @50ms 同时拉取",
                    "参考外设船舵的令牌桶限流（burst 见下一项）；范围 1~10000")
            .defineInRange("trueScreenServerRequestsPerSecond", 200.0, 1.0, 10_000.0);

    /**
     * 服务端令牌桶**突发额度**（最多连放几个）—— 允许短促爆发，避免正常突发被误伤
     * （与磁盘 IOPS 限流的 burst 同理：真实设备/客户端都有队列深度）。
     */
    public static final ModConfigSpec.DoubleValue TRUESCREEN_SERVER_BURST = CK
            .comment("真彩屏服务端限流：令牌桶容量（允许的瞬时突发请求数）",
                    "10(默认): 连发 10 个请求不会被卡",
                    "范围 1~1000")
            .defineInRange("trueScreenServerBurst", 10.0, 1.0, 1_000.0);

    /**
     * 帧体压缩算法（用户 2026-09-29：「压缩算法可以**自选**，配置上可以设置 **auto 或者强制其他后端**」）。
     *
     * <p>实测（`:common:runScreenFrameWireBenchmark`，400x256 = 409600 B）：
     * zlib 总是更紧（终端画面 1.3%~2.2%），RLE 总是更快（0.9~2.1ms vs 2.9~22.7ms）；
     * 随机像素 RLE 会膨胀到 125% ⇒ 必须能回落。</p>
     *
     * <ul>
     *   <li>`auto`(默认)：RLE 体 &lt; 原始一半 ⇒ 用 RLE；≥ 原始 ⇒ 不压（NONE）；中间也不自己压（交通道 zlib）</li>
     *   <li>`none`：帧体不压（交给 MC 通道的 zlib）</li>
     *   <li>`rle`：强制 RLE（压不动也发，慎用）</li>
     *   <li>`zlib`：等同 none（zlib 由 MC 通道做，帧体层不重复压）</li>
     *   <li>`lz4`：预留；**未实现 ⇒ 明确报错**（不静默降级成别的算法）</li>
     * </ul>
     */
    public static final ModConfigSpec.ConfigValue<String> TRUESCREEN_FRAME_COMPRESSION = CK
            .comment("真彩屏帧体压缩算法：auto / none / rle / zlib / lz4",
                    "auto(默认): 按数据特征自动选（终端画面用 RLE 省 CPU，压不动就不压）",
                    "zlib 由 MC 通道完成（帧体层不重复压，避免双重压缩）",
                    "lz4 预留，未实现时会明确报错而不是静默换算法")
            .define("trueScreenFrameCompression", "auto");

    /**
     * 真彩屏 LOD 距离阶梯（用户 2026-09-29：「可以配置表设置 lod 阶梯…五个截梯，分别为 8、16、32、48」）。
     * 每档 = 距离上限:渲染百分比。
     */
    public static final ModConfigSpec.ConfigValue<String> TRUESCREEN_LOD_STAIRS = CK
            .comment("真彩屏 LOD 距离阶梯：每档写作 距离上限:渲染百分比，逗号分隔",
                    "默认 8:100,16:50,32:25,48:12.5,64:6.25（第 5 档 64 是按 8/16/32/48 的规律补的）",
                    "距离必须严格递增，否则启动时明确报错（不静默用默认表）",
                    "百分比 ⇒ 抽稀步长 = round(100/百分比)：100%⇒1、50%⇒2、25%⇒4、12.5%⇒8、6.25%⇒16")
            .define("trueScreenLodStairs", "8:100,16:50,32:25,48:12.5,64:6.25");

    /** LOD 阶梯是否平滑（用户 2026-09-29：「lod 阶梯传入时需要设置是否平滑，平滑默认开」）。 */
    public static final ModConfigSpec.BooleanValue TRUESCREEN_LOD_SMOOTH = CK
            .comment("LOD 阶梯是否平滑（默认开）",
                    "true: 档位之间按距离插值百分比 + 抽稀用块平均（远处糊下去，不是抽成噪点）",
                    "false: 档位硬切换 + 点采样（更锐利，但档位边界会跳一下）")
            .define("trueScreenLodSmooth", true);

    /** LOD 后端链条（用户 2026-09-29：「配置可以配置后端链条，优先使用第一有效」+「lod 后端有自实现兜底」）。 */
    public static final ModConfigSpec.ConfigValue<String> TRUESCREEN_LOD_BACKEND_CHAIN = CK
            .comment("LOD 后端链条（逗号分隔；按顺序取第一个有效后端 = 第一有效）",
                    "默认 builtin：核心内置的自实现兜底，永远可用，且无论如何都会自动垫到链条末尾",
                    "示例 flywheel,voxy,builtin —— 前面的若未登记 / 不可用 / 不支持 2D·3D / 返回 null 则跳过，并写日志",
                    "外部后端由 MC 侧适配器注册；本 mod 目前只自带 builtin")
            .define("trueScreenLodBackendChain", "builtin");

    /** 构建配置规格（⚠ 必须在所有 {@code CK.define*} 之后 build） */
    public static final ModConfigSpec SPEC = CK.build();

    private ConfigOpenComputers() {
    }

    public static boolean useNativeKernel() {
        return USE_NATIVE_KERNEL.get();
    }

    // ==================== 真彩屏帧同步访问器（2026-09-29）====================

    /** 最大显示范围（格）；&lt; 0 = 无限制（max）。 */
    public static double trueScreenMaxViewDistance() {
        return TRUESCREEN_MAX_VIEW_DISTANCE.get();
    }

    /** 客户端请求间隔（毫秒）。 */
    public static int trueScreenClientRequestIntervalMs() {
        return TRUESCREEN_CLIENT_REQUEST_INTERVAL_MS.get();
    }

    /** 服务端每玩家请求速率（次/秒）。 */
    public static double trueScreenServerRequestsPerSecond() {
        return TRUESCREEN_SERVER_REQUESTS_PER_SECOND.get();
    }

    /** 服务端令牌桶突发额度。 */
    public static double trueScreenServerBurst() {
        return TRUESCREEN_SERVER_BURST.get();
    }

    /** 帧体压缩算法（auto/none/rle/zlib/lz4）。 */
    public static String trueScreenFrameCompression() {
        return TRUESCREEN_FRAME_COMPRESSION.get();
    }

    /** LOD 距离阶梯表文本（距离:百分比，逗号分隔）。 */
    public static String trueScreenLodStairs() {
        return TRUESCREEN_LOD_STAIRS.get();
    }

    /** LOD 阶梯是否平滑。 */
    /** LOD 后端链条（逗号分隔；内置兜底 builtin 会自动垫底）。 */
    public static String trueScreenLodBackendChain() {
        return TRUESCREEN_LOD_BACKEND_CHAIN.get();
    }

    public static boolean trueScreenLodSmooth() {
        return TRUESCREEN_LOD_SMOOTH.get();
    }

    /**
     * 是否走沙箱自驱动执行模型（默认 true）。
     *
     * <p>与 {@link #useNativeKernel()} 是**两个正交的维度**：本项决定"周期由谁推进"，
     * {@link #useNativeKernel()} 决定"谁来解释指令"。纯 Java 内核没有沙箱外壳
     * ⇒ 关闭 native 内核时必然回到宿主喂预算（这一点在 {@code createCpu} 里显式判定）。</p>
     */
    /** 整个存档的磁盘上限（字节）；**-1 = 不限制**（配置里的 0 映射成 -1） */
    public static long saveQuotaBytes() {
        return com.hdf.cryptand.soc.fs.DiskQuota.gbToBytes(SAVE_QUOTA_GB.get());
    }

    /** 单台机器的磁盘上限（字节）；**-1 = 不限制** */
    public static long machineQuotaBytes() {
        return com.hdf.cryptand.soc.fs.DiskQuota.gbToBytes(MACHINE_QUOTA_GB.get());
    }

    /** 当前配额（宿主侧直接用） */
    public static com.hdf.cryptand.soc.fs.DiskQuota diskQuota() {
        return new com.hdf.cryptand.soc.fs.DiskQuota(saveQuotaBytes(), machineQuotaBytes());
    }

    public static boolean enabled() {
        return ENABLE.get();
    }

    /** 强制主频（MHz）；0 = 自动按处理器规格 */
    public static int clockMhz() {
        return CLOCK_MHZ.get();
    }

    /** 空心跳间隔（毫秒） */
    public static long heartbeatMs() {
        return HEARTBEAT_SECONDS.get() * 1000L;
    }

    /** 心跳超时（毫秒）：断了这么久虚拟机自暂停 */
    public static long silenceTimeoutMs() {
        return SILENCE_TIMEOUT_SECONDS.get() * 1000L;
    }

    public static boolean useThreadAllocator() {
        return USE_THREAD_ALLOCATOR.get();
    }

    public static int callTimeoutMs() {
        return CALL_TIMEOUT_MS.get();
    }

    public static boolean allowPartInOcCase() {
        return ALLOW_PART_IN_OC_CASE.get();
    }
}
