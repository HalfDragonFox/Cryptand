/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/block/RedstoneAware.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.block

import com.hdf.cryptand.neoforge.truescreen.common.blockentity
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.Direction
import net.minecraft.core.BlockPos
import net.minecraft.world.level.{BlockGetter => IBlockReader}
import net.minecraft.world.level.{Level => World}

abstract class RedstoneAware(props: Properties) extends SimpleBlock(props) {
  override def isSignalSource(state: BlockState): Boolean = true

  override def canConnectRedstone(state: BlockState, world: IBlockReader, pos: BlockPos, side: Direction): Boolean =
    world.getBlockEntity(pos) match {
      case redstone: blockentity.traits.RedstoneAware => redstone.isOutputEnabled
      case _ => false
    }

  override def getDirectSignal(state: BlockState, world: IBlockReader, pos: BlockPos, side: Direction): Int =
    getSignal(state, world, pos, side)

  @Deprecated
  override def getSignal(state: BlockState, world: IBlockReader, pos: BlockPos, side: Direction): Int =
    world.getBlockEntity(pos) match {
      case redstone: blockentity.traits.RedstoneAware if side != null => redstone.getOutput(side.getOpposite) max 0
      case _ => super.getSignal(state, world, pos, side)
    }

  // ----------------------------------------------------------------------- //

  @Deprecated
  override def neighborChanged(state: BlockState, world: World, pos: BlockPos, block: Block, fromPos: BlockPos, b: Boolean): Unit = {
    world.getBlockEntity(pos) match {
      case redstone: blockentity.traits.RedstoneAware => redstone.checkRedstoneInputChanged()
      case _ => // Ignore.
    }
  }
}
