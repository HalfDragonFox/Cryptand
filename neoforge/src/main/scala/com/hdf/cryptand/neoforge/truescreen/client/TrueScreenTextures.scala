/*
 * Ported from OpenComputers: Rebooted 1.9.4-2 (MIT License)
 *   original: src/main/scala/li/cil/oc/client/Textures.scala
 * Copyright (c) 2013-2015 Florian "Sangar" Nücke
 * Copyright (c) 2026 akki697222
 * Copyright (c) 2026 OpenComputers: Rebooted Team
 * See NOTICE in the repository root for the full derivation list.
 */
package com.hdf.cryptand.neoforge.truescreen.client

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.{SimpleTexture, TextureAtlasSprite}
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.{ResourceManager, ResourceManagerReloadListener}
import net.minecraft.world.inventory.InventoryMenu
import net.neoforged.api.distmarker.{Dist, OnlyIn}
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent

import scala.collection.mutable

/**
 * 贴图表（OC 的 client/Textures.scala 的**屏幕渲染子集**）。
 *
 * <h3>相对上游的最小裁剪（逐条）</h3>
 * <ul>
 *   <li>只保留**屏幕渲染器直接输入**的那几组贴图：Font（字形图集）、GUI.Borders（右键窗口边框）、
 *       Block.GenericTop（SmartBlockModelBase 的粒子图标）、Block.Screen（首/中/尾三级分段贴图）、
 *       Block.ScreenUpIndicator（屏幕"朝上"指示箭头）。</li>
 *   <li>丢弃的组（GUISprites / Icons / Model / Item / Block 里非屏幕的 overlay）：它们服务
 *       机架、机器人、打印机、手册等**未移植的子系统**（阶段一清单里就不在屏幕闭包内）。</li>
 *   <li>命名空间：OpenComputers.ID（"opencomputers"）与 Settings.resourceDomain → 常量
 *       ModId = "cryptand"（与 TrueScreenContent 注册用的命名空间一致）。</li>
 * </ul>
 *
 * ⚠ 注册时机（任务 C 的接入点）：onRegisterReloadListeners 必须挂到 **mod 事件总线**
 * （RegisterClientReloadListenersEvent 是 IModBusEvent），否则 cryptand:font/chars 这类
 * **独立贴图**（不在方块图集里的）不会注册，字形图集会取到 missing texture。
 * 本类不自带 @EventBusSubscriber（注册入口统一在 TrueScreenContent.register，属任务 C 领地）。
 */
@OnlyIn(Dist.CLIENT)
object TrueScreenTextures {

  /** 我方命名空间（= TrueScreenContent 里 DeferredRegister 用的那个 mod id）。 */
  final val ModId = "cryptand"

  object Font extends SimpleTextureBundle {
    val Aliased = L("chars_aliased")
    val AntiAliased = L("chars")

    override protected def basePath = "font/%s"
  }

  object GUI {
    private def L(name: String): ResourceLocation =
      ResourceLocation.fromNamespaceAndPath(ModId, "textures/gui/" + name + ".png")

    val Borders = L("borders")
  }

  object Block {
    val ScreenUpIndicator = L("overlay/screen_up_indicator")
    val GenericTop = L("generic_top")

    object Screen {
      val Single = Array(L("screen/b"),L("screen/b"),L("screen/b2"),L("screen/b2"),L("screen/b2"),L("screen/b2"))
      val SingleFront = Array(L("screen/f"),L("screen/f2"))
      val Horizontal = Array(Array(Array(L("screen/bht"),L("screen/bhb"),L("screen/bht2"),L("screen/bht2"),L("screen/b2"),L("screen/b2")),Array(L("screen/bhm"),L("screen/bhm"),L("screen/bhm2"),L("screen/bhm2"),L("screen/b"),L("screen/b")),Array(L("screen/bhb"),L("screen/bht"),L("screen/bhb2"),L("screen/bhb2"),L("screen/b2"),L("screen/b2"))),Array(Array(L("screen/bhb2"),L("screen/bht2"),L("screen/bht"),L("screen/bhb"),L("screen/b2"),L("screen/b2")),Array(L("screen/bhm2"),L("screen/bhm2"),L("screen/bhm"),L("screen/bhm"),L("screen/b"),L("screen/b")),Array(L("screen/bht2"),L("screen/bhb2"),L("screen/bhb"),L("screen/bht"),L("screen/b2"),L("screen/b2"))))
      val HorizontalFront = Array(Array(L("screen/fhb2"),L("screen/fhm2"),L("screen/fht2")),Array(L("screen/fhb"),L("screen/fhm"),L("screen/fht")))
      val Vertical = Array(Array(Array(L("screen/b"),L("screen/b"),L("screen/bvt"),L("screen/bvt"),L("screen/bvt"),L("screen/bvt")),Array(L("screen/b"),L("screen/b"),L("screen/bvm"),L("screen/bvm"),L("screen/bvm"),L("screen/bvm")),Array(L("screen/b"),L("screen/b"),L("screen/bvb2"),L("screen/bvb2"),L("screen/bvb2"),L("screen/bvb2"))),Array(Array(L("screen/b2"),L("screen/b2"),L("screen/bvt"),L("screen/bvt"),L("screen/bht2"),L("screen/bhb2")),Array(L("screen/b"),L("screen/b"),L("screen/bvm"),L("screen/bvm"),L("screen/bhm2"),L("screen/bhm2")),Array(L("screen/b2"),L("screen/b2"),L("screen/bvb"),L("screen/bvb"),L("screen/bhb2"),L("screen/bht2"))))
      val VerticalFront = Array(Array(L("screen/fvt"),L("screen/fvm"),L("screen/fvb2")),Array(L("screen/fvt"),L("screen/fvm"),L("screen/fvb")))
      val Multi = Array(Array(Array(Array(L("screen/bht"),L("screen/bhb"),L("screen/btl"),L("screen/btr"),L("screen/bvb"),L("screen/bvt")),Array(L("screen/bhm"),L("screen/bhm"),L("screen/btm"),L("screen/btm"),L("screen/b"),L("screen/b")),Array(L("screen/bhb"),L("screen/bht"),L("screen/btr"),L("screen/btl"),L("screen/bvt"),L("screen/bvb"))),Array(Array(L("screen/b"),L("screen/b"),L("screen/bml"),L("screen/bmr"),L("screen/bvm"),L("screen/bvm")),Array(L("screen/b"),L("screen/b"),L("screen/bmm"),L("screen/bmm"),L("screen/b"),L("screen/b")),Array(L("screen/b"),L("screen/b"),L("screen/bmr"),L("screen/bml"),L("screen/bvm"),L("screen/bvt"))),Array(Array(L("screen/bht"),L("screen/bhb"),L("screen/bbl2"),L("screen/bbr2"),L("screen/bvt"),L("screen/bvb2")),Array(L("screen/bhm"),L("screen/bhm"),L("screen/bbm2"),L("screen/bbm2"),L("screen/b"),L("screen/b")),Array(L("screen/bhb"),L("screen/bht"),L("screen/bbr2"),L("screen/bbl2"),L("screen/bvb2"),L("screen/bvt")))),Array(Array(Array(L("screen/bhb2"),L("screen/bht2"),L("screen/btl"),L("screen/btr"),L("screen/bht2"),L("screen/bhb2")),Array(L("screen/bhm2"),L("screen/bhm2"),L("screen/btm"),L("screen/btm"),L("screen/b"),L("screen/b")),Array(L("screen/bht2"),L("screen/bhb2"),L("screen/btr"),L("screen/btl"),L("screen/bht2"),L("screen/bhb2"))),Array(Array(L("screen/b"),L("screen/b"),L("screen/bml"),L("screen/bml"),L("screen/bhm2"),L("screen/bhm2")),Array(L("screen/b"),L("screen/b"),L("screen/bmm"),L("screen/bmm"),L("screen/b"),L("screen/b")),Array(L("screen/b"),L("screen/b"),L("screen/bmr"),L("screen/bmr"),L("screen/bhm2"),L("screen/bhm2"))),Array(Array(L("screen/bhb2"),L("screen/bht2"),L("screen/bbl"),L("screen/bbr"),L("screen/bhb2"),L("screen/bht2")),Array(L("screen/bhm2"),L("screen/bhm2"),L("screen/bbm"),L("screen/bbm"),L("screen/b"),L("screen/b")),Array(L("screen/bht2"),L("screen/bhb2"),L("screen/bbr"),L("screen/bbl"),L("screen/bhb2"),L("screen/bht2")))))
      val MultiFront = Array(Array(Array(L("screen/ftr"),L("screen/ftm"),L("screen/ftl")),Array(L("screen/fmr"),L("screen/fmm"),L("screen/fml")),Array(L("screen/fbr2"),L("screen/fbm2"),L("screen/fbl2"))),Array(Array(L("screen/ftr"),L("screen/ftm"),L("screen/ftl")),Array(L("screen/fmr"),L("screen/fmm"),L("screen/fml")),Array(L("screen/fbr"),L("screen/fbm"),L("screen/fbl"))))

      private[Block] def makeSureThisIsInitialized(): Unit = {}
    }

    def bind(): Unit = TrueScreenTextures.bind(InventoryMenu.BLOCK_ATLAS)

    Screen.makeSureThisIsInitialized()

    private def L(name: String) = ResourceLocation.fromNamespaceAndPath(ModId, "block/" + name)
  }

  def getSprite(location: ResourceLocation): TextureAtlasSprite =
    Minecraft.getInstance.getModelManager.getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(location)

  def bind(location: ResourceLocation): Unit = {
    if (location != null) {
      RenderSystem.setShaderTexture(0, location)
    } else {
      RenderSystem.setShaderTexture(0, 0)
    }
  }

  @SubscribeEvent
  def onRegisterReloadListeners(e: RegisterClientReloadListenersEvent): Unit = {
    e.registerReloadListener(new ResourceManagerReloadListener {
      override def onResourceManagerReload(manager: ResourceManager): Unit = {
        val tm = Minecraft.getInstance.getTextureManager
        def register(bundle: SimpleTextureBundle): Unit = {
          bundle.locations.foreach { location =>
            tm.register(location, new SimpleTexture(
              ResourceLocation.fromNamespaceAndPath(location.getNamespace, "textures/" + location.getPath + ".png")))
          }
        }
        register(Font)
      }
    })
  }

  abstract class SimpleTextureBundle {
    private[TrueScreenTextures] val locations = mutable.ArrayBuffer.empty[ResourceLocation]

    protected def L(name: String, load: Boolean = true): ResourceLocation = {
      val location = ResourceLocation.fromNamespaceAndPath(ModId, String.format(basePath, name))
      if (load) locations += location
      location
    }

    protected def basePath: String
  }
}
