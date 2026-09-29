/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/util/ExtendedNBT.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.util

import net.minecraft.nbt.CompoundTag

import scala.language.implicitConversions

/**
 * NBT 的隐式扩展。
 *
 * <h3>最小替代</h3>
 * OC 的 `ExtendedNBT` 有 200 多行（各种 toNbt 隐式、toTypedMap/direction/uuid/pos 读写…），
 * 是给整个 OC 用的。屏幕闭包只用到 `setNewCompoundTag`（把节点数据写成一个子标签），
 * 所以这里只保留它 —— 不搬第二套 NBT 工具库。
 */
object ExtendedNBT {
  implicit def extendedNBT(nbt: CompoundTag): ExtendedCompoundTag = new ExtendedCompoundTag(nbt)

  class ExtendedCompoundTag(val nbt: CompoundTag) {
    def setNewCompoundTag(name: String, f: CompoundTag => Unit): CompoundTag = {
      val t = new CompoundTag()
      f(t)
      nbt.put(name, t)
      nbt
    }
  }
}
