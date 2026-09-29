/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/PacketHandler.scala  (屏幕部分)
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.network

import com.hdf.cryptand.neoforge.truescreen.TrueScreenLog
import com.hdf.cryptand.neoforge.truescreen.common.{blockentity, component}
import com.hdf.cryptand.neoforge.truescreen.common.component.ClientGpuTextBufferHandler
import com.hdf.cryptand.neoforge.truescreen.common.datacomponents.CompoundStorage
import com.hdf.cryptand.neoforge.truescreen.util.ExtendedLevel._
import li.cil.oc.api
import net.minecraft.core.Direction
import net.minecraft.nbt.NbtOps
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.neoforged.api.distmarker.{Dist, OnlyIn}

import java.io.{EOFException, InputStream}

/**
 * 客户端侧的屏幕报文处理器（上游 li.cil.oc.client.PacketHandler 的屏幕子集）。
 *
 * 处理 5 类：快照（TextBufferInit）、增量批（TextBufferMulti，含 15 条子命令）、
 * 亮不亮（TextBufferPowerChange）、触摸反转（ScreenTouchMode）、
 * 颜色 / 朝向 / 红石（ColorChange / RotatableState / RedstoneState）。
 *
 * <h3>★ 客户端内容的来路（本任务的核心）</h3>
 * 服务端把**整块屏的字符面 + 颜色面**塞进 [CompoundStorage]（就是 BE 存档用的那个
 * \`cryptand:text_buffer\` 组件容器）随 \`TextBufferInit\` 发过来，这里原样交给
 * \`buffer.data.loadData(...)\` —— 与 A 在服务端读档走的是**同一个** loadData。
 * 之后每次内容变化走 TextBufferMulti 增量（copy / fill / set / rawSet* / bitblt …），
 * 由 \`p.readPacketType()\` 循环分发到对应的 \`buffer.xxx(...)\`。
 *
 * ⚠ 收到内容后必须让 \`buffer.proxy.setChanged()\`（置 dirty）：任务 B 的
 * \`TextBuffer.ClientProxy.feedVram\` 只认这个 dirty 才会把字符面灌进 VRAM；
 * 而 \`markInitialized()\` 关掉客户端的"每 100 tick 重求一次快照"的兜底轮询。
 */
@OnlyIn(Dist.CLIENT)
object ClientPacketHandler extends PacketHandler {

  /** 诊断（2026-09-27）：地址不在客户端索引而被丢弃的图形帧数（真机验证用，不是功能路径）。 */
  private var gfxDrops = 0L

  override protected def world(player: Player, dimension: ResourceLocation): Option[Level] = {
    val level = player.level
    if (level != null && level.dimension.location == dimension) Some(level) else None
  }

  override protected def createParser(stream: InputStream, player: Player): PacketParser = parser(stream, player)

  override def dispatch(p: PacketParser): Unit = {
    p.packetType match {
      case PacketType.ColorChange => onColorChange(p)
      case PacketType.RedstoneState => onRedstoneState(p)
      case PacketType.RotatableState => onRotatableState(p)
      case PacketType.ScreenFrame => onScreenFrame(p)
      case PacketType.ScreenGraphics => onScreenGraphics(p)
      case PacketType.ScreenTouchMode => onScreenTouchMode(p)
      case PacketType.TextBufferInit => onTextBufferInit(p)
      case PacketType.TextBufferMulti => onTextBufferMulti(p)
      case PacketType.TextBufferPowerChange => onTextBufferPowerChange(p)
      case _ => // 未知/不属于屏幕的类型：忽略（同上游）。
    }
  }

  // ==================== GRAPHICS（像素模式，任务 D）====================

  /**
   * 一整帧像素内容：几何 / 调色板 / 锁状态 / 帧字节 / 帧号，全部来自报文（服务端设备是权威）。
   *
   * ⚠ 未知地址（屏已卸载）时**整包丢弃**：不解析、不缓存 —— 与 TextBufferInit 同款。
   */
  def onScreenGraphics(p: PacketParser): Unit = {
    val address = p.readUTF()
    lookup(p, address) match {
      case Some(buffer) =>
        val f = GraphicsFrameCodec.read(p)
        buffer.applyGraphicsFrame(f.mode, f.width, f.height, f.bpp, f.locked, f.frames, f.skipped, f.checksum, f.palette, f.frame)
      case _ =>
        // 未知地址：帧体不解析。诊断（2026-09-27）：这条日志出现 = 客户端索引里没有这块屏
        // （BE 同步没到 / 客户端还没注册），报文本身是到了的；不出现 = 连报文都没到。
        gfxDrops += 1
        if (gfxDrops <= 3L || (gfxDrops % 100L) == 0L) {
          TrueScreenLog.log.warn("[TrueScreen/CLIENT] 图形帧被丢弃：地址 {} 不在客户端索引（累计 {} 次）",
            address, java.lang.Long.valueOf(gfxDrops))
        }
    }
  }

  /**
   * 图像帧（拉取模型的回答，2026-09-28）：服务器**合成好的 ARGB**（TEXT/GRAPHICS 都走它）。
   *
   * 全量 = 一个覆盖整幅的块；增量 = 若干脏行块。客户端只把它贴进贴图 —— 不再解字符面的
   * 色深/调色板/颜色打包（那类不一致正是白屏的根因）。
   *
   * ⚠ 未知地址（屏已卸载）时整包丢弃（同 onScreenGraphics）。
   */
  def onScreenFrame(p: PacketParser): Unit = {
    // ⚠ 地址在**帧体内部**（ScreenFrameCodec 的第一个字段），这里**不能**再从外层流读一次：
    //   多读一次会让后面所有字段错位（真机症状：rowCount=-1 / readUTF malformed）。
    val frame = ScreenFrameCodec.read(p)
    lookup(p, frame.address) match {
      case Some(buffer) => buffer.applyImageFrame(frame)
      case _ => // 未知地址（屏已卸载）：丢弃
    }
  }

  // ==================== 内容 ====================

  /**
   * 完整快照（客户端注册索引时索要，或同步兜底轮询触发）。
   *
   * 读取顺序必须与 [ServerPacketSender.sendTextBufferInit] 的写入顺序一致：
   * address / NBT / maxWidth / maxHeight / maxDepth / viewportWidth / viewportHeight。
   */
  def onTextBufferInit(p: PacketParser): Unit = {
    val address = p.readUTF()
    lookup(p, address) match {
      case Some(buffer) =>
        val nbt = CompoundStorage.CODEC.parse(NbtOps.INSTANCE, p.readNBT()).getOrThrow()
        buffer.setMaximumResolution(p.readInt(), p.readInt())
        val depthValues = api.internal.TextBuffer.ColorDepth.values
        val depth = p.readInt() min (depthValues.length - 1) max 0
        buffer.setMaximumColorDepth(depthValues(depth))
        buffer.data.loadData(nbt)
        buffer.setViewport(p.readInt(), p.readInt())
        buffer.proxy.setChanged()
        buffer.markInitialized()
        if (diagInitPackets < 8) {
          TrueScreenLog.log.info("[TrueScreen/DIAG] 客户端收到快照 address={} initialized={} render={}x{}",
            address, java.lang.Boolean.valueOf(buffer.isInitialized),
            Integer.valueOf(buffer.renderWidth), Integer.valueOf(buffer.renderHeight))
          diagInitPackets += 1
        }
      case _ => // 快照到了但本地没有这块屏（已卸载）：按上游忽略。
    }
  }

  /**
   * 增量批：一个压缩包里连续 N 条子命令，靠 EOF 结束（上游同款）。
   *
   * ⚠ "还没收到过快照"的窗口期要整包丢掉：此刻本地 buffer 还是构造时的 T1 形状/1bpp，
   * 直接灌增量会因为"色深不够"画错甚至抛异常，而那份快照里本来就已经包含这些增量。
   */
  def onTextBufferMulti(p: PacketParser): Unit = {
    if (p.player != null) {
      val address = p.readUTF()
      lookup(p, address) match {
        case Some(buffer) if !buffer.isInitialized => // 等快照，丢弃（上游同款）。
          if (diagDropped < 8) {
            TrueScreenLog.log.info("[TrueScreen/DIAG] 丢弃增量包（快照还没到）address={}", address)
            diagDropped += 1
          }
        case Some(buffer) =>
          try {
            var reading = true
            while (reading) {
              p.readPacketType() match {
                case PacketType.TextBufferMultiColorChange => onMultiColorChange(p, buffer)
                case PacketType.TextBufferMultiCopy => onMultiCopy(p, buffer)
                case PacketType.TextBufferMultiDepthChange => onMultiDepthChange(p, buffer)
                case PacketType.TextBufferMultiFill => onMultiFill(p, buffer)
                case PacketType.TextBufferMultiPaletteChange => onMultiPaletteChange(p, buffer)
                case PacketType.TextBufferMultiResolutionChange => onMultiResolutionChange(p, buffer)
                case PacketType.TextBufferMultiViewportResolutionChange => onMultiViewportResolutionChange(p, buffer)
                case PacketType.TextBufferMultiMaxResolutionChange => onMultiMaxResolutionChange(p, buffer)
                case PacketType.TextBufferMultiSet => onMultiSet(p, buffer)
                case PacketType.TextBufferRamInit => onTextBufferRamInit(p, buffer)
                case PacketType.TextBufferBitBlt => onTextBufferBitBlt(p, buffer)
                case PacketType.TextBufferRamDestroy => onTextBufferRamDestroy(p, buffer)
                case PacketType.TextBufferMultiRawSetText => onMultiRawSetText(p, buffer)
                case PacketType.TextBufferMultiRawSetBackground => onMultiRawSetBackground(p, buffer)
                case PacketType.TextBufferMultiRawSetForeground => onMultiRawSetForeground(p, buffer)
                case _ => reading = false // 不认识的子命令：停止解析这一包（上游是忽略后继续，这里更保守）。
              }
            }
          } catch {
            case _: EOFException => // 没有更多命令了：正常收尾。
          } finally {
            // ★ 报文写入 data **不经过** owner 的 onBuffer* 钩子 ⇒ dirty 不会自己亮，
            //   而 feedVram→feedText 的唯一重传信号就是 dirty（否则只喂一次，贴图永不更新；
            //   2026-09-28 真机日志：feedText 跳过（changed=false … textFed=true））。
            buffer.proxy.setChanged()
          }
        case _ => // 未知地址：忽略。
      }
    }
  }

  def onTextBufferPowerChange(p: PacketParser): Unit = {
    val address = p.readUTF()
    val hasPower = p.readBoolean()
    lookup(p, address) match {
      case Some(buffer) => buffer.setRenderingEnabled(hasPower)
      case _ => // 未知地址：忽略。
    }
  }

  private var diagColorPackets = 0
  private var diagInitPackets = 0
  private var diagDropped = 0

  def onMultiColorChange(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val foreground = p.readInt()
    val foregroundIsPalette = p.readBoolean()
    buffer.setForegroundColor(foreground, foregroundIsPalette)
    val background = p.readInt()
    val backgroundIsPalette = p.readBoolean()
    if (diagColorPackets < 5) {
      TrueScreenLog.log.info("[TrueScreen/DIAG] 客户端收到颜色报文 fg=0x{} pal={} bg=0x{} pal={}（随后写入缓冲）",
        Integer.valueOf(foreground), java.lang.Boolean.valueOf(foregroundIsPalette),
        Integer.valueOf(background), java.lang.Boolean.valueOf(backgroundIsPalette))
      diagColorPackets += 1
    }
    buffer.setBackgroundColor(background, backgroundIsPalette)
  }

  def onMultiCopy(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val col = p.readInt()
    val row = p.readInt()
    val w = p.readInt()
    val h = p.readInt()
    val tx = p.readInt()
    val ty = p.readInt()
    buffer.copy(col, row, w, h, tx, ty)
  }

  def onMultiDepthChange(p: PacketParser, buffer: component.TextBuffer): Unit = {
    buffer.setColorDepth(api.internal.TextBuffer.ColorDepth.values.apply(p.readInt()))
  }

  def onMultiFill(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val col = p.readInt()
    val row = p.readInt()
    val w = p.readInt()
    val h = p.readInt()
    val c = p.readMedium()
    buffer.fill(col, row, w, h, c)
  }

  def onMultiPaletteChange(p: PacketParser, buffer: component.TextBuffer): Unit = {
    buffer.setPaletteColor(p.readInt(), p.readInt())
  }

  def onMultiResolutionChange(p: PacketParser, buffer: component.TextBuffer): Unit = {
    buffer.setResolution(p.readInt(), p.readInt())
  }

  def onMultiViewportResolutionChange(p: PacketParser, buffer: component.TextBuffer): Unit = {
    buffer.setViewport(p.readInt(), p.readInt())
  }

  def onMultiMaxResolutionChange(p: PacketParser, buffer: component.TextBuffer): Unit = {
    buffer.setMaximumResolution(p.readInt(), p.readInt())
  }

  def onMultiSet(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val col = p.readInt()
    val row = p.readInt()
    val s = p.readUTF()
    val vertical = p.readBoolean()
    buffer.set(col, row, s, vertical)
  }

  def onTextBufferRamInit(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val owner = p.readUTF()
    val id = p.readInt()
    val holder = new CompoundStorage(net.minecraft.core.component.DataComponentMap.CODEC
      .parse(NbtOps.INSTANCE, p.readNBT()).getOrThrow())
    buffer.loadBuffer(owner, id, holder)
    ()
  }

  def onTextBufferBitBlt(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val col = p.readInt()
    val row = p.readInt()
    val w = p.readInt()
    val h = p.readInt()
    val owner = p.readUTF()
    val id = p.readInt()
    val fromCol = p.readInt()
    val fromRow = p.readInt()
    ClientGpuTextBufferHandler.bitblt(buffer, col, row, w, h, owner, id, fromCol, fromRow)
  }

  def onTextBufferRamDestroy(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val owner = p.readUTF()
    val id = p.readInt()
    buffer.removeBuffer(owner, id)
    ()
  }

  def onMultiRawSetText(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val col = p.readInt()
    val row = p.readInt()
    val rows = p.readShort()
    val text = new Array[Array[Int]](rows)
    for (y <- 0 until rows) {
      val cols = p.readShort()
      val line = new Array[Int](cols)
      for (x <- 0 until cols) {
        line(x) = p.readMedium()
      }
      text(y) = line
    }
    buffer.rawSetText(col, row, text)
  }

  def onMultiRawSetBackground(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val col = p.readInt()
    val row = p.readInt()
    val rows = p.readShort()
    val color = new Array[Array[Int]](rows)
    for (y <- 0 until rows) {
      val cols = p.readShort()
      val line = new Array[Int](cols)
      for (x <- 0 until cols) {
        line(x) = p.readInt()
      }
      color(y) = line
    }
    buffer.rawSetBackground(col, row, color)
  }

  def onMultiRawSetForeground(p: PacketParser, buffer: component.TextBuffer): Unit = {
    val col = p.readInt()
    val row = p.readInt()
    val rows = p.readShort()
    val color = new Array[Array[Int]](rows)
    for (y <- 0 until rows) {
      val cols = p.readShort()
      val line = new Array[Int](cols)
      for (x <- 0 until cols) {
        line(x) = p.readInt()
      }
      color(y) = line
    }
    buffer.rawSetForeground(col, row, color)
  }

  // ==================== 方块实体侧状态 ====================

  def onColorChange(p: PacketParser): Unit = {
    p.readBlockEntity[blockentity.traits.Colored]() match {
      case Some(t) =>
        t.setColor(p.readInt())
        t.getLevel.notifyBlockUpdate(t.getBlockPos)
      case _ => // 未知方块实体：忽略。
    }
  }

  def onRotatableState(p: PacketParser): Unit = {
    p.readBlockEntity[blockentity.traits.Rotatable]() match {
      case Some(t) =>
        t.pitch = p.readDirection().get
        t.yaw = p.readDirection().get
      case _ => // 未知方块实体：忽略。
    }
  }

  def onRedstoneState(p: PacketParser): Unit = {
    p.readBlockEntity[blockentity.traits.RedstoneAware]() match {
      case Some(t) =>
        t.setOutputEnabled(p.readBoolean())
        for (d <- Direction.values) {
          t.setOutput(d, p.readByte())
        }
      case _ => // 未知方块实体：忽略。
    }
  }

  def onScreenTouchMode(p: PacketParser): Unit = {
    p.readBlockEntity[blockentity.Screen]() match {
      case Some(t) =>
        t.invertTouchMode = p.readBoolean()
        ()
      case _ => // 未知方块实体：忽略。
    }
  }

  // ==================== 查找 ====================

  /**
   * 按节点地址在这块屏所在的维度里查组件。
   *
   * 用的是 **OC 自己的客户端组件索引**（\`li.cil.oc.client.ComponentTracker\`）：
   * 我方 \`object TextBuffer.registerClientBuffer\` 就是往它里面注册（不另建索引类），
   * 所以这里查得到——这也是"只允许一套索引"的落点。
   */
  private def lookup(p: PacketParser, address: String): Option[component.TextBuffer] = {
    li.cil.oc.client.ComponentTracker.get(p.player.level, address) match {
      case Some(buffer: component.TextBuffer) => Some(buffer)
      // 未知地址 = 这块屏已经被卸载（区块不在/刚走）：整包安静丢掉（上游同款，不刷日志）。
      case _ => None
    }
  }
}
