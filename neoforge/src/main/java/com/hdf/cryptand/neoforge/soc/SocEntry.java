package com.hdf.cryptand.neoforge.soc;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import com.hdf.cryptand.neoforge.soc.config.ConfigSoc;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;

/**
 * ===== 游戏内 SoC/芯片子包入口（2026-09-15）=====
 *
 * <p>内核与沙箱全部实现在 common 纯 Java 子包 {@code com.hdf.cryptand.soc}
 * （<b>零 MC 依赖</b>，可 MC 外自测：{@code gradlew :common:runSocTest}）。
 * 本子包只承载平台侧内容：配置域、方块/物品、事件接线、UI、消息桥。</p>
 *
 * <h3>沙箱纪律（与 core 铁律一致）</h3>
 * <ul>
 *   <li>芯片执行在<b>独立线程</b>（PinnedWorker / 共享池顺序时间片），主线程零计算；</li>
 *   <li>MMIO 读写游戏世界<b>只走消息</b>（tick 边界批量提交），核心不写 Level；</li>
 *   <li>单芯片异常被沙箱收敛为"芯片故障态"，<b>不影响游戏与其它芯片</b>。</li>
 * </ul>
 */
@CryptandSubpackage(id = "soc", order = 96)
public final class SocEntry implements SubpackageEntry {

    public static final SocEntry INSTANCE = new SocEntry();

    /** 构造期最前：注册本子包配置域（soc.toml） */
    @Override
    public void registerConfigs() {
        ConfigSoc.register();
    }

    /** 启用条件：soc.toml#enableSocSupport（false → 子包内容完全不初始化） */
    @Override
    public boolean enabled() {
        return ConfigSoc.enabled();
    }

    @Override
    public String conditionDesc() {
        return "soc.toml#enableSocSupport";
    }

    /**
     * 构造期初始化。
     *
     * <p>当前阶段（内核就绪、玩法未接）只打印诊断；方块/事件/UI 在后续阶段接入，
     * 接入点全部在本方法内（子包删除即消失，core 零编译引用）。</p>
     */
    @Override
    public void init(IEventBus bus) {
        System.out.println("[SoC] 游戏内芯片子包已启用（common/soc 内核：RV32IM + 沙箱）"
                + " cyclesPerTick=" + ConfigSoc.cyclesPerTick()
                + " maxSandboxes=" + ConfigSoc.maxSandboxes()
                + " maxMemoryBytes=" + ConfigSoc.maxMemoryBytes()
                + " dedicatedThresholdUs=" + ConfigSoc.dedicatedThresholdUs()
                + " allowClientOwned=" + ConfigSoc.allowClientOwned()
                + " | 编译：allowRemoteCompile=" + ConfigSoc.allowRemoteCompile()
                + " serverCompile=" + ConfigSoc.enableServerCompile()
                + " cores=" + ConfigSoc.serverCompileCores()
                + " queueLimit=" + ConfigSoc.serverCompileQueueLimit()
                + " timeoutMs=" + ConfigSoc.serverCompileTimeoutMs()
                + " maxSourceBytes=" + ConfigSoc.serverCompileMaxSourceBytes());

        // 网络注册（子包自治）：编译请求/回执 payload
        bus.register(com.hdf.cryptand.neoforge.soc.net.SocNetRegistration.class);

        // 命令注册（子包自治）：/cryptand soc tools | compile example | status
        com.hdf.cryptand.neoforge.soc.command.SocCommand.register();

        // 内容注册：芯片方块 + 部件物品（芯片/内存条/底板/存储/扩展卡）+ 独立创造标签栏（含真彩屏）
        com.hdf.cryptand.neoforge.soc.content.SocContent.register(bus);

        // 客户端专属注册：真彩屏的三条注册线（模型接管 / 独立贴图 / 方块实体渲染器）在
        // com.hdf.cryptand.neoforge.truescreen.TrueScreenContent.register 里挂到 MOD 总线
        // —— 任务 F-2（2026-09-27）已把旧的 soc/client 渲染器（TrueScreenRenderer /
        // CryptandScreenModel）整体删除，soc 这边不再注册任何客户端渲染。
        // 客户端：登录后异步探测工具链并打印"将使用哪些工具"
        if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            SocClientSetup.register();
        }
    }

    /** 服务端 tick：后续在此驱动 SocScheduler（每 tick 派发指令预算） */
    @Override
    public void tick(ServerLevel level) {
        // 阶段 2：SocScheduler.tick(level) —— 派发各芯片预算、回收已卸载芯片
    }
}
