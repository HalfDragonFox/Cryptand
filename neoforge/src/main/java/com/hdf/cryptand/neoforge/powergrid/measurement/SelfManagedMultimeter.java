/**
 * ===== 自管测量类：手持测量元件统一入口（2026-08-14 示波器统一并入） =====
 *
 * 所有【手持】测量元件的客户端测量逻辑统一走本类：
 *   - 万用表（原版 MultimeterItem + 高级 AdvancedMultimeterItem）：getText
 *     读数完全来自自管求解（MultimeterRequestPayload C2S → 服务端
 *     solveBlocks → buildContextFromGraph → 相量结果 → S2C 响应）。
 *   - 示波器（OscilloscopeItem）：请求发送（sendOscilloscopeRequest）与响应
 *     接受（acceptOscilloscope）统一走本类，委托 OscilloscopeStore 存储。
 *
 * 不依赖原版网络/原版 getMeasurement（原版时域在自管模式已禁）。
 *
 * ⚠ 范围：仅手持测量元件（用户 2026-08-14 明确）——方块表计（电压/电流/
 * 功率表）不接管，维持原版行为。
 */
package com.hdf.cryptand.neoforge.powergrid.measurement;

import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.client.OscilloscopeStore;
import com.hdf.cryptand.neoforge.powergrid.net.MultimeterRequestPayload;
import com.hdf.cryptand.neoforge.powergrid.net.OscilloscopeRequestPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterItem;
import org.patryk3211.powergrid.utility.Unit;

import java.util.Map;
import java.util.UUID;

public final class SelfManagedMultimeter {

    /** 多行文本标记字符（PlacementOverlayMixin 拆分渲染时剥离） */
    public static final String MARKER = "\uE000";

    /** 上次发送请求包的客户端 tick（每玩家独立节流，每 10 tick 发送一次） */
    private static final Map<UUID, Long> LAST_REQUEST = new java.util.concurrent.ConcurrentHashMap<>();

    private static int errCounter;
    private static int dbgTextCounter;
    private static int dbgSendCounter;

    private SelfManagedMultimeter() {
    }

    /**
     * 万用表抓取导线（2026-08-18 自管适配：右键“看向的导线”→ 电流模式 +
     * 存单点电流点 = 命中导线一个端点）。与原版 useOnWire（切电流模式 + 记录
     * 导线附着点）等价——自管模式无导线实体，用端点身份代替。
     * 后续 getText：mode=1 + Pos（无 EID）→ NODE_CURRENT 单点电流测量。
     */
    public static void grabWireAsSinglePoint(ItemStack stack, int x, int y, int z, int term) {
        try {
            MultimeterItem meter = meterOf(stack);
            if (meter == null) return;
            meter.setMode(stack, 1); // 电流模式（原版 useOnWire 语义）
            CompoundTag md = MultimeterItem.getModeData(stack);
            if (md == null) {
                md = new CompoundTag();
                MultimeterItem.saveModeData(stack, md);
            }
            md.put("Pos", new BlockWireEndpoint(new BlockPos(x, y, z), term).serialize());
            // 旧 EID（导线钳流）失效——自管无实体，清除残留
            if (md.contains("EID")) md.remove("EID");
            MultimeterItem.saveModeData(stack, md);
        } catch (Throwable ignored) {
        }
    }

    /** 从物品栈拿 MultimeterItem 实例方法（getMode/getModeData） */
    private static MultimeterItem meterOf(ItemStack stack) {
        return stack.getItem() instanceof MultimeterItem m ? m : null;
    }

    /**
     * 自管测量文本（服务端求解结果驱动）。目标无效/未接线 → null（调用方回退
     * 原版/显示降级）。
     */
    public static Component getText(Level level, Player player, ItemStack stack) {
        MultimeterItem meter = meterOf(stack);
        int mode = -1;
        try {
            if (meter == null) return null;
            mode = meter.getMode(stack);
            if (mode != 0 && mode != 1) return null;

            // 单点接线（2026-08-15 用户要求：只接一个点 → 测流经该点的电流）：
            // 电压模式只有 Pos 无 Neg / 电流模式只有 Pos 无 EID → 统一降级为
            // 【单点电流】测量，显示按电流模式（单位 A）。
            int displayMode = mode;
            try {
                CompoundTag md0 = meter.getModeData(stack);
                boolean singlePoint = md0 != null && md0.contains("Pos")
                        && !md0.contains("Neg") && !md0.contains("EID");
                if (singlePoint) displayMode = 1;
            } catch (Throwable ignored) {
            }

            // 频率：客户端 BFS 仅用于请求 key 与"读取中"；AC/DC 以服务端响应为准
            double freq = Double.NaN;
            try {
                IWireEndpoint ep = measureEndpoint(level, stack, meter, mode);
                freq = ep != null ? MultimeterDebug.getNetworkFrequencyHz(level, ep) : 0;
            } catch (Throwable ignored) {
            }
            if (Double.isNaN(freq)) freq = 0;

            String key = registerTargetAndSend(level, player, stack, meter, mode, freq);
            if (key == null) {
                return failText(freq);
            }
            if (MultimeterReadoutStore.isFailed(key)) {
                return failText(freq);
            }
            MultimeterReadoutStore.Readout ro = MultimeterReadoutStore.read(key);
            if (ro == null) {
                return readingText(freq);
            }

            double displayFreq = ro.freq;
            float measurement = (float) ro.value;
            if (++dbgTextCounter % 60 == 0) {
                try {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "[MeterGet] cliFreq={} srvFreq={} key={} ro={} measurement={} displayMode={}",
                            String.format("%.2f", freq), String.format("%.2f", displayFreq),
                            key, ro.value, measurement, displayMode);
                } catch (Throwable ignored) {
                }
            }
            Component text;
            if (displayFreq > 1.0) {
                String skey = sampleKey(player, stack, meter, displayMode);
                MultimeterSampler.Result r = MultimeterSampler.sample(skey, level.getGameTime(), measurement, true);
                text = (r != null)
                        ? (displayMode == 0 ? acVoltageText(r) : acCurrentText(r))
                        : Component.literal("[AC] ").withStyle(ChatFormatting.RED)
                                .append(instantText(displayMode, measurement));
            } else {
                text = instantText(displayMode, measurement);
                text = Component.literal("[DC] ").withStyle(ChatFormatting.GRAY).append(text);
            }
            if (!Double.isNaN(displayFreq)) {
                String freqText = displayFreq > 0 ? String.format("%.1f", displayFreq) : "0.0";
                text = text.copy()
                        .append(Component.literal("  "))
                        .append(Component.translatable("cryptand.tooltip.multimeter.frequency", freqText)
                                .withStyle(ChatFormatting.GOLD));
            }
            return text;
        } catch (Throwable t) {
            try {
                if (++errCounter % 40 == 0) {
                    CryptandNeoForge.WAF_LOGGER.info(
                            "SelfManagedMultimeter error: {} @ {}",
                            t, t.getStackTrace() == null || t.getStackTrace().length == 0
                                    ? "?" : t.getStackTrace()[0]);
                }
                if (mode == 0 || mode == 1) {
                    return instantText(mode, 0f).copy()
                            .append(Component.literal("  "))
                            .append(Component.literal("测量异常").withStyle(ChatFormatting.DARK_RED));
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    // ========== 注册 / 请求 ==========

    /** 构建测量目标并发送请求包（每 10 tick）；返回读数 key；目标无效 → null */
    private static String registerTargetAndSend(Level level, Player player, ItemStack stack,
                                                MultimeterItem meter, int mode, double freq) {
        try {
            CompoundTag md = meter.getModeData(stack);
            if (md == null) return null;
            MultimeterReadoutStore.Target target = null;
            String key = null;
            if (mode == 0) {
                if (!md.contains("Pos")) return null;
                IWireEndpoint pos = WireEndpointType.deserialize(md.getCompound("Pos"));
                if (!(pos instanceof BlockWireEndpoint bp)) return null;
                if (!md.contains("Neg")) {
                    // 单点接线（2026-08-15 用户要求：电压模式只接一个点）→
                    // 降级为【单点电流】：测流经该点的电流。
                    key = "N|" + bp.getPos() + "|" + bp.getTerminal();
                    target = MultimeterReadoutStore.Target.nodeCurrent(
                            bp.getPos(), bp.getTerminal(), freq);
                } else {
                    IWireEndpoint neg = WireEndpointType.deserialize(md.getCompound("Neg"));
                    if (!(neg instanceof BlockWireEndpoint bn)) return null;
                    BlockPos posB = bp.getPos();
                    BlockPos negB = bn.getPos();
                    if (posB.equals(negB)) {
                        key = "V|" + posB + "|" + bp.getTerminal() + "|" + bn.getTerminal();
                        target = MultimeterReadoutStore.Target.sameBlockVoltage(
                                posB, bp.getTerminal(), bn.getTerminal(), freq);
                    } else {
                        key = "B|" + posB + "|" + bp.getTerminal() + "|" + negB + "|" + bn.getTerminal();
                        target = MultimeterReadoutStore.Target.betweenBlocksVoltage(
                                posB, bp.getTerminal(), negB, bn.getTerminal(), freq);
                    }
                }
            } else {
                // 电流模式：单点（Pos，2026-08-15 用户要求）优先——自管模式
                // 导线实体已删除，EID 往往是旧残留（实体已不存在 → 服务端
                // 必 invalid）；Pos 单点直接测流经该点的电流。无 Pos 回退 EID。
                if (md.contains("Pos")) {
                    IWireEndpoint pos = WireEndpointType.deserialize(md.getCompound("Pos"));
                    if (!(pos instanceof BlockWireEndpoint bp)) return null;
                    key = "N|" + bp.getPos() + "|" + bp.getTerminal();
                    target = MultimeterReadoutStore.Target.nodeCurrent(
                            bp.getPos(), bp.getTerminal(), freq);
                } else if (md.contains("EID")) {
                    int eid = md.getInt("EID");
                    key = "C|" + eid;
                    target = MultimeterReadoutStore.Target.wireCurrent(eid, freq);
                } else {
                    return null;
                }
            }
            if (key == null || target == null) return null;

            if (level.isClientSide) {
                long now = level.getGameTime();
                long last = LAST_REQUEST.getOrDefault(player.getUUID(), 0L);
                if (now - last >= MultimeterRequestPayload.REQUEST_INTERVAL_TICKS) {
                    if (++dbgSendCounter % 3 == 0) {
                        try {
                            CryptandNeoForge.WAF_LOGGER.info(
                                    "[MeterSend] key={} mode={} freq={} now={} last={} uuid={}",
                                    key, mode, String.format("%.2f", freq), now, last,
                                    player.getUUID());
                        } catch (Throwable ignored) {
                        }
                    }
                    MultimeterRequestPayload.sendToServer(key, target);
                    LAST_REQUEST.put(player.getUUID(), now);
                }
            }
            return key;
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ========== 示波器波形测量（2026-08-14 统一并入） ==========

    /** 示波器客户端请求节流（tick）：与万用表一致 */
    public static final long OSC_REQUEST_INTERVAL_TICKS = 10L;

    /** 示波器每玩家上次发送 tick（并发 map 防多客户端串） */
    private static final Map<UUID, Long> LAST_OSC_REQUEST =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 示波器波形请求（客户端，仅手持选中时调用）：从搭线端点（modeData.Pos）
     * 读取 BlockWireEndpoint，计算网络频率，节流后发送
     * OscilloscopeRequestPayload（C2S）。与万用表测量请求统一走本自定义测量类。
     */
    public static void sendOscilloscopeRequest(Level level, Player player, ItemStack stack) {
        sendOscilloscopeRequest(level, player, stack, 0);
    }

    /**
     * @param cachedFreq 上次响应回传的主导频率（Hz，OscilloscopeStore 缓存）；
     *                   大于 0 时跳过客户端 BFS 频率探测，0 → 兜底 BFS。
     */
    public static void sendOscilloscopeRequest(Level level, Player player, ItemStack stack,
                                               double cachedFreq) {
        try {
            if (level == null || player == null || stack == null || stack.isEmpty()) return;
            if (!level.isClientSide) return;
            // ⚠ 节流前置（2026-08-15）：NBT 解析与客户端 BFS 频率探测都不在每 tick 执行，
            //   否则手持示波器每 tick 沿导线实体 BFS + 实体扫描 → 客户端卡顿。
            long now = level.getGameTime();
            long last = LAST_OSC_REQUEST.getOrDefault(player.getUUID(), 0L);
            if (now - last < OSC_REQUEST_INTERVAL_TICKS) return;
            LAST_OSC_REQUEST.put(player.getUUID(), now);
            MultimeterItem meter = meterOf(stack);
            if (meter == null) return;
            CompoundTag md = meter.getModeData(stack);
            if (md == null || !md.contains("Pos")) return;
            IWireEndpoint ep = WireEndpointType.deserialize(md.getCompound("Pos"));
            if (!(ep instanceof BlockWireEndpoint bep)) return;
            double freq = cachedFreq > 0 ? cachedFreq : 0;
            if (freq <= 0) {
                try {
                    freq = MultimeterDebug.getNetworkFrequencyHz(level, ep);
                } catch (Throwable ignored) {
                }
            }
            if (Double.isNaN(freq)) freq = 0;
            OscilloscopeRequestPayload.sendToServer(
                    "osc-" + player.getUUID(), bep.getPos(), bep.getTerminal(), freq);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 示波器波形响应接受（客户端，S2C 响应包驱动）：统一接受入口，委托
     * OscilloscopeStore 更新本地波形数据。与万用表读数接受（经
     * MultimeterReadoutStore）同属自定义测量类的统一接受点。
     */
    public static void acceptOscilloscope(String key, boolean valid, float[] freqs,
                                          float[] amps, float[] phases) {
        try {
            OscilloscopeStore.put(
                    key, valid, freqs, amps, phases);
        } catch (Throwable ignored) {
        }
    }

    // ========== 显示构建 ==========

    private static Component instantText(int mode, float measurement) {
        if (mode == 0) {
            return org.patryk3211.powergrid.utility.Lang.translate("tooltip.multimeter.voltage")
                    .add(Unit.VOLTAGE.formatWithPrefixes(measurement).style(ChatFormatting.BLUE))
                    .style(ChatFormatting.GRAY)
                    .component();
        }
        return org.patryk3211.powergrid.utility.Lang.translate("tooltip.multimeter.current")
                .add(Unit.CURRENT.formatWithPrefixes(measurement).style(ChatFormatting.YELLOW))
                .style(ChatFormatting.GRAY)
                .component();
    }

    private static Component acVoltageText(MultimeterSampler.Result r) {
        Component line1 = Component.literal("[AC] ").withStyle(ChatFormatting.RED)
                .append(Component.translatable("cryptand.tooltip.multimeter.voltage_max_min",
                        Unit.VOLTAGE.formatWithPrefixes((float) r.max()).style(ChatFormatting.BLUE).component(),
                        Unit.VOLTAGE.formatWithPrefixes((float) r.min()).style(ChatFormatting.BLUE).component()));
        Component line2 = Component.translatable("cryptand.tooltip.multimeter.voltage_extra",
                Unit.VOLTAGE.formatWithPrefixes((float) (r.max() - r.min())).style(ChatFormatting.BLUE).component(),
                Unit.VOLTAGE.formatWithPrefixes((float) r.rms()).style(ChatFormatting.BLUE).component());
        return Component.literal(MARKER)
                .append(line1)
                .append(Component.literal("\n"))
                .append(line2);
    }

    private static Component acCurrentText(MultimeterSampler.Result r) {
        Component line1 = Component.literal("[AC] ").withStyle(ChatFormatting.RED)
                .append(Component.translatable("cryptand.tooltip.multimeter.current_avg",
                        Unit.CURRENT.formatWithPrefixes((float) r.average()).style(ChatFormatting.YELLOW).component()));
        Component line2 = Component.translatable("cryptand.tooltip.multimeter.current_extra",
                Unit.CURRENT.formatWithPrefixes((float) r.max()).style(ChatFormatting.YELLOW).component(),
                Unit.CURRENT.formatWithPrefixes((float) r.min()).style(ChatFormatting.YELLOW).component(),
                Unit.CURRENT.formatWithPrefixes((float) r.rms()).style(ChatFormatting.YELLOW).component());
        return Component.literal(MARKER)
                .append(line1)
                .append(Component.literal("\n"))
                .append(line2);
    }

    private static Component readingText(double freq) {
        Component t = Component.literal("[···] ").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal("读取中…").withStyle(ChatFormatting.YELLOW));
        if (!Double.isNaN(freq)) {
            String freqText = freq > 0 ? String.format("%.1f", freq) : "0.0";
            t = t.copy()
                    .append(Component.literal("  "))
                    .append(Component.translatable("cryptand.tooltip.multimeter.frequency", freqText)
                            .withStyle(ChatFormatting.GOLD));
        }
        return t;
    }

    private static Component failText(double freq) {
        Component t = Component.literal("[FAIL] ").withStyle(ChatFormatting.DARK_RED)
                .append(Component.literal("未找到网络").withStyle(ChatFormatting.DARK_RED));
        if (!Double.isNaN(freq)) {
            String freqText = freq > 0 ? String.format("%.1f", freq) : "0.0";
            t = t.copy()
                    .append(Component.literal("  "))
                    .append(Component.translatable("cryptand.tooltip.multimeter.frequency", freqText)
                            .withStyle(ChatFormatting.GOLD));
        }
        return t;
    }

    private static String sampleKey(Player player, ItemStack stack, MultimeterItem meter, int mode) {
        String slot = player.getMainHandItem() == stack ? "M" : "O";
        try {
            CompoundTag modeData = meter.getModeData(stack);
            if (mode == 1) {
                if (modeData.contains("EID")) {
                    return slot + "|C|" + modeData.getInt("EID");
                }
                // 单点电流（无 EID）→ 用 Pos 标识采样窗口
                return slot + "|N|" + modeData.getCompound("Pos");
            }
            return slot + "|V|" + modeData.getCompound("Pos") + "|" + modeData.getCompound("Neg");
        } catch (Throwable ignored) {
            return slot + "|" + mode;
        }
    }

    /** 按测量模式获取用于频率检测的端点（模式1电流 → EID 导线实体，无 EID 回退
     *  单点 Pos；0电压 → Pos） */
    private static IWireEndpoint measureEndpoint(Level level, ItemStack stack,
                                                 MultimeterItem meter, int mode) {
        try {
            CompoundTag modeData = meter.getModeData(stack);
            if (mode == 1 && modeData.contains("EID")) {
                Entity entity = level.getEntity(modeData.getInt("EID"));
                if (entity instanceof BaseWireEntity wire) {
                    return wire.getEndpoint1();
                }
                return null;
            }
            if (modeData.contains("Pos")) {
                return WireEndpointType.deserialize(modeData.getCompound("Pos"));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
