/**
 * ===== 手持电阻表（2026-08-19） =====
 *
 * 双点电阻测量（Pos + Neg 两个探针点，交互同万用表电压模式）：
 *   - 右键电气方块端子 / 右键看向的导线 → 依次绑定 Pos → Neg（双点）
 *   - 服务端用【直流测试电流法】求 A-B 等效电阻：零化所有独立源（电压源 →
 *     内阻、电流源 → 开路）、注入 1A 直流测试电流 → R = |V_A − V_B| / 1A
 *     （EngineMeasurements.resistanceBetween，跨设备/导线/网络合并）
 *   - 探针线：沿用原版 MultimeterItemRenderer（模式 0 电压模式双线）
 *
 * 读数链路：客户端每 10 tick 发 MultimeterRequestPayload（kind=RESISTANCE_BETWEEN）
 * → 服务端 ServerMeasurementSystem 特判调 resistanceBetween → MultimeterResponsePayload
 * → MultimeterReadoutStore → getText 悬浮显示（Ω）。
 */

package com.hdf.cryptand.neoforge.powergrid.item;

import com.hdf.cryptand.neoforge.powergrid.client.wire.WireLookStore;
import com.hdf.cryptand.neoforge.powergrid.measurement.CryptandMeterItem;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterReadoutStore;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterItem;

import java.util.UUID;
import com.hdf.cryptand.neoforge.powergrid.measurement.EngineMeasurements;
import com.hdf.cryptand.neoforge.powergrid.net.MultimeterRequestPayload;

public class ResistanceMeterItem extends CryptandMeterItem {

    /** 多行文本标记字符（PlacementOverlayMixin 拆分渲染时剥离） */
    private static final String MARKER = "\uE000";

    /** 请求节流（每玩家，每 10 tick 一次，与万用表一致） */
    private static final java.util.Map<UUID, Long> LAST_REQUEST =
            new java.util.concurrent.ConcurrentHashMap<>();

    public ResistanceMeterItem(Properties properties) {
        super(properties);
    }

    @Override
    public int maxProbeLines() { return 2; }

    /**
     * 抓取导线（右键看向的导线）：把该导线的【端点端子】作为下一个探针点。
     * 交互同万用表电压模式：先 Pos 后 Neg（有 Pos 无 Neg → 存 Neg；否则重置 Pos）。
     * 保持模式 0（电压模式）→ 原版 MultimeterItemRenderer 双探针线渲染。
     */
    @Override
    public void grabWire(ItemStack stack, WireLookStore.WireHit hit) {
        try {
            BlockPos pos = new BlockPos(hit.ax(), hit.ay(), hit.az());
            BlockWireEndpoint ep = new BlockWireEndpoint(pos, hit.aTerm());
            CompoundTag md = MultimeterItem.getModeData(stack);
            if (md == null) md = new CompoundTag();
            if (md.contains("Pos") && !md.contains("Neg")) {
                md.put("Neg", ep.serialize()); // 第二探针点
            } else {
                md.put("Pos", ep.serialize()); // 重置：第一探针点
                md.remove("Neg");
            }
            // X/Y/Z 给原版渲染器（探针线画到最近命中点）
            if (hit.hitPoint() != null) {
                md.putDouble("X", hit.hitPoint().x);
                md.putDouble("Y", hit.hitPoint().y);
                md.putDouble("Z", hit.hitPoint().z);
                md.putDouble("HitX", hit.hitPoint().x);
                md.putDouble("HitY", hit.hitPoint().y);
                md.putDouble("HitZ", hit.hitPoint().z);
            }
            MultimeterItem.saveModeData(stack, md);
        } catch (Throwable ignored) {
        }
    }

    // ========== 读数显示 ==========

    @Override
    public Component getText(Level level, Player player, ItemStack stack) {
        try {
            CompoundTag md = MultimeterItem.getModeData(stack);
            if (md == null || !md.contains("Pos")) return hintText();
            IWireEndpoint pos = WireEndpointType.deserialize(md.getCompound("Pos"));
            if (!(pos instanceof BlockWireEndpoint bp)) return hintText();
            if (!md.contains("Neg")) return singlePointText();
            IWireEndpoint neg = WireEndpointType.deserialize(md.getCompound("Neg"));
            if (!(neg instanceof BlockWireEndpoint bn)) return singlePointText();

            String key = "R|" + bp.getPos() + "|" + bp.getTerminal()
                    + "|" + bn.getPos() + "|" + bn.getTerminal();

            // 每 10 tick 发一次请求（客户端；getText 每帧调用，按游戏 tick 节流）
            if (level.isClientSide) {
                long now = level.getGameTime();
                long last = LAST_REQUEST.getOrDefault(player.getUUID(), 0L);
                if (now - last >= MultimeterRequestPayload.REQUEST_INTERVAL_TICKS) {
                    MultimeterRequestPayload payload = new MultimeterRequestPayload(
                            key,
                            MultimeterReadoutStore.Kind.RESISTANCE_BETWEEN.ordinal(),
                            bp.getPos().asLong(), bp.getTerminal(),
                            bn.getPos().asLong(), bn.getTerminal(),
                            -1, 0);
                    PacketDistributor.sendToServer(payload);
                    LAST_REQUEST.put(player.getUUID(), now);
                }
            }

            if (MultimeterReadoutStore.isFailed(key)) return failText();
            MultimeterReadoutStore.Readout ro = MultimeterReadoutStore.read(key);
            if (ro == null) return readingText();
            return ohmText(ro.value);
        } catch (Throwable ignored) {
        }
        return hintText();
    }

    /** 未连接提示 */
    private Component hintText() {
        return Component.literal(MARKER)
                .append(Component.literal("电阻表 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("未连接").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("\n"))
                .append(Component.literal("右键端子/导线依次接两个探针点，测两点间等效电阻")
                        .withStyle(ChatFormatting.DARK_GRAY));
    }

    /** 只接了第一个探针点 */
    private Component singlePointText() {
        return Component.literal(MARKER)
                .append(Component.literal("[···] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("请接第二个探针点").withStyle(ChatFormatting.YELLOW));
    }

    /** 读取中 */
    private Component readingText() {
        return Component.literal(MARKER)
                .append(Component.literal("[···] ").withStyle(ChatFormatting.YELLOW))
                .append(Component.literal("读取中…").withStyle(ChatFormatting.YELLOW));
    }

    /** 读取不到（服务端 invalid：不同网络/开路） */
    private Component failText() {
        return Component.literal(MARKER)
                .append(Component.literal("电阻表 ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal("读取不到（开路）").withStyle(ChatFormatting.RED));
    }

    /** 电阻文本（自动 Ω / kΩ / MΩ / mΩ / μΩ） */
    private Component ohmText(double ohms) {
        ChatFormatting color;
        if (ohms >= 1e6) color = ChatFormatting.RED;
        else if (ohms >= 1e3) color = ChatFormatting.GOLD;
        else color = ChatFormatting.GREEN;
        Component line1 = Component.literal("电阻 ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(formatOhms(ohms)).withStyle(color));
        return Component.literal(MARKER).append(line1);
    }

    /**
     * 电阻数值格式化（Ω / kΩ / MΩ / mΩ / μΩ）。
     * 档位：≥1e6 → MΩ；≥1e3 → kΩ；≥1 → Ω；≥1e-3 → mΩ（毫欧）；
     * ＜1e-3 → μΩ（微欧）——小电阻（导线/触点/内阻）可读性。
     */
    private static String formatOhms(double v) {
        if (!Double.isFinite(v) || v < 0) return "∞";
        if (v >= 1e6) return String.format("%.2f MΩ", v / 1e6);
        if (v >= 1e3) return String.format("%.2f kΩ", v / 1e3);
        if (v >= 1) return String.format("%.2f Ω", v);
        if (v >= 1e-3) return String.format("%.2f mΩ", v * 1e3);
        return String.format("%.2f μΩ", v * 1e6);
    }
}
