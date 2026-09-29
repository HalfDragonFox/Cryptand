/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/datacomponents/MaximumVideoMode.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.datacomponents

import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec

case class MaximumVideoMode(width: Int, height: Int, depth: Int)

object MaximumVideoMode {
  val CODEC = RecordCodecBuilder.create[MaximumVideoMode](inst => inst.group(
    ScalaCodec.INT.fieldOf("width").forGetter(_.width),
    ScalaCodec.INT.fieldOf("height").forGetter(_.height),
    ScalaCodec.INT.fieldOf("depth").forGetter(_.depth)
  ).apply(inst, MaximumVideoMode.apply _))

  val STREAM_CODEC: StreamCodec[ByteBuf, MaximumVideoMode] = StreamCodec.composite(
    ScalaStreamCodec.VAR_INT, _.width,
    ScalaStreamCodec.VAR_INT, _.height,
    ScalaStreamCodec.VAR_INT, _.depth,
    MaximumVideoMode.apply _
  )
}
