package com.hdf.cryptand.neoforge.truescreen.client.renderer.vram

import com.hdf.cryptand.neoforge.truescreen.TrueScreenLog
import com.hdf.cryptand.neoforge.truescreen.client.renderer.{RenderState, RenderTypes}
import com.hdf.cryptand.neoforge.truescreen.network.{ScreenFrameCodec, ScreenImageFrame}
import com.hdf.cryptand.neoforge.truescreen.util.{PackedColor, TextBuffer => UtilTextBuffer}
import com.hdf.cryptand.soc.board.{ScreenFrameChecksum, ScreenTextLayer, TrueColorScreen}
import com.mojang.blaze3d.vertex.{ByteBufferBuilder, PoseStack, VertexConsumer}
import li.cil.oc.api.network.EnvironmentHost
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.resources.ResourceLocation
import net.neoforged.api.distmarker.{Dist, OnlyIn}
import org.joml.Matrix4f

/**
 * ===== 屏幕渲染的**唯一内容来源**：真彩屏 VRAM 设备（client 侧镜像）=====
 *
 * <h3>这个类做什么（任务 B 的核心，也是唯一允许的行为改动）</h3>
 * OC 的渲染链把"字符面（字符 + 打包前景/背景）"直接喂给字形 quad 生成器；本移植把内容来源
 * 换成 common 的 {@link TrueColorScreen}（1..32bpp + 调色板 + 锁帧 + 拼合），渲染只读它：
 * <ul>
 *   <li><b>TEXT 模式</b>：内容是字符格。字符面灌进设备的字符平面（一格 8x8，设备格坐标 <b>1 基</b>），
 *       再由 common 的 {@link ScreenTextLayer} + {@link com.hdf.cryptand.soc.board.TextFont8x8}
 *       合成 ARGB —— **不另写字形来源**（该路径已有离线闸门 runFontTest）；</li>
 *   <li><b>GRAPHICS 模式</b>：内容是像素。按设备的 bpp/调色板解码：逐像素 {@code rgbAt(x, y)}
 *       （它内部按 1/2/4/8bpp 查调色板、按 16bpp RGB565 展开）；{@code stride} 用来校验
 *       "灌进来的一帧字节数 = stride x height"；调色板由 {@code setPalette} 写入设备，
 *       {@code rgbAt} 解码时读回 —— 见 paletteSignature 的说明；</li>
 *   <li><b>锁帧</b>：GRAPHICS 模式且设备被锁 ⇒ 本帧**跳过**（不等待、不阻塞，保留上一帧贴图），
 *       与 {@link TrueColorScreen#renderTick} 同语义；TEXT 模式**不受锁影响**（照上游口径：
 *       OC 的 gpu.set 驱动的绘制不该被我们的锁吞掉）；</li>
 *   <li><b>拼合</b>：设备尺寸 = 整块逻辑屏的尺寸（origin 那一块持有；非 origin 不渲染）。</li>
 * </ul>
 *
 * <h3>内容怎么进来（两个入口都是显式的，且都只碰这个类）</h3>
 * <ul>
 *   <li>[B 负责] TEXT：{@link #feedText} —— 由 component.TextBuffer.ClientProxy 在
 *       内容变更信号（proxy.dirty / setChanged）触发时调用；</li>
 *   <li>[任务 C 负责] GRAPHICS：{@link #configureGraphics} + {@link #feedGraphics}
 *       + {@link #setPalette} + {@link #setLocked} + {@link #markSynced} —— 报文把服务端
 *       VRAM 帧/几何/调色板/锁状态送过来时调用。**任务 B 只提供接缝，不发报文**（报文属任务 C）。</li>
 * </ul>
 *
 * <h3>与旧自研渲染器的关系（不冲突、不复制）</h3>
 * 旧的 soc/client/TrueScreenRenderer 也读 TrueColorScreen，但那是旧件 —— **已于任务 F-2
 * （2026-09-27）随旧自研真彩屏整体删除**（此处只留历史说明）。
 * 本类是移植链上的**新**内容来源，与旧件的 Uploaded 缓存思路同源但独立存在 —— 两者不会同时
 * 注册（BER 注册属任务 C）。
 */
@OnlyIn(Dist.CLIENT)
final class TrueScreenVram(val host: EnvironmentHost) {

  /** TEXT 模式几何：一个字符格 = CELL_W × CELL_H 像素（像素设备的口径；OC 终端参数 8×16）。 */
  private final val CellW = TrueColorScreen.CELL_W
  private final val CellH = TrueColorScreen.CELL_H

  /** VRAM 设备本体（client 侧镜像；渲染只读它，写入一律走本类的方法）。 */
  private var device: TrueColorScreen = _

  /**
   * 本地镜像的**整屏校验**（用户需求 2026-09-29）：客户端每次更新后算一次，
   * 与服务端随帧下发的值比对（算式只有一处：common 的 `ScreenFrameChecksum`）。
   * 设备还没建（未 configure）时返回 0 —— 与服务端"无设备时 0"同款。
   */
  def checksum: Int = if (device == null) 0 else ScreenFrameChecksum.of(device)

  private var texture: DynamicTexture = _
  private var location: ResourceLocation = _
  private var renderType: net.minecraft.client.renderer.RenderType = _
  private var argb: Array[Int] = _

  // ==================== 图像帧同步（2026-09-28）====================
  //
  // 用户定案：内容由服务器**合成成 ARGB 图像**下发（TEXT 也由服务器合成），客户端只贴图。
  // 采样网格（imageGridW/H）可以比屏幕像素小（远处按距离抽点），贴图按网格建、按像素尺寸铺。

  /** 服务器合成好的整幅 ARGB（采样网格；null = 还没收到过全量）。 */
  private var imageGrid: Array[Int] = _

  /** 屏幕像素尺寸（quad 按它铺；来自报文，权威在服务端设备）。 */
  private var imageW = 0
  private var imageH = 0
  private var imageGridW = 0
  private var imageGridH = 0
  private var imageStepW = 1
  private var imageStepH = 1
  private var imageFrameId = 0L

  /** "内容变了、还没合成进贴图"（唯一的重传信号；由 feedText / setPalette / configureGraphics 置位）。 */
  private var uploadPending = true

  // ===== 客户端 LOD 状态（2026-09-29）=====
  //
  // 用户定案：「lod 效果最好**核心自己实现**，外部调用使用**傻瓜式使用**」。
  // ⇒ 本类只存外部给的两样东西：**阶梯表**（注册时给一次）与**距离**（每帧给一个数）；
  //    查表 / 档间插值 / 平滑 / 选采样算法全在核心的 com.hdf.cryptand.lod.Lod 里。
  /**
   * LOD 阶梯表。
   *
   * <p>默认**与注册处同源**（都读 opencomputers.toml 的 trueScreenLodStairs / trueScreenLodSmooth）：
   * 否则会出现"注册按配置、渲染按内置默认"的错配 —— 用户改了配置却不生效，这种 bug 最难查。</p>
   */
  private var lodStairsCache: com.hdf.cryptand.lod.LodStairs = null

  /**
   * 当前生效的阶梯表：**懒读配置**（与注册处同源）。
   *
   * <p>为什么懒读：注册期读配置会抛 "Trying to access unbound value"（NeoForge 配置尚未绑定）；
   * 而本对象在运行期才被创建/使用，那时配置已经可用。解析失败会明确抛错，不静默用默认表。</p>
   */
  private def lodStairs: com.hdf.cryptand.lod.LodStairs = {
    if (lodStairsCache == null) {
      lodStairsCache = com.hdf.cryptand.lod.LodStairs.parse(
        com.hdf.cryptand.neoforge.opencomputers.config.ConfigOpenComputers.trueScreenLodStairs(),
        com.hdf.cryptand.neoforge.opencomputers.config.ConfigOpenComputers.trueScreenLodSmooth())
    }
    lodStairsCache
  }

  /** 当前距相机距离（格），由渲染方每帧给。 */
  private var lodDistance = 0.0
  /** 重采样缓冲（复用，避免每帧分配）。 */
  private var lodBuffer: Array[Int] = _

  /** 当前生效的步长（诊断 / 拉取节流用）。 */
  /**
   * 当前生效的步长：**阶梯表口径**（用户定案的傻瓜门面）算出的值，再交给 LOD 后端链允许覆盖。
   *
   * <p>用户定案：「配置可以配置后端链条，优先使用第一有效」。默认链里没有会覆盖的后端 ⇒
   * 结果恒等于阶梯表步长（零行为变化）；外部后端（Flywheel / voxy / DH 之类）注册并排到链首后
   * 即可覆盖档位，且覆盖日志可见。</p>
   */
  def lodStep: Int = lodStepOf(imageGridW, imageGridH)

  /** 按给定网格尺寸算最终步长（同一口径，避免两处各算一次而错配）。 */
  private def lodStepOf(gridW: Int, gridH: Int): Int = com.hdf.cryptand.lod.Lod.stepViaChain(
    com.hdf.cryptand.lod.Lod.stepFor(lodStairs, lodDistance),
    gridW, gridH, blockW, blockH, lodDistance)

  /** 注册时传入该屏自己的阶梯（用户："oc 部分只需要注册时传入这个参数即可"）。 */
  def setLodStairs(stairs: com.hdf.cryptand.lod.LodStairs): Unit = {
    if (stairs != null) {
      lodStairsCache = stairs
      uploadPending = true
    }
  }

  /**
   * **傻瓜入口**：外部只给距离。
   *
   * <p>查表、档间插值、平滑与否、块平均还是点采样 —— 全部由核心决定。
   * （上一版要求外部先算 stepX/stepY 再传进来，等于把 LOD 策略泄漏给调用方，用户明确否掉了。）</p>
   */
  def setLodDistance(distanceBlocks: Double): Unit = {
    if (distanceBlocks != lodDistance) {
      lodDistance = distanceBlocks
      uploadPending = true
    }
  }

  /** 屏的方块尺寸（拼接后是 N×M 格）—— LOD 档位换算要用它，默认按 1 格（单格屏的常见情形）。 */
  private var blockW = 1
  private var blockH = 1

  def blockWidth: Int = blockW
  def blockHeight: Int = blockH

  /** 由持有方块实体的一侧注入真实拼接尺寸（单格屏可不动）。 */
  def setBlockSize(width: Int, height: Int): Unit = {
    val w = Math.max(1, width)
    val h = Math.max(1, height)
    // 只在变化时写（本方法会被每帧调用一次，幂等是它的契约）
    if (w != blockW || h != blockH) {
      blockW = w
      blockH = h
    }
  }

  /** 当前实际用于上传的宽（LOD 后；诊断用）。 */
  def uploadWidth: Int = com.hdf.cryptand.lod.Lod2D.downsampledWidth(imageGridW, lodStep)

  /** 当前实际用于上传的高（LOD 后）。 */
  def uploadHeight: Int = com.hdf.cryptand.lod.Lod2D.downsampledHeight(imageGridH, lodStep)

  /** 已经灌过一次 TEXT 内容（第一次必须灌，哪怕 dirty 为 false）。 */
  private var textFed = false

  private var textureSerial = 0

  /** "字形缺失"只报一次（ScreenTextLayer 会把认不出的格数返回给我们）。 */
  private var unknownGlyphReported = false

  private val textureName: String = {
    val level = host.getEnvironmentLevel
    val dim = if (level == null) "none" else level.dimension().location().toString
    "cryptand_truescreen_" + TrueScreenVram.sanitize(dim) + "_" +
      host.xPosition().toInt + "_" + host.yPosition().toInt + "_" + host.zPosition().toInt
  }

  // ----------------------------------------------------------------------- //
  // 对外（尺寸 / 内容灌入 / 状态）
  // ----------------------------------------------------------------------- //

  /** 当前帧的像素宽（= OC 的 renderWidth 的替代）。有图像帧时以它为准（服务端权威）。 */
  def pixelWidth: Int = if (imageGrid != null) imageW else if (device == null) 0 else device.width()

  /** 当前帧的像素高（= OC 的 renderHeight 的替代）。 */
  def pixelHeight: Int = if (imageGrid != null) imageH else if (device == null) 0 else device.height()

  /** 有没有收到过服务器合成的图像（没有 ⇒ 拉取时请求全量）。 */
  def hasImage: Boolean = imageGrid != null

  /**
   * 落一帧服务器合成的图像（全量或增量）。
   *
   * <p>形状不符的**增量**会被丢弃（返回 false）：没有基准就不能接增量，服务端会在下一次
   * 全量请求时补上（客户端 pullImageFrame 用 hasImage 决定请求全量还是增量）。</p>
   *
   * @return 是否真的落了
   */
  def applyImageFrame(frame: ScreenImageFrame): Boolean = {
    val t0 = System.nanoTime()                 // 指标：客户端贴图耗时
    if (frame == null) return false
    val gw = ScreenFrameCodec.gridWidth(frame.width, frame.stepW)
    val gh = ScreenFrameCodec.gridHeight(frame.height, frame.stepH)
    val rebase = imageGrid == null || imageGridW != gw || imageGridH != gh ||
      imageW != frame.width || imageH != frame.height
    if (rebase) {
      if (!frame.full) return false                    // 没基准不接增量
      imageGrid = new Array[Int](gw * gh)
      imageGridW = gw
      imageGridH = gh
      imageW = frame.width
      imageH = frame.height
      imageStepW = frame.stepW
      imageStepH = frame.stepH
    }
    var at = 0
    var i = 0
    while (i < frame.blocks.length) {
      val b = frame.blocks(i)
      var r = 0
      while (r < b.rowCount) {                 // 矩形：逐行只贴列区间（与服务器侧紧凑打包对称）
        System.arraycopy(frame.rows, at, imageGrid, (b.firstRow + r) * gw + b.firstCol, b.colCount)
        at += b.colCount
        r += 1
      }
      i += 1
    }
    imageFrameId = frame.frameId
    uploadPending = true
    com.hdf.cryptand.soc.board.ScreenFrameMetrics.CLIENT.recordFrame(
      frame.full, frame.blocks.length, at.toLong, at.toLong * 4L, System.nanoTime() - t0)
    val metricsLine = com.hdf.cryptand.soc.board.ScreenFrameMetrics.CLIENT.summaryIfDue(10000L)
    if (metricsLine != null) {
      com.hdf.cryptand.neoforge.truescreen.TrueScreenLog.log.info("[metrics] {}", metricsLine)
    }
    true
  }

  /** 设备（只读用；诊断/断言）。 */
  def vram: TrueColorScreen = device

  /**
   * 建/重建 TEXT 模式的设备（尺寸 = viewport x CELL，色深 = 字符面的 bpp）。
   * 尺寸或色深变了才换实例（与"设备换实例 ⇒ 字符面清空"的语义一致）。
   *
   * <p>⚠ 这里的 TEXT/GRAPHICS 是**客户端镜像**：面由**服务端派生**（common 的
   * {@code ScreenOutputFace}，入口在 {@code OcComponentBus}）后随 GraphcisFrame 报文的
   * {@code mode} 字段过来，镜像只是照它选渲染面。客户端自己不判定、也没有开关
   * （用户定案：屏设备的 TEXT/GRAPHICS 是内部派生状态，不是 guest 开关）。</p>
   */
  def ensureTextShape(viewport: (Int, Int), bpp: Int): Unit = {
    val vw = math.max(1, viewport._1)
    val vh = math.max(1, viewport._2)
    val px = vw * CellW
    val py = vh * CellH
    val needsRebuild = device == null || device.mode() != TrueColorScreen.Mode.TEXT ||
      device.width() != px || device.height() != py || device.depth().bits() != bpp
    if (needsRebuild) {
      device = TrueColorScreen.of(px, py, bpp)
      device.setMode(TrueColorScreen.Mode.TEXT)
      textFed = false
      dropTexture()
      uploadPending = true
    }
  }

  /**
   * TEXT：把**字符面**灌进 VRAM（内容来源切换的那一步）。
   *
   * @param data    已移植的字符缓冲（字符 + 打包前景/背景）
   * @param viewport 当前视口（字符列 x 行）
   * @param bpp     字符面的色深（1/4/8/16 —— 与 TrueColorScreen 的档位一致）
   * @param changed 内容变更信号（component 侧 proxy.dirty / setChanged）
   * @return 本次是否真的重灌了（false = 形状没变且没有变更信号）
   */
  /** 诊断计数（临时） */
  private var diagFeedApplied = 0
  private var diagFeedSkipped = 0
  private var diagUploads = 0

  def feedText(data: UtilTextBuffer, viewport: (Int, Int), bpp: Int, changed: Boolean): Boolean = {
    if (data == null) return false
    val before = device
    ensureTextShape(viewport, bpp)
    val shapeChanged = before ne device
    if (!changed && !shapeChanged && textFed) {
      if (diagFeedSkipped < 3) {
        TrueScreenLog.log.info("[TrueScreen/DIAG] feedText 跳过（changed=false shapeChanged=false textFed=true）→ 贴图不会再更新")
        diagFeedSkipped += 1
      }
      return false
    }
    if (diagFeedApplied < 5 || (diagFeedApplied % 100) == 0) {
      val packed0: Short = if (data.color.length > 0 && data.color(0).length > 0) data.color(0)(0) else 0.toShort
      val fg0 = PackedColor.unpackForeground(packed0, data.format)
      val bg0 = PackedColor.unpackBackground(packed0, data.format)
      TrueScreenLog.log.info("[TrueScreen/DIAG] feedText 应用#{} viewport={}x{} bpp={} changed={} fmt={} 缓冲={}x{} 首格code={} fg=0x{} bg=0x{} packed=0x{}",
        Integer.valueOf(diagFeedApplied), Integer.valueOf(viewport._1), Integer.valueOf(viewport._2),
        Integer.valueOf(bpp), java.lang.Boolean.valueOf(changed), String.valueOf(data.format),
        Integer.valueOf(data.height), Integer.valueOf(if (data.buffer.length > 0) data.buffer(0).length else -1),
        Integer.valueOf(if (data.buffer.length > 0 && data.buffer(0).length > 0) data.buffer(0)(0).toInt else -1),
        Integer.valueOf(fg0), Integer.valueOf(bg0), Integer.valueOf(packed0.toInt))
      diagFeedApplied += 1
    }

    val d = device
    val rows = math.min(viewport._2, math.min(data.height, math.min(data.buffer.length, data.color.length)))
    var row = 0
    while (row < rows) {
      val line = data.buffer(row)
      val colors = data.color(row)
      val cols = math.min(viewport._1, math.min(line.length, colors.length))
      var col = 0
      while (col < cols) {
        val packed = colors(col)
        val fg = PackedColor.unpackForeground(packed, data.format)
        val bg = PackedColor.unpackBackground(packed, data.format)
        // 设备字符平面是 1 基；超 BMP 码点在 Char 上截断（取不到字形 ⇒ 只画背景，由 ScreenTextLayer 计数）
        d.setText(col + 1, row + 1, line(col).toChar, fg, bg)
        col += 1
      }
      row += 1
    }
    textFed = true
    uploadPending = true
    true
  }

  /** GRAPHICS：建/重建像素设备（几何来自报文的派生结果；同上，客户端只是镜像那一面）。 */
  def configureGraphics(width: Int, height: Int, bpp: Int): Unit = {
    val needsRebuild = device == null || device.width() != width || device.height() != height ||
      device.depth().bits() != bpp || device.mode() != TrueColorScreen.Mode.GRAPHICS
    if (needsRebuild) {
      device = TrueColorScreen.of(width, height, bpp)
      device.setMode(TrueColorScreen.Mode.GRAPHICS)
      dropTexture()
    }
    uploadPending = true
  }

  /**
   * GRAPHICS：灌一整帧 VRAM 字节（长度必须 = {@code stride x height}；不等就明确报错，不截断）。
   * VRAM 本体就是 guest 内存的映射视图，所以这里是一次 arraycopy（不是逐像素 setRgb）。
   */
  def feedGraphics(frame: Array[Byte]): Unit = {
    val d = device
    if (d == null) {
      TrueScreenLog.log.warn("[TrueScreen] feedGraphics 时设备还没建立（先调 configureGraphics）")
      return
    }
    val expected = d.frameBytes()
    if (frame == null || frame.length != expected) {
      val actual = if (frame == null) "null" else frame.length.toString
      val message = "VRAM 帧字节数不符：期望 " + expected + "（stride " + d.stride() + " x " + d.height() +
        " @ " + d.depth().bits() + "bpp），给了 " + actual
      d.fail(message)
      TrueScreenLog.log.warn("[TrueScreen] " + message)
      return
    }
    System.arraycopy(frame, 0, d.vram(), 0, expected)
    uploadPending = true
  }

  /** GRAPHICS：写调色板（直色色深没有调色板，调用会被设备明确拒绝 ⇒ 这里先判断，不静默吞）。 */
  def setPalette(colors: Array[Int]): Unit = {
    val d = device
    if (d == null) return
    if (!d.depth().palette()) {
      TrueScreenLog.log.warn("[TrueScreen] setPalette 于直色色深（" + d.depth().bits() + "bpp）无意义，已忽略")
      return
    }
    val n = math.min(if (colors == null) 0 else colors.length, d.paletteSize())
    var i = 0
    while (i < n) {
      d.setPaletteColor(i, colors(i))
      i += 1
    }
    uploadPending = true
  }

  /** GRAPHICS：锁帧开关（服务端的状态同步过来；被锁 ⇒ 渲染跳过这一帧）。 */
  def setLocked(locked: Boolean): Unit = {
    val d = device
    if (d == null) return
    if (locked) d.lock() else d.unlock()
  }

  /** 把服务端的帧号/跳过数抄进设备（客户端据此做断言/诊断；与旧件 markSynced 同义）。 */
  def markSynced(frames: Long, skipped: Long): Unit = {
    val d = device
    if (d != null) d.markSynced(frames, skipped)
  }

  // ----------------------------------------------------------------------- //
  // 绘制
  // ----------------------------------------------------------------------- //

  /**
   * 画一张覆盖 VRAM 帧像素尺寸（pixelWidth x pixelHeight）的 quad。
   *
   * ⚠ 调用方（ScreenRenderer.draw / BufferRenderer 之前的 stack）已经把 stack 缩放到
   * "1 单位 = 1 像素"的口径 —— 与上游字符路径同构：上游一个字符格宽 = charRenderWidth 单位，
   * 我方一个像素 = 1 单位。所以这里只需要 (0,0)-(w,h) 的整块 quad。
   *
   * @param alpha 上游 draw 的同名参数（OC 在 1.21 分支里**没有使用**它：淡出靠
   *              RenderSystem.setShaderColor，而 render() 开头就把 shaderColor 重置成 (1,1,1,1)）。
   *              本移植照抄这一事实，不额外引入淡出实现。
   * @return 本帧是否真的画了（false = 没设备 / 图形模式被锁 / 尺寸非法）
   */
  def draw(stack: PoseStack, source: MultiBufferSource, alpha: Float): Boolean = {
    RenderState.checkError(getClass.getName + ".draw: entering")
    val d = device
    val w = pixelWidth
    val h = pixelHeight
    if (w <= 0 || h <= 0) return false
    if (d != null && d.mode() == TrueColorScreen.Mode.GRAPHICS && d.locked()) {
      // 锁帧：跳过这一帧（不等待、不阻塞；贴图保持上一帧）
      return false
    }

    uploadIfNeeded()

    val matrix = stack.last.pose()
    val wf = w.toFloat
    val hf = h.toFloat
    if (source == null) {
      val byteBuffer = new ByteBufferBuilder(786432)
      try {
        val buffers = MultiBufferSource.immediate(byteBuffer)
        writeQuad(buffers.getBuffer(renderType), matrix, wf, hf)
        buffers.endBatch()
      } finally {
        byteBuffer.close()
      }
    } else {
      writeQuad(source.getBuffer(renderType), matrix, wf, hf)
    }

    RenderState.checkError(getClass.getName + ".draw: leaving")
    true
  }

  // ----------------------------------------------------------------------- //
  // 内容 -> 贴图
  // ----------------------------------------------------------------------- //

  private def uploadIfNeeded(): Unit = {
    // 图像帧路径（2026-09-28 起的唯一正常路径）：贴图按采样网格建，内容就是服务器合成的 ARGB。
    if (imageGrid != null) {
      // ===== 客户端 LOD（2026-09-29 用户定案：LOD 全在客户端，服务端恒发全分辨率）=====
      // 远处按步长抽稀后再上传：贴图更小、上传更少。抽稀口径与合成器一致（Lod2D.downsample 注释有依据）。
      // 步长与上传尺寸用**同一个**最终口径（都经后端链），避免"步长 8 却按步长 4 算尺寸"的错配
      val step = lodStepOf(imageGridW, imageGridH)
      val upW = com.hdf.cryptand.lod.Lod2D.downsampledWidth(imageGridW, step)
      val upH = com.hdf.cryptand.lod.Lod2D.downsampledHeight(imageGridH, step)
      val source =
        if (step <= 1) {
          imageGrid                                    // 近处：零拷贝走原路
        } else {
          val need = upW * upH
          if (lodBuffer == null || lodBuffer.length < need) {
            lodBuffer = new Array[Int](need)
          }
          // 平滑与否、块平均还是点采样 —— 由核心决定（Lod.resampleTo），调用方不参与
          com.hdf.cryptand.lod.Lod.resampleTo(lodStairs, imageGrid, imageGridW, imageGridH, lodDistance, lodBuffer)
          lodBuffer
        }
      if (texture == null || texture.getPixels.getWidth != upW || texture.getPixels.getHeight != upH) {
        createTexture(upW, upH)
        uploadPending = true
      }
      if (!uploadPending) return
      val px = texture.getPixels
      var y = 0
      while (y < upH) {
        val rowBase = y * upW
        var x = 0
        while (x < upW) {
          // NativeImage.setPixelRGBA 要 0xAABBGGRR（内存 R,G,B,A 字节序）；我方是 0xAARRGGBB。
          val c = source(rowBase + x)
          px.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c & 0x00FF0000) >>> 16) | ((c & 0xFF) << 16))
          x += 1
        }
        y += 1
      }
      texture.upload()
      uploadPending = false
      return
    }

    val d = device
    if (d == null) {
      uploadPending = false
      return
    }
    if (texture == null || texture.getPixels.getWidth != d.width() || texture.getPixels.getHeight != d.height()) {
      createTexture(d.width(), d.height())
      uploadPending = true
    }
    if (!uploadPending) return

    val w = d.width()
    val h = d.height()
    if (argb == null || argb.length != w * h) argb = new Array[Int](w * h)

    d.mode() match {
      case TrueColorScreen.Mode.TEXT =>
        // TEXT：复用 common 的字符层（字形位来自 TextFont8x8；认不出的格只画背景并计数）
        val unknown = ScreenTextLayer.paint(d, argb)
        if (unknown > 0 && !unknownGlyphReported) {
          unknownGlyphReported = true
          TrueScreenLog.log.info("[TrueScreen] TEXT 面上有 " + unknown + " 个字符格在 8x8 字形表里没有字形（只画背景）")
        }
      case TrueColorScreen.Mode.GRAPHICS =>
        rasterizeGraphics(d, argb)
    }

    val pixels = texture.getPixels
    var y = 0
    while (y < h) {
      val rowBase = y * w
      var x = 0
      while (x < w) {
        // NativeImage 的 setPixelRGBA 要的是 0xAABBGGRR（内存里按 R,G,B,A 字节序），
        // 而 ScreenTextLayer / rgbAt 给的是 0xAARRGGBB ⇒ 交换 R/B。
        val c = argb(rowBase + x)
        pixels.setPixelRGBA(x, y, (c & 0xFF00FF00) | ((c & 0x00FF0000) >>> 16) | ((c & 0xFF) << 16))
        x += 1
      }
      y += 1
    }
    texture.upload()
    uploadPending = false
    if (diagUploads < 5) {
      var diff = 0
      val first = if (argb.length > 0) argb(0) else 0
      var i = 0
      while (i < argb.length) {
        if (argb(i) != first) diff += 1
        i += 1
      }
      TrueScreenLog.log.info("[TrueScreen/DIAG] 上传#{} mode={} {}x{} argb[0]=0x{} 与首像素不同的像素数={}/{}",
        Integer.valueOf(diagUploads), String.valueOf(d.mode()),
        Integer.valueOf(w), Integer.valueOf(h), Integer.valueOf(first), Integer.valueOf(diff), Integer.valueOf(argb.length))
      diagUploads += 1
    }
  }

  /**
   * GRAPHICS：逐像素 {@code rgbAt}（它内部按色深解码：1/2/4/8bpp 查调色板、16bpp 展开 RGB565、
   * 24/32bpp 直取）。越界由调用方保证（x/y 都来自设备自己的 width/height）。
   */
  private def rasterizeGraphics(d: TrueColorScreen, out: Array[Int]): Unit = {
    val w = d.width()
    val h = d.height()
    var y = 0
    while (y < h) {
      val rowBase = y * w
      var x = 0
      while (x < w) {
        out(rowBase + x) = 0xFF000000 | d.rgbAt(x, y)
        x += 1
      }
      y += 1
    }
  }

  private def createTexture(w: Int, h: Int): Unit = {
    dropTexture()
    textureSerial += 1
    texture = new DynamicTexture(w, h, false)
    location = Minecraft.getInstance.getTextureManager.register(textureName + "_" + textureSerial, texture)
    // 过滤：TrueScreenSettings.textLinearFiltering（OC application.conf:35，默认 false）。
    // ⚠ 上游该开关当前**无引用点**（Settings.scala 声明了但没人读），这里按任务的
    // "过滤参数别改"口径把它接到新 RenderType 上（值相同 ⇒ 行为与上游默认一致）。
    renderType = RenderTypes.createScreenTex(textureName + "_" + textureSerial, location,
      com.hdf.cryptand.neoforge.truescreen.TrueScreenSettings.textLinearFiltering)
  }

  private def dropTexture(): Unit = {
    if (texture != null) {
      try {
        texture.close()
      } catch {
        case _: Throwable => // 贴图已经没了就算了（只有诊断价值）
      }
      if (location != null) Minecraft.getInstance.getTextureManager.release(location)
      texture = null
      location = null
      renderType = null
    }
  }

  private def writeQuad(builder: VertexConsumer, matrix: Matrix4f, w: Float, h: Float): Unit = {
    // 顶点顺序照上游 drawQuad：左下 -> 右下 -> 右上 -> 左上；UV 的 v 向下（与字形路径一致，
    // 因为 ScreenRenderer.transform() 已经做过 mirrorScale(1,-1,1)）。
    builder.addVertex(matrix, 0f, h, 0f).setUv(0f, 1f).setColor(255, 255, 255, 255)
    builder.addVertex(matrix, w, h, 0f).setUv(1f, 1f).setColor(255, 255, 255, 255)
    builder.addVertex(matrix, w, 0f, 0f).setUv(1f, 0f).setColor(255, 255, 255, 255)
    builder.addVertex(matrix, 0f, 0f, 0f).setUv(0f, 0f).setColor(255, 255, 255, 255)
  }
}

object TrueScreenVram {
  /** 贴图名只允许 [a-z0-9/._-]（ResourceLocation 的 path 规则）。 */
  private[vram] def sanitize(value: String): String = {
    val sb = new StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
      val c = value.charAt(i)
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' || c == '/') sb.append(c)
      else sb.append('_')
      i += 1
    }
    sb.toString
  }
}
