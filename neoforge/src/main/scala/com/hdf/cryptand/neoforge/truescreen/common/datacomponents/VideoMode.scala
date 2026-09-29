/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/datacomponents/VideoMode.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.datacomponents

import com.mojang.serialization.codecs.RecordCodecBuilder
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.StreamCodec

case class VideoMode(width: Int, height: Int)

object VideoMode {
  val CODEC = RecordCodecBuilder.create[VideoMode](inst => inst.group(
    ScalaCodec.INT.fieldOf("width").forGetter(_.width),
    ScalaCodec.INT.fieldOf("height").forGetter(_.height)
  ).apply(inst, VideoMode.apply _))

  val STREAM_CODEC: StreamCodec[ByteBuf, VideoMode] = StreamCodec.composite(
    ScalaStreamCodec.VAR_INT, _.width,
    ScalaStreamCodec.VAR_INT, _.height,
    VideoMode.apply _
  )
}
