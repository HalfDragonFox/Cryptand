/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/server/PacketHandler.scala  (屏幕部分)
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.network

import com.hdf.cryptand.neoforge.truescreen.TrueScreenLog
import com.hdf.cryptand.neoforge.truescreen.common.{blockentity, component}
import com.hdf.cryptand.neoforge.truescreen.common.datacomponents.CompoundStorage
import com.hdf.cryptand.neoforge.opencomputers.config.ConfigOpenComputers
import com.hdf.cryptand.soc.board.{RequestTokenBucket, ViewRangeLimit}
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.{CompoundTag, NbtOps}
import net.minecraft.resources.{ResourceKey, ResourceLocation}
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.neoforged.neoforge.server.ServerLifecycleHooks

import java.io.InputStream

/**
 * 服务端侧的屏幕报文处理器（上游 li.cil.oc.server.PacketHandler 的屏幕子集）。
 *
 * 处理 9 类：KeyDown / KeyUp / TextInput / Clipboard / MouseClickOrDrag / MouseScroll /
 * MouseUp / CopyToAnalyzer / TextBufferInit（客户端索要快照）。
 *
 * <h3>查找与鉴权（与上游逐字同构）</h3>
 * 一律 \`ComponentTracker.get(p.player.level, address)\` 找组件，再 \`canInteractWith\` 校验
 * "玩家确实贴着这块屏"（距离 ≤ 8 格）—— 否则任何人发一个地址就能往别人屏幕里打字。
 *
 * ⚠ 用的是 **OC 自己的服务端组件索引**（\`li.cil.oc.server.ComponentTracker\`）：
 * 我方 \`component.TextBuffer.onConnect\` 就是往它里面注册的（A 已落地），不另建索引。
 * 组件方法本身（keyDown/…）在 ServerProxy 里只碰纯数据/节点信号，世界写操作仍只发生在 tick。
 */
object ServerPacketHandler extends PacketHandler {

  /**
   * 每玩家的帧请求令牌桶（2026-09-29 用户定案：服务端被动，但要有速率上限）。
   *
   * <p>速率按**玩家**计（一个玩家同时盯十块屏也不该刷爆服务端），
   * 参考外设船舵/磁盘 IOPS 的限流手法：令牌桶 + 给定时钟，**绝不 sleep**（主线程铁律），
   * 超限就丢弃 —— 客户端下一 tick 自然会再来，不需要重试状态机。</p>
   */
  private val frameRequestBuckets =
    new java.util.concurrent.ConcurrentHashMap[java.util.UUID, RequestTokenBucket]()

  /** 被拒计数（用于节流打印：前几次 + 每 500 次，避免刷屏反而看不见真异常）。 */
  private val frameRequestsRejected = new java.util.concurrent.atomic.AtomicLong()

  private def frameRequestBucket(player: java.util.UUID): RequestTokenBucket = {
    val existing = frameRequestBuckets.get(player)
    if (existing != null) {
      existing
    } else {
      val fresh = new RequestTokenBucket(ConfigOpenComputers.trueScreenServerRequestsPerSecond(),
        ConfigOpenComputers.trueScreenServerBurst())
      val raced = frameRequestBuckets.putIfAbsent(player, fresh)
      if (raced != null) raced else fresh
    }
  }

  private def noteFrameRequestRejected(why: String, address: String, distance: Double,
                                       maxRange: Double): Unit = {
    val n = frameRequestsRejected.incrementAndGet()
    if (n <= 5L || (n % 500L) == 0L) {
      TrueScreenLog.log.info("[TrueScreen] 帧请求被拒（{}）：address={} 距离={} 最大范围={} 累计拒绝={}",
        why, address, java.lang.Double.valueOf(distance),
        ViewRangeLimit.describe(maxRange), java.lang.Long.valueOf(n))
    }
  }

  /** 诊断计数（临时）：快照请求/发送各打印前 8 次 */
  private var diagServerInit = 0

  /** 诊断计数（临时，2026-09-28）：图像帧请求前 8 次 */
  private var diagFrameReq = 0L

  override protected def world(player: Player, dimension: ResourceLocation): Option[Level] =
    Option(ServerLifecycleHooks.getCurrentServer.getLevel(ResourceKey.create(Registries.DIMENSION, dimension)))

  override protected def createParser(stream: InputStream, player: Player): PacketParser = parser(stream, player)

  override def dispatch(p: PacketParser): Unit = {
    p.packetType match {
      case PacketType.Clipboard => onClipboard(p)
      case PacketType.CopyToAnalyzer => onCopyToAnalyzer(p)
      case PacketType.KeyDown => onKeyDown(p)
      case PacketType.KeyUp => onKeyUp(p)
      case PacketType.MouseClickOrDrag => onMouseClick(p)
      case PacketType.MouseScroll => onMouseScroll(p)
      case PacketType.MouseUp => onMouseUp(p)
      case PacketType.TextBufferInit => onTextBufferInit(p)
      case PacketType.ScreenFrameRequest => onScreenFrameRequest(p)
      case PacketType.TextInput => onTextInput(p)
      case _ => // 其他方向/其他子系统的类型：忽略。
    }
  }

  // ==================== 输入 ====================

  def onKeyDown(p: PacketParser): Unit = {
    val address = p.readUTF()
    val key = p.readChar()
    val code = p.readInt()
    withBuffer(p, address) { buffer => buffer.keyDown(key, code, p.player) }
  }

  def onKeyUp(p: PacketParser): Unit = {
    val address = p.readUTF()
    val key = p.readChar()
    val code = p.readInt()
    withBuffer(p, address) { buffer => buffer.keyUp(key, code, p.player) }
  }

  def onTextInput(p: PacketParser): Unit = {
    val address = p.readUTF()
    val codePt = p.readInt()
    // 非法码点直接丢弃（上游同款：不给 guest 塞一个不是 Unicode 的东西）。
    if (codePt >= 0 && codePt <= Character.MAX_CODE_POINT) {
      withBuffer(p, address) { buffer => buffer.textInput(codePt, p.player) }
    }
  }

  def onClipboard(p: PacketParser): Unit = {
    val address = p.readUTF()
    val copy = p.readUTF()
    if (copy.length > PacketBuilder.MaxClipboardTextLength) return
    withBuffer(p, address) { buffer => buffer.clipboard(copy, p.player) }
  }

  def onMouseClick(p: PacketParser): Unit = {
    val address = p.readUTF()
    val x = p.readFloat()
    val y = p.readFloat()
    val dragging = p.readBoolean()
    val button = p.readByte()
    withBuffer(p, address) { buffer =>
      if (dragging) buffer.mouseDrag(x, y, button, p.player)
      else buffer.mouseDown(x, y, button, p.player)
    }
  }

  def onMouseUp(p: PacketParser): Unit = {
    val address = p.readUTF()
    val x = p.readFloat()
    val y = p.readFloat()
    val button = p.readByte()
    withBuffer(p, address) { buffer => buffer.mouseUp(x, y, button, p.player) }
  }

  def onMouseScroll(p: PacketParser): Unit = {
    val address = p.readUTF()
    val x = p.readFloat()
    val y = p.readFloat()
    val scroll = p.readByte()
    withBuffer(p, address) { buffer => buffer.mouseScroll(x, y, scroll, p.player) }
  }

  def onCopyToAnalyzer(p: PacketParser): Unit = {
    val address = p.readUTF()
    val line = p.readInt()
    withBuffer(p, address) { buffer => buffer.copyToAnalyzer(line, p.player) }
  }

  // ==================== 快照 ====================

  /**
   * 客户端索要"这块屏的完整快照"。
   *
   * 只回 \`origin\`（拼合屏的主块）：非 origin 的从块没有自己的字符面，
   * 它显示的是 origin 的内容（客户端把非 origin 的 buffer 也注册进索引只是为了
   * 接收被 fill 成空白的占位内容，见 blockentity.Screen.updateEntity）。
   */
  def onTextBufferInit(p: PacketParser): Unit = {
    val address = p.readUTF()
    p.player match {
      case entity: ServerPlayer =>
        li.cil.oc.server.ComponentTracker.get(p.player.level, address) match {
          case Some(buffer: component.TextBuffer) =>
            buffer.host match {
              case screen: blockentity.Screen => screen.ensureServerBufferLoaded()
              case _ =>
            }
            if (buffer.host match {
              case screen: blockentity.Screen if !screen.isOrigin => false
              case _ => true
            }) {
              if (diagServerInit < 8) {
                TrueScreenLog.log.info("[TrueScreen/DIAG] 服务端收到快照请求 address={} 找到=是 viewport={}x{} 即将发送",
                  address, Integer.valueOf(buffer.getViewportWidth), Integer.valueOf(buffer.getViewportHeight))
                diagServerInit += 1
              }
              val nbt = new CompoundStorage()
              buffer.data.saveData(nbt)
              ServerPacketSender.sendTextBufferInit(
                address,
                CompoundStorage.CODEC.encode(nbt, NbtOps.INSTANCE, new CompoundTag()).getOrThrow().asInstanceOf[CompoundTag],
                buffer.getMaximumWidth,
                buffer.getMaximumHeight,
                buffer.getMaximumColorDepth.ordinal,
                buffer.getViewportWidth,
                buffer.getViewportHeight,
                entity
              )
            }
          case _ =>
            if (diagServerInit < 8) {
              TrueScreenLog.log.info("[TrueScreen/DIAG] 服务端收到快照请求 address={} 找到=否（ComponentTracker 无此地址）", address)
              diagServerInit += 1
            }
        }
      case _ => // 控制台/假玩家：忽略。
    }
  }

  /**
   * 客户端请求一帧图像（拉取模型：先全量、再增量）。
   *
   * **服务端是被动的**（用户定案 2026-09-29）：是否看到屏、距离多远，全部由**客户端自己判断并发包**，
   * 服务端不按距离降采样 —— LOD 与渲染都归客户端。因此这里恒以 step=1 合成，
   * 脏块坐标天然等于**全屏像素坐标**（用户口径："像素坐标是针对全屏的"）。
   * 设备还没建立（没进像素模式）时就丢弃 —— 客户端下一 tick 会再来，不需要任何重试状态。
   */
  def onScreenFrameRequest(p: PacketParser): Unit = {
    val address = p.readUTF()
    val full = p.readBoolean()
    // 诊断（临时，2026-09-28）：请求到没到、几何是多少、有没有回帧
    diagFrameReq += 1
    val diag = diagFrameReq <= 8
    p.player match {
      case entity: ServerPlayer =>
        withBuffer(p, address) { buffer =>
          buffer.host match {
            case screen: blockentity.Screen if screen.isOrigin =>
              // ⚠ graphicsIfAny（不按需创建）：**无 GPU 的屏不接入沙箱**（用户定案 2026-09-28），
              //   它只应在沙箱设备表里记成"未连接"，不产生任何帧。
              val g = screen.graphicsIfAny
              if (g != null) {
                val (px, py) = g.pixelSize()
                if (px > 0 && py > 0) {
                  // 服务端被动：不选采样步长（客户端自己决定看不看、看多大）。
                  // step 恒 1 ⇒ 合成出来的采样网格就是像素网格 ⇒ 脏块矩形 = 全屏像素坐标。
                  //
                  // 但被动 ≠ 无条件：客户端可被伪造，所以两道安全闸留在服务端
                  // （用户 2026-09-29：最大显示范围防注入扫描 + 请求速率上限防资源放大）。
                  val maxRange = ConfigOpenComputers.trueScreenMaxViewDistance()
                  val distance = if (screen.screens.isEmpty) {
                    0.0
                  } else {
                    // ⚠ 这里算距离**只用于准入**，不用来选采样（LOD 全在客户端）
                    screen.screens.map(part =>
                      math.sqrt(entity.distanceToSqr(part.xPosition, part.yPosition, part.zPosition))).min
                  }
                  if (!ViewRangeLimit.allows(distance, maxRange)) {
                    noteFrameRequestRejected("超出最大显示范围", address, distance, maxRange)
                  } else if (!frameRequestBucket(entity.getUUID).tryAcquire(System.nanoTime())) {
                    noteFrameRequestRejected("请求过频（令牌桶）", address, distance, maxRange)
                  } else {
                    if (diag) {
                      TrueScreenLog.log.info("[TrueScreen/DIAG] 收到图像帧请求 address={} full={} 设备={}x{} 距离={} 全分辨率（服务端被动，不做 LOD）",
                        address, java.lang.Boolean.valueOf(full), Integer.valueOf(px), Integer.valueOf(py),
                        java.lang.Double.valueOf(distance))
                    }
                    g.requestImageFrame(full, 1, 1, entity.getUUID)
                  }
                } else if (diag) {
                  TrueScreenLog.log.info("[TrueScreen/DIAG] 图像帧请求被丢：几何为 0（address={} 设备={}x{}）", address,
                    Integer.valueOf(px), Integer.valueOf(py))
                }
              } else if (diag) {
                TrueScreenLog.log.info("[TrueScreen/DIAG] 图像帧请求被丢：没有图形通道（address={}）", address)
              }
            case _ => // 非 origin：它显示 origin 的内容，不回帧（与 TextBufferInit 同一口径）
          }
        }
      case _ => // 控制台/假玩家：忽略
    }
  }
  // ==================== 辅助 ====================

  /** 按地址找组件并校验交互距离；找不到/不合规就静默丢弃（上游同款）。 */
  private def withBuffer(p: PacketParser, address: String)(f: component.TextBuffer => Unit): Unit = {
    li.cil.oc.server.ComponentTracker.get(p.player.level, address) match {
      case Some(buffer: component.TextBuffer) if canInteractWith(buffer, p.player) => f(buffer)
      case _ => // Invalid Packet
    }
  }

  /**
   * 玩家是否在够得着的位置操作这块屏。
   *
   * 上游还认"远程终端宿主"（机架 KVM）—— 那个子系统本次没移植，所以这里只剩屏幕两个分支：
   * 屏幕 → 拼合里任意一块离玩家 ≤ 8 格；其它宿主（机架里的屏物品）→ 放行（与上游 \`case _ => true\` 一致）。
   */
  private def canInteractWith(buffer: component.TextBuffer, player: Player): Boolean = buffer.host match {
    case screen: blockentity.Screen => screen.screens.exists(part =>
      player.distanceToSqr(part.xPosition, part.yPosition, part.zPosition) <= 64)
    case _ => true
  }
}
