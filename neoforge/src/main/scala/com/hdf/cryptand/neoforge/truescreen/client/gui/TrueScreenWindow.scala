/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/gui/Screen.scala
 *             (+ li.cil.oc.client.gui.traits.InputBuffer 的键鼠部分)
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client.gui

import com.hdf.cryptand.neoforge.truescreen.TrueScreenLog
import com.hdf.cryptand.neoforge.truescreen.client.renderer.gui.BufferRenderer
import com.mojang.blaze3d.vertex.PoseStack
import li.cil.oc.api
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.neoforged.api.distmarker.{Dist, OnlyIn}
import org.lwjgl.glfw.GLFW

import scala.collection.mutable

/**
 * 右键屏幕打开的窗口（上游 li.cil.oc.client.gui.Screen 的**屏幕子集**）。
 *
 * <h3>画面部分：逐字（上游 Screen.scala + BufferRenderer）</h3>
 * 缩放（scaleX/scaleY 取小者、上限 1）、居中、边框九宫格与边距（margin 7 + innerMargin 1）、
 * \`hasPower\` 为假时不画内容只画边框 —— 全部与上游一致。内容入口是
 * [BufferRenderer.drawText]，它调 \`buffer.renderText\`：与世界侧 ScreenRenderer 走**同一条**
 * VRAM 内容来源（任务 B 的落点）。
 *
 * <h3>相对上游裁掉的部分（不搬的理由）</h3>
 * 上游的窗口继承 \`gui.traits.InputBuffer\`（360 行），而那一层依赖四个**未移植的子系统**：
 * \`GLFWTranslator\`（GLFW↔LWJGL 键码全表）、\`KeyBindings\`、\`Textures.GUISprites\`、
 * \`ItemSearch\`。因此这里的键鼠接线按"最小替代"直连：
 * <ul>
 *   <li>\`keyDown/keyUp\` 用 **GLFW 键码**当 code、字符位传 0（上游会先用 GLFWTranslator
 *       换算成 LWJGL 键码，并把按键对应的字符一起发过去）；</li>
 *   <li>可打印字符一律走 \`textInput(codePoint)\`（\`charTyped\` 回调），代理对（emoji 等）
 *       在这里合并成一个码点；</li>
 *   <li>Ctrl+V 直接用原版剪贴板（上游走 KeyBindings.clipboardPaste）。</li>
 * </ul>
 * ⚠ 结论性影响：**文本输入（textInput）完全正常**（那是固件/OS 真正消费的通道）；
 * 依赖"keyDown 的字符位"的 guest 端用法会拿到 0（退化成只用 code 判断按键）。
 * 要完全等价就需要先移植 GLFWTranslator —— 记在任务 C 的遗留项里。
 */
@OnlyIn(Dist.CLIENT)
class TrueScreenWindow(val buffer: api.internal.TextBuffer, val hasMouse: Boolean,
                       val hasKeyboardCallback: () => Boolean, val hasPower: () => Boolean)
  extends Screen(Component.empty()) {

  /** 边框 + 内边距（与 BufferRenderer 的常量同源）。 */
  private val bufferMargin = BufferRenderer.margin + BufferRenderer.innerMargin

  private var didClick = false

  private var x, y = 0

  private var innerWidth, innerHeight = 0

  private var scale = 1.0

  private var mx, my = -1

  /** 按下未抬起的键（窗口关闭时要补一条 keyUp，否则 guest 会以为键一直按着）。 */
  private val pressedKeys = mutable.Map.empty[Int, Char]

  private var highSurrogate: Char = 0

  override def isPauseScreen: Boolean = false

  override protected def init(): Unit = {
    super.init()
    // 打开窗口要"抓住"鼠标（否则鼠标会转视角），并松开所有原版按键。
    minecraft.mouseHandler.releaseMouse()
    KeyMapping.releaseAll()
    layout()
  }

  /** 按当前窗口尺寸算缩放与居中位置（上游 changeSize 的屏幕子集）。 */
  private def layout(): Unit = {
    val bw = math.max(1, buffer.renderWidth)
    val bh = math.max(1, buffer.renderHeight)
    val scaleX = math.min(width.toDouble / (bw + bufferMargin * 2.0), 1.0)
    val scaleY = math.min(height.toDouble / (bh + bufferMargin * 2.0), 1.0)
    scale = math.min(scaleX, scaleY)
    innerWidth = (bw * scale).toInt
    innerHeight = (bh * scale).toInt
    x = (width - (innerWidth + bufferMargin * 2)) / 2
    y = (height - (innerHeight + bufferMargin * 2)) / 2
    // 开窗几何一行留档：窗口比例完全由 内容像素(bw x bh) 决定，是"太宽/太扁"类反馈的唯一硬证据。
    // 内容像素 = 视口(列 x 行) x 字符格(CELL_W x CELL_H = 8x16)，所以 比例 = 列 : 行*2。
    TrueScreenLog.log.info(
      "[TrueScreen/CLIENT] 窗口布局 内容像素={}x{} 视口={}x{} 字符格={}x{} 缩放={} 绘制={}x{} 窗口={}x{}",
      Int.box(bw), Int.box(bh),
      Int.box(buffer.getViewportWidth), Int.box(buffer.getViewportHeight),
      Int.box(com.hdf.cryptand.soc.board.TrueColorScreen.CELL_W), Int.box(com.hdf.cryptand.soc.board.TrueColorScreen.CELL_H),
      Double.box(scale), Int.box(innerWidth), Int.box(innerHeight), Int.box(width), Int.box(height))
  }

  override def render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float): Unit = {
    super.render(graphics, mouseX, mouseY, partialTick)
    val stack: PoseStack = graphics.pose()
    stack.pushPose()
    stack.translate(x.toFloat, y.toFloat, 0f)
    BufferRenderer.drawBackground(stack, innerWidth, innerHeight)
    if (hasPower()) {
      stack.translate(bufferMargin.toFloat, bufferMargin.toFloat, 0f)
      stack.scale(scale.toFloat, scale.toFloat, 1f)
      BufferRenderer.drawText(stack, buffer)
    }
    stack.popPose()
  }

  // ==================== 鼠标 ====================

  private def hasKeyboard: Boolean = hasKeyboardCallback()

  /**
   * 窗口坐标 → **字符坐标**（上游同款公式）。
   *
   * ⚠ 上游用 TextBufferRenderCache.renderer.charRenderWidth（一个字形格的像素宽）；
   * 我方渲染链没有那个渲染器了（内容直接来自 VRAM 帧）⇒ 这里用
   * \`renderWidth / 视口列数\` **反算**格宽：两者含义相同（帧像素宽 / 列数），
   * 且不引入任何硬编码常量（CELL=8 只在 common 的 TrueColorScreen 里那一处）。
   */
  private def toBufferCoordinates(mouseX: Double, mouseY: Double): Option[(Double, Double)] = {
    val bw = buffer.getViewportWidth
    val bh = buffer.getViewportHeight
    if (bw <= 0 || bh <= 0) return None
    val cellW = buffer.renderWidth.toDouble / bw
    val cellH = buffer.renderHeight.toDouble / bh
    if (cellW <= 0 || cellH <= 0) return None
    val bx = (mouseX - x - bufferMargin) / scale / cellW
    val by = (mouseY - y - bufferMargin) / scale / cellH
    if (bx >= 0 && by >= 0 && bx < bw && by < bh) Some((bx, by))
    else None
  }

  override def mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean = {
    if (hasMouse) {
      toBufferCoordinates(mouseX, mouseY) match {
        case Some((bx, by)) =>
          buffer.mouseScroll(bx, by, math.signum(scrollY.toInt), null)
          return true
        case _ => // 出界：交给原版处理。
      }
    }
    super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
  }

  override def mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean = {
    if (hasMouse && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
      clickOrDrag(mouseX, mouseY, button)
      return true
    }
    super.mouseClicked(mouseX, mouseY, button)
  }

  override def mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean = {
    if (hasMouse && (button == GLFW.GLFW_MOUSE_BUTTON_LEFT || button == GLFW.GLFW_MOUSE_BUTTON_RIGHT)) {
      clickOrDrag(mouseX, mouseY, button)
      return true
    }
    super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
  }

  override def mouseReleased(mouseX: Double, mouseY: Double, button: Int): Boolean = {
    if (hasMouse) {
      if (didClick) {
        toBufferCoordinates(mouseX, mouseY) match {
          case Some((bx, by)) => buffer.mouseUp(bx, by, button, null)
          case _ => buffer.mouseUp(-1.0, -1.0, button, null)
        }
      }
      val hasClicked = didClick
      didClick = false
      mx = -1
      my = -1
      if (hasClicked) return true
    }
    super.mouseReleased(mouseX, mouseY, button)
  }

  /** 同一格内的拖动不重复发（上游用 (bx, by*2) 做去重键，这里逐字保留）。 */
  private def clickOrDrag(mouseX: Double, mouseY: Double, button: Int): Unit = {
    toBufferCoordinates(mouseX, mouseY) match {
      case Some((bx, by)) if bx.toInt != mx || (by * 2).toInt != my =>
        if (mx >= 0 && my >= 0) buffer.mouseDrag(bx, by, button, null)
        else buffer.mouseDown(bx, by, button, null)
        didClick = true
        mx = bx.toInt
        my = (by * 2).toInt
      case _ =>
    }
  }

  // ==================== 键盘 ====================

  override def keyPressed(keyCode: Int, scanCode: Int, mods: Int): Boolean = {
    if (keyCode == GLFW.GLFW_KEY_ESCAPE && shouldCloseOnEsc) {
      onClose()
      return true
    }
    if (hasKeyboard) {
      // Ctrl+V：把系统剪贴板塞给屏幕（上游走 KeyBindings.clipboardPaste）。
      if (keyCode == GLFW.GLFW_KEY_V && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
        buffer.clipboard(Minecraft.getInstance.keyboardHandler.getClipboard, null)
        return true
      }
      if (!pressedKeys.contains(keyCode)) {
        pressedKeys(keyCode) = 0.toChar
        buffer.keyDown(0.toChar, keyCode, null)
      }
      return true
    }
    super.keyPressed(keyCode, scanCode, mods)
  }

  override def keyReleased(keyCode: Int, scanCode: Int, mods: Int): Boolean = {
    if (hasKeyboard) {
      pressedKeys.remove(keyCode)
      buffer.keyUp(0.toChar, keyCode, null)
      return true
    }
    super.keyReleased(keyCode, scanCode, mods)
  }

  /** 可打印字符 → \`textInput(codePoint)\`（代理对在这里合并成一个码点）。 */
  override def charTyped(codePoint: Char, mods: Int): Boolean = {
    if (hasKeyboard) {
      if (Character.isHighSurrogate(codePoint)) {
        highSurrogate = codePoint
      } else if (Character.isLowSurrogate(codePoint) && highSurrogate != 0) {
        buffer.textInput(Character.toCodePoint(highSurrogate, codePoint), null)
        highSurrogate = 0
      } else {
        highSurrogate = 0
        buffer.textInput(codePoint.toInt, null)
      }
      return true
    }
    super.charTyped(codePoint, mods)
  }

  override def removed(): Unit = {
    super.removed()
    // 关窗兜底：把还按着的键全部补一条 keyUp，否则 guest 会一直以为键按着。
    for ((keyCode, _) <- pressedKeys) {
      buffer.keyUp(0.toChar, keyCode, null)
    }
    pressedKeys.clear()
    highSurrogate = 0
  }
}
