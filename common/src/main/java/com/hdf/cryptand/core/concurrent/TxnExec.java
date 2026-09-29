package com.hdf.cryptand.core.concurrent;

/**
 * 事务执行者。
 *
 * <p>标准周期里读走 {@link #ASYNC}（工作线程并行）、写走 {@link #MAIN}（主线程独占落地），
 * 但两者都可显式指定 —— 例如「读 MC 世界」必须在主线程做（{@code Level} 无并发读保证），
 * 而「读纯数据」可以放异步。
 *
 * <p><b>主线程永远是慢路径</b>：tick 预算紧，且 {@code TxnScheduler} 对主线程周期设了每 tick 条数上限，
 * 超出的要跨 tick 消化。所以<b>想快速拿到信息就走 {@link #ASYNC}</b>；
 * 只有必须碰 MC 世界（{@code Level} 无并发保证）或必须与主线程同相位的操作才用 {@link #MAIN}。
 */
public enum TxnExec {
    /** 主线程代跑：由主线程在 tick 里按列表执行。 */
    MAIN,
    /** 工作线程跑：提交后交给 {@code Executor}。 */
    ASYNC
}
