/**
 * ===== 高级万用表 =====
 *
 * 复制 PowerGrid 万用表（MultimeterItem），改名"高级万用表"放入 Cryptand 创造标签栏。
 * 继承全部测量行为（右键搭线/导线钳流/距离断连/探针渲染/表盘），
 * 重写 getText 实现 DC / AC 分离显示：
 *   - 直流（频率 ≤ 1 或无发电机/交流源）：瞬时值 + [DC] 标注 + 频率
 *   - 交流（频率 > 1）：1 秒采样窗口统计
 *       电压：最大 / 最小 / 峰峰值 / 有效值(RMS) / 频率
 *       电流：1s 平均 / 最大 / 最小 / 有效值(RMS) / 频率
 *   AC 为多行文本（以私有区字符 \uE000 标记 + \n 分隔，
 *   由 PlacementOverlayMixin 拆分渲染，保留各段颜色）
 */

package com.hdf.cryptand.neoforge.powergrid.creative;

import com.hdf.cryptand.neoforge.core.client.WireLookStore;
import com.hdf.cryptand.neoforge.core.measurement.CryptandMeterItem;
import com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterReadoutStore;
import com.hdf.cryptand.neoforge.powergrid.adapter.MultimeterSampler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.JunctionWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterItem;
import org.patryk3211.powergrid.utility.Lang;
import org.patryk3211.powergrid.utility.Unit;

public class AdvancedMultimeterItem extends CryptandMeterItem {

    /** 多行文本标记字符（PlacementOverlayMixin 拆分渲染时剥离） */
    private static final String MARKER = "\uE000";

    /** 异常诊断日志节流计数 */
    private static int errCounter;
    private static int dbgTextCounter;
    private static int dbgSendCounter;

    /** 上次发送请求包的客户端 tick（每玩家独立节流，每 10 tick 发送一次） */
    private static final java.util.Map<java.util.UUID, Long> LAST_REQUEST =
            new java.util.concurrent.ConcurrentHashMap<>();

    public AdvancedMultimeterItem(Properties properties) {
        super(properties);
    }

    @Override
    public int maxProbeLines() { return 2; }

    /**
     * 搭线到【接线端子】（CordJunctionBlockEntity）→ 切到电流模式：
     * 接线端子是电流汇流点，单点无电压可测；自动关联该接线端子连接的一根
     * 导线（EID），走电流模式读数。潜行或非主手仍走原版行为。
     */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        try {
            if (context.getPlayer() != null && context.getPlayer().isShiftKeyDown()) {
                // 2026-08-20 shift+右键端子：必须清除绑定（不能走原版 super.useOn——
                // 原版 useOn 的 shift 分支走 Item.useOn 返回 PASS，但若 RightClickBlock
                // 事件未拦截会继续下放；这里兜底清除并拦截，杜绝"接回去"）。
                com.hdf.cryptand.neoforge.core.measurement.CryptandMeterItem
                        .clearTarget(context.getItemInHand());
                return InteractionResult.CONSUME;
            }
            Level level = context.getLevel();
            BlockPos pos = context.getClickedPos();
            BlockEntity be = level.getBlockEntity(pos);
            if (be != null && be.getClass().getName().endsWith("CordJunctionBlockEntity")) {
                ItemStack stack = context.getItemInHand();
                int eid = findJunctionWireEid(level, pos);
                if (eid >= 0) {
                    setMode(stack, 1); // 电流模式
                    CompoundTag md = getModeData(stack);
                    md.putInt("EID", eid);
                    saveModeData(stack, md);
                    return InteractionResult.CONSUME;
                }
            }
        } catch (Throwable ignored) {
        }
        return super.useOn(context);
    }

    /** 找接线端子关联的第一根可见导线实体 id（端点落在该接线端子方块上） */
    private static int findJunctionWireEid(Level level, BlockPos pos) {
        try {
            AABB box = new AABB(pos).inflate(3.0);
            for (BaseWireEntity w : level.getEntitiesOfClass(BaseWireEntity.class, box)) {
                if (endpointAt(w.getEndpoint1(), level, pos)
                        || endpointAt(w.getEndpoint2(), level, pos)) {
                    return w.getId();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static boolean endpointAt(IWireEndpoint ep, Level level, BlockPos pos) {
        if (ep instanceof BlockWireEndpoint bep) return bep.getPos().equals(pos);
        if (ep instanceof JunctionWireEndpoint jep) {
            try {
                return BlockPos.containing(jep.getExactPosition(level)).equals(pos);
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    @Override
    public Component getText(Level level, Player player, ItemStack stack) {
        // 2026-08-13 共享自管测量：读数来自自管求解（C2S 请求 → 服务端
        // buildContextFromGraph → 相量结果 → S2C 响应），与原版网络无关。
        return com.hdf.cryptand.neoforge.powergrid.adapter.SelfManagedMultimeter.getText(
                level, player, stack);
    }

    // ========== 显示构建 ==========

    /** 瞬时值（直流 / AC 首秒回退） */
    private Component instantText(int mode, float measurement) {
        if (mode == 0) {
            return Lang.translate("tooltip.multimeter.voltage")
                    .add(Unit.VOLTAGE.formatWithPrefixes(measurement).style(ChatFormatting.BLUE))
                    .style(ChatFormatting.GRAY)
                    .component();
        }
        return Lang.translate("tooltip.multimeter.current")
                .add(Unit.CURRENT.formatWithPrefixes(measurement).style(ChatFormatting.YELLOW))
                .style(ChatFormatting.GRAY)
                .component();
    }

    /** AC 电压：两行（第 1 行 [AC]+最大/最小，第 2 行 峰峰值/有效值） */
    private Component acVoltageText(MultimeterSampler.Result r) {
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

    /** AC 电流：两行（第 1 行 [AC]+1s平均，第 2 行 最大/最小/有效值） */
    private Component acCurrentText(MultimeterSampler.Result r) {
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

    // ========== 注册 / 请求 ==========

    /**
     * 构建测量目标并注册测量类；每 10 tick 发送一次 MultimeterRequestPayload（C2S）。
     * 电压：同一方块两端子 / 跨方块两点；电流：导线 EID。
     *
     * @return 读数 key；目标无效（未接线/非方块端点）→ null
     */
    private String registerTargetAndSend(Level level, Player player, ItemStack stack,
                                         int mode, double freq) {
        try {
            CompoundTag md = getModeData(stack);
            if (md == null) return null;
            MultimeterReadoutStore.Target target = null;
            String key = null;
            if (mode == 0) {
                if (!md.contains("Pos") || !md.contains("Neg")) return null;
                IWireEndpoint pos = WireEndpointType.deserialize(md.getCompound("Pos"));
                IWireEndpoint neg = WireEndpointType.deserialize(md.getCompound("Neg"));
                if (!(pos instanceof BlockWireEndpoint bp) || !(neg instanceof BlockWireEndpoint bn)) {
                    return null;
                }
                BlockPos posB = bp.getPos(); // getPos() = 方块 pos
                BlockPos negB = bn.getPos();
                if (bp.getPos().equals(bn.getPos())) {
                    // 同一方块两端子（key 不含频率：客户端 BFS 频率抖动不应重置读数）
                    key = "V|" + posB + "|" + bp.getTerminal() + "|" + bn.getTerminal();
                    target = MultimeterReadoutStore.Target.sameBlockVoltage(
                            posB, bp.getTerminal(), bn.getTerminal(), freq);
                } else {
                    // 跨方块两点
                    key = "B|" + posB + "|" + bp.getTerminal() + "|" + negB + "|" + bn.getTerminal();
                    target = MultimeterReadoutStore.Target.betweenBlocksVoltage(
                            posB, bp.getTerminal(), negB, bn.getTerminal(), freq);
                }
            } else {
                // 电流：导线钳流
                if (!md.contains("EID")) return null;
                int eid = md.getInt("EID");
                key = "C|" + eid;
                target = MultimeterReadoutStore.Target.wireCurrent(eid, freq);
            }
            if (key == null || target == null) return null;

            // 每 10 tick 发送一次请求包（客户端；getText 每帧调用，用游戏 tick 节流）
            if (level.isClientSide) {
                long now = level.getGameTime();
                long last = LAST_REQUEST.getOrDefault(player.getUUID(), 0L);
                if (now - last >= MultimeterRequestPayload.REQUEST_INTERVAL_TICKS) {
                    if (++dbgSendCounter % 3 == 0) {
                        try {
                            com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
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

    /** 读取中文本（首次注册，服务端 ~1-2 tick 内算好） */
    private Component readingText(double freq) {
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

    /** 读取失败文本（目标无效 / 服务端返回失败 / 相量不可用） */
    private Component failText(double freq) {
        Component t = Component.literal("[FAIL] ").withStyle(ChatFormatting.DARK_RED)
                .append(Component.literal("读取不到").withStyle(ChatFormatting.DARK_RED));
        if (!Double.isNaN(freq)) {
            String freqText = freq > 0 ? String.format("%.1f", freq) : "0.0";
            t = t.copy()
                    .append(Component.literal("  "))
                    .append(Component.translatable("cryptand.tooltip.multimeter.frequency", freqText)
                            .withStyle(ChatFormatting.GOLD));
        }
        return t;
    }

    /** 端子对匹配（任一顺序） */

    /** 采样目标标识：主/副手 + 模式 + 端点（切换目标自动重置 1 秒窗口） */
    private String sampleKey(Player player, ItemStack stack, int mode) {
        String slot = player.getMainHandItem() == stack ? "M" : "O";
        try {
            CompoundTag modeData = getModeData(stack);
            if (mode == 1) {
                return slot + "|C|" + modeData.getInt("EID");
            }
            return slot + "|V|" + modeData.getCompound("Pos") + "|" + modeData.getCompound("Neg");
        } catch (Throwable ignored) {
            return slot + "|" + mode;
        }
    }

    /**
     * 按测量模式获取用于频率检测的端点：
     *   mode=0（电压）→ ModeData.Pos 端点（需两根线 Pos/Neg）
     *   mode=1（电流）→ ModeData.EID 导线实体 → endpoint1（只需搭一根线到导线）
     */
    private IWireEndpoint measureEndpoint(Level level, ItemStack stack, int mode) {
        try {
            CompoundTag modeData = getModeData(stack);
            if (mode == 1 && modeData.contains("EID")) {
                Entity entity = level.getEntity(modeData.getInt("EID"));
                if (entity instanceof BaseWireEntity wire) {
                    return wire.getEndpoint1();
                }
                return null;
            }
            if (mode == 0 && modeData.contains("Pos")) {
                return WireEndpointType.deserialize(modeData.getCompound("Pos"));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
