package com.hdf.cryptand.circuitsimulation.comm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 协议编解码（2026-08-22 通信组件：统一对象 → 字节，TCP/UDP 传输用）。
 * <p>
 * 采用 DataOutputStream 风格自包含编码（无外部 JSON 依赖，common 纯核心）。
 * 支持值类型：null / String / Integer / Long / Double / Boolean / List / Map。
 * 帧 = magic + 载荷（请求/响应）；传输层（Tcp/Udp）在帧外加 length 或 datagram 边界。
 */
public final class ProtoCommCodec implements CommCodec {

    private static final byte MAGIC_REQ = 0x51;   // 'Q'
    private static final byte MAGIC_RES = 0x52;   // 'R'

    // 值类型标签
    private static final byte T_NULL = 0;
    private static final byte T_STRING = 1;
    private static final byte T_INT = 2;
    private static final byte T_LONG = 3;
    private static final byte T_DOUBLE = 4;
    private static final byte T_BOOL = 5;
    private static final byte T_LIST = 6;
    private static final byte T_MAP = 7;

    @Override
    public byte[] encodeRequest(CommRequest r) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(MAGIC_REQ);
            out.writeLong(r.id);
            out.writeUTF(r.worldName);
            out.writeUTF(r.op.name());
            writeValue(out, r.data);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode request failed", e);
        }
    }

    @Override
    public CommRequest decodeRequest(byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            byte magic = in.readByte();
            if (magic != MAGIC_REQ) throw new IllegalStateException("bad req magic " + magic);
            long id = in.readLong();
            String world = in.readUTF();
            CommOp op = CommOp.valueOf(in.readUTF());
            Map<String, Object> data = (Map<String, Object>) readValue(in);
            return CommRequest.of(id, world, op, data == null ? new LinkedHashMap<>() : data);
        } catch (IOException e) {
            throw new IllegalStateException("decode request failed", e);
        }
    }

    @Override
    public byte[] encodeResponse(CommResponse r) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(MAGIC_RES);
            out.writeLong(r.id);
            out.writeBoolean(r.ok);
            out.writeUTF(r.op.name());
            writeValue(out, r.result);
            out.writeUTF(r.error == null ? "" : r.error);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode response failed", e);
        }
    }

    @Override
    public CommResponse decodeResponse(byte[] payload) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
            byte magic = in.readByte();
            if (magic != MAGIC_RES) throw new IllegalStateException("bad res magic " + magic);
            long id = in.readLong();
            boolean ok = in.readBoolean();
            CommOp op = CommOp.valueOf(in.readUTF());
            Object result = readValue(in);
            String err = in.readUTF();
            return err.isEmpty() ? (ok ? CommResponse.ok(id, op, result) : CommResponse.fail(id, op, null))
                    : CommResponse.fail(id, op, err);
        } catch (IOException e) {
            throw new IllegalStateException("decode response failed", e);
        }
    }

    // ===== 通用对象编码 =====

    @SuppressWarnings("unchecked")
    private static void writeValue(DataOutputStream out, Object v) throws IOException {
        if (v == null) { out.writeByte(T_NULL); return; }
        if (v instanceof String s) { out.writeByte(T_STRING); out.writeUTF(s); return; }
        if (v instanceof Integer i) { out.writeByte(T_INT); out.writeInt(i); return; }
        if (v instanceof Long l) { out.writeByte(T_LONG); out.writeLong(l); return; }
        if (v instanceof Double d) { out.writeByte(T_DOUBLE); out.writeDouble(d); return; }
        if (v instanceof Boolean b) { out.writeByte(T_BOOL); out.writeBoolean(b); return; }
        if (v instanceof List<?> list) {
            out.writeByte(T_LIST);
            out.writeInt(list.size());
            for (Object o : list) writeValue(out, o);
            return;
        }
        if (v instanceof Map<?, ?> map) {
            out.writeByte(T_MAP);
            out.writeInt(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.writeUTF(String.valueOf(e.getKey()));
                writeValue(out, e.getValue());
            }
            return;
        }
        throw new IOException("unsupported value type: " + v.getClass());
    }

    @SuppressWarnings("unchecked")
    private static Object readValue(DataInputStream in) throws IOException {
        byte t = in.readByte();
        return switch (t) {
            case T_NULL -> null;
            case T_STRING -> in.readUTF();
            case T_INT -> in.readInt();
            case T_LONG -> in.readLong();
            case T_DOUBLE -> in.readDouble();
            case T_BOOL -> in.readBoolean();
            case T_LIST -> {
                int n = in.readInt();
                List<Object> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(readValue(in));
                yield list;
            }
            case T_MAP -> {
                int n = in.readInt();
                Map<String, Object> map = new LinkedHashMap<>(n);
                for (int i = 0; i < n; i++) map.put(in.readUTF(), readValue(in));
                yield map;
            }
            default -> throw new IOException("bad value tag " + t);
        };
    }
}