package com.hdf.cryptand.serial;

import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 串口后端实现：jSerialComm（com.fazecast:jSerialComm）跨平台后端。
 * <p>
 * 支持 Windows COM / Linux ttyUSB / macOS cu.*；本地库内嵌于 jar，首次使用自动解压加载。
 * 参数映射见 {@link #dataBitsValue}/{@link #stopBitsValue}/{@link #parityValue}/
 * {@link #flowValue}/{@link #timeoutMode}（public static，供自测验证）。
 */
public final class JSerialCommBackend implements SerialPortBackend {

    @Override
    public List<String> listPortNames() {
        return Arrays.stream(com.fazecast.jSerialComm.SerialPort.getCommPorts())
                .map(com.fazecast.jSerialComm.SerialPort::getSystemPortName)
                .toList();
    }

    @Override
    public SerialPort create(SerialConfig config) {
        return new Port(config);
    }

    // ===== 参数映射（public static：后端无状态，供自测/调用方验证） =====

    /** 数据位：5/6/7/8 直传 */
    public static int dataBitsValue(SerialDataBits bits) {
        return bits.bits;
    }

    /** 停止位 → jSerialComm 常量（ONE=1 / ONE_POINT_FIVE=2 / TWO=3） */
    public static int stopBitsValue(SerialStopBits stopBits) {
        return switch (stopBits) {
            case ONE -> com.fazecast.jSerialComm.SerialPort.ONE_STOP_BIT;
            case ONE_POINT_FIVE -> com.fazecast.jSerialComm.SerialPort.ONE_POINT_FIVE_STOP_BITS;
            case TWO -> com.fazecast.jSerialComm.SerialPort.TWO_STOP_BITS;
        };
    }

    /** 校验位 → jSerialComm 常量（NONE=0 / ODD=1 / EVEN=2 / MARK=3 / SPACE=4） */
    public static int parityValue(SerialParity parity) {
        return switch (parity) {
            case NONE -> com.fazecast.jSerialComm.SerialPort.NO_PARITY;
            case ODD -> com.fazecast.jSerialComm.SerialPort.ODD_PARITY;
            case EVEN -> com.fazecast.jSerialComm.SerialPort.EVEN_PARITY;
            case MARK -> com.fazecast.jSerialComm.SerialPort.MARK_PARITY;
            case SPACE -> com.fazecast.jSerialComm.SerialPort.SPACE_PARITY;
        };
    }

    /** 流控 → jSerialComm 位组合（RTS|CTS / XONXOFF_IN|XONXOFF_OUT） */
    public static int flowValue(SerialFlowControl flowControl) {
        return switch (flowControl) {
            case NONE -> com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_DISABLED;
            case RTS_CTS -> com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_RTS_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_CTS_ENABLED;
            case XON_XOFF -> com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
            case RTS_CTS_XON_XOFF -> com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_RTS_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_CTS_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED
                    | com.fazecast.jSerialComm.SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED;
        };
    }

    /** 超时模式：读半阻塞（有数据即读 / 无数据最多等 readTimeoutMs）+ 写阻塞（最多等 writeTimeoutMs） */
    public static int timeoutMode(SerialConfig config) {
        return com.fazecast.jSerialComm.SerialPort.TIMEOUT_READ_SEMI_BLOCKING
                | com.fazecast.jSerialComm.SerialPort.TIMEOUT_WRITE_BLOCKING;
    }

    /** 单端口实例（包装 com.fazecast.jSerialComm.SerialPort） */
    private static final class Port implements SerialPort {

        private final SerialConfig config;
        private com.fazecast.jSerialComm.SerialPort port;
        private volatile boolean open;
        private volatile SerialDataListener dataListener;
        private volatile SerialEventListener eventListener;

        private final SerialPortDataListener nativeListener = new SerialPortDataListener() {
            @Override
            public int getListeningEvents() {
                return com.fazecast.jSerialComm.SerialPort.LISTENING_EVENT_DATA_AVAILABLE
                        | com.fazecast.jSerialComm.SerialPort.LISTENING_EVENT_PORT_DISCONNECTED;
            }

            @Override
            public void serialEvent(SerialPortEvent event) {
                int type = event.getEventType();
                if (type == com.fazecast.jSerialComm.SerialPort.LISTENING_EVENT_PORT_DISCONNECTED) {
                    // 设备热拔：标记关闭并上报错误
                    Port.this.open = false;
                    SerialEventListener el = Port.this.eventListener;
                    if (el != null) el.onError(new IOException("串口设备已断开: " + config.portName()));
                    return;
                }
                // DATA_AVAILABLE：读尽当前可用字节，聚合成一帧回调
                SerialDataListener l = Port.this.dataListener;
                if (l == null) return;
                try {
                    while (true) {
                        int avail = port.bytesAvailable();
                        if (avail <= 0) break;
                        byte[] buf = new byte[Math.min(avail, 4096)];
                        int n = port.readBytes(buf, buf.length);
                        if (n <= 0) break;
                        l.onData(n == buf.length ? buf : Arrays.copyOf(buf, n), n);
                    }
                } catch (Throwable t) {
                    SerialEventListener el = Port.this.eventListener;
                    if (el != null) el.onError(t);
                }
            }
        };

        Port(SerialConfig config) {
            this.config = config;
        }

        @Override
        public String portName() {
            return config.portName();
        }

        @Override
        public SerialConfig config() {
            return config;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void open() throws IOException {
            if (open) return;
            com.fazecast.jSerialComm.SerialPort sp;
            try {
                sp = com.fazecast.jSerialComm.SerialPort.getCommPort(config.portName());
            } catch (Throwable t) {
                throw new IOException("无效串口名: " + config.portName(), t);
            }
            sp.setComPortParameters(config.baudRate(),
                    dataBitsValue(config.dataBits()), stopBitsValue(config.stopBits()), parityValue(config.parity()));
            sp.setFlowControl(flowValue(config.flowControl()));
            sp.setComPortTimeouts(timeoutMode(config), config.readTimeoutMs(), config.writeTimeoutMs());
            if (!sp.openPort()) {
                throw new IOException("无法打开串口: " + config.portName() + "（可能被占用/无权限/设备不存在）");
            }
            this.port = sp;
            this.open = true;
            if (dataListener != null || eventListener != null) sp.addDataListener(nativeListener);
            SerialEventListener el = eventListener;
            if (el != null) el.onOpened();
        }

        @Override
        public void close() {
            if (!open) return;
            open = false;
            if (port != null) {
                try { port.removeDataListener(); } catch (Throwable ignored) { }
                try { port.closePort(); } catch (Throwable ignored) { }
            }
            SerialEventListener el = eventListener;
            if (el != null) el.onClosed();
        }

        @Override
        public int available() throws IOException {
            ensureOpen();
            return port.bytesAvailable();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            ensureOpen();
            if (offset == 0) {
                return port.readBytes(buffer, length);
            }
            byte[] tmp = new byte[length];
            int n = port.readBytes(tmp, length);
            if (n > 0) System.arraycopy(tmp, 0, buffer, offset, n);
            return n;
        }

        @Override
        public int readByte() throws IOException {
            byte[] b = new byte[1];
            int n = read(b, 0, 1);
            return n > 0 ? (b[0] & 0xFF) : -1;
        }

        @Override
        public int write(byte[] data, int offset, int length) throws IOException {
            ensureOpen();
            if (offset == 0) {
                return Math.max(port.writeBytes(data, length), 0);
            }
            byte[] tmp = Arrays.copyOfRange(data, offset, offset + length);
            return Math.max(port.writeBytes(tmp, length), 0);
        }

        @Override
        public void writeAll(byte[] data, int offset, int length) throws IOException {
            int written = 0;
            while (written < length) {
                int n = write(data, offset + written, length - written);
                if (n <= 0) {
                    throw new IOException("串口写超时: " + config.portName() + " 已写 " + written + "/" + length + " 字节");
                }
                written += n;
            }
        }

        @Override
        public void flush() throws IOException {
            ensureOpen();
            port.flushIOBuffers();
        }

        @Override
        public void setDataListener(SerialDataListener listener) {
            this.dataListener = listener;
            syncListener();
        }

        @Override
        public void setEventListener(SerialEventListener listener) {
            this.eventListener = listener;
            syncListener();
        }

        private void syncListener() {
            if (!open || port == null) return;
            if (dataListener != null || eventListener != null) {
                port.addDataListener(nativeListener);
            } else {
                port.removeDataListener();
            }
        }

        private void ensureOpen() throws IOException {
            if (!open || port == null) throw new IOException("串口未打开: " + config.portName());
        }
    }
}
