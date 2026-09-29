/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/blockentity/traits/Colored.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits

import com.hdf.cryptand.neoforge.truescreen.common.datacomponents.TrueScreenComponents
import com.hdf.cryptand.neoforge.truescreen.util.ExtendedDataComponentHolder._
import li.cil.oc.api.internal
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.util.ColorRGBA
import net.neoforged.neoforge.common.MutableDataComponentHolder

/**
 * 方块实体的"外壳染色"（屏幕按档位给色，拼合时同色才合并）。
 *
 * 改动：`onColorChanged` 里的 `PacketSender.sendColorChange(this)` 去掉 ——
 * 那是 OC 把颜色变化单独推给客户端的报文（任务 C 的领地）。颜色本身写在组件里、
 * 随 BE 更新包同步，所以功能上不缺。
 */
trait Colored extends BaseBlockEntity with internal.Colored {
  private var _color = 0

  def consumesDye = false

  override def getColor: Int = _color

  override def setColor(value: Int) = if (value != _color) {
    _color = value
    onColorChanged()
  }

  override def controlsConnectivity = false

  protected def onColorChanged(): Unit = {
    // 服务端：立刻把外壳颜色推给附近客户端（客户端要靠它决定拼合/染色，不能等 BE 更新包）。
    if (getLevel != null && isServer) {
      com.hdf.cryptand.neoforge.truescreen.network.ServerPacketSender.sendColorChange(this)
    }
  }

  // ----------------------------------------------------------------------- //

  override def loadComponentsCommon(holder: DataComponentHolder): Unit = {
    super.loadComponentsCommon(holder)
    for (color <- holder.getComponent(TrueScreenComponents.RENDER_COLOR))
      _color = color.rgba()
  }

  override def saveComponentsCommon(holder: MutableDataComponentHolder): Unit = {
    super.saveComponentsCommon(holder)
    holder.setComponent(TrueScreenComponents.RENDER_COLOR, new ColorRGBA(_color))
  }
}
