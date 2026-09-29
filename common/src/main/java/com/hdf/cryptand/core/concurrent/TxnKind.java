package com.hdf.cryptand.core.concurrent;

/**
 * 事务访问方向。
 *
 * <p>单次性事务一次只做一种：要么 {@link #READ}，要么 {@link #WRITE}，不存在 read-modify-write
 * 混合体 —— 混合会让「先读后写」的阶段时序失去意义（读到的可能是本周期还没落地的中间态）。
 */
public enum TxnKind {
    /** 读：只读取，不产生写意图。 */
    READ,
    /** 写：产生写意图。 */
    WRITE
}
