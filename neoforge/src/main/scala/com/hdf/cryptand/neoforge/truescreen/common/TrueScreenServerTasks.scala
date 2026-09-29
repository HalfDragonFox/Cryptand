package com.hdf.cryptand.neoforge.truescreen.common

import com.hdf.cryptand.neoforge.truescreen.util.SideTracker
import li.cil.oc.api
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent

import scala.collection.mutable

/**
 * 服务端"下一个 tick 再执行"的小队列。
 *
 * <h3>为什么需要它（最小替代了什么）</h3>
 * OC 的 `common/EventHandler.scala` 是一个**全局**服务端调度器（还管机器人 tick、机器关闭、
 * 无线红石、键盘集合…）。屏幕闭包只用到它的一个用途：
 * `EventHandler.scheduleServer(blockEntity)` → 延迟到下一个服务端 tick 执行
 * `Network.joinOrCreateNetwork(be)`。
 *
 * 为什么必须延迟：`initialize()` 是在方块实体的加载路径上被调用的（`clearRemoved`），
 * 那一刻方块实体刚刚入世、邻居可能还没就绪；OC 因此把它推到下一个 tick。
 *
 * 所以这里只留"延迟执行服务端动作"这一件事（约 30 行），**不搬** OC 的 EventHandler，
 * 也不另造一个通用调度框架。入网本身调用的是 OC 的公开 API `api.Network.joinOrCreateNetwork`。
 */
object TrueScreenServerTasks {

  private val pendingServer = mutable.Buffer.empty[() => Unit]

  /** 排一个动作到下一个服务端 tick 执行（客户端直接丢弃，和 OC 一致）。 */
  def scheduleServer(f: () => Unit): Unit = {
    if (SideTracker.isServer()) pendingServer.synchronized {
      pendingServer += f
    }
  }

  /** 让方块实体的节点在下一个 tick 加入/建立网络（等价 OC 的 `EventHandler.scheduleServer(be)`）。 */
  def scheduleNetworkJoin(blockEntity: BlockEntity): Unit =
    scheduleServer(() => api.Network.joinOrCreateNetwork(blockEntity))

  @SubscribeEvent
  def onServerTickPost(e: ServerTickEvent.Post): Unit = {
    val adds = pendingServer.synchronized {
      val copy = pendingServer.toArray
      pendingServer.clear()
      copy
    }
    adds.foreach { callback =>
      try callback() catch {
        case t: Throwable => com.hdf.cryptand.neoforge.truescreen.TrueScreenLog.log.warn("屏幕延迟动作执行失败", t)
      }
    }
  }
}
