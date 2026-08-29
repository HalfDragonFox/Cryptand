package com.hdf.cryptand.circuitsimulation.comm;

import java.io.IOException;

/**
 * 通信传输（2026-08-22 通信组件：SPI——进程内消息 / TCP / UDP）。
 * <p>
 * 传输层只搬运字节（CommCodec 编解码的请求/响应帧），不感知协议语义：
 * <ul>
 *   <li>{@link InProcessTransport}：进程内消息（同 JVM，对象投递，可靠）；</li>
 *   <li>{@link TcpTransport}：可靠流（length-prefix 帧，可 listen 或 connect）；</li>
 *   <li>{@link UdpTransport}：无连接数据报（不可靠，发送方需可容忍丢失）。</li>
 * </ul>
 * {@link #requestReply} 为同步往返（单飞行：调用方需保证串行，一次一个未完成请求）。
 */
public interface CommTransport {

    /** 传输名（"in-process" / "tcp" / "udp"） */
    String name();

    /** 是否可靠（丢包/乱序容忍） */
    boolean reliable();

    /** 打开并设置本端接收回调（接收外部发来的帧；非阻塞启动接收线程） */
    void open(CommReceiver receiver) throws IOException;

    /** 发送一帧（无往返；服务端回复响应用） */
    void send(byte[] payload) throws IOException;

    /** 同步往返：发送一帧并阻塞等待一帧响应（单飞行；超时抛 IOException） */
    byte[] requestReply(byte[] payload) throws IOException;

    /** 关闭（释放 socket/线程） */
    void close();

    /** 接收回调（传输层收到帧 → 业务分发） */
    interface CommReceiver {
        void onReceive(byte[] payload);
    }
}