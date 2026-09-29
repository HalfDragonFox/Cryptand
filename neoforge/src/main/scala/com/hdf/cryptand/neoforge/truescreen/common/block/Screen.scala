/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/block/Screen.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.block

import com.hdf.cryptand.neoforge.truescreen.TrueScreenContent
import com.hdf.cryptand.neoforge.truescreen.TrueScreenSettings
import com.hdf.cryptand.neoforge.truescreen.common.block.property.PropertyRotatable
import com.hdf.cryptand.neoforge.truescreen.common.{blockentity, Tier}
import com.hdf.cryptand.neoforge.truescreen.util.PackedColor
import li.cil.oc.api
import net.minecraft.client.Minecraft
import net.minecraft.core.{BlockPos, Direction}
import net.minecraft.network.chat.{Component => ITextComponent}
import net.minecraft.world.{InteractionHand => Hand}
import net.minecraft.world.entity.{Entity, LivingEntity}
import net.minecraft.world.entity.player.{Player => PlayerEntity}
import net.minecraft.world.entity.projectile.{Arrow => ArrowEntity}
import net.minecraft.world.item.Item.TooltipContext
import net.minecraft.world.item.{ItemStack, TooltipFlag}
import net.minecraft.world.level.{Level => World}
import net.minecraft.world.level.block.state.BlockBehaviour.Properties
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.{BlockEntity, BlockEntityType}
import net.minecraft.world.level.block.state.{BlockState, StateDefinition => StateContainer}
import net.neoforged.api.distmarker.{Dist, OnlyIn}

import java.util

/**
 * 墙面屏幕方块（T1..T4）。
 *
 * <h3>相对 OC 原文件的最小替代（逐条）</h3>
 * <ul>
 *   <li>`Settings.screenResolutionsByTier/screenDepthsByTier` → `TrueScreenSettings.*`（不整搬 Settings）。</li>
 *   <li>`Tooltip.add(...)` → 我方 `tooltipResolution`（语言键 `cryptand`，资源归任务 C）。</li>
 *   <li>`Wrench.holdsApplicableWrench` → `holdsApplicableWrench`：OC 那套是 IMC 反射注册表
 *       （哪个 mod 的什么东西算扳手由 OC 的集成层填）；本仓的既有约定是 **Create 的扳手**
 *       （`AllItems.WRENCH`，见 soc 里 PeripheralTransmissionBlock 等）。所以这里直接认 Create 扳手。</li>
 *   <li>`api.Items.get(Constants.ItemName.Analyzer)` → `api.Items.get("analyzer")`（同一个 OC 物品，
 *       只是不 import OC 内部的 Constants）。</li>
 *   <li>`BlockEntityTypes.SCREEN.get()` → `TrueScreenContent.SCREEN_BE.get()`（我方注册，cryptand 命名空间）。</li>
 *   <li>`client.gui.Screen`（右键窗口）→ **任务 C 已落地**，见 `showGui`（client/gui/TrueScreenWindow）。</li>
 * </ul>
 */
class Screen(props: Properties, val tier: Int,
             /**
              * 该屏使用的 LOD 阶梯（用户 2026-09-29：「oc 部分只需要注册时传入这个参数即可」）。
              *
              * <p>这里只声明"这个屏用哪套阶梯"；LOD 的**效果实现**全在 com.hdf.cryptand.lod
              * （查表 / 档间插值 / 平滑 / 采哪个采样算法），调用方不需要理解那些。</p>
              */
             val lodStairs: com.hdf.cryptand.lod.LodStairs = com.hdf.cryptand.lod.LodStairs.defaults())
    extends RedstoneAware(props) with traits.Tickable {
  protected override def createBlockStateDefinition(builder: StateContainer.Builder[Block, BlockState]) =
    builder.add(PropertyRotatable.Pitch, PropertyRotatable.Yaw)

  // ----------------------------------------------------------------------- //

  override protected def tooltipBody(stack: ItemStack, context: TooltipContext, tooltip: util.List[ITextComponent], flag: TooltipFlag): Unit = {
    val (w, h) = TrueScreenSettings.screenResolutionsByTier(tier)
    val depth = PackedColor.Depth.bits(TrueScreenSettings.screenDepthsByTier(tier))
    tooltipResolution(tooltip, w, h, depth)
  }

  // ----------------------------------------------------------------------- //

  override def newBlockEntity(pos: BlockPos, state: BlockState) =
    new blockentity.Screen(pos, state, tier, lodStairs)

  // ----------------------------------------------------------------------- //

  override def setPlacedBy(world: World, pos: BlockPos, state: BlockState, placer: LivingEntity, stack: ItemStack): Unit = {
    super.setPlacedBy(world, pos, state, placer, stack)
    world.getBlockEntity(pos) match {
      case screen: blockentity.Screen => screen.delayUntilCheckForMultiBlock = 0
      case _ =>
    }
  }

  override def localOnBlockActivated(world: World, pos: BlockPos, player: PlayerEntity, hand: Hand, heldItem: ItemStack, side: Direction, hitX: Float, hitY: Float, hitZ: Float) =
    rightClick(world, pos, player, hand, heldItem, side, hitX, hitY, hitZ, force = false)

  def rightClick(world: World, pos: BlockPos, player: PlayerEntity, hand: Hand, heldItem: ItemStack,
                 side: Direction, hitX: Float, hitY: Float, hitZ: Float, force: Boolean): Boolean = {
    if (holdsApplicableWrench(player, pos) && getValidRotations(world, pos).contains(side) && !force) false
    else if (api.Items.get(heldItem) == api.Items.get("analyzer")) false
    else world.getBlockEntity(pos) match {
      case screen: blockentity.Screen if screen.hasKeyboard && (force || player.isCrouching == screen.origin.invertTouchMode) =>
        // Yep, this GUI is actually purely client side (to trigger it from
        // the server we would have to give screens a "container", which we
        // do not want).
        if (world.isClientSide) showGui(screen)
        true
      case screen: blockentity.Screen if screen.tier > 0 && side == screen.facing =>
        if (world.isClientSide && player == Minecraft.getInstance.player) {
          screen.click(hitX, hitY, hitZ)
        }
        else true
      case _ => false
    }
  }

  /**
   * 扳手判定：OC 走 `Wrench.holdsApplicableWrench`（IMC 反射注册表，哪个 mod 的什么算扳手由
   * OC 的集成层填）。这里用**本仓既有约定**：Create 的扳手（`AllItems.WRENCH`）。
   * 位置参数（`pos`）保留是为了与 OC 的调用点形态一致，当前实现用不到。
   */
  private def holdsApplicableWrench(player: PlayerEntity, pos: BlockPos): Boolean = {
    val stack = player.getItemInHand(Hand.MAIN_HAND)
    !stack.isEmpty && com.simibubi.create.AllItems.WRENCH.isIn(stack)
  }

  /**
   * 右键打开屏幕窗口（上游逐字：\`buffer\` 给 origin 的，因为拼合屏的内容只在 origin 上）。
   *
   * ⚠ 上游用的是已移除的 \`Minecraft.pushGuiLayer\`（1.20 起改 \`setScreen\`）：
   * 语义等价（把当前界面换成屏幕窗口），用现行 API。
   *
   * 窗口本身见 client/gui/TrueScreenWindow：画面走 BufferRenderer（与世界侧同一条 VRAM 内容来源），
   * 键鼠走 \`ClientProxy.*\` → 报文回服务端。
   */
  @OnlyIn(Dist.CLIENT)
  private def showGui(screen: blockentity.Screen): Unit = {
    Minecraft.getInstance.setScreen(new com.hdf.cryptand.neoforge.truescreen.client.gui.TrueScreenWindow(
      screen.origin.buffer, screen.tier > 0, () => screen.origin.hasKeyboard, () => screen.origin.buffer.isRenderingEnabled))
  }

  override def stepOn(world: World, pos: BlockPos, state: BlockState, entity: Entity): Unit =
    if (!world.isClientSide) world.getBlockEntity(pos) match {
      case screen: blockentity.Screen if screen.tier > 0 && screen.facing == Direction.UP => screen.walk(entity)
      case _ => super.stepOn(world, pos, state, entity)
    }

  override def entityInside(state: BlockState, world: World, pos: BlockPos, entity: Entity): Unit =
    if (world.isClientSide) (entity, world.getBlockEntity(pos)) match {
      case (arrow: ArrowEntity, screen: blockentity.Screen) if screen.tier > 0 =>
        val hitX = math.max(0, math.min(1, arrow.getX - pos.getX))
        val hitY = math.max(0, math.min(1, arrow.getY - pos.getY))
        val hitZ = math.max(0, math.min(1, arrow.getZ - pos.getZ))
        val absX = math.abs(hitX - 0.5)
        val absY = math.abs(hitY - 0.5)
        val absZ = math.abs(hitZ - 0.5)
        val side = if (absX > absY && absX > absZ) {
          if (hitX < 0.5) Direction.WEST
          else Direction.EAST
        }
        else if (absY > absZ) {
          if (hitY < 0.5) Direction.DOWN
          else Direction.UP
        }
        else {
          if (hitZ < 0.5) Direction.NORTH
          else Direction.SOUTH
        }
        if (side == screen.facing) {
          screen.shot(arrow)
        }
      case _ =>
    }

  // ----------------------------------------------------------------------- //

  override def getValidRotations(world: World, pos: BlockPos): Array[Direction] =
    world.getBlockEntity(pos) match {
      case screen: blockentity.Screen =>
        if (screen.facing == Direction.UP || screen.facing == Direction.DOWN) Direction.values
        else Direction.values.filter {
          d => d != screen.facing && d != screen.facing.getOpposite
        }
      case _ => super.getValidRotations(world, pos)
    }

  override def getBlockEntityType: BlockEntityType[_ <: BlockEntity] = TrueScreenContent.SCREEN_BE.get()
}
