/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/renderer/block/ScreenModel.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client.renderer.block

import java.util

import com.hdf.cryptand.neoforge.truescreen.client.TrueScreenTextures
import com.hdf.cryptand.neoforge.truescreen.common.Tier
import com.hdf.cryptand.neoforge.truescreen.common.block.Screen
import com.hdf.cryptand.neoforge.truescreen.common.{blockentity => commonBlockEntity}
import com.hdf.cryptand.neoforge.truescreen.util.Color
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.block.model.{BakedQuad, ItemOverrides}
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.core.Direction
import net.minecraft.util.RandomSource
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.neoforge.client.model.data.{ModelData, ModelProperty}

/**
 * 屏幕方块的外观模型（首/中/尾三级 UV 分段 + tier 染色 + 物品模型）。
 *
 * 除包名/类型引用外**逐字照搬**：外观（逐面 1 quad、含屏面）与分段规则（xy2part）都来自上游，
 * 一个像素都没改 —— 任务 B 只改"内容来源"（见 TrueScreenVram），不改外观。
 * 命名空间：Textures.Block.Screen.* → TrueScreenTextures.Block.Screen.*（贴图落在 cryptand:block/screen/ 下）。
 */
object ScreenModel extends SmartBlockModelBase {
  val SCREEN_PROPERTY = new ModelProperty[commonBlockEntity.Screen]()

  override def getOverrides: ItemOverrides = ItemOverride

  override def getQuads(state: BlockState, side: Direction, rand: RandomSource, data: ModelData, renderType: RenderType): util.List[BakedQuad] = {
    val safeSide = if (side != null) side else Direction.SOUTH
    Option(data.get(SCREEN_PROPERTY)) match {
      case Some(screen) =>
        val facing = screen.toLocal(safeSide)

        val (x, y) = screen.localPosition
        var px = xy2part(x, screen.width - 1)
        var py = xy2part(y, screen.height - 1)
        if ((safeSide == Direction.DOWN || screen.facing == Direction.DOWN) && safeSide != screen.facing) {
          px = 2 - px
          py = 2 - py
        }
        val rotation =
          if (safeSide == Direction.UP) screen.yaw.get2DDataValue
          else if (safeSide == Direction.DOWN) -screen.yaw.get2DDataValue
          else 0

        def pitch = if (screen.pitch == Direction.NORTH) 0 else 1
        val texture =
          if (screen.width == 1 && screen.height == 1) {
            if (facing == Direction.SOUTH)
              TrueScreenTextures.Block.Screen.SingleFront(pitch)
            else
              TrueScreenTextures.Block.Screen.Single(safeSide.get3DDataValue)
          }
          else if (screen.width == 1) {
            if (facing == Direction.SOUTH)
              TrueScreenTextures.Block.Screen.VerticalFront(pitch)(py)
            else
              TrueScreenTextures.Block.Screen.Vertical(pitch)(py)(facing.get3DDataValue)
          }
          else if (screen.height == 1) {
            if (facing == Direction.SOUTH)
              TrueScreenTextures.Block.Screen.HorizontalFront(pitch)(px)
            else
              TrueScreenTextures.Block.Screen.Horizontal(pitch)(px)(facing.get3DDataValue)
          }
          else {
            if (facing == Direction.SOUTH)
              TrueScreenTextures.Block.Screen.MultiFront(pitch)(py)(px)
            else
              TrueScreenTextures.Block.Screen.Multi(pitch)(py)(px)(facing.get3DDataValue)
          }

        java.util.List.of(bakeQuad(safeSide, TrueScreenTextures.getSprite(texture), Some(screen.getColor), rotation))
      case _ => super.getQuads(state, safeSide, rand)
    }
  }

  private def xy2part(value: Int, high: Int) = if (value == 0) 2 else if (value == high) 0 else 1

  class ItemModel(val stack: ItemStack) extends SmartBlockModelBase {
    val color: Int = Block.byItem(stack.getItem) match {
      case screen: Screen => Color.byTier(screen.tier)
      case _ => Color.byTier(Tier.One)
    }

    override def getQuads(state: BlockState, side: Direction, rand: RandomSource): util.List[BakedQuad] = {
      val result =
        if (side == Direction.NORTH || side == null)
          TrueScreenTextures.Block.Screen.SingleFront(0)
        else
          TrueScreenTextures.Block.Screen.Single(side.ordinal())
      java.util.List.of(bakeQuad(if (side != null) side else Direction.SOUTH, TrueScreenTextures.getSprite(result), Some(color), 0))
    }
  }

  object ItemOverride extends ItemOverrides {
    override def resolve(originalModel: BakedModel, stack: ItemStack, world: ClientLevel, entity: LivingEntity, seed: Int): BakedModel = new ItemModel(stack)
  }
}
