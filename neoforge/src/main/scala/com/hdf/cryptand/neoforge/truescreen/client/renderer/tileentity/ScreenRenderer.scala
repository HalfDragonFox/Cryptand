/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/renderer/tileentity/ScreenRenderer.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client.renderer.tileentity

import com.hdf.cryptand.neoforge.truescreen.client.renderer.{RenderState, RenderTypes}
import com.hdf.cryptand.neoforge.truescreen.client.TrueScreenTextures
import com.hdf.cryptand.neoforge.truescreen.{TrueScreenLog, TrueScreenSettings}
import com.hdf.cryptand.neoforge.truescreen.common.component.{TextBuffer => ComponentTextBuffer}
import com.hdf.cryptand.neoforge.truescreen.common.blockentity.Screen
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.{PoseStack, VertexConsumer}
import com.mojang.math.Axis
import li.cil.oc.integration.util.Wrench
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.{BlockEntityRenderer => TileEntityRenderer, BlockEntityRendererProvider}
import net.minecraft.core.Direction
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.{AABB, Vec3}
import net.neoforged.api.distmarker.{Dist, OnlyIn}

/**
 * 屏幕的方块实体渲染器（世界侧的**内容入口**）。
 *
 * <h3>外观/拼合/裁剪语义：逐字照搬，一个数都没改</h3>
 * transform()（yaw/pitch 旋转 + mirrorScale(1,-1,1)）、边框内缩（flat 0.5 / 墙屏 2.25）、
 * 宽高比裁剪（scaleX/scaleY 取小者 + 居中平移）、drawOverlay（朝上指示箭头）、
 * shouldRenderOffScreen（拼合屏免裁剪）、渲染距离/淡出阈值、profiler 段名：
 * 全部与上游一致。
 *
 * <h3>★ 内容入口（本任务唯一改动的两处之一）</h3>
 * draw() 末尾那三行（上游 369-372 行）不再把"字符面"交给字形生成器，而是交给
 * component.TextBuffer.renderText —— 它的实现现在读**真彩屏 VRAM**
 * （TrueScreenRenderCache → TrueScreenVram → common 的 TrueColorScreen）。
 * 调用形态（match ComponentTextBuffer / 退化到无 buffer 的 renderText）与上游**完全相同**：
 * 变的只是 renderText 背后的内容来源，不是"谁在哪里怎么调它"。
 *
 * <h3>相对上游的裁剪（逐条，都与屏幕外观无关）</h3>
 * <ul>
 *   <li><b>HoloScreen 整段</b>（queueHolo / onRenderLevelStage / onInteractionKeyMapping /
 *       renderHolo / transformHolo / createHologramLayout / renderProjectionBeam /
 *       renderHologramSurface / renderMonitorContent / projectedHoloHit / resizeSideForHit /
 *       isShiftHeld / HoloHit）：全息屏方块不在本次移植的方块清单里（阶段一清单已注明
 *       "holo 段本轮可裁"）。裁掉它们也顺带避开了 PacketSender（任务 C 的报文领地）。</li>
 *   <li><b>SableCompat</b>：上游用 Sable Companion 把"子层坐标"投影到物理世界。Sable 不存在时
 *       Companion 就是恒等实现，故这里按"无 Sable"的等价写法内联（physicalPosition = 原坐标、
 *       physicalFacing = 原朝向、距离 = 原距离）—— 与上游在无 Sable 环境下的行为一致。
 *       等 Sable 集成进来（另一任务）再换回 Companion 调用，插入点就在这两个私有方法里。</li>
 * </ul>
 *
 * ⚠ 注册（任务 C）：ScreenRenderer 作为 BlockEntityRendererProvider 需要在
 * EntityRenderersEvent.RegisterRenderers 里对 TrueScreenContent.SCREEN_BE 注册一次。
 */
object ScreenRenderer extends BlockEntityRendererProvider[Screen] {
  override def create(ctx: BlockEntityRendererProvider.Context): ScreenRenderer =
    new ScreenRenderer()
}

class ScreenRenderer extends TileEntityRenderer[Screen] {
  private val maxRenderDistanceSq = TrueScreenSettings.maxScreenTextRenderDistance * TrueScreenSettings.maxScreenTextRenderDistance
  private val fadeDistanceSq      = TrueScreenSettings.screenTextFadeStartDistance * TrueScreenSettings.screenTextFadeStartDistance
  private val fadeRatio           = 1.0 / (maxRenderDistanceSq - fadeDistanceSq)

  private var screen: Screen = null
  private var diagBerDraw = 0

  override def getRenderBoundingBox(screen: Screen): AABB = screen.getRenderBoundingBox

  override def render(
                       screen: Screen,
                       dt: Float,
                       stack: PoseStack,
                       buffer: MultiBufferSource,
                       light: Int,
                       overlay: Int
                     ): Unit = {
    RenderState.checkError(getClass.getName + ".render: entering (aka: wasntme)")

    this.screen = screen
    if (!screen.isOrigin) return

    val distance = playerDistanceSq() / math.min(screen.width, screen.height)
    if (distance > maxRenderDistanceSq) return

    if (!isFlatScreen) {
      val eye_pos   = Minecraft.getInstance.player.getEyePosition(dt)
      val screenPosition = Vec3.atCenterOf(screen.getBlockPos)
      val screenFacing = screen.facing.getOpposite
      val x = screenPosition.x - eye_pos.x
      val y = screenPosition.y - eye_pos.y
      val z = screenPosition.z - eye_pos.z
      if (screenFacing.getStepX * x + screenFacing.getStepY * y + screenFacing.getStepZ * z < 0) return
    }

    RenderSystem.setShaderColor(1, 1, 1, 1)

    stack.pushPose()
    stack.translate(0.5, 0.5, 0.5)

    RenderState.checkError(getClass.getName + ".render: setup")

    drawOverlay(stack, buffer.getBuffer(RenderTypes.BLOCK_OVERLAY))

    RenderState.checkError(getClass.getName + ".render: overlay")

    val alpha = if (distance > fadeDistanceSq)
      math.max(0, 1 - ((distance - fadeDistanceSq) * fadeRatio).toFloat)
    else 1f

    RenderState.checkError(getClass.getName + ".render: fade")

    if (screen.buffer.isRenderingEnabled) {
      val profiler = Minecraft.getInstance.getProfiler
      profiler.push("cryptand:truescreen_text")
      draw(stack, alpha, buffer)
      profiler.pop()
    }

    stack.popPose()

    RenderState.checkError(getClass.getName + ".render: leaving")
  }

  private def transform(stack: PoseStack): Unit = {
    screen.yaw match {
      case Direction.WEST  => stack.mulPose(Axis.YP.rotationDegrees(-90))
      case Direction.NORTH => stack.mulPose(Axis.YP.rotationDegrees(180))
      case Direction.EAST  => stack.mulPose(Axis.YP.rotationDegrees(90))
      case _               => // No yaw.
    }
    screen.pitch match {
      case Direction.DOWN => stack.mulPose(Axis.XP.rotationDegrees(90))
      case Direction.UP   => stack.mulPose(Axis.XP.rotationDegrees(-90))
      case _              => // No pitch.
    }

    stack.translate(-0.5f, -0.5f, 0.5f)
    stack.translate(0, screen.height.toFloat, 0)
    RenderState.mirrorScale(stack, 1, -1, 1)
  }

  private def isScreen(stack: ItemStack): Boolean = Block.byItem(stack.getItem) match {
    case _: com.hdf.cryptand.neoforge.truescreen.common.block.Screen => true
    case _                                                            => false
  }

  private def isFlatScreen: Boolean =
    screen.getBlockState.getBlock.isInstanceOf[com.hdf.cryptand.neoforge.truescreen.common.block.FlatScreen]

  private def isBackFlatScreen: Boolean =
    screen.getBlockState.getBlock match {
      case flatScreen: com.hdf.cryptand.neoforge.truescreen.common.block.FlatScreen => flatScreen.isBack
      case _ => false
    }

  // 1.18.2: IVertexBuilder → VertexConsumer
  private def drawOverlay(matrix: PoseStack, r: VertexConsumer): Unit =
    if (screen.facing == Direction.UP || screen.facing == Direction.DOWN) {
      // 1.18.2: Hand.MAIN_HAND → InteractionHand.MAIN_HAND
      val stack = Minecraft.getInstance.player.getItemInHand(InteractionHand.MAIN_HAND)
      if (!stack.isEmpty) {
        if (Wrench.holdsApplicableWrench(Minecraft.getInstance.player, screen.getBlockPos) || isScreen(stack)) {
          matrix.pushPose()
          transform(matrix)
          matrix.translate(screen.width / 2f - 0.5f, screen.height / 2f - 0.5f, if (isBackFlatScreen) -0.935f else 0.05f)

          val icon = TrueScreenTextures.getSprite(TrueScreenTextures.Block.ScreenUpIndicator)
          r.addVertex(matrix.last.pose, 0, 1, 0).setUv(icon.getU0, icon.getV1)
          r.addVertex(matrix.last.pose, 1, 1, 0).setUv(icon.getU1, icon.getV1)
          r.addVertex(matrix.last.pose, 1, 0, 0).setUv(icon.getU1, icon.getV0)
          r.addVertex(matrix.last.pose, 0, 0, 0).setUv(icon.getU0, icon.getV0)

          matrix.popPose()
        }
      }
    }

  private def draw(stack: PoseStack, alpha: Float, buffer: MultiBufferSource): Unit = {
    RenderState.checkError(getClass.getName + ".draw: entering (aka: wasntme)")

    val sx = screen.width
    val sy = screen.height
    val tw = sx * 16f
    val th = sy * 16f

    transform(stack)

    val border = if (isFlatScreen) 0.5f else 2.25f
    stack.translate(sx * border / tw, sy * border / th, 0)

    val isx = sx - (border / 8)
    val isy = sy - (border / 8)

    // ★ 内容像素尺寸 = 真彩屏 VRAM 帧的尺寸（TEXT = 视口字符数 x 8；GRAPHICS = 设备分辨率）
    val sizeX  = screen.buffer.renderWidth
    val sizeY  = screen.buffer.renderHeight
    val scaleX = isx / sizeX
    val scaleY = isy / sizeY

    if (true) {
      if (scaleX > scaleY) {
        stack.translate(sizeX * 0.5f * (scaleX - scaleY), 0, 0)
        stack.scale(scaleY, scaleY, 1)
      } else {
        stack.translate(0, sizeY * 0.5f * (scaleY - scaleX), 0)
        stack.scale(scaleX, scaleX, 1)
      }
    } else {
      stack.scale(scaleX, scaleY, 1)
    }

    stack.translate(0, 0, (if (isBackFlatScreen) -0.94f else 0) + 0.01f)

    RenderState.checkError(getClass.getName + ".draw: setup")

    // ★★ 内容入口：世界侧与 GUI 窗口（BufferRenderer.drawText）走的是**同一条**内容来源
    //    —— component.TextBuffer.renderText → ClientProxy → TrueScreenRenderCache/TrueScreenVram
    //    → common 的 TrueColorScreen VRAM 帧。
    if (diagBerDraw < 8) {
      TrueScreenLog.log.info("[TrueScreen/DIAG] BER draw origin={} enabled={} buf={} size={}x{}",
        java.lang.Boolean.valueOf(screen.isOrigin), java.lang.Boolean.valueOf(screen.buffer.isRenderingEnabled),
        screen.buffer.getClass.getSimpleName, Integer.valueOf(sizeX), Integer.valueOf(sizeY))
      diagBerDraw += 1
    }
    screen.buffer match {
      case textBuffer: ComponentTextBuffer => textBuffer.renderText(stack, buffer)
      case _ => screen.buffer.renderText(stack)
    }

    RenderState.checkError(getClass.getName + ".draw: text")
  }

  @OnlyIn(Dist.CLIENT)
  override def shouldRenderOffScreen(screen: Screen): Boolean = screen.isOrigin && (screen.width > 1 || screen.height > 1)

  /**
   * 屏幕包围盒到玩家的**最近距离平方**（上游的 Sable 投影版本见裁剪说明）。
   * 数学与上游逐字相同：把玩家点夹到扩大的 AABB 外，再按三段求和。
   */
  private def playerDistanceSq(): Double = {
    val player = Minecraft.getInstance.player
    val bounds = getRenderBoundingBox(screen)

    val physicalBounds = (bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ)

    val px = player.getX
    val py = player.getY
    val pz = player.getZ

    val ex = physicalBounds._4 - physicalBounds._1
    val ey = physicalBounds._5 - physicalBounds._2
    val ez = physicalBounds._6 - physicalBounds._3
    val cx = physicalBounds._1 + ex * 0.5
    val cy = physicalBounds._2 + ey * 0.5
    val cz = physicalBounds._3 + ez * 0.5
    val dx = px - cx
    val dy = py - cy
    val dz = pz - cz

    (if (dx < -ex) { val d = dx + ex; d * d }
    else if (dx > ex) { val d = dx - ex; d * d }
    else 0.0) +
      (if (dy < -ey) { val d = dy + ey; d * d }
      else if (dy > ey) { val d = dy - ey; d * d }
      else 0.0) +
      (if (dz < -ez) { val d = dz + ez; d * d }
      else if (dz > ez) { val d = dz - ez; d * d }
      else 0.0)
  }
}
