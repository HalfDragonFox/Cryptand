package com.hdf.cryptand.serial;

/**
 * 串口数据到达监听（异步，后端事件线程回调）。
 * <p>
 * 一次回调 = 事件触发时读尽当前可用字节聚合出的一帧；{@code data.length} 等于 {@code length}。
 * 回调内避免长时间阻塞（会阻塞串口事件线程）；耗时业务请自行切线程。
 */
@FunctionalInterface
public interface SerialDataListener {

    /**
     * 数据到达。
     *
     * @param data   帧数据（新分配的字节数组，可安全持有）
     * @param length 有效字节数（等于 data.length）
     */
    void onData(byte[] data, int length);
}
