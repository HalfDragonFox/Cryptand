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
import com.hdf.cryptand.neoforge.powergrid.adapter.ServerMeasurementSystem;
import com.hdf.cryptand.neoforge.powergrid.adapter.WindingTurnsPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.AcSourceUpdatePayload;
import com.hdf.cryptand.neoforge.powergrid.creative.CreativeSources;
import com.hdf.cryptand.neoforge.powergrid.creative.LibraryListRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.LibraryListResponsePayload;
import com.hdf.cryptand.neoforge.powergrid.creative.MultimeterRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.MultimeterResponsePayload;
import com.hdf.cryptand.neoforge.powergrid.creative.NetworkColorPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.OscilloscopeRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.OscilloscopeResponsePayload;
import com.hdf.cryptand.neoforge.powergrid.creative.ProgrammableComponentUpdatePayload;
import com.hdf.cryptand.neoforge.powergrid.creative.ThermometerRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.ThermometerResponsePayload;
import com.hdf.cryptand.neoforge.powergrid.creative.ValueUnitUpdatePayload;
import com.hdf.cryptand.neoforge.powergrid.creative.WireBlockedPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.WireCutPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.WireGraphSyncPayload;
import com.hdf.cryptand.neoforge.powergrid.creative.WirePlacementPayload;
import com.electronwill.nightconfig.core.file.FileConfig;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class PowergridModule {

    private PowergridModule() {
    }

    /** 是否启用交错电网支持/接管（配置缓存，类加载阶段读取） */
    public static boolean shouldLoad() {
        return readConfigBool("config/cryptand/powergrid.toml",
                "enablePowergridSupport", true);
    }

    /** 注册 powergrid 内容（创造源/电容/电感/可编程/测量工具等） */
    public static void register(IEventBus bus) {
        CreativeSources.register(bus);
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

    /** 从指定 TOML 读取布尔配置，失败时回退默认值 */
    private static boolean readConfigBool(String path, String key, boolean defaultValue) {
        try {
            Path p = Paths.get(path);
            if (!p.toFile().exists()) {
                return defaultValue;
            }
            try (FileConfig cfg = FileConfig.of(p)) {
                cfg.load();
                return cfg.getOrElse(key, defaultValue);
            }
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    /** MOD 总线：powergrid 网络包注册（子包自持；shouldLoad=false 全部不注册） */
    @EventBusSubscriber(modid = Cryptand.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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
            registrar.playToClient(com.hdf.cryptand.neoforge.core.sound.MotorSoundPayload.TYPE,
                    com.hdf.cryptand.neoforge.core.sound.MotorSoundPayload.STREAM_CODEC,
                    com.hdf.cryptand.neoforge.core.sound.MotorSoundPayload::handle);
            registrar.playToClient(com.hdf.cryptand.neoforge.core.sound.TransformerSoundPayload.TYPE,
                    com.hdf.cryptand.neoforge.core.sound.TransformerSoundPayload.STREAM_CODEC,
                    com.hdf.cryptand.neoforge.core.sound.TransformerSoundPayload::handle);
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
