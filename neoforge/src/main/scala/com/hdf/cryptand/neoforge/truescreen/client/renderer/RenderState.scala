/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/util/RenderState.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client.renderer

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.util.Mth
import net.neoforged.api.distmarker.{Dist, OnlyIn}
import org.joml.{Matrix3f, Matrix4f}
import org.lwjgl.opengl.GL11

/**
 * OC 的 util/RenderState 的**最小子集**（只留屏幕渲染路径用到的两个成员）。
 *
 * <h3>⚠ 为什么落在 client/renderer/ 而不是 util/（与上游的相对路径不一致）</h3>
 * 任务 B 的领地是 truescreen/client 整棵子树（新）+ 两处插入点；truescreen/util 不在领地内
 * （那是任务 A 的产物，只读）。所以这个上游位于 li.cil.oc.util 的工具类被搬到
 * ...truescreen.client.renderer 下 —— 包的相对位置变了，**语义逐字不变**。
 *
 * 另一处最小替代：OC 的 checkError 受 Settings.logOpenGLErrors 控制
 * （application.conf:1592 默认 **false**）。TrueScreenSettings 不在任务 B 的领地内，
 * 故这里不引入该开关，直接把默认值（false ⇒ 什么都不做）固定成行为：
 * LogOpenGlErrors 是一个**显式常量**，不是"忘了实现"。若任务 C 想开放这个开关，
 * 在 TrueScreenSettings 里加字段后把它换成该字段即可（插入点就在这里）。
 */
@OnlyIn(Dist.CLIENT)
object RenderState {

  /** OC 的 Settings.get.logOpenGLErrors 默认值（application.conf:1592 = false）。 */
  private final val LogOpenGlErrors = false

  def getErrorString(errorCode: Int): String = errorCode match {
    case GL11.GL_NO_ERROR => "No error"
    case GL11.GL_INVALID_ENUM => "Enum argument out of range"
    case GL11.GL_INVALID_VALUE => "Numeric argument out of range"
    case GL11.GL_INVALID_OPERATION => "Operation illegal in current state"
    case GL11.GL_STACK_OVERFLOW => "Command would cause a stack overflow"
    case GL11.GL_STACK_UNDERFLOW => "Command would cause a stack underflow"
    case GL11.GL_OUT_OF_MEMORY => "Not enough memory left to execute command"
    case _ => f"Unknown [0x$errorCode%X]"
  }

  def checkError(where: String): Unit = {
    if (LogOpenGlErrors) {
      val error = GL11.glGetError
      if (error != 0) {
        com.hdf.cryptand.neoforge.truescreen.TrueScreenLog.log.warn("GL ERROR @ " + where + ": " + getErrorString(error))
      }
    }
  }

  def mirrorScale(stack: PoseStack, sx: Float, sy: Float, sz: Float): Unit = {
    stack.last.pose.mul(new Matrix4f().scaling(sx, sy, sz))
    if (sx != sy || sx != sz || sx <= 0) {
      val isx = 1 / sx
      val isy = 1 / sy
      val isz = 1 / sz
      val invScale = isx * isy * isz
      // Issue with vanilla impl: the inverse cube root algorithm completely fails for negative values.
      var normScale = Mth.fastInvCubeRoot(Mth.abs(invScale))
      if (invScale < 0) {
        // compensate for taking the absolute of invScale
        normScale = -normScale
      }
      stack.last.normal.mul(new Matrix3f().scaling(isx * normScale, isy * normScale, isz * normScale))
    }
  }
}
