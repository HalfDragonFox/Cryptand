package com.hdf.cryptand.soc.sandbox;

import com.hdf.cryptand.soc.api.SocFault;
import com.hdf.cryptand.soc.board.SocBoard;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== SoC 沙箱运行时（2026-09-15 soc 子包，纯 Java 零 MC）=====
 *
 * <p>沙箱的执行宿主：<b>预算 / 配额 / 异常边界 / 故障态 / 统计</b>。宿主（MC 平台侧）
 * 每 tick 调一次 {@link #tick()} 即可，其余全部由沙箱自理。</p>
 *
 * <h3>四条沙箱纪律（见记忆 `soc-simulation-architecture.md`）</h3>
 * <ol>
 *   <li><b>能力表</b>：对外通道只有 {@link SocBoard} 上挂载的设备，无隐式通道；</li>
 *   <li><b>预算</b>：每 tick 至多 {@link Limits#cyclesPerTick} 条指令，到点返回（协作式中断）；</li>
 *   <li><b>配额</b>：地址空间总量 / 设备数上限（防止一个芯片吃掉服务器）；</li>
 *   <li><b>异常边界</b>：任何未预期异常都被收敛为"芯片故障"，<b>绝不上抛到游戏</b>。</li>
 * </ol>
 *
 * <p>线程纪律：本类<b>不是线程安全</b>的——一个实例只应由它的所有者线程访问
 * （平台侧用 PinnedWorker 或共享池的顺序时间片）。</p>
 */
public final class SocSandbox {

    /** 沙箱限额（由配置注入） */
    public static final class Limits {
        /** 每 tick 指令预算（等效"主频"：20 tick/s × 200k = 4 MHz） */
        public int cyclesPerTick = 200_000;
        /** 地址空间总量上限（字节） */
        public int maxMemoryBytes = 64 * 1024;
        /** 设备数量上限 */
        public int maxDevices = 8;

        public Limits cyclesPerTick(int v) {
            this.cyclesPerTick = Math.max(1, v);
            return this;
        }

        public Limits maxMemoryBytes(int v) {
            this.maxMemoryBytes = Math.max(64, v);
            return this;
        }

        public Limits maxDevices(int v) {
            this.maxDevices = Math.max(1, v);
            return this;
        }
    }

    /** 沙箱状态（UI/调度器读取） */
    public enum State {
        /** 正常运行 */
        RUNNING,
        /** 已停机（故障或程序主动 ecall 退出） */
        HALTED,
        /** 等待中断（WFI） */
        WAITING,
        /** 沙箱级故障（宿主异常边界触发） */
        SANDBOX_FAULTED
    }

    private final SocBoard board;
    private final Limits limits;

    private boolean sandboxFaulted;
    private SocFault sandboxFault = SocFault.NONE;
    private String sandboxFaultDetail = "";

    private long ticks;
    private long cycles;
    private long lastTickNanos;
    private long maxTickNanos;
    private long lastExecuted;

    public SocSandbox(SocBoard board) {
        this(board, new Limits());
    }

    public SocSandbox(SocBoard board, Limits limits) {
        this.board = board;
        this.limits = limits == null ? new Limits() : limits;
        verifyQuota();
    }

    // ==================== 配额校验 ====================

    private void verifyQuota() {
        if (board.memoryBytes() > limits.maxMemoryBytes) {
            throw new IllegalArgumentException("SoC memory " + board.memoryBytes()
                    + "B exceeds sandbox quota " + limits.maxMemoryBytes + "B");
        }
        if (board.devices().size() > limits.maxDevices) {
            throw new IllegalArgumentException("SoC device count " + board.devices().size()
                    + " exceeds sandbox quota " + limits.maxDevices);
        }
    }

    // ==================== 执行 ====================

    /**
     * 执行一个 tick 的预算。
     *
     * @return 本 tick 实际执行的指令数（0 = 停机/故障/等待中断）
     */
    public int tick() {
        if (sandboxFaulted) {
            return 0;
        }
        final long start = System.nanoTime();
        try {
            final int executed = board.step(limits.cyclesPerTick);
            cycles += executed;
            lastExecuted = executed;
            ticks++;
            return executed;
        } catch (Throwable t) {
            // ★ 异常边界：任何未预期异常（含设备实现 bug）→ 芯片故障，绝不上抛
            sandboxFaulted = true;
            sandboxFaultDetail = t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage());
            sandboxFault = new SocFault(SocFault.CAUSE_ILLEGAL_INSTRUCTION,
                    board.cpu().getProgramCounter(), board.cpu().getProgramCounter(),
                    "sandbox boundary: " + sandboxFaultDetail);
            return 0;
        } finally {
            final long elapsed = System.nanoTime() - start;
            lastTickNanos = elapsed;
            if (elapsed > maxTickNanos) {
                maxTickNanos = elapsed;
            }
        }
    }

    /** 复位芯片（清故障、重跑固件） */
    public void reset() {
        sandboxFaulted = false;
        sandboxFault = SocFault.NONE;
        sandboxFaultDetail = "";
        board.reset();
    }

    /** 重新烧录固件（保持装配，替换 ROM 内容并复位） */
    public void flash(long address, byte[] image) {
        board.loadFirmware(address, image);
        reset();
    }

    // ==================== 状态 ====================

    public State state() {
        if (sandboxFaulted) {
            return State.SANDBOX_FAULTED;
        }
        if (board.cpu().isFaulted()) {
            return State.HALTED;
        }
        if (board.cpu() instanceof com.hdf.cryptand.soc.riscv.Rv32Core rv32 && rv32.isWaitingForInterrupt()) {
            return State.WAITING;
        }
        return State.RUNNING;
    }

    /** 当前故障（内核故障或沙箱故障；无则 {@link SocFault#NONE}） */
    public SocFault getFault() {
        if (sandboxFaulted) {
            return sandboxFault;
        }
        return board.cpu().getFault();
    }

    public SocBoard board() {
        return board;
    }

    public Limits limits() {
        return limits;
    }

    public long ticks() {
        return ticks;
    }

    public long cycles() {
        return cycles;
    }

    public long lastExecutedInstructions() {
        return lastExecuted;
    }

    /** 上一 tick 耗时（ns；调度器负载判据用） */
    public long lastTickNanos() {
        return lastTickNanos;
    }

    /** 历史最大 tick 耗时（ns） */
    public long maxTickNanos() {
        return maxTickNanos;
    }

    /** 能力表（诊断/UI） */
    public List<String> capabilities() {
        return new ArrayList<>(board.capabilityNames());
    }

    /** 诊断摘要（一行） */
    public String describe() {
        return "SoC[" + board.cpu().getName() + " state=" + state()
                + " ticks=" + ticks + " cycles=" + cycles
                + " last=" + (lastTickNanos / 1000) + "us max=" + (maxTickNanos / 1000) + "us"
                + (getFault().isFaulted() ? " fault=" + getFault() : "") + "]";
    }
}
