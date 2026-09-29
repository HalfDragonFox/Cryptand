/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/blockentity/traits/TextBuffer.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits

import com.hdf.cryptand.neoforge.truescreen.TrueScreenSettings
import com.hdf.cryptand.neoforge.truescreen.common.datacomponents.CompoundStorage
import li.cil.oc.api
import li.cil.oc.api.internal
import li.cil.oc.api.network.Node
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.DataComponentHolder
import net.minecraft.nbt.{CompoundTag, NbtOps}

/**
 * 方块实体 ↔ 屏幕组件（buffer）的桥。
 *
 * <h3>★ 最关键的一处改动（任务 A 存在的核心理由之一）</h3>
 * OC 的 `buffer` 是这样拿的：
 * {{{
 *   val screenItem = api.Items.get(Constants.BlockName.ScreenTier1).createItemStack(1)
 *   val buffer = api.Driver.driverFor(screenItem, getClass).createEnvironment(screenItem, this)…
 * }}}
 * 即"拿 OC 的 1 档屏物品去问驱动表"。**我们不能这么写**：`driverFor` 只认 OC 自己的屏物品，
 * 为了让它返回东西就得让我们的屏物品也走 OC 的 `DriverScreen` —— 那样懒加载出来的 buffer 就是
 * **OC 的** `component.TextBuffer`（第二套实现），而不是我们移植的这一套。
 * ⇒ 这里直接 `new component.Screen(this)`（tier &gt; 0 的真实屏幕）或 `new component.TextBuffer(this)`
 * （非屏幕宿主，例如插在机架里的屏物品），与 OC 的 `DriverScreen.createEnvironment` 分支逐字一致，
 * 只是**不再经过 OC 的驱动表**。
 *
 * <h3>其它改动</h3>
 * <ul>
 *   <li>`Settings.screenResolutionsByTier/screenDepthsByTier` → `TrueScreenSettings.*`。</li>
 *   <li>Create 移动缓存（`registerMovingClientBuffer`/`unregisterMovingClientBuffer`）去掉。</li>
 *   <li>外部存档文件（`SaveHandler`）去掉，缓冲一律内联在 BE 的 NBT 里。</li>
 * </ul>
 */
trait TextBuffer extends Environment with Tickable {
  private final val ClientBufferComponentsTag = TrueScreenSettings.namespace + "clientBufferComponents"
  private var pendingServerBufferData: CompoundTag = _

  /** 客户端侧延迟数据（BE 描述包在 level 赋值前到达；见 loadForClient 注释）。 */
  private var pendingClientBufferData: CompoundTag = _

  lazy val buffer: internal.TextBuffer = {
    val buffer: internal.TextBuffer = this match {
      // 与 OC DriverScreen.createEnvironment 的分支一致：真实屏幕（tier>0）→ component.Screen（
      //  额外带"反转触摸模式"两个回调）；其它宿主 → 纯 TextBuffer 组件。
      case screen: com.hdf.cryptand.neoforge.truescreen.common.blockentity.Screen if screen.tier > 0 =>
        new com.hdf.cryptand.neoforge.truescreen.common.component.Screen(screen)
      case _ =>
        new com.hdf.cryptand.neoforge.truescreen.common.component.TextBuffer(this)
    }
    val (maxWidth, maxHeight) = TrueScreenSettings.screenResolutionsByTier(tier)
    buffer.setMaximumResolution(maxWidth, maxHeight)
    buffer.setMaximumColorDepth(TrueScreenSettings.screenDepthsByTier(tier))
    buffer
  }

  override def node: Node = buffer.node

  def tier: Int

  override def updateEntity(): Unit = {
    ensureServerBufferLoaded()
    ensureClientBufferLoaded()
    super.updateEntity()
    if (isClient || isConnected) {
      buffer.update()
    }
  }

  // ----------------------------------------------------------------------- //

  private def reapplyTierToBuffer(): Unit = {
    // Re-apply tier-based limits before loading data. This guards against the
    // case where the `buffer` lazy val was forced before `load(nbt)` ran
    // (e.g. during network join scheduled by initialize()), which would leave
    // it initialised with the default tier-0 (OneBit) depth even for a
    // higher-tier screen.  setMaximumColorDepth only updates the `maxDepth`
    // field; it does NOT touch the already-constructed data buffer, so calling
    // it again here is safe and idempotent.
    val (maxWidth, maxHeight) = TrueScreenSettings.screenResolutionsByTier(tier)
    buffer.setMaximumResolution(maxWidth, maxHeight)
    buffer.setMaximumColorDepth(TrueScreenSettings.screenDepthsByTier(tier))
  }

  override def loadComponentsForClient(holder: DataComponentHolder): Unit = {
    super.loadComponentsForClient(holder)
    // BaseBlockEntity loads the legacy/client NBT before it loads OC's
    // components, so tier still has the constructor default during loadForClient.
    // Do this again after Screen.loadComponentsCommon has supplied the real tier.
    reapplyTierToBuffer()
  }

  override def loadComponentsForServer(holder: DataComponentHolder): Unit = {
    super.loadComponentsForServer(holder)
    reapplyTierToBuffer()
  }

  override def loadForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.loadForServer(nbt, provider)
    // BlockEntity.loadStatic calls loadWithComponents/loadAdditional before it
    // assigns the level. Loading the screen contents needs the position/dimension
    // for external saves in OC; we persist inline, but the level is still needed
    // for the registry access used by the codec paths, so keep the same deferral.
    if (getLevel == null) pendingServerBufferData = nbt.copy()
    else loadServerBufferData(nbt, provider)
  }

  private def loadServerBufferData(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    reapplyTierToBuffer()
    buffer.loadData(nbt, provider)
    // loadData also restores MAX_VIDEO_MODE when it is present. A stale save
    // can contain the constructor's tier-one limits, so enforce the physical
    // screen tier again after loading persisted data as well.
    reapplyTierToBuffer()
  }

  /**
   * Consume deferred buffer data as soon as this screen has a level.
   *
   * Block entity lifecycle ordering is not identical for every host around a
   * screen (rack-mounted servers are reconstructed through the rack's item
   * inventory). Do not rely on initialize() being the only opportunity to
   * restore the buffer: exposing or saving the freshly constructed default
   * buffer first would overwrite the real contents with a blank T1 buffer.
   */
  private[truescreen] def ensureServerBufferLoaded(): Unit = {
    if (isServer && getLevel != null && pendingServerBufferData != null) {
      val data = pendingServerBufferData
      loadServerBufferData(data, getLevel.registryAccess())
      pendingServerBufferData = null
    }
  }

  override def onLoad(): Unit = {
    super.onLoad()
    ensureServerBufferLoaded()
    ensureClientBufferLoaded()
  }

  override protected def initialize(): Unit = {
    super.initialize()
    ensureServerBufferLoaded()
    ensureClientBufferLoaded()
  }

  override def saveForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    ensureServerBufferLoaded()
    super.saveForServer(nbt, provider)
    // If the level is still unavailable, retain the existing auxiliary file
    // instead of replacing it with the lazy buffer's blank default state.
    if (pendingServerBufferData == null) buffer.saveData(nbt, provider)
  }

  override def loadForClient(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.loadForClient(nbt, provider)
    reapplyTierToBuffer()
    // ⚠ 客户端也要等 level 就位：`TextBuffer.loadData` 的客户端分支会 `registerClientBuffer(this, level)`，
    //   而 `registerClientBuffer` 在 level == null 时**早退（连日志都没有）**；
    //   而 BE 描述包是在 level 赋值**之前**应用的（与 loadForServer 同一个时序问题）。
    //   真机症状：客户端索引里永远没有这块屏 ⇒ 所有屏幕报文按「未知地址」丢弃 ⇒ 屏面全黑。
    if (getLevel == null) pendingClientBufferData = nbt.copy()
    else loadClientBufferData(nbt, provider)
  }

  private def loadClientBufferData(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    if (nbt.contains(ClientBufferComponentsTag)) {
      val storage = CompoundStorage.CODEC.parse(NbtOps.INSTANCE, nbt.get(ClientBufferComponentsTag)).getOrThrow()
      buffer.loadData(storage)
    }
    else buffer.loadData(nbt, provider)
    // The saved MAX_VIDEO_MODE may be stale or may have been produced while
    // this block entity still had its constructor-default tier.
    reapplyTierToBuffer()
  }

  /** 客户端侧延迟加载（对齐 {@link #ensureServerBufferLoaded}，理由见 loadForClient 注释）。 */
  private[truescreen] def ensureClientBufferLoaded(): Unit = {
    if (isClient && getLevel != null && pendingClientBufferData != null) {
      val data = pendingClientBufferData
      loadClientBufferData(data, getLevel.registryAccess())
      pendingClientBufferData = null
    }
  }

  override def saveForClient(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.saveForClient(nbt, provider)
    val storage = new CompoundStorage()
    buffer.saveData(storage)
    nbt.put(ClientBufferComponentsTag, CompoundStorage.CODEC.encodeStart(NbtOps.INSTANCE, storage).getOrThrow())
  }
}
