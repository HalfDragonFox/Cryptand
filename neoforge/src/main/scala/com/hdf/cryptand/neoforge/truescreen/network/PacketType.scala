/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/PacketType.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.network

/**
 * 我方屏幕报文的**类型表**（上游 \`li.cil.oc.common.PacketType\` 的屏幕子集）。
 *
 * <h3>相对上游的裁剪</h3>
 * 上游的 PacketType 是一张覆盖全部子系统的长表（音频 / 全息 / 机器人 / 机架 / 手册…）。
 * 本次移植的闭包只有**屏幕**，所以只留屏幕真正用到的 26 个值：
 * <ul>
 *   <li><b>服务端 → 客户端</b>：TextBufferInit（快照）/ TextBufferMulti（增量批）/
 *       TextBufferPowerChange / ScreenTouchMode / ColorChange / RotatableState / RedstoneState；</li>
 *   <li><b>TextBufferMulti 的子命令</b>（同一个压缩包里连续多条）：颜色 / 拷贝 / 色深 / 填充 /
 *       调色板 / 分辨率 / 视口 / 最大分辨率 / 置字 / 显存块（Init / BitBlt / Destroy）/
 *       原始置字·背景·前景；</li>
 *   <li><b>客户端 → 服务端</b>：KeyDown / KeyUp / TextInput / Clipboard / CopyToAnalyzer /
 *       MouseClickOrDrag / MouseScroll / MouseUp。</li>
 * </ul>
 * 值是 \`Enumeration\` 的序号，**不跨版本兼容**（两端永远同版本）：报文注册用
 * \`PayloadRegistrar.versioned("1")\` 挡住不同版本的客户端。
 *
 * ⚠ 序号变了就等于协议变了：新增值只能**追加**在对应分组末尾，不能插在中间。
 */
object PacketType extends Enumeration {
  val
  // ==================== 服务端 → 客户端 ====================
  ColorChange,
  RotatableState,
  RedstoneState,
  ScreenTouchMode,
  /** 双向：客户端请求快照 / 服务端回快照。 */
  TextBufferInit,
  /** 一个压缩包里装 N 条下面的 TextBufferMulti* 子命令。 */
  TextBufferMulti,
  TextBufferPowerChange,

  // ==================== TextBufferMulti 的子命令 ====================
  TextBufferMultiColorChange,
  TextBufferMultiCopy,
  TextBufferMultiDepthChange,
  TextBufferMultiFill,
  TextBufferMultiPaletteChange,
  TextBufferMultiResolutionChange,
  TextBufferMultiViewportResolutionChange,
  TextBufferMultiMaxResolutionChange,
  TextBufferMultiSet,
  TextBufferMultiRawSetText,
  TextBufferMultiRawSetBackground,
  TextBufferMultiRawSetForeground,
  TextBufferRamInit,
  TextBufferBitBlt,
  TextBufferRamDestroy,

  // ==================== 客户端 → 服务端 ====================
  KeyDown,
  KeyUp,
  TextInput,
  Clipboard,
  CopyToAnalyzer,
  MouseClickOrDrag,
  MouseScroll,
  MouseUp,

  // ==================== 真彩屏 GRAPHICS 帧（任务 D；只服务端 → 客户端）====================
  // ⚠ 追加在**表尾**：序号即协议，插在中间会让所有旧序号漂移。
  ScreenGraphics,

  // ==================== 真彩屏图像帧同步（2026-09-28；客户端拉取）====================
  // 用户定案：客户端**主动请求**（先全量、再增量），服务器按每台机器的上限帧率（默认 60fps）
  // 返回**合成好的 ARGB 图像**（TEXT 也由服务器合成）—— 客户端不再理解字符面色深/调色板。
  // ⚠ 同样只能追加在表尾。
  ScreenFrameRequest,
  ScreenFrame,

  EndOfList = Value
}
