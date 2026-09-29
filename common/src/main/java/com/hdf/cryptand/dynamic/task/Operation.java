package com.hdf.cryptand.dynamic.task;

/**
 * ===== 外部只需定义这一个（common 框架工具，2026-09-29）=====
 *
 * <p>用户要求："外部只需要定义读写文件或者其他操作" —— 那就是这个：
 * 一段能抛异常的逻辑（读文件、写文件、解析、装配……），**它自己不关心在哪个线程跑**。</p>
 *
 * <p>线程、并行、加锁、主线程派发全部由 {@link TaskDispatcher} 按 {@link TaskSpec} 的声明决定，
 * 调用方不需要 {@code new Thread} / {@code synchronized} / {@code mc.execute}。</p>
 */
@FunctionalInterface
public interface Operation<T> {

    /** 执行并返回结果（允许抛任何异常，框架负责把它变成失败的 future）。 */
    T run() throws Exception;
}
