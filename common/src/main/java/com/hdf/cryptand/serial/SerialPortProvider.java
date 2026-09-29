package com.hdf.cryptand.serial;

import java.io.IOException;
import java.util.List;

/**
 * 串口门面（2026-09-08 串口对接库：外部使用入口，全静态）。
 * <p>
 * <pre>{@code
 * // 1) 枚举系统串口
 * List<String> ports = SerialPortProvider.listPortNames();    // [COM3, COM5] / [/dev/ttyUSB0]
 *
 * // 2) 打开串口（默认 9600-8N1，可链式改参数）
 * SerialPort port = SerialPortProvider.open(
 *         SerialConfig.of("COM3", 115200).withReadTimeoutMs(500));
 *
 * // 3) 收发（阻塞型 I/O：请在后台线程调用，勿在 MC 主线程）
 * port.writeAll("AT+CSQ\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, 8);
 * int n = port.read(buf, 0, buf.length);
 *
 * // 4) 异步接收
 * port.setDataListener((data, len) -> handleFrame(data, len));
 *
 * // 5) 关闭
 * port.close();
 * }</pre>
 * 默认后端 {@link JSerialCommBackend}（jSerialComm）；可用 {@link #setBackend} 替换
 * （虚拟串口 / 模拟后端 / 未来其他实现）。
 */
public final class SerialPortProvider {

    private static volatile SerialPortBackend backend = new JSerialCommBackend();
    private static volatile boolean loaded = false;

    private SerialPortProvider() {
    }

    // ==================== 子包加载/卸载（懒加载生命周期） ====================

    /**
     * 加载串口子包（幂等）。使用前需加载方可正常初始化使用——本门面在
     * {@link #listPortNames}/{@link #open} 调用前自动懒加载；显式调用用于提前就绪。
     * 实际资源（jSerialComm 三平台本地库）仍为首次使用时才加载，未使用时零开销。
     */
    public static void load() {
        loaded = true;
    }

    /**
     * 卸载串口子包（幂等）：关闭全部已登记实例（经 {@link SerialManager#DEFAULT}）
     * 并释放资源；之后可再次 {@link #load} 或由使用自动懒加载。用于节省内存/兼容场景。
     */
    public static void unload() {
        if (!loaded) return;
        SerialManager.DEFAULT.closeAll();
        loaded = false;
    }

    /** 串口子包是否已加载 */
    public static boolean isLoaded() {
        return loaded;
    }

    private static void ensureLoaded() {
        if (!loaded) load();
    }

    // ==================== 后端与能力 ====================

    /** 当前后端 */
    public static SerialPortBackend backend() {
        return backend;
    }

    /** 替换后端（平台注入 / 测试）；传 null 恢复默认 */
    public static void setBackend(SerialPortBackend newBackend) {
        backend = newBackend != null ? newBackend : new JSerialCommBackend();
    }

    /** 枚举系统可用串口名（未加载自动懒加载） */
    public static List<String> listPortNames() {
        ensureLoaded();
        return backend.listPortNames();
    }

    /** 按完整配置打开串口（未加载自动懒加载；打开失败抛 {@link IOException}） */
    public static SerialPort open(SerialConfig config) throws IOException {
        ensureLoaded();
        SerialPort port = backend.create(config);
        port.open();
        return port;
    }

    /** 便捷：仅串口名 + 波特率打开（其余默认参数） */
    public static SerialPort open(String portName, int baudRate) throws IOException {
        return open(SerialConfig.of(portName, baudRate));
    }
}
