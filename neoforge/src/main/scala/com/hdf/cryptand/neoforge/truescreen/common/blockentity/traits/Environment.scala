/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/blockentity/traits/Environment.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits

import com.hdf.cryptand.neoforge.truescreen.TrueScreenSettings
import com.hdf.cryptand.neoforge.truescreen.common.TrueScreenServerTasks
import com.hdf.cryptand.neoforge.truescreen.util.ExtendedNBT._
import li.cil.oc.api.network
import li.cil.oc.api.network.Connector
import li.cil.oc.api.network.SidedEnvironment
import net.minecraft.nbt.CompoundTag
import net.minecraft.core.{Direction, HolderLookup}
import net.minecraft.world.level.Level

/**
 * "方块实体即 OC 环境/宿主"的胶水层。
 *
 * 改动（最小替代）：
 * <ul>
 *   <li>`EventHandler.scheduleServer(this)` → `TrueScreenServerTasks.scheduleNetworkJoin(this)`
 *       （OC 的 `EventHandler` 是全局服务端调度器，我们只留"延迟入网"这一件事）。</li>
 *   <li>Create 移动支持（`movingLevel/movingPosition`、`saveMovingState`、`disposeMoving`、
 *       `isMoving`）整段去掉：不搬 Create 集成。</li>
 *   <li>`getModelData` 保留返回 `ModelData.EMPTY`（屏幕模型属性是任务 B 的事，
 *       见 `common/blockentity/Screen.scala` 的 TODO）。</li>
 *   <li>删掉 OC 遗留的 `hasProperty/getData/setData`（1.16 时代的 ModelProperty 钩子，已无调用方）。</li>
 * </ul>
 */
trait Environment extends BaseBlockEntity with network.Environment with network.EnvironmentHost {
  protected var isChangeScheduled = false

  override def getEnvironmentLevel: Level = getLevel

  override def xPosition: Double = x + 0.5

  override def yPosition: Double = y + 0.5

  override def zPosition: Double = z + 0.5

  override def markChanged(): Unit = if (this.isInstanceOf[Tickable]) isChangeScheduled = true else this.setChanged()

  protected def isConnected: Boolean = node != null && node.address != null && node.network != null

  // ----------------------------------------------------------------------- //

  override protected def initialize(): Unit = {
    super.initialize()
    if (isServer) {
      TrueScreenServerTasks.scheduleNetworkJoin(this)
    }
  }

  override def updateEntity(): Unit = {
    super.updateEntity()
    if (isChangeScheduled) {
      this.setChanged()
      isChangeScheduled = false
      // ⚠ 只 setChanged() 只把区块标脏（**存档**用），不会把描述包推给客户端 ⇒ 客户端的屏幕快照
      //   永远停在"进世界那一刻"：真机症状 = 客户端索引里没有这块屏（地址还是空的），
      //   随后 service→client 的屏幕报文（TextBufferInit / ScreenGraphics …）全部按"未知地址"被丢弃，
      //   屏面全黑（TEXT 与 GRAPHICS 都一样，且没有任何报错）。
      //   上游 OC 的 AbstractBlockEntity.updateEntity 两件事都做（markDirty + 标块更新），移植时漏了后半。
      if (isServer && getLevel != null) {
        val state = getBlockState
        getLevel.sendBlockUpdated(getBlockPos, state, state, 3)
      }
    }
  }

  override def dispose(): Unit = {
    super.dispose()
    if (isServer) {
      Option(node).foreach(_.remove)
      this match {
        case sidedEnvironment: SidedEnvironment => for (side <- Direction.values) {
          Option(sidedEnvironment.sidedNode(side)).foreach(_.remove())
        }
        case _ =>
      }
    }
  }

  // ----------------------------------------------------------------------- //

  private final val NodeTag = TrueScreenSettings.namespace + "node"

  override def loadForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.loadForServer(nbt, provider)
    if (node != null && node.host == this) {
      node.loadData(nbt.getCompound(NodeTag), provider)
    }
  }

  override def saveForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.saveForServer(nbt, provider)
    if (node != null && node.host == this) {
      nbt.setNewCompoundTag(NodeTag, (nbt: CompoundTag) => node.saveData(nbt, provider))
    }
  }

  // ----------------------------------------------------------------------- //

  override def onMessage(message: network.Message): Unit = {}

  override def onConnect(node: network.Node): Unit = {}

  override def onDisconnect(node: network.Node): Unit = {
    if (node == this.node) node match {
      case connector: Connector =>
        // Set it to zero to push all energy into other nodes, to
        // avoid energy loss when removing nodes. Set it back to the
        // original value though, as there are cases where the node
        // is re-used afterwards, without re-adjusting its buffer size.
        var bufferSize = connector.localBufferSize()
        connector.setLocalBufferSize(0)
        connector.setLocalBufferSize(bufferSize)
      case _ =>
    }
  }

  // ----------------------------------------------------------------------- //

  protected def result(args: Any*) = com.hdf.cryptand.neoforge.truescreen.util.ResultWrapper.result(args: _*)
}
