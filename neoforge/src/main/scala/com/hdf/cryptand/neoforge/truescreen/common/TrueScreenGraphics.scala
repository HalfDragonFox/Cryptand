package com.hdf.cryptand.neoforge.truescreen.common

import com.hdf.cryptand.neoforge.truescreen.{TrueScreenGraphicsSink, TrueScreenLog}
import com.hdf.cryptand.neoforge.truescreen.network.{GraphicsFrame, GraphicsFrameCodec, ScreenFrameBlock, ScreenImageFrame}
import com.hdf.cryptand.neoforge.truescreen.util.PackedColor
import com.hdf.cryptand.soc.board.{FrameRateLimiter, ScreenImageComposer, TrueColorScreen}

import java.util.concurrent.atomic.AtomicLong

/**
 * 真彩屏 GRAPHICS（像素模式）的**服务端侧通道**（2026-09-27 任务 D）。
 *
 * <p>它做三件事：</p>
 * <ol>
 *   <li>持有一块与服务端屏幕同构的 {@link TrueColorScreen} 设备（像素模式的显存本体，
 *       <b>与 guest 看到的是同一份</b>；生产者直接把像素写进它）；</li>
 *   <li>实现生产者接口 {@link com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsSink}
 *       —— 生产者（OC 组件回调 / 机器线程）只写设备 + 调 markFrameChanged()（一个 volatile 计数）；</li>
 *   <li>给**主线程 tick** 提供 {@link #pendingFrame()}：有新帧号或显示模式变了就返回要发的那一帧，
 *       由 blockentity.Screen 的 tick 交给报文层（绝不在机器线程发报文）。</li>
 * </ol>
 *
 * <p>几何/色深/锁/调色板全部来自设备自己（没有第二份数值）；报文携带它们，客户端据此重建镜像设备。</p>
 */
final class TrueScreenGraphics(host: li.cil.oc.api.network.EnvironmentHost) extends TrueScreenGraphicsSink {

  /** 像素模式的设备（未 configure 前为 null ⇒ 生产者必须自己判空，不做隐式兜底）。 */
  @volatile private var vramDevice: TrueColorScreen = _

  /** 就绪位：生产者每写完一帧 +1（volatile 语义）。 */
  private val writtenSeq = new AtomicLong(0L)

  /** 已经发出去的帧号（只有主线程 tick 会写）。 */
  private var pushedSeq = 0L

  // ==================== 整屏校验缓存（用户需求 2026-09-29）====================
  //
  // 屏内容一变（markFrameChanged）⇒ 失效；下一次要发帧时算一遍并缓存，**所有观看者复用同一个值**，
  // 不按观者重复计算。算式只有一处：common 的 ScreenFrameChecksum（FNV-1a 风格，整屏）。

  @volatile private var checksumCache: Int = 0
  @volatile private var checksumValid: Boolean = false

  /** 整屏校验值（懒算 + 缓存）。未 configure 时设备为空 ⇒ 返回 0（此时也没有帧可发）。 */
  override def frameChecksum(): Int = {
    if (!checksumValid) {
      val d = vramDevice
      checksumCache = if (d == null) 0 else com.hdf.cryptand.soc.board.ScreenFrameChecksum.of(d)
      checksumValid = true
    }
    checksumCache
  }

  /** 上一次发出去的显示模式（-1 = 还没发过）。模式切换（TEXT<->GRAPHICS）也必须推一次。 */
  private var pushedMode = -1

  // ==================== 生产者接口（可从机器线程调用）====================

  override def device(): TrueColorScreen = vramDevice

  /**
   * 派生的输出面落地（任务 G）。
   *
   * <p>⚠ 这里**不是**一个"切模式开关"：调用方（GPU/屏链路）拿
   * {@code com.hdf.cryptand.soc.board.ScreenOutputFace.derive(谁在画, 屏能显示什么)} 的结果传进来，
   * 规则只有那一份。设备还没有（没 configure）时什么都不做 —— 不隐式建设备。</p>
   */
  /**
   * 本屏声明的像素色深（bpp）：1 / 8 / 16 / 24 —— 由屏实体按 tier 声明
   * （`blockentity.Screen.declaredBpp` ⇒ `TrueScreenSettings.screenBppByTier`）。
   *
   * 宿主不是屏实体时**明确失败**：sink 是按屏地址登记的（`TrueScreenGraphicsApi`），
   * 出现别的宿主说明登记/绑定链路坏了，这里不静默降级。
   */
  override def bpp(): Int = host match {
    case be: com.hdf.cryptand.neoforge.truescreen.common.blockentity.Screen => be.declaredBpp
    case other => throw new IllegalStateException(
      "[TrueScreen] 像素通道的宿主不是屏实体：" + (if (other == null) "null" else other.getClass.getName))
  }

  override def applyDerivedFace(face: TrueColorScreen.Mode): Unit = {
    val d = vramDevice
    if (d != null && face != null && d.mode() != face) {
      d.setMode(face)
      TrueScreenLog.log.info("[TrueScreen] 输出面派生落地：mode={}（谁在画 × 屏能显示什么，规则见 ScreenOutputFace）",
        face)
    }
    ()
  }

  override def configure(width: Int, height: Int, bpp: Int): Unit = {
    val w = math.max(1, width)
    val h = math.max(1, height)
    val rebuild = vramDevice == null || vramDevice.width() != w || vramDevice.height() != h || vramDevice.depth().bits() != bpp
    if (rebuild) {
      vramDevice = TrueColorScreen.of(w, h, bpp)
      TrueScreenLog.log.info("[TrueScreen] 像素设备建立：{}x{} @ {}bpp（host={}）",
        Int.box(w), Int.box(h), Int.box(bpp), host)
    }
    // ⚠ 这里**不再**强制切 GRAPHICS（任务 G）：设备的 TEXT/GRAPHICS 是派生的内部状态，
    //   由 GPU/屏链路在"图形面写入"那一刻用 ScreenOutputFace.derive 的结论落地
    //   （见 applyDerivedFace）。用户定案："屏设备的 TEXT/GRAPHICS 是内部派生状态，不是 guest 开关。"
    ()
  }

  override def lock(): Unit = if (vramDevice != null) vramDevice.lock()

  override def unlock(): Unit = if (vramDevice != null) vramDevice.unlock()

  override def setPaletteColor(index: Int, color: Int): Unit =
    if (vramDevice != null) vramDevice.setPaletteColor(index, color)

  override def markFrameChanged(): Unit = {
    writtenSeq.incrementAndGet()
    // 屏内容变了 ⇒ 整屏校验失效（下次要发帧时重算一次，然后给所有观看者复用）
    checksumValid = false
    ()
  }

  /** 方块坐标（宿主就是那块屏的方块实体；不是方块实体时返回 null —— 按坐标的工具据此判空）。 */
  override def blockPos(): net.minecraft.core.BlockPos = host match {
    case be: net.minecraft.world.level.block.entity.BlockEntity => be.getBlockPos
    case _ => null
  }

  // ==================== 主线程 tick 侧 ====================

  /** 有没有设备（诊断用）。 */
  def hasDevice: Boolean = vramDevice != null

  /**
   * **宿主渲染一拍**（由 origin 屏的**服务端 tick** 调用一次）—— 帧号/跳过数的**唯一推进点**。
   *
   * <p>与旧自研屏同口径（已删除的 {@code TrueScreenBlockEntity}（任务 F-2）里每 tick 调一次
 * {@code device.renderTick()}）：
   * 图形模式下设备被锁 ⇒ 只累加 {@code skipped} 并返回 false（guest 从 status 里就能看出锁生效）；
   * 否则 {@code frames++}。还没有设备（未进像素模式）时什么都不做、返回 true。</p>
   *
   * <p>⚠ 它不是"发报文的触发点"：报文仍然只由生产者的就绪位（{@link #markFrameChanged}）驱动 ——
   * 帧号只是**计数/诊断**，随下一帧报文一起同步给客户端镜像（{@code markSynced}）。</p>
   */
  def renderTick(): Boolean = {
    val d = vramDevice
    if (d == null) {
      true
    } else {
      d.renderTick()
    }
  }

  /**
   * 主线程 tick 调用：有新帧（或显示模式变了）就返回要发的那一帧，否则 None。
   *
   * <p>调色板**每帧都带**（调色板色深时）：256 个 int = 1KB，zlib 之后可忽略；
   * 换来的是"客户端刚重建设备/刚重连"时不需要任何额外状态同步 —— 少一条状态机就少一类 bug。</p>
   */
  def pendingFrame(): Option[GraphicsFrame] = {
    val d = vramDevice
    if (d == null) return None
    val mode = if (d.mode() == TrueColorScreen.Mode.GRAPHICS) GraphicsFrameCodec.ModeGraphics else GraphicsFrameCodec.ModeText
    val seq = writtenSeq.get()
    if (seq == pushedSeq && mode == pushedMode) return None

    val palette = if (d.depth().palette()) {
      val n = d.paletteSize()
      val p = new Array[Int](n)
      var i = 0
      while (i < n) { p(i) = d.paletteColor(i); i += 1 }
      p
    } else Array.emptyIntArray

    // ⚠ 整屏校验在**拷贝之前**取：它与下面这份帧快照必须是同一时刻的内容
    //   （脏了才算、算完缓存 ⇒ 同一份内容发给多个观看者时不重复计算）。
    val checksum = frameChecksum()

    // 发送前一次性快照：帧字节 = 设备 VRAM 的一份拷贝（报文在别的线程上被序列化，不能共享缓冲）。
    val frameBytes = d.frameBytes()
    val frame = new Array[Byte](frameBytes)
    System.arraycopy(d.vram(), 0, frame, 0, frameBytes)

    pushedSeq = seq
    pushedMode = mode
    Some(GraphicsFrame(mode, d.width(), d.height(), d.depth().bits(), d.locked(), d.frames(), d.skipped(),
      checksum, palette, frame))
  }

  // ==================== 图像帧同步（2026-09-28）====================
  //
  // 用户定案：客户端**主动请求**（先全量、再增量），服务器按每台机器的上限帧率回答，内容是
  // 服务器**合成好的 ARGB 图像**（TEXT 也由服务器合成）—— 客户端不再理解字符面的色深/调色板
  // 与颜色打包，白屏那类格式不一致从根上消失。采样步长由服务器按玩家距离自己算
  // （ScreenSamplingPolicy），客户端一个参数都不带。
  //
  // 全部在主线程（报文处理器与 tick 都是），没有跨线程共享。

  /** 合成器（惰性建立；设备几何变了就重建）。 */
  private var composer: ScreenImageComposer = _

  /** 每台机器的回帧上限（默认 60fps）；被限帧的请求直接丢弃 —— 客户端下一次请求会再来。 */
  private val frameLimiter = new FrameRateLimiter(FrameRateLimiter.DEFAULT_FPS)

  /** 单调递增的帧号（诊断/乱序检测用）。 */
  private var imageFrameId = 0L

  /** 请求（报文处理器写、tick 取）：同一 tick 内的多次请求合并，full 只升不降。 */
  private var requestPending = false
  private var requestFull = false
  private var requestStepW = 1
  private var requestStepH = 1
  private var requestPlayer: java.util.UUID = _

  /** 合成器当前喂的是"字符面"还是"像素设备"（切换时必须重建，几何口径不同）。 */
  private var composerFromCells = false

  /**
   * 屏幕像素几何：有**像素模式**设备就用它，否则由**字符视口**推出
   * （cols*CELL_W x rows*CELL_H）——纯文本屏、重启后机器停机时没有像素设备，
   * 但字符面照样要能画（2026-09-28 真机踩到：没有设备 ⇒ 采样几何 0 ⇒ 请求被丢 ⇒ 白屏）。
   */
  def pixelSize(): (Int, Int) = {
    val d = vramDevice
    if (d != null && d.mode() == TrueColorScreen.Mode.GRAPHICS) {
      (d.width(), d.height())
    } else {
      textGeometry() match {
        case Some((px, py, _, _)) => (px, py)
        case None => if (d == null) (0, 0) else (d.width(), d.height())
      }
    }
  }

  /** 字符面几何：(像素宽, 像素高, 列, 行)；没有字符缓冲 ⇒ None。 */
  private def textGeometry(): Option[(Int, Int, Int, Int)] = textBuffer().map { buf =>
    val cols = math.max(1, buf.getViewportWidth)
    val rows = math.max(1, buf.getViewportHeight)
    (cols * TrueColorScreen.CELL_W, rows * TrueColorScreen.CELL_H, cols, rows)
  }

  /** 服务端组件的字符缓冲（权威内容来源；不是方块实体或无缓冲 ⇒ None）。 */
  private def textBuffer(): Option[component.TextBuffer] = host match {
    case screen: blockentity.Screen =>
      screen.ensureServerBufferLoaded()
      screen.buffer match {
        case b: component.TextBuffer => Some(b)
        case _ => None
      }
    case _ => None
  }

  /**
   * 字符源：用**组件自己的格式**把打包颜色解成 RGB。
   *
   * 这正是"白屏"的结构性修复点：以前是客户端拿服务端发的颜色去猜格式（宽格式当 1bit 解 ⇒ 全白），
   * 现在由服务端在自己这一侧解 —— 它写进去时用什么格式，解出来就是什么颜色。
   */
  private def cellReader(buf: component.TextBuffer): ScreenImageComposer.CellReader =
    new ScreenImageComposer.CellReader {
      override def charAt(col: Int, row: Int): Char = {
        val r = row - 1
        val c = col - 1
        val lines = buf.data.buffer
        if (r < 0 || r >= lines.length || lines(r) == null || c < 0 || c >= lines(r).length) ' '
        else lines(r)(c).toChar
      }

      override def foregroundAt(col: Int, row: Int): Int = unpack(col, row, fg = true)

      override def backgroundAt(col: Int, row: Int): Int = unpack(col, row, fg = false)

      private def unpack(col: Int, row: Int, fg: Boolean): Int = {
        val r = row - 1
        val c = col - 1
        val lines = buf.data.color
        if (r < 0 || r >= lines.length || lines(r) == null || c < 0 || c >= lines(r).length) {
          0
        } else {
          val packed = lines(r)(c)
          val v = if (fg) PackedColor.unpackForeground(packed, buf.data.format)
          else PackedColor.unpackBackground(packed, buf.data.format)
          v & 0xFFFFFF
        }
      }
    }

  def limiter(): FrameRateLimiter = frameLimiter

  /** 客户端请求一帧（主线程；报文处理器调用）。 */
  def requestImageFrame(full: Boolean, stepW: Int, stepH: Int, player: java.util.UUID): Unit = {
    requestPending = true
    if (full) {
      requestFull = true
    }
    requestStepW = math.max(1, stepW)
    requestStepH = math.max(1, stepH)
    requestPlayer = player
  }

  /** 取走这一 tick 的请求（没有就 None）。 */
  def takeImageRequest(): Option[(Boolean, Int, Int, java.util.UUID)] = {
    if (!requestPending) {
      None
    } else {
      val r = (requestFull, requestStepW, requestStepH, requestPlayer)
      requestPending = false
      requestFull = false
      Some(r)
    }
  }

  /**
   * 合成一帧要发的图像（主线程）。
   *
   * @return 有内容且未被限帧 ⇒ Some（**address 由发送方填**）；没设备/没变化/被限帧 ⇒ None
   */
  def buildImageFrame(full: Boolean, stepW: Int, stepH: Int, nowNanos: Long): Option[ScreenImageFrame] = {
    val d = vramDevice
    // 像素模式：内容权威 = 设备 VRAM。
    if (d != null && d.mode() == TrueColorScreen.Mode.GRAPHICS) {
      if (composer == null || composerFromCells || !composer.matches(d)) {
        composer = new ScreenImageComposer(d.width(), d.height())
        composerFromCells = false
      }
      composer.setSampling(stepW, stepH)          // 步长变了 ⇒ 全脏
      val tCompose = System.nanoTime()            // 指标：合成（读设备 VRAM → 图像缓冲）与打包分开算
      composer.compose(d)
      com.hdf.cryptand.soc.board.ScreenFrameMetrics.SERVER.recordCompose(System.nanoTime() - tCompose)
      return emitFrame(full, d.width(), d.height(), nowNanos)
    }
    // TEXT（含"没有像素设备"的一切情形）：内容权威 = 组件字符面 + 它自己的格式。
    textGeometry() match {
      case Some((px, py, cols, rows)) =>
        val buf = textBuffer().orNull
        if (buf == null) {
          None
        } else {
          if (composer == null || !composerFromCells || composer.width() != px || composer.height() != py) {
            composer = new ScreenImageComposer(px, py)
            composerFromCells = true
          }
          composer.setSampling(stepW, stepH)
          val tCompose = System.nanoTime()
          composer.composeText(cols, rows, cellReader(buf))
          com.hdf.cryptand.soc.board.ScreenFrameMetrics.SERVER.recordCompose(System.nanoTime() - tCompose)
          emitFrame(full, px, py, nowNanos)
        }
      case None => None
    }
  }

  /**
   * 把合成器当前内容打包成一帧报文（脏行合并成若干块）；没内容/被限帧 ⇒ None。
   */
  private def emitFrame(full: Boolean, width: Int, height: Int, nowNanos: Long): Option[ScreenImageFrame] = {
    val t0 = System.nanoTime()                 // 指标：一帧的合成+打包耗时（度量驱动优化的第一步）
    val sendFull = full || composer.fullDirty()
    if (!sendFull && composer.dirtyRects().isEmpty) {
      return None                              // 没变化：不回帧（客户端下次请求会再来）
    }
    if (!frameLimiter.tryAcquire(nowNanos)) {
      return None                              // 限帧：丢弃（客户端下次请求会再来）
    }

    val gridW = composer.gridWidth()
    val gridH = composer.gridHeight()
    // 矩形脏块（用户要求）：全量 = 整幅一个矩形；增量 = composer 合并好的 [row, rowCount, colFrom, colToExclusive)
    val rects = if (sendFull) Array(0, gridH, 0, gridW) else composer.dirtyRects()
    val image = composer.image()
    val blocks = new Array[ScreenFrameBlock](rects.length / 4)
    var ints = 0
    var i = 0
    while (i < rects.length) {
      val colCount = rects(i + 3) - rects(i + 2)
      blocks(i / 4) = ScreenFrameBlock(rects(i), rects(i + 1), rects(i + 2), colCount)
      ints += rects(i + 1) * colCount
      i += 4
    }
    val rows = new Array[Int](ints)
    var at = 0
    i = 0
    while (i < rects.length) {
      val row0 = rects(i)
      val rowCount = rects(i + 1)
      val col0 = rects(i + 2)
      val colCount = rects(i + 3) - col0
      var r = 0
      while (r < rowCount) {                   // 紧凑打包：每行只拷列区间，不带整行
        System.arraycopy(image, (row0 + r) * gridW + col0, rows, at, colCount)
        at += colCount
        r += 1
      }
      i += 4
    }
    imageFrameId += 1
    composer.markClean()
    // 指标：块数 = 矩形块个数；像素 = 本帧实际携带（紧凑打包后）；字节按 ARGB int 计（未压缩参考值）
    com.hdf.cryptand.soc.board.ScreenFrameMetrics.SERVER.recordFrame(
      sendFull, blocks.length, ints.toLong, ints.toLong * 4L, System.nanoTime() - t0)
    // ⚠ 打印不在这里：这一刻帧还没编码发送，"上线字节"必然是 0（会误导成"压缩没生效"）。
    //   打点放在 ServerPacketSender.sendScreenFrame 里 —— 编码器已记完上线字节再打。
    //   （必须走 TrueScreenLog：System.out 不进 latest.log，无人化验证看不到。）
    Some(ScreenImageFrame("", imageFrameId, sendFull, width, height,
      composer.stepW(), composer.stepH(), blocks, rows))
  }
}
