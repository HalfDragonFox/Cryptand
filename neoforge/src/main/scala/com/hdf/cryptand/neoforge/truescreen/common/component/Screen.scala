/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/common/component/Screen.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.common.component

import com.hdf.cryptand.neoforge.truescreen.common.blockentity
import li.cil.oc.api.machine.{Arguments, Callback, Context}

/**
 * 屏幕组件（在 `component.TextBuffer` 之上再加两个回调：反转触摸模式）。
 * 这是**真实屏幕方块**用的组件实例（见 traits.TextBuffer 里 buffer 的构造分支）。
 *
 * 改动：`ServerPacketSender.sendScreenTouchMode(screen, newValue)` 去掉
 * （报文链属任务 C；invertTouchMode 本身在 component 里，随 BE 更新包同步）。
 */
class Screen(val screen: blockentity.Screen) extends TextBuffer(screen) {
  @Callback(direct = true, doc = """function():boolean -- Whether touch mode is inverted (sneak-activate opens GUI, instead of normal activate).""")
  def isTouchModeInverted(computer: Context, args: Arguments): Array[AnyRef] = result(screen.invertTouchMode)

  @Callback(doc = """function(value:boolean):boolean -- Sets whether to invert touch mode (sneak-activate opens GUI, instead of normal activate).""")
  def setTouchModeInverted(computer: Context, args: Arguments): Array[AnyRef] = {
    val newValue = args.checkBoolean(0)
    val oldValue = screen.invertTouchMode
    if (newValue != oldValue) {
      screen.invertTouchMode = newValue
      // 立刻通知客户端（它右键时用它决定 sneak 是开窗口还是走触摸）：
      // 值本身也随 BE 更新包里的 INVERT_TOUCH 组件同步，报文只是让"立刻生效"。
      com.hdf.cryptand.neoforge.truescreen.network.ServerPacketSender.sendScreenTouchMode(screen, newValue)
    }
    result(oldValue)
  }
}
