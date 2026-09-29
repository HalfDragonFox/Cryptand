/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/PacketBuilder.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.network

import li.cil.oc.api.network.EnvironmentHost
import net.minecraft.core.Direction
import net.minecraft.nbt.{CompoundTag, NbtIo}
import net.minecraft.server.level.{ServerLevel, ServerPlayer}
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.server.ServerLifecycleHooks

import java.io.{BufferedOutputStream, ByteArrayOutputStream, DataOutputStream, OutputStream}
import java.util.zip.{Deflater, DeflaterOutputStream}

/**
 * 屏幕报文的**字节流构造器**（上游 \`li.cil.oc.common.PacketBuilder\` 及其两个子类）。
 *
 * <h3>相对上游的最小替代</h3>
 * <ul>
 *   <li>上游的发送距离上限来自 OC 的 \`Settings.get.maxNetworkClientPacketDistance\`。
 *       我方屏幕参数里**没有**这个开关，也不需要一个"什么都有"的 Settings ⇒ 一律用
 *       \`range = None\`，即上游 \`getOrElse((viewDistance + 1) * 16.0)\` 的原样默认值
 *       （上游在配置为 0 时的行为完全一致）。</li>
 *   <li>\`writeRegistryEntry / writeEntity / writeItemStack / writeBlockPosCoords\` 未搬：
 *       屏幕报文一个都不用（只有 \`writeTileEntity\` 被 \`ScreenTouchMode\` 用到）。<b>不搬是裁剪，
 *       不是留空</b> —— 需要时再按上游补回。</li>
 * </ul>
 */
abstract class PacketBuilder(stream: OutputStream) extends DataOutputStream(stream) {

  /** 方块实体定位（维度 + 坐标），屏幕报文里只有"反转触摸模式"用它。 */
  def writeTileEntity(t: BlockEntity): Unit = {
    writeUTF(t.getLevel.dimension.location.toString)
    writeInt(t.getBlockPos.getX)
    writeInt(t.getBlockPos.getY)
    writeInt(t.getBlockPos.getZ)
  }

  def writeDirection(d: Option[Direction]): Unit = d match {
    case Some(side) => writeByte(side.ordinal.toByte)
    case _ => writeByte(-1: Byte)
  }

  def writeNBT(nbt: CompoundTag): Unit = {
    val haveNbt = nbt != null
    writeBoolean(haveNbt)
    if (haveNbt) {
      NbtIo.write(nbt, this)
    }
  }

  def writeMedium(v: Int): Unit = {
    writeByte(v & 0xFF)
    writeByte((v >> 8) & 0xFF)
    writeByte((v >> 16) & 0xFF)
  }

  def writePacketType(pt: PacketType.Value): Unit = writeByte(pt.id)

  def sendToPlayersNearHost(host: EnvironmentHost, range: Option[Double] = None): Unit = {
    host match {
      case t: BlockEntity => sendToPlayersNearTileEntity(t, range)
      case _ => sendToNearbyPlayers(host.getEnvironmentLevel, host.xPosition, host.yPosition, host.zPosition, range)
    }
  }

  def sendToPlayersNearTileEntity(t: BlockEntity, range: Option[Double] = None): Unit = {
    t.getLevel match {
      case w: ServerLevel =>
        val chunk = new ChunkPos(t.getBlockPos)
        val manager = ServerLifecycleHooks.getCurrentServer.getPlayerList
        val maxPacketRange = range.getOrElse((manager.getViewDistance + 1) * 16.0)
        val maxPacketRangeSq = maxPacketRange * maxPacketRange
        w.getChunkSource.chunkMap.getPlayers(chunk, false).forEach {
          player => if (player.distanceToSqr(t.getBlockPos.getX + 0.5D, t.getBlockPos.getY + 0.5D, t.getBlockPos.getZ + 0.5D) <= maxPacketRangeSq) sendToPlayer(player)
        }
      case _ => sendToNearbyPlayers(t.getLevel, t.getBlockPos.getX + 0.5D, t.getBlockPos.getY + 0.5D, t.getBlockPos.getZ + 0.5D, range)
    }
  }

  def sendToNearbyPlayers(world: net.minecraft.world.level.Level, x: Double, y: Double, z: Double, range: Option[Double]): Unit = {
    val manager = ServerLifecycleHooks.getCurrentServer.getPlayerList
    val maxPacketRange = range.getOrElse((manager.getViewDistance + 1) * 16.0)
    val maxPacketRangeSq = maxPacketRange * maxPacketRange
    manager.getPlayers.forEach {
      player => if (player.level == world && player.distanceToSqr(x, y, z) <= maxPacketRangeSq) sendToPlayer(player)
    }
  }

  def sendToAllPlayers(): Unit =
    PacketDistributor.sendToAllPlayers(new PacketPayload(packet))

  def sendToPlayer(player: ServerPlayer): Unit =
    PacketDistributor.sendToPlayer(player, new PacketPayload(packet))

  def sendToServer(): Unit =
    PacketDistributor.sendToServer(new PacketPayload(packet))

  protected def packet: Array[Byte]
}

/** 保留"到底层流"的类型参数（上游用它在子类里选中 GZIP/Deflater 流）。 */
abstract class PacketBuilderBase[T <: OutputStream](protected val stream: T) extends PacketBuilder(new BufferedOutputStream(stream))

/** 不压缩：包体首字节是"未压缩"标记 0。 */
class SimplePacketBuilder(val packetType: PacketType.Value) extends PacketBuilderBase(PacketBuilder.newData(compressed = false)) {
  writeByte(packetType.id)

  override protected def packet: Array[Byte] = {
    flush()
    stream.toByteArray
  }
}

/**
 * zlib 压缩（BEST_SPEED）：\`TextBufferMulti\` / \`TextBufferInit\` / 剪贴板用它。
 *
 * 屏幕内容是一大片"多数字节相同"的字符/颜色面，压缩率极高；用一个 Deflater 实例贯穿整个
 * 包（不是逐条子命令压一次），所以 [TextBufferMulti][PacketType.TextBufferMulti] 的 N 条命令共用一份压缩流。
 */
class CompressedPacketBuilder(val packetType: PacketType.Value, private val data: ByteArrayOutputStream = PacketBuilder.newData(compressed = true))
  extends PacketBuilderBase(new DeflaterOutputStream(data, new Deflater(Deflater.BEST_SPEED))) {
  writeByte(packetType.id)

  override protected def packet: Array[Byte] = {
    flush()
    stream.finish()
    data.toByteArray
  }
}

object PacketBuilder {

  /**
   * 单次剪贴板内容上限（上游 Settings.get.maxClipboardTextLength 的默认值 32KB）。
   *
   * 客户端发送前与**服务端收到后**都按它判断（上游两端各判一次）：客户端超标整条丢弃，
   * 服务端超标直接 return —— 这样改客户端也塞不进超长文本。
   */
  final val MaxClipboardTextLength = 32 * 1024

  /** 包体首字节标记：1 = 后续字节是 zlib 流（见 [PacketHandler.handlePacket]）。 */
  def newData(compressed: Boolean): ByteArrayOutputStream = {
    val data = new ByteArrayOutputStream
    data.write(if (compressed) 1 else 0)
    data
  }
}
