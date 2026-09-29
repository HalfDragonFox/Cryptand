package com.hdf.cryptand.serial;

import java.util.List;

/**
 * 串口后端 SPI（对接库可替换性）：枚举系统串口 + 创建端口实例。
 * <p>
 * 默认实现 {@link JSerialCommBackend}（jSerialComm 跨平台后端）；
 * 平台/测试可用 {@link SerialPortProvider#setBackend} 替换（虚拟串口 / 模拟后端）。
 */
public interface SerialPortBackend {

    /** 枚举系统可用串口名（无串口返回空列表，不抛异常） */
    List<String> listPortNames();

    /** 按配置创建端口实例（不打开；打开由 {@link SerialPort#open} 负责） */
    SerialPort create(SerialConfig config);
}
