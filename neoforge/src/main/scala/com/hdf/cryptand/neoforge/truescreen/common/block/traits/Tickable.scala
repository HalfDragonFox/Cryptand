/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/block/traits/Tickable.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.block.traits

import com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits.{Tickable => BlockEntityTickable}
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.{BlockEntity, BlockEntityTicker, BlockEntityType}
import net.minecraft.world.level.block.state.BlockState

trait Tickable extends EntityBlock {
  def getBlockEntityType: BlockEntityType[_ <: BlockEntity]

  override def getTicker[T <: BlockEntity](pLevel: Level, pState: BlockState, pBlockEntityType: BlockEntityType[T]): BlockEntityTicker[T] = {
    if (pBlockEntityType == getBlockEntityType) {
      (level: Level, pos: BlockPos, state: BlockState, blockEntity: T) => {
        blockEntity match {
          case tickable: BlockEntityTickable => tickable.tick()
          case _ =>
        }
      }
    } else {
      null
    }
  }
}
