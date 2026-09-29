package com.hdf.cryptand.neoforge.truescreen

import com.hdf.cryptand.neoforge.truescreen.common.datacomponents.TrueScreenComponents
import com.hdf.cryptand.neoforge.truescreen.common.{Tier, TrueScreenServerTasks, block, blockentity}
import com.hdf.cryptand.neoforge.truescreen.integration.opencomputers.DriverScreen
import net.minecraft.core.{BlockPos, Direction}
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.{BlockBehaviour, BlockState}
import net.minecraft.world.level.material.MapColor
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent
import net.neoforged.neoforge.registries.{DeferredBlock, DeferredHolder, DeferredItem, DeferredRegister}

/**
 * 屏幕相关条目的**唯一**注册入口（cryptand 命名空间）。
 *
 * <h3>⚠ 任务 C 要做的唯一一件事</h3>
 * 在 mod 构造期（子包 init，与 CryptandOcDrivers.register() 同一时机）调用一次：
 * {{{
 *   com.hdf.cryptand.neoforge.truescreen.TrueScreenContent.register(modBus)
 * }}}
 * 它会依次完成：方块 / 物品 / BE 类型 / 屏幕 DataComponent 的注册 + OC 的 SidedEnvironment
 * capability + 我方屏幕驱动注册 + 服务端延迟任务队列的监听。
 * **不要**在别处再注册这些屏幕条目。
 *
 * ⚠ 时机：Driver.add 必须在 OC 的驱动表上锁前（init 期）；失败只记日志，不影响启动
 * （与既有 CryptandOcDrivers.register() 同款兜底）。
 *
 * <h3>id（任务 C 已收编）</h3>
 * 旧的自研真彩屏（soc/content/SocContent.java 里的 cryptand:truescreen 方块/物品/BE）
 * 已在任务 C 里**整条删除**，真彩屏这个 id 从此由移植件独占：
 * <ul>
 *   <li>方块 / 物品：cryptand:truescreen1..4（墙屏 T1..T4）、cryptand:truescreenflat1..4
 *       （平板屏正面）、cryptand:truescreenflatback1..4（平板屏背面）；</li>
 *   <li>BE 类型：cryptand:truescreen_display（一个类型服务全部档位/形态，与 OC 的
 *       BlockEntityTypes.SCREEN 同构）。</li>
 * </ul>
 * id 只在这一个文件里声明（改名的唯一入口）。
 *
 * <h3>capability</h3>
 * NeoForge 1.21.1 **不会**自动发现"BE 实现了某个接口"，OC 只给它自己的 BE 类型注册
 * opencomputers:sided_environment（javap + 源码双核实）。所以这里必须自己注册，
 * 否则玩家的机器永远看不见我们的屏幕节点。移植后的 blockentity.Screen **本身就是**
 * SidedEnvironment（旧 TrueScreenBlockEntity 刻意不 implements OC 接口；该类已于任务 F-2
 * （2026-09-27）删除，这里只留历史说明）。
 */
object TrueScreenContent {

  private val BLOCKS: DeferredRegister.Blocks = DeferredRegister.createBlocks("cryptand")
  private val ITEMS: DeferredRegister.Items = DeferredRegister.createItems("cryptand")
  private val BE_TYPES: DeferredRegister[BlockEntityType[_]] =
    DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, "cryptand")

  /** 墙屏：与 OC 的 defaultProps 一致（OCBlocks.scala:17）。 */
  private def wallProps: BlockBehaviour.Properties =
    BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(2, 5)

  /** 平板屏：OC 用 defaultProps.noOcclusion（薄片不该挡光）。 */
  private def flatProps: BlockBehaviour.Properties = wallProps.noOcclusion()

  // 2026-09-29 定案：真彩屏**只有单格方块**（1×1 格），靠拼接扩展；**平面/背面等额外变体不注册**。
  // 四档墙屏 = OC 的四种色深（tier 0..3 → ColorDepth.OneBit/FourBit/EightBit/SixteenBit，
  // 见 TrueScreenSettings.screenDepthsByTier；OC 的 ColorDepth 枚举只有这 4 档，见
  // api/internal/TextBuffer.java:654-674 —— 2bit/24bit/32bit 在 OC 语义里不存在）。
  private val wallNames = Array("truescreen1", "truescreen2", "truescreen3", "truescreen4")
  private val flatFrontNames = Array.empty[String]
  private val flatBackNames = Array.empty[String]

  // 物品的 supplier 引用方块的 Holder.get()：方块注册事件在物品之前（原版注册顺序），
  // 这也是 NeoForge 上"方块 + BlockItem"的标准写法。
  /**
   * 该屏的 LOD 阶梯：**注册时传入**（用户 2026-09-29："oc 部分只需要注册时传入这个参数即可"）。
   *
   * <p>⚠ 注册期**不能读配置**：方块注册发生在 NeoForge 配置绑定之前，那时
   * `ConfigValue.get()` 会抛 "Trying to access unbound value"（本轮真机踩到过）。
   * 所以注册期只传**内置默认**（与配置默认表一致）；**运行期以配置为准** ——
   * 客户端渲染侧（TrueScreenVram）在第一次用到时懒读配置，解析失败会明确抛错而不是静默用默认。</p>
   */
  private def screenLodStairs(): com.hdf.cryptand.lod.LodStairs = com.hdf.cryptand.lod.LodStairs.defaults()

  val WALL_SCREENS: Array[DeferredBlock[block.Screen]] = wallNames.zipWithIndex.map { case (name, tier) =>
    BLOCKS.register(name, () => new block.Screen(wallProps, tier, screenLodStairs()))
  }
  val WALL_ITEMS: Array[DeferredItem[block.Item]] = wallNames.zipWithIndex.map { case (name, index) =>
    ITEMS.register(name, () => new block.Item(WALL_SCREENS(index).get(), new Item.Properties()))
  }

  val FLAT_SCREENS: Array[DeferredBlock[block.FlatScreen]] = flatFrontNames.zipWithIndex.map { case (name, tier) =>
    BLOCKS.register(name, () => new block.FlatScreen(flatProps, tier, false))
  }
  val FLAT_ITEMS: Array[DeferredItem[block.Item]] = flatFrontNames.zipWithIndex.map { case (name, index) =>
    ITEMS.register(name, () => new block.Item(FLAT_SCREENS(index).get(), new Item.Properties()))
  }

  val FLAT_BACK_SCREENS: Array[DeferredBlock[block.FlatScreen]] = flatBackNames.zipWithIndex.map { case (name, tier) =>
    BLOCKS.register(name, () => new block.FlatScreen(flatProps, tier, true))
  }
  val FLAT_BACK_ITEMS: Array[DeferredItem[block.Item]] = flatBackNames.zipWithIndex.map { case (name, index) =>
    ITEMS.register(name, () => new block.Item(FLAT_BACK_SCREENS(index).get(), new Item.Properties()))
  }

  /**
   * 全部屏幕方块（给 BE 类型的 valid-blocks 列表用；显式标成 Block 以免 Scala 的 Array 不变性捣乱）。
   *
   * ⚠ **必须是 lazy**（2026-09-27 任务 D 离线闸门抓到的真机崩溃点）：这个 val 里对
   * \`DeferredBlock.get()\` 求值，而 object 初始化发生在 {@link #register} 被调用的那一刻 ——
   * 早于 \`BLOCKS.register(modBus)\`，此时 holder **还没绑定**：
   *   java.lang.NullPointerException: Trying to access unbound value:
   *     ResourceKey[minecraft:block / cryptand:truescreen1]
   * 真机上的表现是：这一行在 SocContent 的 try/catch 里被吞成"真彩屏注册失败"，
   * 症状与"类不可见"完全一样（屏幕一个都没注册）。改成 lazy 后它只在 BE 类型的 supplier 里
   * 被求值 —— 那时方块注册事件已经跑完，holder 已绑定（方块注册顺序在 BE 类型之前）。
   */
  private lazy val allScreenBlocks: Array[Block] =
    WALL_SCREENS.map(_.get(): Block) ++ FLAT_SCREENS.map(_.get(): Block) ++ FLAT_BACK_SCREENS.map(_.get(): Block)

  /** 屏幕 BE 类型：一个类型服务全部档位 / 全部形态（与 OC 的 BlockEntityTypes.SCREEN 同构）。 */
  val SCREEN_BE: DeferredHolder[BlockEntityType[_], BlockEntityType[blockentity.Screen]] =
    BE_TYPES.register("truescreen_display", () => BlockEntityType.Builder
      .of((pos: BlockPos, state: BlockState) => new blockentity.Screen(pos, state, tierOf(state)), allScreenBlocks: _*)
      .build(null))

  /** 从方块状态取档位（与 OC BlockEntityTypes.createScreen 同款：档位写在方块上）。 */
  private def tierOf(state: BlockState): Int = state.getBlock match {
    case screen: block.Screen => screen.tier
    case _ => Tier.One
  }

  private var registered = false

  /**
   * 创造栏监听（2026-09-28 修）。
   *
   * ⚠ 为什么必须用 @SubscribeEvent **注解方法**，而不是 `NeoForge.EVENT_BUS.addListener(lambda)`：
   *   NeoForge 的总线靠监听器的**泛型签名**判定它要收哪个事件，而 Scala 的 lambda 编译后泛型被擦除，
   *   真机直接抛 IllegalArgumentException（"takes an argument that is not valid for this bus"）
   *   —— 异常穿出 register()、被 SocContent 兜住 ⇒ 整个真彩屏被判「注册失败，屏幕不可用」。
   *   注解方法走方法签名（Scala 保留参数类型），是安全路径。
   */
  private object TabItems {

    @net.neoforged.bus.api.SubscribeEvent
    def onBuildTab(event: net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent): Unit = {
      if (event.getTabKey == com.hdf.cryptand.neoforge.soc.content.SocContent.SOC_TAB.getKey) {
        val visibility = net.minecraft.world.item.CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS
        WALL_ITEMS.foreach(i => event.accept(new net.minecraft.world.item.ItemStack(i.get()), visibility))
        FLAT_ITEMS.foreach(i => event.accept(new net.minecraft.world.item.ItemStack(i.get()), visibility))
        FLAT_BACK_ITEMS.foreach(i => event.accept(new net.minecraft.world.item.ItemStack(i.get()), visibility))
      }
    }
  }

  def register(modBus: IEventBus): Unit = {
    if (registered) return
    registered = true

    // LOD 后端链（用户定案：「lod 后端有自实现兜底」+「配置可以配置后端链条，优先使用第一有效」）：
    // 注册期**不能**读 NeoForge 配置（会抛 Trying to access unbound value），所以这里只注入 lambda，
    // 真正的读取推迟到首次使用（Lod.ready()）；内置兜底 builtin 由注册表构造时自动登记且永远垫底。
    com.hdf.cryptand.lod.Lod.setBackendLogger(message => TrueScreenLog.log.info(message))
    com.hdf.cryptand.lod.Lod.setChainSupplier(new java.util.function.Supplier[String] {
      override def get(): String =
        com.hdf.cryptand.neoforge.opencomputers.config.ConfigOpenComputers.trueScreenLodBackendChain()
    })
    TrueScreenLog.log.info("[TrueScreen] LOD 后端链已注入（内置兜底 builtin；配置在首次使用时读取）")

    BLOCKS.register(modBus)
    ITEMS.register(modBus)
    BE_TYPES.register(modBus)
    TrueScreenComponents.register(modBus)

    modBus.addListener((event: RegisterCapabilitiesEvent) => registerCapabilities(event))

    // 服务端"延迟到下一 tick"的队列（OC EventHandler.scheduleServer 的最小替代，见该类注释）。
    net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(TrueScreenServerTasks)

    // ★ 报文通道（任务 C）：cryptand:truescreen 一个双向 payload + 服务端处理器就位。
    network.TrueScreenNetwork.register(modBus)

    // ★ 客户端三条注册线（模型接管 / 独立贴图 / 方块实体渲染器）+ 客户端报文处理器。
    //   ⚠ 必须 dist 判断后再引用：client.** 里的类引用 net.minecraft.client.*，
    //     专用服务端上不存在（与 SocEntry 调 SocClientSetup.registerMod 同一个模式）。
    if (net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
      com.hdf.cryptand.neoforge.truescreen.client.TrueScreenClientSetup.register(modBus)
    }

    registerDrivers()

    // ★ 创造栏：真彩屏三组物品（墙屏 4 档 / 平板正 4 档 / 平板背 4 档）**加进 Cryptand 芯片页**
    //   （2026-09-28 用户："真彩屏没有加入到创造栏"）。
    //   为什么不改 SocContent：Java 看不到"同一回合新加的 Scala 成员"（compileJava 先于 compileScala）；
    //   而 BuildCreativeModeTabContentsEvent 是游戏总线事件，Scala 侧加监听即可，方向是 Scala → Java，成立。
    //   ⚠ 注册走本文件的 TabItems 注解方法，原因见 TabItems 注释（Scala lambda 会被总线拒，
    //     而且异常会把整个 register() 拖挂 ⇒ 屏幕不可用）。
    //   （2026-09-28 二次修：BuildCreativeModeTabContentsEvent 是**模组总线**事件 —— 每个模组一份
    //     tab 内容事件；挂到游戏总线上会被总线拒：argument is not valid for this bus。第一次修错在总线，
    //     第二次才对：注解方法 + modBus。）
    modBus.register(TabItems)

    TrueScreenLog.log.info("[TrueScreen] 屏幕方块/BE/数据组件已注册（cryptand 命名空间，墙屏 {} 档 + 平板屏 {} 档（正/背）；BE 类型 truescreen_display）",
      Int.box(wallNames.length), Int.box(flatFrontNames.length * 2))
  }

  /**
   * OC 的 sided_environment capability。
   *
   * 移植后的 blockentity.Screen 自己就实现 SidedEnvironment，所以直接把 BE 交出去；
   * 服务端 / 客户端同一份实现（OC 的网络只在服务端存在，客户端侧只需要 canConnect 供渲染判断）。
   */
  private def registerCapabilities(event: RegisterCapabilitiesEvent): Unit = {
    event.registerBlockEntity(
      li.cil.oc.common.Capabilities.SidedEnvironmentCapability,
      SCREEN_BE.get(),
      // 显式把返回值标成 SidedEnvironment：ICapabilityProvider 在 T 上不变，
      // 若让 Scala 推断成 Screen，就与 capability 声明的组件类型对不上。
      (be: blockentity.Screen, side: Direction) => (be: li.cil.oc.api.network.SidedEnvironment))
    TrueScreenLog.log.info("[TrueScreen] 已注册 OC 的 sided_environment capability（屏幕节点才能进 OC 网络）")
  }

  /**
   * 把屏幕物品接进 OC 的驱动表（**必须在 init 期**：OC 的驱动表在 init 结束后上锁）。
   * 复用 OC 的公开 API api.Driver.add，失败只记日志（不影响游戏启动）。
   */
  private def registerDrivers(): Unit = {
    try {
      li.cil.oc.api.Driver.add(DriverScreen)
      li.cil.oc.api.Driver.add(DriverScreen.Provider)
      TrueScreenLog.log.info("[TrueScreen] 已注册我方屏幕驱动（Slot.Upgrade，EnvironmentProvider → component.Screen）")
    } catch {
      case t: Throwable =>
        TrueScreenLog.log.warn("[TrueScreen] 屏幕驱动注册失败（通常是时机太晚，OC 的 driver 表已上锁）", t)
    }
  }
}
