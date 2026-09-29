/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/block/SimpleBlock.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.block

import com.hdf.cryptand.neoforge.truescreen.common.block.property.PropertyRotatable
import com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits.Rotatable
import net.minecraft.core.{BlockPos, Direction}
import net.minecraft.network.chat.Component
import net.minecraft.world.{InteractionHand, InteractionResult, ItemInteractionResult}
import net.minecraft.world.entity.player.{Player => PlayerEntity}
import net.minecraft.world.item.{ItemStack, TooltipFlag}
import net.minecraft.world.item.Item.TooltipContext
import net.minecraft.world.item.context.{BlockPlaceContext => BlockItemUseContext}
import net.minecraft.world.level.{BlockGetter, Level => World}
import net.minecraft.world.level.block.{BaseEntityBlock => ContainerBlock, Mirror, RenderShape => BlockRenderType, Rotation}
import net.minecraft.world.level.block.entity.{BlockEntity => TileEntity}
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

import java.util

/**
 * 屏幕方块的基类（OC `common/block/SimpleBlock.scala` 的**最小替代**）。
 *
 * <h3>相对 OC 原文件砍掉了什么，以及为什么</h3>
 * OC 的 `SimpleBlock` 是**它全部方块**的基类，因此拖着与屏幕无关的一整套东西：
 * <ul>
 *   <li>`Inventory` trait + `LootFunctions.DYN_VOLATILE_CONTENTS` + `getDrops`/`playerWillDestroy`：
 *       那是"方块里带物品栏"（机箱/机架/打印机）用的；屏幕没有物品栏。</li>
 *   <li>染料交互（`Color.isDye` → `applyColor`）：屏幕可以被染料改外壳色是 OC 的行为，
 *       本移植**不接染料**（`Color` 里对应的染料表也已删除）。 ⇒ 记为已知行为差异，
 *       若要补请由任务 C 在"交互 + 资源"阶段一起做。</li>
 *   <li>`setUnlocalizedName`/`getDescriptionId`：OC 用 `blockentity.oc.<id>` 作为语言键前缀；
 *       我们的语言键走 `cryptand`（资源是任务 C），所以这里不引入第二套键。
 *   <li>机器人/扳手集成用的 `getFacing`/`setFacing`/`setRotationFromEntityPitchAndYaw`/`toLocal`：
 *       那些是 OC 的 `DriverBlock`/`Converter`/`Wrench` 集成入口，本移植没有对应集成。</li>
 * </ul>
 * 保留的是**屏幕真正走到**的路径：放置（`newBlockEntity`）、右键（`useItemOn`/`useWithoutItem`
 * → `localOnBlockActivated`）、工具提示、朝向属性旋转（`rotate`/`mirror`）、合法旋转面
 * （`getValidRotations`，供 `blockentity.traits.Rotatable.rotate` 使用）。
 */
abstract class SimpleBlock(props: Properties) extends ContainerBlock(props) {
  override protected def codec(): com.mojang.serialization.MapCodec[_ <: SimpleBlock] =
    com.mojang.serialization.MapCodec.unit(this)

  protected val validRotations_ : Array[Direction] = Array(Direction.UP, Direction.DOWN)

  override def newBlockEntity(pos: BlockPos, state: BlockState): TileEntity = null

  override def getRenderShape(state: BlockState): BlockRenderType = BlockRenderType.MODEL

  // ----------------------------------------------------------------------- //
  // BlockItem 工具提示
  // ----------------------------------------------------------------------- //

  override def appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: util.List[Component], flag: TooltipFlag): Unit = {
    tooltipHead(stack, context, tooltip, flag)
    tooltipBody(stack, context, tooltip, flag)
    tooltipTail(stack, context, tooltip, flag)
  }

  protected def tooltipHead(stack: ItemStack, context: TooltipContext, tooltip: util.List[Component], flag: TooltipFlag): Unit = {
  }

  protected def tooltipBody(stack: ItemStack, context: TooltipContext, tooltip: util.List[Component], flag: TooltipFlag): Unit = {
    // OC 这里走 Tooltip.add(...)，按 `oc:tooltip.<name>` 查语言表并决定是否展开。
    // 我们的语言键由任务 C 提供，先不放任何提示（宁缺勿造第二套键）。
  }

  protected def tooltipTail(stack: ItemStack, context: TooltipContext, tooltip: util.List[Component], flag: TooltipFlag): Unit = {
  }

  /** 屏幕分辨率/色深提示。语言键归任务 C（资源）；键缺失时 MC 会显示键名本身，不影响功能。 */
  protected def tooltipResolution(tooltip: util.List[Component], width: Int, height: Int, depth: Int): Unit = {
    tooltip.add(Component.translatable("block.cryptand.truescreen.resolution",
      Int.box(width), Int.box(height), Int.box(depth)))
  }

  // ----------------------------------------------------------------------- //
  // Block
  // ----------------------------------------------------------------------- //

  override def canBeReplaced(state: BlockState, ctx: BlockItemUseContext): Boolean = false

  /**
   * Vanilla 直接变换方块状态（结构生成/push 等）。与"扳手转动方块实体"是两条独立路径。
   */
  @SuppressWarnings(Array("deprecation"))
  override protected def rotate(state: BlockState, rotation: Rotation): BlockState = {
    var result = state
    if (state.hasProperty(PropertyRotatable.Facing)) {
      result = result.setValue(PropertyRotatable.Facing, rotation.rotate(state.getValue(PropertyRotatable.Facing)))
    }
    if (state.hasProperty(PropertyRotatable.Yaw)) {
      result = result.setValue(PropertyRotatable.Yaw, rotation.rotate(state.getValue(PropertyRotatable.Yaw)))
    }
    result
  }

  @SuppressWarnings(Array("deprecation"))
  override protected def mirror(state: BlockState, mirror: Mirror): BlockState = {
    var result = state
    if (state.hasProperty(PropertyRotatable.Facing)) {
      result = result.setValue(PropertyRotatable.Facing, mirror.mirror(state.getValue(PropertyRotatable.Facing)))
    }
    if (state.hasProperty(PropertyRotatable.Yaw)) {
      result = result.setValue(PropertyRotatable.Yaw, mirror.mirror(state.getValue(PropertyRotatable.Yaw)))
    }
    result
  }

  def getValidRotations(world: World, pos: BlockPos): Array[Direction] = validRotations_

  /**
   * 传统（OC 内建扳手 / IMC）旋转入口。任务 A 保留此方法以便：
   * ① 移植过来的 `blockentity.traits.Rotatable.rotate` 有调用方；
   * ② 任务 C 接我方扳手（Create `AllItems.WRENCH`）时直接复用，不用重写朝向算法。
   *
   * ⚠ 本轮**不**把方块做成 Create 的 `IWrenchable`：那是"交互集成"的决定，属任务 C 领地。
   */
  @Deprecated
  def rotateBlock(world: World, pos: BlockPos, axis: Direction): Boolean =
    world.getBlockEntity(pos) match {
      case rotatable: Rotatable if rotatable.rotate(axis) =>
        world.sendBlockUpdated(pos, world.getBlockState(pos), world.getBlockState(pos), 3)
        true
      case _ => false
    }

  // ----------------------------------------------------------------------- //
  // 交互
  // ----------------------------------------------------------------------- //

  override def useItemOn(stack: ItemStack, state: BlockState, level: World, pos: BlockPos, player: PlayerEntity,
                         hand: InteractionHand, hitResult: BlockHitResult): ItemInteractionResult = {
    val result = localOnBlockActivated(level, pos, player, hand, stack, hitResult.getDirection,
      (hitResult.getLocation.x - pos.getX).toFloat,
      (hitResult.getLocation.y - pos.getY).toFloat,
      (hitResult.getLocation.z - pos.getZ).toFloat)
    if (result) ItemInteractionResult.sidedSuccess(level.isClientSide) else ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
  }

  override def useWithoutItem(state: BlockState, world: World, pos: BlockPos, player: PlayerEntity,
                              hitResult: BlockHitResult): InteractionResult = {
    val heldItem = player.getItemInHand(InteractionHand.MAIN_HAND)
    val loc = hitResult.getLocation
    val bPos = hitResult.getBlockPos
    val x = loc.x.toFloat - bPos.getX
    val y = loc.y.toFloat - bPos.getY
    val z = loc.z.toFloat - bPos.getZ
    if (localOnBlockActivated(world, bPos, player, InteractionHand.MAIN_HAND, heldItem, hitResult.getDirection, x, y, z))
      InteractionResult.sidedSuccess(world.isClientSide) else InteractionResult.PASS
  }

  def localOnBlockActivated(world: World, pos: BlockPos, player: PlayerEntity, hand: InteractionHand,
                            heldItem: ItemStack, side: Direction, hitX: Float, hitY: Float, hitZ: Float): Boolean = false
}
