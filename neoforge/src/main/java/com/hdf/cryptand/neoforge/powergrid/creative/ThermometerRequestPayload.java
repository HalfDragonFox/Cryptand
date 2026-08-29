/**
 * ===== 温度表请求包（C2S） =====
 *
 * 客户端温度表绑定目标后每 10 tick 发送：携带测量目标
 * （targetType 0=元件方块 / 1=导线段）。服务端收到后【直接查温度存储】
 * （不做网络求解）：元件 → ThermalBehaviour / DeviceThermalStore；
 * 导线 → 实体端点定位连续段 → WireThermalStore。然后回发
 * ThermometerResponsePayload（S2C）。
 *
 * 单线连接：每次只绑一个目标（元件 或 导线），新目标覆盖旧目标。
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.circuitsimulation.netgraph.WireEdge;
import com.hdf.cryptand.circuitsimulation.netgraph.WireSegment;
import com.hdf.cryptand.neoforge.powergrid.adapter.DeviceThermalStore;
import com.hdf.cryptand.neoforge.powergrid.adapter.WireNetworkManager;
import com.hdf.cryptand.neoforge.powergrid.adapter.WireThermalStore;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.patryk3211.powergrid.electricity.base.ThermalBehaviour;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record ThermometerRequestPayload(String key, int targetType, long pos, int eid)
        implements CustomPacketPayload {

    /** 目标类型 */
    public static final int TARGET_DEVICE = 0;   // 元件方块（潜行强制）
    public static final int TARGET_WIRE = 1;     // 导线段（旧 eid 兼容）
    // 自动路由（2026-08-18 修复“加热器不发热”）：优先元件——该方块有设备温度
    // 模型（ThermalBehaviour/DeviceThermalStore）→ 元件内部温度；否则该处有导线
    // → 导线温度；否则元件回退。原实现“有导线 → 导线温度”导致接了导线的
    // 加热器永远显示导线温度（20°C），看不到加热器自身发热。
    public static final int TARGET_AUTO = -1;

    public static final Type<ThermometerRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Cryptand.MOD_ID, "thermometer_request"));

    public static final StreamCodec<ByteBuf, ThermometerRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.key());
                buf.writeInt(p.targetType());
                buf.writeLong(p.pos());
                buf.writeInt(p.eid());
            },
            buf -> new ThermometerRequestPayload(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    buf.readInt(),
                    buf.readLong(),
                    buf.readInt()));

    @Override
    public Type<ThermometerRequestPayload> type() {
        return TYPE;
    }

    /** 诊断日志节流（~2s） */
    private static volatile long THERMO_DBG_LAST;

    /** 服务端处理：直接查温度存储并回发响应（不做网络求解） */
    public static void handle(ThermometerRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.flow() == null || !context.flow().isServerbound()
                    || !(context.player() instanceof ServerPlayer sp)) {
                return;
            }
            double tempC = Double.NaN;
            boolean valid = false;
            String label = "未连接";
            String segKey = null;
            try {
                BlockPos pos = payload.pos() == Long.MIN_VALUE ? null : BlockPos.of(payload.pos());
                if (payload.targetType() == TARGET_DEVICE) {
                    // 潜行强制 → 元件内部温度
                    if (pos != null) {
                        tempC = deviceTemp(sp.level(), pos);
                        valid = true;
                        label = "元件 " + pos.toShortString();
                    }
                } else if (payload.targetType() == TARGET_AUTO) {
                    // 自动路由：优先元件（该方块有设备温度模型 → 元件内部温度）；
                    // 否则该方块有导线连接 → 导线温度；否则元件回退
                    if (pos != null) {
                        if (blockHasDevice(sp.level(), pos)) {
                            tempC = deviceTemp(sp.level(), pos);
                            valid = true;
                            label = "元件 " + pos.toShortString();
                        } else {
                            segKey = findSegmentKeyAtBlock(sp.level(), pos);
                            if (segKey != null) {
                                tempC = WireThermalStore.thermalFor(segKey).tempCelsius();
                                valid = true;
                                label = "导线段";
                            } else {
                                tempC = deviceTemp(sp.level(), pos);
                                valid = true;
                                label = "元件 " + pos.toShortString();
                            }
                        }
                    }
                } else if (payload.targetType() == TARGET_WIRE) {
                    ServerLevel level = (ServerLevel) sp.level();
                    if (payload.eid() >= 0) {
                        // 旧版：导线实体定位（自管模式无实体，仅兼容旧存档/旧 NBT）
                        Entity e = level.getEntity(payload.eid());
                        if (e instanceof BaseWireEntity wire) {
                            String k1 = endpointKey(level, wire.getEndpoint1());
                            String k2 = endpointKey(level, wire.getEndpoint2());
                            segKey = findSegmentKey(level, k1, k2);
                            if (segKey != null) {
                                tempC = WireThermalStore.thermalFor(segKey).tempCelsius();
                                valid = true;
                                label = "导线段";
                            }
                        }
                    } else if (pos != null) {
                        // 自管（2026-08-18 右键导线抓取）：按端点方块定位导线段
                        segKey = findSegmentKeyAtBlock(level, pos);
                        if (segKey != null) {
                            tempC = WireThermalStore.thermalFor(segKey).tempCelsius();
                            valid = true;
                            label = "导线段";
                        }
                    }
                }
                // 诊断（节流 ~2s）：确认温度推进状态（WireThermalStore 有温度条目
                // 且 >环境 → 推进正常；否则 roundFromGraph 未跑/段未构建）
                long now2 = System.currentTimeMillis();
                if (now2 - THERMO_DBG_LAST >= 2000) {
                    THERMO_DBG_LAST = now2;
                    try {
                        com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                                "[Thermo] type={} pos={} eid={} segKey={} temp={}C valid={} "
                                        + "wireStore={} deviceStore={}",
                                payload.targetType(),
                                payload.pos() == Long.MIN_VALUE ? "-" : BlockPos.of(payload.pos()),
                                payload.eid(), segKey, String.format("%.1f", tempC), valid,
                                WireThermalStore.size(), DeviceThermalStore.size());
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                PacketDistributor.sendToPlayer(sp,
                        new ThermometerResponsePayload(payload.key(), tempC, label, valid));
            } catch (Throwable ignored) {
            }
        });
    }

    /** 元件内部温度。2026-08-18 修复“加热器不发热”：自管模式（Cryptand 求解）
     *  下【优先 Cryptand DeviceThermalStore】（温度由 Cryptand 相量求解推进——
     *  原版 PowerGrid 时域被禁用，原版 ThermalBehaviour 温度恒环境不推进，读
     *  它会把实际在发热的加热器显示成 22°C）。仅当 Cryptand 无该方块温度模型
     *  （非自管设备）才回退原版 ThermalBehaviour。
     *  2026-08-20 加变压器：变压器温度在 TransformerHeatStore（非 DeviceThermalStore），
     *  且多方块（2x2 主+PART）需映射到主方块才读得到——点 PART 子方块也要能读。 */
    private static double deviceTemp(Level level, BlockPos pos) {
        // Cryptand 设备温度（DeviceThermalStore）
        try {
            if (DeviceThermalStore.contains(pos)) {
                return DeviceThermalStore.thermalFor(pos).tempCelsius();
            }
        } catch (Throwable ignored) {
        }
        // 变压器温度（TransformerHeatStore；多方块 PART → 主方块映射）
        try {
            BlockPos main = com.hdf.cryptand.neoforge.powergrid.adapter
                    .DeviceParamCache.transformerMainPos(level, pos);
            if (main != null) pos = main;
            com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel tf =
                    com.hdf.cryptand.neoforge.powergrid.adapter.TransformerHeatStore.getThermal(pos);
            if (tf != null) return tf.tempCelsius();
        } catch (Throwable ignored) {
        }
        // 原版 ThermalBehaviour
        try {
            ThermalBehaviour tb = BlockEntityBehaviour.get(level, pos, ThermalBehaviour.TYPE);
            if (tb != null) return tb.getTemperature();
        } catch (Throwable ignored) {
        }
        return DeviceThermalStore.thermalFor(pos).tempCelsius();
    }

    /** 该方块是否有设备温度模型（PowerGrid/Create ThermalBehaviour 或 Cryptand
     *  DeviceThermalStore 注册）——有 → 自动路由优先显示元件内部温度 */
    private static boolean blockHasDevice(Level level, BlockPos pos) {
        try {
            ThermalBehaviour tb = BlockEntityBehaviour.get(level, pos, ThermalBehaviour.TYPE);
            if (tb != null) return true;
        } catch (Throwable ignored) {
        }
        return DeviceThermalStore.contains(pos);
    }

    /** 方块是否在自管图中有导线端点（有导线连接） */
    private static boolean blockHasWire(Level level, BlockPos pos) {
        try {
            var mgr = WireNetworkManager.get();
            for (int t = 0; t < 8; t++) {
                if (mgr.contains(new com.hdf.cryptand.circuitsimulation.netgraph.WirePoint("B" + pos + "#" + t))) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 连接在方块端子上的导线段 key（温度模型 key；无 → null）。
     *  ⚠ 2026-08-24 修复：模型 key 必须用【seg.pathKey】（烧毁/发热同源——
     *  PhasorNetworkBuilder 段组装用 thermalFor(seg.pathKey)），非 seg.key
     *  （旧实现查错模型 → 恒 25°C 但导线已烧 200°C）。多段匹配时返回【最热段】
     *  （该方块上的段全部比较温度，显示真实最热）。 */
    private static String findSegmentKeyAtBlock(Level level, BlockPos pos) {
        if (pos == null) return null;
        if (!blockHasWire(level, pos)) return null;
        java.util.Set<String> pointKeys = new java.util.HashSet<>();
        for (int t = 0; t < 8; t++) pointKeys.add("B" + pos + "#" + t);
        List<WireSegment> segs = WireNetworkManager.get().segments();
        if (segs == null || segs.isEmpty()) return null;
        String bestKey = null;
        double bestT = Double.NEGATIVE_INFINITY;
        for (WireSegment seg : segs) {
            if (seg.edges == null || seg.edges.isEmpty()) continue;
            boolean hit = false;
            for (WireEdge ed : seg.edges) {
                if ((ed.a != null && pointKeys.contains(ed.a.key))
                        || (ed.b != null && pointKeys.contains(ed.b.key))) {
                    hit = true;
                    break;
                }
            }
            if (!hit) continue;
            // 2026-08-24：key 已统一（netgraph WireSegment.key == 烧毁模型
            // pathKey 格式：端点集合排序 + ';'）→ 直接用 seg.key。
            String key = seg.key;
            double t = 25.0;
            try {
                var th = com.hdf.cryptand.neoforge.powergrid.adapter
                        .WireThermalStore.getThermal(key);
                if (th != null) t = th.tempCelsius();
            } catch (Throwable ignored) {
            }
            if (bestKey == null || t > bestT) {
                bestT = t;
                bestKey = key;
            }
        }
        return bestKey;
    }

    /** 端点 → 段匹配 key（BlockWireEndpoint: Bpos#term；Junction: Jpos） */
    private static String endpointKey(Level level, IWireEndpoint ep) {
        if (ep == null) return null;
        if (ep instanceof BlockWireEndpoint bep) {
            return "B" + bep.getPos() + "#" + bep.getTerminal();
        }
        if (ep instanceof JunctionWireEndpoint jep) {
            try {
                return "J" + BlockPos.containing(jep.getExactPosition(level));
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** 导线两端点 → 其所属连续段的段 key（温度模型 key；悬空无段 → null）
     *  2026-08-24：返回 pathKey（与烧毁/发热模型同源）。 */
    private static String findSegmentKey(Level level, String k1, String k2) {
        if (k1 == null && k2 == null) return null;
        List<WireSegment> segs = WireNetworkManager.get().segments();
        if (segs == null || segs.isEmpty()) return null;
        for (WireSegment seg : segs) {
            if (seg.edges == null || seg.edges.isEmpty()) continue;
            Set<String> keys = new HashSet<>();
            for (WireEdge ed : seg.edges) {
                if (ed.a != null) keys.add(ed.a.key);
                if (ed.b != null) keys.add(ed.b.key);
            }
            if ((k1 != null && keys.contains(k1)) || (k2 != null && keys.contains(k2))) {
                // 2026-08-24：key 统一（端点集合排序格式）→ 直接 seg.key
                return seg.key;
            }
        }
        return null;
    }

    /** 客户端发送（仅客户端调用） */
    public static void sendToServer(String key, int targetType, BlockPos pos, int eid) {
        PacketDistributor.sendToServer(new ThermometerRequestPayload(
                key, targetType,
                pos == null ? Long.MIN_VALUE : pos.asLong(),
                eid));
    }
}
