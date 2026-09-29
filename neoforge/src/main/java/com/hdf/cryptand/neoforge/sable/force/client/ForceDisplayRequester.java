/**
 * ===== 力显示客户端请求器（主动端，2026-09-14） =====
 *
 * <p>【客户端是主动端】：本类按间隔发请求；服务端永远被动响应。
 * <ul>
 *   <li>前置条件：总开关开（{@code enableSableForceDisplay}）+ 显示意图
 *       （F3+B 开 / 强制显示）</li>
 *   <li>能力未知 → 发 probe（每 20 tick 一次）；服务端回 supported=false → 进入
 *       UNSUPPORTED：停止一切请求并提示一次（服务器不开接口时客户端零流量、零空转）</li>
 *   <li>能力已知支持 → 按 interval（默认 2 tick = 10Hz）发数据请求</li>
 *   <li>超过 {@link #STALE_MS} 无响应 → 回到 UNKNOWN 重新探测（服务端可能中途关闭）</li>
 * </ul>
 */
package com.hdf.cryptand.neoforge.sable.force.client;

import com.hdf.cryptand.neoforge.sable.config.ConfigSable;
import com.hdf.cryptand.neoforge.sable.force.impl.ForceDisplayBootstrap;
import com.hdf.cryptand.neoforge.sable.force.net.SableForceRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ForceDisplayRequester {

    /** 探测重试间隔（tick）。 */
    private static final int PROBE_INTERVAL_TICKS = 20;
    /** 无响应判定（ms）：超过则回到 UNKNOWN 重探。 */
    private static final long STALE_MS = 4000L;

    private static int probeCooldown = 0;
    private static int tickCounter = 0;

    private ForceDisplayRequester() {
    }

    public static void onClientTick(final ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            ForceDisplayStore.INSTANCE.reset();
            return;
        }
        if (!ConfigSable.ENABLE_SABLE_FORCE_DISPLAY.get()) return;
        if (!wantsDisplay(mc)) return;

        ForceDisplayBootstrap.init();
        ForceDisplayBootstrap.ensureStyles();

        final ForceDisplayStore store = ForceDisplayStore.INSTANCE;
        switch (store.capability()) {
            case UNKNOWN -> {
                if (--probeCooldown <= 0) {
                    probeCooldown = PROBE_INTERVAL_TICKS;
                    PacketDistributor.sendToServer(new SableForceRequestPayload(
                            mc.player.getUUID(), true, false, 0));
                }
            }
            case UNSUPPORTED -> {
                if (!store.noticeLogged()) {
                    store.markNoticeLogged();
                    mc.player.displayClientMessage(
                            Component.literal("§e[Cryptand] 服务器未开启 Sable 力学可视化接口（enableSableForceDisplay=false）"),
                            true);
                }
            }
            case SUPPORTED -> {
                final long last = store.lastUpdateMs();
                if (last > 0 && System.currentTimeMillis() - last > STALE_MS) {
                    store.markStale();
                    return;
                }
                final int interval = Math.max(1, ConfigSable.SABLE_FORCE_DISPLAY_REQUEST_INTERVAL.get());
                if (++tickCounter % interval != 0) return;
                PacketDistributor.sendToServer(new SableForceRequestPayload(
                        mc.player.getUUID(),
                        false,
                        ConfigSable.SABLE_FORCE_DISPLAY_DETAILED.get(),
                        0));
            }
        }
    }

    /** 是否希望显示：强制显示 > F3+B 联动 > 常显（不联动时）。 */
    public static boolean wantsDisplay(final Minecraft mc) {
        if (ConfigSable.SABLE_FORCE_DISPLAY_FORCE_SHOW.get()) return true;
        if (!ConfigSable.SABLE_FORCE_DISPLAY_FOLLOW_HITBOX.get()) return true;
        try {
            return mc.getEntityRenderDispatcher().shouldRenderHitBoxes();
        } catch (final Throwable t) {
            return false;
        }
    }
}
