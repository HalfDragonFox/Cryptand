/**
 * ===== 自管剪线（2026-08-13 一步到位） =====
 *
 * 自管模式无原版导线实体 → 原版"WireCutter 右键导线"剪线不可用。接管为：
 * WireCutter 右键【端子方块】→ 移除该端子所有自管边（CryptandWirePlacement.
 * removeWiresAt）+ 返回导线物品。
 *
 * 用 NeoForge PlayerInteractEvent.RightClickBlock（服务端执行移除；客户端
 * removeWiresAt 返回 false 自然放行）。
 */
package com.hdf.cryptand.neoforge.powergrid.network.wire;

import com.hdf.cryptand.Cryptand;
import com.hdf.cryptand.neoforge.CryptandNeoForge;
import com.hdf.cryptand.neoforge.powergrid.client.wire.WireLookStore;
import com.hdf.cryptand.neoforge.powergrid.measurement.CryptandMeterItem;
import com.hdf.cryptand.neoforge.powergrid.net.WireCutPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.patryk3211.powergrid.electricity.base.IElectric;

public final class CryptandWireCutHandler {

    private CryptandWireCutHandler() {
    }

    /** WireCutter 判定（powergrid:wire_cutter；PowerGrid 的 ModdedItems 注册） */
    private static boolean isWireCutter(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return false;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return id != null && id.getNamespace().equals("powergrid")
                    && id.getPath().equals("wire_cutter");
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        try {
            Level level = event.getLevel();
            if (level == null || !level.isClientSide) return;
            if (!CryptandWirePlacement.selfManaged(level)) return;
            Player player = event.getEntity();
            ItemStack stack = event.getItemStack();
            if (player == null || stack.isEmpty()) return;

            // ════════ 测量工具（客户端）════════
            // shift+右键方块：清除测量工具选中的目标（不需要看向导线）
            if (player.isShiftKeyDown()
                    && stack.getItem() instanceof CryptandMeterItem) {
                CryptandMeterItem.clearTarget(stack);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[Measure] clear-target block item={}", stack.getItem());
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            // 看向导线 → 剪线钳剪线（始终拦截）
            WireLookStore.WireHit hit = WireLookStore.current();
            if (isWireCutter(stack)) {
                if (hit != null) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new WireCutPayload(hit.ax(), hit.ay(), hit.az(), hit.aTerm(),
                                    hit.bx(), hit.by(), hit.bz(), hit.bTerm()));
                    event.setCanceled(true);
                    event.setCancellationResult(InteractionResult.SUCCESS);
                }
                return;
            }
            // 测量工具抓导线：仅当【未点击电气端子方块】时才拦截——
            // 点击端子 → 走 useOn 电压模式（Pos 第一个点，Neg 第二个点）；
            // 点击普通方块/空气 + 看向导线 → grabWire 电流单点抓取。
            // ⚠ 2026-08-18 修复：之前一律 grabWire 导致“点第二个端点点不出来”
            if (stack.getItem() instanceof CryptandMeterItem
                    && hit != null
                    && org.patryk3211.powergrid.electricity.base.IElectric.getAt(level, event.getPos()) == null) {
                ((CryptandMeterItem) stack.getItem()).grabWire(stack, hit);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[Measure] grab-wire block item={} hit=({},{},{})",
                        stack.getItem(), hit.ax(), hit.ay(), hit.az());
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }

            // ════════ 未看向导线 → 原版剪线钳行为（服务端）════════
            if (level.isClientSide) return; // 以下仅服务端
            if (!isWireCutter(stack)) return;
            BlockPos pos = event.getPos();
            var electric = IElectric.getAt(level, pos);
            if (electric == null) return;
            var state = level.getBlockState(pos);
            if (event.getHitVec() == null) return;
            int terminal = electric.terminalIndexAt(state,
                    event.getHitVec().getLocation()
                            .subtract(pos.getX(), pos.getY(), pos.getZ()));
            if (terminal < 0) return;
            boolean removed = CryptandWirePlacement.removeWiresAt(level, player, pos, terminal);
            if (removed) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 右键（客户端分支）：WireLookPicker 射线检测到玩家【看向导线】→ 按手持
     * 工具分发：
     *   - shift+右键空气（不要求命中导线）→ 清除测量工具选中的目标
     *     （元件/导线：万用表/温度计/示波器 ModeData 清空 → 显示"未连接"）
     *   - 剪线钳 → WireCutPayload（服务端按边拆除 + 返回物品 + 同步）
     *   - 万用表（原版 + 高级）→ 抓取导线：电流模式 + 单点电流点 = 命中导线
     *     一个端点（原版 useOnWire 等价；自管无实体 → 端点身份代替）
     *   - 温度计 → 抓取导线：目标 = 导线段（端点方块定位段，测导线温度）
     * <p>右键方块走 onRightClickBlock（端子剪线/端子测量）；右键空气/方块
     * fallback 走本方法——自管模式导线悬空无碰撞体，准星穿过导线命中后方
     * 方块或空气，靠射线检测（WireLookStore）判定“看向导线”。
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        try {
            Level level = event.getLevel();
            if (level == null || !level.isClientSide) return;
            if (!CryptandWirePlacement.selfManaged(level)) return;
            Player player = event.getEntity();
            ItemStack stack = event.getItemStack();
            if (player == null || stack.isEmpty()) return;
            // shift+右键空气：清除测量工具选中的目标（基于 CryptandMeterItem 基类）
            // ⚠ 在命中判断之前——清除不要求看向导线。
            if (player.isShiftKeyDown()
                    && stack.getItem() instanceof CryptandMeterItem) {
                CryptandMeterItem.clearTarget(stack);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[Measure] clear-target air item={}", stack.getItem());
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            WireLookStore.WireHit hit = WireLookStore.current();
            if (hit == null) return;
            // 剪线钳 → 剪线
            if (isWireCutter(stack)) {
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new WireCutPayload(hit.ax(), hit.ay(), hit.az(), hit.aTerm(),
                                hit.bx(), hit.by(), hit.bz(), hit.bTerm()));
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                return;
            }
            // 测量工具 → 右键看向的导线（基类统一分发，子类覆写 grabWire）
            if (stack.getItem() instanceof CryptandMeterItem meterItem) {
                meterItem.grabWire(stack, hit);
                CryptandNeoForge.WAF_LOGGER.info(
                        "[Measure] grab-wire air item={} hit=({},{},{})",
                        stack.getItem(), hit.ax(), hit.ay(), hit.az());
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
            }
        } catch (Throwable ignored) {
        }
    }
}