package com.hdf.cryptand.neoforge.truescreen.common.datacomponents

import com.mojang.serialization.Codec
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.{ByteBufCodecs, StreamCodec}
import net.minecraft.util.ColorRGBA
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.{DeferredHolder, DeferredRegister}

/**
 * 我方（cryptand）的**屏幕相关** DataComponentType 注册表。
 *
 * <h3>为什么要有这个文件（而不是直接用 OC 的 OCComponents）</h3>
 * OC 把屏幕状态存成 DataComponent（tier / 色深 / 显存内容 / 调色板…），这些 component 类型
 * 注册在 `opencomputers:` 命名空间下（`OCComponents.scala` 是一个 13KB、覆盖机器人/无人机/
 * 机架/存档迁移等**全部**子系统的注册中心）。父代理裁决：「注册只挑屏幕相关条目、走
 * `cryptand:` 命名空间与我方既有注册方式，别整搬 OC 的注册链」。
 *
 * 于是这里**只**列出移植后的屏幕闭包真正读写的 component 类型（逐条注明对应的 OC 条目），
 * 用 `DeferredRegister.DataComponents` + `cryptand` 命名空间自注册。
 *
 * ⚠ 注册时机：`register(modBus)` 必须由任务 C 在 **mod 总线**上调用一次
 * （见 `TrueScreenContent.register`）。未注册时任何 `X.get()` 都会抛（loud failure），
 * 不会静默退化成"没有 component"。
 *
 * <h3>value 类型保持 OC 的写法</h3>
 * `Type[T] = DeferredHolder[DataComponentType[_], DataComponentType[T]]`（即
 * `Supplier[DataComponentType[T]]`），这样移植过来的代码里 `holder.getComponent(TIER)` /
 * `holder.setComponent(TIER, v)` **一个字符都不用改**：取值/存值走
 * `ExtendedDataComponentHolder`（NeoForge 的 Supplier 重载）。
 */
object TrueScreenComponents {

  type Type[T] = DeferredHolder[DataComponentType[_], DataComponentType[T]]

  val REGISTRAR: DeferredRegister.DataComponents =
    DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, "cryptand")

  def register(modBus: IEventBus): Unit = REGISTRAR.register(modBus)

  private def persistent[T](name: String, codec: Codec[T]): Type[T] =
    REGISTRAR.registerComponentType(name, _.persistent(codec))

  private def persistentShared[T](name: String, codec: Codec[T],
                                  streamCodec: StreamCodec[_ >: RegistryFriendlyByteBuf, T]): Type[T] =
    REGISTRAR.registerComponentType(name, _.persistent(codec).networkSynchronized(streamCodec))

  private def persistentSharedUnit(name: String): Type[Unit] =
    persistentShared(name, Codec.unit(()), StreamCodec.unit(()))

  // --------------------------------------------------------------------- //
  // 以下逐条对应 OC 的 OCComponents.<名字>；语义与编解码器完全一致，只换命名空间。
  // --------------------------------------------------------------------- //

  /** 屏幕档位（0..3）。OC: `OCComponents.TIER`。 */
  val TIER: Type[Byte] = persistentShared("tier", ScalaCodec.BYTE, ScalaStreamCodec.BYTE)

  /** 「反转触摸模式」开关（潜行右键才开 GUI）。OC: `OCComponents.INVERT_TOUCH`（Unit 型）。 */
  val INVERT_TOUCH: Type[Unit] = persistentSharedUnit("invert_touch")

  /** 屏幕上一次是否收到红石输入（只存服务端）。OC: `OCComponents.HAS_REDSTONE_INPUT`。 */
  val HAS_REDSTONE_INPUT: Type[Boolean] = persistent("has_redstone_input", ScalaCodec.BOOL)

  /** 屏幕外壳渲染色（档位色；拼合时同色才合并）。OC: `OCComponents.RENDER_COLOR`。 */
  val RENDER_COLOR: Type[ColorRGBA] = persistentShared("render_color", ColorRGBA.CODEC, ScalaStreamCodec.COLOR_RGBA)

  /** 4/8/16bpp 调色板。OC: `OCComponents.PALETTE`。 */
  val PALETTE: Type[List[Int]] = persistentShared("palette",
    ScalaCodec.list(ScalaCodec.INT), ScalaStreamCodec.list(ScalaStreamCodec.INT))

  /** 字符面 + 颜色面 + 前景/背景色快照。OC: `OCComponents.TEXT_BUFFER`（persistent，不走网络同步）。 */
  val TEXT_BUFFER: Type[TextBufferContents] = persistent("text_buffer", TextBufferContents.CODEC)

  /**
   * 组件节点地址。
   *
   * OC: `OCComponents.ADDRESS`。屏幕的客户端侧靠它把「服务端发来的报文」对上是哪一块屏
   * （`ClientComponentTracker` 按地址查组件）——报文链本身是任务 C，但**这个 component 类型
   * 必须现在就有**，否则任务 C 落地时得回头改注册表。
   */
  val ADDRESS: Type[String] = persistentShared("address", Codec.STRING, ByteBufCodecs.STRING_UTF8)

  /** 屏幕是否"开着"（component.turnOn/turnOff 的状态）。OC: `OCComponents.IS_ON`。 */
  val IS_ON: Type[Boolean] = persistentShared("is_on", ScalaCodec.BOOL, ScalaStreamCodec.BOOL)

  /** 屏幕当前是否**实际**有电（供电不足时与 IS_ON 不同）。OC: `OCComponents.IS_POWERED`。 */
  val IS_POWERED: Type[Boolean] = persistentShared("is_powered", ScalaCodec.BOOL, ScalaStreamCodec.BOOL)

  /** 高精度鼠标模式（T3+ 才有）。OC: `OCComponents.IS_PRECISE`。 */
  val IS_PRECISE: Type[Boolean] = persistentShared("is_precise", ScalaCodec.BOOL, ScalaStreamCodec.BOOL)

  /** 物理最大分辨率（档位能力，不含运行时改过的 viewport）。OC: `OCComponents.MAX_VIDEO_MODE`。 */
  val MAX_VIDEO_MODE: Type[MaximumVideoMode] =
    persistentShared("max_video_mode", MaximumVideoMode.CODEC, MaximumVideoMode.STREAM_CODEC)

  /** 当前 viewport 分辨率（gpu.setResolution 之后的实际值）。OC: `OCComponents.VIDEO_MODE`。 */
  val VIDEO_MODE: Type[VideoMode] =
    persistentShared("video_mode", VideoMode.CODEC, VideoMode.STREAM_CODEC)
}
