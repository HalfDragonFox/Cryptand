package com.hdf.cryptand.circuitsimulation.comm;

/**
 * 通信编解码（2026-08-22 通信组件：协议序列化）。
 * <p>
 * 把 {@link CommRequest} / {@link CommResponse} 与字节流互转——TCP/UDP 等
 * 传输层只搬运字节，协议层统一编解码（跨传输一致）。
 */
public interface CommCodec {

    byte[] encodeRequest(CommRequest r);

    CommRequest decodeRequest(byte[] payload);

    byte[] encodeResponse(CommResponse r);

    CommResponse decodeResponse(byte[] payload);
}