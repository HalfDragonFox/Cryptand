/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/renderer/TextBufferRenderCache.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client.renderer

import com.hdf.cryptand.neoforge.truescreen.client.renderer.font.TextureFontRenderer
import com.hdf.cryptand.neoforge.truescreen.client.renderer.vram.TrueScreenVram
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.renderer.MultiBufferSource
import net.neoforged.api.distmarker.{Dist, OnlyIn}

/**
 * 渲染缓存入口（OC 的 TextBufferRenderCache 的移植 + **内容来源替换**）。
 *
 * <h3>相对上游的改动，只有一处</h3>
 * 上游是 render/renderImmediate/renderDirect 三个方法，数据源类型是 font.TextBufferRenderData
 * （字符面 + 视口），内部把每个字符格交给字形渲染器生成 quad。
 * 本移植**保持这三个方法的名字、签名形状与调用点**，只把数据源换成
 * {@link TrueScreenVram}（真彩屏 VRAM 设备）—— 也就是"只改内容来源"：
 * 字符面 → quad 的生成整条被 VRAM 帧 → 一张 DynamicTexture 取代，
 * 而"谁在什么距离/什么 stack 下调它"（ScreenRenderer.draw / BufferRenderer）一个字都没改。
 *
 * <h3>font.renderer（字形渲染器）的保留与边界</h3>
 * 上游的对象里有一个 renderer 字段（按 Settings.fontRenderer 选 Static/ Dynamic）。
 * 本移植保留该字段（= font.StaticFontRenderer，**纯图集字形**那一套，带 fontCharScale 与
 * textAntiAlias 两个参数），但它**不是屏幕内容的字形来源**：
 * 屏幕内容一律来自 VRAM（TEXT 用 common 的 ScreenTextLayer + TextFont8x8）。
 * 上游的另一个分支 DynamicFontRenderer（hexfont，application.conf:87 的默认值）**故意不移植**：
 * 它读 cryptand:font.hex 从零光栅化字形，等于再引入一套字形来源 —— 与任务书
 * "别另写字形来源"直接冲突（详见任务 B 报告的阻塞/裁决项）。
 */
@OnlyIn(Dist.CLIENT)
object TrueScreenRenderCache {

  /**
   * 字形渲染器（上游同名字段）。屏幕内容路径**不经过它**；保留它是为了忠实移植 L5 的这一层，
   * 并让"字体缩放/过滤/抗锯齿"三个参数有明确的落点（fontCharScale / textAntiAlias /
   * RenderTypes 的 NEAR·LINEAR 文本状态）。
   */
  lazy val renderer: TextureFontRenderer = new font.StaticFontRenderer()

  // ----------------------------------------------------------------------- //
  // Rendering
  // ----------------------------------------------------------------------- //

  /** 上游 render(stack, buffer) 的对应物：没有外部 MultiBufferSource 的路径（GUI 等）。 */
  def render(stack: PoseStack, buffer: TrueScreenVram): Boolean = {
    RenderState.checkError(getClass.getName + ".render: entering")
    // 用户定案（2026-09-29）：**LOD 只对非 UI 部分生效**。
    // 这条路正是 GUI/界面路径（上游注释：没有外部 MultiBufferSource）—— 界面里必须全分辨率，
    // 而且要**显式复位**：否则打开界面时会沿用世界里的低分辨率档位（表现就是"UI 里也是一格一格"）。
    buffer.setLodDistance(0.0)
    val drawn = renderDirect(stack, null, buffer)
    RenderState.checkError(getClass.getName + ".render: leaving")
    drawn
  }

  /** 上游 renderImmediate(stack, renderBuffer, buffer) 的对应物：世界侧（有 buffer 可复用）。 */
  def renderImmediate(stack: PoseStack, renderBuffer: MultiBufferSource, buffer: TrueScreenVram): Boolean = {
    RenderState.checkError(getClass.getName + ".renderImmediate: entering")
    // 世界路径才应用 LOD（按距离抽稀）；UI 路径在上面显式复位成全分辨率。
    applyClientLod(stack, buffer)
    val drawn = renderDirect(stack, renderBuffer, buffer)
    RenderState.checkError(getClass.getName + ".renderImmediate: leaving")
    drawn
  }

  private def renderDirect(stack: PoseStack,
                           renderBuffer: MultiBufferSource,
                           buffer: TrueScreenVram): Boolean = {
    // 上游在这里对每一行调 renderer.generateChars(line) 做预热；VRAM 路径的字形是即取即用
    // （ScreenTextLayer 直接查 TextFont8x8 位表），没有"预热"这一步。
    // ⚠ LOD 不在这里算：世界路径在 renderImmediate 里算，UI 路径显式复位（见上）。
    buffer.draw(stack, renderBuffer, 1f)
  }

  /**
   * 客户端 LOD（2026-09-29 用户定案："LOD 之类的渲染**全部由客户端自己实现**"）。
   *
   * <p>距离**直接从 PoseStack 的平移分量取**：ScreenRenderer 已经把"方块位置 − 相机位置"
   * 乘进了矩阵，所以这里不必再单独查世界坐标（否则两套坐标容易脱节）。</p>
   *
   * <p>算出的步长交给 {@link TrueScreenVram} 的 setLod，由它决定上传时抽稀多少 ——
   * 服务端始终发全分辨率（服务端被动、不做 LOD），抽稀纯属客户端行为。</p>
   */
  private def applyClientLod(stack: PoseStack, buffer: TrueScreenVram): Unit = {
    val w = buffer.pixelWidth
    val h = buffer.pixelHeight
    if (w <= 0 || h <= 0) {
      return
    }
    val pose = stack.last.pose()
    val dx = pose.m30()
    val dy = pose.m31()
    val dz = pose.m32()
    // 傻瓜调用（用户定案"外部调用使用傻瓜式使用"）：核心只要一个距离。
    // 查表（阶梯）、档间插值、平滑与否、块平均还是点采样，全部由 Lod / TrueScreenVram 内部决定 ——
    // 本类不再自己拼 Lod2DRequest、也不再算 pixelsPerRadian（那套连续算法正是用户说"距离不对"的那版）。
    buffer.setLodDistance(math.sqrt(dx * dx + dy * dy + dz * dz))
  }
}
