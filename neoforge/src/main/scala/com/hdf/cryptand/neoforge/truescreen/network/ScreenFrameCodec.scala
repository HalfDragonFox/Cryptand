/*
 * Cryptand 屏幕图像帧同步（2026-09-28）。
 *
 * 与 GraphicsFrameCodec 的分工：那个是**像素模式的 VRAM 原样搬运**（bpp/调色板都在报文里），
 * 这个是**服务器合成好的 ARGB 图像**——TEXT 模式也走它（服务器自己把字符面合成像素），
 * 于是客户端完全不需要理解字符面的色深/调色板/颜色打包。
 *
 * 形状：一帧 = 若干"行块"（块 = [firstRow, firstRow+rowCount)），行内是 gridW 个 ARGB。
 * 全量 = 一个覆盖整幅的块；增量 = 若干个脏行块（相邻脏行已在服务器侧合并）。
 * gridW/gridH 由 width/height + stepW/stepH 推出来
 * （ScreenSamplingPolicy.gridSize，common 里同一份公式，两端不会各算一套）。
 */
package com.hdf.cryptand.neoforge.truescreen.network

import com.hdf.cryptand.soc.board.ScreenSamplingPolicy

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInputStream, DataOutputStream}

/**
 * 一个**矩形**块：行 [firstRow, firstRow + rowCount)、列 [firstCol, firstCol + colCount)。
 *
 * <p>2026-09-29 用户要求"下发按矩形脏块"：原来只有行范围（列恒为整行），
 * 光标闪烁/局部刷新这类变化会把整行没变的像素一起发出去。</p>
 */
case class ScreenFrameBlock(firstRow: Int, rowCount: Int, firstCol: Int, colCount: Int)

/**
 * 一帧合成图像（纯数据，无 MC 依赖）。
 *
 * @param rows 所有块按顺序拼接的 ARGB 像素（第 i 块占 rows 的 gridW*rowCount 个 int）
 */
case class ScreenImageFrame(
  address: String,
  frameId: Long,
  full: Boolean,
  width: Int,
  height: Int,
  stepW: Int,
  stepH: Int,
  blocks: Array[ScreenFrameBlock],
  rows: Array[Int]
)

object ScreenFrameCodec {

  def gridWidth(width: Int, stepW: Int): Int = ScreenSamplingPolicy.gridSize(width, stepW)

  def gridHeight(height: Int, stepH: Int): Int = ScreenSamplingPolicy.gridSize(height, stepH)

  /**
   * 编码 rows：返回 (算法号, 压缩体或 null)。
   *
   * <p>用户定案（2026-09-29）：「压缩算法可以自选，配置上可以设置 auto 或者强制其他后端」。</p>
   * <p>注意：`zlib` 由 MC 通道（`CompressedPacketBuilder`）完成 ⇒ 帧体层**不重复压**
   * （双重压缩白烧 CPU）；`lz4` 未实现 ⇒ **明确抛错**，不静默换成别的算法。</p>
   */
  /**
   * 帧体编码成字节（**纯函数**：只读入参、不碰任何共享状态）。
   *
   * <p>抽出来的原因（2026-09-29）：帧体打包 + 压缩是几毫秒的纯 CPU 活（zlib 实测 2.9~14.3ms），
   * 不该占主线程。抽成纯函数后可以丢到后台线程编码，再回主线程发送。</p>
   *
   * @param algorithm 算法号，或 -1 表示 auto
   */
  def encodeBody(f: ScreenImageFrame, algorithm: Int): Array[Byte] = {
    val bodyStream = new ByteArrayOutputStream()
    val body = new DataOutputStream(bodyStream)
    body.writeUTF(if (f.address == null) "" else f.address)
    body.writeLong(f.frameId)
    body.writeBoolean(f.full)
    body.writeInt(f.width)
    body.writeInt(f.height)
    body.writeInt(f.stepW)
    body.writeInt(f.stepH)
    val blocks = if (f.blocks == null) Array.empty[ScreenFrameBlock] else f.blocks
    body.writeInt(blocks.length)
    var i = 0
    while (i < blocks.length) {
      body.writeInt(blocks(i).firstRow)
      body.writeInt(blocks(i).rowCount)
      body.writeInt(blocks(i).firstCol)
      body.writeInt(blocks(i).colCount)
      i += 1
    }
    val rows = if (f.rows == null) Array.emptyIntArray else f.rows
    // 帧体压缩（2026-09-29 用户定案：算法可自选、auto 自动挑、压不动回落 NONE）。
    // 算法号写在像素数**之前**，读端才知道后面是裸 int 数组还是压缩体。
    val encoded = encodeRows(rows, algorithm)
    body.writeByte(encoded._1)
    body.writeInt(rows.length)
    if (encoded._2 == null) {
      i = 0
      while (i < rows.length) {
        body.writeInt(rows(i))
        i += 1
      }
    } else {
      body.writeInt(encoded._2.length)
      body.write(encoded._2)
    }
    body.flush()
    bodyStream.toByteArray
  }

  /** MC 入口：算法取自配置。 */
  def encodeBody(f: ScreenImageFrame): Array[Byte] = encodeBody(f,
    com.hdf.cryptand.soc.board.FrameCompression.parseAlgorithm(
      com.hdf.cryptand.neoforge.opencomputers.config.ConfigOpenComputers.trueScreenFrameCompression()))

  private def encodeRows(rows: Array[Int], configured: Int): (Int, Array[Byte]) = {
    import com.hdf.cryptand.soc.board.FrameCompression
    if (configured == FrameCompression.ALGORITHM_NONE || configured == FrameCompression.ALGORITHM_ZLIB) {
      (FrameCompression.ALGORITHM_NONE, null)
    } else if (configured == FrameCompression.ALGORITHM_LZ4) {
      throw new UnsupportedOperationException(
        "帧体压缩配置为 lz4，但 lz4 尚未实现（trueScreenFrameCompression 可选 auto/none/rle/zlib）")
    } else if (configured == FrameCompression.ALGORITHM_RLE) {
      (FrameCompression.ALGORITHM_RLE, FrameCompression.encodeRle(rows, 0, rows.length))
    } else {
      // auto：只算一次 RLE，再按"值不值得"决定（< 原始一半 ⇒ 用 RLE；否则不压，交通道）
      val rle = FrameCompression.encodeRle(rows, 0, rows.length)
      if (FrameCompression.pickAuto(rows.length * 4, rle.length) == FrameCompression.ALGORITHM_RLE) {
        (FrameCompression.ALGORITHM_RLE, rle)
      } else {
        (FrameCompression.ALGORITHM_NONE, null)
      }
    }
  }

  /** 帧体上限（1 个 int 长度 + 帧体；防坏包让我们按垃圾长度分配内存）。 */
  final val MaxBodyBytes = 8 * 1024 * 1024

  /** MC 入口：算法取自配置（离线闸门请用带 algorithm 的重载 —— 那里没有配置域）。 */
  def write(out: DataOutputStream, f: ScreenImageFrame): Unit = {
    write(out, f, com.hdf.cryptand.soc.board.FrameCompression.parseAlgorithm(
      com.hdf.cryptand.neoforge.opencomputers.config.ConfigOpenComputers.trueScreenFrameCompression()))
  }

  /**
   * 主实现。
   *
   * @param algorithm 算法号，或 -1 表示 auto（`FrameCompression.parseAlgorithm("auto")` 的返回值）
   */
  def write(out: DataOutputStream, f: ScreenImageFrame, algorithm: Int): Unit = {
    // 先把帧体写成一个独立的字节数组，再以"长度 + 字节"送进容器：
    // 容器侧的缓冲/压缩流/解析器任何怪癖都影响不到帧体内部的字段对齐
    // （2026-09-28 真机踩到：直接往容器流里逐字段写，读回时字段错位 ⇒ rowCount=-1）。
    val bytes = encodeBody(f, algorithm)
    // 指标：**上线字节**（帧体总长，含压缩体）。与 emitFrame 记的"压缩前 ints*4"对比即知压缩收益。
    com.hdf.cryptand.soc.board.ScreenFrameMetrics.SERVER.recordWireBytes(bytes.length.toLong)
    out.writeInt(bytes.length)
    out.write(bytes)
    out.flush()
  }

  /** 读一帧；块数/像素数与声明不符 ⇒ 抛异常（不截断、不猜测）。 */
  def read(in: DataInputStream): ScreenImageFrame = {
    val bodyLength = in.readInt()
    if (bodyLength < 0 || bodyLength > MaxBodyBytes) {
      throw new IllegalArgumentException("帧体长度非法：" + bodyLength + "（上限 " + MaxBodyBytes + "）")
    }
    val bodyBytes = new Array[Byte](bodyLength)
    in.readFully(bodyBytes)
    readBody(new DataInputStream(new ByteArrayInputStream(bodyBytes)))
  }

  private def readBody(in: DataInputStream): ScreenImageFrame = {
    val address = in.readUTF()
    val frameId = in.readLong()
    val full = in.readBoolean()
    val width = in.readInt()
    val height = in.readInt()
    val stepW = in.readInt()
    val stepH = in.readInt()
    val blockCount = in.readInt()
    if (blockCount < 0) throw new IllegalArgumentException("blockCount < 0: " + blockCount)
    val blocks = new Array[ScreenFrameBlock](blockCount)
    var i = 0
    while (i < blockCount) {
      val first = in.readInt()
      val count = in.readInt()
      val colFrom = in.readInt()
      val colCount = in.readInt()
      if (count < 1) throw new IllegalArgumentException("行块的 rowCount 必须 >= 1：" + count)
      if (colCount < 1) throw new IllegalArgumentException("列块的 colCount 必须 >= 1：" + colCount)
      if (colFrom < 0) throw new IllegalArgumentException("列起点不能为负：" + colFrom)
      blocks(i) = ScreenFrameBlock(first, count, colFrom, colCount)
      i += 1
    }
    val algorithm = in.readByte().toInt
    val ints = in.readInt()
    if (ints < 0) throw new IllegalArgumentException("rows 长度 < 0: " + ints)
    val rows = if (algorithm == com.hdf.cryptand.soc.board.FrameCompression.ALGORITHM_RLE) {
      val compressedLength = in.readInt()
      if (compressedLength < 0 || compressedLength > MaxBodyBytes) {
        throw new IllegalArgumentException("压缩体长度非法：" + compressedLength)
      }
      val compressed = new Array[Byte](compressedLength)
      in.readFully(compressed)
      // 像素数严格校验：解出来不是 ints 个 ⇒ 报文坏了，明确抛错（不补零、不截断）
      com.hdf.cryptand.soc.board.FrameCompression.decodeRle(compressed, ints)
    } else if (algorithm == com.hdf.cryptand.soc.board.FrameCompression.ALGORITHM_NONE) {
      val plain = new Array[Int](ints)
      i = 0
      while (i < ints) {
        plain(i) = in.readInt()
        i += 1
      }
      plain
    } else {
      throw new IllegalArgumentException("未知帧体压缩算法号：" + algorithm
        + "（0=none, 1=rle；zlib 由通道完成，lz4 未实现）")
    }
    val gridW = gridWidth(width, stepW)
    var expected = 0L
    i = 0
    while (i < blockCount) {
      val b = blocks(i)
      if (b.firstRow < 0 || b.firstRow + b.rowCount > gridHeight(height, stepH)) {
        throw new IllegalArgumentException("行范围越界：" + b.firstRow + "+" + b.rowCount)
      }
      if (b.firstCol + b.colCount > gridW) {
        throw new IllegalArgumentException("列范围越界：" + b.firstCol + "+" + b.colCount + " > " + gridW)
      }
      expected += b.rowCount.toLong * b.colCount
      i += 1
    }
    if (expected != ints.toLong) {
      throw new IllegalArgumentException("像素数与块声明不符：期望 " + expected + " 个 int，给了 " + ints)
    }
    ScreenImageFrame(address, frameId, full, width, height, stepW, stepH, blocks, rows)
  }

  // --------------------------------------------------------------------- //
  // 离线闸门（不依赖 Minecraft）：
  //   java -cp <classes> ...ScreenFrameCodec
  // 只钉"往返一致 + 坏数据明确报错"，采样/合成的数学在 common 的闸门里。
  // --------------------------------------------------------------------- //

  def main(args: Array[String]): Unit = {
    var passed = 0
    var failed = 0
    def check(name: String, ok: Boolean): Unit = {
      if (ok) {
        passed += 1
        println("[PASS] " + name)
      } else {
        failed += 1
        println("[FAIL] " + name)
      }
    }

    // ⚠ 局部 def 不能重载（Scala 报 "defined twice"）⇒ 用默认参数而不是两个同名方法
    def roundTrip(f: ScreenImageFrame, algorithm: Int = -1): ScreenImageFrame = {
      val bytes = new ByteArrayOutputStream()
      write(new DataOutputStream(bytes), f, algorithm)
      read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray)))
    }

    def bodyBytes(f: ScreenImageFrame, algorithm: Int): Int = {
      val bytes = new ByteArrayOutputStream()
      write(new DataOutputStream(bytes), f, algorithm)
      bytes.size()
    }

    // 帧体压缩（2026-09-29 用户定案：算法可自选、auto 自动挑、压不动回落）——
    // 三种算法都必须无损往返；能压的数据必须真的变小。
    val solid: ScreenImageFrame = ScreenImageFrame("addr-c", 9L, full = true, 64, 32, 1, 1,
      Array(ScreenFrameBlock(0, 32, 0, 64)), Array.fill(64 * 32)(0xFF102030))
    check("压缩：auto 往返逐像素一致",
      java.util.Arrays.equals(roundTrip(solid, -1).rows, solid.rows))
    check("压缩：rle 往返逐像素一致",
      java.util.Arrays.equals(roundTrip(solid, com.hdf.cryptand.soc.board.FrameCompression.ALGORITHM_RLE).rows, solid.rows))
    check("压缩：none 往返逐像素一致",
      java.util.Arrays.equals(roundTrip(solid, com.hdf.cryptand.soc.board.FrameCompression.ALGORITHM_NONE).rows, solid.rows))
    val noneBytes = bodyBytes(solid, com.hdf.cryptand.soc.board.FrameCompression.ALGORITHM_NONE)
    val rleBytes = bodyBytes(solid, com.hdf.cryptand.soc.board.FrameCompression.ALGORITHM_RLE)
    check("压缩：同色帧 RLE 远小于裸 int（" + rleBytes + " < " + noneBytes + "/4）", rleBytes < noneBytes / 4)
    val autoBytes = bodyBytes(solid, -1)
    check("压缩：auto 对同色帧选了 RLE（" + autoBytes + " ≈ " + rleBytes + "）", autoBytes <= rleBytes + 8)
    // 随机像素：auto 必须回落 none（RLE 会膨胀到 125%）
    val noisy: ScreenImageFrame = ScreenImageFrame("addr-n", 10L, full = true, 64, 32, 1, 1,
      Array(ScreenFrameBlock(0, 32, 0, 64)),
      Array.tabulate(64 * 32)(i => 0xFF000000 | ((i * 2654435761L).toInt & 0xFFFFFF)))
    val noisyAuto = bodyBytes(noisy, -1)
    check("压缩：随机帧 auto 回落 none（" + noisyAuto + " ≈ " + bodyBytes(noisy, com.hdf.cryptand.soc.board.FrameCompression.ALGORITHM_NONE) + "）",
      noisyAuto == bodyBytes(noisy, com.hdf.cryptand.soc.board.FrameCompression.ALGORITHM_NONE))
    // lz4 未实现：必须明确抛错（不静默换算法）
    var lz4Threw = false
    try {
      bodyBytes(solid, com.hdf.cryptand.soc.board.FrameCompression.ALGORITHM_LZ4)
    } catch {
      case _: UnsupportedOperationException => lz4Threw = true
    }
    check("压缩：lz4 未实现 ⇒ 明确抛错（不静默降级）", lz4Threw)

    val full: ScreenImageFrame = ScreenImageFrame("addr-1", 7L, full = true, 64, 32, 1, 1,
      Array(ScreenFrameBlock(0, 32, 0, 64)), Array.tabulate(64 * 32)(i => 0xFF000000 | i))
    val r1 = roundTrip(full)
    check("全量帧往返：地址/帧号/几何", r1.address == "addr-1" && r1.frameId == 7L && r1.width == 64 && r1.height == 32)
    check("全量帧往返：像素", java.util.Arrays.equals(r1.rows, full.rows))
    check("全量帧往返：块（整幅矩形）", r1.blocks.length == 1 && r1.blocks(0).firstRow == 0
      && r1.blocks(0).rowCount == 32 && r1.blocks(0).firstCol == 0 && r1.blocks(0).colCount == 64)

    // 增量：一个整行矩形 + 一个**局部列**矩形（验证列范围真的往返，且数据是紧凑打包的）
    val delta: ScreenImageFrame = ScreenImageFrame("addr-2", 8L, full = false, 64, 32, 2, 2,
      Array(ScreenFrameBlock(3, 2, 0, 32), ScreenFrameBlock(9, 1, 4, 8)),
      Array.tabulate(2 * 32 + 1 * 8)(i => 0xFFFFFFFF - i))
    val r2 = roundTrip(delta)
    check("增量帧往返：两块 + 像素", r2.blocks.length == 2 && r2.blocks(1).firstRow == 9
      && java.util.Arrays.equals(r2.rows, delta.rows))
    check("增量帧往返：列范围", r2.blocks(1).firstCol == 4 && r2.blocks(1).colCount == 8)
    check("增量帧：像素数按矩形算（2*32 + 1*8）", r2.rows.length == 2 * 32 + 1 * 8)
    check("网格由宽高/步长推出（64/2=32）", gridWidth(64, 2) == 32 && gridHeight(32, 2) == 16)

    // 帧体构造器：declaredLength < 0 = 按实际长度；>= 0 = 故意写错的声明长度（测坏包）
    def framedBody(declaredLength: Int)(fill: DataOutputStream => Unit): Array[Byte] = {
      val body = new ByteArrayOutputStream()
      val b = new DataOutputStream(body)
      fill(b)
      b.flush()
      val bytes = body.toByteArray
      val all = new ByteArrayOutputStream()
      val o = new DataOutputStream(all)
      o.writeInt(if (declaredLength < 0) bytes.length else declaredLength)
      o.write(bytes)
      o.flush()
      all.toByteArray
    }

    var threw = false
    try {
      // ⚠ 2026-09-29 修：原夹具少写了列的 colCount，且缺帧体算法字节（新增字段），
      //   一直是"恰好抛在别的分支上"的假绿 ⇒ 按真实格式重写，让它真的测"像素数与块声明不符"。
      val bad = framedBody(-1) { b =>
        b.writeUTF("a"); b.writeLong(1L); b.writeBoolean(false)
        b.writeInt(64); b.writeInt(32); b.writeInt(1); b.writeInt(1)
        b.writeInt(1)                                        // blockCount = 1
        b.writeInt(0); b.writeInt(1); b.writeInt(0); b.writeInt(1)   // 一块：1 行 × 1 列 ⇒ 应 1 个 int
        b.writeByte(0)                                       // 帧体算法 = NONE
        b.writeInt(5)                                        // 谎报 5 个 int
        var k = 0
        while (k < 5) { b.writeInt(0); k += 1 }
      }
      read(new DataInputStream(new ByteArrayInputStream(bad)))
    } catch {
      case _: IllegalArgumentException => threw = true
    }
    check("像素数与块声明不符 ⇒ 明确报错", threw)

    var threwBlock = false
    try {
      val bad = framedBody(-1) { b =>
        b.writeUTF("a"); b.writeLong(1L); b.writeBoolean(false)
        b.writeInt(64); b.writeInt(32); b.writeInt(1); b.writeInt(1)
        b.writeInt(1)                                        // blockCount = 1
        b.writeInt(0); b.writeInt(0); b.writeInt(0); b.writeInt(64)  // rowCount = 0 ⇒ 应报错
      }
      read(new DataInputStream(new ByteArrayInputStream(bad)))
    } catch {
      case _: IllegalArgumentException => threwBlock = true
    }
    check("rowCount < 1 ⇒ 明确报错", threwBlock)

    var threwLength = false
    try {
      val bad = framedBody(-1) { b =>
        b.writeUTF("a"); b.writeLong(1L); b.writeBoolean(false)
        b.writeInt(64); b.writeInt(32); b.writeInt(1); b.writeInt(1)
        b.writeInt(1); b.writeInt(0); b.writeInt(1); b.writeInt(64)
      }
      // 先把声明长度改成"远超实际"
      val mangled = bad.clone()
      mangled(0) = 0x7F; mangled(1) = -1.toByte
      read(new DataInputStream(new ByteArrayInputStream(mangled)))
    } catch {
      case _: IllegalArgumentException => threwLength = true
    }
    check("帧体长度非法 ⇒ 明确报错", threwLength)

    var threwTruncated = false
    try {
      val bad = framedBody(4096) { b =>                       // 声明 4KB，实际很短
        b.writeUTF("a"); b.writeLong(1L); b.writeBoolean(false)
      }
      read(new DataInputStream(new ByteArrayInputStream(bad)))
    } catch {
      case _: java.io.IOException => threwTruncated = true
    }
    check("截断的报文 ⇒ 明确报错（EOFException）", threwTruncated)

    println("=== ScreenFrameCodec " + passed + "/" + failed + " ===")
    if (failed > 0) {
      System.exit(1)
    }
  }
}
