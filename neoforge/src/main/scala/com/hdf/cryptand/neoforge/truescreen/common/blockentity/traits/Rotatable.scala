/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/blockentity/traits/Rotatable.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.blockentity.traits

import com.hdf.cryptand.neoforge.truescreen.common.block.SimpleBlock
import com.hdf.cryptand.neoforge.truescreen.common.block.property.PropertyRotatable
import com.hdf.cryptand.neoforge.truescreen.util.ExtendedEnumFacing._
import com.hdf.cryptand.neoforge.truescreen.util.ExtendedLevel._
import com.hdf.cryptand.neoforge.truescreen.util.RotationHelper
import li.cil.oc.api.internal
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.block.state.BlockState

/**
 * 可旋转方块实体（pitch = 上下贴面 / 朝向，yaw = 水平朝向）。
 *
 * 改动：① 去掉 Create 移动分支（`isMoving/movingBlockState`）；②
 * `onRotationChanged` 里的 `ServerPacketSender.sendRotatableState(this)` 去掉 ——
 * 那是 OC 把"方块实体朝向"单独同步给客户端的自绘缓存用的报文，属任务 C 的报文链；
 * 朝向我方本来就写在**方块状态**（PropertyRotatable.Pitch/Yaw）里，随原版方块更新同步，
 * 所以服务端这条报文对我们的正确性没有影响。
 */
trait Rotatable extends RotationAware with internal.Rotatable {
  // ----------------------------------------------------------------------- //
  // Lookup tables
  // ----------------------------------------------------------------------- //

  private val pitch2Direction = Array(Direction.UP, Direction.NORTH, Direction.DOWN)

  private val yaw2Direction = Array(Direction.SOUTH, Direction.WEST, Direction.NORTH, Direction.EAST)

  // ----------------------------------------------------------------------- //
  // Accessors
  // ----------------------------------------------------------------------- //

  def pitch: Direction = getBlockState match {
    case rotatable if rotatable.getProperties.contains(PropertyRotatable.Pitch) => rotatable.getValue(PropertyRotatable.Pitch)
    case _ => Direction.NORTH
  }

  def pitch_=(value: Direction): Unit =
    trySetPitchYaw(value match {
      case Direction.DOWN | Direction.UP => value
      case _ => Direction.NORTH
    }, yaw)

  def yaw: Direction = getBlockState match {
    case rotatable if rotatable.getProperties.contains(PropertyRotatable.Yaw) => rotatable.getValue(PropertyRotatable.Yaw)
    case rotatable if rotatable.getProperties.contains(PropertyRotatable.Facing) => rotatable.getValue(PropertyRotatable.Facing)
    case _ => Direction.SOUTH
  }

  def yaw_=(value: Direction): Unit =
    trySetPitchYaw(pitch, value match {
      case Direction.DOWN | Direction.UP => yaw
      case _ => value
    })

  def setFromEntityPitchAndYaw(entity: Entity) =
    trySetPitchYaw(
      pitch2Direction((entity.getXRot / 90).round + 1),
      yaw2Direction((entity.getYRot / 360 * 4).round & 3))

  def setFromEntityPitchAndYaw(entity: Entity, localYaw: Direction) =
    trySetPitchYaw(
      pitch2Direction((entity.getXRot / 90).round + 1),
      localYaw)

  def setFromPitchAndYaw(localPitch: Float, localYaw: Float) =
    trySetPitchYaw(
      pitch2Direction((localPitch / 90).round + 1),
      yaw2Direction((localYaw / 360 * 4).round & 3))

  def setFromFacing(value: Direction) =
    value match {
      case Direction.DOWN | Direction.UP =>
        trySetPitchYaw(value, yaw)
      case yaw =>
        trySetPitchYaw(Direction.NORTH, yaw)
    }

  /** 公开桥：给"在方块外变换方块实体"的集成用（OC 给 Create 留的入口）。 */
  def setFromPitchAndYaw(newPitch: Direction, newYaw: Direction): Boolean =
    trySetPitchYaw(newPitch, newYaw)

  def invertRotation() =
    trySetPitchYaw(pitch match {
      case Direction.DOWN | Direction.UP => pitch.getOpposite
      case _ => Direction.NORTH
    }, yaw.getOpposite)

  override def facing = pitch match {
    case Direction.DOWN | Direction.UP => pitch
    case _ => yaw
  }

  def rotate(axis: Direction): Boolean = {
    val state = getLevel.getBlockState(getBlockPos)
    state.getBlock match {
      case simple: SimpleBlock => {
        val valid = simple.getValidRotations(getLevel, getBlockPos)
        if (valid != null && valid.contains(axis)) {
          val (newPitch, newYaw) = facing.getRotation(axis) match {
            case value@(Direction.UP | Direction.DOWN) =>
              if (value == pitch) (value, yaw.getRotation(axis))
              else (value, yaw)
            case value => (Direction.NORTH, value)
          }
          trySetPitchYaw(newPitch, newYaw)
        }
        else false
      }
      case _ if axis == Direction.UP || axis == Direction.DOWN => {
        val updated = state.rotate(getLevel, getBlockPos, if (axis == Direction.DOWN) Rotation.COUNTERCLOCKWISE_90 else Rotation.CLOCKWISE_90)
        updated != state && getLevel.setBlockAndUpdate(getBlockPos, updated)
      }
      case _ => false
    }
  }

  override def toLocal(value: Direction): Direction = if (value == null) null else RotationHelper.toLocal(pitch, yaw, value)

  override def toGlobal(value: Direction): Direction = if (value == null) null else RotationHelper.toGlobal(pitch, yaw, value)

  def validFacings: Array[Direction] = Array(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)

  // ----------------------------------------------------------------------- //

  protected def onRotationChanged(): Unit = {
    if (isClient) {
      getLevel.notifyBlockUpdate(getBlockPos)
    }
    getLevel.updateNeighborsAt(getBlockPos, getBlockState.getBlock)
    // 服务端：pitch/yaw 单独推一次（客户端按同一套规则重算拼合关系；方块状态里的朝向
    // 到客户端要等方块更新，拼合重算会慢一拍）。
    if (isServer) {
      com.hdf.cryptand.neoforge.truescreen.network.ServerPacketSender.sendRotatableState(this)
    }
  }

  // ----------------------------------------------------------------------- //

  /** Updates cached translation array and sends notification to clients. */
  protected def updateTranslation(): Unit = {
    if (getLevel != null) {
      onRotationChanged()
    }
  }

  /** Validates new values against the allowed rotations as set in our block. */
  protected def trySetPitchYaw(pitch: Direction, yaw: Direction): Boolean = {
    var safePitch = pitch
    var safeYaw = yaw
    if (!pitch2Direction.contains(pitch))
      safePitch = Direction.NORTH
    if (!validFacings.contains(yaw))
      safeYaw = validFacings.headOption.getOrElse(Direction.NORTH)
    val oldState = getLevel.getBlockState(getBlockPos)
    def setState(newState: BlockState): Boolean = {
      if (oldState.hashCode() != newState.hashCode()) {
        getLevel.setBlockAndUpdate(getBlockPos, newState)
        updateTranslation()
        true
      }
      else false
    }
    getBlockState.getBlock match {
      case rotatable if oldState.hasProperty(PropertyRotatable.Pitch) && oldState.hasProperty(PropertyRotatable.Yaw) =>
        setState(oldState.setValue(PropertyRotatable.Pitch, safePitch).setValue(PropertyRotatable.Yaw, safeYaw))
      case rotatable if oldState.hasProperty(PropertyRotatable.Facing) =>
        setState(oldState.setValue(PropertyRotatable.Facing, safeYaw))
      case _ => false
    }
  }
}
