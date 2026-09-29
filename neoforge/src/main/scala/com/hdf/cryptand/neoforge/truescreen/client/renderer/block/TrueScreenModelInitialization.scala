/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/renderer/block/ModelInitialization.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client.renderer.block

import com.hdf.cryptand.neoforge.truescreen.client.TrueScreenTextures
import net.minecraft.client.resources.model.{BakedModel, ModelResourceLocation}
import net.neoforged.api.distmarker.{Dist, OnlyIn}
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ModelEvent

/**
 * 烘焙期把**我方屏幕方块**的模型换成 {@link ScreenModel}（外观来自代码，不来自 JSON）。
 *
 * <h3>相对上游的最小裁剪</h3>
 * 上游还接管 NetSplitter / Print / Robot 的模型并做一批 ModelResourceLocation 重映射；
 * 那些方块不在本次移植范围内（阶段一清单里就没有它们）⇒ 只保留屏幕那一条。
 * 上游按 ^oc:<name>#.* 的正则匹配 **4 个档位**；我方方块是 12 个 id
 * （墙屏 truescreen1..4 + 平板屏 truescreenflat1..4 + 平板屏背面 truescreenflatback1..4，
 * 见 TrueScreenContent），所以对 12 个名字各挂一次同一个 ScreenModel。
 *
 * ⚠ 注册时机（任务 C 的接入点）：@SubscribeEvent 只是**方法**，要真的生效必须把这个 object
 * 挂到 **mod 事件总线**（ModelEvent.ModifyBakingResult 是 IModBusEvent），
 * 例如在 TrueScreenContent.register 里加 modBus.register(TrueScreenModelInitialization)
 * 或在客户端加 @EventBusSubscriber。注册入口统一由任务 C 决定，本类不自带。
 *
 * ⚠ 前提：方块模型/blockstates（任务 C 的资源）必须让 cryptand:block/screen 下的那批贴图
 * 进入方块图集，否则 ScreenModel 取到的是 missing sprite（贴图已随任务 B 复制进仓库）。
 */
@OnlyIn(Dist.CLIENT)
object TrueScreenModelInitialization {

  /** 我方全部屏幕方块 id（与 TrueScreenContent 的三个名字数组一一对应）。 */
  private final val ScreenBlockNames = Array(
    "truescreen1", "truescreen2", "truescreen3", "truescreen4",
    "truescreenflat1", "truescreenflat2", "truescreenflat3", "truescreenflat4",
    "truescreenflatback1", "truescreenflatback2", "truescreenflatback3", "truescreenflatback4"
  )

  @SubscribeEvent
  def onModifyBakingResult(e: ModelEvent.ModifyBakingResult): Unit = {
    val registry = e.getModels

    // 取值快照再写回（上游同款：避免边遍历边改 Map 的结构）。
    registry.keySet.toArray.foreach {
      case location: ModelResourceLocation =>
        val id = location.toString
        if (ScreenBlockNames.exists(name => id.matches("^" + TrueScreenTextures.ModId + ":" + name + "#.*"))) {
          registry.put(location, ScreenModel)
        }
      case _ =>
    }
  }
}
