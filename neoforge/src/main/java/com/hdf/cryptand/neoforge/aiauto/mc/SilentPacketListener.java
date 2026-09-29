package com.hdf.cryptand.neoforge.aiauto.mc;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * ===== 假玩家的 PacketListener：所有出站包静默丢弃（2026-09-15）=====
 *
 * <p>NeoForge 的 payload 校验在 {@code ServerCommonPacketListenerImpl.send} 里，
 * 而假连接没有 payload 协商记录，于是任何包（Sable 的 udp_activation、本 mod 的 wire_graph_sync…）
 * 都会被判"不能发给这个客户端"抛异常，把假玩家的登录打断在半路。既然它不需要真的收包，就一条都不发。</p>
 */
public class SilentPacketListener extends ServerGamePacketListenerImpl {

    public SilentPacketListener(MinecraftServer server, Connection connection, ServerPlayer player,
                                CommonListenerCookie cookie) {
        super(server, connection, player, cookie);
    }

    @Override
    public void send(Packet<?> packet) {
        // 丢弃
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        // 丢弃
    }
}
