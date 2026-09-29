/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/renderer/font/TextureFontRenderer.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client.renderer.font

import com.hdf.cryptand.neoforge.truescreen.client.renderer.RenderTypes
import com.hdf.cryptand.neoforge.truescreen.util.{PackedColor, TextBuffer}
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.{ByteBufferBuilder, PoseStack, VertexConsumer}
import net.minecraft.client.renderer.{MultiBufferSource, RenderType}
import org.joml.Matrix4f

/**
 * 字符面 → quad 的基类（字形位来自子类的字体图集）。
 *
 * ⚠ 任务 B 的定位：本类**不是**屏幕内容的字形来源。屏幕内容一律来自 VRAM
 * （TEXT 模式 = common 的 ScreenTextLayer + TextFont8x8，GRAPHICS 模式 = TrueColorScreen 的
 * rgbAt/stride/palette）。本类是渲染层 L5 的**忠实移植**（上游 client/renderer/font 下的那几个文件），
 * 保留它的度量、过滤、缩放语义；屏幕路径不调用它（见 TrueScreenRenderCache 的说明）。
 *
 * 两处最小替代：OC 的 ExtendedUnicodeHelper.length(s) 只是一行 codePointCount，而
 * 我方 util/ 不在任务 B 领地内 ⇒ 就地内联（见 stringLength）。
 */
abstract class TextureFontRenderer {
  protected final val basicChars = """☺☻♥♦♣♠•◘○◙♂♀♪♫☼►◄↕‼¶§▬↨↑↓→←∟↔▲▼ !"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\]^_`abcdefghijklmnopqrstuvwxyz{|}~⌂ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■"""

  def charRenderWidth = charWidth / 2
  def charRenderHeight = charHeight / 2

  def generateChars(chars: Array[Char]): Unit = {
    for (char <- chars) generateChar(char.toInt)
  }

  def generateChars(chars: Array[Int]): Unit = {
    for (char <- chars) generateChar(char)
  }

  def drawBuffer(stack: PoseStack, renderBuff: MultiBufferSource, buffer: TextBuffer, viewportWidth: Int, viewportHeight: Int): Unit = {
    val format = buffer.format
    stack.pushPose()
    stack.scale(0.5f, 0.5f, 1)

    var quadBuilder: VertexConsumer = null
    for (y <- 0 until (viewportHeight min buffer.height)) {
      val color = buffer.color(y)
      var cbg = 0x000000
      var x = 0
      var width = 0
      for (col <- color.map(PackedColor.unpackBackground(_, format)) if x + width < viewportWidth) {
        if (col != cbg) {
          if (cbg != 0 && width > 0) {
            if (quadBuilder == null) quadBuilder = renderBuff.getBuffer(RenderTypes.FONT_QUAD)
            drawQuad(quadBuilder, stack.last.pose(), cbg, x, y, width)
          }
          cbg = col
          x += width
          width = 0
        }
        width += 1
      }
      if (cbg != 0 && width > 0) {
        if (quadBuilder == null) quadBuilder = renderBuff.getBuffer(RenderTypes.FONT_QUAD)
        drawQuad(quadBuilder, stack.last.pose(), cbg, x, y, width)
      }
    }

    for (i <- 0 until textureCount) {
      var fontBuilder: VertexConsumer = null
      for (y <- 0 until (viewportHeight min buffer.height)) {
        val line = buffer.buffer(y)
        val color = buffer.color(y)
        val ty = y * charHeight
        var tx = 0f
        for (n <- 0 until viewportWidth) {
          val ch = line(n)
          if (ch != ' ') {
            if (fontBuilder == null) fontBuilder = renderBuff.getBuffer(selectType(i))
            val col = PackedColor.unpackForeground(color(n), format)
            drawChar(fontBuilder, stack.last.pose(), col, tx, ty.toFloat, ch.toInt)
          }
          tx += charWidth
        }
      }
    }
    stack.popPose()
  }

  def drawString(stack: PoseStack, s: String, x: Int, y: Int): Unit = {
    val sLength = stringLength(s)

    stack.pushPose()
    stack.translate(x.toFloat, y.toFloat, 0f)
    stack.scale(0.5f, 0.5f, 1)

    RenderSystem.depthMask(false)

    val byteBuffer = new ByteBufferBuilder(786432)
    val bufferSource = MultiBufferSource.immediate(byteBuffer)

    for (i <- 0 until textureCount) {
      val renderType = selectType(i)
      val builder = bufferSource.getBuffer(renderType)

      var tx = 0f
      var cx = 0
      for (_ <- 0 until sLength) {
        val ch = s.codePointAt(cx)
        if (ch != ' ') {
          drawChar(builder, stack.last.pose(), 0xFFFFFF, tx, 0f, ch)
        }
        tx += charWidth
        cx = s.offsetByCodePoints(cx, 1)
      }
      bufferSource.endBatch(renderType)
    }

    byteBuffer.close()

    RenderSystem.depthMask(true)
    stack.popPose()
    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)
  }

  /** OC 的 li.cil.oc.util.ExtendedUnicodeHelper.length（一行 codePointCount）。 */
  private def stringLength(s: String): Int = s.codePointCount(0, s.length)

  protected def charWidth: Int
  protected def charHeight: Int
  protected def textureCount: Int
  protected def selectType(index: Int): RenderType
  protected def generateChar(char: Int): Unit
  protected def drawChar(builder: VertexConsumer, matrix: Matrix4f, color: Int, tx: Float, ty: Float, char: Int): Unit

  private def drawQuad(builder: VertexConsumer, matrix: Matrix4f, color: Int, x: Int, y: Int, width: Int): Unit = {
    if (color != 0 && width > 0) {
      val x0 = x * charWidth
      val x1 = (x + width) * charWidth
      val y0 = y * charHeight
      val y1 = (y + 1) * charHeight
      val r = (color >> 16) & 0xFF
      val g = (color >> 8) & 0xFF
      val b = color & 0xFF
      builder.addVertex(matrix, x0.toFloat, y1.toFloat, 0).setUv(0f, 1f).setColor(r, g, b, 255)
      builder.addVertex(matrix, x1.toFloat, y1.toFloat, 0).setUv(1f, 1f).setColor(r, g, b, 255)
      builder.addVertex(matrix, x1.toFloat, y0.toFloat, 0).setUv(1f, 0f).setColor(r, g, b, 255)
      builder.addVertex(matrix, x0.toFloat, y0.toFloat, 0).setUv(0f, 0f).setColor(r, g, b, 255)
    }
  }
}
