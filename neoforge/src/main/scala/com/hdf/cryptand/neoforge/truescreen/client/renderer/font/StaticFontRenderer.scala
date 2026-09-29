/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/renderer/font/StaticFontRenderer.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client.renderer.font

import com.google.common.base.Charsets
import com.hdf.cryptand.neoforge.truescreen.{TrueScreenLog, TrueScreenSettings}
import com.hdf.cryptand.neoforge.truescreen.client.TrueScreenTextures
import com.hdf.cryptand.neoforge.truescreen.client.renderer.RenderTypes
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderType
import net.minecraft.resources.ResourceLocation
import org.joml.Matrix4f

import scala.io.Source

/**
 * 图集字形渲染器（OC 的 StaticFontRenderer 逐字移植）。
 *
 * 保留的三个渲染参数就是任务书点名的"字体缩放 / 过滤 / 抗锯齿"：
 *   fontCharScale（TrueScreenSettings，OC application.conf:52 = 1.01）、
 *   textAntiAlias（:39 = true，选 chars / chars_aliased 两套图集）、
 *   textLinearFiltering（:35 = false —— ⚠ 上游**当前没有任何引用点**，
 *   本移植同样不引入引用，保持"声明了但不用"的上游事实；过滤状态由 RenderTypes 的
 *   NEAR/LINEAR 文本状态按 antiAlias 选择，与上游一致）。
 *
 * 三处最小替代：Settings.get.fontCharScale/textAntiAlias → TrueScreenSettings.*；
 * Settings.resourceDomain → TrueScreenTextures.ModId；OpenComputers.log → TrueScreenLog.log。
 */
class StaticFontRenderer extends TextureFontRenderer {
  protected val (chars, charWidth, charHeight) = try {
    val manager = Minecraft.getInstance.getResourceManager
    val location = ResourceLocation.fromNamespaceAndPath(TrueScreenTextures.ModId, "textures/font/chars.txt")
    val optRes = manager.getResource(location)
    if (optRes.isPresent) {
      val is = optRes.get.open
      val lines = Source.fromInputStream(is)(Charsets.UTF_8).getLines()
      val charStr = lines.next()
      val (w, h) = if (lines.hasNext) {
        val size = lines.next().split(" ", 2)
        (size(0).toInt, size(1).toInt)
      } else (10, 18)
      (charStr, w, h)
    } else {
      (basicChars, 10, 18)
    }
  } catch {
    case t: Throwable =>
      TrueScreenLog.log.warn("Failed reading font metadata, using defaults.", t)
      (basicChars, 10, 18)
  }

  private val cols = 256 / charWidth
  private val uStep = charWidth / 256f
  private val vStep = (charHeight + 1) / 256f
  private val vSize = charHeight / 256f
  private val s = TrueScreenSettings.fontCharScale.toFloat
  private val dw = charWidth * s - charWidth
  private val dh = charHeight * s - charHeight

  override protected def textureCount = 1

  override protected def selectType(index: Int): RenderType = {
    val isAntiAlias = TrueScreenSettings.textAntiAlias
    val location = if (isAntiAlias) TrueScreenTextures.Font.AntiAliased else TrueScreenTextures.Font.Aliased
    RenderTypes.createFontTex(location.getPath, location, isAntiAlias)
  }

  override protected def generateChar(char: Int): Unit = {}

  override protected def drawChar(builder: VertexConsumer, matrix: Matrix4f, color: Int, tx: Float, ty: Float, char: Int): Unit = {
    val index = chars.indexOf(char) match {
      case -1 => chars.indexOf('?')
      case i => i
    }
    val x = index % cols
    val y = index / cols
    val u = x * uStep
    val v = y * vStep
    val r = ((color >> 16) & 0xFF) / 255f
    val g = ((color >> 8) & 0xFF) / 255f
    val b = (color & 0xFF) / 255f

    builder.addVertex(matrix, tx - dw, ty + charHeight * s, 0).setColor(r, g, b, 1f).setUv(u, v + vSize)
    builder.addVertex(matrix, tx + charWidth * s, ty + charHeight * s, 0).setColor(r, g, b, 1f).setUv(u + uStep, v + vSize)
    builder.addVertex(matrix, tx + charWidth * s, ty - dh, 0).setColor(r, g, b, 1f).setUv(u + uStep, v)
    builder.addVertex(matrix, tx - dw, ty - dh, 0).setColor(r, g, b, 1f).setUv(u, v)
  }
}
