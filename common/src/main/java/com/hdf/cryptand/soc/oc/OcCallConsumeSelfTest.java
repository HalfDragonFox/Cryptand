package com.hdf.cryptand.soc.oc;

import com.hdf.cryptand.soc.board.SocBoard;
import com.hdf.cryptand.soc.device.RegBankDevice;
import com.hdf.cryptand.soc.riscv.Rv32Core;

/**
 * ===== 组件调用"兑现且只兑现一次"闸门（纯 Java 零 MC）=====
 *
 * <p>真机 bug 的离线回归（2026-09-18，用户："只是测试代码的话可以用软件测"）。</p>
 *
 * <p>现场：固件在整个会话里只发起 <b>2 次</b>引导请求（{@code trying drive} ×2、
 * {@code handing over} ×1），宿主却执行了 <b>259 次</b> {@code loadProgram}；
 * 时间线显示第 2 次发生在 {@code handing over} 之后 3.9 秒（系统早已跑起来）。
 * 根因：<b>CALL 没有被消费到 guest 可见</b> —— 残留的 {@code CALL=1} 被后续每次 pump
 * 当成新请求反复处理。</p>
 *
 * <p>本闸门把这条 ABI 语义钉死，不需要 MC、不需要固件：</p>
 * <ol>
 *   <li>一次 CALL ⇒ 恰好一次兑现；</li>
 *   <li>之后无论再 pump 多少次都<b>不再</b>兑现（CALL 已被消费）；</li>
 *   <li>新写一次 CALL ⇒ 再兑现一次（防重复不能把正常请求也吞掉）。</li>
 * </ol>
 *
 * <p>跑法：{@code gradlew :common:runOcCallConsumeTest}</p>
 */
public final class OcCallConsumeSelfTest {

    // 与 OcFsBridgeSelfTest 同一套地址（照抄其常量定义，含 RAM_BYTES 的表达式）
    private static final long RAM_BASE = 0x2000_0000L;
    private static final long REG_BASE = 0x1000_0000L;
    private static final int RAM_BYTES = (int) (OcAbi.MAILBOX_BASE - RAM_BASE) + OcAbi.MAILBOX_SPAN;

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        final java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        // ★ 引导服务 = BIOS 的**读盘服务**（INT 13h）：宿主不挑文件、不改地址 —— 这两件事由 guest 给，
        //   所以这里把"guest 给了什么"记下来断言（这正是 2026-09-27 定案的可断言形态）。
        final java.util.concurrent.atomic.AtomicReference<String> askedPath =
                new java.util.concurrent.atomic.AtomicReference<>("(未被调用)");
        final java.util.concurrent.atomic.AtomicLong askedAddr =
                new java.util.concurrent.atomic.AtomicLong(-1L);

        final Rv32Core cpu = new Rv32Core(new Rv32Core.Config().resetVector(0).enableM(true));
        final RegBankDevice regBank = new RegBankDevice(OcArchitectureCore.BRIDGE_REGISTERS, OcAbi.DEVICE_NAME);
        final SocBoard board = SocBoard.builder(cpu)
                .ram(RAM_BASE, RAM_BYTES)
                .device(REG_BASE, regBank, OcAbi.DEVICE_NAME)
                .build();
        // 假总线传 null：引导服务在总线分支之前就返回，本闸门只走引导路径
        final OcArchitectureCore core = new OcArchitectureCore(board, regBank, null);
        core.setBootLoader(new BootLoaderService() {
            @Override
            public long[] readFile(String path, long loadAddr) {
                loads.incrementAndGet();
                askedPath.set(path);
                askedAddr.set(loadAddr);
                return new long[]{16L, loadAddr};
            }
        });
        final OcSandboxBridge bridge = OcSandboxBridge.create(board, REG_BASE, () -> 0L, core::pump);

        // 固件侧发起一次引导读盘请求：路径放 guest 内存里的缓冲区（NUL 结尾），
        // 载入地址走 ARG0 —— 与 hal.c 的 oc_boot_invoke 同序，最后写 CALL=1。
        final byte[] pathBytes = "/boot/system.bin\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        board.writeMemory(RAM_BASE, pathBytes);
        bridge.write((int) (REG_BASE + OcAbi.REG_COMPONENT), 4, OcAbi.HANDLE_BOOT);
        bridge.write((int) (REG_BASE + OcAbi.REG_METHOD), 4, OcAbi.BOOT_METHOD_READ_FILE);
        bridge.write((int) (REG_BASE + OcAbi.REG_ARG0), 4, 0x10000);
        bridge.write((int) (REG_BASE + OcAbi.REG_BUF_ADDR), 4, (int) RAM_BASE);
        bridge.write((int) (REG_BASE + OcAbi.REG_BUF_LEN), 4, pathBytes.length);
        bridge.write((int) (REG_BASE + OcAbi.REG_CALL), 4, 1);
        check("一次 CALL ⇒ 恰好一次兑现", loads.get() == 1);
        check("★ 宿主读出了 guest 给的路径（NUL 剥掉、ASCII 解码）",
                "/boot/system.bin".equals(askedPath.get()));
        check("★ 宿主用的载入地址是 guest 给的那个（宿主不替它改地址）",
                askedAddr.get() == 0x10000L);

        // ★ 回归点：CALL 必须已被消费 —— 后续怎么 pump 都不该再兑现
        for (int i = 0; i < 50; i++) {
            bridge.drainCalls(1);
        }
        core.pump();
        check("后续 50 次 drainCalls + pump 都不再重复兑现（CALL 已被消费）", loads.get() == 1);

        // 新的一次 CALL ⇒ 再兑现一次（防重复不能把正常请求也吞掉）
        bridge.write((int) (REG_BASE + OcAbi.REG_CALL), 4, 1);
        check("新的一次 CALL ⇒ 再兑现一次", loads.get() == 2);

        // ★ 没给路径 = ABI 用错：必须明确报 ERR_BAD_ARGS，**绝不**由宿主猜一个默认文件去读。
        //   ⚠ 这正是 2026-09-27 真机事故的**离线复现**：引导介质上那份**旧** bootloader
        //     （loadSystem 时代，method 0 + 无缓冲区）打的就是这个形状 —— 新宿主按 ABI 拒答，
        //     固件却打出 "host bridge not wired"（与真相无关）。所以本节既钉"不许兼容旧形状"，
        //     也钉"真机上那种停机能被离线重现"。
        //   ⚠ 顺序有讲究：本段**必须**排在下面 COMPONENT=3 那段之前 —— 后面的邮箱用例靠
        //     "COMPONENT 不是引导句柄"才会走总线分支把队列灌满；若把 COMPONENT 留成 HANDLE_BOOT，
        //     每次 pump 都会被引导服务分流掉，队列永远不满（第一次改完就是这样翻的车）。
        bridge.write((int) (REG_BASE + OcAbi.REG_COMPONENT), 4, OcAbi.HANDLE_BOOT);
        bridge.write((int) (REG_BASE + OcAbi.REG_METHOD), 4, OcAbi.BOOT_METHOD_READ_FILE);
        bridge.write((int) (REG_BASE + OcAbi.REG_BUF_LEN), 4, 0);
        bridge.write((int) (REG_BASE + OcAbi.REG_CALL), 4, 1);
        // （直接问寄存器组对象：与核心 pumpBootService 读的就是同一处，不经内存映射绕一圈）
        check("★ 空路径 ⇒ ERROR 且**不再**兑现读盘（宿主不替 guest 猜文件）",
                loads.get() == 2
                        && regBank.get(OcAbi.REG_STATUS / 4) == OcAbi.STATUS_ERROR
                        && regBank.get(OcAbi.REG_ERROR / 4) == OcAbi.ERR_BAD_ARGS);
        bridge.write((int) (REG_BASE + OcAbi.REG_BUF_LEN), 4, pathBytes.length);   // 还原缓冲区

        // 换个组件号：不该再走引导服务（COMPONENT 判定不能被残留值带偏）
        // ⚠ 本段同时是"给后面邮箱用例留下非引导 COMPONENT"的那一步，别挪到它后面去。
        bridge.write((int) (REG_BASE + OcAbi.REG_COMPONENT), 4, 3);
        bridge.write((int) (REG_BASE + OcAbi.REG_CALL), 4, 1);
        check("COMPONENT=3（非引导）⇒ 不再走引导服务", loads.get() == 2);

        // ==================== 邮箱账本：认领 / 写回必须配对（2026-09-27）====================
        //
        // 真机现象：高级分析器的"邮箱未消化调用"**恒为 1**（机器完全空闲、3 次采样一致）。
        // 显示的口径 = guest 内存里 MB_STATE == BUSY 的通道数，而宿主写回是**排队**进 native 内核
        // 命令队列、在指令边界才落地的（SandboxVm.writeMemory = CMD_WMEM）⇒ guest 的 STATE
        // 永远滞后于宿主账本，采样必然把"已兑现、DONE 还在路上"算成"未消化"。
        // 本节把两条口径**分开**钉死：
        //   · 账本口径（mailboxInFlight）：认领 +1 / 写回 -1，空闲必为 0；
        //   · 采样口径（guest STATE）：只作对账，滞后是正常的（下面手工造出这种滞后）。
        final java.util.concurrent.atomic.AtomicInteger busCalls = new java.util.concurrent.atomic.AtomicInteger();
        final ComponentBus fakeBus = new ComponentBus() {
            @Override
            public ComponentBus.Result invoke(ComponentBus.Call call) {
                busCalls.incrementAndGet();
                return ComponentBus.Result.ok(1);
            }

            @Override
            public java.util.List<ComponentBus.Entry> components() {
                return java.util.List.of(new ComponentBus.Entry("addr-0", OcAbi.GPU_COMPONENT_NAME));
            }
        };
        final OcArchitectureCore mc = new OcArchitectureCore(board, regBank, fakeBus);
        postMailbox(board, 0, 0, 0, 0);
        check("邮箱：固件投递后被认领 1 个通道", mc.pollMailbox() == 1);
        check("邮箱：认领后在途 = 1（**账本口径**）", mc.mailboxInFlight() == 1);
        check("邮箱：此刻 guest STATE = BUSY（采样口径，只作对账）",
                board.readInt(OcAbi.mailboxChannel(0) + OcAbi.MB_STATE) == OcAbi.MB_STATE_BUSY);
        check("邮箱：认领记在该通道上（认领=1 / 写回=0 ⇒ 未配平）",
                mc.mailboxClaims(0) == 1 && mc.mailboxFinishes(0) == 0 && !mc.mailboxPaired(0));
        check("邮箱：写回的最终目标必须是 DONE 之外的终态（认领后不该再有 BUSY 残留在账本里）",
                !mc.mailboxAllPaired());

        mc.drainCalls(0);
        check("邮箱：兑现 1 次调用", busCalls.get() == 1);
        check("邮箱：写回后在途 = 0、该通道配平（账本与 guest 的 STATE 无关）",
                mc.mailboxInFlight() == 0 && mc.mailboxPaired(0) && mc.mailboxAllPaired());
        check("邮箱：写回把 guest 的 STATE 改成 DONE",
                board.readInt(OcAbi.mailboxChannel(0) + OcAbi.MB_STATE) == OcAbi.MB_STATE_DONE);

        // ★ 关键回归：**guest 的 STATE 滞后于账本**（宿主写入排队落地）是正常时序 ——
        //   手工把它造出来（账本 0、STATE 仍 BUSY），账本口径必须仍然是 0。
        //   这就是"恒为 1"的成因：采样口径把在途事务当成了积压。
        board.writeInt(OcAbi.mailboxChannel(0) + OcAbi.MB_STATE, OcAbi.MB_STATE_BUSY);
        check("★ 账本口径不受 guest 滞后影响：STATE 停在 BUSY 时 在途 仍为 0",
                mc.mailboxInFlight() == 0 && mc.mailboxAllPaired());
        board.writeInt(OcAbi.mailboxChannel(0) + OcAbi.MB_STATE, OcAbi.MB_STATE_IDLE);

        // 失败路径同样要配对（ERROR 也是"写回"，不是"漏写回"）
        postMailbox(board, 2, 0, 1, 0);
        final OcArchitectureCore mcFail = new OcArchitectureCore(board, regBank, null);   // 总线 null ⇒ ERR_NO_BUS
        check("邮箱：失败路径也被认领", mcFail.pollMailbox() == 1 && mcFail.mailboxInFlight() == 1);
        mcFail.drainCalls(0);
        check("邮箱：失败写回 ERROR、账本配平、在途归零",
                mcFail.mailboxInFlight() == 0 && mcFail.mailboxAllPaired()
                        && board.readInt(OcAbi.mailboxChannel(2) + OcAbi.MB_STATE) == OcAbi.MB_STATE_ERROR);

        // ★ 显示口径 = **积压**（在途且超过宽限期），而不是"在途"：宿主每 tick 消化一次
        //   ⇒ 一条正常调用在途 ≤1 个 tick，不该被算成"未消化"（那正是"空闲也恒报 1"的来源）。
        postMailbox(board, 4, 0, 0, 0);
        final OcArchitectureCore mb = new OcArchitectureCore(board, regBank, fakeBus);
        check("邮箱积压：刚认领（还没到宽限期）⇒ 积压 = 0（正常在途事务不算未消化）",
                mb.pollMailbox() == 1 && mb.mailboxBacklog() == 0);
        final long t0 = System.nanoTime();       // 认领之后取基准时刻（认领时刻 ≤ t0）
        check("邮箱积压：同一时刻 + 宽限期 ⇒ 积压 = 1（合成时刻，确定可复现）",
                mb.mailboxBacklog(t0 + OcArchitectureCore.MAILBOX_DIGEST_GRACE_NANOS) == 1);
        mb.drainCalls(0);
        check("邮箱积压：写回之后积压恒为 0（怎么取时刻都是 0）",
                mb.mailboxBacklog(t0 + OcArchitectureCore.MAILBOX_DIGEST_GRACE_NANOS * 10L) == 0
                        && mb.mailboxInFlight() == 0 && mb.mailboxAllPaired());

        // ★ 队列超上限时**被丢掉的那条也必须写回**：否则固件永远停在 BUSY（component_invoke 是
        //   无限自旋等终态）⇒ 整台机器卡死在那个调用上。这里把队列灌爆来造这个现场。
        postMailbox(board, 3, 0, 0, 0);
        check("邮箱：通道 3 被认领（准备被丢弃）", mc.pollMailbox() == 1 && mc.mailboxInFlight() == 1);
        for (int i = 0; i < 4200; i++) {                  // PENDING_MAX = 4096 ⇒ 头部（通道 3）会被丢
            board.writeInt((int) (REG_BASE + OcAbi.REG_CALL), 1);
            mc.pump();
        }
        check("★ 邮箱：超上限被丢弃的调用也写回 ERROR（不留停在 BUSY 的通道）",
                board.readInt(OcAbi.mailboxChannel(3) + OcAbi.MB_STATE) == OcAbi.MB_STATE_ERROR);
        check("★ 邮箱：丢弃后账本仍然配平（认领 = 写回）",
                mc.mailboxAllPaired() && mc.mailboxInFlight() == 0);
        check("邮箱：丢弃被计数（绝不静默丢）", mc.droppedCalls() > 0);

        System.out.println("[CALLC] " + passed + "/" + (passed + failed) + " checks passed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** 固件侧投递一个邮箱请求（字段写齐后**最后**写 STATE=REQUEST，与 hal.c 的 oc_mb_post 同序） */
    private static void postMailbox(SocBoard board, int channel, int component, int method, int argc) {
        final long base = OcAbi.mailboxChannel(channel);
        board.writeInt(base + OcAbi.MB_COMPONENT, component);
        board.writeInt(base + OcAbi.MB_METHOD, method);
        board.writeInt(base + OcAbi.MB_ARGC, argc);
        board.writeInt(base + OcAbi.MB_BUF_ADDR, 0);
        board.writeInt(base + OcAbi.MB_BUF_LEN, 0);
        board.writeInt(base + OcAbi.MB_STATE, OcAbi.MB_STATE_REQUEST);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  [OK]   " + name);
        } else {
            failed++;
            System.out.println("  [FAIL] " + name);
        }
    }
}
