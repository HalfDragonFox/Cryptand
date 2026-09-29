package com.hdf.cryptand.soc.oc;

import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.device.RegBankDevice;
import com.hdf.cryptand.soc.riscv.Rv32Core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * ===== 组件桥 ⇄ 文件系统：邮箱通道 + **出方向缓冲区**自测（2026-09-18，纯 Java 无 MC）=====
 *
 * <p>这是 {@code cryptand-fs-design.md} §7 阶段 2.1 的验证要求：
 * <b>假 ComponentBus + 真 SocBoard</b>，让它返回 {@code byte[]}，断言 guest 内存与寄存器都正确。
 * 不需要 MC、不需要固件 —— ABI 与语义对不对，在这里就能定论。</p>
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>投递（STATE=REQUEST）→ 宿主认领（BUSY）→ 兑现（DONE），SEQ 自增；</li>
 *   <li>{@code FS_READ} 的出方向：数据写进**固件自带**的缓冲区，result0 = 实际写入字节数；</li>
 *   <li>容量不足：只写 {@code min(容量, 长度)}，result0 = 实际写入量（绝不静默超写）；</li>
 *   <li>{@code FS_LIST}：NUL 分隔的目录名往返；</li>
 *   <li>非出方向方法（{@code FS_EXISTS}）**不碰**缓冲区；</li>
 *   <li>入方向缓冲区（固件 → 宿主）仍照旧：{@code Call.buffer()} 拿到 path 字节。</li>
 * </ol>
 *
 * <p>跑法：{@code gradlew :common:runOcFsBridgeTest}</p>
 */
public final class OcFsBridgeSelfTest {

    private static final long RAM_BASE = 0x2000_0000L;
    private static final long REG_BASE = 0x1000_0000L;
    /** 邮箱就在 RAM 尾部，RAM 必须覆盖到它（与宿主装配同口径） */
    private static final int RAM_BYTES = (int) (OcAbi.MAILBOX_BASE - RAM_BASE) + OcAbi.MAILBOX_SPAN;
    /** 固件"自带"的缓冲区（guest 内存里的一块） */
    private static final int BUF_ADDR = (int) RAM_BASE + 0x8000;

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        System.out.println("=== OC bridge -> filesystem self test (mailbox + outbound buffer, no MC) ===");
        readOutbound();
        readTruncatedByCapacity();
        listOutbound();
        nonOutboundUntouched();
        inboundStillWorks();
        System.out.println("=== 结果：PASS " + passed + " / FAIL " + failed + " ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ==================== 用例 ====================

    /** ① + ②：认领 → 兑现，数据进 guest 缓冲区，result0 = 写入字节数 */
    private static void readOutbound() {
        final FakeBus bus = new FakeBus();
        final Rig rig = build(bus);
        final byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        bus.payload = payload;

        post(rig.board, 0, OcAbi.FS_READ, BUF_ADDR, 64, 12345);
        check("投递后宿主认领 1 个通道", rig.core.pollMailbox() == 1, "1");
        check("认领后通道状态 = BUSY",
                rig.board.readInt(OcAbi.mailboxChannel(0) + OcAbi.MB_STATE) == OcAbi.MB_STATE_BUSY, "BUSY");

        final int drained = rig.core.drainCalls(200);
        check("drainCalls 兑现 1 个", drained == 1, String.valueOf(drained));
        check("兑现后通道状态 = DONE",
                rig.board.readInt(OcAbi.mailboxChannel(0) + OcAbi.MB_STATE) == OcAbi.MB_STATE_DONE, "DONE");
        check("result0 = 实际写入的字节数（5）",
                rig.board.readInt(OcAbi.mailboxChannel(0) + OcAbi.MB_RESULT0) == payload.length,
                "result0=" + rig.board.readInt(OcAbi.mailboxChannel(0) + OcAbi.MB_RESULT0));
        check("guest 缓冲区里出现了那 5 个字节",
                Arrays.equals(rig.board.readMemory(BUF_ADDR, payload.length), payload),
                new String(rig.board.readMemory(BUF_ADDR, payload.length), StandardCharsets.UTF_8));
        check("调用的方法 id 与入参被正确组包",
                methodId(bus.last) == OcAbi.FS_READ && bus.last.args().size() == 1,
                "method=" + bus.last.method() + " argc=" + bus.last.args().size());
        check("SEQ 自增（固件据此识别新一帧结果）",
                rig.board.readInt(OcAbi.mailboxChannel(0) + OcAbi.MB_SEQ) == 1, "seq=1");
    }

    /** ③：容量只有 4 字节、数据 10 字节 ⇒ 只写 4，result0 = 4 */
    private static void readTruncatedByCapacity() {
        final FakeBus bus = new FakeBus();
        final Rig rig = build(bus);
        bus.payload = "0123456789".getBytes(StandardCharsets.UTF_8);

        post(rig.board, 1, OcAbi.FS_READ, BUF_ADDR, 4, 7);
        rig.core.pollMailbox();
        rig.core.drainCalls(200);
        check("容量不足时只写 min(容量, 长度)",
                Arrays.equals(rig.board.readMemory(BUF_ADDR, 4), "0123".getBytes(StandardCharsets.UTF_8)),
                new String(rig.board.readMemory(BUF_ADDR, 4), StandardCharsets.UTF_8));
        check("result0 = 实际写入量（4），不是数据总长（10）",
                rig.board.readInt(OcAbi.mailboxChannel(1) + OcAbi.MB_RESULT0) == 4,
                "result0=" + rig.board.readInt(OcAbi.mailboxChannel(1) + OcAbi.MB_RESULT0));
    }

    /** ④：FS_LIST 的 NUL 分隔目录名 */
    private static void listOutbound() {
        final FakeBus bus = new FakeBus();
        final Rig rig = build(bus);
        final byte[] listing = "a.txt\0sub/\0".getBytes(StandardCharsets.UTF_8);

        post(rig.board, 2, OcAbi.FS_LIST, BUF_ADDR, 64);
        rig.core.pollMailbox();
        rig.core.drainCalls(200);
        check("FS_LIST：目录名写进缓冲区",
                Arrays.equals(rig.board.readMemory(BUF_ADDR, listing.length), listing),
                new String(rig.board.readMemory(BUF_ADDR, listing.length), StandardCharsets.UTF_8));
        check("FS_LIST：result0 = 写入字节数",
                rig.board.readInt(OcAbi.mailboxChannel(2) + OcAbi.MB_RESULT0) == listing.length,
                "result0=" + rig.board.readInt(OcAbi.mailboxChannel(2) + OcAbi.MB_RESULT0));
    }

    /** ⑤：FS_EXISTS 不是出方向 —— 缓冲区必须原封不动 */
    private static void nonOutboundUntouched() {
        final FakeBus bus = new FakeBus();
        final Rig rig = build(bus);
        final byte[] marker = "KEEPME!!".getBytes(StandardCharsets.UTF_8);
        rig.board.writeMemory(BUF_ADDR, marker);

        post(rig.board, 3, OcAbi.FS_EXISTS, BUF_ADDR, 64);
        rig.core.pollMailbox();
        rig.core.drainCalls(200);
        check("非出方向方法不碰缓冲区（内容仍是 MARKER）",
                Arrays.equals(rig.board.readMemory(BUF_ADDR, marker.length), marker),
                new String(rig.board.readMemory(BUF_ADDR, marker.length), StandardCharsets.UTF_8));
        check("非出方向方法照常返回标量 result0",
                rig.board.readInt(OcAbi.mailboxChannel(3) + OcAbi.MB_RESULT0) == 1,
                "result0=" + rig.board.readInt(OcAbi.mailboxChannel(3) + OcAbi.MB_RESULT0));
    }

    /** ⑥：入方向（固件 → 宿主）没被改坏：Call.buffer() 仍拿到 path 字节 */
    private static void inboundStillWorks() {
        final FakeBus bus = new FakeBus();
        final Rig rig = build(bus);
        final byte[] path = "/home/readme.txt".getBytes(StandardCharsets.UTF_8);
        rig.board.writeMemory(BUF_ADDR, path);

        post(rig.board, 4, OcAbi.FS_OPEN, BUF_ADDR, path.length, OcAbi.FS_MODE_R);
        rig.core.pollMailbox();
        rig.core.drainCalls(200);
        check("入方向缓冲区照旧：Call.buffer() = path 字节",
                bus.last != null && Arrays.equals(bus.last.buffer(), path),
                bus.last == null ? "(未收到调用)" : new String(bus.last.buffer(), StandardCharsets.UTF_8));
        check("入方向调用带上了 mode 参数",
                bus.last != null && bus.last.args().size() == 1
                        && ((Number) bus.last.args().get(0)).intValue() == OcAbi.FS_MODE_R,
                bus.last == null ? "(未收到调用)" : bus.last.args().toString());
    }

    // ==================== 装置 ====================

    private record Rig(OcArchitectureCore core, SocBoard board) {
    }

    private static Rig build(ComponentBus bus) {
        final Rv32Core cpu = new Rv32Core(new Rv32Core.Config().resetVector(0).enableM(true));
        final RegBankDevice regBank = new RegBankDevice(OcArchitectureCore.BRIDGE_REGISTERS, OcAbi.DEVICE_NAME);
        final SocBoard board = SocBoard.builder(cpu)
                .ram(RAM_BASE, RAM_BYTES)
                .device(REG_BASE, regBank, OcAbi.DEVICE_NAME)
                .build();
        final OcArchitectureCore core = new OcArchitectureCore(board, regBank, bus);
        return new Rig(core, board);
    }

    /** 固件侧投递：填好字段，最后写 STATE=REQUEST（"写下去就返回，不回读"） */
    private static void post(SocBoard board, int channel, int methodId, int bufAddr, int bufLen, int... args) {
        final long base = OcAbi.mailboxChannel(channel);
        board.writeInt(base + OcAbi.MB_COMPONENT, 1);          // 假总线表里的 filesystem（下标 1）
        board.writeInt(base + OcAbi.MB_METHOD, methodId);
        board.writeInt(base + OcAbi.MB_ARGC, args.length);
        for (int i = 0; i < args.length; i++) {
            board.writeInt(base + OcAbi.MB_ARG0 + i * 4, args[i]);
        }
        board.writeInt(base + OcAbi.MB_BUF_ADDR, bufAddr);
        board.writeInt(base + OcAbi.MB_BUF_LEN, bufLen);
        board.writeInt(base + OcAbi.MB_STATE, OcAbi.MB_STATE_REQUEST);
    }

    private static int methodId(ComponentBus.Call call) {
        if (call == null || call.method().length() < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(call.method().substring(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 假组件总线：只实现"文件系统"该有的几个方法返回值，其余返回 0 */
    private static final class FakeBus implements ComponentBus {

        byte[] payload = new byte[0];
        ComponentBus.Call last;

        @Override
        public Result invoke(Call call) {
            last = call;
            return switch (methodId(call)) {
                case OcAbi.FS_READ -> Result.ok(payload);
                case OcAbi.FS_LIST -> Result.ok("a.txt\0sub/\0".getBytes(StandardCharsets.UTF_8));
                case OcAbi.FS_EXISTS -> Result.ok(1);
                case OcAbi.FS_OPEN -> Result.ok(42);
                default -> Result.ok(0);
            };
        }

        @Override
        public List<Entry> components() {
            return List.of(new Entry("addr-gpu", OcAbi.GPU_COMPONENT_NAME), new Entry("addr-fs", "filesystem"));
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + name + (detail.isEmpty() ? "" : "  " + detail));
        } else {
            failed++;
            System.out.println("  [FAIL] " + name + (detail.isEmpty() ? "" : "  " + detail));
        }
    }
}
