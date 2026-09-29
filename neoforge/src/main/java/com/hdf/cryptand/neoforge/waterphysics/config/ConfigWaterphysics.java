package com.hdf.cryptand.neoforge.waterphysics.config;

import com.hdf.cryptand.neoforge.core.registry.CryptandConfigSpec;
import com.hdf.cryptand.neoforge.core.registry.CryptandRegistries;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * ConfigWaterphysics —— 流体物理（有限水量水流）配置域。
 *
 * <p>读写上限是这套设计的核心：主线程每 tick 的读（格数，一个格 = 自己 + 6 邻居）与写
 * （变更条数）都被限死，保证单帧工作量有界、不卡顿；采集跨 tick 分帧，
 * 整个 region 的点名格全部读完才提交求解（不一半提交）。
 */
public final class ConfigWaterphysics implements CryptandConfigSpec {

    public static final String DOMAIN = "waterphysics";

    public static final ConfigWaterphysics INSTANCE = new ConfigWaterphysics();

    @Override
    public String domain() { return DOMAIN; }

    @Override
    public String fileName() { return "waterphysics.toml"; }

    @Override
    public ModConfigSpec spec() { return SPEC; }

    public static void register() {
        CryptandRegistries.registerConfig(DOMAIN, "waterphysics.toml", SPEC);
    }

    private static final ModConfigSpec.Builder CK = new ModConfigSpec.Builder();

    /** 流体物理总开关。 */
    public static final ModConfigSpec.BooleanValue ENABLE_WATERPHYSICS = CK
            .comment("流体物理总开关",
                     "true(默认): 接管原版流体 tick，按有限水量求解",
                     "false: 完全关闭，回到原版流体行为")
            .define("enableWaterphysics", true);

    /**
     * <b>新液体引擎开关</b>（阶段 2b，<b>默认开</b>）。
     *
     * <p>true（默认）：采集从「点名格」出发沿同种液体的连通性<b>跨 region 展开整片连通水</b>
     * （{@code FluidBodyCollector}，世界坐标，region 概念一次都不出现），整片交给
     * {@code com.hdf.cryptand.fluid.FluidEngine}（纯 Java 零 MC）算一步；写回是同一份
     * {@code FluidWritePlan}（绝对水位）、同一条 {@code WriteBackQueue → FluidApplier} 链路，
     * 整片一次落地（不按 section 拆分）。
     *
     * <p>★ 为什么默认开（用户 2026-09-29 定案「对象为一整块连通水」并要求同时打开）：
     * 旧路径的工作集是「被点名的格」（{@code RegionSnapshot.active}）⇒ 均衡器只看得到局部、
     * 算出<b>假平衡</b> ⇒ 超平坦里倒一桶水只铺两三格、源格水位不降（水在几格之间来回搬）。
     *
     * <p>false 时<b>完全走旧路径</b>（{@code SpreadSolver} + 单 region 快照），旧行为一个字都不变
     * —— 新路径是旁路，可随时回退对比。
     *
     * <p>★ 已知差异（对比时必须知道）：额度的单位从「格数」变成「搬运次数」；被预算打断
     * （或采集被片格数预算截断）的那一批会整批重排、不判收敛，所以同样的
     * {@code computeBudgetPerTick} 下推进节奏与旧路径不同。
     */
    public static final ModConfigSpec.BooleanValue USE_FLUID_ENGINE = CK
            .comment("是否改用新的液体引擎(fluid 包)求解整片连通水(阶段 2b，默认开)",
                     "true(默认): 采集沿同种液体跨 region 展开整片，交 FluidEngine 求解",
                     "false: 完全走旧路径(SpreadSolver + 单 region 快照)，旧行为一字不变",
                     "★ 额度单位从「格数」变成「搬运次数」；被打断的一批整批重排(不判收敛)")
            .define("useFluidEngine", true);

    /**
     * 每 tick 发放的求解额度（<b>格数</b>，每 tick 重置）。
     *
     * <p>单位是「一格」而不是「一次转移」：一次计算里，一格要么被完整处理
     * （向所有更低的邻居各扩散 1 格），要么整个留给下一 tick —— 绝不在格中途停下重做。
     *
     * <p>这是「一次整个计算」能碰多少格的来源：主线程按本 tick 待算的 region 数均分，
     * 随任务一起下发给工作线程。额度用尽是「还没算完」而不是「稳定」——
     * 没算完的 region 保留快照与游标，<b>下一个 tick 拿新额度接着算</b>。
     */
    /**
     * 外部变更是否<b>作废</b>在该 region 上进行中的那一轮计算（默认 true）。
     *
     * <p>★ 诊断开关（2026-09-28 用户要求）：设 false 时不再推进 region 世代、也不再丢弃在途结果 ——
     * 用来判别「连续多格水时的异常」是不是作废造成的。
     * 代价：外部改过的世界可能被旧快照的水位盖回去（会丢改动），所以只用于对比实验。
     */
    /**
     * 延后多少 tick 再算「水位刚变过的那一格」（tick 间隔）。
     *
     * <p>1 = 下一 tick 就算（最快）；调大 ⇒ 水推进更从容、每 tick 的活更少。
     */
    public static final ModConfigSpec.IntValue FLUID_TICK_RATE = CK
            .comment("水位变过之后隔多少 tick 再算它(默认 1)",
                     "1: 下一 tick 继续流(最快)；调大则水推进更慢更省")
            .defineInRange("fluidTickRate", 1, 1, 20);

    /**
     * 压力均衡器的开关与采集视野（默认 16）。
     *
     * <p>均衡器按整片连通水域算（每片一次 flood fill），不再受这个数值限制；
     * 它现在只决定「快照顺带往外读多远」以及是否启用均衡器：0 = 关掉均衡器（只剩逐格流动）。
     */
    public static final ModConfigSpec.IntValue MAX_EQ_DIST = CK
            .comment("压力均衡器视野(默认 16，0=关闭均衡器)",
                     "均衡器按整片连通水域算，这个值只决定快照顺带读多远的邻居")
            .defineInRange("surfaceEqualizeScan", 16, 0, 32);

    public static final ModConfigSpec.BooleanValue ENABLE_EPOCH_INVALIDATION = CK
            .comment("外部变更是否作废在途那一轮计算(默认 true)",
                     "★ 诊断开关：false 时不再推进世代、不丢弃在途结果；",
                     "代价：外部改过的世界可能被旧快照的水位盖回去(会丢改动)")
            .define("enableEpochInvalidation", true);

    public static final ModConfigSpec.IntValue COMPUTE_BUDGET_PER_TICK = CK
            .comment("每tick发放的求解额度(格数，每tick重置)",
                     "对齐其它子包的心跳预算：每tick直接重置为该值，不累加；",
                     "★ 这是**全局**额度（不是每个工作线程一份）：一 tick 里所有 region 加起来最多花这么多，",
                     "  均分后随任务下发给工作线程，所以异步求解不会无限吃 CPU。",
                     "★ 调法：机器弱 / 核少 / 同时开大视距就调小（1024~2048）；强机可加大。",
                     "4096 = 一个 chunk section 的格数（一 tick 最多把一整段走一遍）")
            .defineInRange("computeBudgetPerTick", 4096, 128, 1_048_576);

    /** 主线程每 tick 最多写回的变更条数（写上限）。 */
    public static final ModConfigSpec.IntValue WRITE_BUDGET_PER_TICK = CK
            .comment("主线程每tick写回上限(变更条数)",
                     "每次写回都是一次 setBlock（会触发光照/邻居/客户端包），是最贵的一环；",
                     "默认 1024：一 tick 至多一次大改动的量，写不完的分到后续 tick 继续（不丢）",
                     "★ 条数管不住「单条成本」：写回落到未加载/跨区的格时，一次 setBlock 可能极贵",
                     "  （实机实测：2 次 setBlock = 44.7 ms）。那道防线是 chunk-loaded 门控，不是这个数字。",
                     "★ 调法：主线程卡顿就先降这个（512/256），它直接决定写回段的每 tick 上限。")
            .defineInRange("writeBudgetPerTick", 1024, 64, 262144);

    /** 主线程每 tick 最多读多少个格（读上限）—— 一个格 = 自己 + 6 个邻居。 */
    public static final ModConfigSpec.IntValue MAX_WORK_PER_TICK = CK
            .comment("主线程每tick读取上限(格数)",
                     "一个格 = 该格自身 + 6 个邻居（单格粒度；只读点名格，不扫 section）",
                     "采集跨 tick 分帧，整个 region 的点名格全部读完才提交求解（不一半提交）")
            .defineInRange("maxWorkPerTick", 8192, 256, 1_048_576);

    // ★ 原 maxSpreadStepsPerRound 已删除（2026-09-28）：一次计算固定 = 一次 sweep（所有水格各
    //   向外扩散 1 格），写死在 WaterPhysicsBridge.SWEEPS_PER_COMPUTE。
    //   理由：用户机器上已存在的 toml 里它是旧值 16，读配置会把「一次计算 = 扩散一层」顶掉。

    /** 流体物理推进间隔（tick）。 */
    public static final ModConfigSpec.IntValue FLUID_TICK_INTERVAL = CK
            .comment("流体物理推进间隔(tick)", "1 = 每 tick 推进")
            .defineInRange("fluidTickInterval", 1, 1, 20);

    // ==================================================================
    // 自然水源（2026-09-29）：判据 = 【配置列表里的群系】且【世界生成时就在的水源方块】
    // ==================================================================

    /**
     * 自然水源总开关（默认 true）。
     *
     * <p>true：只有「群系在 {@link #NATURAL_SOURCE_WATER_BIOMES} 列表里」<b>且</b>
     * 「section 第一次被看到时就在」的水源方块才是恒定水源（不会被抽干、别处可以一直从它填）；
     * 玩家在海洋等群系里<b>自己放</b>的水不算。false：一格都不打标。
     *
     * <p>注意：开关只影响<b>采集时打不打标</b>；登记由区块加载驱动，
     * 所以开关关着时加载过的区块不会被登记（重新打开后需要区块重载才会登记）。
     */
    public static final ModConfigSpec.BooleanValue NATURAL_SOURCE_WATER = CK
            .comment("自然水源总开关(默认 true)",
                     "true: 只有【列表里的群系】中【世界生成时就在】的水源方块才算恒定水源",
                     "      这类格不会被抽干，别处可以一直从它填充（无限供水语义）",
                     "false: 一格都不打标（登记由区块加载驱动，重开本项需重载区块）")
            .define("naturalSourceWater", true);

    /** 默认群系白名单（原版水系群系）。 */
    public static final List<String> DEFAULT_NATURAL_SOURCE_BIOMES = List.of(
            "minecraft:ocean", "minecraft:deep_ocean",
            "minecraft:cold_ocean", "minecraft:deep_cold_ocean",
            "minecraft:frozen_ocean", "minecraft:deep_frozen_ocean",
            "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean",
            "minecraft:warm_ocean",
            "minecraft:river", "minecraft:frozen_river");

    /**
     * 自然水源的群系白名单（资源名）。
     *
     * <p>★ 判据是<b>群系命中</b>与<b>世界生成时就在</b>两者同时满足，缺一不可：
     * 玩家在海洋里自己倒的水，群系虽然命中，但它不在「首次登记」的集合里 ⇒ 没有源功能。
     *
     * <p>列表为空 = 不登记任何自然水源（等于关掉本功能）。
     */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NATURAL_SOURCE_WATER_BIOMES = CK
            .comment("自然水源的群系白名单(资源名，如 minecraft:ocean)",
                     "★ 判据 =【群系命中】且【世界生成时就在】，两者必须同时满足",
                     "玩家在海洋里自己倒的水不在「首次登记」集合里 ⇒ 没有源功能",
                     "列表为空 = 不登记任何自然水源（等于关闭本功能）")
            .defineList("naturalSourceWaterBiomes",
                    () -> DEFAULT_NATURAL_SOURCE_BIOMES,
                    () -> "minecraft:ocean",
                    o -> o instanceof String);

    /** 调试输出。 */
    public static final ModConfigSpec.BooleanValue DEBUG = CK
            .comment("调试输出（水位/求解统计）")
            .define("debug", false);

    public static final ModConfigSpec SPEC = CK.build();
}
