/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/PacketHandler.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.network

import com.hdf.cryptand.neoforge.truescreen.TrueScreenLog
import net.minecraft.core.BlockPos
import net.minecraft.nbt.{CompoundTag, NbtIo}
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level

import java.io.{ByteArrayInputStream, DataInputStream, InputStream}
import java.util.zip.InflaterInputStream
import scala.reflect.{ClassTag, classTag}

/**
 * 屏幕报文的**分发入口**（上游 li.cil.oc.common.PacketHandler 的屏幕子集）。
 *
 * <h3>为什么两端各一份 handler，而不是一个 if</h3>
 * 上游用 [clientHandler] / [serverHandler] 两个变量：客户端侧要引用
 * net.minecraft.client.* （客户端专属类），专用服务端里那些类**不存在** ⇒ 不能在公共代码里
 * 直接引用客户端处理器。两个变量的赋值发生在各自的 dist 分支里（见 [TrueScreenNetwork.register]）。
 *
 * <h3>包体形态（与上游逐字一致）</h3>
 * PacketPayload.data 的第一字节是压缩标记：0 = 明文，1 = 后面是 zlib 流；解出来之后第一字节是
 * [PacketType] 序号。坏包**只记日志**，不抛给网络层（否则一个坏包会把玩家踢下线）。
 */
object PacketHandler {

  /** 客户端处理器（只在客户端 dist 里赋值；专用服务端保持 null）。 */
  var clientHandler: PacketHandler = _

  /** 服务端处理器（客户端 dist 里的**集成服务端**也要用同一个）。 */
  var serverHandler: PacketHandler = _

  /**
   * 收到一条我方通道的报文。
   *
   * @param isClientSide \`context.flow.isClientbound\`：true 表示这条报文"发往客户端"，
   *                     由客户端处理器处理；false 表示"发往服务端"，由服务端处理器处理。
   */
  def handlePacket(isClientSide: Boolean, arr: Array[Byte], player: Player): Unit = {
    var stream: InputStream = null
    try {
      val handler = if (isClientSide) clientHandler else serverHandler
      if (handler != null) {
        stream = new ByteArrayInputStream(arr)
        if (stream.read() != 0) stream = new InflaterInputStream(stream)
        handler.dispatch(handler.createParser(stream, player))
      }
    } catch {
      case e: Throwable => TrueScreenLog.log.warn("[TrueScreen] 收到一条格式错误的屏幕报文（已忽略）", e)
    } finally {
      if (stream != null) {
        stream.close()
      }
    }

    // 上游在这儿顺手重置 ServerPlayer 的挂机计时（"玩家在操作屏幕"）：屏幕交互确实算活跃操作，
    // 但它属于 OC 的整机策略，不是屏幕语义 ⇒ 我方不做（要做得由服务端 tick 侧统一决定）。
  }
}

abstract class PacketHandler {

  /**
   * 取指定维度对应的 Level（客户端只会拿到自己那个维度，服务端按 ResourceKey 查）。
   */
  protected def world(player: Player, dimension: ResourceLocation): Option[Level]

  protected def dispatch(p: PacketParser): Unit

  protected def createParser(stream: InputStream, player: Player): PacketParser

  protected def parser(stream: InputStream, player: Player): PacketParser =
    new PacketParser(stream, player, world)
}

/**
 * 报文解析器：把字节流读成值。
 *
 * 相对上游裁掉了 \`readRegistryEntry / readEntity / readItemStack / readBlockPosCoords\`
 * 与机器人残影（OCBlocks.RobotAfterimage）那一段 —— 屏幕报文不用。\`readBlockEntity\` 保留，
 * 只有"反转触摸模式"用它。
 */
class PacketParser(stream: InputStream, val player: Player, worldResolver: (Player, ResourceLocation) => Option[Level])
  extends DataInputStream(stream) {

  val packetType: PacketType.Value = PacketType(readByte())

  def readBlockEntity[T: ClassTag](): Option[T] = {
    val dimension = ResourceLocation.tryParse(readUTF())
    val x = readInt()
    val y = readInt()
    val z = readInt()
    val pos = new BlockPos(x, y, z)
    worldResolver(player, dimension) match {
      case Some(w) if w.isLoaded(pos) =>
        val t = w.getBlockEntity(pos)
        if (t != null && classTag[T].runtimeClass.isAssignableFrom(t.getClass)) Some(t.asInstanceOf[T]) else None
      case _ => None
    }
  }

  def readDirection(): Option[net.minecraft.core.Direction] = readByte() match {
    case id if id < 0 => None
    case id => Option(net.minecraft.core.Direction.from3DDataValue(id))
  }

  def readNBT(): CompoundTag = {
    val haveNbt = readBoolean()
    if (haveNbt) {
      NbtIo.read(this)
    }
    else null
  }

  def readMedium(): Int = {
    val c0 = readUnsignedByte()
    val c1 = readUnsignedByte()
    val c2 = readUnsignedByte()
    c0 | (c1 << 8) | (c2 << 16)
  }

  def readPacketType(): PacketType.Value = PacketType(readByte())
}
