package com.hdf.cryptand.neoforge.truescreen

import li.cil.oc.api.internal.TextBuffer.ColorDepth

/**
 * 真彩屏（移植自 OC 屏幕）用到的**屏幕参数**单一来源。
 *
 * <h3>为什么不是 OC 的 Settings</h3>
 * OC 的 `li.cil.oc.Settings` 是一个 typesafe-config 单例，覆盖它的全部子系统
 * （电力 / 机器人 / 网络 / 文件系统 / 客户端渲染…），并且它的构造依赖 OC 自己的
 * `application.conf` 与 `OpenComputers` 主类。父代理裁决：「不要整搬 Settings，只挑屏幕
 * 真正用到的那部分，落成我方常量/枚举」。于是这里只保留屏幕参数，逐条标注数值出处。
 *
 * <h3>数值出处（可在 .ai_cache/OpenComputers 里逐条复核）</h3>
 * <ul>
 *   <li>分辨率 / 色深：`Settings.scala:528-529`（object Settings 里的常量，不在 conf 里）</li>
 *   <li>拼合上限：`resources/application.conf:1259`（`misc.maxScreenWidth`=8）、`:1266`（`maxScreenHeight`=6）</li>
 *   <li>耗电：`application.conf:681`（`power.cost.screen`=0.05）、`:518`（`power.tickFrequency`=10）</li>
 *   <li>单色屏颜色：`application.conf:73`（`client.monochromeColor`=0xFFFFFF）</li>
 *   <li>输入：`application.conf:1272`（`misc.inputUsername`=true）</li>
 *   <li>渲染距离 / 淡出：`application.conf:26`（20.0）、`:20`（15.0）；字体：`:35`(`textLinearFiltering`=false)、
 *       `:39`(`textAntiAlias`=true)、`:52`(`fontCharScale`=1.01)</li>
 *   <li>光照刷新：`application.conf:1627`（`periodicallyForceLightUpdate`=false）</li>
 * </ul>
 *
 * ⚠ 这里**只有**屏幕参数；任务 B（渲染）与任务 C（报文/注册）需要的其它 OC 配置项一律
 * 由各自任务按需新增，不要在这里塞一个「什么都有」的 Settings。
 */
object TrueScreenSettings {

  /** 我方资源/组件命名空间。OC 用的是 `Settings.namespace = "oc:"`（见 Settings.scala:525）。 */
  final val namespace = "cryptand:"

  // --------------------------------------------------------------------- //
  // 屏幕规格（OC Settings.scala:528-529）
  // --------------------------------------------------------------------- //

  /** 每档屏幕的最大字符分辨率（tier 0..3）。 */
  final val screenResolutionsByTier: Array[(Int, Int)] = Array((50, 16), (80, 25), (160, 50), (190, 60))

  /** 每档屏幕的最大色深（tier 0..3）。 */
  final val screenDepthsByTier: Array[ColorDepth] = Array(
    ColorDepth.OneBit, ColorDepth.FourBit, ColorDepth.EightBit, ColorDepth.SixteenBit)

  /**
   * 四档真彩屏的**自有色深**（bpp，tier 0..3）：1 / 8 / 16 / 24。
   *
   * ⚠ 用户定案（2026-09-29）：真彩屏**不走 OC 的色深轴**（OC 的 `ColorDepth` 只有
   * 1/4/8/16，装不下 24bpp）—— 这是我们**自定义彩屏**自己的色深声明：屏声明 bpp，
   * 帧仍是 RGB565，编码路径见 common 的 `ScreenFrameEncoder`。
   */
  final val screenBppByTier: Array[Int] = Array(1, 8, 16, 24)

  /** 基础（tier 0）屏的像素数；OC 用它把 gpu 单次操作的耗电折算到屏幕上（Settings.scala:539）。 */
  def basicScreenPixels: Int = screenResolutionsByTier(0)._1 * screenResolutionsByTier(0)._2

  // --------------------------------------------------------------------- //
  // 拼合与耗电
  // --------------------------------------------------------------------- //

  /** 多屏拼合的最大横向/纵向块数。 */
  final val maxScreenWidth = 8
  final val maxScreenHeight = 6

  /** 屏幕基础耗电（每个 tick，被 tickFrequency 放大）。 */
  final val screenCost = 0.05

  /** 连续耗电设备的结算周期（每多少个 tick 结一次电）。 */
  final val tickFrequency = 10.0

  // --------------------------------------------------------------------- //
  // 行为
  // --------------------------------------------------------------------- //

  /** 1bit 屏"亮色"那一半的 RGB。 */
  final val monochromeColor = 0xFFFFFF

  /** 踩踏/触摸事件是否带上玩家名（做 walk 事件的 username 字段）。 */
  final val inputUsername = true

  /** 是否周期性强制方块光照刷新（OC 用来修屏幕发光不更新的问题）。 */
  final val periodicallyForceLightUpdate = false

  // --------------------------------------------------------------------- //
  // 渲染参数（任务 B 用；这里只是把 OC 的数值集中过来，本轮不实现渲染）
  // --------------------------------------------------------------------- //

  /** 屏幕内容的最大渲染距离（超过就不画内容）。 */
  final val maxScreenTextRenderDistance = 20.0

  /** 开始按距离淡出屏幕内容的位置。 */
  final val screenTextFadeStartDistance = 15.0

  /** 字体纹理是否用线性过滤。 */
  final val textLinearFiltering = false

  /** 是否开启字形抗锯齿。 */
  final val textAntiAlias = true

  /** 字形缩放系数。 */
  final val fontCharScale = 1.01
}
