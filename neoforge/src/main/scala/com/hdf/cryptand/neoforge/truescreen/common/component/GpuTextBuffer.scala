/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/component/GpuTextBuffer.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.component

import java.io.InvalidObjectException

import com.hdf.cryptand.neoforge.truescreen.common.component.traits.{TextBufferProxy, VideoRamRasterizer}
import com.hdf.cryptand.neoforge.truescreen.util.{TextBuffer => UtilTextBuffer}
import com.mojang.blaze3d.vertex.PoseStack
import li.cil.oc.api
import li.cil.oc.api.internal.TextBuffer.ColorDepth
import li.cil.oc.api.network.{Environment, Message, Node}
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.world.entity.player.Player
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.neoforged.neoforge.common.MutableDataComponentHolder

/**
 * GPU 显存里的一页"字符缓冲"（可被 bitblt 到屏幕上，或作为 bitblt 的目标）。
 *
 * 改动：去掉 `ClientGpuTextBufferHandler`（报文处理器用的入口，属任务 C）、
 * 去掉未使用的 `ClientAccessHelper`/`OCComponents` import。
 */
class GpuTextBuffer(val owner: String, val id: Int, val data: UtilTextBuffer) extends traits.TextBufferProxy {

  // the gpu ram does not join nor is searchable to the network
  // this field is required because the api TextBuffer is an Environment
  override def node(): Node = {
    throw new InvalidObjectException("GpuTextBuffers do not have nodes")
  }

  override def getMaximumWidth: Int = data.width
  override def getMaximumHeight: Int = data.height
  override def getViewportWidth: Int = data.height
  override def getViewportHeight: Int = data.width

  var dirty: Boolean = true
  override def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean): Unit = dirty = true
  override def onBufferColorChange(): Unit = dirty = true
  override def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Unit = dirty = true
  override def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Int): Unit = dirty = true

  override def loadData(holder: DataComponentHolder): Unit = {
    data.loadData(holder)

    dirty = true
  }

  override def saveData(holder: MutableDataComponentHolder): Unit = {
    data.saveData(holder)

    dirty = false
  }

  override def setEnergyCostPerTick(value: Double): Unit = {}
  override def getEnergyCostPerTick: Double = 0
  override def setPowerState(value: Boolean): Unit = {}
  override def getPowerState: Boolean = false
  override def setMaximumResolution(width: Int, height: Int): Unit = {}
  override def setAspectRatio(width: Double, height: Double): Unit = {}
  override def getAspectRatio: Double = 1
  override def setResolution(width: Int, height: Int): Boolean = false
  override def setViewport(width: Int, height: Int): Boolean = false
  override def setMaximumColorDepth(depth: ColorDepth): Unit = {}
  override def getMaximumColorDepth: ColorDepth = data.format.depth
  @OnlyIn(Dist.CLIENT)
  override def renderText(stack: PoseStack): Boolean = false
  override def renderWidth: Int = 0
  override def renderHeight: Int = 0
  override def setRenderingEnabled(enabled: Boolean): Unit = {}
  override def isRenderingEnabled: Boolean = false
  override def keyDown(character: Char, code: Int, player: Player): Unit = {}
  override def keyUp(character: Char, code: Int, player: Player): Unit = {}
  override def textInput(codePt: Int, player: Player): Unit = {}
  override def clipboard(value: String, player: Player): Unit = {}
  override def mouseDown(x: Double, y: Double, button: Int, player: Player): Unit = {}
  override def mouseDrag(x: Double, y: Double, button: Int, player: Player): Unit = {}
  override def mouseUp(x: Double, y: Double, button: Int, player: Player): Unit = {}
  override def mouseScroll(x: Double, y: Double, delta: Int, player: Player): Unit = {}
  override def canUpdate: Boolean = false
  override def update(): Unit = {}
  override def onConnect(node: Node): Unit = {}
  override def onDisconnect(node: Node): Unit = {}
  override def onMessage(message: Message): Unit = {}
}

object GpuTextBuffer {
  def wrap(owner: String, id: Int, data: UtilTextBuffer): GpuTextBuffer = new GpuTextBuffer(owner, id, data)

  def bitblt(dst: api.internal.TextBuffer, col: Int, row: Int, w: Int, h: Int, src: api.internal.TextBuffer, fromCol: Int, fromRow: Int): Unit = {
    val x = col - 1
    val y = row - 1
    val fx = fromCol - 1
    val fy = fromRow - 1
    var adjustedDstX = x
    var adjustedDstY = y
    var adjustedWidth = w
    var adjustedHeight = h
    var adjustedSourceX = fx
    var adjustedSourceY = fy

    if (x < 0) {
      adjustedWidth += x
      adjustedSourceX -= x
      adjustedDstX = 0
    }

    if (y < 0) {
      adjustedHeight += y
      adjustedSourceY -= y
      adjustedDstY = 0
    }

    if (adjustedSourceX < 0) {
      adjustedWidth += adjustedSourceX
      adjustedDstX -= adjustedSourceX
      adjustedSourceX = 0
    }

    if (adjustedSourceY < 0) {
      adjustedHeight += adjustedSourceY
      adjustedDstY -= adjustedSourceY
      adjustedSourceY = 0
    }

    adjustedWidth -= ((adjustedDstX + adjustedWidth) - dst.getWidth) max 0
    adjustedWidth -= ((adjustedSourceX + adjustedWidth) - src.getWidth) max 0

    adjustedHeight -= ((adjustedDstY + adjustedHeight) - dst.getHeight) max 0
    adjustedHeight -= ((adjustedSourceY + adjustedHeight) - src.getHeight) max 0

    // anything left?
    if (adjustedWidth <= 0 || adjustedHeight <= 0) {
      return
    }

    dst match {
      case dstScreen: TextBuffer => src match {
        case srcGpu: GpuTextBuffer => write_vram_to_screen(dstScreen, adjustedDstX, adjustedDstY, adjustedWidth, adjustedHeight, srcGpu, adjustedSourceX, adjustedSourceY)
        case _ => throw new UnsupportedOperationException("Source buffer does not support bitblt operations to a screen")
      }
      case dstGpu: GpuTextBuffer => src match {
        case srcProxy: TextBufferProxy => write_to_vram(dstGpu, adjustedDstX, adjustedDstY, adjustedWidth, adjustedHeight, srcProxy, adjustedSourceX, adjustedSourceY)
        case _ => throw new UnsupportedOperationException("Source buffer does not support bitblt operations")
      }
      case _ => throw new UnsupportedOperationException("Destination buffer does not support bitblt operations")
    }
  }

  def write_vram_to_screen(dstScreen: TextBuffer, x: Int, y: Int, w: Int, h: Int, srcRam: GpuTextBuffer, fx: Int, fy: Int): Boolean = {
    if (dstScreen.data.rawcopy(x + 1, y + 1, w, h, srcRam.data, fx + 1, fy + 1)) {
      // rawcopy returns true only if data was modified
      dstScreen.addBuffer(srcRam)
      dstScreen.onBufferBitBlt(x + 1, y + 1, w, h, srcRam, fx + 1, fy + 1)
      true
    } else false
  }

  def write_to_vram(dstRam: GpuTextBuffer, x: Int, y: Int, w: Int, h: Int, src: TextBufferProxy, fx: Int, fy: Int): Boolean = {
    if (dstRam.data.rawcopy(x + 1, y + 1, w, h, src.data, fx + 1, fy + 1)) {
      dstRam.dirty = true
      true
    } else false
  }
}
