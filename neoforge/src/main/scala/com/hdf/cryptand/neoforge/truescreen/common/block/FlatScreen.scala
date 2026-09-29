/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/block/FlatScreen.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.block

import com.hdf.cryptand.neoforge.truescreen.TrueScreenSettings
import com.hdf.cryptand.neoforge.truescreen.common.block.property.PropertyRotatable
import com.hdf.cryptand.neoforge.truescreen.util.PackedColor
import net.minecraft.core.{BlockPos, Direction}
import net.minecraft.network.chat.{Component => ITextComponent}
import net.minecraft.world.item.{ItemStack, TooltipFlag}
import net.minecraft.world.item.Item.TooltipContext
import net.minecraft.world.item.context.{BlockPlaceContext => BlockItemUseContext}
import net.minecraft.world.level.{BlockGetter => IBlockReader}
import net.minecraft.world.level.block.state.{BlockState, StateDefinition => StateContainer}
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.shapes.{CollisionContext => ISelectionContext, Shapes => VoxelShapes, VoxelShape}

import java.util

/**
 * 平板屏（1/16 厚，贴在方块某一面上）。
 *
 * 与 OC 原文件的差别只有参数来源：`Settings.screenResolutionsByTier/screenDepthsByTier`
 * → `TrueScreenSettings.*`，`Tooltip` → `tooltipResolution`。
 */
class FlatScreen(props: Properties, tier: Int, val isBack: Boolean) extends Screen(props, tier) {
  private val NorthShape = VoxelShapes.box(0, 0, 15.0 / 16.0, 1, 1, 1)
  private val EastShape = VoxelShapes.box(0, 0, 0, 1.0 / 16.0, 1, 1)
  private val SouthShape = VoxelShapes.box(0, 0, 0, 1, 1, 1.0 / 16.0)
  private val WestShape = VoxelShapes.box(15.0 / 16.0, 0, 0, 1, 1, 1)
  private val UpShape = VoxelShapes.box(0, 0, 0, 1, 1.0 / 16.0, 1)
  private val DownShape = VoxelShapes.box(0, 15.0 / 16.0, 0, 1, 1, 1)

  override protected def createBlockStateDefinition(builder: StateContainer.Builder[Block, BlockState]) =
    builder.add(PropertyRotatable.Pitch, PropertyRotatable.Yaw)

  override def getStateForPlacement(ctx: BlockItemUseContext): BlockState = {
    val (pitch, yaw) = ctx.getClickedFace match {
      case side@(Direction.DOWN | Direction.UP) => (side, ctx.getHorizontalDirection)
      case side => (Direction.NORTH, side)
    }
    super.getStateForPlacement(ctx).setValue(PropertyRotatable.Pitch, pitch).setValue(PropertyRotatable.Yaw, yaw)
  }

  override def getShape(state: BlockState, world: IBlockReader, pos: BlockPos, ctx: ISelectionContext): VoxelShape =
    shapeFor(mountFace(state))

  override protected def tooltipBody(stack: ItemStack, context: TooltipContext, tooltip: util.List[ITextComponent], flag: TooltipFlag): Unit = {
    val (w, h) = TrueScreenSettings.screenResolutionsByTier(tier)
    val depth = PackedColor.Depth.bits(TrueScreenSettings.screenDepthsByTier(tier))
    tooltipResolution(tooltip, w, h, depth)
  }

  private def mountFace(state: BlockState): Direction = {
    val facing = state.getValue(PropertyRotatable.Pitch) match {
      case side@(Direction.DOWN | Direction.UP) => side
      case _ => state.getValue(PropertyRotatable.Yaw)
    }
    if (isBack) facing else facing.getOpposite
  }

  private def shapeFor(facing: Direction): VoxelShape = facing match {
    case Direction.NORTH => NorthShape
    case Direction.EAST => EastShape
    case Direction.SOUTH => SouthShape
    case Direction.WEST => WestShape
    case Direction.UP => UpShape
    case Direction.DOWN => DownShape
  }
}
