/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/util/ExtendedDataComponentHolder.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.util

import net.minecraft.core.component.{DataComponentHolder, DataComponentType}
import net.neoforged.neoforge.common.MutableDataComponentHolder

import java.util.function.Supplier

object ExtendedDataComponentHolder {
  implicit def convert(value: DataComponentHolder): ExtendedDataComponentHolder = new ExtendedDataComponentHolder(value)

  implicit def convert(value: MutableDataComponentHolder): ExtendedMutableDataComponentHolder =
    new ExtendedMutableDataComponentHolder(value)
}

/**
 * OC 用这层扩展让「用 DeferredHolder（= Supplier）当 DataComponentType 用」写起来不别扭：
 * NeoForge 的 `MutableDataComponentHolder` / `DataComponentHolder` 有接受
 * `Supplier[DataComponentType[T]]` 的重载，DeferredHolder 正好是 Supplier。
 *
 * <h3>最小替代</h3>
 * OC 原文件里的 `tryFallback` 走 `Migrator` + `ItemData.defaultProvider`，那是 OC 的
 * **旧版本存档迁移链**（把 1.20 前的 item NBT 迁到 DataComponent）。我们不做旧内容兼容
 * （内测开发期策略），迁移器一律不搬；因此这里没有 fallback 分支，`getComponent` 就是
 * "有就 Some，没有就 None"。
 */
sealed class ExtendedDataComponentHolder(val holder: DataComponentHolder) {
  def getComponent[T](dataComponent: DataComponentType[T]): Option[T] = {
    holder.get(dataComponent) match {
      case null => None
      case realValue => Some(realValue)
    }
  }

  def getComponent[T](sup: Supplier[DataComponentType[T]]): Option[T] = getComponent(sup.get())
}

class ExtendedMutableDataComponentHolder(holder: MutableDataComponentHolder) extends ExtendedDataComponentHolder(holder) {

  def setComponent(dataComponent: DataComponentType[Unit], value: Boolean): Unit = {
    if (value) {
      holder.set(() => dataComponent, ())
    } else {
      holder.remove(() => dataComponent)
    }
  }

  def setComponent(dataComponent: Supplier[DataComponentType[Unit]], value: Boolean): Unit = {
    if (value) {
      holder.set(dataComponent, ())
    } else {
      holder.remove(dataComponent)
    }
  }

  def setComponent[T](dataComponent: DataComponentType[T], value: T): T = {
    holder.set(dataComponent, value)
  }

  def setComponent[T](dataComponent: DataComponentType[T], value: Option[T]): T = {
    value match {
      case Some(value) => holder.set(dataComponent, value)
      case None => holder.remove(dataComponent)
    }
  }

  def setComponent[T](dataComponent: Supplier[DataComponentType[T]], value: T): T = {
    holder.set(dataComponent, value)
  }

  def setComponent[T](dataComponent: Supplier[DataComponentType[T]], value: Option[T]): T = {
    value match {
      case Some(value) => holder.set(dataComponent, value)
      case None => holder.remove(dataComponent)
    }
  }

  def removeComponent[T](dataComponent: DataComponentType[T]): T = {
    holder.remove(dataComponent)
  }
}
