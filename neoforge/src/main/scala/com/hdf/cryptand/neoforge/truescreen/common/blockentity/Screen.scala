/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/blockentity/Screen.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.blockentity

import com.hdf.cryptand.neoforge.truescreen.{TrueScreenContent, TrueScreenSettings}
import com.hdf.cryptand.neoforge.truescreen.common.{Tier, blockentity}
import com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits.RedstoneChangedEventArgs
import com.hdf.cryptand.neoforge.truescreen.common.component.TextBuffer
import com.hdf.cryptand.neoforge.truescreen.common.TrueScreenGraphics
import com.hdf.cryptand.neoforge.truescreen.network.ServerPacketSender
import com.hdf.cryptand.neoforge.truescreen.common.datacomponents.TrueScreenComponents
import com.hdf.cryptand.neoforge.truescreen.client.renderer.block.ScreenModel
import com.hdf.cryptand.neoforge.truescreen.util.BlockPosition
import com.hdf.cryptand.neoforge.truescreen.util.Color
import com.hdf.cryptand.neoforge.truescreen.util.ExtendedDataComponentHolder._
import com.hdf.cryptand.neoforge.truescreen.util.ExtendedLevel._
import li.cil.oc.api.network.Analyzable
import li.cil.oc.api.network._
import li.cil.oc.common.blockentity.{Keyboard => OcKeyboard}
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.core.{BlockPos, Direction, HolderLookup}
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.entity.{BlockEntity, BlockEntityType}
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.api.distmarker.Dist
import net.neoforged.api.distmarker.OnlyIn
import net.minecraft.world.phys.AABB
import net.neoforged.neoforge.client.model.data.ModelData
import net.neoforged.neoforge.common.MutableDataComponentHolder
import net.neoforged.neoforge.common.extensions.IBlockEntityExtension

import scala.collection.mutable
import scala.language.postfixOps
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.projectile.Arrow
import net.minecraft.world.entity.player.Player

/**
 * 屏幕方块实体：拼合（multi-block）、坐标换算、点击/踩踏/箭矢、组件存读档。
 *
 * <h3>相对 OC 原文件的最小替代（逐条）</h3>
 * <ul>
 *   <li>`BlockEntityTypes.SCREEN.get()` → `TrueScreenContent.SCREEN_BE.get()`
 *       （我方 `cryptand:` 注册的 BE 类型；capability 也由我方注册，见 TrueScreenContent）。</li>
 *   <li>`Settings.get.*` → `TrueScreenSettings.*`（拼合上限 / 耗电）。</li>
 *   <li>`OCComponents.*` → `TrueScreenComponents.*`（我方注册的屏幕相关 component）。</li>
 *   <li>`getModelData`（把 `ScreenModel.SCREEN_PROPERTY` 塞进模型数据）**整段去掉** ——
 *       那是渲染（烘焙模型/BER）用的属性，属任务 B 的领地；继承 `Environment.getModelData`
 *       的 `ModelData.EMPTY`。任务 B 要加回来时就在这个类里加一个 override
 *       （OC 原实现见 .ai_cache/OpenComputers .../common/blockentity/Screen.scala 的 getModelData）。</li>
 *   <li>`client.gui.Screen`（dispose 时关掉右键窗口）→ **任务 C 已落地**：窗口是
 *       `client/gui/TrueScreenWindow`（上游 client/gui/Screen 的屏幕子集，键码翻译按最小替代）。</li>
 *   <li>`updateMergedModels`（拼合变化后把涉及的区块标记为待重绘）**保留**：它不画任何东西，
 *       只是让客户端重算区块网格；任务 B 的 BER/模型接管依赖它。这是渲染的**失效通知**，
 *       不是渲染实现。</li>
 *   <li>`Thread.sleep` 级的线程纪律：`click/walk/shot/copyToAnalyzer` 只调组件的数据方法
 *       （最终变成网络信号或"待发报文"），**不碰 Level、不写方块**。真正的世界写操作
 *       （`setChanged`/区块脏标记/neighborChanged）只发生在 `updateEntity`（服务端 tick）里。
 *       ⚠ 组件回调跑在 OC 的机器线程上，所以这条纪律必须保持。</li>
 * </ul>
 */
class Screen(pos: BlockPos, state: BlockState, var tier: Int,
             /** 该屏的 LOD 阶梯（注册时由方块传入；效果实现全在 com.hdf.cryptand.lod）。 */
             val lodStairs: com.hdf.cryptand.lod.LodStairs = com.hdf.cryptand.lod.LodStairs.defaults())
    extends BlockEntity(TrueScreenContent.SCREEN_BE.get(), pos, state)
  with traits.TextBuffer with SidedEnvironment with traits.Rotatable with traits.RedstoneAware with traits.Colored with Analyzable with Ordered[Screen]
  with IBlockEntityExtension {
  def this(pos: BlockPos, state: BlockState) = this(pos, state, 0)

  /**
   * 本屏声明的像素色深（bpp）：1 / 8 / 16 / 24，按 tier 取
   * （见 `TrueScreenSettings.screenBppByTier`；用户定案 2026-09-29）。
   *
   * 这是**屏自己**的声明：生产者（GPU）据此编码帧，不假设 16bpp。
   */
  def declaredBpp: Int = {
    val tierIndex = math.max(0, math.min(TrueScreenSettings.screenBppByTier.length - 1, tier))
    TrueScreenSettings.screenBppByTier(tierIndex)
  }

  // Enable redstone functionality.
  _isOutputEnabled = true

  override def validFacings = Direction.values

  /**
   * ★ 任务 B：把本 BE 塞进 ModelData，好让烘焙模型（ScreenModel）知道"这块屏由哪个 BE
   * 提供外观与首/中/尾 UV 分段"。上游逐字实现（唯一的形态差异是这行链式调用的换行）：
   * {{{
   *   ModelData.builder().`with`(ScreenModel.SCREEN_PROPERTY, this).build()
   * }}}
   * 触发时机：BaseBlockEntity/Environment 的 requestModelDataUpdate（拼合变化、tier 变化）——
   * 上游的拼合失效通知（blockentity.Screen.updateMergedModels）就是靠它把区块标脏后重取模型数据。
   */
  @OnlyIn(Dist.CLIENT)
  override def getModelData: ModelData =
    ModelData.builder()
      .`with`(ScreenModel.SCREEN_PROPERTY, this)
      .build()

  // ----------------------------------------------------------------------- //

  /**
    * Check for multi-block screen option in next update. We do this in the
    * update to avoid unnecessary checks on chunk unload.
    */
  var shouldCheckForMultiBlock = true

  /**
    * On the client we delay connecting screens a little, to avoid glitches
    * when not all tile entity data for a chunk has been received within a
    * single tick (meaning some screens are still "missing").
    */
  var delayUntilCheckForMultiBlock = 40

  var width, height = 1

  var cachedBounds: Option[(Boolean, Int, Int, AABB)] = None

  var origin = this

  val screens = mutable.Set(this)

  var hadRedstoneInput = false

  var invertTouchMode = false

  // ==================== 像素模式（GRAPHICS）的设备/通道（任务 D）====================
  //
  // 设备本体在 TrueScreenGraphics 里（common 的 TrueColorScreen，与服务端显存同一份）；
  // 本类只持有它，并在**主线程 tick** 里把生产者写好的帧交给报文层。
  // ⚠ lazy 由 synchronized 手工实现（Scala 的 lazy val 在这里够用，但显式同步更直观）：
  //   生产者（机器线程）与 tick（主线程）都可能第一次触发它。
  @volatile private var graphicsRef: TrueScreenGraphics = _

  /** 像素通道（不存在就建；可从机器线程调用）。 */
  def graphics: TrueScreenGraphics = this.synchronized {
    if (graphicsRef == null) {
      graphicsRef = new TrueScreenGraphics(this)
      registerOwnSink()
    }
    graphicsRef
  }

  /** 本屏在我们自己通道表里的 key（没登记过 = null）。 */
  private var ownSinkKey: String = _

  /**
   * 把本屏登记进我们自己的像素通道表（`TrueScreenGraphicsApi`）。
   *
   * <p>这是 rc_screen 改造的第一步：原先**只有** OC 的组件包装
   * （`common/component/TextBuffer` 的 onConnect）会登记通道，一旦字符驱动退役，
   * GPU 就再也找不到屏 ⇒ 登记必须由**方块实体自己**承担。
   * 目前两条路径并存（双保险），component 那条会随字符驱动一起退役。</p>
   *
   * <p>⚠ 时机局限：只在这里注册 ⇒ 若首次创建时拼接还没解析（`isOrigin` 尚为 false）就不会登记。
   * 过渡期由 component 那条兜住；改造后要在拼接解析处补一次注册。</p>
   */
  private def registerOwnSink(): Unit = {
    if (ownSinkKey == null && isOrigin && getLevel != null) {
      val dim = getLevel.dimension().location().toString
      ownSinkKey = com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsApi.keyFor(dim, getBlockPos)
      com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsApi.register(ownSinkKey, graphicsRef)
    }
  }

  override def setRemoved(): Unit = {
    if (ownSinkKey != null) {
      com.hdf.cryptand.neoforge.truescreen.TrueScreenGraphicsApi.unregister(ownSinkKey, graphicsIfAny)
      ownSinkKey = null
    }
    super.setRemoved()
  }

  /** 已经建过的像素通道（没建过 = null；**不**触发创建 —— 注销路径用它）。 */
  def graphicsIfAny: TrueScreenGraphics = graphicsRef

  private val arrows = mutable.Set.empty[Arrow]

  private val lastWalked = mutable.WeakHashMap.empty[Entity, (Int, Int)]

  setColor(Color.byTier(tier))

  @OnlyIn(Dist.CLIENT)
  override def canConnect(side: Direction) = side != facing

  // Allow connections from front for keyboards, and keyboards only...
  // 正面（屏幕面）本来不许接网络线，**只有键盘**例外 —— 见 OC 同名实现。
  // ⚠ 这里直接认 **OC 自己的键盘方块实体** `li.cil.oc.common.blockentity.Keyboard`：
  //   键盘子系统（键盘 BE/组件/驱动/方块）不在任务 A 的范围里，也**不该**出现第二套键盘；
  //   直接引用 OC 的类型 ⇒ 玩家在屏幕正面放 OC 键盘时，键事件照旧能从 OC 的键盘传给我们的屏幕。
  override def sidedNode(side: Direction) = if (side != facing || (getLevel.isLoaded(getBlockPos.relative(side)) && getLevel.getBlockEntity(getBlockPos.relative(side)).isInstanceOf[OcKeyboard])) node else null

  // ----------------------------------------------------------------------- //

  def isOrigin = origin == this

  def getRenderBoundingBox: AABB = {
    cachedBounds match {
      case Some((o, w, h, b)) if o == isOrigin && w == width && h == height => b
      case _ =>
        val bb = if ((width == 1 && height == 1) || !isOrigin) new AABB(getBlockPos) else {
          val size = unproject(width - 1, height - 1, 0)
          new AABB(getBlockPos).expandTowards(size.x, size.y, size.z)
        }
        cachedBounds = Some((isOrigin, width, height, bb))
        bb
    }
  }

  def localPosition = {
    val lpos = project(this)
    val opos = project(origin)
    (lpos.x - opos.x, lpos.y - opos.y)
  }

  def hasKeyboard = screens.exists(screen =>
    Direction.values.map(side => (side, {
      val blockPos = BlockPosition(screen).offset(side)
      if (getLevel.blockExists(blockPos)) getLevel.getBlockEntity(blockPos.toBlockPos)
      else null
    })).exists {
      case (side, keyboard: OcKeyboard) => keyboard.hasNodeOnSide(side.getOpposite)
      case _ => false
    })

  def checkMultiBlock(): Unit = {
    shouldCheckForMultiBlock = true
    width = 1
    height = 1
    origin = this
    screens.clear()
    screens += this
    invertTouchMode = false
  }

  def toScreenCoordinates(hitX: Double, hitY: Double, hitZ: Double): (Boolean, Option[(Double, Double)]) = {
    // Compute absolute position of the click on the face, measured in blocks.
    def dot(f: Direction) = f.getStepX * hitX + f.getStepY * hitY + f.getStepZ * hitZ
    val (hx, hy) = (dot(toGlobal(Direction.EAST)), dot(toGlobal(Direction.UP)))
    val tx = if (hx < 0) 1 + hx else hx
    val ty = 1 - (if (hy < 0) 1 + hy else hy)
    val (lx, ly) = localPosition
    val (ax, ay) = (lx + tx, height - 1 - ly + ty)

    // Get the relative position in the *display area* of the face.
    val border = 2.25 / 16.0
    if (ax <= border || ay <= border || ax >= width - border || ay >= height - border) {
      return (false, None)
    }
    if (!getLevel.isClientSide) return (true, None)

    val (iw, ih) = (width - border * 2, height - border * 2)
    val (rx, ry) = ((ax - border) / iw, (ay - border) / ih)

    // Make it a relative position in the displayed buffer.
    val bw = origin.buffer.getViewportWidth
    val bh = origin.buffer.getViewportHeight
    val (bpw, bph) = (origin.buffer.renderWidth / iw.toDouble, origin.buffer.renderHeight / ih.toDouble)
    // TODO(任务 B —— L5 渲染)：上面的 renderWidth/renderHeight 来自 org.lazy... 即
    //   `component.TextBuffer.renderWidth/renderHeight`，OC 实现里乘了"一个字符格的像素尺寸"
    //   （TextBufferRenderCache.renderer.charRenderWidth）。本任务把 ClientProxy 渲染桩成
    //   "0 像素"，于是 bpw/bph 目前恒为 (0,0) 相等 ⇒ 走下面的 else 分支 (rx, ry)。
    //   任务 B 把 renderWidth/Height 换成"VRAM 帧像素尺寸"后，这段宽高比裁剪逻辑就自动生效，
    //   **不需要**再改这里。
    val (brx, bry) = if (bpw > bph) {
      val rh = bph.toDouble / bpw.toDouble
      val bry = (ry - (1 - rh) * 0.5) / rh
      (rx, bry)
    }
    else if (bph > bpw) {
      val rw = bpw.toDouble / bph.toDouble
      val brx = (rx - (1 - rw) * 0.5) / rw
      (brx, ry)
    }
    else {
      (rx, ry)
    }

    val inBounds = bry >= 0 && bry <= 1 && brx >= 0 || brx <= 1
    (inBounds, Some((brx * bw, bry * bh)))
  }

  def copyToAnalyzer(hitX: Double, hitY: Double, hitZ: Double): Boolean = {
    val (inBounds, coordinates) = toScreenCoordinates(hitX, hitY, hitZ)
    coordinates match {
      case Some((x, y)) => origin.buffer match {
        case buffer: TextBuffer =>
          buffer.copyToAnalyzer(y.toInt, null)
          true
        case _ => false
      }
      case _ => inBounds
    }
  }

  def click(hitX: Double, hitY: Double, hitZ: Double): Boolean = {
    val (inBounds, coordinates) = toScreenCoordinates(hitX, hitY, hitZ)
    coordinates match {
      case Some((x, y)) =>
        // Send the packet to the server (manually, for accuracy).
        origin.buffer.mouseDown(x, y, 0, null)
        true
      case _ => inBounds
    }
  }

  def walk(entity: Entity): Unit = {
    val (x, y) = localPosition
    origin.lastWalked.put(entity, localPosition) match {
      case Some((oldX, oldY)) if oldX == x && oldY == y => // Ignore
      case _ => entity match {
        case player: Player if TrueScreenSettings.inputUsername =>
          origin.node.sendToReachable("computer.signal", "walk", Int.box(x + 1), Int.box(height - y), player.getName.getString)
        case _ =>
          origin.node.sendToReachable("computer.signal", "walk", Int.box(x + 1), Int.box(height - y))
      }
    }
  }

  def shot(arrow: Arrow): Unit = {
    arrows.add(arrow)
  }

  // ----------------------------------------------------------------------- //

  override def updateEntity(): Unit = {
    super.updateEntity()
    if (shouldCheckForMultiBlock && ((isClient && isClientReadyForMultiBlockCheck) || (isServer && isConnected))) {
      // Make sure we merge in a deterministic order, to avoid getting
      // different results on server and client due to the update order
      // differing between the two. This also saves us from having to save
      // any multi-block specific state information.
      val pending = mutable.SortedSet(this)
      val queue = mutable.Queue(this)
      while (queue.nonEmpty) {
        val current = queue.dequeue()
        val lpos = project(current)
        def tryQueue(dx: Int, dy: Int): Unit = {
          val npos = unproject(lpos.x + dx, lpos.y + dy, lpos.z)
          if (getLevel.blockExists(npos)) getLevel.getBlockEntity(npos.toBlockPos) match {
            case s: Screen if s.pitch == pitch && s.yaw == yaw && pending.add(s) => queue += s
            case _ => // Ignore.
          }
        }
        tryQueue(-1, 0)
        tryQueue(1, 0)
        tryQueue(0, -1)
        tryQueue(0, 1)
      }
      // Perform actual merges.
      while (pending.nonEmpty) {
        val current = pending.firstKey
        while (current.tryMerge()) {}
        current.screens.foreach {
          screen =>
            screen.shouldCheckForMultiBlock = false
            pending.remove(screen)
            queue += screen
        }
      }
      if (isClient) updateMergedModels()
      // Update visibility after everything is done, to avoid noise.
      queue.foreach(screen => {
        val buffer = screen.buffer
        if (screen.isOrigin) {
          if (isServer) {
            buffer.node.asInstanceOf[Component].setVisibility(Visibility.Network)
            buffer.setEnergyCostPerTick(TrueScreenSettings.screenCost * screen.width * screen.height)
            buffer.setAspectRatio(screen.width, screen.height)
          }
        }
        else {
          if (isServer) {
            buffer.node.asInstanceOf[Component].setVisibility(Visibility.None)
            buffer.setEnergyCostPerTick(TrueScreenSettings.screenCost)
          }
          buffer.setAspectRatio(1, 1)
          val w = buffer.getWidth
          val h = buffer.getHeight
          buffer.setForegroundColor(0xFFFFFF, false)
          buffer.setBackgroundColor(0x000000, false)
          buffer.fill(0, 0, w, h, 0x20)
        }
      })
    }

    // 像素模式（GRAPHICS）：宿主渲染一拍 + 把生产者写好的帧发给附近玩家。
    //
    //  · renderTick()：**帧号/跳过数的唯一推进点**，与旧自研屏同口径（已删除的 TrueScreenBlockEntity 里每 tick 调
    //    device.renderTick()）。不加这一步，服务端设备的 frames 永远是 0（真机日志就表现为 frames=0），
    //    虽然不影响上屏（客户端用 uploadPending 判定上传），但"锁生效/帧节流"这条诊断链就断了。
    //  · 发报文仍然只在**主线程 tick**（本方法）；生产者（OC 组件回调/机器线程）只写设备 + 置就绪位。
    if (isServer && isOrigin) {
      // ⚠ 必须是 graphicsIfAny：**没有 GPU 的屏不接入沙箱**（用户定案 2026-09-28）——
      //   没有真实图形通道就没有内容可发（沙箱侧只把它记成"未连接"）。
      //   之前用 graphics 按需建通道是错的：那等于让无 GPU 的屏也能被服务。
      // 接入规则按《接口准入》定案（2026-09-27 用户裁定 H）：
      //   · 屏幕 **有 GPU ⇒ 自动接入**（自动只限「外设 ↔ RV 芯片」）；
      //   · 手动连接表优先，用于覆盖/禁用（硬件调节器写进 CPU 信息）—— 那一层落地前，
      //     这里保持"有 GPU 就供帧"的自动语义（未接入名单/覆盖规则随接口准入一起做）。
      val g = graphicsIfAny
      if (g != null) {
        g.renderTick()
        // 图像帧同步（2026-09-28 定案）：客户端**主动请求**（先全量、再增量），服务器只回答请求，
        // 且每台机器有回帧上限（默认 60fps，见 TrueScreenGraphics.frameLimiter）。
        // 旧的"每 tick 主动推送"（pendingFrame -> ScreenGraphics）已退役。
        g.takeImageRequest() match {
          case Some((full, stepW, stepH, playerId)) =>
            g.buildImageFrame(full, stepW, stepH, System.nanoTime()) match {
              case Some(frame) =>
                val server = level.getServer
                val player = if (server == null) null else server.getPlayerList.getPlayer(playerId)
                if (player != null) {
                  ServerPacketSender.sendScreenFrame(node.address, frame, player)
                }
              case None => // 没变化 / 被限帧：不回帧（客户端下一 tick 会再来）
            }
          case None =>
        }
      }
    }

    if (arrows.nonEmpty) {
      for (arrow <- arrows) {
        val hitX = arrow.getX - x
        val hitY = arrow.getY - y
        val hitZ = arrow.getZ - z
        arrow.getOwner match {
          case player: Player if player == Minecraft.getInstance.player => click(hitX, hitY, hitZ)
          case _ =>
        }
      }
      arrows.clear()
    }
  }

  /**
   * 拼合变化后把相关区块标记为待重绘（**不是**渲染实现：只是让客户端重算区块网格）。
   * 任务 B 的 BER/模型接管依赖这个失效通知。
   */
  private def updateMergedModels(): Unit = {
    if (getLevel == Minecraft.getInstance.level) {
      val renderer = Minecraft.getInstance.levelRenderer
      screens.foreach(screen => {
        val pos = screen.getBlockPos
        renderer.setSectionDirty(pos.getX >> 4, pos.getY >> 4, pos.getZ >> 4)
      })
    }
  }

  private def isClientReadyForMultiBlockCheck = if (delayUntilCheckForMultiBlock > 0) {
    delayUntilCheckForMultiBlock -= 1
    false
  } else true

  override def dispose(): Unit = {
    super.dispose()
    screens.clone().foreach(_.checkMultiBlock())
    // 客户端：方块实体被卸载时把还开着的右键窗口关掉（否则窗口会指着一块不存在的屏）。
    if (isClient) {
      Minecraft.getInstance.screen match {
        case screenGui: com.hdf.cryptand.neoforge.truescreen.client.gui.TrueScreenWindow if screenGui.buffer == origin.buffer =>
          screenGui.onClose()
        case _ =>
      }
    }
  }

  override protected def onColorChanged(): Unit = {
    super.onColorChanged()
    screens.clone().foreach(_.checkMultiBlock())
  }

  // ----------------------------------------------------------------------- //

  override def loadComponentsCommon(holder: DataComponentHolder): Unit = {
    for (t <- holder.getComponent(TrueScreenComponents.TIER)) tier = t
    setColor(Color.byTier(tier))
    super.loadComponentsCommon(holder)

    invertTouchMode = holder.has(TrueScreenComponents.INVERT_TOUCH.get())
  }

  override def saveComponentsCommon(holder: MutableDataComponentHolder): Unit = {
    holder.setComponent(TrueScreenComponents.TIER, tier.toByte)
    super.saveComponentsCommon(holder)
    holder.setComponent(TrueScreenComponents.INVERT_TOUCH, invertTouchMode)
  }

  override def loadComponentsForServer(holder: DataComponentHolder): Unit = {
    super.loadComponentsForServer(holder)
    hadRedstoneInput = holder.getComponent(TrueScreenComponents.HAS_REDSTONE_INPUT) getOrElse false
  }

  override def saveComponentsForServer(holder: MutableDataComponentHolder): Unit = {
    super.saveComponentsForServer(holder)
    holder.setComponent(TrueScreenComponents.HAS_REDSTONE_INPUT, hadRedstoneInput)
  }

  // Explicit entry point used by BaseBlockEntity.getUpdateTag. Scala trait
  // dispatch can otherwise skip TextBuffer.saveForClient, leaving clients
  // with an empty buffer (see OpenComputers-CE PR #6 for the 1.20 port).
  override def saveForClient(nbt: CompoundTag, provider: HolderLookup.Provider): Unit =
    saveForClientDirect(nbt, provider)

  def saveForClientDirect(nbt: CompoundTag, provider: HolderLookup.Provider): Unit =
    super.saveForClient(nbt, provider)

  override def loadComponentsForClient(holder: DataComponentHolder): Unit = {
    super.loadComponentsForClient(holder)
    requestModelDataUpdate()
  }

  // ----------------------------------------------------------------------- //

  override def onAnalyze(player: Player, side: Direction, hitX: Float, hitY: Float, hitZ: Float): Array[Node] = Array(origin.node)

  override protected def onRedstoneInputChanged(args: RedstoneChangedEventArgs): Unit = {
    super.onRedstoneInputChanged(args)
    val hasRedstoneInput = screens.map(_.maxInput).max > 0
    if (hasRedstoneInput != hadRedstoneInput) {
      hadRedstoneInput = hasRedstoneInput
      if (hasRedstoneInput) {
        origin.buffer.setPowerState(!origin.buffer.getPowerState)
      }
    }
  }

  override def onRotationChanged(): Unit = {
    super.onRotationChanged()
    screens.clone().foreach(_.checkMultiBlock())
  }

  // ----------------------------------------------------------------------- //

  override def compare(that: Screen): Int =
    if (x != that.x) x - that.x
    else if (y != that.y) y - that.y
    else z - that.z

  // ----------------------------------------------------------------------- //

  private def tryMerge(): Boolean = {
    val opos = project(origin)
    def tryMergeTowards(dx: Int, dy: Int): Boolean = {
      val npos = unproject(opos.x + dx, opos.y + dy, opos.z)
      getLevel.blockExists(npos) && (getLevel.getBlockEntity(npos.toBlockPos) match {
        case s: Screen if s.tier == tier && s.pitch == pitch && s.getColor == getColor && s.yaw == yaw && !screens.contains(s) =>
          val spos = project(s.origin)
          val canMergeAlongX = spos.y == opos.y && s.height == height && s.width + width <= TrueScreenSettings.maxScreenWidth
          val canMergeAlongY = spos.x == opos.x && s.width == width && s.height + height <= TrueScreenSettings.maxScreenHeight
          if (canMergeAlongX || canMergeAlongY) {
            val newOrigin =
              if (canMergeAlongX) {
                if (spos.x < opos.x) s.origin else origin
              }
              else {
                if (spos.y < opos.y) s.origin else origin
              }
            val (newWidth, newHeight) =
              if (canMergeAlongX) (width + s.width, height)
              else (width, height + s.height)
            val newScreens = screens ++ s.screens
            for (screen <- newScreens) {
              screen.width = newWidth
              screen.height = newHeight
              screen.origin = newOrigin
              screen.screens ++= newScreens // It's a set, so there won't be duplicates.
            }
            true
          }
          else false // Cannot merge.
        case _ => false
      })
    }
    tryMergeTowards(0, height) || tryMergeTowards(0, -1) || tryMergeTowards(width, 0) || tryMergeTowards(-1, 0)
  }

  private def project(t: Screen) = {
    def dot(f: Direction, s: Screen) = f.getStepX * s.x + f.getStepY * s.y + f.getStepZ * s.z
    BlockPosition(dot(toGlobal(Direction.EAST), t), dot(toGlobal(Direction.UP), t), dot(toGlobal(Direction.SOUTH), t))
  }

  private def unproject(x: Int, y: Int, z: Int) = {
    def dot(f: Direction) = f.getStepX * x + f.getStepY * y + f.getStepZ * z
    BlockPosition(dot(toLocal(Direction.EAST)), dot(toLocal(Direction.UP)), dot(toLocal(Direction.SOUTH)))
  }
}
