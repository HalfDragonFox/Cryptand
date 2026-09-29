package com.hdf.cryptand.neoforge.aiauto;

import com.hdf.cryptand.core.api.CryptandSubpackage;
import com.hdf.cryptand.neoforge.aiauto.config.ConfigAiauto;
import com.hdf.cryptand.neoforge.core.module.SubpackageEntry;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * ===== AI 自动化子包入口（aiauto，2026-09-15）=====
 *
 * <p>给不同 mod 提供统一的"AI 自动化接口"：<b>文本描述导出 + 截图 + 程序化打开 + 调试器</b>，
 * 产物统一落在 {@code <gameDir>/cryptand/ai-auto/}，AI 直接读文件即可参与调优。</p>
 *
 * <h3>按 mod 分模块</h3>
 * <ul>
 *   <li>{@code aiauto.ldlib} —— LDLib2 界面：布局树 + 截图 + UIDebugger（当前）</li>
 *   <li>后续：其它 UI 库 / 配方浏览 / 数据面板，各自实现 {@link AiTarget} 后
 *       {@link AiAutomation#register} 一行接入</li>
 * </ul>
 *
 * <h3>门控</h3>
 * <p>子包本身常驻（命令始终可见，便于发现），但<b>所有能力由
 * {@code aiauto.toml#enableAiAutomation} 门控</b>（默认 false）；关闭时命令只提示开关位置。</p>
 *
 * <h3>两条 AI 通道（2026-09-16）</h3>
 * <ul>
 *   <li><b>MCP（标准，主通道）</b>：协议框架在 {@code common}（{@code com.hdf.cryptand.mcp}），
 *       平台实现是 {@code aiauto.mcp.server} 包 —— Streamable HTTP 服务、主线程调度、
 *       产物资源（截图/报告）；开关 {@code aiauto.toml#mcpEnabled}；</li>
 *   <li><b>文件 legacy（兜底）</b>：{@code calls/ → results/}，给不能发 HTTP 的调用方，
 *       开关 {@code aiauto.toml#fileChannelEnabled}。</li>
 * </ul>
 */
@CryptandSubpackage(id = "aiauto", order = 97)
public final class AiautoEntry implements SubpackageEntry {

    public static final AiautoEntry INSTANCE = new AiautoEntry();

    @Override
    public void registerConfigs() {
        ConfigAiauto.register();
        // 动态资源框架配置域（2026-09-29）：总闸 + 核心白名单 + 安全开关
        com.hdf.cryptand.neoforge.dynamic.config.ConfigDynamic.register();
    }

    /** 常驻：功能由 aiauto.toml 门控，子包关闭会让用户无从发现开关 */
    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String conditionDesc() {
        return "always（能力开关：aiauto.toml#enableAiAutomation）";
    }

    @Override
    public void init(IEventBus bus) {
        // AI 展示方块（双端注册：方块/物品/BE 类型）
        com.hdf.cryptand.neoforge.aiauto.display.AiDisplayBlocks.register(bus);

        if (FMLEnvironment.dist.isClient()) {
            // 展示方块渲染器
            bus.addListener((net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) ->
                    event.registerBlockEntityRenderer(
                            com.hdf.cryptand.neoforge.aiauto.display.AiDisplayBlocks.DISPLAY_BE.get(),
                            com.hdf.cryptand.neoforge.aiauto.display.AiDisplayRenderer::new));
            // 目标注册（客户端专属类型，服务端不加载）
            // mc 目标 = 纯原版（建筑/命令/查询/存档），永远注册；
            // ldlib 目标只在真的加载了 LDLib2 时注册 —— 它的类是编进 LDLib2 的，
            // 缺少该 mod 时连类加载都会失败，不该让原版能力跟着一起陪葬。
            AiAutomation.register(com.hdf.cryptand.neoforge.aiauto.mc.McTarget.INSTANCE);
            if (net.neoforged.fml.ModList.get().isLoaded("ldlib2")) {
                AiAutomation.register(com.hdf.cryptand.neoforge.aiauto.ldlib.LdlibTarget.INSTANCE);
            }

            // ⚠ 下面两个是**游戏总线（NeoForge.EVENT_BUS）**事件，只能挂在 NeoForge.EVENT_BUS 上。
            //   挂到 mod 总线（bus）会抛 "Listener for event class … is not valid for this bus"，
            //   并让整个 aiauto 子包 init 失败 —— 之后的 tick 驱动与工具注册**全部不会执行**。
            //   2026-09-15 实测：RegisterClientCommandsEvent 在 mod 总线上直接炸掉 init，
            //   结果 calls/ 与 tools.json 永不生成，工具通道整体哑火。
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) ->
                            com.hdf.cryptand.neoforge.aiauto.command.AiCommand.register(event.getDispatcher()));
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                    (net.neoforged.neoforge.client.event.ClientTickEvent.Post event) -> {
                        AiAutomation.tick();
                        AiSession.tick();        // 状态信号 + 事件流
                        com.hdf.cryptand.neoforge.aiauto.mcp.AiToolServer.tick();   // 工具调用（calls/ → results/）
                        com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer.tick(); // MCP 工具在主线程按序执行
                    });
            AiSession.markBoot();
            com.hdf.cryptand.neoforge.aiauto.mcp.AiTools.registerAll();   // 工具清单 + tools.json + MCP 注册

            // 真 MCP 服务器（common 的 com.hdf.cryptand.mcp 框架 + 本平台实现）：
            // 按配置启动（enableAiAutomation && mcpEnabled），配置热重载时自动同步启停。
            com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer.syncFromConfig();
            bus.addListener((net.neoforged.fml.event.config.ModConfigEvent event) -> {
                try {
                    com.hdf.cryptand.neoforge.aiauto.mcp.server.AiMcpServer.syncFromConfig();
                } catch (Throwable ex) {
                    com.mojang.logging.LogUtils.getLogger()
                            .warn("[aiauto/mcp] 配置重载同步失败", ex);
                }
            });

            com.mojang.logging.LogUtils.getLogger().info(
                    "[aiauto] 子包已启用；目标={} 产物目录={}（enableAiAutomation={}）",
                    AiAutomation.targetIds(), AiAutomation.outDir(), ConfigAiauto.enableAiAutomation());
        }
    }
}
