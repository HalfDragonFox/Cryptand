/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/blockentity/traits/RedstoneAware.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits

import java.util

import com.hdf.cryptand.neoforge.truescreen.TrueScreenSettings
import com.hdf.cryptand.neoforge.truescreen.common.TrueScreenServerTasks
import com.hdf.cryptand.neoforge.truescreen.util.ExtendedLevel._
import net.minecraft.nbt.CompoundTag
import net.minecraft.core.{BlockPos, Direction, HolderLookup}
import net.minecraft.world.level.block.{Blocks, RedStoneWireBlock}

/**
 * 方块实体的红石输入/输出。
 *
 * <h3>最小替代 ①：红石输入的计算</h3>
 * OC 走 `integration.util.BundledRedstone.computeInput`（一个"提供者"注册表：原版红石 + ProjectRed
 * 的 bundled 线 + 其它 mod）。本移植**不搬**那个集成层，而是把其中的**原版分支**直接内联：
 * {{{
 *   max(level.computeRedstoneSignal(pos, side),          // 直接信号 / 间接信号取大
 *       if (邻居是 redstone_wire) 该线的 POWER else 0)  // 红石线的"信号强度"必须直接读属性
 * }}}
 * ⇒ 与原版红石的行为逐式一致；**bundled（多信道）红石不支持**（OC 也只有在装了 ProjectRed 时才启用）。
 *
 * <h3>最小替代 ②：报文</h3>
 * `ServerPacketSender.sendRedstoneState(this)` 去掉（OC 把红石状态单独推给客户端自绘缓存；
 * 属任务 C 的报文链）。
 *
 * <h3>最小替代 ③：延迟</h3>
 * `EventHandler.scheduleServer` → `TrueScreenServerTasks.scheduleServer`（只留"下一 tick 执行"）。
 */
case class RedstoneChangedEventArgs(side: Direction, oldValue: Int, newValue: Int, color: Int = -1)

trait RedstoneAware extends RotationAware {
  protected[blockentity] val _input: Array[Int] = Array.fill(6)(-1)

  protected[blockentity] val _output: Array[Int] = Array.fill(6)(0)

  protected var _isOutputEnabled: Boolean = false

  protected var shouldUpdateInput = true

  def isOutputEnabled: Boolean = _isOutputEnabled

  def setOutputEnabled(value: Boolean): RedstoneAware = {
    if (value != _isOutputEnabled) {
      _isOutputEnabled = value
      if (!value) {
        for (i <- _output.indices) {
          _output(i) = 0
        }
      }
      onRedstoneOutputEnabledChanged()
    }
    this
  }

  /** 从"可能是 Integer / Double / 其它 Number"的 Map 里按 key 取整数（Lua 表传过来的键类型不定）。 */
  protected def getObjectFuzzy(map: util.Map[_, _], key: Int): Option[AnyRef] = {
    val refMap: util.Map[AnyRef, AnyRef] = map.asInstanceOf[util.Map[AnyRef, AnyRef]]
    if (refMap.containsKey(key))
      Option(refMap.get(key))
    else if (refMap.containsKey(Int.box(key)))
      Option(refMap.get(Int.box(key)))
    else if (refMap.containsKey(Double.box(key)))
      Option(refMap.get(Double.box(key)))
    else
      None
  }

  protected def valueToInt(value: AnyRef): Option[Int] = {
    value match {
      case Some(num: Number) => Option(num.intValue)
      case _ => None
    }
  }

  def getInput: Array[Int] = _input.map(math.max(_, 0))

  def getInput(side: Direction): Int = _input(side.ordinal) max 0

  def setInput(side: Direction, newInput: Int): Unit = {
    val oldInput = _input(side.ordinal())
    _input(side.ordinal()) = newInput
    if (oldInput >= 0 && newInput != oldInput) {
      onRedstoneInputChanged(RedstoneChangedEventArgs(side, oldInput, newInput))
    }
  }

  def setInput(values: Array[Int]): Unit = {
    for (side <- Direction.values) {
      val value = if (side.ordinal < values.length) values(side.ordinal) else 0
      setInput(side, value)
    }
  }

  def maxInput: Int = _input.map(math.max(_, 0)).max

  def getOutput: Array[Int] = Direction.values.map { (side: Direction) => _output(toLocal(side).ordinal) }

  def getOutput(side: Direction) = if (_output != null && _output.length > toLocal(side).ordinal())
    _output(toLocal(side).ordinal())
  else 0

  def setOutput(side: Direction, value: Int): Boolean = {
    if (value == getOutput(side)) return false
    _output(toLocal(side).ordinal()) = value
    onRedstoneOutputChanged(side)
    true
  }

  def setOutput(values: util.Map[_, _]): Boolean = {
    var changed: Boolean = false
    Direction.values.foreach(side => {
      val sideIndex = toLocal(side).ordinal
      // due to a bug in our jnlua layer, I cannot loop the map
      valueToInt(getObjectFuzzy(values, sideIndex)) match {
        case Some(num: Int) if setOutput(side, num) => changed = true
        case _ =>
      }
    })
    changed
  }

  def checkRedstoneInputChanged(): Unit = {
    if (this.isInstanceOf[Tickable]) {
      shouldUpdateInput = isServer
    } else {
      Direction.values().foreach(updateRedstoneInput)
    }
  }

  // ----------------------------------------------------------------------- //

  override def updateEntity(): Unit = {
    super.updateEntity()
    if (isServer) {
      if (shouldUpdateInput) {
        shouldUpdateInput = false
        Direction.values().foreach(updateRedstoneInput)
      }
    }
  }

  override def clearRemoved(): Unit = {
    super.clearRemoved()
    if (!this.isInstanceOf[Tickable] && isServer) {
      TrueScreenServerTasks.scheduleServer(() => Direction.values().foreach(updateRedstoneInput))
    }
  }

  def updateRedstoneInput(side: Direction): Unit = {
    setInput(side, computeRedstoneInput(side))
  }

  /**
   * 原版红石输入强度（OC `ModMinecraft.computeInput` 的逐式内联）。
   *
   * `level.computeRedstoneSignal` 见 `util.ExtendedLevel`：直接信号与间接信号取大者；
   * 另外红石线不"提供"直接/间接信号强度（它只在 getSignal 里报自己的 POWER 于部分情形），
   * 所以 OC 额外直接读线的 POWER 属性 —— 这里保持同样做法，否则"红石线接屏幕"会读成 0。
   */
  protected def computeRedstoneInput(side: Direction): Int = {
    val position = this.position
    val neighbor = position.offset(side)
    if (!getLevel.blockExists(neighbor)) 0
    else {
      val signal = getLevel.computeRedstoneSignal(position, side)
      val wire = getLevel.getBlockState(neighbor) match {
        case state if state.is(Blocks.REDSTONE_WIRE) =>
          state.getValue(RedStoneWireBlock.POWER).intValue()
        case _ => 0
      }
      math.max(signal, wire)
    }
  }

  // ----------------------------------------------------------------------- //

  override def loadForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.loadForServer(nbt, provider)

    val input = nbt.getIntArray(TrueScreenSettings.namespace + "rs.input")
    input.copyToArray(_input, 0, input.length min _input.length)
    val output = nbt.getIntArray(TrueScreenSettings.namespace + "rs.output")
    output.copyToArray(_output, 0, output.length min _output.length)
  }

  override def saveForServer(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.saveForServer(nbt, provider)

    nbt.putIntArray(TrueScreenSettings.namespace + "rs.input", _input)
    nbt.putIntArray(TrueScreenSettings.namespace + "rs.output", _output)
  }

  override def loadForClient(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.loadForClient(nbt, provider)
    _isOutputEnabled = nbt.getBoolean("isOutputEnabled")
    nbt.getIntArray("output").copyToArray(_output)
  }

  override def saveForClient(nbt: CompoundTag, provider: HolderLookup.Provider): Unit = {
    super.saveForClient(nbt, provider)
    nbt.putBoolean("isOutputEnabled", _isOutputEnabled)
    nbt.putIntArray("output", _output)
  }

  // ----------------------------------------------------------------------- //

  protected def onRedstoneInputChanged(args: RedstoneChangedEventArgs): Unit = {}

  protected def onRedstoneOutputEnabledChanged(): Unit = {
    if (getLevel != null) {
      getLevel.updateNeighborsAt(getBlockPos, getBlockState.getBlock)
      // 服务端：把红石输出状态推给客户端（客户端自绘缓存用）；客户端分支走方块更新。
      if (isServer) com.hdf.cryptand.neoforge.truescreen.network.ServerPacketSender.sendRedstoneState(this)
      else getLevel.sendBlockUpdated(getBlockPos, getLevel.getBlockState(getBlockPos), getLevel.getBlockState(getBlockPos), 3)
    }
  }

  protected def onRedstoneOutputChanged(side: Direction): Unit = {
    val sourcePos = getBlockPos
    val sourceBlock = getBlockState.getBlock
    // 显式标 BlockPos：BlockPos 同时继承了 Vec3i.relative(Direction): Vec3i 与自己的
    // BlockPos.relative(Direction)，无期望类型时 Scala 无法在两者间选择（OC 因此另写了 BlockPosHelper）。
    val blockPos: BlockPos = sourcePos.relative(side)
    getLevel.neighborChanged(blockPos, sourceBlock, sourcePos)
    getLevel.updateNeighborsAtExceptFromFacing(blockPos, getLevel.getBlockState(blockPos).getBlock, side.getOpposite)

    if (isServer) com.hdf.cryptand.neoforge.truescreen.network.ServerPacketSender.sendRedstoneState(this)
    else getLevel.sendBlockUpdated(getBlockPos, getLevel.getBlockState(getBlockPos), getLevel.getBlockState(getBlockPos), 3)
  }
}
