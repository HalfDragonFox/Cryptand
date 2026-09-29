/**
 * ===== OpenComputers 联动子包入口（2026-09-16）=====
 *
 * <p><b>定位（用户 2026-09-16 定稿）</b>：</p>
 * <ul>
 *   <li><b>Cryptand 芯片可以单独跑</b> —— {@code soc} 子包保持独立、**不依赖本子包**；</li>
 *   <li><b>OC 在场时才叠加联动</b> —— 让我们的处理器插进 OC 机箱，并由我们的
 *       C/RV32 架构（而不是 Lua）执行。</li>
 * </ul>
 *
 * <p><b>分层纪律</b>：核心（RV32 沙箱 / 组件总线 / ABI）全部在 common 的
 * {@code com.hdf.cryptand.soc.oc}（纯 Java，零 MC / 零 OC 依赖，可离线自测）；
 * 本子包只做<b>平台接线</b>：软依赖判断、配置、OC 架构薄壳、组件适配器。
 * 本类<b>不引用 OC 的任何类型</b>（只用 modId 字符串做软依赖探测），因此 OC 缺席时加载它也绝对安全。</p>
 *
 * <p>子包名用<b>全称</b>（{@code opencomputers} 而非 {@code oc}），遵循"子包名字保持完整、防止冲突"的纪律。</p>
 */
package com.hdf.cryptand.neoforge.opencomputers;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import com.hdf.cryptand.neoforge.opencomputers.config.ConfigOpenComputers;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;

@CryptandSubpackage(id = "opencomputers", order = 97)
public final class OpenComputersEntry implements SubpackageEntry {

    public static final OpenComputersEntry INSTANCE = new OpenComputersEntry();

    /** OC 的 modId（软依赖探测用；**不引 OC 任何类**） */
    public static final String OC_MOD_ID = "opencomputers";

    /** 构造期最前：注册本子包配置域（opencomputers.toml） */
    @Override
    public void registerConfigs() {
        ConfigOpenComputers.register();
        LOG.info("[OpenComputers] registerConfigs 已执行（opencomputers.toml 已注册）");
    }

    /**
     * 启用条件：**只看 OC 是否在场**（刻意不读配置）。
     *
     * <p>⚠ 踩坑记录（2026-09-17）：原先写成 {@code ocLoaded() && ConfigOpenComputers.enabled()}，
     * 而 {@code ModuleRegistry} 会对 {@code enabled} 做**缓存求值**；若求值时机早于配置文件加载完成，
     * 读 {@code ModConfigSpec.Value.get()} 会抛异常 ⇒ 被当成 false 并**缓存**，子包此后永不 init
     * （症状：日志里一条 {@code [OpenComputers]} 都没有、驱动没注册、部件插不进机箱）。
     * 因此这里**只判 OC 在场**，配置开关延后到 {@link #init(IEventBus)} 里判断。</p>
     */
    @Override
    public boolean enabled() {
        final boolean loaded = ocLoaded();
        LOG.info("[OpenComputers] enabled() 求值：ocLoaded={}（配置开关延后到 init 判定）", loaded);
        return loaded;
    }

    @Override
    public String conditionDesc() {
        return "OC 已加载（opencomputers）+ opencomputers.toml#enableOpenComputersIntegration（在 init 判定）";
    }

    /** OC 是否在场（软依赖探测；异常一律视为"不在场"） */
    public static boolean ocLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded(OC_MOD_ID);
        } catch (Throwable t) {
            return false;
        }
    }

    /** ⚠ 用 log4j（而不是 System.out）：只有 log4j 会进 run/logs/latest.log，排查时才能看到 */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger("cryptand/opencomputers");

    @Override
    public void init(IEventBus bus) {
        // ⚠⚠ 关键纪律（2026-09-17 实测踩坑）：**子包 init 里绝不能读配置** ——
        //   子包 init 发生在 modloading-worker（构造期），此时配置**尚未加载**，
        //   读 ModConfigSpec.Value.get() 会抛
        //     IllegalStateException: "Cannot get config value before config is loaded."
        //   ⇒ 整个 init 失败（症状：驱动没注册、部件插不进机箱、日志只有一行 init 都没有）。
        //   需要配置的地方一律**延后**到首次使用 / tick / setup 阶段，并做容错。
        LOG.info("[OpenComputers] 联动子包 init 已执行（OC modId={} 在场）"
                + " | 核心在 common/com.hdf.cryptand.soc.oc（纯 Java，零 MC 依赖），本子包只做平台接线"
                + " | 注：配置值在首次使用/热重载时读取（init 早于配置加载，不能在此读）", OC_MOD_ID);

        // ===== P1：注册部件驱动（**不依赖配置**，因此可在 init 内安全执行）=====
        //  让 Cryptand 的处理器/内存条/硬盘/扩展卡/底板插进 OC 机箱（按 OC 的 Slot 规则），
        //  且处理器会让 OC 用我们的 C/RV32 架构（见 CryptandOcArchitecture）。
        //  ⚠ 必须在 OC 的 driver Registry 上锁前调用，否则 OC 抛 IllegalStateException
        //    （register() 对异常做了兜底日志，并会打印注册后自检结果）。
        CryptandOcDrivers.register();

        // ===== P1.5：注册 OC 机器诊断 / 开关机的 MCP 工具 =====
        //  用户要求「没有的 mcp 自己实现」：部件能插进去只证明了一半，"机器是否真的开机、
        //  为什么开不了机"在外部看不到（OC 的 Machine.start() 在节点没接入网络时会**静默**
        //  返回 false，不写日志）。因此补两个原子工具：oc_machine_state / oc_machine_power。
        //  ⚠ 本子包 init 早于 aiauto 的 schema 落盘，因此注册后主动 writeSchema() 一次。
        try {
            com.hdf.cryptand.neoforge.opencomputers.ai.OcAiTools.registerAll();
            com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.writeSchema();
            LOG.info("[OpenComputers] 已注册 MCP 工具：oc_machine_state（读机器内部状态）、"
                    + "oc_machine_power（直接开关机，返回失败原因）");
        } catch (Throwable t) {
            LOG.warn("[OpenComputers] MCP 工具注册失败（不影响部件驱动）", t);
        }
    }

    /**
     * FMLCommonSetup 钩子：**注册绑定之后的第一个安全时机** ⇒ 在这里做驱动自检。
     *
     * <p>⚠ 踩坑记录（2026-09-18）：自检原先在 {@link #init(IEventBus)} 里（经
     * {@code CryptandOcDrivers.register()}）执行，而 init 跑在 mod 构造期 —— 那时 NeoForge
     * 的注册事件还没派发，{@code DeferredHolder.get()} 抛
     * {@code NullPointerException: Trying to access unbound value: ResourceKey[... cryptand:mcu0_32]}，
     * 被 catch 吞成一行"自检异常（忽略）"⇒ 整张自检表一条都没打出来（实测日志）。
     * 驱动注册**必须**留在 init（OC 的 driver 表在 init 后上锁），但自检要挪到这里。</p>
     */
    @Override
    public void commonSetup() {
        CryptandOcDrivers.selfCheck();
    }

    /**
     * 服务端 tick。
     *
     * <p>P2 在此把 OC 机箱里的 Cryptand 芯片交给 {@code OcArchitectureCore} 推进；
     * 开了 {@code useThreadAllocator} 时，实际推进在 common 的线程分配器上，
     * 这里只做 tick 边界的状态汇总与回填。</p>
     */
    @Override
    public void tick(ServerLevel level) {
        // P0：无内容
    }
}
