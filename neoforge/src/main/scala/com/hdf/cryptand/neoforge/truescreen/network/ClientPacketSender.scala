/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/PacketSender.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.network

/**
 * 客户端 → 服务端的**屏幕输入报文**（上游 li.cil.oc.client.PacketSender 的屏幕子集）。
 *
 * 8 条：KeyDown / KeyUp / TextInput / Clipboard / MouseClickOrDrag / MouseScroll / MouseUp /
 * CopyToAnalyzer；外加双向的 TextBufferInit（客户端主动索要一次完整快照）。
 * 调用点在 \`component.TextBuffer.ClientProxy\`（右键窗口里的键鼠）与
 * [object TrueScreenTextBuffer] 的客户端索引注册处。
 *
 * <h3>为什么不放在 client 包（与上游的差别）</h3>
 * 上游这个类在 li.cil.oc.client 下，因为它用了 Minecraft.getInstance() 放"剪贴板太大"的提示音。
 * 我方屏幕闭包不搬那个音效 ⇒ 本类**不引用任何客户端专属类型**，于是可以被公共代码
 * （component.TextBuffer 的 ClientProxy、方块 showGui）直接引用，不必再分一层代理类。
 * 实际发送仍走 PacketDistributor.sendToServer（只在客户端侧执行；服务端侧永远不会走到这些分支，
 * 因为 ClientProxy 只在 SideTracker.isClient 时构造）。
 */
object ClientPacketSender {

  /** 分片大小（上游 16KB：一个压缩包塞一段 UTF 字符串）。 */
  private final val ClipboardPartSize = 16 * 1024

  def sendKeyDown(address: String, char: Char, code: Int): Unit = {
    val pb = new SimplePacketBuilder(PacketType.KeyDown)
    pb.writeUTF(address)
    pb.writeChar(char)
    pb.writeInt(code)
    pb.sendToServer()
  }

  def sendKeyUp(address: String, char: Char, code: Int): Unit = {
    val pb = new SimplePacketBuilder(PacketType.KeyUp)
    pb.writeUTF(address)
    pb.writeChar(char)
    pb.writeInt(code)
    pb.sendToServer()
  }

  def sendTextInput(address: String, codePt: Int): Unit = {
    val pb = new SimplePacketBuilder(PacketType.TextInput)
    pb.writeUTF(address)
    pb.writeInt(codePt)
    pb.sendToServer()
  }

  /**
   * 剪贴板：超过上限**整条丢弃**（不是截断）—— 截断会往屏幕里写半截文本，
   * 上游同样是"丢弃 + 提示音"，我方去掉提示音只保留丢弃语义。
   */
  def sendClipboard(address: String, value: String): Unit = {
    if (value != null && !value.isEmpty && value.length <= PacketBuilder.MaxClipboardTextLength) {
      for (part <- value.grouped(ClipboardPartSize)) {
        val pb = new CompressedPacketBuilder(PacketType.Clipboard)
        pb.writeUTF(address)
        pb.writeUTF(part)
        pb.sendToServer()
      }
    }
  }

  def sendMouseClick(address: String, x: Double, y: Double, drag: Boolean, button: Int): Unit = {
    val pb = new SimplePacketBuilder(PacketType.MouseClickOrDrag)
    pb.writeUTF(address)
    pb.writeFloat(x.toFloat)
    pb.writeFloat(y.toFloat)
    pb.writeBoolean(drag)
    pb.writeByte(button.toByte)
    pb.sendToServer()
  }

  def sendMouseScroll(address: String, x: Double, y: Double, scroll: Int): Unit = {
    val pb = new SimplePacketBuilder(PacketType.MouseScroll)
    pb.writeUTF(address)
    pb.writeFloat(x.toFloat)
    pb.writeFloat(y.toFloat)
    pb.writeByte(scroll)
    pb.sendToServer()
  }

  def sendMouseUp(address: String, x: Double, y: Double, button: Int): Unit = {
    val pb = new SimplePacketBuilder(PacketType.MouseUp)
    pb.writeUTF(address)
    pb.writeFloat(x.toFloat)
    pb.writeFloat(y.toFloat)
    pb.writeByte(button.toByte)
    pb.sendToServer()
  }

  def sendCopyToAnalyzer(address: String, line: Int): Unit = {
    val pb = new SimplePacketBuilder(PacketType.CopyToAnalyzer)
    pb.writeUTF(address)
    pb.writeInt(line)
    pb.sendToServer()
  }

  /** 客户端向服务端索要"这块屏的完整快照"（服务端在 ServerPacketHandler.onTextBufferInit 里回）。 */
  def sendTextBufferInit(address: String): Unit = {
    val pb = new SimplePacketBuilder(PacketType.TextBufferInit)
    pb.writeUTF(address)
    pb.sendToServer()
  }

  /**
   * 客户端请求一帧**服务器合成的图像**（拉取模型，2026-09-28 定案）。
   *
   * full=true ⇒ 要整幅（刚进世界/刚重建/本地还没有基准）；false ⇒ 只要脏行。
   * 采样步长**不在这里传**：服务器按玩家到屏的距离自己算（ScreenSamplingPolicy）。
   * 服务器没变化时不回帧 —— 调用方按固定间隔重复请求即可（无状态、自愈）。
   */
  def sendScreenFrameRequest(address: String, full: Boolean): Unit = {
    val pb = new SimplePacketBuilder(PacketType.ScreenFrameRequest)
    pb.writeUTF(address)
    pb.writeBoolean(full)
    pb.sendToServer()
  }
}
