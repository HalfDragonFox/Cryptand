package com.hdf.cryptand.circuitsimulation.comm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通信请求（2026-08-22 通信组件：统一请求协议）。
 * <p>
 * 外部（MC / EDA / 任意客户端）通过通信渠道发送本请求：
 * <ul>
 *   <li>{@link #id()}：请求 id（客户端生成，响应回带——多路复用/异步匹配）；</li>
 *   <li>{@link #worldName()}：目标实例名（路由到 NetworkWorldManager 的哪个世界）；</li>
 *   <li>{@link #op()}：操作（实例创建/数据/消息/查询）；</li>
 *   <li>{@link #data()}：附加参数（Map&lt;String,Object&gt;，可序列化——经通信编码传输）。</li>
 * </ul>
 * 实例完全隔离：客户端不持实例引用，只发请求。
 */
public final class CommRequest {

    public final long id;
    public final String worldName;
    public final CommOp op;
    public final Map<String, Object> data;

    private CommRequest(long id, String worldName, CommOp op, Map<String, Object> data) {
        this.id = id;
        this.worldName = worldName == null ? "" : worldName;
        this.op = op;
        this.data = data == null ? new LinkedHashMap<>() : data;
    }

    public static CommRequest of(long id, String worldName, CommOp op) {
        return new CommRequest(id, worldName, op, new LinkedHashMap<>());
    }

    public static CommRequest of(long id, String worldName, CommOp op, Map<String, Object> data) {
        return new CommRequest(id, worldName, op, data);
    }

    /** data 便捷读写 */
    public CommRequest put(String k, Object v) {
        data.put(k, v);
        return this;
    }

    public Object get(String k) { return data.get(k); }

    @Override
    public String toString() {
        return "CommRequest{id=" + id + ", world=" + worldName + ", op=" + op + "}";
    }
}