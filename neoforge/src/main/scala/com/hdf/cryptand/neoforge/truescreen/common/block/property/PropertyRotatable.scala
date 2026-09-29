/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/block/property/PropertyRotatable.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.block.property

import net.minecraft.core.Direction
import net.minecraft.world.level.block.state.properties.{BlockStateProperties, DirectionProperty}

object PropertyRotatable {
  final val Facing = BlockStateProperties.HORIZONTAL_FACING
  final val Mount = DirectionProperty.create("mount", (d: Direction) => d == Direction.UP || d == Direction.DOWN)
  final val Pitch = DirectionProperty.create("pitch", (d: Direction) => d.getAxis == Direction.Axis.Y || d == Direction.NORTH)
  final val Yaw = DirectionProperty.create("yaw", Direction.Plane.HORIZONTAL)
}
