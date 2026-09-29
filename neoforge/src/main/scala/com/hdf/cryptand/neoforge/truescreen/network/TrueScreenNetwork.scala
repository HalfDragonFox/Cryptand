/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/Proxy.scala  (registerPacket 部分)
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.network

import com.hdf.cryptand.neoforge.truescreen.TrueScreenLog
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadContext
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/**
 * 屏幕报文通道的**唯一注册入口**（上游 li.cil.oc.common.Proxy.registerPacket 的屏幕子集）。
 *
 * 在 MOD 总线上挂一个 [RegisterPayloadHandlersEvent] 监听，注册**一个**双向 payload
 * \`cryptand:truescreen\`（见 [PacketPayload]：一个通道 + 自描述字节流）。
 *
 * <h3>两端各用哪个处理器</h3>
 * <ul>
 *   <li>[PacketHandler.serverHandler] 在这里（公共路径）赋值：客户端 dist 里的**集成服务端**
 *       也要处理客户端发来的输入报文，所以它在任何 dist 下都必须就位；</li>
 *   <li>[PacketHandler.clientHandler] 由客户端专属的 \`client.TrueScreenClientSetup\` 赋值
 *       （那个类引用 net.minecraft.client.*，专用服务端上不存在 ⇒ 不能在这里直接引用，
 *       与上游把 clientHandler 赋值放在 client/Proxy 里是同一个理由）。</li>
 * </ul>
 *
 * ⚠ 通道号带版本（\`versioned("1")\`）：协议就是 [PacketType] 的序号表，改表必须改版本号。
 * 这里**不**用 \`optional()\`：屏幕报文是玩家与世界交互的实体内容，对端必须同版本。
 */
object TrueScreenNetwork {

  /** 由 TrueScreenContent.register 在 mod 构造期调用一次。 */
  def register(modBus: IEventBus): Unit = {
    PacketHandler.serverHandler = ServerPacketHandler

    // 客户端 buffer 索引的"随区块/维度卸载而注销"：挂在 game 总线（事件本身是 game 总线事件）。
    // 这个 object 的处理器只碰 Level/ManagedEnvironment（无客户端专属类型），所以两端都能注册；
    // 服务端上 clientBuffers 恒空。
    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
      com.hdf.cryptand.neoforge.truescreen.common.component.TextBuffer)

    modBus.addListener((event: RegisterPayloadHandlersEvent) => registerPayloads(event))

    TrueScreenLog.log.info("[TrueScreen] 屏幕报文通道已注册：cryptand:truescreen（versioned 1，"
      + PacketType.EndOfList.id + " 种报文类型；服务端处理器已就位）")
  }

  private def registerPayloads(event: RegisterPayloadHandlersEvent): Unit = {
    val registrar: PayloadRegistrar = event.registrar("cryptand").versioned("1")

    registrar.playBidirectional(
      PacketPayload.TYPE,
      PacketPayload.STREAM_CODEC,
      (payload: PacketPayload, context: IPayloadContext) => {
        // 报文处理一律扔回主线程：组件回调/世界访问都只能在主线程（同上游的 enqueueWork）。
        context.enqueueWork(new Runnable {
          override def run(): Unit =
            PacketHandler.handlePacket(context.flow.isClientbound, payload.data, context.player())
        })
      }
    )
  }
}
