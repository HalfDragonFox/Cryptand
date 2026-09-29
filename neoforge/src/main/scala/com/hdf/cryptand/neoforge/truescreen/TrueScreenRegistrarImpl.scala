/*
 * Cryptand —— 真彩屏移植件的注册实现（SPI 实现类，2026-09-27 任务 D）
 *
 * 为什么是 **Scala class** 而不是 Java 类：
 *   ① 它必须引用 Scala 的 TrueScreenContent。放在 src/main/java 源集里，javac 在编译期**看不到**
 *      scala 源集的输出（两个源集分开编译）；放在 src/main/scala 源集里用 **Java** 写也不行 ——
 *      scalac 的联合编译把 Java 源交给 javac 时，**同一轮**里的 Scala 类（尤其是 object 的静态
 *      转发器 TrueScreenContent.class）还没落到输出目录，javac 会报"找不到符号"（本轮实测）。
 *      写成 Scala class 则天然可见（同轮 Scala → Scala）。
 *   ② JDK 的 ServiceLoader 要求实现类 public + **public 无参构造器**：Scala 的 class 正好有，
 *      而 object 没有（不能用 object）。
 *
 * ⚠ 全限定名与 META-INF/services 里的那一行必须逐字一致（改名要同时改两处）。
 */
package com.hdf.cryptand.neoforge.truescreen

import com.hdf.cryptand.neoforge.truescreen.common.blockentity.Screen
import net.minecraft.world.level.block.entity.BlockEntity
import net.neoforged.bus.api.IEventBus

final class TrueScreenRegistrarImpl extends TrueScreenRegistrar {

  override def install(modBus: IEventBus): Unit = {
    // 直接调用（同源集 ⇒ 可见）：方块 / 物品 / BE 类型 / DataComponent / OC capability /
    // 屏幕驱动 / 报文通道 / 客户端三条注册线，一次性装好。
    TrueScreenContent.register(modBus)
  }

  /** 真彩屏判定（含非原点块）—— 供 java 侧查询（它不能直接 instanceof 本源集的类）。 */
  override def isTrueScreen(be: BlockEntity): Boolean = be.isInstanceOf[Screen]

  /** 真彩屏主块判定（多块结构原点）。 */
  override def isTrueScreenOrigin(be: BlockEntity): Boolean = be match {
    case s: Screen => s.isOrigin
    case _         => false
  }
}
