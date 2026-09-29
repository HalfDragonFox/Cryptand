/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/blockentity/traits/BaseBlockEntity.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits

import com.hdf.cryptand.neoforge.truescreen.{TrueScreenLog, TrueScreenSettings}
import com.hdf.cryptand.neoforge.truescreen.util.{BlockPosition, SideTracker}
import li.cil.oc.api.Persistable
import li.cil.oc.api.datacomponents.{MutableNbtComponentHolder, NbtComponentHolder}
import net.minecraft.core.HolderLookup
import net.minecraft.core.component.{DataComponentHolder, DataComponentMap, DataComponentPatch, DataComponentType, PatchedDataComponentMap}
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.neoforged.api.distmarker.{Dist, OnlyIn}
import net.neoforged.neoforge.common.MutableDataComponentHolder
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState

/**
 * 屏幕方块实体的公共基类。
 *
 * <h3>相对 OC 原文件砍掉了什么（最小替代，逐条）</h3>
 * <ul>
 *   <li><b>Create 载具"随行方块实体"</b>（`movingLevel/movingPosition/movingRotation/movingState`、
 *       `beginMoving/updateMovingPosition/endMoving/movingBlockPos/movingBlockState/movingDirection/
 *       movingNeighbor`）：那是 OC 为 Create 的 contraption 做的集成。本移植不搬 Create 集成，
 *       于是 `isMoving` 恒为 false；原来围着它写的一堆分支在移植件里被直接去掉（不是留空壳）。</li>
 *   <li><b>外部存档文件（SaveHandler）</b>：OC 把超大屏的字符缓冲写到维度目录下的
 *       `&lt;address&gt;_buffer` 文件（`SaveHandler.scheduleSave/loadNBT`，以及 `savingForClients`
 *       这个开关）。本移植改为**只走方块实体 NBT 内联**：屏幕缓冲随 BE 的 NBT 一起存/读，
 *       不再依赖外挂文件 ⇒ 没有第二个真相来源，也少一个"文件在但 BE 没加载"的失败模式。</li>
 *   <li><b>`Sound.stopLoop(this)`</b>：OC 在客户端 dispose 时停循环音效。屏幕闭包里没有音效，
 *       `dispose` 因此是空的（保留方法本身，因为子类会 override）。</li>
 *   <li>`OpenComputers.log` → `TrueScreenLog.log`。
 * </ul>
 *
 * <h3>保留的机制（都是"数据"性质，不牵涉渲染/报文）</h3>
 * DataComponent 的存读路径逐字保留：BE 的组件状态在服务端存进自己的 NBT
 * （`cryptand:components` 子标签，由 OC 的 api `MutableNbtComponentHolder` 提供），
 * 更新包（`getUpdateTag`）里带一份"给客户端的快照"。这条路径是**原版 MC 的 BE 同步**，
 * 不是 OC 的自定义报文链，所以留在任务 A。
 */
trait BaseBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity {
  private final val IsServerDataTag = TrueScreenSettings.namespace + "isServerData"

  /** 诊断计数（2026-09-27，真机验证用；不作为功能路径）。 */
  private var diagClientLoads = 0L
  private var diagOutbound = 0L

  def x: Int = getBlockPos.getX

  def y: Int = getBlockPos.getY

  def z: Int = getBlockPos.getZ

  def position = BlockPosition(x, y, z, getLevel)

  def isClient: Boolean = !isServer

  def isServer: Boolean = if (getLevel != null) !getLevel.isClientSide else SideTracker.isServer()

  // ----------------------------------------------------------------------- //

  def updateEntity(): Unit = {
    // OC 用这个开关周期性强制刷新方块光照（屏幕是发光方块，光照有时不更新）。
    if (TrueScreenSettings.periodicallyForceLightUpdate && getLevel.getGameTime % 40 == 0 &&
      getBlockState.getLightEmission(getLevel, getBlockPos) > 0) {
      getLevel.sendBlockUpdated(getBlockPos, getLevel.getBlockState(getBlockPos), getLevel.getBlockState(getBlockPos), 3)
    }
  }

  override def clearRemoved(): Unit = {
    super.clearRemoved()
    initialize()
  }

  override def setRemoved(): Unit = {
    super.setRemoved()
    dispose()
  }

  override def onChunkUnloaded(): Unit = {
    super.onChunkUnloaded()
    try dispose() catch {
      case t: Throwable => TrueScreenLog.log.error("方块实体 dispose 失败，可能导致泄漏", t)
    }
  }

  protected def initialize(): Unit = {
  }

  def dispose(): Unit = {
  }

  // ----------------------------------------------------------------------- //

  def loadForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {}

  def saveForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    nbt.putBoolean(IsServerDataTag, true)
  }

  def loadForClient(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {}

  def saveForClient(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    nbt.putBoolean(IsServerDataTag, false)
  }

  def loadComponentsCommon(holder: DataComponentHolder): Unit = {}
  def saveComponentsCommon(holder: MutableDataComponentHolder): Unit = {}
  def loadComponentsForServer(holder: DataComponentHolder): Unit = {}
  def saveComponentsForServer(holder: MutableDataComponentHolder): Unit = {}

  @OnlyIn(Dist.CLIENT)
  def loadComponentsForClient(holder: DataComponentHolder): Unit = {}

  // The dedicated server writes this client-facing snapshot into update tags
  // sent to clients. Only loading the snapshot is client-only.
  def saveComponentsForClient(holder: MutableDataComponentHolder): Unit = {}

  // ----------------------------------------------------------------------- //

  /**
   * 读组件（tier / 颜色 / 显存快照…）。
   *
   * <h3>⚠ 为什么挂在 loadAdditional 上，而不是 loadWithComponents</h3>
   * OC 在 .ai_cache 里的那份源码是 `override def loadWithComponents`。**本仓库的 NeoForge 上不行**：
   * `javap net.minecraft.world.level.block.entity.BlockEntity`（neoforge-21.1.231，见 gradle.properties
   * `neoforge_version=21.1.231`）给出
   * {{{
   *   public final void loadWithComponents(CompoundTag, HolderLookup$Provider);
   * }}}
   * 它是 **final**，实现是「先调 `loadAdditional`，再把 tag 里的组件表应用回 BE 自己的 components」。
   * 所以唯一可 override 的钩子就是 `loadAdditional` —— 我们读自己的组件子标签（`NbtComponentHolder`），
   * 顺序与语义与 OC 完全一致；BE 自己那张组件表我们不使用（屏幕状态全部走下面的子标签），
   * 因此 final 方法最后那一步对我们是空操作。
   *
   * ⚠ 这也印证了用户的既定纪律：**接口签名以实际依赖 jar 的 javap 为准，不要照抄 .ai_cache 的新源码**
   *    （源码版本 ≠ 依赖版本）。
   */
  override def loadAdditional(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.loadAdditional(nbt, provider)
    if (isServer || nbt.getBoolean(IsServerDataTag)) {
      loadForServer(nbt, provider)
    } else {
      loadForClient(nbt, provider)
      // 诊断（2026-09-27）：客户端到底有没有收到描述包（决定客户端的屏幕注册/渲染）。
      diagClientLoads += 1L
      if (diagClientLoads <= 3L || (diagClientLoads % 200L) == 0L) {
        TrueScreenLog.log.info("[TrueScreen/CLIENT] 描述包到达并走客户端分支（累计 {}，pos {}）",
          java.lang.Long.valueOf(diagClientLoads), getBlockPos)
      }
    }

    // Client update tags carry the render-only component snapshot in NBT;
    // persistent block entity components are only available directly when
    // loading on the server.
    val holder: DataComponentHolder =
      if (isServer) {
        // OC's data components are persistent block-entity state, not merely
        // item-transfer components. Once a block has been saved to disk, read
        // the serialized component patch from its NBT. For a freshly placed
        // block there is no NBT snapshot yet, so fall back to the component
        // map Minecraft applied from the placing ItemStack.
        if (NbtComponentHolder.hasComponents(nbt))
          new NbtComponentHolder(nbt, provider)
        else
          Persistable.holder(this)
      }
      else new NbtComponentHolder(nbt, provider)

    loadComponentsCommon(holder)

    if (isServer) {
      loadComponentsForServer(holder)
    } else {
      loadComponentsForClient(holder)
    }
  }

  override def saveAdditional(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.saveAdditional(nbt, provider)
    save(nbt, provider)

    if (isServer) {
      // Data components on a BlockEntity are not automatically chunk-persistent.
      // Serialize OC's persistent component snapshot into the block entity NBT.
      // saveForServer may itself persist component-backed state into this NBT
      // (notably TextBuffer, whose node address is required to reconnect the
      // screen and locate its external buffer save). Seed this holder from the
      // NBT we have already written instead of starting empty, otherwise this
      // second component write replaces the component tag and silently
      // drops everything written by saveForServer.
      val holder = new MutableNbtComponentHolder(nbt, provider)
      saveComponentsCommon(holder)
      saveComponentsForServer(holder)
      holder.save(nbt, provider)

      // Keep the live BlockEntity component map in sync as well. Minecraft
      // uses this map when transferring block state to an ItemStack.
      val liveHolder = Persistable.holder(this)
      try liveHolder.applyComponents(holder.getComponents)
      finally liveHolder.close()
    }
    else {
      val holder = Persistable.holder(this)
      try {
        saveComponentsCommon(holder)
        saveComponentsForClient(holder)
      } finally {
        holder.close()
      }
    }
  }

  def save(nbt: CompoundTag, provider: HolderLookup.Provider): CompoundTag = {
    if (isServer) {
      saveForServer(nbt, provider)
    }
    nbt
  }

  override def getUpdatePacket: ClientboundBlockEntityDataPacket = {
    ClientboundBlockEntityDataPacket.create(this)
  }

  override def getUpdateTag(provider: HolderLookup.Provider): CompoundTag = {
    val nbt = super.getUpdateTag(provider)

    try {
      try {
        this match {
          case screen: com.hdf.cryptand.neoforge.truescreen.common.blockentity.Screen => screen.saveForClientDirect(nbt, provider)
          case _ => saveForClient(nbt, provider)
        }
      } catch {
        case e: Throwable => TrueScreenLog.log.warn("写方块实体描述包失败", e)
      }

      try {
        // Preserve any component-backed state saveForClient already placed in
        // the update NBT for the same reason as the server save path above.
        val holder = new MutableNbtComponentHolder(nbt, provider)
        saveComponentsCommon(holder)
        saveComponentsForClient(holder)
        holder.save(nbt, provider)
      } catch {
        case e: Throwable => TrueScreenLog.log.warn("写方块实体客户端组件失败", e)
      }
    } finally {
    }

    diagOutbound += 1L
    if (diagOutbound <= 3L || (diagOutbound % 200L) == 0L) {
      TrueScreenLog.log.info("[TrueScreen/SERVER] 出站描述包（累计 {}，pos {}）：isServerData={}",
        java.lang.Long.valueOf(diagOutbound), getBlockPos,
        java.lang.Boolean.valueOf(nbt.getBoolean(IsServerDataTag)))
    }

    nbt
  }

  override def onDataPacket(manager: Connection, packet: ClientboundBlockEntityDataPacket, provider: HolderLookup.Provider): Unit = {
    try loadWithComponents(packet.getTag, provider) catch {
      case e: Throwable => TrueScreenLog.log.warn("读方块实体描述包失败", e)
    }
  }
}
