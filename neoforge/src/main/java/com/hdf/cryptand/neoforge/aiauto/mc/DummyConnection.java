package com.hdf.cryptand.neoforge.aiauto.mc;

import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * ===== 假玩家用的内存连接（2026-09-15）=====
 *
 * <p>登录流程照走，但所有出站包直接丢弃。为什么要丢：NeoForge 下 mod 会校验 payload 能否发给这个客户端
 * （实测 Sable 抛 {@code Payload sable:udp_activation may not be sent to the client!}）。</p>
 *
 * <p><b>只重写本类是不够的</b>：经堆栈定位，NeoForge 的校验点在
 * {@code ServerCommonPacketListenerImpl.send}（PacketListener 层），不是 {@code Connection.send}。
 * 因此配套 {@code FakePlayerPlayerListMixin} 会把 {@code PlayerList.placeNewPlayer} 里 new 的
 * PacketListener 换成 {@link SilentPacketListener}（出站包全丢），两条一起才生效。</p>
 *
 * <p>挂一个内存 channel 是为了让 {@code isConnected()} 为真 —— 登录流程要用它。
 * 做法参考 Carpet 的 {@code FakeClientConnection}（.ai_cache/fabric-carpet）。</p>
 */
public class DummyConnection extends Connection {

    public DummyConnection() {
        super(PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(this);
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        // 丢弃
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener listener) {
    }
}
