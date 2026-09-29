package com.hdf.cryptand.serial;

import java.util.Objects;

/**
 * 串口连接配置（不可变）。
 * <p>
 * 最简创建 {@link #of(String, int)} 即得默认参数（8 数据位 / 1 停止位 / 无校验 /
 * 无流控 / 读写超时 200ms），再经 {@code withXxx} 链式微调：
 * <pre>{@code
 * SerialConfig cfg = SerialConfig.of("COM3", 115200)
 *         .withDataBits(SerialDataBits.DATA_8)
 *         .withReadTimeoutMs(500);
 * }</pre>
 *
 * @param portName       系统串口名（Windows: "COM3"；Linux: "/dev/ttyUSB0"；macOS: "/dev/cu.usbserial-xxx"）
 * @param baudRate       波特率（9600 / 115200 …）
 * @param dataBits       数据位宽
 * @param stopBits       停止位
 * @param parity         校验位
 * @param flowControl    流控
 * @param readTimeoutMs  读超时（毫秒；0 = 非阻塞，立即返回当前可用字节）
 * @param writeTimeoutMs 写超时（毫秒；阻塞写模式下单次写的最长等待）
 */
public record SerialConfig(
        String portName,
        int baudRate,
        SerialDataBits dataBits,
        SerialStopBits stopBits,
        SerialParity parity,
        SerialFlowControl flowControl,
        int readTimeoutMs,
        int writeTimeoutMs) {

    /** 默认波特率 */
    public static final int DEFAULT_BAUD_RATE = 9600;

    /** 默认读写超时（毫秒） */
    public static final int DEFAULT_TIMEOUT_MS = 200;

    public SerialConfig {
        Objects.requireNonNull(portName, "portName");
        if (portName.isBlank()) throw new IllegalArgumentException("portName 不能为空");
        if (baudRate <= 0) throw new IllegalArgumentException("baudRate 必须为正: " + baudRate);
        Objects.requireNonNull(dataBits, "dataBits");
        Objects.requireNonNull(stopBits, "stopBits");
        Objects.requireNonNull(parity, "parity");
        Objects.requireNonNull(flowControl, "flowControl");
        if (readTimeoutMs < 0) throw new IllegalArgumentException("readTimeoutMs 不能为负: " + readTimeoutMs);
        if (writeTimeoutMs < 0) throw new IllegalArgumentException("writeTimeoutMs 不能为负: " + writeTimeoutMs);
    }

    /** 最简创建：仅串口名 + 波特率，其余用默认值（9600-8N1 之外可链式微调） */
    public static SerialConfig of(String portName, int baudRate) {
        return new SerialConfig(portName, baudRate,
                SerialDataBits.DATA_8, SerialStopBits.ONE, SerialParity.NONE,
                SerialFlowControl.NONE, DEFAULT_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
    }

    public SerialConfig withPortName(String portName) {
        return new SerialConfig(portName, baudRate, dataBits, stopBits, parity, flowControl, readTimeoutMs, writeTimeoutMs);
    }

    public SerialConfig withBaudRate(int baudRate) {
        return new SerialConfig(portName, baudRate, dataBits, stopBits, parity, flowControl, readTimeoutMs, writeTimeoutMs);
    }

    public SerialConfig withDataBits(SerialDataBits dataBits) {
        return new SerialConfig(portName, baudRate, dataBits, stopBits, parity, flowControl, readTimeoutMs, writeTimeoutMs);
    }

    public SerialConfig withStopBits(SerialStopBits stopBits) {
        return new SerialConfig(portName, baudRate, dataBits, stopBits, parity, flowControl, readTimeoutMs, writeTimeoutMs);
    }

    public SerialConfig withParity(SerialParity parity) {
        return new SerialConfig(portName, baudRate, dataBits, stopBits, parity, flowControl, readTimeoutMs, writeTimeoutMs);
    }

    public SerialConfig withFlowControl(SerialFlowControl flowControl) {
        return new SerialConfig(portName, baudRate, dataBits, stopBits, parity, flowControl, readTimeoutMs, writeTimeoutMs);
    }

    public SerialConfig withReadTimeoutMs(int readTimeoutMs) {
        return new SerialConfig(portName, baudRate, dataBits, stopBits, parity, flowControl, readTimeoutMs, writeTimeoutMs);
    }

    public SerialConfig withWriteTimeoutMs(int writeTimeoutMs) {
        return new SerialConfig(portName, baudRate, dataBits, stopBits, parity, flowControl, readTimeoutMs, writeTimeoutMs);
    }
}
