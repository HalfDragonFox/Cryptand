/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/Proxy.scala  (客户端注册接线)
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client

import com.hdf.cryptand.neoforge.truescreen.TrueScreenContent
import com.hdf.cryptand.neoforge.truescreen.TrueScreenLog
import com.hdf.cryptand.neoforge.truescreen.client.renderer.block.TrueScreenModelInitialization
import com.hdf.cryptand.neoforge.truescreen.client.renderer.tileentity.ScreenRenderer
import com.hdf.cryptand.neoforge.truescreen.network.{ClientPacketHandler, PacketHandler, ServerPacketHandler}
import net.neoforged.api.distmarker.{Dist, OnlyIn}
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.client.event.{EntityRenderersEvent, RegisterClientReloadListenersEvent}

/**
 * 客户端的三条注册线 + 客户端报文处理器就位（**任务 C 的注册入口**）。
 *
 * <h3>为什么必须由 mod 构造期在 MOD 总线挂上（而不是 @EventBusSubscriber）</h3>
 * 这三个事件都是 \`IModBusEvent\`（MOD 总线事件）：挂到 \`NeoForge.EVENT_BUS\` 上**不会报错**，
 * 只是永远不触发 —— 症状分别是"贴图取到 missing"（模型不接管）、"字形图集是紫黑"（独立贴图没注册）、
 * "方块在、像素看不见"（宿主渲染器没注册）。本仓已有前车之鉴（SocClientSetup 的注释）。
 *
 * <h3>三条线</h3>
 * <ol>
 *   <li>[TrueScreenModelInitialization]：\`ModelEvent.ModifyBakingResult\` —— 把 12 个屏幕方块的
 *       烘焙模型换成 [com.hdf.cryptand.neoforge.truescreen.client.renderer.block.ScreenModel]；</li>
 *   <li>[TrueScreenTextures]：\`RegisterClientReloadListenersEvent\` —— 注册 \`cryptand:font/chars\`
 *       这类**独立贴图**（不在方块图集里，必须自己 register）；</li>
 *   <li>[ScreenRenderer]：\`EntityRenderersEvent.RegisterRenderers\` —— 给
 *       [TrueScreenContent.SCREEN_BE] 注册方块实体渲染器（内容 → 像素的入口）。</li>
 * </ol>
 *
 * ⚠ 加上 [PacketHandler.clientHandler] 的赋值：它引用客户端专属类型，只能在这个
 * dist 专属的类里做（上游同款，见 net/TrueScreenNetwork 的注释）。
 *
 * ⚠ 本类**只能**由客户端 dist 调用（\`TrueScreenContent.register\` 里做了 dist 判断）：
 * 它引用的 net.minecraft.client.* 在专用服务端上不存在。
 */
@OnlyIn(Dist.CLIENT)
object TrueScreenClientSetup {

  def register(modBus: IEventBus): Unit = {
    // 报文处理器的客户端侧（服务端侧由 TrueScreenNetwork.register 在公共路径上赋值）。
    PacketHandler.clientHandler = ClientPacketHandler
    PacketHandler.serverHandler = ServerPacketHandler

    // ① 动态方块模型接管
    modBus.register(TrueScreenModelInitialization)

    // ② 独立贴图（字形图集）
    modBus.register(TrueScreenTextures)

    // ③ 方块实体渲染器
    modBus.addListener((event: EntityRenderersEvent.RegisterRenderers) =>
      event.registerBlockEntityRenderer(TrueScreenContent.SCREEN_BE.get(), ScreenRenderer))

    TrueScreenLog.log.info("[TrueScreen] 客户端注册完成：模型接管(ModelEvent.ModifyBakingResult)"
      + " + 独立贴图(RegisterClientReloadListenersEvent) + 屏幕渲染器(EntityRenderersEvent.RegisterRenderers)"
      + " + 客户端报文处理器")
  }
}
