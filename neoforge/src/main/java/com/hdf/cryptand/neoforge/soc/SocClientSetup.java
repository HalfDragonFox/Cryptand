package com.hdf.cryptand.neoforge.soc;

import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * ===== SoC 客户端接线（2026-09-15，仅客户端加载）=====
 *
 * <p>登录后<b>异步</b>探测工具链（实测冷探测 ~13s，必须后台），完成后回主线程打印
 * "将使用哪些编译工具"——即用户要求的"编译前先展示工具信息"。</p>
 *
 * <p>⚠ 本类含客户端专属类型（{@link ClientPlayerNetworkEvent}），只能由
 * {@code FMLEnvironment.dist.isClient()} 分支加载（专用服务端不得引用）。</p>
 */
public final class SocClientSetup {

    private SocClientSetup() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SocClientSetup::onLogin);
        // 客户端命令：/cryptand soc tools（打印客户端工具链与编译方案）
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.RegisterClientCommandsEvent event) ->
                com.hdf.cryptand.neoforge.soc.command.SocCommand.registerClient(event.getDispatcher()));
        // 每帧把已打开面板的计算后布局写到 <gameDir>/cryptand/ui-dump/（AI 可直接读，用于调布局）
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.client.event.ClientTickEvent.Post event) ->
                com.hdf.cryptand.neoforge.soc.ui.SocUiInspector.tick());
    }

    /**
     * 登录时<b>不做</b>工具链探测。
     *
     * <p>用户 2026-09-15 定稿：<b>进入世界不检查工具链</b>——冷探测约 13s（多次进程调用），
     * 不该占用进世界的时机。探测改为<b>按需触发</b>：</p>
     * <ul>
     *   <li>显式：{@code /cryptand soc tools} / 打开下载 UI；</li>
     *   <li>惰性：<b>编译时</b>（{@link ClientToolchainService#compileAsync} 内部按需探测并缓存）。</li>
     * </ul>
     */
    private static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        // 有意留空：探测只在使用时进行
    }
}
