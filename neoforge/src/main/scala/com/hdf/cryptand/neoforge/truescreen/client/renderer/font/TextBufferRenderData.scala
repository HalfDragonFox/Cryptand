/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/renderer/font/TextBufferRenderData.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client.renderer.font

import com.hdf.cryptand.neoforge.truescreen.util.TextBuffer

trait TextBufferRenderData {
  def dirty: Boolean

  def dirty_=(value: Boolean): Unit

  def data: TextBuffer

  def viewport: (Int, Int)
}
