/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/integration/opencomputers/DriverScreen.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.integration.opencomputers

import com.hdf.cryptand.neoforge.truescreen.common.{Tier, block, blockentity, component}
import li.cil.oc.api
import li.cil.oc.api.driver.EnvironmentProvider
import li.cil.oc.api.driver.item.{HostAware, Slot}
import li.cil.oc.api.network.EnvironmentHost
import net.minecraft.world.item.{BlockItem, ItemStack}

/**
 * 我方屏幕物品的 OC 驱动（**不复用 OC 的 `DriverScreen`**）。
 *
 * <h3>为什么必须自己写一个</h3>
 * ① 若复用 OC 的 `DriverScreen`，它就只认 OC 的 `screen1..4` 物品，我们的屏物品根本进不了
 *    `api.Driver.driverFor`；而一旦为了"能进"而去改 OC 的物品表，就会污染 OC 的注册链
 *    （父代理裁决明令禁止）。
 * ② 更关键：`traits.TextBuffer` 的懒加载 buffer 若走驱动表拿组件，拿到的会是 **OC 的**
 *    `component.TextBuffer`（第二套实现）。所以我们两边都不走驱动表：
 *    懒加载直接 `new component.Screen/TextBuffer`，驱动只负责"物品 → 环境类型"这一段对外契约。
 * ③ `EnvironmentProvider` 是 OC 侧（NEI 之类）"这个物品会提供什么组件"的查询入口，
 *    屏幕物品必须报 `component.Screen`。
 *
 * <h3>槽位</h3>
 * `Slot.Upgrade`：与 OC 一样，屏物品插进机箱时占"升级槽"（它提供的是 screen 组件）。
 * 档位用 OC 的 0 基口径（`Tier.One = 0`），直接取方块自身的 `tier`（0..3），不额外换算。
 */
object DriverScreen extends HostAware {

  /** 从物品反查屏幕方块：本驱动认"任何以我方屏方块为基础的 BlockItem"。 */
  private def screenBlock(stack: ItemStack): Option[block.Screen] =
    if (stack == null || stack.isEmpty) None
    else stack.getItem match {
      case item: BlockItem => item.getBlock match {
        case screen: block.Screen => Some(screen)
        case _ => None
      }
      case _ => None
    }

  override def worksWith(stack: ItemStack): Boolean = screenBlock(stack).isDefined

  override def worksWith(stack: ItemStack, host: Class[_ <: EnvironmentHost]): Boolean = worksWith(stack)

  override def slot(stack: ItemStack): String = Slot.Upgrade

  override def tier(stack: ItemStack): Int = screenBlock(stack).map(_.tier).getOrElse(Tier.One)

  override def createEnvironment(stack: ItemStack, host: EnvironmentHost): api.network.ManagedEnvironment = host match {
    // 真实屏幕（tier > 0）：组件就是"这块屏自己的 buffer 包装" —— 与 OC DriverScreen 同构，
    // 只是这些类型都是我们移植的那一套。
    case screen: blockentity.Screen if screen.tier > 0 => new component.Screen(screen)
    // 其它宿主（机架里的屏物品、平板…）：给一个独立的文本缓冲组件。
    case _ => new component.TextBuffer(host)
  }

  object Provider extends EnvironmentProvider {
    override def getEnvironment(stack: ItemStack): Class[_] =
      if (worksWith(stack)) classOf[component.Screen]
      else null
  }
}
