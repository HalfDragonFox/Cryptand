package com.hdf.cryptand.integratednetwork;

/**
 * 传输类型（2026-08-26 集成网络核心）。
 * <p>
 * 集成网络核心（{@link IntegratedNetworkCore}）的负载/通道分类：同一框架下可混载
 * 多种内容——物流管道（物品 {@link #ITEM} / 流体 {@link #FLUID}）、能量管道
 * （{@link #ENERGY}）、无线电/数据链路（{@link #SIGNAL}），以及不区分类型的通用
 * 负载（{@link #GENERIC}）。
 * <p>
 * 兼容规则（{@link #compatible}）：任一侧为 {@link #GENERIC}（或未指定）即兼容；
 * 否则要求两侧类型相同——物品管道不可传信号，无线电链路不可运物品。
 */
public enum TransferType {

    /** 通用负载/通道（不限类型；兼容一切） */
    GENERIC,

    /** 物品（物流管道：物品实体、仓库存量等） */
    ITEM,

    /** 流体（流体管道：mB） */
    FLUID,

    /** 能量（能量管道：FE / EU / J 等） */
    ENERGY,

    /** 信号（无线电/网络数据：消息、频点强度） */
    SIGNAL;

    /**
     * 类型兼容判定：任一侧为 GENERIC（或 null=未指定）即兼容；否则要求同类型。
     * 用于边对负载的通行过滤（无线电通道不可运物品，物品管道不可传信号）。
     *
     * @param left  边/通道类型
     * @param right 负载类型
     */
    public static boolean compatible(TransferType left, TransferType right) {
        if (left == null || right == null) return true;
        if (left == GENERIC || right == GENERIC) return true;
        return left == right;
    }
}