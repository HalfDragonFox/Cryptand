package com.hdf.cryptand.serial;

import java.io.IOException;

/**
 * 串口对接接口（2026-09-08 串口对接库：COM/RS-232 抽象层，外部使用核心）。
 * <p>
 * 经 {@link SerialPortProvider#open} 获取实例后：{@code read/write/readByte} 同步收发，
 * {@link #setDataListener} 异步接收。端口名/波特率等参数在构造时固定（{@link SerialConfig}）。
 * <p>
 * ⚠ 串口 I/O 是阻塞型 I/O，请勿在 MC 主线程调用读写——应由调用方放到后台线程
 * （本库只做 I/O，不假设调用线程；符合主线程/核心分离铁律）。
 */
public interface SerialPort {

    /** 系统串口名（与配置一致，如 "COM3"） */
    String portName();

    /** 本端口使用的配置 */
    SerialConfig config();

    /** 端口是否已打开 */
    boolean isOpen();

    /**
     * 按构造时配置打开端口（重复调用幂等）。
     * 失败（占用/无权限/无效名/设备不存在）抛 {@link IOException}。
     */
    void open() throws IOException;

    /** 关闭端口（幂等；释放本地句柄与监听线程） */
    void close();

    /** 当前可读字节数（未打开抛 {@link IOException}） */
    int available() throws IOException;

    /**
     * 读一帧：最多读取 {@code length} 字节到 {@code buffer[offset..]}。
     * 返回实际读取字节数：
     * <ul>
     *   <li>{@code >0}：读到数据；</li>
     *   <li>{@code 0}：读超时/暂无数据（阻塞语义由 config.readTimeoutMs 决定，0 = 立即返回）；</li>
     *   <li>{@code -1}：端口已关闭。</li>
     * </ul>
     */
    int read(byte[] buffer, int offset, int length) throws IOException;

    /** 读单字节：{@code 0..255} 成功；{@code -1} 超时无数据/端口关闭 */
    int readByte() throws IOException;

    /**
     * 写数据（尽力写）：返回实际写入字节数（{@code 0} = 写超时/失败）。
     * 阻塞语义由 config.writeTimeoutMs 决定。
     */
    int write(byte[] data, int offset, int length) throws IOException;

    /** 写满：循环 write 直到全部写入；写超时抛 {@link IOException} */
    void writeAll(byte[] data, int offset, int length) throws IOException;

    /** 刷新收发缓冲 */
    void flush() throws IOException;

    /**
     * 注册异步数据监听（后端事件线程回调；传 null 移除）。
     * 回调语义见 {@link SerialDataListener}。
     */
    void setDataListener(SerialDataListener listener);

    /** 注册状态/错误监听（传 null 移除） */
    void setEventListener(SerialEventListener listener);
}
