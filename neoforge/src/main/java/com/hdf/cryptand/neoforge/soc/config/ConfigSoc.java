package com.hdf.cryptand.neoforge.soc.config;

import com.hdf.cryptand.neoforge.core.config.ConfigLoad;
import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * ConfigSoc —— 游戏内 SoC/芯片配置域（2026-09-15 用户架构：子包自治注册）。
 *
 * <p>内核与沙箱实现位于 common 纯 Java 子包 {@code com.hdf.cryptand.soc}（零 MC 依赖，
 * 可 MC 外自测 {@code :common:runSocTest}）；本域只承载平台侧开关与限额。</p>
 *
 * <p>关闭语义（{@code enableSocSupport=false}）：整个 soc 子包不初始化——方块/事件/
 * 指令/配置全不加载，已有芯片不推进（存档保留）。</p>
 */
public final class ConfigSoc implements CryptandConfigSpec {

    public static final String DOMAIN = "soc";

    public static final ConfigSoc INSTANCE = new ConfigSoc();

    @Override
    public String domain() {
        return DOMAIN;
    }

    @Override
    public String fileName() {
        return "soc.toml";
    }

    @Override
    public ModConfigSpec spec() {
        return SPEC;
    }

    /** 子包自治注册（由 SocEntry.registerConfigs 调用） */
    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "soc.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /** soc 子包总开关（soc.toml#enableSocSupport） */
    public static final ModConfigSpec.BooleanValue ENABLE_SOC_SUPPORT = CK
            .comment("游戏内 SoC/芯片支持总开关",
                    "false(默认, 2026-09-30 用户要求先关闭): 整个 soc 子包禁用(不加载方块/事件/指令,已有芯片不推进)",
                    "true: 启用游戏内芯片(沙箱 SoC/RV32 内核/FPGA 预备)",
                    "需要重启游戏生效")
            .define("enableSocSupport", false);

    /** 每 tick 指令预算（等效主频：20 tick/s × N） */
    public static final ModConfigSpec.IntValue CYCLES_PER_TICK = CK
            .comment("每个芯片每 tick 的指令预算(等效主频 = 20 × 该值)",
                    "默认 200000 → 等效 4 MHz；调大更流畅但更吃 CPU",
                    "范围 1000..20000000")
            .defineInRange("cyclesPerTick", 200_000, 1_000, 20_000_000);

    /** 同时存在的芯片数量上限（独占线程上限） */
    public static final ModConfigSpec.IntValue MAX_SANDBOXES = CK
            .comment("同时运行的芯片数量上限(每个独占线程会占一条 OS 线程)",
                    "默认 8；超出时新芯片拒绝放置(可做槽位/散热玩法)")
            .defineInRange("maxSandboxes", 8, 1, 64);

    /** 单芯片地址空间配额 */
    public static final ModConfigSpec.IntValue MAX_MEMORY_BYTES = CK
            .comment("单芯片地址空间配额(字节；RAM+ROM+设备寄存器合计)",
                    "默认 65536(64KB)；防单个芯片吃掉服务器")
            .defineInRange("maxMemoryBytes", 65_536, 1_024, 16_777_216);

    /** 独占线程升级阈值（微秒/tick） */
    public static final ModConfigSpec.IntValue DEDICATED_THRESHOLD_US = CK
            .comment("负载自适应：实测每 tick 耗时超过该值(微秒)则升级为独占线程",
                    "默认 200us；轻负载芯片共享线程池(顺序时间片)，重负载自动独占",
                    "0 = 全部独占(不做共享)")
            .defineInRange("dedicatedThresholdUs", 200, 0, 10_000);

    /** 是否允许客户端侧芯片（多人服务器语义） */
    public static final ModConfigSpec.BooleanValue ALLOW_CLIENT_OWNED = CK
            .comment("是否允许客户端侧发起/上传固件(多人服务器)",
                    "true(默认): 客户端可编译并上传固件,服务端沙箱执行",
                    "false: 仅服务端可创建芯片(防作弊/防注入)")
            .define("allowClientOwned", true);

    /** 客户端：本地无工具链时是否允许请求服务端代编译 */
    public static final ModConfigSpec.BooleanValue ALLOW_REMOTE_COMPILE = CK
            .comment("客户端：本地无 RISC-V 工具链时，是否允许请求服务端代编译",
                    "true(默认): 允许（需服务端开启 enableServerCompile）",
                    "false: 只用本地工具链，没有就提示缺少工具")
            .define("allowRemoteCompile", true);

    /** ★ 服务端代编译总开关（默认关闭） */
    public static final ModConfigSpec.BooleanValue ENABLE_SERVER_COMPILE = CK
            .comment("服务端代编译功能（客户端无工具链时由服务端编译 C 固件）",
                    "false(默认): 关闭——客户端无工具时只提示缺少工具",
                    "true: 开启——服务端用自己的 RISC-V 工具链代编译，受下方核心数/队列/超时限制",
                    "★ 默认关闭；开启会消耗服务端 CPU，请按余量配置核心数")
            .define("enableServerCompile", false);

    /** 服务端编译并发核心数 */
    public static final ModConfigSpec.IntValue SERVER_COMPILE_CORES = CK
            .comment("用于编译的并发核心数（同时进行的编译任务上限）",
                    "默认 2；按服务器 CPU 余量设置，过大将影响游戏 tick")
            .defineInRange("serverCompileCores", 2, 1, 32);

    /** 服务端编译队列上限 */
    public static final ModConfigSpec.IntValue SERVER_COMPILE_QUEUE_LIMIT = CK
            .comment("编译队列上限（超出直接拒绝并回执'排队已满'）", "默认 8")
            .defineInRange("serverCompileQueueLimit", 8, 1, 256);

    /** 服务端单次编译超时 */
    public static final ModConfigSpec.IntValue SERVER_COMPILE_TIMEOUT_MS = CK
            .comment("服务端单次编译超时（毫秒）", "默认 30000")
            .defineInRange("serverCompileTimeoutMs", 30_000, 5_000, 600_000);

    /** 服务端允许的源码大小上限 */
    public static final ModConfigSpec.IntValue SERVER_COMPILE_MAX_SOURCE_BYTES = CK
            .comment("允许上传的源码总大小上限（字节，含 main.c 与附加文件）",
                    "默认 262144（256KB）")
            .defineInRange("serverCompileMaxSourceBytes", 262_144, 1_024, 4_194_304);

    // ==================== 工具链下载（2026-09-15） ====================

    /** 服务端：是否允许代下载工具链（★默认关闭；服务端只管"允许操作"） */
    public static final ModConfigSpec.BooleanValue ENABLE_SERVER_DOWNLOAD = CK
            .comment("服务端：是否允许玩家（OP）请求服务端下载工具链",
                    "false(默认): 关闭——服务端不提供下载",
                    "true: 开启——OP 可经下载 UI 让服务端下载到它自己的 cryptand/tool/<平台>/")
            .define("enableServerDownload", false);

    /** 下载源镜像前缀（空 = 官方源；适用于所有工具/平台） */
    public static final ModConfigSpec.ConfigValue<String> DOWNLOAD_URL_OVERRIDE = CK
            .comment("下载源镜像前缀（加速用；留空 = 用内置官方地址）",
                    "示例：https://mirror.example.com/xpack/  → 只替换文件名，目录由前缀决定")
            .define("downloadUrlOverride", "");

    /** 客户端下载目录（空 = <游戏目录>/cryptand/tool/<平台>/） */
    public static final ModConfigSpec.ConfigValue<String> DOWNLOAD_TARGET_DIR = CK
            .comment("客户端下载目录（留空 = <游戏目录>/cryptand/tool/<平台>/）")
            .define("downloadTargetDir", "");

    /** 服务端下载目录（空 = <服务端运行目录>/cryptand/tool/<平台>/） */
    public static final ModConfigSpec.ConfigValue<String> SERVER_DOWNLOAD_TARGET_DIR = CK
            .comment("服务端下载目录（留空 = <服务端运行目录>/cryptand/tool/<平台>/）")
            .define("serverDownloadTargetDir", "");

    public static final ModConfigSpec SPEC = CK.build();

    // ==================== 运行期读取（spec 未加载时回退默认；构造期安全） ====================

    /**
     * 子包总开关。
     * <p>构造期（ModConfigEvent 之前）spec 尚未加载，故走
     * {@link ConfigLoad#preloadBoolean} 直读配置文件文本——与其它子包
     * （gameinput/sable/simserver/pipez…）的 enabled 判定方式一致。</p>
     */
    public static boolean enabled() {
        return ConfigLoad.preloadBoolean(DOMAIN, "enableSocSupport", false);
    }

    private static String safeString(ModConfigSpec.ConfigValue<String> value, String def) {
        try {
            final String v = value.get();
            return v == null ? def : v;
        } catch (Throwable ignored) {
            return def;
        }
    }

    /** 服务端是否允许代下载（默认关闭） */
    public static boolean enableServerDownload() {
        return safeBool(ENABLE_SERVER_DOWNLOAD, false);
    }

    /** 下载镜像前缀（空 = 官方） */
    public static String downloadUrlOverride() {
        return safeString(DOWNLOAD_URL_OVERRIDE, "");
    }

    /** 客户端下载目录（空 = 默认） */
    public static String downloadTargetDir() {
        return safeString(DOWNLOAD_TARGET_DIR, "");
    }

    /** 服务端下载目录（空 = 默认） */
    public static String serverDownloadTargetDir() {
        return safeString(SERVER_DOWNLOAD_TARGET_DIR, "");
    }

    private static int safeInt(ModConfigSpec.IntValue value, int def) {
        try {
            return value.get();
        } catch (Throwable ignored) {
            return def;   // spec 未加载（构造期）→ 默认值
        }
    }

    private static boolean safeBool(ModConfigSpec.BooleanValue value, boolean def) {
        try {
            return value.get();
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static int cyclesPerTick() {
        return safeInt(CYCLES_PER_TICK, 200_000);
    }

    public static int maxSandboxes() {
        return safeInt(MAX_SANDBOXES, 8);
    }

    public static int maxMemoryBytes() {
        return safeInt(MAX_MEMORY_BYTES, 65_536);
    }

    public static int dedicatedThresholdUs() {
        return safeInt(DEDICATED_THRESHOLD_US, 200);
    }

    public static boolean allowClientOwned() {
        return safeBool(ALLOW_CLIENT_OWNED, true);
    }

    // -------- 编译服务（客户端请求侧 / 服务端提供侧） --------

    /** 客户端：是否允许请求服务端代编译 */
    public static boolean allowRemoteCompile() {
        return safeBool(ALLOW_REMOTE_COMPILE, true);
    }

    /** ★ 服务端：是否开启代编译（默认关闭） */
    public static boolean enableServerCompile() {
        return safeBool(ENABLE_SERVER_COMPILE, false);
    }

    /** 服务端：编译并发核心数 */
    public static int serverCompileCores() {
        return safeInt(SERVER_COMPILE_CORES, 2);
    }

    /** 服务端：编译队列上限 */
    public static int serverCompileQueueLimit() {
        return safeInt(SERVER_COMPILE_QUEUE_LIMIT, 8);
    }

    /** 服务端：单次编译超时 */
    public static int serverCompileTimeoutMs() {
        return safeInt(SERVER_COMPILE_TIMEOUT_MS, 30_000);
    }

    /** 服务端：源码大小上限 */
    public static int serverCompileMaxSourceBytes() {
        return safeInt(SERVER_COMPILE_MAX_SOURCE_BYTES, 262_144);
    }
}
