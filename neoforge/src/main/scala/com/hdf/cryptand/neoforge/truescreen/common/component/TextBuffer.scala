/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/component/TextBuffer.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.component

import com.google.common.base.Strings
import com.hdf.cryptand.neoforge.truescreen.{TrueScreenLog, TrueScreenSettings}
import com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsApi
import com.hdf.cryptand.neoforge.truescreen.common.Tier
import com.hdf.cryptand.neoforge.truescreen.common.blockentity
import com.hdf.cryptand.neoforge.truescreen.common.component.GpuTextBuffer
import com.hdf.cryptand.neoforge.truescreen.common.component.traits.VideoRamRasterizer
import com.hdf.cryptand.neoforge.truescreen.common.datacomponents.{MaximumVideoMode, TrueScreenComponents, VideoMode}
import com.hdf.cryptand.neoforge.truescreen.util.ExtendedDataComponentHolder._
import com.hdf.cryptand.neoforge.truescreen.util.{PackedColor, SideTracker}
import com.hdf.cryptand.neoforge.truescreen.util.{TextBuffer => UtilTextBuffer}
import com.hdf.cryptand.neoforge.truescreen.client.renderer.TrueScreenRenderCache
import com.hdf.cryptand.neoforge.truescreen.client.renderer.vram.TrueScreenVram
import com.hdf.cryptand.neoforge.truescreen.common.datacomponents.CompoundStorage
import com.hdf.cryptand.neoforge.truescreen.network.{ClientPacketSender, CompressedPacketBuilder, GraphicsFrameCodec, PacketBuilder, PacketType, ServerPacketSender}
import com.mojang.blaze3d.vertex.PoseStack
import li.cil.oc.api
import li.cil.oc.api.driver.DeviceInfo
import li.cil.oc.api.driver.DeviceInfo.{DeviceAttribute, DeviceClass}
import li.cil.oc.api.machine.{Arguments, Callback, Context}
import li.cil.oc.api.network._
import li.cil.oc.api.prefab.AbstractManagedEnvironment
import li.cil.oc.server.{ComponentTracker => ServerComponentTracker}
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.core.component.{DataComponentHolder, DataComponents}
import net.minecraft.nbt.{CompoundTag, NbtOps}
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.Level
import net.neoforged.api.distmarker.{Dist, OnlyIn}
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.common.MutableDataComponentHolder

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * `screen` 组件本体：字符/颜色缓冲 + 11 个 @Callback + 供电。
 *
 * <h3>任务 A 的边界（哪些是空实现 + TODO，为什么）</h3>
 * <ul>
 *   <li><b>ClientProxy 的渲染</b>（`render(stack)` / `render(stack, buffer)` / `renderWidth/Height`）：
 *       任务 B。本轮返回 false / 0，**不**临时凑一个画法。</li>
 *   <li><b>报文链</b>（`ClientPacketSender.*` / `ServerPacketSender.appendTextBuffer*` /
 *       `CompressedPacketBuilder` / `clientBuffers`+`ComponentTracker` 客户端侧 /
 *       `requestSynchronization`）：**任务 C 已落地**，见 `network/` 包与 `object TextBuffer`。
 *       ⚠ 客户端的字符面**只**来自报文（TextBufferInit 快照 + TextBufferMulti 增量）：
 *       BE 更新包只把 `ADDRESS` 组件带过来（见 traits.TextBuffer.saveForClient）。</li>
 *   <li><b>外部存档文件</b>（OC 的 SaveHandler：大屏缓冲写到维度目录下的文件）：
 *       一律内联进 BE 的 NBT（与 traits.TextBuffer 一致）⇒ 没有第二个真相来源。</li>
 *   <li>`blockentity.traits.Computer` 的"保存前暂停所有计算机"hack：不搬 OC 的 Computer 阶段，
 *       且我们的写屏路径只碰纯数据（见 blockentity/Screen 的线程纪律注释）。</li>
 * </ul>
 *
 * ⚠ 线程纪律：这 11 个回调跑在 **OC 的机器线程**上。本文件里它们只读写组件自己的字段 +
 * 发节点信号，**不碰 Level、不写方块、不 setChanged**。
 */
class TextBuffer(val host: EnvironmentHost) extends AbstractManagedEnvironment with traits.TextBufferProxy with VideoRamRasterizer with DeviceInfo {
  override val node = api.Network.newNode(this, Visibility.Network).
    withComponent("rc_screen").
    withConnector().
    create()

  private var maxResolution: (Int, Int) = TrueScreenSettings.screenResolutionsByTier(Tier.One)

  // 2026-09-29 白屏根治：`_data`（:186）在**构造期**就用本字段定编码格式，而 tier 是构造**之后**
  // 才由 setMaximumColorDepth 送进来 —— 默认 Tier.One 会让高档屏的 `_data` 永远以 OneBit 编码，
  // 上层却按新档打包 ⇒ 解包错位 ⇒ 整屏白（实证：SingleBitFormat 去解 16bit 的 0x257，
  // fg=0x16777215/bg=0x16777215 ⇒ 近白）。故构造期直接从宿主取档位，不依赖外部调用时序。
  private var maxDepth = {
    val depths = TrueScreenSettings.screenDepthsByTier
    host match {
      case s: com.hdf.cryptand.neoforge.truescreen.common.blockentity.Screen if s.tier > 0 =>
        depths(math.min(s.tier, depths.length - 1))
      case _ => depths(0)
    }
  }

  private var aspectRatio = (1.0, 1.0)

  private var powerConsumptionPerTick = TrueScreenSettings.screenCost

  private var precisionMode = false

  // For client side only.
  private var isRendering = true

  private var isDisplaying = true

  private var hasPower = true

  private var relativeLitArea = -1.0

  // ==================== 任务 C：报文（L6） ====================

  /** 本 tick 累积的"内容变更"压缩包（首次写入时才建包，见 {@link #pendingCommands}）。 */
  private var _pendingCommands: Option[PacketBuilder] = None

  /** 客户端"多久还没收到快照就再要一次"的兜底轮询间隔（tick）。上游 100。 */
  private val syncInterval = 100

  /** >0 = 还在等快照（每 tick 减一）；-1 = 已初始化（停止轮询）。 */
  private var syncCooldown = syncInterval

  /**
   * 本 tick 的增量包（\`TextBufferMulti\`）。
   *
   * ⚠ 线程纪律：组件回调跑在 **OC 的机器线程**上，而发送在主线程的 update() 里 ——
   * 所以每一次读写都用 owner 的监视器串起来（调用点见 ServerProxy 与 update()）。
   */
  private def pendingCommands: PacketBuilder = _pendingCommands.getOrElse {
    val pb = new CompressedPacketBuilder(PacketType.TextBufferMulti)
    pb.writeUTF(node.address)
    _pendingCommands = Some(pb)
    pb
  }

  /** 客户端收到权威快照后调用：停止兜底轮询，并让整屏重算一次发光面积。 */
  def markInitialized(): Unit = {
    syncCooldown = -1
    relativeLitArea = -1
  }

  /**
   * 客户端注册索引 + 索要一次完整快照。
   *
   * ⚠ 不把"已初始化"的屏打回未初始化（上游注释）：未初始化期间增量包会被整包丢弃，
   * 一旦打回就会让"实时输入要关了窗口重开才看得见"。
   */
  def requestSynchronization(): Unit = if (SideTracker.isClient()) {
    if (!isInitialized) TextBuffer.registerClientBuffer(this)
  }

  def registerClientBufferOnLevel(level: Level): Boolean = TextBuffer.registerClientBuffer(this, level)

  def unregisterClientBufferOnLevel(level: Level): Unit = TextBuffer.unregisterClientBuffer(this, level)

  def isInitialized: Boolean = syncCooldown < 0

  var fullyLitCost: Double = computeFullyLitCost()

  // This computes the energy cost (per tick) to keep the screen running if
  // every single "pixel" is lit. This cost increases with higher tiers as
  // their maximum resolution (pixel density) increases. For a basic screen
  // this is simply the configured cost.
  def computeFullyLitCost(): Double = {
    val (w, h) = TrueScreenSettings.screenResolutionsByTier(0)
    val mw = getMaximumWidth
    val mh = getMaximumHeight
    powerConsumptionPerTick * (mw * mh) / (w * h)
  }

  val proxy: TextBuffer.Proxy =
    if (SideTracker.isClient()) new TextBuffer.ClientProxy(this)
    else new TextBuffer.ServerProxy(this)

  /**
   * 数据缓冲（字符面/颜色面/调色板的**唯一入口**）。
   *
   * <p>★ 任务 F-1：真彩屏的**驱动方闸门**就钉在这个访问器上 —— 原版 OC 显卡驱动本屏时，
   * 它的每一次 {@code set/get/fill/copy/setResolution/setColorDepth/rawSet*} 都必须经过这里，
   * 于是"驱动方是不是我们的 GPU"只需在这一个点上判（判定依据与成本账见
   * {@code com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate} 的类注释：
   * 原版显卡的 {@code GraphicsCard.screen(...)} 字节码是 monitorenter → 调用屏方法）。</p>
   *
   * <p>★ 2026-09-27 P0 修复：被拒时**不再抛异常**（抛出去会穿出 {@code Network.send} 崩服务端），
   * 而是返回 {@link #refusedView} —— 只读假屏（读中性值、写全部 no-op、不发任何报文）。</p>
   *
   * <p>我们的 GPU / 报文钩子 / 客户端渲染都走同一个访问器：没持本对象监视器 ⇒ 26 ns 直接放行。</p>
   */
  override def data: UtilTextBuffer = {
    if (com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "data")) _data
    else refusedView
  }

  /**
   * 被拒驱动方（原版 OC 显卡）看到的"只读假屏"（2026-09-27 P0 修复）。
   *
   * <p>两条要求同时满足：**读 = 中性值**（{@code TrueScreenDriveGate.NEUTRAL_WIDTH}×
   * {@code NEUTRAL_HEIGHT} 的最小几何 + 全空白字符面 + 默认四色调色板）、**写 = no-op**
   * （改状态的方法全部覆写成 no-op / 返回 false）。后者还顺带让 {@code TextBufferProxy} 里
   * {@code if (data.set(...)) onBufferSet(...)} 式的钩子**根本不会触发** ⇒ 一条客户端报文都发不出去。</p>
   */
  private val refusedView: UtilTextBuffer = new TextBuffer.RefusedView

  private val _data = new UtilTextBuffer(maxResolution, PackedColor.Depth.format(maxDepth))

  var viewport: (Int, Int) = _data.size

  private final lazy val deviceInfo = Map(
    DeviceAttribute.Class -> DeviceClass.Display,
    DeviceAttribute.Description -> "Text buffer",
    DeviceAttribute.Vendor -> "cryptand",
    DeviceAttribute.Product -> "TrueScreen V0",
    DeviceAttribute.Capacity -> (maxResolution._1 * maxResolution._2).toString,
    DeviceAttribute.Width -> Array("1", "4", "8", "16").apply(maxDepth.ordinal())
  )

  override def getDeviceInfo: java.util.Map[String, String] = deviceInfo.asJava

  // ----------------------------------------------------------------------- //

  override val canUpdate = true

  override def update(): Unit = {
    super.update()
    // 客户端拉取（可见才拉，见 ClientProxy.pullImageFrame 的 0.5s 渲染时间戳判据）：
    // 挂在 tick 上而不是某个渲染重载上 —— 渲染入口有好几个，tick 只有一个。
    proxy match {
      case client: TextBuffer.ClientProxy => client.tickPullImageFrame()
      case _ =>
    }
    if (isDisplaying && host.getEnvironmentLevel.getGameTime % TrueScreenSettings.tickFrequency == 0) {
      if (relativeLitArea < 0) {
        // The relative lit area is the number of pixels that are not blank
        // versus the number of pixels in the *current* resolution. This is
        // scaled to multi-block screens, since we only compute this for the
        // origin.
        val w = getViewportWidth
        val h = getViewportHeight
        var acc = 0f
        // Description packets and legacy saves may resize the character and
        // color planes in separate steps. Never let one inconsistent frame
        // take down the client tick loop while the next update repairs it.
        val safeHeight = math.min(h, math.min(data.buffer.length, data.color.length))
        for (y <- 0 until safeHeight) {
          val line = data.buffer(y)
          val colors = data.color(y)
          val safeWidth = math.min(w, math.min(line.length, colors.length))
          for (x <- 0 until safeWidth) {
            val char = line(x)
            val color = colors(x)
            val bg = PackedColor.unpackBackground(color, data.format)
            val fg = PackedColor.unpackForeground(color, data.format)
            acc += (if (char == ' ') if (bg == 0) 0 else 1
            else if (char == 0x2588) if (fg == 0) 0 else 1
            else if (fg == 0 && bg == 0) 0 else 1)
          }
        }
        relativeLitArea = if (w > 0 && h > 0) acc / (w * h).toDouble else 0
      }
      if (node != null) {
        val hadPower = hasPower
        val neededPower = relativeLitArea * fullyLitCost * TrueScreenSettings.tickFrequency
        hasPower = node.tryChangeBuffer(-neededPower)
        if (hasPower != hadPower) {
          ServerPacketSender.sendTextBufferPowerChange(node.address, isDisplaying && hasPower, host)
        }
      }
    }

    // 本 tick 累积的增量：**一次性**发出去。这一句跑在主线程（updateEntity → buffer.update），
    // 而写入端在 OC 的机器线程 ⇒ 用监视器把两边串起来（上游同款）。
    this.synchronized {
      _pendingCommands.foreach(_.sendToPlayersNearHost(host))
      _pendingCommands = None
    }

    // 客户端兜底：还没拿到权威快照就每 syncInterval tick 再要一次（上游同款）。
    if (SideTracker.isClient() && syncCooldown > 0) {
      syncCooldown -= 1
      if (syncCooldown == 0) {
        syncCooldown = syncInterval
        ClientPacketSender.sendTextBufferInit(proxy.nodeAddress)
      }
    }
  }

  // ----------------------------------------------------------------------- //

  @Callback(direct = true, doc = """function():boolean -- Returns whether the screen is currently on.""")
  def isOn(computer: Context, args: Arguments): Array[AnyRef] = result(isDisplaying)

  @Callback(doc = """function():boolean -- Turns the screen on. Returns whether the state changed, and whether it is now on.""")
  def turnOn(computer: Context, args: Arguments): Array[AnyRef] = {
    val oldPowerState = isDisplaying
    setPowerState(value = true)
    result(isDisplaying != oldPowerState, isDisplaying)
  }

  @Callback(doc = """function():boolean -- Turns off the screen. Returns whether the state changed, and whether it is now on.""")
  def turnOff(computer: Context, args: Arguments): Array[AnyRef] = {
    val oldPowerState = isDisplaying
    setPowerState(value = false)
    result(isDisplaying != oldPowerState, isDisplaying)
  }

  @Callback(direct = true, doc = """function():number, number -- The aspect ratio of the screen. For multi-block screens this is the number of blocks, horizontal and vertical.""")
  def getAspectRatio(context: Context, args: Arguments): Array[AnyRef] = this.synchronized {
    result(aspectRatio._1, aspectRatio._2)
  }

  @Callback(doc = """function():table -- The list of keyboards attached to the screen.""")
  def getKeyboards(context: Context, args: Arguments): Array[AnyRef] = {
    context.pause(0.25)
    host match {
      case screen: com.hdf.cryptand.neoforge.truescreen.common.blockentity.Screen =>
        // ⚠ 这里认的是 **OC 自己的键盘组件**（li.cil.oc.server.component.Keyboard）：键盘子系统
        //   不在任务 A 范围，也不该有第二套。玩家用 OC 键盘贴着屏幕时，照旧能列出来。
        Array(screen.screens.map(_.node).flatMap(_.neighbors.asScala.filter(_.host.isInstanceOf[li.cil.oc.server.component.Keyboard]).map(_.address)).toArray)
      case _ =>
        Array(node.neighbors.asScala.filter(_.host.isInstanceOf[li.cil.oc.server.component.Keyboard]).map(_.address).toArray)
    }
  }

  @Callback(direct = true, doc = """function():boolean -- Returns whether the screen is in high precision mode (sub-pixel mouse event positions).""")
  def isPrecise(computer: Context, args: Arguments): Array[AnyRef] = result(precisionMode)

  @Callback(doc = """function(enabled:boolean):boolean -- Set whether to use high precision mode (sub-pixel mouse event positions).""")
  def setPrecise(computer: Context, args: Arguments): Array[AnyRef] = {
    // Available for T3+ screens only... easiest way to check for us is to
    // base it off of the maximum color depth.
    if (maxDepth.compareTo(TrueScreenSettings.screenDepthsByTier(Tier.Three)) >= 0) {
      val oldValue = precisionMode
      precisionMode = args.checkBoolean(0)
      result(oldValue)
    }
    else result((), "unsupported operation")
  }

  @Callback(doc = """function():number -- Get the maximum resolution supported by the screen.""")
  def hardwareResolution(context: Context, args: Arguments): Array[AnyRef] = {
    val (smw, smh) = maxResolution
    result(smw, smh)
  }

  @Callback(direct = true, doc = """function():number -- Get the maximum color depth supported by the screen.""")
  def hardwareDepth(context: Context, args: Arguments): Array[AnyRef] =
    result(PackedColor.Depth.bits(maxDepth))

  // ----------------------------------------------------------------------- //

  override def setEnergyCostPerTick(value: Double): Unit = {
    powerConsumptionPerTick = value
    fullyLitCost = computeFullyLitCost()
  }

  override def getEnergyCostPerTick: Double = powerConsumptionPerTick

  override def setPowerState(value: Boolean): Unit = {
    if (isDisplaying != value) {
      isDisplaying = value
      if (isDisplaying) {
        val neededPower = fullyLitCost * TrueScreenSettings.tickFrequency
        hasPower = node.changeBuffer(-neededPower) == 0
      }
      ServerPacketSender.sendTextBufferPowerChange(node.address, isDisplaying && hasPower, host)
    }
  }

  override def getPowerState: Boolean = isDisplaying

  override def setMaximumResolution(width: Int, height: Int): Unit = {
    if (width < 1) throw new IllegalArgumentException("width must be larger or equal to one")
    if (height < 1) throw new IllegalArgumentException("height must be larger or equal to one")
    maxResolution = (width, height)
    fullyLitCost = computeFullyLitCost()
    proxy.onBufferMaxResolutionChange(width, height)
  }

  override def getMaximumWidth: Int = {
    // 任务 F-1（2026-09-27 修复）：被拒 ⇒ 只读入口回**中性值**，绝不抛（读类不再有异常通道）
    if (com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "getMaximumWidth")) maxResolution._1
    else com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.NEUTRAL_WIDTH
  }

  override def getMaximumHeight: Int = {
    if (com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "getMaximumHeight")) maxResolution._2
    else com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.NEUTRAL_HEIGHT
  }

  override def setAspectRatio(width: Double, height: Double): Unit = this.synchronized(this.aspectRatio = (width, height))

  override def getAspectRatio: Double = aspectRatio._1 / aspectRatio._2

  override def setResolution(w: Int, h: Int): Boolean = {
    // 任务 F-1（2026-09-27 修复）：不抛 —— 被拒 ⇒ 整个写入口 no-op 并回 false
    // （setResolution 会改真 viewport 并广播 screen_resized，是本闸门之外唯一能"写生效"的路）
    if (!com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "setResolution")) return false
    val (mw, mh) = maxResolution
    if (w < 1 || h < 1 || w > mw || h > mw || h * w > mw * mh)
      throw new IllegalArgumentException("unsupported resolution")
    // Always send to clients, their state might be dirty.
    proxy.onBufferResolutionChange(w, h)
    // Force set viewport to new resolution. This is partially for
    // backwards compatibility, and partially to enforce a valid one.
    val sizeChanged = data.size = (w, h)
    val viewportChanged = setViewport(w, h)
    if (sizeChanged || viewportChanged) {
      if (!viewportChanged && node != null) {
        node.sendToReachable("computer.signal", "screen_resized", Int.box(w), Int.box(h))
      }
      true
    }
    else false
  }

  override def setViewport(w: Int, h: Int): Boolean = {
    // 任务 F-1（2026-09-27 修复）：被拒 ⇒ no-op（不换 viewport、不发 screen_resized），不抛
    if (!com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "setViewport")) return false
    val (mw, mh) = data.size
    if (w < 1 || h < 1 || w > mw || h > mh)
      throw new IllegalArgumentException("unsupported viewport resolution")
    // Always send to clients, their state might be dirty.
    proxy.onBufferViewportResolutionChange(w, h)
    val (cw, ch) = viewport
    if (w != cw || h != ch) {
      viewport = (w, h)
      if (node != null) {
        node.sendToReachable("computer.signal", "screen_resized", Int.box(w), Int.box(h))
      }
      true
    }
    else false
  }

  override def getViewportWidth: Int = {
    if (com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "getViewportWidth")) viewport._1
    else com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.NEUTRAL_VIEWPORT_WIDTH
  }

  override def getViewportHeight: Int = {
    if (com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "getViewportHeight")) viewport._2
    else com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.NEUTRAL_VIEWPORT_HEIGHT
  }

  override def setMaximumColorDepth(depth: api.internal.TextBuffer.ColorDepth): Unit = {
    maxDepth = depth
    syncDataFormatToMaxDepth()
  }

  /**
   * 让 `_data` 的编码格式跟上 `maxDepth`。
   *
   * `util.TextBuffer.format_=`（:62-78）会按**当前** `_format` 逐格解包、再按新格式打包 ——
   * 只有当"数据确实是用 `_format` 写的"时才成立；而 `_data` 的写入路径
   * （`foreground_=`/`background_=`/`set`）一律用 `_format` 打包，所以重编码是安全的。
   * 这里直接读私有字段 `_data`：`data`（:171）过驱动闸门，内部维护不该被闸门拦。
   */
  private def syncDataFormatToMaxDepth(): Unit = {
    val target = PackedColor.Depth.format(maxDepth)
    if (_data.format.depth != target.depth) _data.format = target
  }

  override def getMaximumColorDepth: api.internal.TextBuffer.ColorDepth = {
    // 任务 F-1：不摸 data 的驱动入口 ⇒ 各自单独过闸门（原版显卡 bind 与 onMessage 都会问它）
    // 2026-09-27 修复：被拒 ⇒ 回中性深度（四色调色板，与 {@code refusedView} 的档位一致），不抛
    if (com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "getMaximumColorDepth")) maxDepth
    else api.internal.TextBuffer.ColorDepth.FourBit
  }

  override def setColorDepth(depth: api.internal.TextBuffer.ColorDepth): Boolean = {
    // 任务 F-1（2026-09-27 修复）：被拒 ⇒ no-op（既不换真色深，也不发 depth 报文），不抛
    if (!com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "setColorDepth")) return false
    val colorDepthChanged: Boolean = super.setColorDepth(depth)
    // Always send to clients, their state might be dirty.
    proxy.onBufferDepthChange(depth)
    colorDepthChanged
  }

  // 任务 F-1（2026-09-27 修复）：这两个钩子是**不经过 data** 的报文出口（原版显卡的
  // setForeground/setBackground/setPaletteColor 会直接走到这里）⇒ 各自过闸门，被拒即丢弃。
  override def onBufferPaletteChange(index: Int): Unit =
    if (com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "onBufferPaletteChange")) proxy.onBufferPaletteChange(index)

  override def onBufferColorChange(): Unit =
    if (com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.requireCryptandDriver(this, "onBufferColorChange")) proxy.onBufferColorChange()

  override def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Unit = {
    proxy.onBufferCopy(col, row, w, h, tx, ty)
  }

  override def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Int): Unit = {
    proxy.onBufferFill(col, row, w, h, c)
  }

  override def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean): Unit = {
    proxy.onBufferSet(col, row, s, vertical)
  }

  override def onBufferBitBlt(col: Int, row: Int, w: Int, h: Int, ram: GpuTextBuffer, fromCol: Int, fromRow: Int): Unit = {
    proxy.onBufferBitBlt(col, row, w, h, ram, fromCol, fromRow)
  }

  override def onBufferRamInit(ram: GpuTextBuffer): Unit = {
    proxy.onBufferRamInit(ram)
  }

  override def onBufferRamDestroy(ram: GpuTextBuffer): Unit = {
    proxy.onBufferRamDestroy(ram)
  }

  override def rawSetText(col: Int, row: Int, text: Array[Array[Int]]): Unit = {
    super.rawSetText(col, row, text)
    proxy.onBufferRawSetText(col, row, text)
  }

  override def rawSetBackground(col: Int, row: Int, color: Array[Array[Int]]): Unit = {
    super.rawSetBackground(col, row, color)
    // Better for bandwidth to send packed shorts here. Would need a special case for handling on client,
    // though, so let's be wasteful for once...
    proxy.onBufferRawSetBackground(col, row, color)
  }

  override def rawSetForeground(col: Int, row: Int, color: Array[Array[Int]]): Unit = {
    super.rawSetForeground(col, row, color)
    proxy.onBufferRawSetForeground(col, row, color)
  }

  // ----------------------------------------------------------------------- //
  // ★ 任务 B —— 内容 → 像素的 4 个出口（渲染链唯一允许改动的地方）
  //   上游：renderText → ClientProxy.render → TextBufferRenderCache.render(renderer)，
  //         renderWidth/Height → TextBufferRenderCache.renderer.charRenderWidth * viewport。
  //   本移植：内容来源换成**真彩屏 VRAM**（common 的 TrueColorScreen，1..32bpp + 调色板 +
  //         锁帧 + 拼合）：
  //         renderText → ClientProxy.render/render(renderBuffer)
  //                    → TrueScreenRenderCache.render/renderImmediate(stack, vram)
  //                    → TrueScreenVram（char 面 → 设备字符平面 → ScreenTextLayer；
  //                      像素面 → 设备 VRAM → rgbAt/bpp/调色板）→ 一张 DynamicTexture quad。
  //         renderWidth/Height → VRAM 帧的像素尺寸（TEXT = 视口字符数 x CELL）。
  //   两个内容入口（世界侧 ScreenRenderer.draw 与 GUI 侧 BufferRenderer.drawText）都调
  //   renderText，所以它们走的是**同一条**来源；本文件是这条链的起点。
  // ----------------------------------------------------------------------- //

  @OnlyIn(Dist.CLIENT)
  override def renderText(stack: PoseStack): Boolean = relativeLitArea != 0 && proxy.render(stack)

  private var diagRenderText = 0

  @OnlyIn(Dist.CLIENT)
  def renderText(stack: PoseStack, renderBuffer: MultiBufferSource): Boolean = {
    if (diagRenderText < 8) {
      TrueScreenLog.log.info("[TrueScreen/DIAG] renderText lit={} displaying={} power={} viewport={}x{} proxy={}",
        java.lang.Double.valueOf(relativeLitArea), java.lang.Boolean.valueOf(isDisplaying),
        java.lang.Boolean.valueOf(hasPower), Integer.valueOf(getViewportWidth), Integer.valueOf(getViewportHeight),
        proxy.getClass.getSimpleName)
      diagRenderText += 1
    }
    relativeLitArea != 0 && (proxy match {
      case client: TextBuffer.ClientProxy => client.render(stack, renderBuffer)
      case _ => proxy.render(stack)
    })
  }

  @OnlyIn(Dist.CLIENT)
  override def renderWidth: Int = proxy match {
    case client: TextBuffer.ClientProxy => client.renderPixelWidth
    // 服务端代理没有渲染面（上游也是"客户端才有像素尺寸"：TextBufferRenderCache 只存在于客户端）。
    case _ => 0
  }

  @OnlyIn(Dist.CLIENT)
  override def renderHeight: Int = proxy match {
    case client: TextBuffer.ClientProxy => client.renderPixelHeight
    case _ => 0
  }

  @OnlyIn(Dist.CLIENT)
  override def setRenderingEnabled(enabled: Boolean): Unit = isRendering = enabled

  @OnlyIn(Dist.CLIENT)
  override def isRenderingEnabled: Boolean = isRendering

  override def keyDown(character: Char, code: Int, player: Player): Unit =
    proxy.keyDown(character, code, player)

  override def keyUp(character: Char, code: Int, player: Player): Unit =
    proxy.keyUp(character, code, player)

  override def textInput(codePt: Int, player: Player): Unit =
    proxy.textInput(codePt, player)

  override def clipboard(value: String, player: Player): Unit =
    proxy.clipboard(value, player)

  override def mouseDown(x: Double, y: Double, button: Int, player: Player): Unit =
    proxy.mouseDown(x, y, button, player)

  override def mouseDrag(x: Double, y: Double, button: Int, player: Player): Unit =
    proxy.mouseDrag(x, y, button, player)

  override def mouseUp(x: Double, y: Double, button: Int, player: Player): Unit =
    proxy.mouseUp(x, y, button, player)

  override def mouseScroll(x: Double, y: Double, delta: Int, player: Player): Unit =
    proxy.mouseScroll(x, y, delta, player)

  def copyToAnalyzer(line: Int, player: Player): Unit = {
    proxy.copyToAnalyzer(line, player)
  }

  // ----------------------------------------------------------------------- //
  // 组件索引（服务端）。复用 **OC 自己的 ComponentTracker**（按节点地址 → 组件实例的索引）：
  // 报文处理器（任务 C）会用它把客户端发来的键鼠事件落到具体这块屏上。
  // 不另造一份索引 —— 否则 OC 的报文处理器找不到我们的组件。
  // ----------------------------------------------------------------------- //

  override def onConnect(node: Node): Unit = {
    super.onConnect(node)
    if (node == this.node) {
      ServerComponentTracker.add(host.getEnvironmentLevel, node.address, this)
      registerGraphicsSink()
    }
  }

  override def onDisconnect(node: Node): Unit = {
    super.onDisconnect(node)
    if (node == this.node) {
      unregisterGraphicsSink()
      ServerComponentTracker.remove(host.getEnvironmentLevel, this)
    }
  }

  // ----------------------------------------------------------------------- //
  // 像素模式（GRAPHICS）的生产者通道：按**节点地址**登记，生产者（GPU 环境）据此拿到设备写像素。
  // 只登记拼合 **origin**（设备/内容/客户端索引都只在 origin 上，同一个口径）。
  // ----------------------------------------------------------------------- //

  private def registerGraphicsSink(): Unit = host match {
    case screen: blockentity.Screen if screen.isOrigin =>
      TrueScreenGraphicsApi.register(node.address, screen.graphics)
    case _ =>
  }

  private def unregisterGraphicsSink(): Unit = host match {
    case screen: blockentity.Screen =>
      val sink = screen.graphicsIfAny
      if (sink != null) TrueScreenGraphicsApi.unregister(node.address, sink)
    case _ =>
  }

  /**
   * 客户端：把一帧 GRAPHICS 内容落到 VRAM 镜像（服务端 tick 发的 ScreenGraphics 报文调用）。
   *
   * 几何/调色板/锁状态/帧字节都在报文里（见 GraphicsFrameCodec），客户端只是按顺序应用；
   * 帧字节数与设备不符时由 TrueScreenVram.feedGraphics 明确报错（不截断）。
   */
  @OnlyIn(Dist.CLIENT)
  def applyGraphicsFrame(mode: Int, w: Int, h: Int, bpp: Int, locked: Boolean, frames: Long, skipped: Long,
                         checksum: Int, palette: Array[Int], frame: Array[Byte]): Unit = proxy match {
    case client: TextBuffer.ClientProxy =>
      client.applyGraphicsFrame(mode, w, h, bpp, locked, frames, skipped, checksum, palette, frame)
    case _ =>
  }

  /** 客户端：把服务器**合成好的一帧图像**落到贴图（拉取模型的回答，2026-09-28）。 */
  @OnlyIn(Dist.CLIENT)
  def applyImageFrame(frame: com.hdf.cryptand.neoforge.truescreen.network.ScreenImageFrame): Unit = proxy match {
    case client: TextBuffer.ClientProxy => client.applyImageFrame(frame)
    case _ =>
  }

  // ----------------------------------------------------------------------- //

  override def loadData(holder: DataComponentHolder): Unit = {
    super.loadData(holder)
    for (address <- holder.getComponent(TrueScreenComponents.ADDRESS)) {
      if (SideTracker.isClient()) {
        // 诊断（2026-09-27）：这一行决定客户端有没有索引条目。
        TextBuffer.diagLoadData += 1L
        if (TextBuffer.diagLoadData <= 5L) {
          TrueScreenLog.log.info("[TrueScreen/CLIENT-DIAG] loadData 客户端分支：ADDRESS='{}' proxyAddress='{}' level={}",
            address, proxy.nodeAddress, java.lang.String.valueOf(host.getEnvironmentLevel))
        }
        if (!Strings.isNullOrEmpty(proxy.nodeAddress)) return // Only load once.
        proxy.nodeAddress = address
        // 注册客户端索引 + 向服务端索要一次完整快照（内容走报文，不走 BE 更新包 ——
        // BE 更新包只负责把 ADDRESS 组件带到客户端，见 traits.TextBuffer.saveForClient）。
        requestSynchronization()
      }
      else {
        holder.getComponent(TrueScreenComponents.TEXT_BUFFER) match {
          case Some(_) => data.loadData(holder)
          // OC 在这个分支走 loadExternalData()（维度目录下的 <address>_buffer 外挂文件）。
          // 我们一律内联持久化（见 traits.TextBuffer），所以这里没有内容可补。
          case None =>
        }
      }
    }

    for (isOnComponent <- holder.getComponent(TrueScreenComponents.IS_ON))
      isDisplaying = isOnComponent

    for (isPoweredComponent <- holder.getComponent(TrueScreenComponents.IS_POWERED))
      hasPower = isPoweredComponent

    for (MaximumVideoMode(maxWidth, maxHeight, depth) <- holder.getComponent(TrueScreenComponents.MAX_VIDEO_MODE)) {
      maxResolution = (maxWidth, maxHeight)

      // Restore maxDepth so that getMaximumColorDepth() returns the correct tier
      // even if setMaximumColorDepth() was not called after construction (e.g.
      // when the buffer lazy val was initialised before load(nbt) ran).
      val depthValues = api.internal.TextBuffer.ColorDepth.values
      val ordinal = depth min (depthValues.length - 1) max 0
      maxDepth = depthValues(ordinal)
      syncDataFormatToMaxDepth()
    }

    precisionMode = holder.getOrDefault(TrueScreenComponents.IS_PRECISE.get(), false)

    viewport = holder.getComponent(TrueScreenComponents.VIDEO_MODE) match {
      case Some(VideoMode(vpw, vph)) => (vpw min data.width max 1, vph min data.height max 1)
      case None => data.size
    }
  }

  // Null check for Waila (and other mods that may call this client side).
  override def saveData(holder: MutableDataComponentHolder): Unit = if (node != null) {
    super.saveData(holder)
    // OC 在这里会先暂停所有连到本屏的计算机（"Happy thread synchronization hack"）：
    // GPU 允许直接调用改屏，保存时若"计算机先存、屏后改"就会存下过期内容。
    // 我们不做这一步：① 没搬 OC 的 Computer 阶段；② 任务 A 的写屏回调只碰纯数据，
    // 世界侧写操作一律延后到服务端 tick（见 blockentity/Screen 的线程纪律注释）。
    // 任务 C 落地报文/序列化后若发现竞态，再按 OC 的做法补回。

    // ★ 节点地址必须随存档与**客户端快照**一起走：客户端就是靠它把服务端报文对上是哪一块屏
    //   （loadData 的客户端分支读 ADDRESS ⇒ registerClientBuffer）。此前**只有读没有写** ⇒
    //   客户端的地址恒为空 ⇒ 注册被 `registerClientBuffer` 的早退吃掉（还不打日志）⇒
    //   所有屏幕报文（TextBufferInit / TextBufferMulti / ScreenGraphics…）全部按「未知地址」丢弃，
    //   屏面全黑且没有任何报错。2026-09-27 真机定位（诊断日志：图形帧被丢弃：地址不在客户端索引）。
    //   地址为空时不写：让"地址还没分配"保持"查不到"，而不是写一个空串冒充存在。
    //   地址的来源分两侧：**服务端**权威是组件自己的节点（`node.address`）——`proxy.nodeAddress` 只在
    //   客户端的 loadData 里被填（服务端从不填它），所以这里必须优先取 node.address，否则服务端
    //   永远写不出地址（真机 2026-09-27：客户端 loadData 拿不到 ADDRESS ⇒ 客户端的 ADDRESS 循环体
    //   一次都没进 ⇒ 索引为空 ⇒ 全部屏幕报文被丢弃）。
    val address = if (node != null && !Strings.isNullOrEmpty(node.address)) node.address else proxy.nodeAddress
    if (!Strings.isNullOrEmpty(address)) {
      proxy.nodeAddress = address
      holder.setComponent(TrueScreenComponents.ADDRESS, address)
    }

    // 内联持久化（OC 只对 Tablet/Projector/移动中的屏幕内联，其余走外挂文件；我们一律内联）
    data.saveData(holder)
    holder.setComponent(TrueScreenComponents.IS_ON, isDisplaying)
    holder.setComponent(TrueScreenComponents.IS_POWERED, hasPower)
    holder.setComponent(TrueScreenComponents.MAX_VIDEO_MODE, MaximumVideoMode(maxResolution._1, maxResolution._2, maxDepth.ordinal))
    holder.setComponent(TrueScreenComponents.IS_PRECISE, precisionMode)
    holder.setComponent(TrueScreenComponents.VIDEO_MODE, VideoMode(viewport._1, viewport._2))
  }
}

object TextBuffer {

  /**
   * 被拒驱动方（原版 OC 显卡）看到的"只读假屏"（任务 F-1，2026-09-27 P0 修复）。
   *
   * <p>{@code util.TextBuffer} 的只读替身：几何 = 中性值（{@code NEUTRAL_WIDTH}×
   * {@code NEUTRAL_HEIGHT}）、字符面全空白、调色板 = 默认四色 16 项（四色是为了让
   * {@code setPaletteColor/getPaletteColor} 这类调用在 OC 语义下**不抛"palette not available"**）。
   * **所有改状态的方法都覆写成 no-op / 返回 false** —— 这一条同时干掉两类穿透：
   * ① 真缓冲一格不动；② {@code TextBufferProxy} 里 {@code if (data.set(...)) onBufferSet(...)}
   * 式的"变了才发报文"钩子**根本不会触发**（一条客户端报文都发不出去）。</p>
   *
   * <p>⚠ 它只服务于"被拒的驱动方"这一个用途：我方 GPU / 报文钩子 / 客户端渲染走的永远是真
   * {@code _data}（快路径不持锁 ⇒ 直接放行）。</p>
   */
  final class RefusedView extends UtilTextBuffer(
    com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.NEUTRAL_WIDTH,
    com.hdf.cryptand.neoforge.opencomputers.TrueScreenDriveGate.NEUTRAL_HEIGHT,
    PackedColor.Depth.format(api.internal.TextBuffer.ColorDepth.FourBit)) {

    override def set(col: Int, row: Int, s: String, vertical: Boolean): Boolean = false

    override def fill(col: Int, row: Int, w: Int, h: Int, c: Int): Boolean = false

    override def copy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Boolean = false

    override def rawcopy(col: Int, row: Int, w: Int, h: Int, src: UtilTextBuffer, fromCol: Int, fromRow: Int): Boolean = false

    override def size_=(value: (Int, Int)): Boolean = false

    override def format_=(value: PackedColor.ColorFormat): Boolean = false

    // 颜色 setter 也 no-op：既不改假屏状态，也避免 PackedColor 的 validate 对"调色板色号越界"抛异常
    override def foreground_=(value: PackedColor.Color): UtilTextBuffer = this

    override def background_=(value: PackedColor.Color): UtilTextBuffer = this
  }

  // ----------------------------------------------------------------------- //
  // 任务 C：**客户端**的 buffer 索引
  //
  // 用途有两个：① 客户端右键窗口（client.gui.Screen）能找到自己那块屏的 buffer；
  //            ② 报文处理器按节点地址把服务端快照投递到对应客户端 buffer。
  // ⚠ 索引本体**复用 OC 自己的 li.cil.oc.client.ComponentTracker**（不另建索引类）：
  //    报文处理器查的就是它，另建一份就会"注册了但查不到"。该类的常数池里没有任何
  //    @OnlyIn 注解（javap -v 核实），因此专用服务端上也安全（它内部只有 Level/ManagedEnvironment）。
  // ----------------------------------------------------------------------- //

  /** 已注册索引的客户端 buffer（区块/维度卸载时从这里摘掉）。 */
  /** 诊断计数（2026-09-27，真机验证用；不作为功能路径）。 */
  private var diagLoadData = 0L
  private var diagRegisterSkip = 0L

  var clientBuffers = mutable.ListBuffer.empty[TextBuffer]

  /** 该 buffer 的方块坐标（宿主不是方块实体时退化成浮点位置取整）。 */
  private def blockXZ(t: TextBuffer): (Int, Int) = t.host match {
    case be: net.minecraft.world.level.block.entity.BlockEntity => (be.getBlockPos.getX, be.getBlockPos.getZ)
    case _ => (t.host.xPosition.toInt, t.host.zPosition.toInt)
  }

  @SubscribeEvent
  def onChunkUnloaded(e: net.neoforged.neoforge.event.level.ChunkEvent.Unload): Unit = {
    val chunk = e.getChunk
    clientBuffers = clientBuffers.filter(t => {
      val (bx, bz) = blockXZ(t)
      val chunkPos = chunk.getPos
      val keep = t.host.getEnvironmentLevel != e.getLevel || ((bx >> 4) != chunkPos.x || (bz >> 4) != chunkPos.z)
      if (!keep) {
        li.cil.oc.client.ComponentTracker.remove(t.host.getEnvironmentLevel, t)
      }
      keep
    })
  }

  @SubscribeEvent
  def onWorldUnload(e: net.neoforged.neoforge.event.level.LevelEvent.Unload): Unit = {
    clientBuffers = clientBuffers.filter(t => {
      val keep = t.host.getEnvironmentLevel != e.getLevel
      if (!keep) {
        li.cil.oc.client.ComponentTracker.remove(t.host.getEnvironmentLevel, t)
      }
      keep
    })
  }

  /** 注册到"这块屏实际所在的客户端维度"（宿主自己的 level 可能还是空的）。 */
  def registerClientBuffer(t: TextBuffer): Unit = {
    registerClientBuffer(t, t.host.getEnvironmentLevel)
  }

  def registerClientBuffer(t: TextBuffer, level: Level): Boolean = {
    if (level == null || Strings.isNullOrEmpty(t.proxy.nodeAddress)) {
      // 诊断（2026-09-27）：早退时**必须**留痕，否则这类失败完全静默（真机排查因此绕了远路）。
      diagRegisterSkip += 1L
      if (diagRegisterSkip <= 5L) {
        TrueScreenLog.log.warn("[TrueScreen/CLIENT-DIAG] 注册被跳过：level={} address='{}'",
          java.lang.String.valueOf(level), t.proxy.nodeAddress)
      }
      return false
    }

    // 宿主所在的维度与目标维度不一致时，先把旧维度里的注册摘掉（避免同一块屏注册两次）。
    val hostLevel = t.host.getEnvironmentLevel
    if (hostLevel != null && hostLevel != level) {
      li.cil.oc.client.ComponentTracker.remove(hostLevel, t)
    }

    // 区块/容器重新同步时重复调用是常态：先删再加，保证不会留下重复注册。
    li.cil.oc.client.ComponentTracker.remove(level, t)
    li.cil.oc.client.ComponentTracker.add(level, t.proxy.nodeAddress, t)

    if (!clientBuffers.contains(t)) {
      clientBuffers += t
    }

    // 注册完立刻索要权威快照（否则客户端只有 ADDRESS，字符面永远是空的）。
    ClientPacketSender.sendTextBufferInit(t.proxy.nodeAddress)
    // 诊断（2026-09-27）：客户端索引有没有这块屏，是「报文被丢」与「屏幕没渲染」的分水岭。
    if (level.isClientSide) {
      TrueScreenLog.log.info("[TrueScreen/CLIENT] 客户端索引注册：地址 {} 维度 {}",
        t.proxy.nodeAddress, level.dimension().location())
    }
    true
  }

  def unregisterClientBuffer(t: TextBuffer, level: Level): Unit = {
    if (level == null) return
    li.cil.oc.client.ComponentTracker.remove(level, t)
    val hostLevel = t.host.getEnvironmentLevel
    if (hostLevel != null && hostLevel != level) {
      li.cil.oc.client.ComponentTracker.remove(hostLevel, t)
    }
    clientBuffers -= t
    ()
  }

  abstract class Proxy {
    def owner: TextBuffer

    var dirty = false

    /** 报文用的节点地址（客户端侧由 loadData 从 ADDRESS 组件填）。 */
    var nodeAddress = ""

    def setChanged(): Unit = {
      dirty = true
    }

    @OnlyIn(Dist.CLIENT)
    def render(stack: PoseStack) = false

    def onBufferColorChange(): Unit

    def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferDepthChange(depth: api.internal.TextBuffer.ColorDepth): Unit

    def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Int): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferPaletteChange(index: Int): Unit

    def onBufferResolutionChange(w: Int, h: Int): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferViewportResolutionChange(w: Int, h: Int): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferMaxResolutionChange(w: Int, h: Int): Unit = {
    }

    def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferBitBlt(col: Int, row: Int, w: Int, h: Int, ram: GpuTextBuffer, fromCol: Int, fromRow: Int): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferRamInit(ram: GpuTextBuffer): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferRamDestroy(ram: GpuTextBuffer): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferRawSetText(col: Int, row: Int, text: Array[Array[Int]]): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferRawSetBackground(col: Int, row: Int, color: Array[Array[Int]]): Unit = {
      owner.relativeLitArea = -1
    }

    def onBufferRawSetForeground(col: Int, row: Int, color: Array[Array[Int]]): Unit = {
      owner.relativeLitArea = -1
    }

    def keyDown(character: Char, code: Int, player: Player): Unit

    def keyUp(character: Char, code: Int, player: Player): Unit

    def textInput(codePt: Int, player: Player): Unit

    def clipboard(value: String, player: Player): Unit

    def mouseDown(x: Double, y: Double, button: Int, player: Player): Unit

    def mouseDrag(x: Double, y: Double, button: Int, player: Player): Unit

    def mouseUp(x: Double, y: Double, button: Int, player: Player): Unit

    def mouseScroll(x: Double, y: Double, delta: Int, player: Player): Unit

    def copyToAnalyzer(line: Int, player: Player): Unit
  }

  /**
   * 客户端代理 —— **任务 A 里的桩**。
   *
   * 保留的：`dirty` 标记与 `onBuffer*` 的"内容变了"失效通知（纯数据，任务 B 的渲染缓存要用）。
   * 留空的（TODO 已逐条标注）：渲染（任务 B）与"把输入发回服务端"的报文（任务 C）。
   */
  class ClientProxy(val owner: TextBuffer) extends Proxy {
    override def onBufferColorChange(): Unit = {
      setChanged()
    }

    override def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Unit = {
      super.onBufferCopy(col, row, w, h, tx, ty)
      setChanged()
    }

    override def onBufferDepthChange(depth: api.internal.TextBuffer.ColorDepth): Unit = {
      setChanged()
    }

    override def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Int): Unit = {
      super.onBufferFill(col, row, w, h, c)
      setChanged()
    }

    override def onBufferPaletteChange(index: Int): Unit = {
      setChanged()
    }

    override def onBufferResolutionChange(w: Int, h: Int): Unit = {
      super.onBufferResolutionChange(w, h)
      setChanged()
    }

    override def onBufferViewportResolutionChange(w: Int, h: Int): Unit = {
      super.onBufferViewportResolutionChange(w, h)
      setChanged()
    }

    override def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean): Unit = {
      super.onBufferSet(col, row, s, vertical)
      setChanged()
    }

    override def onBufferBitBlt(col: Int, row: Int, w: Int, h: Int, ram: GpuTextBuffer, fromCol: Int, fromRow: Int): Unit = {
      super.onBufferBitBlt(col, row, w, h, ram, fromCol, fromRow)
      setChanged()
    }

    override def onBufferRamInit(ram: GpuTextBuffer): Unit = {
      super.onBufferRamInit(ram)
    }

    override def onBufferRamDestroy(ram: GpuTextBuffer): Unit = {
      super.onBufferRamDestroy(ram)
    }

    // ------------------------------------------------------------------- //
    // ★ 任务 B：内容来源 = 真彩屏 VRAM（common 的 TrueColorScreen）
    //
    //   上游：render → TextBufferRenderCache.render(renderer)，renderer 是
    //   font.TextBufferRenderData（把字符面 + 视口交给字形渲染器逐格生成 quad）。
    //   本移植：内容一律先灌进 VRAM 设备，再由渲染器把**整帧**合成成一张 DynamicTexture：
    //     render / renderImmediate → TrueScreenRenderCache.render / renderImmediate(stack, vram)
    //                              → TrueScreenVram → common 的 TrueColorScreen
    //     · TEXT 模式：字符面 → 设备字符平面 → common 的 ScreenTextLayer（字形位来自 TextFont8x8）
    //     · GRAPHICS 模式：设备 VRAM 字节 → 逐像素 rgbAt（bpp / 调色板 / stride 都在 common）
    //
    //   灌入的触发信号保持上游语义：dirty（setChanged()，由各 onBuffer* 钩子置位）。
    //   ⚠ 若任务 C 的报文同步**直接写 owner.data 而不过 onBuffer* 钩子**，需要在写入点显式
    //     setChanged() —— 否则这份内容不会被灌进 VRAM（唯一插入点就是这个 dirty）。
    // ------------------------------------------------------------------- //

    /** 本组件对应的 VRAM 设备（client 侧镜像；lazy = 只在真正要渲染时才建立）。 */
    /**
   * 本组件的屏宿主。
   *
   * <p>⚠ 真机实证：{@code owner} 运行时是 {@code component.Screen}（屏组件包装），**不是** BE ——
   * 所以要么经它的 {@code screen} 字段拿 BE。静态类型 {@code traits.TextBuffer} 上没有 width/height/lodStairs。
   *
   * <p>（原始记录）{@code owner} 的静态类型是 {@code traits.TextBuffer}（trait），而 width/height/lodStairs
   * 定义在 {@code blockentity.Screen} 类上 —— 所以这里做一次显式匹配。
   * 匹配失败**明确抛错**而不是静默跳过：屏幕组件的宿主不可能是别的东西，出现即真 bug。</p>
   */
  private def screenOwner: com.hdf.cryptand.neoforge.truescreen.common.blockentity.Screen =
    owner match {
      // 真实路径：宿主是**屏组件包装**（component.Screen 持有 blockentity.Screen）。
      // 真机实证：owner 运行时是 component.Screen，不是 blockentity.Screen（后者只是它的字段）。
      case componentScreen: com.hdf.cryptand.neoforge.truescreen.common.component.Screen => componentScreen.screen
      // 裸 TextBuffer 组件（非屏宿主）不该有 vram；出现即真 bug，明确报错而不是静默跳过。
      case other => throw new IllegalStateException("屏幕组件的宿主不是屏组件：" + other)
    }

  private lazy val vram: TrueScreenVram = {
    val created = new TrueScreenVram(owner.host)
    val screen = screenOwner
    // 把**注册时传入该屏的 LOD 阶梯**交给渲染侧（块注册时传 ⇒ BE 持有 ⇒ 这里直达 vram）。
    // 不注入的话客户端只会用配置表的全局阶梯，"按屏定制"就不生效 ——
    // 而用户定案正是「oc 部分只需要注册时传入这个参数即可」。
    created.setLodStairs(screen.lodStairs)
    // 拼接后的方块尺寸（LOD 档位换算需要）；单格屏为 1x1。
    created.setBlockSize(screen.width, screen.height)
    // 一次性日志（每个屏组件只建一次 vram）：证明"注册传入 ⇒ BE ⇒ vram"这条链真的通了。
    com.hdf.cryptand.neoforge.truescreen.TrueScreenLog.log.info(
      "[TrueScreen] 屏 LOD 阶梯（注册传入）：{}；拼接 {}x{}",
      screen.lodStairs.describe(),
      Integer.valueOf(screen.width), Integer.valueOf(screen.height))
    created
  }

    /**
     * 显示模式：true = 像素模式（GRAPHICS）。
     *
     * ⚠ 由 **ScreenGraphics 报文**置位（服务端设备的 mode 是权威），不是客户端自己猜的。
     * 像素模式下**绝不喂字符面**：那会调用 ensureTextShape 把设备重建成 TEXT 形状，
     * 把刚收到的帧整块抹掉（而且字符面本来就是空的）。切回 TEXT 时再交还给字符面路径。
     */
    private var graphicsMode = false

    /** 字符面的色深（1/4/8/16 —— 与 TrueColorScreen 的档位一一对应）。 */
    private def vramBpp: Int = PackedColor.Depth.bits(owner.data.format.depth)

    // --- 图形帧诊断（2026-09-27，真机验证用；不作为功能路径） ---
    //   目的：一次真机跑就能区分「报文没到」与「到了但没画」。
    //   只在「形状变了 / 模式切了 / 每 100 帧」打一行 —— 否则 20Hz 会刷屏。
    private var gfxFrames = 0L
    private var gfxLastShape = ""
    private var gfxLastMode = false

    /**
     * 内容 → VRAM（TEXT 面）。灌完把 dirty 清掉（上游 renderDirect 结束时也清 dirty）。
     * 像素模式下直接跳过（见 graphicsMode 的说明）。
     */
    private def feedVram(): Unit = {
      if (graphicsMode) return
      // 拼接尺寸可能变（相邻屏合/拆）⇒ 每次喂帧前同步一次（setBlockSize 内部只在变化时写，幂等）。
    val screenHost = screenOwner
    vram.setBlockSize(screenHost.width, screenHost.height)
    vram.feedText(owner.data, owner.viewport, vramBpp, dirty)
      dirty = false
    }

    /** VRAM 帧的像素宽（= 上游 renderer.charRenderWidth * viewport 的替代）。 */
    def renderPixelWidth: Int = {
      if (!graphicsMode) vram.ensureTextShape(owner.viewport, vramBpp)
      vram.pixelWidth
    }

    /** VRAM 帧的像素高（同上）。 */
    def renderPixelHeight: Int = {
      if (!graphicsMode) vram.ensureTextShape(owner.viewport, vramBpp)
      vram.pixelHeight
    }

    /**
     * 应用一帧 GRAPHICS 报文（server → client，主线程）。
     *
     * 顺序 = 设备重建 → 调色板 → 帧字节 → 锁状态 → 帧号；帧字节数与设备不符时
     * {@code TrueScreenVram.feedGraphics} 会明确报错（device.fail() + 日志），**不截断**。
     */
    @OnlyIn(Dist.CLIENT)
    def applyGraphicsFrame(mode: Int, w: Int, h: Int, bpp: Int, locked: Boolean, frames: Long, skipped: Long,
                           checksum: Int, palette: Array[Int], frame: Array[Byte]): Unit = {
      val nowGraphics = mode == GraphicsFrameCodec.ModeGraphics
      if (!nowGraphics) {
        // 服务端切回 TEXT：交还字符面路径 —— 置 dirty 让下一帧 render 重新按字符面形状建设备。
        if (graphicsMode || gfxLastMode) {
          TrueScreenLog.log.info("[TrueScreen/CLIENT] 图形帧诊断：服务端切回 TEXT（帧号 {}），客户端交还字符面",
            java.lang.Long.valueOf(frames))
        }
        gfxLastMode = false
        graphicsMode = false
        dirty = true
        return
      }
      graphicsMode = true
      val shape = w + "x" + h + "@" + bpp + "bpp"
      gfxFrames += 1
      val shapeChanged = !shape.equals(gfxLastShape)
      if (shapeChanged || !gfxLastMode || (gfxFrames % 100L) == 0L) {
        TrueScreenLog.log.info("[TrueScreen/CLIENT] 图形帧诊断：收到并应用 mode=GRAPHICS {} 帧号 {} 锁 {} 帧字节 {} 累计 {} 形状变化={}",
          shape, java.lang.Long.valueOf(frames),
          java.lang.Boolean.valueOf(locked),
          Integer.valueOf(if (frame == null) -1 else frame.length),
          java.lang.Long.valueOf(gfxFrames), java.lang.Boolean.valueOf(shapeChanged))
      }
      gfxLastShape = shape
      gfxLastMode = true
      vram.configureGraphics(w, h, bpp)
      if (palette != null && palette.nonEmpty) vram.setPalette(palette)
      vram.feedGraphics(frame)
      vram.setLocked(locked)
      vram.markSynced(frames, skipped)
      // 整屏校验（用户需求 2026-09-29）：更新后本地整屏再算一次，必须与服务端随帧下发的值一致。
      // 算式两侧共用（common 的 ScreenFrameChecksum）—— 不符只报日志，不猜测、不局部修补。
      val localChecksum = vram.checksum
      if (localChecksum != checksum) {
        gfxChecksumMismatch += 1
        if (gfxChecksumMismatch <= 3L || (gfxChecksumMismatch % 100L) == 0L) {
          TrueScreenLog.log.warn(
            "[TrueScreen/CLIENT] 整屏校验不符：本地 {} != 服务端 {}（{}x{}@{}bpp 帧号 {}，累计 {} 次）",
            Integer.valueOf(localChecksum), Integer.valueOf(checksum),
            Integer.valueOf(w), Integer.valueOf(h), Integer.valueOf(bpp),
            java.lang.Long.valueOf(frames), java.lang.Long.valueOf(gfxChecksumMismatch))
        }
      } else {
        gfxChecksumOk += 1
        if (gfxChecksumOk <= 3L || (gfxChecksumOk % 200L) == 0L) {
          TrueScreenLog.log.info(
            "[TrueScreen/CLIENT] 整屏校验一致：值 {}（{}x{}@{}bpp 帧号 {}，累计 {} 次）",
            Integer.valueOf(localChecksum), Integer.valueOf(w), Integer.valueOf(h), Integer.valueOf(bpp),
            java.lang.Long.valueOf(frames), java.lang.Long.valueOf(gfxChecksumOk))
        }
      }
    }

    /** 拉取间隔：50ms（20 次/秒）。服务器侧另有每台机器的回帧上限（默认 60fps）。 */
    /** LOD 对拉取间隔的最大放大倍数（再远也要保留最低刷新，不能让远处的屏"看起来死了"）。 */
  private final val MaxLodPullFactor = 8L

  /**
   * 有效拉取间隔 = 配置间隔 × LOD 步长（客户端 LOD 的"省服务端"那一半）。
   *
   * <p>用户定案"LOD 全部由客户端实现"：屏在远处时既降采样渲染，也**少拉帧** ——
   * 后者才是真正省成本的地方：少一次请求 = 服务端少合成一帧全分辨率图像（全屏 1.504ms）
   * + 少一次网络往返 + 少一次压缩。档位由 {@link #MaxLodPullFactor} 夹住。</p>
   */
  /** 上次打过的有效间隔（低频日志用；-1 = 还没打过）。 */
  private var lastEffectiveIntervalMs = -1L

  private def effectivePullIntervalMs(): Long = {
    val base = pullIntervalMs()
    val step = Math.max(1L, vram.lodStep.toLong)
    val effective = base * Math.min(MaxLodPullFactor, step)
    if (effective != lastEffectiveIntervalMs) {
      lastEffectiveIntervalMs = effective
      // 只在间隔真的变化时打（低频）：证明"远处少拉帧"这条客户端 LOD 确实生效。
      com.hdf.cryptand.neoforge.truescreen.TrueScreenLog.log.info(
        "[TrueScreen] 拉取间隔 {}ms（LOD 步长 {}，基准 {}ms）",
        java.lang.Long.valueOf(effective), java.lang.Long.valueOf(step), java.lang.Long.valueOf(base))
    }
    effective
  }

  /** 请求间隔（毫秒）—— 客户端请求速率上限（用户 2026-09-29：配置可调，默认 50ms = 20 次/秒）。 */
  private def pullIntervalMs(): Long =
    com.hdf.cryptand.neoforge.opencomputers.config.ConfigOpenComputers.trueScreenClientRequestIntervalMs().toLong

    private var lastPullMs = 0L

    /** 诊断计数（临时，2026-09-28）。 */
    private var diagPulls = 0L

    /** 收到了多少帧图像（诊断）。 */
    private var imageFrames = 0L

    /** 整屏校验不符的次数（用户需求 2026-09-29；诊断用）。 */
    private var gfxChecksumMismatch = 0L

    /** 整屏校验一致的次数（低频打日志，用于无人化验证"校验确实跑了"）。 */
    private var gfxChecksumOk = 0L

    @OnlyIn(Dist.CLIENT)
    def applyImageFrame(frame: com.hdf.cryptand.neoforge.truescreen.network.ScreenImageFrame): Unit = {
      if (vram.applyImageFrame(frame)) {
        imageFrames += 1
        if (imageFrames <= 3L || (imageFrames % 200L) == 0L) {
          TrueScreenLog.log.info("[TrueScreen/CLIENT] 图像帧：帧号 {} {} {}x{} 采样 {}x{} 块 {} 累计 {}",
            java.lang.Long.valueOf(frame.frameId),
            if (frame.full) "全量" else "增量",
            Integer.valueOf(frame.width), Integer.valueOf(frame.height),
            Integer.valueOf(frame.stepW), Integer.valueOf(frame.stepH),
            Integer.valueOf(frame.blocks.length), java.lang.Long.valueOf(imageFrames))
        }
      }
    }

    /**
     * 可见即拉取（客户端主动请求模型）：渲染被调用 = 这块屏在视野里。
     *
     * 本地还没有基准（没收到过全量）就请求全量，否则请求增量；服务器没变化时不回帧，
     * 下一次 50ms 后自然重试 —— 没有重试状态机，也不会因为丢包卡死。
     */
    /** 最近一次被渲染的时间（毫秒）—— "看得见"的判据：不在视野里就不拉帧。 */
    private var lastRenderMs = 0L

    /** 客户端每 tick 调一次（由 component.TextBuffer.update 统一驱动）。 */
    @OnlyIn(Dist.CLIENT)
    def tickPullImageFrame(): Unit = pullImageFrame()

    @OnlyIn(Dist.CLIENT)
    private def pullImageFrame(): Unit = {
      val address = nodeAddress
      if (address == null || address.isEmpty) return
      val now = System.currentTimeMillis()
      // 最近 0.5s 没被画过 ⇒ 看不见（或还没渲染过），不拉。
      // 判据用"渲染时间戳"而不是"哪个渲染重载"：世界屏/手持屏/窗口走的是不同入口，
      // 而它们都会打这个戳（2026-09-28 真机踩到：只挂在其中一个重载上 ⇒ 一次都没拉）。
      if (now - lastRenderMs > 500L) return
      if (lastPullMs != 0L && now - lastPullMs < effectivePullIntervalMs()) return
      lastPullMs = now
      // 诊断（临时，2026-09-28）：拉取链路是否真的发出去
      diagPulls += 1
      if (diagPulls <= 5L || (diagPulls % 100L) == 0L) {
        TrueScreenLog.log.info("[TrueScreen/DIAG] 拉取图像帧 第 {} 次 address={} full={}",
          java.lang.Long.valueOf(diagPulls), address, java.lang.Boolean.valueOf(!vram.hasImage))
      }
      com.hdf.cryptand.neoforge.truescreen.network.ClientPacketSender
        .sendScreenFrameRequest(address, !vram.hasImage)
    }

    @OnlyIn(Dist.CLIENT)
    override def render(stack: PoseStack): Boolean = {
      feedVram()
      lastRenderMs = System.currentTimeMillis()
      pullImageFrame()
      TrueScreenRenderCache.render(stack, vram)
    }

    @OnlyIn(Dist.CLIENT)
    def render(stack: PoseStack, renderBuffer: MultiBufferSource): Boolean = {
      feedVram()
      lastRenderMs = System.currentTimeMillis()
      pullImageFrame()
      TrueScreenRenderCache.renderImmediate(stack, renderBuffer, vram)
    }

    // 客户端 → 服务端：把窗口里的键鼠/剪贴板发回服务端（上游逐字）。
    // 服务端侧由 ServerPacketHandler 按地址落到 ComponentTracker 里的组件上。
    override def keyDown(character: Char, code: Int, player: Player): Unit = {
      ClientPacketSender.sendKeyDown(nodeAddress, character, code)
    }

    override def keyUp(character: Char, code: Int, player: Player): Unit = {
      ClientPacketSender.sendKeyUp(nodeAddress, character, code)
    }

    override def textInput(codePt: Int, player: Player): Unit = {
      ClientPacketSender.sendTextInput(nodeAddress, codePt)
    }

    override def clipboard(value: String, player: Player): Unit = {
      ClientPacketSender.sendClipboard(nodeAddress, value)
    }

    override def mouseDown(x: Double, y: Double, button: Int, player: Player): Unit = {
      ClientPacketSender.sendMouseClick(nodeAddress, x, y, drag = false, button)
    }

    override def mouseDrag(x: Double, y: Double, button: Int, player: Player): Unit = {
      ClientPacketSender.sendMouseClick(nodeAddress, x, y, drag = true, button)
    }

    override def mouseUp(x: Double, y: Double, button: Int, player: Player): Unit = {
      ClientPacketSender.sendMouseUp(nodeAddress, x, y, button)
    }

    override def mouseScroll(x: Double, y: Double, delta: Int, player: Player): Unit = {
      ClientPacketSender.sendMouseScroll(nodeAddress, x, y, delta)
    }

    override def copyToAnalyzer(line: Int, player: Player): Unit = {
      ClientPacketSender.sendCopyToAnalyzer(nodeAddress, line)
    }
  }

  /**
   * 服务端代理。
   *
   * 保留的：`owner.host.markChanged()`（内容变了 ⇒ 需要存档/同步）、键鼠 → 键盘组件的
   * **节点信号**（`keyboard.keyDown` / `computer.checked_signal` touch/drag/drop/scroll），
   * 以及 copyToAnalyzer（把某一行文本写进手持分析器的物品数据）—— 这些都是组件语义，
   * 不是 OC 的报文封装，任务 A 必须保留。
   *
   * 留空的：OC 的 `ServerPacketSender.appendTextBuffer*`（把 buffer 增量塞进本 tick 的
   * `TextBufferMulti` 压缩包）—— 任务 C。
   */
  class ServerProxy(val owner: TextBuffer) extends Proxy {
    /**
     * 每一条都是"把这次内容变更追加进本 tick 的增量包"。
     *
     * ⚠ 全部走 \`owner.synchronized\`：这些钩子跑在 **OC 的机器线程**上（guest 写屏），
     * 而包的发送在**主线程**的 update() 里 —— 不加锁就会出现"边压边发"的半截包。
     * 发送与清空见 TextBuffer.update()。
     */
    override def onBufferColorChange(): Unit = {
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferColorChange(owner.pendingCommands, owner.data.foreground, owner.data.background))
    }

    override def onBufferCopy(col: Int, row: Int, w: Int, h: Int, tx: Int, ty: Int): Unit = {
      super.onBufferCopy(col, row, w, h, tx, ty)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferCopy(owner.pendingCommands, col, row, w, h, tx, ty))
    }

    override def onBufferDepthChange(depth: api.internal.TextBuffer.ColorDepth): Unit = {
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferDepthChange(owner.pendingCommands, depth))
    }

    override def onBufferFill(col: Int, row: Int, w: Int, h: Int, c: Int): Unit = {
      super.onBufferFill(col, row, w, h, c)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferFill(owner.pendingCommands, col, row, w, h, c))
    }

    override def onBufferPaletteChange(index: Int): Unit = {
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferPaletteChange(owner.pendingCommands, index, owner.getPaletteColor(index)))
    }

    override def onBufferResolutionChange(w: Int, h: Int): Unit = {
      super.onBufferResolutionChange(w, h)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferResolutionChange(owner.pendingCommands, w, h))
    }

    override def onBufferViewportResolutionChange(w: Int, h: Int): Unit = {
      super.onBufferViewportResolutionChange(w, h)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferViewportResolutionChange(owner.pendingCommands, w, h))
    }

    override def onBufferMaxResolutionChange(w: Int, h: Int): Unit = {
      if (owner.node.network != null) {
        super.onBufferMaxResolutionChange(w, h)
        owner.host.markChanged()
        owner.synchronized(ServerPacketSender.appendTextBufferMaxResolutionChange(owner.pendingCommands, w, h))
      }
    }

    override def onBufferSet(col: Int, row: Int, s: String, vertical: Boolean): Unit = {
      super.onBufferSet(col, row, s, vertical)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferSet(owner.pendingCommands, col, row, s, vertical))
    }

    override def onBufferBitBlt(col: Int, row: Int, w: Int, h: Int, ram: GpuTextBuffer, fromCol: Int, fromRow: Int): Unit = {
      super.onBufferBitBlt(col, row, w, h, ram, fromCol, fromRow)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferBitBlt(owner.pendingCommands, col, row, w, h, ram.owner, ram.id, fromCol, fromRow))
    }

    override def onBufferRamInit(ram: GpuTextBuffer): Unit = {
      super.onBufferRamInit(ram)
      owner.host.markChanged()
      val nbt = new CompoundStorage()
      ram.saveData(nbt)
      owner.synchronized(ServerPacketSender.appendTextBufferRamInit(owner.pendingCommands, ram.owner, ram.id,
        CompoundStorage.CODEC.encode(nbt, NbtOps.INSTANCE, new net.minecraft.nbt.CompoundTag()).getOrThrow().asInstanceOf[net.minecraft.nbt.CompoundTag]))
    }

    override def onBufferRamDestroy(ram: GpuTextBuffer): Unit = {
      super.onBufferRamDestroy(ram)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferRamDestroy(owner.pendingCommands, ram.owner, ram.id))
    }

    override def onBufferRawSetText(col: Int, row: Int, text: Array[Array[Int]]): Unit = {
      super.onBufferRawSetText(col, row, text)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferRawSetText(owner.pendingCommands, col, row, text))
    }

    override def onBufferRawSetBackground(col: Int, row: Int, color: Array[Array[Int]]): Unit = {
      super.onBufferRawSetBackground(col, row, color)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferRawSetBackground(owner.pendingCommands, col, row, color))
    }

    override def onBufferRawSetForeground(col: Int, row: Int, color: Array[Array[Int]]): Unit = {
      super.onBufferRawSetForeground(col, row, color)
      owner.host.markChanged()
      owner.synchronized(ServerPacketSender.appendTextBufferRawSetForeground(owner.pendingCommands, col, row, color))
    }

    override def keyDown(character: Char, code: Int, player: Player): Unit = {
      sendToKeyboards("keyboard.keyDown", player, Char.box(character), Int.box(code))
    }

    override def keyUp(character: Char, code: Int, player: Player): Unit = {
      sendToKeyboards("keyboard.keyUp", player, Char.box(character), Int.box(code))
    }

    override def textInput(codePt: Int, player: Player): Unit = {
      sendToKeyboards("keyboard.textInput", player, Int.box(codePt))
    }

    override def clipboard(value: String, player: Player): Unit = {
      sendToKeyboards("keyboard.clipboard", player, value)
    }

    override def mouseDown(x: Double, y: Double, button: Int, player: Player): Unit = {
      sendMouseEvent(player, "touch", x, y, button)
    }

    override def mouseDrag(x: Double, y: Double, button: Int, player: Player): Unit = {
      sendMouseEvent(player, "drag", x, y, button)
    }

    override def mouseUp(x: Double, y: Double, button: Int, player: Player): Unit = {
      sendMouseEvent(player, "drop", x, y, button)
    }

    override def mouseScroll(x: Double, y: Double, delta: Int, player: Player): Unit = {
      sendMouseEvent(player, "scroll", x, y, delta)
    }

    override def copyToAnalyzer(line: Int, player: Player): Unit = {
      val stack = player.getItemInHand(InteractionHand.MAIN_HAND)
      if (!stack.isEmpty) {
        val text = if (line >= 0 && line < owner.getViewportHeight) owner.data.lineToString(line) else ""
        // 一次 update 内完成"清旧 + 写新"（OC 分两步、并用了已废弃的 CustomData.getUnsafe；
        // 语义相同，只是不碰废弃 API）。
        stack.update(
          DataComponents.CUSTOM_DATA,
          CustomData.EMPTY,
          (customData: CustomData) => {
            val tag = customData.copyTag()
            tag.remove(TrueScreenSettings.namespace + "clipboard")
            if (!Strings.isNullOrEmpty(text)) {
              tag.putString(TrueScreenSettings.namespace + "clipboard", text)
            }
            CustomData.of(tag)
          }
        )
      }
    }

    private def sendMouseEvent(player: Player, name: String, x: Double, y: Double, data: Int): Unit = {
      val args = mutable.ArrayBuffer.empty[AnyRef]

      args += player
      args += name
      if (owner.precisionMode) {
        args += Double.box(x)
        args += Double.box(y)
      }
      else {
        args += Int.box(x.toInt + 1)
        args += Int.box(y.toInt + 1)
      }
      args += Int.box(data)
      if (TrueScreenSettings.inputUsername) {
        args += player.getName.getString
      }

      owner.node.sendToReachable("computer.checked_signal", args.toSeq: _*)
    }

    private def sendToKeyboards(name: String, values: AnyRef*): Unit = {
      owner.host match {
        case screen: com.hdf.cryptand.neoforge.truescreen.common.blockentity.Screen =>
          screen.screens.foreach(_.node.sendToNeighbors(name, values: _*))
        case _ =>
          owner.node.sendToNeighbors(name, values: _*)
      }
    }
  }

}
