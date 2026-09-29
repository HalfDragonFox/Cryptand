/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/component/traits/VideoRamRasterizer.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.component.traits

import com.hdf.cryptand.neoforge.truescreen.common.component
import com.hdf.cryptand.neoforge.truescreen.common.component.GpuTextBuffer
import com.hdf.cryptand.neoforge.truescreen.util.PackedColor
import com.hdf.cryptand.neoforge.truescreen.util.{TextBuffer => UtilTextBuffer}
import net.minecraft.core.component.DataComponentHolder

import scala.collection.mutable

trait VideoRamRasterizer {
  class VirtualRamDevice(val owner: String) extends VideoRamDevice {}
  private val internalBuffers = new mutable.HashMap[String, VideoRamDevice]

  def onBufferRamInit(ram: component.GpuTextBuffer): Unit
  def onBufferBitBlt(col: Int, row: Int, w: Int, h: Int, ram: component.GpuTextBuffer, fromCol: Int, fromRow: Int): Unit
  def onBufferRamDestroy(ram: component.GpuTextBuffer): Unit

  def addBuffer(ram: GpuTextBuffer): Boolean = {
    var gpu = internalBuffers.get(ram.owner)
    if (gpu.isEmpty) {
      gpu = Option(new VirtualRamDevice(ram.owner))
      internalBuffers += ram.owner -> gpu.get
    }
    val preexists: Boolean = gpu.get.addBuffer(ram)
    if (!preexists || ram.dirty) {
      onBufferRamInit(ram)
    }
    preexists
  }

  def removeBuffer(owner: String, id: Int): Boolean = {
    internalBuffers.get(owner) match {
      case Some(gpu: VideoRamDevice) => {
        gpu.getBuffer(id) match {
          case Some(ram: component.GpuTextBuffer) => {
            onBufferRamDestroy(ram)
            gpu.removeBuffers(Array(id)) == 1
          }
          case _ => false
        }
      }
      case _ => false
    }
  }

  def removeAllBuffers(owner: String): Int = {
    var count = 0
    internalBuffers.get(owner) match {
      case Some(gpu: VideoRamDevice) => {
        val ids = gpu.bufferIndexes()
        for (id <- ids) {
          if (removeBuffer(owner, id)) {
            count += 1
          }
        }
      }
      case _ =>
    }
    count
  }

  def removeAllBuffers(): Int = {
    // 任务 F-1：原版显卡 bind 的 reset 分支会对屏调 removeAllBuffers()（GraphicsCard.scala:312）
    // ⇒ 这里也必须过驱动方闸门（否则它会静默清掉我们 GPU 已登记的全部 VRAM 页）。
    // 2026-09-27 P0 修复：闸门不再抛异常 ⇒ 被拒时**直接返回 0**（一个 VRAM 页都不清）。
    if (!com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "removeAllBuffers")) {
      return 0
    }
    var count = 0
    for ((owner: String, _: Any) <- internalBuffers) {
      count += removeAllBuffers(owner)
    }
    count
  }

  def loadBuffer(owner: String, id: Int, holder: DataComponentHolder): Boolean = {
    val src = new UtilTextBuffer(width = 1, height = 1, PackedColor.SingleBitFormat)
    src.loadData(holder)
    addBuffer(component.GpuTextBuffer.wrap(owner, id, src))
  }

  def getBuffer(owner: String, id: Int): Option[component.GpuTextBuffer] = {
    internalBuffers.get(owner) match {
      case Some(gpu: VideoRamDevice) => gpu.getBuffer(id)
      case _ => None
    }
  }
}
