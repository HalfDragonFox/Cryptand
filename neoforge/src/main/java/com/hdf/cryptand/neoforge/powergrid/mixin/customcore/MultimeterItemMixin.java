/**
 * ===== 万用表调试模式 Mixin =====
 *
 * 受配置 enableMultimeterDebug 控制（始终注入，运行时决定是否生效）：
 *   - 解除 getText 的显示量程限制（原版超量程显示 ">max" / "<-max"，
 *     调试模式下直接显示实际测量值）
 *   - 区分直流 / 交流显示（越多越好）：
 *       · 直流（频率 ≤ 1 或无发电机/交流源）：瞬时值 + [DC] 标注 + 频率
 *       · 交流（频率 > 1）：基于 1 秒采样窗口的统计参数
 *           电压：最大 / 最小 / 峰峰值 / 有效值(RMS) / 频率
 *           电流：1s 平均 / 最大 / 最小 / 有效值(RMS) / 频率
 *   - AC 为多行文本（以私有区字符 \uE000 标记 + \n 分隔，
 *     由 PlacementOverlayMixin 拆分渲染，保留各段颜色）
 *
 * 关闭配置时本 Mixin 不干预（返回后走原版 getText 逻辑）。
 */

package com.hdf.cryptand.neoforge.powergrid.mixin.customcore;

import com.hdf.cryptand.neoforge.powergrid.config.ConfigPowerGrid;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterDebug;
import com.hdf.cryptand.neoforge.powergrid.measurement.MultimeterSampler;
import com.hdf.cryptand.neoforge.powergrid.measurement.SelfManagedMultimeter;
import com.hdf.cryptand.neoforge.powergrid.network.wire.PowerGridWireConverter;
import com.hdf.cryptand.neoforge.powergrid.network.wire.WireNetworkManager;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.patryk3211.powergrid.electricity.wire.BaseWireEntity;
import org.patryk3211.powergrid.electricity.wire.IWireEndpoint;
import org.patryk3211.powergrid.electricity.wire.WireEndpointType;
import org.patryk3211.powergrid.equipment.multimeter.MultimeterItem;
import org.patryk3211.powergrid.utility.Lang;
import org.patryk3211.powergrid.utility.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MultimeterItem.class, remap = false)
public abstract class MultimeterItemMixin {

    /** 多行文本标记字符（PlacementOverlayMixin 拆分渲染时剥离） */
    @Unique
    private static final String MARKER = "\uE000";

    /**
     * 电流模式单点接线（2026-08-15 用户要求：单点接线 → 测流经该点的电流）。
     * <p>原版 onTerminal 在电流模式（mode!=0）下【强制切回电压模式】（字节码
     * 实锤：getMode!=0 → setMode(0)）→ 电流模式只能搭导线存 EID。自管模式下
     * 导线实体已删除 → 电流测量彻底失效。本注入接管：
     *   - 电流模式右键方块端子 → 把该端点存为 Pos（单点电流点，覆盖旧点）
     *   - 重复右键同一端点 → 取消（移除 Pos）
     *   - 电压模式（mode==0）→ 不干预（原版两点测量行为保留）
     * 读数由 getText → SelfManagedMultimeter（Pos 且无 EID → NODE_CURRENT
     * 单点电流请求）。
     */
    @Inject(method = "onTerminal", at = @At("HEAD"), cancellable = true)
    private void cryptand$singlePointCurrent(Level level, IWireEndpoint endpoint,
                                             ItemStack stack,
                                             CallbackInfoReturnable<net.minecraft.world.InteractionResult> cir) {
        try {
            if (!PowerGridWireConverter.isEnabled()) return;
            MultimeterItem self = (MultimeterItem) (Object) this;
            if (self.getMode(stack) != 1) return; // 仅电流模式接管
            if (!(endpoint instanceof org.patryk3211.powergrid.electricity.wire.BlockWireEndpoint)) {
                return;
            }
            CompoundTag md = MultimeterItem.getModeData(stack);
            if (md == null) {
                md = new CompoundTag();
                MultimeterItem.saveModeData(stack, md);
            }
            // ⚠ 2026-09-11 诊断（用户：高级万用表表笔线不显示——Pos 未写入则渲染器无线可画）：
            // 记录每次 onTerminal 接管调用的模式/端点/已有 Pos/Neg 状态，便于一次定位。
            try {
                com.hdf.cryptand.neoforge.CryptandNeoForge.WAF_LOGGER.info(
                        "[Measure] onTerminal mode={} ep={} hasPos={} hasNeg={}",
                        self.getMode(stack), endpoint.getClass().getSimpleName(),
                        md.contains("Pos"), md.contains("Neg"));
            } catch (Throwable ignored) {
            }
            if (md.contains("Pos")) {
                IWireEndpoint cur = WireEndpointType.deserialize(md.getCompound("Pos"));
                if (endpoint.equals(cur)) {
                    md.remove("Pos"); // 重复点击 → 取消单点
                    MultimeterItem.saveModeData(stack, md);
                    cir.setReturnValue(net.minecraft.world.InteractionResult.SUCCESS);
                    return;
                }
            }
            md.put("Pos", endpoint.serialize()); // 单点电流点（覆盖旧点）
            // 单点接线后旧 EID（导线钳流）失效——导线实体已删/不适用，清除
            // 残留，否则 SelfManagedMultimeter 电流分支可能命中旧 EID。
            if (md.contains("EID")) md.remove("EID");
            MultimeterItem.saveModeData(stack, md);
            cir.setReturnValue(net.minecraft.world.InteractionResult.SUCCESS);
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "getText", at = @At("HEAD"), cancellable = true)
    private void cryptand$debugGetText(Level level, Player player, ItemStack stack,
                                       CallbackInfoReturnable<Component> cir) {
        // ⚠ 自管模式（2026-08-13 用户要求"万用表全部接管"）：读数完全走自管
        //   求解（SelfManagedMultimeter → C2S → 服务端 buildContextFromGraph →
        //   相量结果 → S2C），不依赖原版网络/原版 getMeasurement（时域已禁）。
        try {
            if (PowerGridWireConverter.isEnabled()
                    && WireNetworkManager.get().nodeCount() > 0) {
                cir.setReturnValue(
                        SelfManagedMultimeter.getText(
                                level, player, stack));
                return;
            }
        } catch (Throwable ignored) {
        }
        if (!ConfigPowerGrid.ENABLE_MULTIMETER_DEBUG.get()) return;   // 关闭 → 原版行为

        MultimeterItem self = (MultimeterItem) (Object) this;
        try {
            float measurement = self.getMeasurement(level, stack);
            int mode = self.getMode(stack);
            if (mode != 0 && mode != 1) {
                cir.setReturnValue(null);
                return;
            }

            // 频率：NaN=无法获取网络；0=直流；正值=实际频率
            double freq = MultimeterDebug.getNetworkFrequencyHz(level,
                    cryptand$measureEndpoint(level, stack, mode));

            Component text;
            if (!Double.isNaN(freq) && freq > 1.0) {
                // ===== 交流：1 秒采样窗口统计 =====
                String key = cryptand$sampleKey(player, stack, mode);
                MultimeterSampler.Result r = MultimeterSampler.sample(key, level.getGameTime(), measurement, true);
                if (r != null) {
                    text = mode == 0 ? cryptand$acVoltageText(r) : cryptand$acCurrentText(r);
                } else {
                    // 首个窗口未完成 → 暂显瞬时值（标注 AC）
                    text = Component.literal("[AC] ").withStyle(ChatFormatting.RED)
                            .append(cryptand$instantText(mode, measurement));
                }
            } else {
                // ===== 直流（或无法判定） =====
                text = cryptand$instantText(mode, measurement);
                if (!Double.isNaN(freq)) {
                    text = Component.literal("[DC] ").withStyle(ChatFormatting.GRAY)
                            .append(text);
                }
            }

            // 追加频率（AC/DC 网络均可显示；NaN=无网络不显示）
            if (!Double.isNaN(freq)) {
                String freqText = freq > 0 ? String.format("%.1f", freq) : "0.0";
                text = text.copy()
                        .append(Component.literal("  "))
                        .append(Component.translatable("cryptand.tooltip.multimeter.frequency", freqText)
                                .withStyle(ChatFormatting.GOLD));
            }

            cir.setReturnValue(text);
        } catch (Throwable t) {
            // 任何异常 → 走原版逻辑
        }
    }

    // ========== 显示构建 ==========

    /** 瞬时值（直流 / AC 首秒回退） */
    @Unique
    private static Component cryptand$instantText(int mode, float measurement) {
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
    @Unique
    private static Component cryptand$acVoltageText(MultimeterSampler.Result r) {
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
    @Unique
    private static Component cryptand$acCurrentText(MultimeterSampler.Result r) {
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

    // ========== 采样 ==========

    /** 采样目标标识：主/副手 + 模式 + 端点（切换目标自动重置 1 秒窗口） */
    @Unique
    private static String cryptand$sampleKey(Player player, ItemStack stack, int mode) {
        String slot = player.getMainHandItem() == stack ? "M" : "O";
        try {
            CompoundTag modeData = MultimeterItem.getModeData(stack);
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
    @Unique
    private static IWireEndpoint cryptand$measureEndpoint(Level level, ItemStack stack, int mode) {
        try {
            CompoundTag modeData = MultimeterItem.getModeData(stack);
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