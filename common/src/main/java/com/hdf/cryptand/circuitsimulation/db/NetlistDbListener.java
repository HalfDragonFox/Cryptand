package com.hdf.cryptand.circuitsimulation.db;

/**
 * 网表数据库消息监听器（2026-08-15 用户架构：通过消息机制天然支持多线程）。
 * <p>
 * {@link NetlistDatabase} 的所有异步操作（saveSnapshot 等）完成后经
 * {@link com.hdf.cryptand.core.storage.AsyncOp} 回调本监听器——订阅方（UI、网络重建、
 * 日志、其他 mod）无需轮询、无需关心操作发生在哪个线程（写线程/调用方线程），
 * 收到消息即可安全地做后续工作（结果已是不可变快照）。
 * <p>
 * 线程安全：监听器在【写线程】回调（SQLite 单写线程串行），回调内应只做
 * 轻量工作；如需更新 Minecraft 主线程状态，请用 {@code enqueueWork} 转主线程。
 * 默认方法全空，只覆写关心的即可。
 */
public interface NetlistDbListener {

    /** 数据库已打开（含从 meta 恢复 id 盐/计数器） */
    default void onOpened(NetlistDatabase db) {}

    /** 快照保存完成（异步写落盘后回调；snap 为已保存内容，不可变） */
    default void onSaveComplete(NetlistDatabase db, NetlistRecord.NetlistSnapshot snap) {}

    /** 快照加载完成（同步 restore 后回调；snap 为已恢复内容，不可变） */
    default void onLoadComplete(NetlistDatabase db, NetlistRecord.NetlistSnapshot snap) {}

    /** 数据库已关闭（flush 落盘 + 关连接后） */
    default void onClosed(NetlistDatabase db) {}
}
