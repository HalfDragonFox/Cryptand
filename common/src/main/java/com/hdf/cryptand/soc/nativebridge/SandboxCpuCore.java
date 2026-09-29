/**
 * ===== 沙箱内核的 CpuCore 适配层（SandboxCpuCore，2026-09-18）=====
 *
 * <p>把<b>自驱动沙箱</b>（{@link SandboxVm}）接到 soc 已有的 {@link CpuCore} 契约上，
 * 使 {@code SocBoard} / {@code OcArchitectureCore} / 组件桥一行都不用改就能拿到
 * "谁持有内存、谁兑现设备"的答案。</p>
 *
 * <h3>为什么执行模型必须换（用户 2026-09-18 定案）</h3>
 * <p>旧模型是"宿主每 tick 喂预算"：宿主算好本 tick 的周期数（{@code MHz × 50000}），
 * 切成小片喂给内核，片间还查 wall-clock 上限。问题有两层：</p>
 * <ol>
 *   <li><b>语义不对</b>：真实 CPU 的时间由自己的时钟决定，不是由"宿主 tick 里剩多少时间"决定。
 *       宿主一卡（GC、区块加载、别的机器抢线程），这台机器的"主频"就跟着掉 —— 频率变成了
 *       宿主调度质量的函数，而不是沙箱的配置。</li>
 *   <li><b>代价极高</b>：预算切片意味着每片一次跨语言调用。10 MHz 下每 tick 5×10⁵ 周期、
 *       片长 1000 ⇒ 每 tick 500 次 {@code step}，而每次 {@code step} 还要附带
 *       "推中断位图 / 查故障 / 查停机 / 查 MMIO" ⇒ 每 tick 约 2500 次 JNI、
 *       <b>每秒 5 万次</b>。这些调用的绝大多数只是在问"你还好吗"。</li>
 * </ol>
 * <p>新模型：<b>配置一次</b>（{@link SandboxVm#clock(int)}，每秒多少周期），沙箱按<b>内部时钟</b>
 * 自己推进并自己限速（跑满就睡），宿主只收消息、只做心跳。频率重新成为沙箱的属性，
 * 宿主卡顿只影响"这一瞬间谁占用 CPU"，不影响机器的标称主频。</p>
 *
 * <h3>本类在自驱动路径上的角色</h3>
 * <ul>
 *   <li>{@link #step(int)} <b>明确不实现</b>：宿主喂预算正是被去掉的那件事（见下）；</li>
 *   <li>设备访问走平台层的 {@code DeviceBus}（沙箱消息 → {@code SandboxVm.dispatch} → 总线），
 *       不再经过 {@code MemoryMap} 的 load/store 分发；</li>
 *   <li>中断位图由心跳一次性下发（{@link SandboxVm#heartbeat(int)}），不走
 *       {@link com.hdf.cryptand.soc.api.InterruptController} 的每次 step 推送；</li>
 *   <li>内存读写仍然只走内核（native 自持 RAM/ROM）——{@link #readMemory} / {@link #writeMemory}
 *       转发到沙箱，这正是"宿主读 guest 内存必须问内核"那条纪律的延续。</li>
 * </ul>
 */
package com.hdf.cryptand.soc.nativebridge;

import com.hdf.cryptand.soc.api.CpuCore;
import com.hdf.cryptand.soc.api.InterruptController;
import com.hdf.cryptand.soc.api.MemoryMap;
import com.hdf.cryptand.soc.api.SocFault;

public final class SandboxCpuCore implements CpuCore {

    private final SandboxVm vm;
    private final long resetVector;

    public SandboxCpuCore(SandboxVm vm, long resetVector) {
        if (vm == null) {
            throw new IllegalArgumentException("vm must not be null");
        }
        this.vm = vm;
        this.resetVector = resetVector;
    }

    /** 底层沙箱（平台层要挂设备总线 / 发心跳 / 下发频率）。 */
    public SandboxVm vm() {
        return vm;
    }

    @Override
    public String getName() {
        return "rv32im-sandbox(self-driven)";
    }

    /**
     * 自驱动沙箱**不接受宿主预算**：设备访问与中断都在平台层兑现，内存映射不参与执行。
     *
     * <p>两个 setter 因此只保留签名（{@code SocBoard} 装配时会调它们），不保存引用 ——
     * 保存下来会让人以为"改这张表能影响沙箱"，而实际上沙箱的设备只在
     * {@code DeviceBus} 那一处。这正是这次改造要去掉的耦合。</p>
     */
    @Override
    public void setMemoryMap(MemoryMap memoryMap) {
        // 设备访问经 DeviceBus；RAM/ROM 由 native 自持 ⇒ 这里无事可做
    }

    @Override
    public void setInterruptController(InterruptController controller) {
        // 中断位图由心跳直接下发（SandboxVm.heartbeat）⇒ 这里无事可做
    }

    @Override
    public void reset() {
        vm.reset();
    }

    /**
     * ⚠ <b>不支持</b>：自驱动沙箱的周期由内部时钟推进，调用方若在这里喂预算，
     * 就等于回到"宿主每 tick 喂预算"的旧模型 —— 那正是本次改造要去掉的路径。
     *
     * <p>明确抛异常而不是静默返回 0：返回 0 会被上层读成"停机/故障"，把
     * "用错了执行模型"伪装成"机器挂了"（项目纪律：不静默降级）。</p>
     */
    @Override
    public int step(int instructionBudget) {
        throw new UnsupportedOperationException(
                "自驱动沙箱不接受宿主预算（cycles=" + instructionBudget
                        + "）：配置一次 clock() 后由内部时钟自跑，见 SandboxVm");
    }

    @Override
    public SocFault getFault() {
        if (!vm.isFaulted()) {
            return SocFault.NONE;
        }
        final int cause = vm.faultCause();
        return new SocFault(cause, Integer.toUnsignedLong(vm.faultTval()),
                Integer.toUnsignedLong(vm.faultEpc()), SocFault.causeName(cause));
    }

    @Override
    public boolean isFaulted() {
        // 与 NativeRv32Core 同口径："停机"（ECALL from M 正常退出）也算停下来了
        return vm.isStopped();
    }

    @Override
    public long getProgramCounter() {
        return Integer.toUnsignedLong(vm.pc());
    }

    @Override
    public long[] getRegisters() {
        final SandboxVm.Snapshot snap = vm.snapshot();
        final long[] out = new long[32];
        for (int i = 0; i < 32; i++) {
            out[i] = Integer.toUnsignedLong(snap.reg(i));
        }
        return out;
    }

    @Override
    public long getInstructionsRetired() {
        return vm.instructionsRetired();
    }

    @Override
    public long getResetVector() {
        return resetVector;
    }

    /**
     * 沙箱消息接口不暴露 CSR（它只推"故障/断点/停机"这类事件）。
     *
     * <p>需要单步级调试时用 {@link SandboxVm} 的断点与 {@link SandboxVm#snapshot()}
     * （PC + 32 寄存器）；CSR 目前没有消费方，不为此扩大 native 接口。</p>
     */
    @Override
    public long getCsr(int csr) {
        return 0L;
    }

    // ==================== 宿主侧内存访问（只走内核）====================

    @Override
    public byte[] readMemory(long address, int length) {
        return vm.readMemory((int) address, length);
    }

    @Override
    public void writeMemory(long address, byte[] data) {
        vm.writeMemory((int) address, data);
    }

    @Override
    public void loadImage(long address, byte[] image) {
        vm.loadImage((int) address, image);
    }

    /**
     * **异步投递**写入：排进沙箱自己的命令队列，由沙箱线程在"下一条指令之前"的固定点执行。
     *
     * <p>这就是用户 2026-09-17 定案里「沙箱在下一条命令周期前插入一条改值命令」的那条路 ——
     * 也对应「是否成功只需要沙箱接收到相应数据后**当前命令周期一条执行完成后先写入寄存器
     * 然后继续运行**即可」。</p>
     *
     * <p>相比 {@link #writeMemory}（宿主线程直接写、与沙箱线程**并发**同一块内存），它有两个好处：
     * 写入严格落在**指令边界**，且内核**独占**机器状态（无跨线程共享可变状态）。</p>
     *
     * <p>底层就是 {@code NativeSandbox.writeMem} = 沙箱命令队列（native 侧 {@code CMD_WMEM}），
     * 所以这里直接转发。</p>
     *
     * <p>精度：默认粒度是 quantum（数百条一轮）；需要精确到**单条**时用断点/同步点把它降到 1
     * —— 那套能力内建在 {@code Sandbox} 里（有断点时 {@code limit = 1}），只在真正需要时才付代价。</p>
     */
    @Override
    public boolean postMemoryWrite(long address, byte[] data) {
        if (data == null || data.length == 0) {
            return true;
        }
        vm.writeMemory((int) address, data);
        return true;
    }

    /** 设备访问次数（诊断：消息泵兑现的 MMIO 事务数）。 */
    public long mmioCount() {
        return vm.mmioCount();
    }

    @Override
    public String toString() {
        return "SandboxCpuCore[pc=0x" + Long.toHexString(getProgramCounter())
                + ", retired=" + vm.instructionsRetired()
                + ", mmio=" + vm.mmioCount()
                + (vm.isHalted() ? ", halted" : vm.isFaulted() ? ", FAULT" : "")
                + "]";
    }
}
