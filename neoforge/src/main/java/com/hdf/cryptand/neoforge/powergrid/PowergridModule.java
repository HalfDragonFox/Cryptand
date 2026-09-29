/**
 * ===== 交错电网(PowerGrid)联动模块主类（2026-08-24，模块化） =====
 *
 * shouldLoad()=enablePowergridSupport（config/cryptand/powergrid.toml）：
 * 关闭 → 完全原版 PowerGrid，不注册本 mod 创造源等内容；
 * Mixin 由 powergrid mixin 配置 + CryptandMixinPlugin 子管理（同开关）。
 * 网络包注册仍由 powergrid/creative/CreativeSourceRegistration（子包内自持）。
 */

package com.hdf.cryptand.neoforge.powergrid;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.core.sound.MotorSoundPayload;
import com.hdf.cryptand.neoforge.core.sound.TransformerSoundPayload;
import com.hdf.cryptand.neoforge.powergrid.block.CreativeSources;
import com.hdf.cryptand.neoforge.powergrid.client.wire.BlockedHighlightRenderer;
import com.hdf.cryptand.neoforge.powergrid.client.wire.WireOutlineRenderer;
import com.hdf.cryptand.neoforge.powergrid.client.wire.WirePlacementPreviewRenderer;
import com.hdf.cryptand.neoforge.powergrid.measurement.ServerMeasurementSystem;
import com.hdf.cryptand.neoforge.powergrid.net.WindingTurnsPayload;
import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.powergrid.mixin.takeover.BlockEntityTypeAccessor;

import com.hdf.cryptand.neoforge.powergrid.motor.singlephase.SinglePhaseAsyncMotors;
import com.hdf.cryptand.neoforge.powergrid.motor.singlephase.SinglePhaseAsyncMotorsClient;
import com.hdf.cryptand.neoforge.powergrid.net.AcSourceUpdatePayload;
import com.hdf.cryptand.neoforge.powergrid.net.LibraryListRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.net.LibraryListResponsePayload;
import com.hdf.cryptand.neoforge.powergrid.net.MultimeterRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.net.MultimeterResponsePayload;
import com.hdf.cryptand.neoforge.powergrid.net.NetworkColorPayload;
import com.hdf.cryptand.neoforge.powergrid.net.OscilloscopeRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.net.OscilloscopeResponsePayload;
import com.hdf.cryptand.neoforge.powergrid.net.ProgrammableComponentUpdatePayload;
import com.hdf.cryptand.neoforge.powergrid.net.ThermometerRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.net.ThermometerResponsePayload;
import com.hdf.cryptand.neoforge.powergrid.net.ValueUnitUpdatePayload;
import com.hdf.cryptand.neoforge.powergrid.net.WireBlockedPayload;
import com.hdf.cryptand.neoforge.powergrid.net.WireCutPayload;
import com.hdf.cryptand.neoforge.powergrid.net.WireGraphSyncPayload;
import com.hdf.cryptand.neoforge.powergrid.net.WirePlacementPayload;
import com.hdf.cryptand.neoforge.powergrid.network.wire.CryptandWireCutHandler;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireOverlengthChecker;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class PowergridModule {

    private PowergridModule() {
    }

    /**
     * 注册到【游戏总线】并打印结果（2026-09-11）：
     * 原先整块 try/catch(ignored) 会吞掉注册失败 → 客户端渲染/交互整体静默失效且无从诊断。
     * 判别：@SubscribeEvent 方法的第一个参数若是 neoforge.event.* / neoforge.client.event.*
     *（RenderLevelStageEvent、ClientTickEvent、ServerStoppingEvent、交互事件…）→ 必须挂
     * NeoForge.EVENT_BUS；只有 fml.event.* / registries.* / payload 注册才用 MOD 总线。
     */
    private static void cryptand$gameBus(Class<?> cls, String name) {
        try {
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(cls);
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[Powergrid] game-bus listener OK: {}", name);
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(
                    "[Powergrid] game-bus listener FAILED: {}", name, t);
        }
    }

    /** 是否启用交错电网支持/接管（NeoForge 官方 spec；构造期 spec 未加载 → spec 默认 true） */
    public static boolean shouldLoad() {
        // ⚠ 构造期 spec 未加载 → 预读配置文件（enablePowergridSupport=false 时
        // 子包内容不注册——物品/方块不被创建）。
        return ConfigPowerGrid.SPEC.isLoaded()
                ? ConfigPowerGrid.ENABLE_POWERGRID_SUPPORT.get()
                : com.hdf.cryptand.neoforge.core.config.ConfigLoad
                        .preloadBoolean("powergrid", "enablePowergridSupport", true);
    }

    /** 注册 powergrid 内容（创造源/电容/电感/可编程/测量工具等） */
    public static void register(IEventBus bus) {
        // ⚠ 2026-09-11 用户：两个主开关【完全分隔】——交错电网支持已开但电路仿真核心未开
        // → 给 MC 报错显示（避免静默的半工作状态：接管/转换/测量都不可用）。
        try {
            if (ConfigPowerGrid.ENABLE_POWERGRID_SUPPORT.get()
                    && !com.hdf.cryptand.neoforge.simulator.config.ConfigCircuit
                            .ENABLE_CRYPTAND_SIMULATION.get()) {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(
                        "[Cryptand] 配置错误：交错电网(PowerGrid)支持已启用，但电路仿真核心"
                                + "(enableCryptandSimulation)未启用 —— 接管/导线转换/测量等"
                                + "全部不可用。请开启 circuit-simulation.toml 的"
                                + "enableCryptandSimulation，或关闭 powergrid.toml 的"
                                + "enablePowergridSupport。");
            }
        } catch (Throwable ignored) {
        }
        CreativeSources.register(bus);
        // ===== 2026-09-13 寄生式接管 POC（电机）：把原版 BE 工厂换成我们的子类 =====
        // 用户方案：必须加载交错电网 mod（避嫌），但原本全套加载机制由我们接管。
        // 注册 id 不变 ⇒ 旧存档/配方/生态照旧；实例类型换成 CryptandElectricMotorBE
        // （继承原版 → NBT 全兼容）。工厂替换须早于世界加载 → FMLCommonSetupEvent。
        bus.addListener((net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent ev) ->
                ev.enqueueWork(() -> {
                    // 三个电机统一处理（反射取注册字段，避免与 PowerGrid 字段名耦合）
                    cryptand$replaceMotorFactory("ELECTRIC_MOTOR",
                            "CryptandElectricMotorBE");
                    cryptand$replaceMotorFactory("CONSTANT_SPEED_MOTOR",
                            "CryptandConstantSpeedMotorBE");
                    cryptand$replaceMotorFactory("SERVO", "CryptandServoMotorBE");
                    // ===== 全部电气设备移植（2026-09-13 用户：先把所有电气设备进行移植）=====
                    // 表驱动：CryptandBlockEntities.TABLE = {注册字段名, 自有 BE 类}
                    int ok = 0;
                    for (Object[] row : com.hdf.cryptand.neoforge.powergrid.be
                            .CryptandBlockEntities.TABLE) {
                        if (row != null && row.length >= 2 && row[0] instanceof String f
                                && row[1] instanceof Class<?> k) {
                            if (cryptand$replaceFactoryByClass(f, k)) {
                                ok++;
                            }
                        }
                    }
                    com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                            "[DeviceBE] 电气设备 BE 移植完成: {}/{} 项",
                            ok, com.hdf.cryptand.neoforge.powergrid.be
                                    .CryptandBlockEntities.TABLE.length);
                    // ===== 自定义电机【应力倍数】注入（2026-09-13 用户：应力为可配置
                    //  倍数×扭矩，反馈也是需要除以倍数）=====
                    // common 层零 MC 依赖、不读配置 → 由平台侧在此注入（改配置需重启）。
                    // 语义：输出应力 = |扭矩|×16×倍数；反馈（BE 上报应力 → 负载扭矩）
                    // 除以同一倍数 ⇒ 对称可逆（InductionMotorModel.stressSuPerNm）。
                    try {
                        com.hdf.cryptand.circuitsimulation.model.composite
                                .InductionMotorModel.setStressScale(
                                        com.hdf.cryptand.neoforge.powergrid.config
                                                .ConfigPowerGrid.MOTOR_STRESS_SCALE.get());
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[MotorStress] 应力倍数 = {}（应力 = |扭矩|×16×倍数，反馈 ÷ 同倍数）",
                                com.hdf.cryptand.circuitsimulation.model.composite
                                        .InductionMotorModel.stressScale());
                    } catch (Throwable ignored) {
                    }
                    // ===== 2026-09-15 用户："进入存档直接从 sqlite 恢复" =====
                    // 注册通用复合元件恢复工厂：NetworkStructureCodec.decode 遇到
                    // byte 5 通用条目（compositeKey + 类名 + 展开元件 + KV）时，
                    // 交回【对应设备的组装器】重建真实模型并重新绑定。
                    // 没有它，含电机等设备的电路从 SQLite 恢复时只会丢元件。
                    com.hdf.cryptand.neoforge.powergrid.device.CryptandCompositeFactory
                            .register();
                }));
        // ⚠ 2026-08-30：@EventBusSubscriber(bus=Bus.MOD) 在 NeoForge 21.1.231 已
        // 标记 [removal]（弃用待删，javac removal=error）→ 改【编程式注册】：
        // 把 Network 类的 @SubscribeEvent 静态方法挂到 MOD 总线（bus 即 MOD 总线）。
        bus.register(Network.class);
        // ⚠ 2026-08-30：游戏事件类改为显式注册（原 @EventBusSubscriber 自动注册会
        // 绕过子包 enabled——关闭子包后仍在每 tick 扫描）。
        // ⚠⚠ 2026-09-11 【powergrid 子包 init 失败根因，实测】：PowergridGameEvents 监听的是
        // 【游戏总线】事件（ServerTickEvent / ChunkEvent / EntityPlaceEvent / ServerStoppingEvent /
        // LevelEvent …）——原先 @EventBusSubscriber 默认就是 game bus；改成 bus.register(...)
        // （MOD 总线）后 NeoForge 注册时校验失败：
        //   IllegalArgumentException: Method public static void
        //     com.hdf.cryptand.neoforge.powergrid.PowergridGameEvents.xxx(...)
        // → 整个 PowergridModule.register 抛异常中断（[Subpackage] 模块 powergrid init 失败）
        // → 后面【所有】注册都没执行（含 game-bus 的渲染/交互类：导线轮廓、放置预览、
        //   万用表表笔线、客户端声音 tick、剪线交互）→ 导线放上却不显示、表笔线不显示。
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(
                com.hdf.cryptand.neoforge.powergrid.PowergridGameEvents.class);
        // ⚠ 2026-08-30 子包开关彻底生效：原 @EventBusSubscriber 自动注册的 powergrid
        // 事件/渲染类改为【显式注册】（enabled=false → init 不调用 → 零监听、
        // 零每帧渲染开销——此前关闭子包后 MeasurementLineRenderer/WireOutlineRenderer
        // 等仍每帧运行，造成主线程周期性卡顿与大量内存分配）。
        // ⚠⚠ 2026-09-11 关键修复（"导线/表笔/预览全都不显示"根因）＋可诊断化：
        // 以下类监听【游戏总线（game bus）】事件（RenderLevelStageEvent / ClientTickEvent /
        // 玩家交互 / ServerStoppingEvent 等）；原先 @EventBusSubscriber 默认就是 game bus，
        // 2026-08-30 改显式注册时误挂 MOD 总线（bus）→ 监听器全部静默失效。
        // 且原代码整块包在 try/catch(ignored) 里 → 失败不可见。现逐个注册并打印结果。
        cryptand$gameBus(com.hdf.cryptand.neoforge.powergrid.network.wire.CryptandWireCutHandler.class,
                "CryptandWireCutHandler");
        cryptand$gameBus(com.hdf.cryptand.neoforge.powergrid.network.wire.WireOverlengthChecker.class,
                "WireOverlengthChecker");
        if (net.neoforged.fml.loading.FMLEnvironment.dist
                == net.neoforged.api.distmarker.Dist.CLIENT) {
            cryptand$gameBus(com.hdf.cryptand.neoforge.powergrid.client.wire.BlockedHighlightRenderer.class,
                    "BlockedHighlightRenderer");
            cryptand$gameBus(com.hdf.cryptand.neoforge.powergrid.client.ClientSoundTicker.class,
                    "ClientSoundTicker");
            cryptand$gameBus(com.hdf.cryptand.neoforge.powergrid.client.MeasurementLineRenderer.class,
                    "MeasurementLineRenderer");
            cryptand$gameBus(com.hdf.cryptand.neoforge.powergrid.client.wire.WirePlacementPreviewRenderer.class,
                    "WirePlacementPreviewRenderer");
            cryptand$gameBus(com.hdf.cryptand.neoforge.powergrid.client.wire.WireOutlineRenderer.class,
                    "WireOutlineRenderer");
            // ⚠ 2026-09-11 修正（实测日志）：SinglePhaseAsyncMotorsClient 监听的是【MOD 总线】
            // 事件（EntityRenderersEvent.RegisterRenderers —— 与 RailwayClient 同类：属于
            // "注册类"事件而非运行时事件）→ 不能挂 game bus，否则 EventBus 校验失败：
            //   IllegalArgumentException: Method ...onRegisterRenderers(EntityRenderersEvent$...)
            // 判据修正：注册/初始化类事件（EntityRenderersEvent / *Register*Event / payload
            // 注册）→ MOD 总线；运行时事件（RenderLevelStageEvent / ClientTickEvent / 交互）
            // → game bus。
            try {
                bus.register(com.hdf.cryptand.neoforge.powergrid.motor.singlephase.SinglePhaseAsyncMotorsClient.class);
            } catch (Throwable t) {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(
                        "[Powergrid] mod-bus listener FAILED: SinglePhaseAsyncMotorsClient", t);
            }
        }
        // ⚠ 2026-08-30 单相异步电机（2/4/6/8 极，2kW）——替代电容启动电机（已删除）
        SinglePhaseAsyncMotors.register(bus);
        // ★ 2026-09-07 core 服务注册（core.api）：/cryptand library set 目标方块处理器
        com.hdf.cryptand.neoforge.core.api.CryptandServices.register(
                com.hdf.cryptand.neoforge.core.api.CryptandLibraryTarget.class,
                new com.hdf.cryptand.neoforge.powergrid.ProgrammableLibraryTarget());
    }

    /**
     * ===== 把某个原版 BE 注册项的工厂换成 Cryptand 自有 BE（2026-09-13 寄生式接管）=====
     *
     * 用户方案："必须加载交错电网 mod（避嫌），但原本全套加载机制由我们接管"。
     * - 反射取 ModdedBlockEntities 的注册项（避免与 PowerGrid 字段名编译期耦合）
     * - 注册 id 不变 ⇒ 旧存档 / 配方 / 标签 / 其它 mod 引用全部照旧
     * - 自有 BE 继承原版 ⇒ NBT 全兼容、instanceof 关系不断
     * - 工厂替换必须早于任何世界/区块加载（调用点：FMLCommonSetupEvent）
     *
     * @param field       ModdedBlockEntities 里的静态字段名（如 ELECTRIC_MOTOR / SERVO）
     * @param beClassName com.hdf.cryptand.neoforge.powergrid.motor 下的 BE 类简单名
     */
    /**
     * 通用工厂替换（2026-09-13 全部电气设备移植）：把 ModdedBlockEntities 某注册项的
     * BE 工厂换成给定自有 BE 类（继承原版 ⇒ NBT 兼容；注册 id 不变 ⇒ 存档/生态不变）。
     *
     * @return 是否成功替换
     */
    private static boolean cryptand$replaceFactoryByClass(String field, Class<?> beClass) {
        try {
            Object entry = org.patryk3211.powergrid.collections.ModdedBlockEntities.class
                    .getField(field).get(null);
            Object typeObj = entry.getClass().getMethod("get").invoke(entry);
            if (!(typeObj instanceof net.minecraft.world.level.block.entity
                    .BlockEntityType<?> type)) {
                return false;
            }
            java.lang.reflect.Constructor<?> ctor = beClass.getConstructor(
                    net.minecraft.world.level.block.entity.BlockEntityType.class,
                    net.minecraft.core.BlockPos.class,
                    net.minecraft.world.level.block.state.BlockState.class);
            ((com.hdf.cryptand.neoforge.powergrid.mixin.takeover.BlockEntityTypeAccessor) type)
                    .cryptand$setFactory((pos, state) -> {
                        try {
                            return (net.minecraft.world.level.block.entity.BlockEntity)
                                    ctor.newInstance(type, pos, state);
                        } catch (Throwable t) {
                            return null;
                        }
                    });
            return true;
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.warn(
                    "[DeviceBE] 工厂替换跳过 {}: {}", field, String.valueOf(t.getMessage()));
            return false;
        }
    }

    private static void cryptand$replaceMotorFactory(String field, String beClassName) {
        try {
            Object entry = org.patryk3211.powergrid.collections.ModdedBlockEntities.class
                    .getField(field).get(null);
            Object typeObj = entry.getClass().getMethod("get").invoke(entry);
            if (!(typeObj instanceof net.minecraft.world.level.block.entity
                    .BlockEntityType<?> type)) {
                return;
            }
            final Class<?> beClass = Class.forName(
                    "com.hdf.cryptand.neoforge.powergrid.motor." + beClassName);
            ((com.hdf.cryptand.neoforge.powergrid.mixin.takeover.BlockEntityTypeAccessor) type)
                    .cryptand$setFactory((pos, state) -> {
                        try {
                            return (net.minecraft.world.level.block.entity.BlockEntity)
                                    beClass.getConstructor(
                                            net.minecraft.world.level.block.entity
                                                    .BlockEntityType.class,
                                            net.minecraft.core.BlockPos.class,
                                            net.minecraft.world.level.block.state
                                                    .BlockState.class)
                                            .newInstance(type, pos, state);
                        } catch (Throwable t) {
                            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(
                                    "[MotorBE] BE 构造失败: {}", beClassName, t);
                            return null;
                        }
                    });
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                    "[MotorBE] 工厂已替换: {} -> {}", field, beClassName);
        } catch (Throwable t) {
            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.error(
                    "[MotorBE] 工厂替换失败: {}", field, t);
        }
    }

    /** 服务端 tick：测量系统（同网络请求合并求解后回发） */
    public static void tick(ServerLevel level) {
        if (!shouldLoad() || level == null) {
            return;
        }
        try {
            ServerMeasurementSystem.tick(level);
        } catch (Throwable ignored) {
        }
    }

    /** MOD 总线：powergrid 网络包注册（子包自持；shouldLoad=false 全部不注册）。
     *  ⚠ 2026-08-30：bus=Bus.MOD 弃用 → 由 PowergridModule.register(bus) 编程式
     *  注册本类（bus.register(Network.class)），不再用 @EventBusSubscriber。 */
    public static final class Network {

        private Network() {
        }

        @SubscribeEvent
        public static void registerPayloads(RegisterPayloadHandlersEvent event) {
            if (!shouldLoad()) {
                return;
            }
            PayloadRegistrar registrar = event.registrar(Cryptand.MOD_ID)
                    .versioned("1").optional();
            registrar.playToServer(AcSourceUpdatePayload.TYPE, AcSourceUpdatePayload.STREAM_CODEC,
                    AcSourceUpdatePayload::handle);
            registrar.playToServer(ValueUnitUpdatePayload.TYPE, ValueUnitUpdatePayload.STREAM_CODEC,
                    ValueUnitUpdatePayload::handle);
            registrar.playToServer(MultimeterRequestPayload.TYPE, MultimeterRequestPayload.STREAM_CODEC,
                    MultimeterRequestPayload::handle);
            registrar.playToClient(MultimeterResponsePayload.TYPE, MultimeterResponsePayload.STREAM_CODEC,
                    MultimeterResponsePayload::handle);
            registrar.playToServer(OscilloscopeRequestPayload.TYPE, OscilloscopeRequestPayload.STREAM_CODEC,
                    OscilloscopeRequestPayload::handle);
            registrar.playToClient(OscilloscopeResponsePayload.TYPE, OscilloscopeResponsePayload.STREAM_CODEC,
                    OscilloscopeResponsePayload::handle);
            registrar.playToServer(ThermometerRequestPayload.TYPE, ThermometerRequestPayload.STREAM_CODEC,
                    ThermometerRequestPayload::handle);
            registrar.playToClient(ThermometerResponsePayload.TYPE, ThermometerResponsePayload.STREAM_CODEC,
                    ThermometerResponsePayload::handle);
            registrar.playToClient(LibraryListResponsePayload.TYPE, LibraryListResponsePayload.STREAM_CODEC,
                    LibraryListResponsePayload::handle);
            registrar.playToServer(LibraryListRequestPayload.TYPE, LibraryListRequestPayload.STREAM_CODEC,
                    LibraryListRequestPayload::handle);
            registrar.playToServer(ProgrammableComponentUpdatePayload.TYPE,
                    ProgrammableComponentUpdatePayload.STREAM_CODEC,
                    ProgrammableComponentUpdatePayload::handle);
            registrar.playToClient(MotorSoundPayload.TYPE,
                    MotorSoundPayload.STREAM_CODEC,
                    MotorSoundPayload::handle);
            registrar.playToClient(TransformerSoundPayload.TYPE,
                    TransformerSoundPayload.STREAM_CODEC,
                    TransformerSoundPayload::handle);
            registrar.playToClient(WireGraphSyncPayload.TYPE, WireGraphSyncPayload.STREAM_CODEC,
                    WireGraphSyncPayload::handle);
            registrar.playToClient(NetworkColorPayload.TYPE, NetworkColorPayload.STREAM_CODEC,
                    NetworkColorPayload::handle);
            registrar.playToServer(WirePlacementPayload.TYPE, WirePlacementPayload.STREAM_CODEC,
                    WirePlacementPayload::handle);
            registrar.playToClient(WireBlockedPayload.TYPE, WireBlockedPayload.STREAM_CODEC,
                    WireBlockedPayload::handle);
            registrar.playToServer(WireCutPayload.TYPE, WireCutPayload.STREAM_CODEC,
                    WireCutPayload::handle);
            registrar.playToServer(WindingTurnsPayload.TYPE,
                    WindingTurnsPayload.STREAM_CODEC,
                    WindingTurnsPayload::handle);
        }
    }
}
