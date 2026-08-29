package com.hdf.cryptand.circuitsimulation.comm;

/**
 * 通信响应（2026-08-22 通信组件：统一响应协议）。
 * <p>
 * 服务端处理请求后返回：{@link #id()} 回带请求 id（匹配），{@link #ok()}
 * 是否成功，{@link #op()} 原操作回显，{@link #result()} 结果（可序列化，
 * 经通信编码传输），{@link #error()} 失败原因。
 */
public final class CommResponse {

    public final long id;
    public final boolean ok;
    public final CommOp op;
    public final Object result;
    public final String error;

    private CommResponse(long id, boolean ok, CommOp op, Object result, String error) {
        this.id = id;
        this.ok = ok;
        this.op = op;
        this.result = result;
        this.error = error;
    }

    public static CommResponse ok(long id, CommOp op, Object result) {
        return new CommResponse(id, true, op, result, null);
    }

    public static CommResponse ok(long id, CommOp op) {
        return new CommResponse(id, true, op, null, null);
    }

    public static CommResponse fail(long id, CommOp op, String error) {
        return new CommResponse(id, false, op, null, error);
    }

    @Override
    public String toString() {
        return "CommResponse{id=" + id + ", ok=" + ok + ", op=" + op
                + (error != null ? ", err=" + error : "") + "}";
    }
}