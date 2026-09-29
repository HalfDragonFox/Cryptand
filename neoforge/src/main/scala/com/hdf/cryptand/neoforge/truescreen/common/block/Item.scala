/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/block/Item.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.block

import com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits.Rotatable
import net.minecraft.world.item.{BlockItem, ItemStack}
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * 屏幕的 BlockItem。
 *
 * <h3>为什么必须移植它</h3>
 * `block.Screen` 本身**没有** `getStateForPlacement`（朝向完全由方块状态的 pitch/yaw 属性 +
 * 扳手旋转决定），真正"让屏幕面朝玩家"的逻辑在 OC 的 BlockItem 里：
 * `Item.placeBlock` 放置成功后取方块实体，按玩家视线算出 pitch/yaw 再 `setFromPitchAndYaw`，
 * 最后 `invertRotation()`（屏幕面朝玩家 ⇒ 与"朝外"差 180°）。少了这一步，屏幕会停在默认状态
 * （pitch=DOWN），玩家从任何角度都只能看到屏幕背面。
 *
 * <h3>最小替代：Sable 坐标边界</h3>
 * OC（这个 fork）的版本先过 `SableCompat.localHeading/localPitch`，把世界朝向换算到
 * "Sable 子空间（移动载具/飞艇）内的本地朝向"。本移植**不搬 Sable 集成**（那是"方块实体随载具
 * 移动"的一整套机制，属 OC 的 Create/Aeronautics 集成，不在屏幕闭包内）。因此这里直接按
 * 世界朝向算 heading/pitch —— 公式与 `SableCompat.localHeading` 在"不在子空间里"时的分支**完全一致**
 * （`SableCompanion.getContaining == null` 时 `localDirection` 原样返回向量）。
 * ⇒ 静止世界里的放置朝向与 OC 相同；把屏幕放到移动载具上的场景本轮不支持。
 */
class Item(value: Block, props: Properties) extends BlockItem(value, props) {

  override def placeBlock(ctx: BlockPlaceContext, newState: BlockState): Boolean = {
    if (super.placeBlock(ctx, newState)) {
      // If it's a rotatable block try to make it face the player.
      ctx.getLevel.getBlockEntity(ctx.getClickedPos) match {
        case rotatable: Rotatable =>
          val forward = Vec3.directionFromRotation(ctx.getPlayer.getXRot, ctx.getPlayer.getYRot).reverse()
          val (heading, pitch) = Item.headingAndPitch(forward)
          rotatable.setFromPitchAndYaw(pitch.toFloat, heading.toFloat)
          rotatable.invertRotation()
        case _ => // Ignore.
      }
      true
    }
    else false
  }
}

object Item {
  /**
   * 世界坐标下的 (水平朝向角, 俯仰角)，单位度。
   *
   * 与 `li.cil.oc.util.SableCompat.localHeading/localPitch` 的"非子空间"分支逐式相同：
   * heading = atan2(x, -z) 归一到 [0,360)，pitch = atan2(y, sqrt(x²+z²))。
   */
  def headingAndPitch(forward: Vec3): (Double, Double) = {
    val horizontal = math.sqrt(forward.x * forward.x + forward.z * forward.z)
    val heading =
      if (horizontal < 1.0e-9) 0.0
      else {
        val h = math.toDegrees(math.atan2(forward.x, -forward.z)) % 360.0
        if (h < 0.0) h + 360.0 else h
      }
    val pitch = math.toDegrees(math.atan2(forward.y, horizontal))
    (heading, pitch)
  }
}
