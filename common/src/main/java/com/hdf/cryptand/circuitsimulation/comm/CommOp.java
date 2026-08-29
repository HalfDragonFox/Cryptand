package com.hdf.cryptand.circuitsimulation.comm;

/**
 * 通信操作（2026-08-22 用户架构：通信组件——外部与 NetworkWorld 的所有交互
 * 通过统一请求协议，实例完全隔离，不能直接持引用）。
 * <p>
 * 每个操作对应网络世界实例的一个能力；客户端只发请求（按世界名路由），
 * 由服务端处理器映射到 NetworkWorldManager（创建/查询/数据/消息/查询）。
 */
public enum CommOp {

    // ===== 实例生命周期（创建等都通过通信接口） =====
    /** 创建实例（需 worldName + WorldFactory 提供 executor/link；幂等） */
    CREATE_WORLD,
    /** 查询实例是否存在（worldName） */
    GET_WORLD,
    /** 释放实例（worldName） */
    REMOVE_WORLD,
    /** 列出全部实例 */
    LIST_WORLDS,

    // ===== 图数据（建网，直接写入） =====
    /** 设备放置建网（data["keys"]=List<String> 端子 key） */
    ADD_DEVICE,
    /** 接线（data["a"]/data["b"] WirePoint key） */
    ADD_EDGE,
    /** 拆线（data["a"]/data["b"]） */
    REMOVE_EDGE,
    /** 设备拆除（data["p"]） */
    REMOVE_POINT,
    /** 孤立点（data["p"]） */
    ADD_POINT,

    // ===== 图算法 =====
    ENSURE_COMPONENTS,
    COMPACT,
    CLEAR_GRAPH,

    // ===== 消息（异步 → 核心） =====
    /** 网络内容破坏（data["data"]=目标） */
    POST_DESTROY,
    /** 拓扑事件（data["structural"]=boolean） */
    POST_TOPOLOGY,
    /** 求解节拍（data["data"]=forceInit） */
    POST_SOLVE,
    /** 提交通用操作（data["kind"]=NetOpKind 名, data["data"]） */
    SUBMIT,

    // ===== 查询 =====
    QUERY_SUMMARY,
    QUERY_NETWORKS,

    // ===== 导出（2026-08-22 用户需求：导出原理图作为核心功能的扩展功能；
    //     通过发送导出请求（带网络）直接导出虚拟电路，不检测实际 BE 模型） =====
    /** 导出原理图（data["networkKey"]=可选目标网络，null=全部；
     *  data["format"]=可选格式 id，默认 custom-eda；返回内容 JSON/文本） */
    EXPORT_SCHEMATIC,

    /** 存活探测 */
    PING
}