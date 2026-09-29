/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/util/Color.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.util

import net.minecraft.world.item.DyeColor

/**
 * 屏幕的档位染色（OC 用档位给屏幕外壳一个色调，拼合时同色才合并）。
 *
 * <h3>最小替代（相对 OC 原文件砍掉了什么）</h3>
 * OC 的 `Color` 还带一整套**染料**支持（`byName` / `byTag` / `findDye` / `isDye` /
 * `dyeColor`），那是给方块"用染料右键改色"用的。本移植的屏幕方块**不提供染料交互**
 * （OC 的 `Screen` 也不在 `SimpleBlock` 的染色分支里走到），所以只保留本对象真正被用到的
 * 部分：色表 + `byTier`。留着未使用的染料表只会是第二套死代码。
 */
object Color {
  val rgbValues = Map(
    DyeColor.BLACK -> 0x444444, // 0x1E1B1B
    DyeColor.RED -> 0xB3312C,
    DyeColor.GREEN -> 0x339911, // 0x3B511A
    DyeColor.BROWN -> 0x51301A,
    DyeColor.BLUE -> 0x6666FF, // 0x253192
    DyeColor.PURPLE -> 0x7B2FBE,
    DyeColor.CYAN -> 0x66FFFF, // 0x287697
    DyeColor.LIGHT_GRAY -> 0xABABAB,
    DyeColor.GRAY -> 0x666666, // 0x434343
    DyeColor.PINK -> 0xD88198,
    DyeColor.LIME -> 0x66FF66, // 0x41CD34
    DyeColor.YELLOW -> 0xFFFF66, // 0xDECF2A
    DyeColor.LIGHT_BLUE -> 0xAAAAFF, // 0x6689D3
    DyeColor.MAGENTA -> 0xC354CD,
    DyeColor.ORANGE -> 0xEB8844,
    DyeColor.WHITE -> 0xF0F0F0
  )

  private val tierRgbValues = Array(
    rgbValues(DyeColor.LIGHT_GRAY),
    rgbValues(DyeColor.YELLOW),
    rgbValues(DyeColor.CYAN),
    0x9A7D7D,
    rgbValues(DyeColor.MAGENTA)
  )

  def byTier(tier: Int): Int = tierRgbValues(tier max 0 min (tierRgbValues.length - 1))
}
