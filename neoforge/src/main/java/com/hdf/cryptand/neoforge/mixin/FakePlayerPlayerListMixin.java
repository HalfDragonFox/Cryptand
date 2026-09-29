package com.hdf.cryptand.neoforge.mixin;

import com.hdf.cryptand.neoforge.aiauto.mc.DummyConnection;
import com.hdf.cryptand.neoforge.aiauto.mc.SilentPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * ===== 让"假玩家登录"绕开 NeoForge 的 payload 校验（2026-09-15）=====
 *
 * <p>问题：{@code PlayerList.placeNewPlayer} 内部 new 一个 {@link ServerGamePacketListenerImpl}，
 * 它 {@code send} 时会走 NeoForge 的 {@code NetworkRegistry.checkPacket}；内存假连接没有 payload
 * 协商记录 → 任何包都被拒 → 登录被 mod 的登录广播（Sable / 本 mod PowerGrid）打断。</p>
 *
 * <p>做法照搬 Carpet 的 {@code PlayerList_fakePlayersMixin}：把那个 NEW 重定向掉，
 * 假连接改用 {@link SilentPacketListener}（出站包全丢），普通玩家不受影响。</p>
 */
@Mixin(PlayerList.class)
public class FakePlayerPlayerListMixin {

    @Redirect(method = "placeNewPlayer",
            at = @At(value = "NEW",
                    target = "(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/network/Connection;"
                            + "Lnet/minecraft/server/level/ServerPlayer;"
                            + "Lnet/minecraft/server/network/CommonListenerCookie;)"
                            + "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;"))
    private ServerGamePacketListenerImpl cryptand$silentListener(MinecraftServer server, Connection connection,
                                                                 ServerPlayer player,
                                                                 CommonListenerCookie cookie) {
        if (connection instanceof DummyConnection) {
            return new SilentPacketListener(server, connection, player, cookie);
        }
        return new ServerGamePacketListenerImpl(server, connection, player, cookie);
    }
}
