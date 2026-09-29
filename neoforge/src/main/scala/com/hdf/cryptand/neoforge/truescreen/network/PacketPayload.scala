/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/PacketPayload.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.network

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * 屏幕报文的**载荷**：一个不透明的字节数组（上游 \`li.cil.oc.common.PacketPayload\` 逐字）。
 *
 * <h3>为什么不给每条报文建一个 CustomPacketPayload 子类</h3>
 * OC 的设计是「**一个** payload 通道 + 自描述字节流」：包体第一个字节是
 * [PacketType] 序号，后面按类型各自序列化；\`TextBufferMulti\` 更是一个压缩包里塞 N 条子命令
 * （见 [PacketBuilder]）。这样每条屏的每 tick 增量只走**一次** send（和一次 zlib 压缩），
 * 而不是 N 条独立的 payload —— 屏幕是高频小包设备，这个形态是上游为它选的。
 *
 * ⚠ 通道 id 用我方命名空间 \`cryptand:truescreen\`，**不是** OC 的 \`opencomputers:packet\`：
 * OC 的通道由 OC 自己的 PacketHandler 处理（它的处理器只认 OC 自己的组件类），我们**不能**借道，
 * 也不要往 OC 的通道里塞我们的字节流（那是第二条能生效的路径）。
 */
class PacketPayload(val data: Array[Byte]) extends CustomPacketPayload {
  override def `type`(): CustomPacketPayload.Type[_ <: CustomPacketPayload] = PacketPayload.TYPE
}

object PacketPayload {

  /** 通道 id（与 \`PayloadRegistrar.versioned("1")\` 一起构成协议身份）。 */
  val TYPE: CustomPacketPayload.Type[PacketPayload] =
    new CustomPacketPayload.Type[PacketPayload](ResourceLocation.fromNamespaceAndPath("cryptand", "truescreen"))

  /**
   * 字节数组编解码：长度 + 原始字节（上游逐字）。
   *
   * ⚠ 包体自身已经 zlib 压缩（见 [CompressedPacketBuilder]），这里**不再**套一层压缩：
   * 队列里的字节就是上线字节。
   */
  val STREAM_CODEC: StreamCodec[ByteBuf, PacketPayload] = StreamCodec.of[ByteBuf, PacketPayload](
    (buf: ByteBuf, payload: PacketPayload) => {
      buf.writeInt(payload.data.length)
      buf.writeBytes(payload.data)
      ()
    },
    (buf: ByteBuf) => {
      val len = buf.readInt()
      val bytes = new Array[Byte](len)
      buf.readBytes(bytes)
      new PacketPayload(bytes)
    }
  )
}
