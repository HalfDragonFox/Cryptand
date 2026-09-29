package com.hdf.cryptand.serial;

/**
 * 串口状态/错误监听（异步，后端事件线程回调；均为 default，按需覆写）。
 */
public interface SerialEventListener {

    /** 端口打开成功（open() 返回前回调） */
    default void onOpened() {
    }

    /** 端口已关闭（close() 完成时回调） */
    default void onClosed() {
    }

    /** I/O/硬件错误（如设备热拔；onError 回调后端口标记为关闭） */
    default void onError(Throwable error) {
    }
}
