package com.hdf.cryptand.soc.board;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * ===== 共享消息缓存区自测（离线，纯 Java 零 MC）=====
 *
 * <p>跑法：{@code gradlew :common:runMsgQueueTest}</p>
 *
 * <p>两块内容：</p>
 * <ol>
 *   <li><b>宿主侧队列语义</b>（2026-09-26）：持续发到上限 + 虚拟机一次性消费 ——
 *       空取、满丢弃可观测、批量发送、一次取空后能继续、单条长度上限；</li>
 *   <li><b>guest 内存布局与指针推进</b>（2026-09-27，"把缓存区真正接进 guest RAM"）：
 *       头部字节级布局、头/尾指针推进、满/空边界、丢弃计数、跨环尾，
 *       以及<b>用一段假 guest RAM 跑完整协议</b>（宿主发布 → 固件按 tail 逐条取走 →
 *       写回 tail → 宿主回收空间）；最后把 C 侧镜像（{@code hal.h}）与 Java 常量<b>逐项对拍</b>
 *       —— 布局只允许有一份来源，漂移必须在这里就被拦住。</li>
 * </ol>
 */
public final class HostMessageRingSelfTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== Host<->VM message ring self test, no MC ===");
        hostQueueSemantics();
        guestMemoryLayout();
        fakeGuestRamProtocol();
        concurrentAppendAndDrain();
        firmwareHeaderMirror();
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 一、宿主侧队列语义（原有 17 条） ====================

    private static void hostQueueSemantics() {
        final HostMessageRing ring = new HostMessageRing(64);
        check("新队列：待发 0 字节 / 0 条", ring.pendingBytes() == 0 && ring.pendingMessages() == 0);
        check("新队列：一次取空得到空数组", ring.takeAll().length == 0);
        check("新队列：丢弃计数为 0", ring.dropped() == 0);
        check("上限可读（= 构造值）", ring.capacity() == 64);

        // 单条消息：kind + len + 数据
        check("追加一条（kind=串口, 3 字节）", ring.append(HostMessageRing.KIND_SERIAL,
                new byte[]{'a', 'b', 'c'}));
        check("待发 = 2 + 3 = 5 字节 / 1 条", ring.pendingBytes() == 5 && ring.pendingMessages() == 1);
        final byte[] first = ring.takeAll();
        check("取空后内容与线上帧一致（kind/len/data）",
                first.length == 5 && first[0] == (byte) HostMessageRing.KIND_SERIAL
                        && first[1] == 3 && first[2] == 'a' && first[4] == 'c');
        check("取空后队列归零", ring.pendingBytes() == 0 && ring.pendingMessages() == 0);
        check("取空后还能继续追加（环形复用）", ring.append(HostMessageRing.KIND_KEY, new byte[]{1}));

        // 批量发送 + 持续到上限
        final HostMessageRing small = new HostMessageRing(16);
        final byte[] burst = new byte[40];
        for (int i = 0; i < burst.length; i++) {
            burst[i] = (byte) i;
        }
        final int accepted = small.appendBytes(HostMessageRing.KIND_SERIAL, burst, 0, burst.length);
        check("批量发送会在上限处停下（接受 " + accepted + " 条）",
                accepted > 0 && accepted < burst.length);
        check("停下时丢弃计数 > 0（**不静默**）", small.dropped() > 0);
        check("已接受的都能取出来（长度 = 接受条数 ×（1+1+1））",
                small.takeAll().length == accepted * 3);

        // 单条长度上限
        final HostMessageRing big = new HostMessageRing(1024);
        final byte[] tooLong = new byte[300];
        check("超长单条被截到 255 字节", big.append(HostMessageRing.KIND_NOTICE, tooLong));
        check("截断后的帧长度 = 1 + 1 + 255", big.takeAll().length == 257);

        // 边界：刚好填满
        final HostMessageRing exact = new HostMessageRing(3);
        check("刚好填满（1+1+1 = 3）成功", exact.append(HostMessageRing.KIND_KEY, new byte[]{7}));
        check("再追加一条即到上限（返回 false）", !exact.append(HostMessageRing.KIND_KEY, new byte[]{8}));
        check("填满时丢弃计数 = 1", exact.dropped() == 1);
    }

    // ==================== 二、guest 内存布局与边界 ====================

    private static void guestMemoryLayout() {
        // 头被 tail 切成"宿主独占的两段"，两段加起来 + tail 必须正好是 32 字节：
        // 这条断言拦的是"谁又往头里塞了一个字段却忘了改 B 段长度"（那会让 B 段覆盖到别处）。
        check("头部 32 字节 = A 段(8) + tail(4) + B 段(20)",
                HostMessageRing.HEADER_BYTES == 32
                        && HostMessageRing.HOST_A_OFFSET == 0
                        && HostMessageRing.HOST_A_BYTES == 8
                        && HostMessageRing.OFF_TAIL == 8
                        && HostMessageRing.HOST_B_OFFSET == 12
                        && HostMessageRing.HOST_B_BYTES == 20
                        && HostMessageRing.HOST_B_OFFSET + HostMessageRing.HOST_B_BYTES
                            == HostMessageRing.HEADER_BYTES);
        check("偏移互不重叠且都在头内",
                HostMessageRing.OFF_MAGIC == 0 && HostMessageRing.OFF_HEAD == 4
                        && HostMessageRing.OFF_TAIL == 8 && HostMessageRing.OFF_USED == 12
                        && HostMessageRing.OFF_CAPACITY == 16 && HostMessageRing.OFF_DROPPED == 20
                        && HostMessageRing.OFF_STATE == 24 && HostMessageRing.OFF_SEQ == 28);
        check("板级布局：数据区 = MSG_QUEUE_BASE + 32、总长 = 32 + 4096",
                OcBoardLayout.MSG_QUEUE_DATA_BASE == OcBoardLayout.MSG_QUEUE_BASE + 32
                        && OcBoardLayout.MSG_QUEUE_BYTES == 32 + 4096
                        && HostMessageRing.MESSAGE_BYTES == 4096);
        // 消息缓存区必须**紧跟显存窗口之后**（两者都是 guest RAM 的一段，映射区由宿主一起算）
        check("消息缓存区紧跟显存窗口（VRAM_BASE + VRAM_BYTES）",
                OcBoardLayout.MSG_QUEUE_BASE == OcBoardLayout.VRAM_BASE + OcBoardLayout.VRAM_BYTES);

        final HostMessageRing ring = new HostMessageRing(64);
        final byte[] a0 = ring.guestHeaderA();
        check("A 段长度 = 8", a0.length == 8);
        check("magic 小端字节 = 'C' 'M' 'S' 'G'",
                a0[0] == 'C' && a0[1] == 'M' && a0[2] == 'S' && a0[3] == 'G');
        check("空队列：head == tail（单调计数）", ring.head() == ring.tail() && ring.used() == 0);
        check("空队列：state 只有 READY 位", ring.state() == HostMessageRing.STATE_READY);

        ring.append(HostMessageRing.KIND_KEY, new byte[]{'x'});
        final byte[] a1 = ring.guestHeaderA();
        final byte[] b1 = ring.guestHeaderB();
        check("追加 1 条后 head 推进 3 字节（1+1+1）", ring.head() == 3 && a1[4] == 3 && a1[5] == 0);
        check("B 段：used=3 / capacity=64 / dropped=0 / state=READY / seq=1",
                rd32(b1, HostMessageRing.OFF_USED - HostMessageRing.HOST_B_OFFSET) == 3
                        && rd32(b1, HostMessageRing.OFF_CAPACITY - HostMessageRing.HOST_B_OFFSET) == 64
                        && rd32(b1, HostMessageRing.OFF_DROPPED - HostMessageRing.HOST_B_OFFSET) == 0
                        && rd32(b1, HostMessageRing.OFF_STATE - HostMessageRing.HOST_B_OFFSET)
                            == HostMessageRing.STATE_READY
                        && rd32(b1, HostMessageRing.OFF_SEQ - HostMessageRing.HOST_B_OFFSET) == 1);
        check("windowOffset = tail % capacity", ring.windowOffset() == ring.tail() % ring.capacity());
        check("window 拷出 3 字节且 kind/len 正确",
                ring.window().length == 3 && ring.window()[0] == HostMessageRing.KIND_KEY
                        && ring.window()[1] == 1 && ring.window()[2] == 'x');

        // 刚好填满 ⇒ FULL 位点亮；再追加 ⇒ 丢弃 + DROPPED 位
        final HostMessageRing full = new HostMessageRing(6);
        check("填满前半：2 条 3 字节帧都进得去",
                full.append(HostMessageRing.KIND_KEY, new byte[]{1})
                        && full.append(HostMessageRing.KIND_KEY, new byte[]{2}));
        check("刚好填满：used == capacity 且 FULL 位点亮",
                full.used() == 6 && (full.state() & HostMessageRing.STATE_FULL) != 0);
        check("满时再追加被拒 + 丢弃计数 + DROPPED 位",
                !full.append(HostMessageRing.KIND_KEY, new byte[]{3})
                        && full.dropped() == 1
                        && (full.state() & HostMessageRing.STATE_DROPPED) != 0);
        check("满后取值仍能取空（head == tail 之外的判空靠单调计数）",
                full.takeAll().length == 6 && full.used() == 0 && full.head() == full.tail());

        // 丢弃计数是累计值（固件与面板都靠它判断"塞满过"）
        final HostMessageRing drops = new HostMessageRing(4);
        for (int i = 0; i < 10; i++) {
            drops.append(HostMessageRing.KIND_KEY, new byte[]{(byte) i, (byte) i});
        }
        check("丢弃计数累计（10 条里只装得下 1 条 ⇒ dropped = 9）", drops.dropped() == 9);
        check("丢弃计数写进 guest 头（B 段 dropped 字段 = 9）",
                rd32(drops.guestHeaderB(), HostMessageRing.OFF_DROPPED - HostMessageRing.HOST_B_OFFSET) == 9);
    }

    // ==================== 三、假 guest RAM：跑完整协议（含跨环尾） ====================

    private static void fakeGuestRamProtocol() {
        // capacity 8：故意选成"与帧长不整除"，逼出跨环尾的写入与读出
        final HostMessageRing ring = new HostMessageRing(8);
        final FakeGuestRam guest = new FakeGuestRam(HostMessageRing.HEADER_BYTES + 8);
        guest.publish(ring);                    // 平台层开机那次发布：假 guest RAM 先拿到头

        check("假 guest RAM：magic 就绪（宿主发布过头）", guest.ready());
        check("假 guest RAM：空队列 used = 0（固件不会读到垃圾）", guest.used() == 0);

        check("入队 A（3 字节帧）", ring.append(HostMessageRing.KIND_KEY, new byte[]{'A'}));
        check("入队 B（4 字节帧：2 字节负载）", ring.append(HostMessageRing.KIND_SERIAL, new byte[]{'B', 'C'}));
        guest.publish(ring);
        check("发布后 guest 看到 used = 7 / capacity = 8", guest.used() == 7 && guest.capacity() == 8);

        final byte[] buf = new byte[8];
        int n = guest.take(buf);
        check("固件取第 1 条：kind=KEY, 1 字节 'A'", n == 1 && guest.lastKind() == HostMessageRing.KIND_KEY
                && buf[0] == 'A');
        n = guest.take(buf);
        check("固件取第 2 条（跨环尾的帧）：kind=SERIAL, 'B''C'",
                n == 2 && guest.lastKind() == HostMessageRing.KIND_SERIAL && buf[0] == 'B' && buf[1] == 'C');
        check("取空后 guest 视角 used = 0", guest.used() == 0);
        check("固件写回的 tail 与 head 相等（单调计数）", guest.tail() == guest.head());

        // 宿主读回 guest 的 tail ⇒ 空间回收（这正是生产路径每 tick 做的事）
        check("宿主 syncGuestTail 后待发归零", ring.syncGuestTail(guest.tail()) && ring.used() == 0);
        check("回收后还能继续入队（空间真的回来了）",
                ring.append(HostMessageRing.KIND_NOTICE, new byte[]{'D', 'E', 'F'}));
        check("回绕后再发布，guest 仍能取到（跨环尾写入正确）", () -> {
            guest.publish(ring);
            final byte[] b2 = new byte[8];
            final int m = guest.take(b2);
            return m == 3 && guest.lastKind() == HostMessageRing.KIND_NOTICE
                    && b2[0] == 'D' && b2[2] == 'F';
        });

        // 越界的 tail 绝不接受（接受了就是把没发出去的数据判成已消费）
        final HostMessageRing guard = new HostMessageRing(64);
        guard.append(HostMessageRing.KIND_KEY, new byte[]{1, 2, 3});
        final boolean accepted = guard.syncGuestTail(guard.tail() + 99);
        check("越界 tail 被拒（不回收空间）", !accepted && guard.used() == 5
                && guard.syncAnomalies() == 1);
        check("越界 tail 之后 tail 原封不动", guard.tail() == 0);

        // 固件侧的"取空"：整帧地连续拷，装不下就留在队列里（hal_msg_take_all 的语义）
        final HostMessageRing drain = new HostMessageRing(64);
        drain.append(HostMessageRing.KIND_KEY, new byte[]{'1'});
        drain.append(HostMessageRing.KIND_KEY, new byte[]{'2'});
        final byte[] raw = drain.window();
        check("取空 = 两帧连续（3 + 3 = 6 字节）", raw.length == 6
                && raw[0] == HostMessageRing.KIND_KEY && raw[1] == 1 && raw[3] == HostMessageRing.KIND_KEY);
    }

    // ==================== 三点五、并发：多线程追加 + 单线程取空（2026-09-27） ====================

    /**
     * 并发断言：<b>N 个线程同时 append，一个线程按 tick 节奏 takeAll</b>。
     *
     * <p>为什么必须有它（用户 2026-09-27 指出）：队列在真机上**本来就是多写者** ——
     * 服务端 tick（键盘/窗口报文）、OC 组件回调（真人按键）、MCP 工具与子代理脚本都会追加；
     * 而"取空/发布"只有 tick pump 一个线程。没有这一条断言，"偶发丢一次按键"这类问题
     * 只能靠真机撞运气。</p>
     *
     * <p>判据（容量给够 ⇒ 丢与重都只能是 bug）：<b>入队失败 0 条、丢弃 0 条、
     * 取出的条数 = 生产的条数、每条只出现一次</b>。</p>
     */
    private static void concurrentAppendAndDrain() {
        final int threads = 4;
        final int perThread = 2000;
        final HostMessageRing ring = new HostMessageRing(64 * 1024);
        final java.util.List<String> drained =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final java.util.concurrent.atomic.AtomicInteger rejected =
                new java.util.concurrent.atomic.AtomicInteger();
        final Thread[] producers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int id = t;
            producers[t] = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    // 3 字节负载 = 线程号 + 序号（高低位）⇒ 之后可以逐条判"不重不漏"
                    if (!ring.append(HostMessageRing.KIND_KEY,
                            new byte[]{(byte) id, (byte) (i >> 8), (byte) i})) {
                        rejected.incrementAndGet();
                    }
                }
            }, "msg-producer-" + t);
        }
        try {
            for (final Thread producer : producers) {
                producer.start();
            }
            // 消费者 = "tick pump"那一个线程：边生产边取空（这正是真机上的形态）
            final long deadline = System.currentTimeMillis() + 10_000L;
            while (true) {
                parseFrames(ring.takeAll(), drained);
                boolean alive = false;
                for (final Thread producer : producers) {
                    alive |= producer.isAlive();
                }
                if (!alive && ring.pendingBytes() == 0) {
                    break;                              // 生产结束且队列已空
                }
                if (System.currentTimeMillis() > deadline) {
                    break;                              // 兜底：不让闸门挂死
                }
            }
            for (final Thread producer : producers) {
                producer.join();
            }
            parseFrames(ring.takeAll(), drained);        // 收尾：join 之后再取一次
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            check("并发：测试线程未被中断", false);
        }
        check("并发：4 线程 × 2000 条全部入队（容量够 ⇒ 一条没丢）",
                rejected.get() == 0 && ring.dropped() == 0);
        check("并发：取出的条数 = 生产的条数（" + drained.size() + " / " + (threads * perThread) + "）",
                drained.size() == threads * perThread);
        check("并发：每条消息只出现一次（不重）",
                new java.util.HashSet<>(drained).size() == drained.size());
        check("并发：取空后队列归零（字节守恒的另一面）", ring.pendingBytes() == 0);
    }

    /** 拆帧（kind 1 + len 1 + 数据）→ "线程号:序号"；半帧说明被撕裂 ⇒ 记成 BROKEN 让断言炸 */
    private static void parseFrames(byte[] frames, java.util.List<String> out) {
        int at = 0;
        while (at + 2 <= frames.length) {
            final int len = frames[at + 1] & 0xFF;
            if (at + 2 + len > frames.length) {
                out.add("BROKEN");
                return;
            }
            final int id = frames[at + 2] & 0xFF;
            final int seq = ((frames[at + 3] & 0xFF) << 8) | (frames[at + 4] & 0xFF);
            out.add(id + ":" + seq);
            at += 2 + len;
        }
    }

    // ==================== 四、固件侧 C 镜像（hal.h）逐项对拍 ====================

    private static void firmwareHeaderMirror() {
        final Path header = locateFirmwareHeader();
        if (header == null) {
            check("找得到固件的 hal.h（布局的 C 侧镜像）", false);
            return;
        }
        final Map<String, Long> defines = new HashMap<>();
        try {
            // ⚠ 不能按 US-ASCII 解（hal.h 里有中文注释，US-ASCII 解码器遇到非法字节会直接抛）：
            //   按 ISO-8859-1 逐字节无损映射，够用了 —— 我们只读 #define 那几行，它们本来就是 ASCII。
            final String[] lines = new String(Files.readAllBytes(header), StandardCharsets.ISO_8859_1)
                    .split("\\R");
            for (final String rawLine : lines) {
                final String line = rawLine.trim();
                if (!line.startsWith("#define")) {
                    continue;
                }
                final String[] parts = line.split("\\s+");
                if (parts.length < 3 || !parts[1].startsWith("HAL_MSG_")) {
                    continue;
                }
                final String value = parts[2].replace("u", "").replace("U", "").replace("L", "");
                try {
                    defines.put(parts[1], Long.decode(value));
                } catch (NumberFormatException ignored) {
                    // 不是数字（宏拼接之类）：对拍时会被判成缺失
                }
            }
        } catch (IOException e) {
            check("读得到固件的 hal.h（" + header + "）", false);
            return;
        }
        check("解析到 hal.h 里的 HAL_MSG_* 定义（" + defines.size() + " 条）", defines.size() >= 20);

        expect(defines, "HAL_MSG_BASE", OcBoardLayout.MSG_QUEUE_BASE);
        expect(defines, "HAL_MSG_BYTES", OcBoardLayout.MSG_QUEUE_BYTES);
        expect(defines, "HAL_MSG_DATA_OFF", HostMessageRing.HEADER_BYTES);
        expect(defines, "HAL_MSG_MAGIC", HostMessageRing.MAGIC);
        expect(defines, "HAL_MSG_OFF_MAGIC", HostMessageRing.OFF_MAGIC);
        expect(defines, "HAL_MSG_OFF_HEAD", HostMessageRing.OFF_HEAD);
        expect(defines, "HAL_MSG_OFF_TAIL", HostMessageRing.OFF_TAIL);
        expect(defines, "HAL_MSG_OFF_USED", HostMessageRing.OFF_USED);
        expect(defines, "HAL_MSG_OFF_CAPACITY", HostMessageRing.OFF_CAPACITY);
        expect(defines, "HAL_MSG_OFF_DROPPED", HostMessageRing.OFF_DROPPED);
        expect(defines, "HAL_MSG_OFF_STATE", HostMessageRing.OFF_STATE);
        expect(defines, "HAL_MSG_OFF_SEQ", HostMessageRing.OFF_SEQ);
        expect(defines, "HAL_MSG_HEADER_BYTES", HostMessageRing.HEADER_BYTES);
        expect(defines, "HAL_MSG_CAPACITY_MAX", HostMessageRing.MESSAGE_BYTES);
        expect(defines, "HAL_MSG_STATE_READY", HostMessageRing.STATE_READY);
        expect(defines, "HAL_MSG_STATE_FULL", HostMessageRing.STATE_FULL);
        expect(defines, "HAL_MSG_STATE_DROPPED", HostMessageRing.STATE_DROPPED);
        expect(defines, "HAL_MSG_KIND_SERIAL", HostMessageRing.KIND_SERIAL);
        expect(defines, "HAL_MSG_KIND_KEY", HostMessageRing.KIND_KEY);
        expect(defines, "HAL_MSG_KIND_EVENT", HostMessageRing.KIND_EVENT);
        expect(defines, "HAL_MSG_KIND_NOTICE", HostMessageRing.KIND_NOTICE);
    }

    /** 对拍一条：#define 必须存在且数值相等（缺了、或值不同都算 FAIL —— 布局不允许有两份） */
    private static void expect(Map<String, Long> defines, String name, long javaValue) {
        final Long c = defines.get(name);
        check("hal.h 对拍：" + name + " = " + javaValue + (c == null ? "（C 侧缺失）"
                : ("（C 侧 " + c + "）")), c != null && c == javaValue);
    }

    /** 从当前工作目录向上找 excode/firmware/common/hal.h（IDE 直跑时工作目录可能不是仓库根） */
    private static Path locateFirmwareHeader() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            final Path candidate = dir.resolve("excode").resolve("firmware").resolve("common").resolve("hal.h");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    // ==================== 工具 ====================

    /** 一段**假 guest RAM**：照着平台层的写序把窗口落进去，再按固件的读法取消息 */
    private static final class FakeGuestRam {
        private final byte[] mem;
        private int lastKind = -1;

        FakeGuestRam(int bytes) {
            this.mem = new byte[bytes];
        }

        int rd32(int off) {
            return (mem[off] & 0xFF) | ((mem[off + 1] & 0xFF) << 8)
                    | ((mem[off + 2] & 0xFF) << 16) | ((mem[off + 3] & 0xFF) << 24);
        }

        void wr32(int off, int v) {
            mem[off] = (byte) v;
            mem[off + 1] = (byte) (v >>> 8);
            mem[off + 2] = (byte) (v >>> 16);
            mem[off + 3] = (byte) (v >>> 24);
        }

        boolean ready() {
            return rd32(HostMessageRing.OFF_MAGIC) == HostMessageRing.MAGIC;
        }

        int capacity() {
            return rd32(HostMessageRing.OFF_CAPACITY);
        }

        int head() {
            return rd32(HostMessageRing.OFF_HEAD);
        }

        int tail() {
            return rd32(HostMessageRing.OFF_TAIL);
        }

        int used() {
            return head() - tail();
        }

        int lastKind() {
            return lastKind;
        }

        /** 宿主发布（与 CryptandOcArchitecture.pumpMessageQueue 的写序一致：先数据、后 A 段、再 B 段） */
        void publish(HostMessageRing ring) {
            final byte[] win = ring.window();
            if (win.length > 0) {
                final int at = ring.windowOffset();
                final int first = Math.min(win.length, ring.capacity() - at);
                System.arraycopy(win, 0, mem, HostMessageRing.HEADER_BYTES + at, first);
                if (win.length > first) {
                    System.arraycopy(win, first, mem, HostMessageRing.HEADER_BYTES, win.length - first);
                }
            }
            System.arraycopy(ring.guestHeaderA(), 0, mem, HostMessageRing.HOST_A_OFFSET,
                    HostMessageRing.HOST_A_BYTES);
            System.arraycopy(ring.guestHeaderB(), 0, mem, HostMessageRing.HOST_B_OFFSET,
                    HostMessageRing.HOST_B_BYTES);
        }

        /** guest 侧取一条（hal.c 的 hal_msg_take 的模型）：返回负载长度；-1 = 没就绪；-2 = 装不下 */
        int take(byte[] out) {
            if (!ready()) {
                return -1;
            }
            final int cap = capacity();
            if (cap <= 0) {
                return -1;
            }
            final int head = head();
            final int tail = tail();
            final int used = head - tail;
            if (used == 0) {
                return 0;
            }
            if (used > cap) {
                return -1;
            }
            final byte[] hdr = fetch(tail, 2);
            lastKind = hdr[0] & 0xFF;
            final int len = hdr[1] & 0xFF;
            if (len + 2 > used) {
                return -1;
            }
            if (len > out.length) {
                wr32(HostMessageRing.OFF_TAIL, tail + len + 2);
                return -2;
            }
            final byte[] payload = fetch(tail + 2, len);
            System.arraycopy(payload, 0, out, 0, len);
            wr32(HostMessageRing.OFF_TAIL, tail + len + 2);
            return len;
        }

        /** 从单调计数 off 起连续取 n 字节（跨环尾分两段）—— 与 hal.c 的 msg_read 同一套做法 */
        private byte[] fetch(int off, int n) {
            final int cap = capacity();
            final byte[] out = new byte[n];
            final int at = off % cap;
            final int first = Math.min(n, cap - at);
            for (int i = 0; i < first; i++) {
                out[i] = mem[HostMessageRing.HEADER_BYTES + at + i];
            }
            for (int i = first; i < n; i++) {
                out[i] = mem[HostMessageRing.HEADER_BYTES + (i - first)];
            }
            return out;
        }
    }

    private static int rd32(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16) | ((buf[off + 3] & 0xFF) << 24);
    }

    // ==================== 断言 ====================

    private interface BoolSupplier {
        boolean get();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }

    /** 需要在断言里做副作用（如"发布 + 取一条"）时用；异常一律算 FAIL（别把崩溃当通过） */
    private static void check(String name, BoolSupplier body) {
        boolean ok;
        try {
            ok = body.get();
        } catch (RuntimeException e) {
            ok = false;
            System.out.println("  [FAIL] " + name + " —— 抛异常：" + e);
        }
        check(name, ok);
    }
}
