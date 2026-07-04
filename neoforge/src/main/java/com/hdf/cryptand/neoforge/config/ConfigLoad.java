/**
 * ===== 配置层 =====
 *
 * 本文件用于注册模组的NeoForge配置项。配置项存储在游戏的
 * config/cryptand-common.toml 文件中。
 *
 * 涉及多线程化的配置项：
 *
 *   enableCreateAsyncNetwork   - 机械动力（Create）动能网络的多线程应力/容量计算
 *   enablePowergridAsyncNetwork - 交错动力（PowerGrid）电力网络的多线程MNA求解器
 *
 * 两个配置项默认均为 false，需要手动启用并重启游戏生效。
 */

package com.hdf.cryptand.neoforge.config;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.fml.common.Mod;
import com.hdf.cryptand.Cryptand;

@Mod(Cryptand.MOD_ID)
public class ConfigLoad {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue CONFIG_VERSIONS = BUILDER
            .comment("配置文件版本")
            .defineInRange("configVersions", 1, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.BooleanValue ENABLE_OUT_ALL_FEATURE = BUILDER
            .comment("导出所有可生成的特征数据")
            .define("enableOutAllFeature", false);

    /*
     * =====================================================================
     *  实验性多线程化配置
     *  这些参数控制对 Create 和 PowerGrid 模组的网络计算进行多线程化。
     *  默认关闭，启用后需要重启游戏。
     * =====================================================================
     */

    /**
     * 机械动力（Create）动能网络多线程计算开关。
     *
     * 开启后，每个 ServerTick 的 Post 阶段会对当前维度的所有 KineticNetwork
     * 使用 parallelStream() 并行调用 updateNetwork()，
     * 将应力(stress)和容量(capacity)的计算分布到多个线程执行。
     *
     * 安全性：每个 KineticNetwork 是独立的（成员不重叠），无跨网络共享状态。
     * updateNetwork() 内的 sync() 会在工作线程上直接写回 KineticBlockEntity 字段。
     *
     * 对内置计算（纯 float 乘法累加）的提升有限。
     * 主要针对 MCMDK 添加复杂运算到 Create 网络后的场景。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_CREATE_ASYNC_NETWORK = BUILDER
            .comment("启用实验性的机械动力网络多线程计算",
                     "当为true时，动能网络的应力/容量计算会在后台线程并行执行",
                     "需要重启游戏生效")
            .define("enableCreateAsyncNetwork", true);

    /**
     * 交错动力（PowerGrid）电力网络多线程计算开关。
     *
     * 开启后，WorldNetworks.preTick() 中的同步 MNA 求解器循环
     * （prepare → singleTick × multiTick）会被替换为：
     *   1. 主线程同步 prepare() —— 标记矩阵
     *   2. 工作线程池并行 singleTick() —— 每个子网络独立求解
     *
     * 使用固定线程池（FixedThreadPool）而非 ForkJoinPool，
     * 因为 NativeMNA 是 JNI native 代码，在 ForkJoinPool 线程上不可重入。
     *
     * 提升效果：子网络数量越多、矩阵规模越大，多线程化加速越显著。
     */
    public static final ModConfigSpec.BooleanValue ENABLE_POWERGRID_ASYNC_NETWORK = BUILDER
            .comment("启用实验性的电力网络(交错动力)多线程计算",
                     "当为true时，电力子网络的MNA求解器会在工作线程池中并行执行",
                     "需要重启游戏生效")
            .define("enablePowergridAsyncNetwork", true);

    /**
     * PowerGrid 多线程计算超时时间（毫秒）。
     *
     * 后台计算线程中，等待单次求解完成的最大时间。
     * 超时后未完成的子网络会跳过当轮计算。
     * 设为 0 表示无限等待。
     */
    public static final ModConfigSpec.IntValue POWERGRID_COMPUTE_TIMEOUT_MS = BUILDER
            .comment("电力网络单轮计算超时时间(毫秒)",
                     "后台线程等待本轮求解完成的最大时间",
                     "设为0表示无限等待")
            .defineInRange("powergridComputeTimeoutMs", 50, 0, 5000);

    /**
     * PowerGrid 后台计算间隔（毫秒）。
     *
     * 后台线程每隔此毫秒数执行一轮完整的 MNA 求解（prepare + singleTick × multiTick）。
     * 主线程 preTick 不再执行求解，仅同步后台已完成的结果。
     *
     * 推荐值：50ms（与 20TPS 匹配），值越小求解越频繁但 CPU 占用越高。
     */
    public static final ModConfigSpec.IntValue POWERGRID_COMPUTE_INTERVAL_MS = BUILDER
            .comment("电力网络后台计算间隔(毫秒)",
                     "后台线程每隔此时间执行一轮完整求解",
                     "推荐50ms（与20TPS匹配）")
            .defineInRange("powergridComputeIntervalMs", 50, 1, 1000);

    /**
     * PowerGrid 多线程计算最大线程数。
     *
     * 设为 0 表示自动（CPU核心数 - 1），
     * 设为正数表示最大线程数上限。
     */
    public static final ModConfigSpec.IntValue POWERGRID_MAX_THREADS = BUILDER
            .comment("电力网络并行计算最大线程数",
                     "设为0表示自动（CPU核心数 - 1）",
                     "需要重启游戏生效")
            .defineInRange("powergridMaxThreads", 3, 0, 16384);

    public static final ModConfigSpec SPEC = BUILDER.build();
}
