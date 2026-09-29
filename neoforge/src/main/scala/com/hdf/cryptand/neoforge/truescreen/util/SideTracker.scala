/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/util/SideTracker.java (Java file -> Scala object)
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.util

import net.neoforged.fml.util.thread.EffectiveSide
import net.neoforged.neoforgespi.Environment

/**
 * "当前是不是服务端"的统一判据（原文件是 Java，逐字翻成 Scala object）。
 *
 * ⚠ 注意这是个**弱判据**：在客户端进程里跑服务端逻辑（集成服务器）时，
 * `Environment.getDist` 仍是 CLIENT，所以还要看 `EffectiveSide`（当前正在跑哪一侧的线程）。
 * OC 的组件代理就是靠它决定建 ClientProxy 还是 ServerProxy。
 */
object SideTracker {
  def isServer(): Boolean = Environment.get().getDist().isDedicatedServer() || EffectiveSide.get().isServer()

  def isClient(): Boolean = !isServer()
}
