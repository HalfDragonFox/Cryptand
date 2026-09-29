/**
 * ===== Aeronautics 联动模块主类（2026-08-24，模块化） =====
 *
 * 可选联动（compileOnly）：未装 create_aeronautics → shouldLoad()=false，
 * 主类无需感知；其 Mixin 由 aeronautics 配置子管理。
 *
 * 2026-09-13 追加【外设船舵】（航空学子包扩展）：
 *   - 内容注册 {@link PeripheralHelmRegistry}（方块/物品/BE/标签栏）
 *   - 网络包（绑定保存 + 舵角同步）
 *   - 客户端渲染（舵轮旋转）+ 退出世界时释放外部设备
 *   - 全部受 {@code aeronautics.toml#enablePeripheralHelm}（默认 false）门控；
 *     开启时强制校验 gameinput 库同步开启，否则构造期报错。
 */

package com.hdf.cryptand.neoforge.aeronautics;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.aeronautics.config.ConfigAero;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmBindPayload;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmClient;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmGameEvents;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmRegistry;
import com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmStatePayload;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class AeronauticsModule {

    private AeronauticsModule() {
    }

    public static boolean shouldLoad() {
        return AeronauticsCompat.isLoaded();
    }

    public static void register(IEventBus bus) {
        // 外设统一标签栏（船舵 + 拉杆）：无条件注册，内容按实际开启情况动态填充
        PeripheralCreativeTab.register(bus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 客户端公共事件：核心看门狗（自愈静默停摆）+ 退出世界清残留（游戏总线）
            NeoForge.EVENT_BUS.register(PeripheralClientTicker.class);
        }
        registerPeripheralHelm(bus);
        registerPeripheralLever(bus);
        registerPeripheralRailing(bus);
        registerPeripheralTransmission(bus);
        registerPeripheralTransmissionKey(bus);
        // 外设指令 /cryptand peripheral …（任一外设开启即提供；动作转发到执行者自己的客户端）
        if (com.hdf.cryptand.neoforge.aeronautics.helm.PeripheralHelmRegistry.isRegistered()
                || com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverRegistry
                .isRegistered()
                || com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingRegistry
                .isRegistered()
                || com.hdf.cryptand.neoforge.aeronautics.transmission.PeripheralTransmissionRegistry
                .isRegistered()
                || com.hdf.cryptand.neoforge.aeronautics.transmissionkey.PeripheralTransmissionKeyRegistry
                .isRegistered()) {
            PeripheralCommands.register();
            bus.register(PeripheralNetwork.class);
            CryptandNeoForgeLog.info("[Peripheral] 指令已注册：/cryptand peripheral scan|disconnect|stop|list");
        }
    }

    /** ===== 外设模拟传动器（默认关闭；同样强制 gameinput） ===== */
    private static void registerPeripheralTransmission(IEventBus bus) {
        if (!ConfigAero.peripheralTransmissionEnabled()) {
            return;
        }
        com.hdf.cryptand.neoforge.aeronautics.transmission.PeripheralTransmissionRegistry.register(bus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bus.register(com.hdf.cryptand.neoforge.aeronautics.transmission
                    .PeripheralTransmissionClient.class);
        }
        CryptandNeoForgeLog.info("[PeripheralTransmission] 外设模拟传动器已注册"
                + "（设备轴 → 可编辑曲线 → 输出转速 0%-100%）");
    }

    /** ===== 外设模拟传动器（按键）（默认关闭；同样强制 gameinput） ===== */
    private static void registerPeripheralTransmissionKey(IEventBus bus) {
        if (!ConfigAero.peripheralTransmissionKeyEnabled()) {
            return;
        }
        com.hdf.cryptand.neoforge.aeronautics.transmissionkey.PeripheralTransmissionKeyRegistry.register(bus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bus.register(com.hdf.cryptand.neoforge.aeronautics.transmissionkey
                    .PeripheralTransmissionKeyClient.class);
        }
        CryptandNeoForgeLog.info("[PeripheralTransmissionKey] 外设模拟传动器（按键）已注册"
                + "（曲线点绑按键+时间 → 滑块推进 → 输出百分比）");
    }

    /** ===== 外设栏杆（按钮）（默认关闭；同样强制 gameinput） ===== */
    private static void registerPeripheralRailing(IEventBus bus) {
        if (!ConfigAero.peripheralRailingEnabled()) {
            return;
        }
        com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingRegistry.register(bus);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bus.register(com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingClient.class);
        }
        CryptandNeoForgeLog.info("[PeripheralRailing] 外设栏杆（按钮）已注册（按键映射表 → 0..15 档红石）");
    }

    /** MOD 总线：外设指令的 S2C 包注册。 */
    public static final class PeripheralNetwork {

        private PeripheralNetwork() {
        }

        @SubscribeEvent
        public static void registerPayloads(RegisterPayloadHandlersEvent event) {
            PayloadRegistrar registrar = event.registrar(Cryptand.MOD_ID)
                    .versioned("1").optional();
            registrar.playToClient(PeripheralCommandPayload.TYPE,
                    PeripheralCommandPayload.STREAM_CODEC, PeripheralCommandPayload::handle);
            // 外设会话：C2S 上行（客户端主动）+ S2C sable 数据（服务端被动回包）
            registrar.playToServer(PeripheralSessionPayload.TYPE,
                    PeripheralSessionPayload.STREAM_CODEC, PeripheralSessionPayload::handle);
            registrar.playToClient(PeripheralSablePayload.TYPE,
                    PeripheralSablePayload.STREAM_CODEC, PeripheralSablePayload::handle);
            // 外设模拟传动器的绑定（设备 + 轴 + 输入范围 + 曲线）
            if (com.hdf.cryptand.neoforge.aeronautics.transmission.PeripheralTransmissionRegistry
                    .isRegistered()) {
                registrar.playToServer(
                        com.hdf.cryptand.neoforge.aeronautics.transmission.PeripheralTransmissionBindPayload.TYPE,
                        com.hdf.cryptand.neoforge.aeronautics.transmission.PeripheralTransmissionBindPayload.STREAM_CODEC,
                        com.hdf.cryptand.neoforge.aeronautics.transmission.PeripheralTransmissionBindPayload::handle);
            }
            // 外设模拟传动器（按键）的绑定（设备 + 轴 + 范围 + 曲线 + 每点的按键/时间）
            if (com.hdf.cryptand.neoforge.aeronautics.transmissionkey.PeripheralTransmissionKeyRegistry
                    .isRegistered()) {
                registrar.playToServer(
                        com.hdf.cryptand.neoforge.aeronautics.transmissionkey.PeripheralTransmissionKeyBindPayload.TYPE,
                        com.hdf.cryptand.neoforge.aeronautics.transmissionkey.PeripheralTransmissionKeyBindPayload.STREAM_CODEC,
                        com.hdf.cryptand.neoforge.aeronautics.transmissionkey.PeripheralTransmissionKeyBindPayload::handle);
            }
            // 外设栏杆（按钮）的按键映射表绑定
            if (com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingRegistry.isRegistered()) {
                registrar.playToServer(
                        com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingBindPayload.TYPE,
                        com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingBindPayload.STREAM_CODEC,
                        com.hdf.cryptand.neoforge.aeronautics.railing.PeripheralRailingBindPayload::handle);
            }
        }
    }

    /** ===== 外设船舵（默认关闭；开启需同步开启 gameinput，否则此处报错） ===== */
    private static void registerPeripheralHelm(IEventBus bus) {
        if (!ConfigAero.peripheralHelmEnabled()) {
            return;
        }
        PeripheralHelmRegistry.register(bus);
        bus.register(Network.class);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bus.register(PeripheralHelmClient.class);
            // 退出世界 → 释放方向盘/手柄（游戏总线事件）
            NeoForge.EVENT_BUS.register(PeripheralHelmGameEvents.class);
        }
        CryptandNeoForgeLog.info("[PeripheralHelm] 外设船舵已注册（绑定界面 LDLib2 / 输入源 gameinput）");
    }

    /**
     * ===== 外设拉杆（默认关闭；与船舵一样强制 gameinput）=====
     * 复制航空学官方油门拉杆：保留官方"右键抓住手柄、上下拖动调档"交互，
     * 并可绑定真实踏板轴/按钮 → 线性 0..15 档 → 输出红石信号强度。
     */
    private static void registerPeripheralLever(IEventBus bus) {
        if (!ConfigAero.peripheralLeverEnabled()) {
            return;
        }
        com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverRegistry.register(bus);
        bus.register(LeverNetwork.class);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bus.register(com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverClient.class);
        }
        CryptandNeoForgeLog.info("[PeripheralLever] 外设拉杆已注册（右键开界面绑定踏板轴/按钮 → 0..15 档红石）");
    }

    /** MOD 总线：外设拉杆的网络包与创造标签栏。 */
    public static final class LeverNetwork {

        private LeverNetwork() {
        }

        @SubscribeEvent
        public static void registerPayloads(RegisterPayloadHandlersEvent event) {
            if (!com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverRegistry.isRegistered()) {
                return;
            }
            PayloadRegistrar registrar = event.registrar(Cryptand.MOD_ID)
                    .versioned("1").optional();
            registrar.playToServer(
                    com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverBindPayload.TYPE,
                    com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverBindPayload.STREAM_CODEC,
                    com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverBindPayload::handle);
            registrar.playToServer(
                    com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverStatePayload.TYPE,
                    com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverStatePayload.STREAM_CODEC,
                    com.hdf.cryptand.neoforge.aeronautics.lever.PeripheralLeverStatePayload::handle);
        }

    }

    public static void tick(ServerLevel level) {
    }

    /** MOD 总线：外设船舵网络包注册（子包自持；内容未注册 → 全部不注册）。 */
    public static final class Network {

        private Network() {
        }

        @SubscribeEvent
        public static void registerPayloads(RegisterPayloadHandlersEvent event) {
            if (!PeripheralHelmRegistry.isRegistered()) {
                return;
            }
            PayloadRegistrar registrar = event.registrar(Cryptand.MOD_ID)
                    .versioned("1").optional();
            registrar.playToServer(PeripheralHelmBindPayload.TYPE,
                    PeripheralHelmBindPayload.STREAM_CODEC, PeripheralHelmBindPayload::handle);
            registrar.playToServer(PeripheralHelmStatePayload.TYPE,
                    PeripheralHelmStatePayload.STREAM_CODEC, PeripheralHelmStatePayload::handle);
        }
    }

    /** 日志门面（避免本文件直接依赖主类静态字段的加载时序）。 */
    private static final class CryptandNeoForgeLog {
        private static void info(String msg) {
            try {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(msg);
            } catch (Throwable ignored) {
            }
        }
    }
}
