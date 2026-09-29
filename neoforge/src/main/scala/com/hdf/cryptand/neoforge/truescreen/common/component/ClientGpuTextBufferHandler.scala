/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/component/GpuTextBuffer.scala  (ClientGpuTextBufferHandler)
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.component

import com.hdf.cryptand.neoforge.truescreen.common.component.traits.VideoRamRasterizer
import li.cil.oc.api
import net.minecraft.core.component.DataComponentHolder

/**
 * 客户端侧"把服务端 GPU 的显存块操作落到本屏"的三个入口（上游 li.cil.oc.common.component.
 * ClientGpuTextBufferHandler 逐字）。
 *
 * <h3>为什么这是**客户端**侧的类却不在 net 包里</h3>
 * 它操作的是 [VideoRamRasterizer] 的显存索引（纯数据），不碰报文也不碰 Minecraft 客户端类；
 * 上游也把它放在 common/component 里。报文处理器（[net.ClientPacketHandler]）只是调用方。
 *
 * <h3>与任务 A 的关系</h3>
 * 任务 A 移植 [GpuTextBuffer] 时把这个 object 整段裁掉了（它是"报文处理器用的入口"）；
 * 现在报文链落地，按上游原样补回 —— 三个方法都是"在 dst 的显存索引里找/放/删一块 ram"，
 * 真正的像素搬运仍是 [GpuTextBuffer.bitblt]。
 */
object ClientGpuTextBufferHandler {

  /** GPU 的 \`bitblt\`：按 owner+id 找到源显存块，再拷进屏幕的字符面。 */
  def bitblt(dst: api.internal.TextBuffer, col: Int, row: Int, w: Int, h: Int, owner: String, srcId: Int, fromCol: Int, fromRow: Int): Unit = {
    dst match {
      case videoDevice: VideoRamRasterizer => videoDevice.getBuffer(owner, srcId) match {
        case Some(buffer: GpuTextBuffer) => GpuTextBuffer.bitblt(dst, col, row, w, h, buffer, fromCol, fromRow)
        case _ => // 收到一块已经不存在的显存的目标 bitblt：忽略（上游同款）。
      }
      case _ => // 不是显存设备（理论上不会走到）：忽略。
    }
  }

  /** GPU \`free\` 后服务端通知客户端丢掉那块显存。 */
  def removeBuffer(buffer: api.internal.TextBuffer, owner: String, id: Int): Boolean = {
    buffer match {
      case screen: VideoRamRasterizer => screen.removeBuffer(owner, id)
      case _ => false
    }
  }

  /** 屏幕注册索引时把已有的显存块灌给客户端（\`RamInit\`）。 */
  def loadBuffer(buffer: api.internal.TextBuffer, owner: String, id: Int, data: DataComponentHolder): Boolean = {
    buffer match {
      case screen: VideoRamRasterizer => screen.loadBuffer(owner, id, data)
      case _ => false
    }
  }
}
