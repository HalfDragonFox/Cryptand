package com.hdf.cryptand.serial;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 串口对接库自测（2026-09-08）：
 * <pre>./gradlew :common:runSerialTest</pre>
 * 无真实串口也可运行：验证默认配置 / 后端参数映射 / 门面委托 / 接口与后端可替换性
 * （内存模拟后端回环 + 事件回调）。检测到真实串口时附加真实 open 探测（占用不算失败）。
 */
public final class SerialSelfTest {

    private static final List<String> FAILS = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static int passed = 0;

    public static void main(String[] args) {
        System.out.println("=== Serial 串口对接库自测 ===");
        try {
            testLoadUnload();
            testDefaultConfig();
            testMapping();
            testListPortNames();
            testProviderWithFakeBackend();
            testManagerLifecycle();
            testProbeRealPorts();
        } catch (Throwable t) {
            FAILS.add("自测异常: " + t);
            t.printStackTrace();
        }
        System.out.println();
        if (FAILS.isEmpty()) {
            System.out.println("✅ 全部通过 (" + passed + " 项)");
        } else {
            FAILS.forEach(f -> System.out.println("❌ FAIL: " + f));
            throw new AssertionError(FAILS.size() + " 项失败");
        }
    }

    private static void testLoadUnload() {
        // 懒加载生命周期：初始未加载 → load → unload → 使用自动重载
        check("生命周期: 初始未加载", !SerialPortProvider.isLoaded(), "false");
        SerialPortProvider.load();
        check("生命周期: load 后已加载", SerialPortProvider.isLoaded(), "true");
        SerialPortProvider.load();
        check("生命周期: load 幂等", SerialPortProvider.isLoaded(), "true");
        SerialPortProvider.unload();
        check("生命周期: unload 后未加载", !SerialPortProvider.isLoaded(), "false");
        SerialPortProvider.listPortNames(); // 使用自动懒加载
        check("生命周期: 使用自动重载", SerialPortProvider.isLoaded(), "true");
        SerialPortProvider.unload();
    }

    private static void testDefaultConfig() {
        SerialConfig cfg = SerialConfig.of("COM3", 115200);
        check("默认: 端口名", cfg.portName().equals("COM3"), "COM3");
        check("默认: 波特率", cfg.baudRate() == 115200, "115200");
        check("默认: 数据位 8", cfg.dataBits() == SerialDataBits.DATA_8, "DATA_8");
        check("默认: 停止位 1", cfg.stopBits() == SerialStopBits.ONE, "ONE");
        check("默认: 无校验", cfg.parity() == SerialParity.NONE, "NONE");
        check("默认: 无流控", cfg.flowControl() == SerialFlowControl.NONE, "NONE");
        check("默认: 读超时 200", cfg.readTimeoutMs() == SerialConfig.DEFAULT_TIMEOUT_MS, String.valueOf(SerialConfig.DEFAULT_TIMEOUT_MS));
        SerialConfig tweaked = cfg.withBaudRate(57600).withParity(SerialParity.EVEN).withReadTimeoutMs(500);
        check("with 链: 修改生效", tweaked.baudRate() == 57600 && tweaked.parity() == SerialParity.EVEN && tweaked.readTimeoutMs() == 500, "57600/EVEN/500");
        check("with 链: 原配置不变", cfg.baudRate() == 115200 && cfg.parity() == SerialParity.NONE, "115200/NONE");
        boolean threw = false;
        try { SerialConfig.of("", 9600); } catch (IllegalArgumentException e) { threw = true; }
        check("校验: 空端口名拒绝", threw, "IllegalArgumentException");
    }

    private static void testMapping() {
        check("映射: 数据位 8", JSerialCommBackend.dataBitsValue(SerialDataBits.DATA_8) == 8, "8");
        check("映射: 停止位 ONE", JSerialCommBackend.stopBitsValue(SerialStopBits.ONE) == 1, "1");
        check("映射: 停止位 1.5", JSerialCommBackend.stopBitsValue(SerialStopBits.ONE_POINT_FIVE) == 2, "2");
        check("映射: 停止位 TWO", JSerialCommBackend.stopBitsValue(SerialStopBits.TWO) == 3, "3");
        check("映射: 校验 NONE", JSerialCommBackend.parityValue(SerialParity.NONE) == 0, "0");
        check("映射: 校验 EVEN", JSerialCommBackend.parityValue(SerialParity.EVEN) == 2, "2");
        check("映射: 校验 SPACE", JSerialCommBackend.parityValue(SerialParity.SPACE) == 4, "4");
        check("映射: 流控 NONE", JSerialCommBackend.flowValue(SerialFlowControl.NONE) == 0, "0");
        check("映射: 流控 RTS_CTS", JSerialCommBackend.flowValue(SerialFlowControl.RTS_CTS) == (1 | 16), "17");
        check("映射: 流控 XON_XOFF", JSerialCommBackend.flowValue(SerialFlowControl.XON_XOFF) == (65536 | 1048576), String.valueOf(65536 | 1048576));
        check("映射: 超时模式", JSerialCommBackend.timeoutMode(SerialConfig.of("C", 9600)) == (1 | 256), "257");
    }

    private static void testListPortNames() {
        try {
            List<String> names = SerialPortProvider.listPortNames();
            check("枚举串口: 不抛异常", true, names.toString());
        } catch (Throwable t) {
            check("枚举串口: 不抛异常", false, String.valueOf(t));
        }
    }

    private static void testProviderWithFakeBackend() throws Exception {
        SerialPortBackend original = SerialPortProvider.backend();
        try {
            SerialPortProvider.setBackend(new FakeBackend());
            check("替换后端: backend() 生效", SerialPortProvider.backend() instanceof FakeBackend, "fake");
            check("替换后端: listPortNames 委托", SerialPortProvider.listPortNames().equals(List.of("TEST-COM")), "[TEST-COM]");

            SerialPort port = SerialPortProvider.open(SerialConfig.of("TEST-COM", 9600));
            check("门面 open: 打开成功", port.isOpen(), "open");

            byte[] hello = "ping".getBytes();
            port.writeAll(hello, 0, hello.length);
            check("门面 open: 写回环", ((FakePort) port).written == hello.length, String.valueOf(hello.length));

            byte[] buf = new byte[16];
            int n = port.read(buf, 0, buf.length);
            check("门面 open: 读回环", n == hello.length && new String(buf, 0, n).equals("ping"), new String(buf, 0, Math.max(n, 0)));

            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger got = new AtomicInteger(-1);
            port.setDataListener((data, len) -> {
                got.set(len);
                latch.countDown();
            });
            ((FakePort) port).emit("event".getBytes());
            check("事件: onData 回调", latch.await(2, TimeUnit.SECONDS) && got.get() == 5, "len=" + got.get());

            port.close();
            check("门面 open: 关闭", !port.isOpen(), "closed");
        } finally {
            SerialPortProvider.setBackend(original);
        }
        check("替换后端: 恢复默认", SerialPortProvider.backend() == original, "restored");
    }

    private static void testManagerLifecycle() throws Exception {
        SerialPortBackend original = SerialPortProvider.backend();
        try {
            SerialPortProvider.setBackend(new FakeBackend());
            SerialManager mgr = new SerialManager();

            // 申请：默认组
            SerialPort p1 = mgr.acquire(SerialConfig.of("COM-A", 9600));
            check("管理: acquire 打开", p1.isOpen(), "open");
            check("管理: size=1", mgr.size() == 1, "1");

            // 申请到组 "GPS"
            SerialPort p2 = mgr.acquire("GPS", SerialConfig.of("COM-B", 9600));
            SerialPort p3 = mgr.acquire("GPS", SerialConfig.of("COM-C", 115200));
            check("管理: size=3", mgr.size() == 3, "3");
            check("管理: portsOf(GPS)=2", mgr.portsOf("GPS").size() == 2, "2");
            check("管理: UUID 分配", mgr.idOf(p1) != null && !mgr.idOf(p1).equals(mgr.idOf(p2)), "uuid");
            check("管理: 组归属", mgr.groupOf(p2).equals("GPS") && mgr.groupOf(p1).equals(SerialManager.DEFAULT_GROUP), "GPS/default");

            // 实例可用（回环）
            byte[] hi = "hi".getBytes();
            p1.writeAll(hi, 0, hi.length);
            byte[] buf = new byte[4];
            int n = p1.read(buf, 0, buf.length);
            check("管理: 实例可读写", n == 2 && new String(buf, 0, n).equals("hi"), "hi");

            // 释放单个：先关闭再移除
            boolean rel = mgr.release(p1);
            check("管理: release 成功", rel && !p1.isOpen(), "closed");
            check("管理: release 后 size=2", mgr.size() == 2, "2");
            check("管理: 重复 release=false", !mgr.release(p1), "false");

            // 按 UUID 释放
            java.util.UUID id2 = mgr.idOf(p2);
            check("管理: release(uuid)", mgr.release(id2) && !p2.isOpen(), "closed");
            check("管理: release(uuid) 后 size=1", mgr.size() == 1, "1");

            // 整组释放
            int nReleased = mgr.releaseGroup("GPS");
            check("管理: releaseGroup 数量", nReleased == 1 && !p3.isOpen(), "1");
            check("管理: releaseGroup 后 empty", mgr.isEmpty(), "empty");

            // 退出游戏：遍历关闭全部
            SerialPort p4 = mgr.acquire("X", SerialConfig.of("COM-D", 9600));
            SerialPort p5 = mgr.acquire(SerialConfig.of("COM-E", 9600));
            mgr.shutdown();
            check("管理: shutdown 全部关闭", !p4.isOpen() && !p5.isOpen(), "closed");
            check("管理: shutdown 清空", mgr.size() == 0 && mgr.isShutdown(), "empty");
            boolean threw = false;
            try {
                mgr.acquire(SerialConfig.of("COM-F", 9600));
            } catch (IllegalStateException e) {
                threw = true;
            }
            check("管理: shutdown 后拒绝申请", threw, "IllegalStateException");
        } finally {
            SerialPortProvider.setBackend(original);
        }
    }

    private static void testProbeRealPorts() {
        List<String> names = SerialPortProvider.listPortNames();
        if (names.isEmpty()) {
            System.out.println("ℹ 无真实串口，跳过真实 open 探测");
            return;
        }
        System.out.println("ℹ 检测到真实串口: " + names);
        for (String name : names) {
            try {
                SerialPort p = SerialPortProvider.open(SerialConfig.of(name, 9600).withReadTimeoutMs(50));
                System.out.println("ℹ 真实端口可打开: " + name + " -> " + p.config().baudRate() + " baud");
                p.close();
            } catch (IOException e) {
                System.out.println("ℹ 真实端口被占用/不可开(不算失败): " + name + " -> " + e.getMessage());
            }
        }
    }

    // ===== 内存模拟后端（验证门面委托与接口可替换性，回环写读） =====

    private static final class FakeBackend implements SerialPortBackend {
        @Override
        public List<String> listPortNames() {
            return List.of("TEST-COM");
        }

        @Override
        public SerialPort create(SerialConfig config) {
            return new FakePort(config);
        }
    }

    private static final class FakePort implements SerialPort {
        private final SerialConfig config;
        private final LinkedBlockingQueue<Byte> rx = new LinkedBlockingQueue<>();
        private volatile boolean open;
        private volatile int written;
        private volatile SerialDataListener dataListener;

        FakePort(SerialConfig config) {
            this.config = config;
        }

        @Override public String portName() { return config.portName(); }
        @Override public SerialConfig config() { return config; }
        @Override public boolean isOpen() { return open; }
        @Override public void open() { open = true; }
        @Override public void close() { open = false; }
        @Override public int available() { return rx.size(); }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (!open) return -1;
            int n = 0;
            while (n < length) {
                Byte b = rx.poll();
                if (b == null) break;
                buffer[offset + n] = b;
                n++;
            }
            return n;
        }

        @Override
        public int readByte() {
            Byte b = rx.poll();
            return b == null ? -1 : (b & 0xFF);
        }

        @Override
        public int write(byte[] data, int offset, int length) {
            for (int i = 0; i < length; i++) rx.offer(data[offset + i]); // 回环：写即入队
            written += length;
            return length;
        }

        @Override
        public void writeAll(byte[] data, int offset, int length) {
            write(data, offset, length);
        }

        @Override
        public void flush() {
        }

        @Override
        public void setDataListener(SerialDataListener listener) {
            this.dataListener = listener;
        }

        @Override
        public void setEventListener(SerialEventListener listener) {
        }

        void emit(byte[] data) {
            for (byte b : data) rx.offer(b);
            SerialDataListener l = dataListener;
            if (l != null) l.onData(data, data.length);
        }
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("✅ " + label + " (" + detail + ")");
        } else {
            FAILS.add(label + " -> " + detail);
            System.out.println("❌ FAIL: " + label + " -> " + detail);
        }
    }
}
