/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/server/PacketSender.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.network

import com.hdf.cryptand.neoforge.truescreen.common.blockentity
import com.hdf.cryptand.neoforge.truescreen.util.PackedColor
import li.cil.oc.api
import li.cil.oc.api.network.EnvironmentHost
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerPlayer

/**
 * 服务端 → 客户端的**屏幕内容报文**（上游 li.cil.oc.server.PacketSender 的屏幕子集）。
 *
 * 三类：
 * <ol>
 *   <li><b>快照</b> [sendTextBufferInit]：一条 \`TextBufferInit\`（zlib），回答客户端的索要请求；</li>
 *   <li><b>增量</b> \`appendTextBuffer*\`（15 条）：把本 tick 的内容变化追加进**同一个**
 *       \`TextBufferMulti\` 压缩包（包由 component.TextBuffer.pendingCommands 持有，
 *       每 tick 结束由 update() 一次性发出）；</li>
 *   <li><b>状态</b>：TextBufferPowerChange（亮不亮）/ ScreenTouchMode（反转触摸）/ ColorChange /
 *       RotatableState / RedstoneState。</li>
 * </ol>
 *
 * ⚠ append* 系列**只写包、不发送**：调用方（ServerProxy）在 owner 的监视器里调它们，
 * 发送由主线程 tick 统一做 —— 组件回调跑在 OC 的机器线程上，绝不能在那儿碰 Level/网络。
 */
object ServerPacketSender {

  // ==================== TextBufferMulti 的增量命令 ====================

  def appendTextBufferColorChange(pb: PacketBuilder, foreground: PackedColor.Color, background: PackedColor.Color): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiColorChange)
    pb.writeInt(foreground.value)
    pb.writeBoolean(foreground.isPalette)
    pb.writeInt(background.value)
    pb.writeBoolean(background.isPalette)
  }

  def appendTextBufferCopy(pb: PacketBuilder, col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiCopy)
    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeInt(w)
    pb.writeInt(h)
    pb.writeInt(tx)
    pb.writeInt(ty)
  }

  def appendTextBufferDepthChange(pb: PacketBuilder, value: api.internal.TextBuffer.ColorDepth): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiDepthChange)
    pb.writeInt(value.ordinal)
  }

  def appendTextBufferFill(pb: PacketBuilder, col: Int, row: Int, w: Int, h: Int, c: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiFill)
    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeInt(w)
    pb.writeInt(h)
    pb.writeMedium(c)
  }

  def appendTextBufferPaletteChange(pb: PacketBuilder, index: Int, color: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiPaletteChange)
    pb.writeInt(index)
    pb.writeInt(color)
  }

  def appendTextBufferResolutionChange(pb: PacketBuilder, w: Int, h: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiResolutionChange)
    pb.writeInt(w)
    pb.writeInt(h)
  }

  def appendTextBufferViewportResolutionChange(pb: PacketBuilder, w: Int, h: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiViewportResolutionChange)
    pb.writeInt(w)
    pb.writeInt(h)
  }

  def appendTextBufferMaxResolutionChange(pb: PacketBuilder, w: Int, h: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiMaxResolutionChange)
    pb.writeInt(w)
    pb.writeInt(h)
  }

  def appendTextBufferSet(pb: PacketBuilder, col: Int, row: Int, s: String, vertical: Boolean): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiSet)
    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeUTF(s)
    pb.writeBoolean(vertical)
  }

  def appendTextBufferBitBlt(pb: PacketBuilder, col: Int, row: Int, w: Int, h: Int, owner: String, id: Int, fromCol: Int, fromRow: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferBitBlt)
    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeInt(w)
    pb.writeInt(h)
    pb.writeUTF(owner)
    pb.writeInt(id)
    pb.writeInt(fromCol)
    pb.writeInt(fromRow)
  }

  def appendTextBufferRamInit(pb: PacketBuilder, address: String, id: Int, nbt: CompoundTag): Unit = {
    pb.writePacketType(PacketType.TextBufferRamInit)
    pb.writeUTF(address)
    pb.writeInt(id)
    pb.writeNBT(nbt)
  }

  def appendTextBufferRamDestroy(pb: PacketBuilder, owner: String, id: Int): Unit = {
    pb.writePacketType(PacketType.TextBufferRamDestroy)
    pb.writeUTF(owner)
    pb.writeInt(id)
  }

  def appendTextBufferRawSetText(pb: PacketBuilder, col: Int, row: Int, text: Array[Array[Int]]): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiRawSetText)
    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeShort(text.length.toShort)
    for (y <- 0 until text.length.toShort) {
      val line = text(y)
      pb.writeShort(line.length.toShort)
      for (x <- 0 until line.length.toShort) {
        pb.writeMedium(line(x))
      }
    }
  }

  def appendTextBufferRawSetBackground(pb: PacketBuilder, col: Int, row: Int, color: Array[Array[Int]]): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiRawSetBackground)
    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeShort(color.length.toShort)
    for (y <- 0 until color.length.toShort) {
      val line = color(y)
      pb.writeShort(line.length.toShort)
      for (x <- 0 until line.length.toShort) {
        pb.writeInt(line(x))
      }
    }
  }

  def appendTextBufferRawSetForeground(pb: PacketBuilder, col: Int, row: Int, color: Array[Array[Int]]): Unit = {
    pb.writePacketType(PacketType.TextBufferMultiRawSetForeground)
    pb.writeInt(col)
    pb.writeInt(row)
    pb.writeShort(color.length.toShort)
    for (y <- 0 until color.length.toShort) {
      val line = color(y)
      pb.writeShort(line.length.toShort)
      for (x <- 0 until line.length.toShort) {
        pb.writeInt(line(x))
      }
    }
  }

  // ==================== 整包发送 ====================

  /** 完整快照（回答客户端的 TextBufferInit 请求；只发给这一个玩家）。 */
  def sendTextBufferInit(address: String, value: CompoundTag, maxWidth: Int, maxHeight: Int, maxDepth: Int,
                         viewportWidth: Int, viewportHeight: Int, player: ServerPlayer): Unit = {
    val pb = new CompressedPacketBuilder(PacketType.TextBufferInit)
    pb.writeUTF(address)
    pb.writeNBT(value)
    pb.writeInt(maxWidth)
    pb.writeInt(maxHeight)
    pb.writeInt(maxDepth)
    pb.writeInt(viewportWidth)
    pb.writeInt(viewportHeight)
    pb.sendToPlayer(player)
  }

  /** "屏幕现在亮不亮"（通电/断电、算完发光面积之后立刻推一次）。 */
  def sendTextBufferPowerChange(address: String, hasPower: Boolean, host: EnvironmentHost): Unit = {
    val pb = new SimplePacketBuilder(PacketType.TextBufferPowerChange)
    pb.writeUTF(address)
    pb.writeBoolean(hasPower)
    pb.sendToPlayersNearHost(host)
  }

  /**
   * GRAPHICS（像素模式）的一整帧 + 几何 + 调色板 + 锁状态 + 帧号（zlib 压缩，任务 D）。
   *
   * 只由**主线程 tick** 调用（生产者在机器线程只写设备 + 置就绪位，见 TrueScreenGraphics）；
   * 帧字节是一份拷贝，报文序列化不会与生产者并发共享缓冲。
   */
  def sendScreenGraphics(address: String, host: EnvironmentHost, frame: GraphicsFrame): Unit = {
    val pb = new CompressedPacketBuilder(PacketType.ScreenGraphics)
    pb.writeUTF(address)
    GraphicsFrameCodec.write(pb, frame)
    pb.sendToPlayersNearHost(host)
  }

  /**
   * 图像帧同步（2026-09-28）：把服务器**合成好**的一帧 ARGB 图像发给**请求它的那一个玩家**。
   *
   * 与 [sendScreenGraphics] 的分工：那个是像素模式 VRAM 的原样搬运（客户端还要自己解 bpp/调色板），
   * 这个是客户端拉取模型的回答（先全量、再增量），TEXT 与 GRAPHICS 都走它。
   */
  def sendScreenFrame(address: String, frame: ScreenImageFrame, player: ServerPlayer): Unit = {
    val target = if (frame.address == address) frame else frame.copy(address = address)
    // 帧体打包 + 压缩是**纯 CPU**（离线实测：RLE 0.9~2.1ms，zlib 2.9~14.3ms），不该占主线程。
    // 拆法（符合项目铁律"计算在异步、主线程只接收结果"）：
    //   ① 后台：encodeBody（纯函数，只读帧数据）
    //   ② 主线程：建包 + 投递（PacketDistributor 必须主线程）
    // 用 Tasks 门面而不是自己 new 线程；发送用 player.server.execute 保证落到主线程。
    com.hdf.cryptand.dynamic.task.Tasks.read("screen-frame-encode", () =>
      ScreenFrameCodec.encodeBody(target)).thenAccept { bytes =>
      player.server.execute { () =>
        // 编码期间玩家可能已离线 ⇒ 别往断线玩家投包（也不吞异常：直接跳过）
        if (player.hasDisconnected()) {
          return
        }
        val pb = new CompressedPacketBuilder(PacketType.ScreenFrame)
        pb.writeInt(bytes.length)
        pb.write(bytes)
        pb.sendToPlayer(player)
        // 指标打点放在编码+发送之后：此时"上线字节"（含压缩体）才记完，summary 里的数字才有意义。
        val metricsLine = com.hdf.cryptand.soc.board.ScreenFrameMetrics.SERVER.summaryIfDue(10000L)
        if (metricsLine != null) {
          com.hdf.cryptand.neoforge.truescreen.TrueScreenLog.log.info("[metrics] {}", metricsLine)
        }
      }
    }
  }

  /** 反转触摸模式（客户端右键时用它决定 sneak 的行为）。 */
  def sendScreenTouchMode(t: blockentity.Screen, value: Boolean): Unit = {
    val pb = new SimplePacketBuilder(PacketType.ScreenTouchMode)
    pb.writeTileEntity(t)
    pb.writeBoolean(value)
    pb.sendToPlayersNearTileEntity(t)
  }

  /** 外壳颜色（染料/档位色；拼合同色才合并，客户端要立刻看到）。 */
  def sendColorChange(t: blockentity.traits.Colored): Unit = {
    val pb = new SimplePacketBuilder(PacketType.ColorChange)
    pb.writeTileEntity(t)
    pb.writeInt(t.getColor)
    pb.sendToPlayersNearTileEntity(t)
  }

  /** 朝向变了（扳手旋转 / 拼合重算）—— pitch/yaw 推给客户端，它按同一套规则重算拼合。 */
  def sendRotatableState(t: blockentity.traits.Rotatable): Unit = {
    val pb = new SimplePacketBuilder(PacketType.RotatableState)
    pb.writeTileEntity(t)
    pb.writeDirection(Option(t.pitch))
    pb.writeDirection(Option(t.yaw))
    pb.sendToPlayersNearTileEntity(t)
  }

  /** 红石输出状态（客户端自绘缓存用）。字节格式与 [ClientPacketHandler.onRedstoneState] 一一对应。 */
  def sendRedstoneState(t: blockentity.traits.RedstoneAware): Unit = {
    val pb = new SimplePacketBuilder(PacketType.RedstoneState)
    pb.writeTileEntity(t)
    pb.writeBoolean(t.isOutputEnabled)
    for (d <- net.minecraft.core.Direction.values) {
      pb.writeByte(t.getOutput(d))
    }
    pb.sendToPlayersNearTileEntity(t)
  }
}
