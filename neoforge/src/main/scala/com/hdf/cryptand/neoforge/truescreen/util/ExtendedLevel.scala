/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/util/ExtendedLevel.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.util

import net.minecraft.core.{BlockPos, Direction}
import net.minecraft.world.level.Level
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.entity.BlockEntity

import scala.language.implicitConversions

/**
 * Level / BlockGetter 的隐式扩展。
 *
 * <h3>最小替代</h3>
 * OC 原文件里还有一大堆"机器人类"用的工具（挖方块、破坏进度、掉落、工具等级、灭火…），
 * 那是给 Robot/Drone 用的。屏幕只用得到坐标查询 + 红石输入 + 方块更新通知，所以这里
 * **只保留屏幕闭包真正调用的成员**，其余一概不搬（避免把半个 OC 的工具库拖进来）。
 */
object ExtendedLevel {

  implicit def extendedBlockAccess(getter: BlockGetter): ExtendedBlockAccess = new ExtendedBlockAccess(getter)

  implicit def extendedLevel(level: Level): ExtendedLevel = new ExtendedLevel(level)

  class ExtendedBlockAccess(val getter: BlockGetter) {
    def getBlock(position: BlockPosition) = getter.getBlockState(position.toBlockPos).getBlock

    def getBlockState(position: BlockPosition) = getter.getBlockState(position.toBlockPos)

    def getBlockEntity(position: BlockPosition): BlockEntity = getter.getBlockEntity(position.toBlockPos)
  }

  class ExtendedLevel(level: Level) extends ExtendedBlockAccess(level) {
    def blockExists(position: BlockPosition): Boolean = level.isLoaded(position.toBlockPos)

    /** 原文件：`computeRedstoneSignal`（直接信号与间接信号取大者）。 */
    def computeRedstoneSignal(position: BlockPosition, side: Direction): Int =
      math.max(level.getDirectSignal(position.offset(side).toBlockPos, side),
        level.getSignal(position.offset(side).toBlockPos, side))

    def isBlockProvidingPowerTo(position: BlockPosition, side: Direction): Int =
      level.getDirectSignal(position.toBlockPos, side)

    def getIndirectPowerLevelTo(position: BlockPosition, side: Direction): Int =
      level.getSignal(position.toBlockPos, side)

    def notifyBlockUpdate(pos: BlockPos): Unit =
      level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3)

    def notifyBlockUpdate(position: BlockPosition): Unit =
      level.sendBlockUpdated(position.toBlockPos, level.getBlockState(position.toBlockPos),
        level.getBlockState(position.toBlockPos), 3)

    def isLoaded(position: BlockPosition): Boolean = level.isLoaded(position.toBlockPos)
  }

}
