/*
 * Cryptand —— 真彩屏 GRAPHICS 帧的字节编解码（2026-09-27 任务 D）
 *
 * 本文件只用 JDK 类型（DataInput/DataOutputStream），因此可以在没有任何 Minecraft/NeoForge 的
 * 裸 JVM 上跑自测（见 main）；服务端发送器与客户端处理器都复用它 —— 一份格式两个方向，不会漂。
 *
 * 帧格式（大端，字段顺序即协议；改顺序 = 改协议）：
 *   byte   mode            0 = TEXT，1 = GRAPHICS（客户端据此决定"还喂不喂字符面"）
 *   int    width           像素宽
 *   int    height          像素高
 *   int    bpp             1/2/4/8/16/24/32
 *   boolean locked         锁帧（被锁 ⇒ 客户端保留上一帧，不重复上传）
 *   long   frames          服务端累计帧号
 *   long   skipped         服务端因锁跳过的帧数
 *   int    paletteCount    调色板条目数（直色色深 = 0）
 *   int[]  palette         调色板（ARGB，长度 = paletteCount）
 *   int    frameBytes      帧字节数（必须 = stride x height，由客户端设备再校验一次）
 *   byte[] frame           帧字节（长度 = frameBytes）
 */
package com.hdf.cryptand.neoforge.truescreen.network

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

/** 一帧的头部 + 帧体（纯数据，无 MC 依赖）。 */
case class GraphicsFrame(
  mode: Int,
  width: Int,
  height: Int,
  bpp: Int,
  locked: Boolean,
  frames: Long,
  skipped: Long,
  /** 整屏校验（FNV-1a 风格，见 common 的 `ScreenFrameChecksum`）：客户端更新后本地再算一次必须与之相等。
   *  服务端由**屏**持有/缓存（刷新置脏、算一次给所有观看者复用），不在这里算。 */
  checksum: Int,
  palette: Array[Int],
  frame: Array[Byte]
)

object GraphicsFrameCodec {

  /** mode 取值（与 common 的 TrueColorScreen.Mode 一一对应，但那层不在这里 import）。 */
  final val ModeText = 0
  final val ModeGraphics = 1

  def write(out: DataOutputStream, f: GraphicsFrame): Unit = {
    out.writeByte(f.mode)
    out.writeInt(f.width)
    out.writeInt(f.height)
    out.writeInt(f.bpp)
    out.writeBoolean(f.locked)
    out.writeLong(f.frames)
    out.writeLong(f.skipped)
    out.writeInt(f.checksum)
    val palette = if (f.palette == null) Array.emptyIntArray else f.palette
    out.writeInt(palette.length)
    var i = 0
    while (i < palette.length) {
      out.writeInt(palette(i))
      i += 1
    }
    val frame = if (f.frame == null) Array.emptyByteArray else f.frame
    out.writeInt(frame.length)
    out.write(frame)
    out.flush()
  }

  /** 读一帧；帧字节数与声明不符 ⇒ 抛异常（不截断、不猜测）。 */
  def read(in: DataInputStream): GraphicsFrame = {
    val mode = in.readByte().toInt
    val width = in.readInt()
    val height = in.readInt()
    val bpp = in.readInt()
    val locked = in.readBoolean()
    val frames = in.readLong()
    val skipped = in.readLong()
    val checksum = in.readInt()
    val paletteCount = in.readInt()
    if (paletteCount < 0) throw new IllegalArgumentException("paletteCount < 0: " + paletteCount)
    val palette = new Array[Int](paletteCount)
    var i = 0
    while (i < paletteCount) {
      palette(i) = in.readInt()
      i += 1
    }
    val frameBytes = in.readInt()
    if (frameBytes < 0) throw new IllegalArgumentException("frameBytes < 0: " + frameBytes)
    val frame = new Array[Byte](frameBytes)
    in.readFully(frame)
    GraphicsFrame(mode, width, height, bpp, locked, frames, skipped, checksum, palette, frame)
  }

  // --------------------------------------------------------------------- //
  // 离线闸门（不依赖 Minecraft；java -cp <classes>;<scala-library> ...GraphicsFrameCodec）
  // --------------------------------------------------------------------- //

  /** 往返自测：写进去再读出来必须逐字段相等；覆盖 TEXT / 8bpp 调色板 / 16bpp 直色三种形态。 */
  def selfTest(): Option[String] = {
    val cases = Seq(
      GraphicsFrame(ModeText, 1520, 480, 8, locked = false, 0L, 0L, 0,
        Array.emptyIntArray, Array.emptyByteArray),
      GraphicsFrame(ModeGraphics, 190, 60, 8, locked = false, 7L, 2L, -1,
        Array.tabulate(256)(i => 0xFF000000 | i), Array.tabulate(190 * 60)(i => (i & 0xFF).toByte)),
      GraphicsFrame(ModeGraphics, 190, 60, 16, locked = true, 1234567890123L, 42L, 0x7FFFFFFF,
        Array.emptyIntArray, Array.tabulate(190 * 60 * 2)(i => (i % 251).toByte))
    )

    var idx = 0
    while (idx < cases.length) {
      val f = cases(idx)
      val back = decode(encode(f))
      if (back.mode != f.mode) return Some("case " + idx + " mode: " + back.mode + " != " + f.mode)
      if (back.width != f.width || back.height != f.height || back.bpp != f.bpp) return Some("case " + idx + " geometry mismatch")
      if (back.locked != f.locked) return Some("case " + idx + " locked mismatch")
      if (back.frames != f.frames || back.skipped != f.skipped) return Some("case " + idx + " frame counters mismatch")
      if (back.checksum != f.checksum) return Some("case " + idx + " checksum mismatch: " + back.checksum + " != " + f.checksum)
      if (!back.palette.sameElements(f.palette)) return Some("case " + idx + " palette mismatch")
      if (!back.frame.sameElements(f.frame)) return Some("case " + idx + " frame bytes mismatch")
      idx += 1
    }

    // 负例：帧字节被截断 ⇒ 必须明确抛错（不静默补齐）
    val truncated = encode(cases(1)).dropRight(8)
    try {
      decode(truncated)
      return Some("truncated frame was accepted (should have thrown)")
    } catch {
      case _: java.io.EOFException => // 期望
      case _: IllegalArgumentException => // 也接受（取决于截断位置）
      case t: Throwable => return Some("truncated frame threw unexpected " + t.getClass.getName)
    }
    None
  }

  private def encode(f: GraphicsFrame): Array[Byte] = {
    val bytes = new ByteArrayOutputStream()
    val out = new DataOutputStream(bytes)
    write(out, f)
    bytes.toByteArray
  }

  private def decode(bytes: Array[Byte]): GraphicsFrame = {
    val in = new DataInputStream(new ByteArrayInputStream(bytes))
    val f = read(in)
    if (in.available() != 0) throw new IllegalArgumentException("trailing bytes: " + in.available())
    f
  }

  def main(args: Array[String]): Unit = {
    selfTest() match {
      case None => println("[TrueScreen] GraphicsFrameCodec self-test: PASS (3 round-trips + 1 negative case)")
      case Some(reason) =>
        println("[TrueScreen] GraphicsFrameCodec self-test: FAIL - " + reason)
        System.exit(1)
    }
  }
}
