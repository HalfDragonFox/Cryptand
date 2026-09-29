/*
 * ============================================================================
 * Cryptand 沙箱实现（sandbox.cpp，2026-09-17）
 *
 * 线程模型（重要）：
 *   · 机器状态（Machine）**只由 run() 所在线程访问** —— Java 侧的一切改动
 *     都走命令队列，在 drainLocked() 里由该线程执行 ⇒ 无需给机器加锁。
 *   · 队列/断点/频率等共享字段由 m_ 保护；run() 在"等命令 / 等领域设备兑现"
 *     时会释放锁，**并且每 kSandboxYieldNs（200µs）主动让出一次**（yieldLocked()）
 *     ⇒ 自驱动满载（due 恒 ≥ 1、循环永不走 wait 分支）时 Java 侧投递也不会被饿死。
 *     ⚠ 这一点曾经写错："等待时才释放锁"在自驱动满载下等于"永不释放"，
 *     导致 heartbeat 永久阻塞 + OC Machine 监视器被 machine 线程扣住 ⇒ 全服卡死。
 *   · 本类**不创建线程**：run() 由 Java 在自己分配的线程里调用（用户定稿：
 *     "沙箱能分配到 Java 定义的线程中运行"）。析构前调用方应先 stop() 并
 *     等 run() 返回（Java 侧 SandboxVm 负责 join）。
 * ============================================================================
 */

#include "sandbox.h"

#ifndef _WIN32
#  include <thread>
#endif

namespace cryptand {

void sbSleepMicros(unsigned long us)
{
#ifdef _WIN32
    /* Windows 的 Sleep 精度约 1ms：不足 1ms 也睡 1ms，避免退化成忙等 */
    ::Sleep(us < 1000UL ? 1UL : (DWORD)(us / 1000UL));
#else
    std::this_thread::sleep_for(std::chrono::microseconds(us));
#endif
}

int64_t Sandbox::nowNs()
{
    return (int64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

Sandbox::Sandbox(const Config& cfg) : machine_(cfg) {}

Sandbox::~Sandbox()
{
    /* 只置位，不等人 —— join 是 Java 侧的责任（见文件头） */
    stop();
}

/* ==================== 配置 ==================== */

void Sandbox::setClockHz(uint64_t hz)
{
    SbGuard lk(m_);
    clockHz_ = hz == 0 ? 1 : hz;
    cv_.notifyAll();
}

uint64_t Sandbox::clockHz()
{
    SbGuard lk(m_);
    return clockHz_;
}

void Sandbox::setQuantum(int cycles)
{
    SbGuard lk(m_);
    quantum_ = cycles < 1 ? 1 : (cycles > 65536 ? 65536 : cycles);
    cv_.notifyAll();
}

/* ==================== Java → 沙箱 ==================== */

void Sandbox::postLocked(const Command& cmd)
{
    cmds_.push_back(cmd);
    noteHostMessageLocked();          /* 宿主说话了：刷新静默计时 / 清自暂停 */
}

/* ==================== 宿主静默看门狗 + 自走时钟 ==================== */

void Sandbox::noteHostMessageLocked()
{
    lastHostMsgNs_ = nowNs();
    if (!idlePaused_) {
        return;
    }
    /* 之前因宿主静默而自暂停：宿主又说话了 ⇒ 自动恢复（这不是用户按的暂停） */
    idlePaused_ = false;
    if (!running_ && !machine_.faulted() && !machine_.halted()) {
        secondStartNs_ = lastHostMsgNs_;
        cyclesThisSecond_ = 0;
        running_ = true;
    }
    cv_.notifyAll();
}

void Sandbox::checkHostWatchdogLocked()
{
    if (idlePauseMs_ == 0 || idlePaused_ || !running_) {
        return;
    }
    const int64_t now = nowNs();
    const int64_t window = (int64_t)idlePauseMs_ * 1000000LL;
    /* 按阈值 1/4 降频检查（下限 250ms）：解释循环每片只做一次时间的比较，
     * 不让 nowNs() 进到每指令路径里。 */
    int64_t probe = window / 4;
    if (probe < 250000000LL) {
        probe = 250000000LL;
    }
    if (now - lastIdleCheckNs_ < probe) {
        return;
    }
    lastIdleCheckNs_ = now;
    if (now - lastHostMsgNs_ > window) {
        idlePaused_ = true;
        running_ = false;             /* 自己暂停：不再吃 CPU，等宿主下一条消息 */
        pushLocked(SB_MSG_IDLE_PAUSED, idlePauseMs_, 0, 0);
    }
}

void Sandbox::setIdlePauseMs(uint32_t ms)
{
    SbGuard lk(m_);
    idlePauseMs_ = ms;
    lastHostMsgNs_ = nowNs();
    lastIdleCheckNs_ = lastHostMsgNs_;
    if (ms == 0 && idlePaused_) {
        idlePaused_ = false;          /* 关掉看门狗就立刻恢复 */
        if (!running_ && !machine_.faulted() && !machine_.halted()) {
            secondStartNs_ = lastHostMsgNs_;
            cyclesThisSecond_ = 0;
            running_ = true;
        }
    }
    cv_.notifyAll();
}

uint32_t Sandbox::idlePauseMs()
{
    SbGuard lk(m_);
    return idlePauseMs_;
}

void Sandbox::cmdLoadImage(uint32_t addr, const uint8_t* data, uint32_t len)
{
    if (data == nullptr || len == 0) {
        return;
    }
    SbGuard lk(m_);
    Command c;
    c.type = CMD_LOAD;
    c.a = addr;
    c.data.assign(data, data + len);
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdReset()
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_RESET;
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdRun()
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_RUN;
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdPause()
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_PAUSE;
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdStep(int cycles)
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_STEP;
    c.a = (uint32_t)(cycles < 1 ? 1 : cycles);
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdSetBreakpoint(uint32_t addr)
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_BP_SET;
    c.a = addr;
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdClearBreakpoint(uint32_t addr)
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_BP_CLR;
    c.a = addr;
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdClearBreakpoints()
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_BP_CLRALL;
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdWriteReg(int index, uint32_t value)
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_WREG;
    c.a = (uint32_t)index;
    c.b = value;
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdWriteMem(uint32_t addr, const uint8_t* data, uint32_t len)
{
    if (data == nullptr || len == 0) {
        return;
    }
    SbGuard lk(m_);
    Command c;
    c.type = CMD_WMEM;
    c.a = addr;
    c.data.assign(data, data + len);
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdSetInterrupts(uint32_t bits)
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_IRQ;
    c.a = bits;
    postLocked(c);
    cv_.notifyAll();
}

void Sandbox::cmdMmioResult(uint32_t value)
{
    SbGuard lk(m_);
    Command c;
    c.type = CMD_MMIO;
    c.a = value;
    postLocked(c);
    cv_.notifyAll();
}

/* ==================== 运行 ==================== */

void Sandbox::stop()
{
    SbGuard lk(m_);
    stop_ = true;
    cv_.notifyAll();
}

void Sandbox::run()
{
    m_.lock();
    stop_ = false;
    secondStartNs_ = nowNs();
    cyclesThisSecond_ = 0;
    lastHostMsgNs_ = secondStartNs_;
    lastIdleCheckNs_ = secondStartNs_;
    idlePaused_ = false;

    while (!stop_) {
        drainLocked();
        if (stop_) {
            break;
        }

        /* 宿主静默看门狗：10 秒（可配置）没收到任何宿主消息 ⇒ 自己暂停，别占着 CPU 空转 */
        checkHostWatchdogLocked();

        if (!running_) {
            /* 没在跑：等命令（暂停 / 断点 / 故障 / 停机 / 静默自暂停都会走到这里） */
            cv_.waitMs(m_, 8);
            continue;
        }
        if (waitingMmio_) {
            /* 卡在设备访问：Java 还没兑现，等它 */
            cv_.waitMs(m_, 2);
            continue;
        }
        /* 断点：有断点时粒度降到 1 条，保证精确命中 */
        if (!breakpoints_.empty() && breakpoints_.count(machine_.pc()) != 0) {
            running_ = false;
            pushLocked(SB_MSG_BREAKPOINT, machine_.pc(), 0, 0);
            continue;
        }

        /* ---------- 自走时钟：每秒配额，虚拟机自己算（2026-09-28 定案）----------
         * 主线程只在装配时把"每秒推进多少周期"配置进来（setClockHz）；之后由虚拟机自己：
         *   ① 每秒清算一次配额（跨秒整块重置）；
         *   ② 秒内**按经过时间摊开**执行（本秒应得 = 已过时间 × 标称），跑超前就睡到配额追上来。
         * 为什么秒内要摊开而不是一次跑满：跑满再睡会让低主频机器"睡掉大半个秒"
         * （1 MHz 的机器睡 0.98 秒），消息/按键要等到下一秒 —— 机器必须始终随叫随到。
         * 不跨秒欠账、不追补：宿主卡顿 ⇒ 这一秒就是少跑，不会攒债之后爆跑。 */
        const int64_t now = nowNs();
        if (now - secondStartNs_ >= 1000000000LL) {
            secondStartNs_ = now;          /* 跨秒：配额整块重置（不欠账、不追补） */
            cyclesThisSecond_ = 0;
        }
        const double elapsedS = (double)(now - secondStartNs_) * 1e-9;
        const int64_t allowed = (int64_t)(elapsedS * (double)clockHz_);
        int64_t due = allowed - (int64_t)cyclesThisSecond_;
        if (due < 1) {
            cv_.waitMs(m_, 1);             /* 已跑超前（或刚好跑满）：睡一下，等配额追上来 */
            continue;
        }
        const int64_t limit = breakpoints_.empty() ? (int64_t)quantum_ : 1;
        if (due > limit) {
            due = limit;
        }

        const int ran = runSliceLocked((int)due);
        if (ran > 0) {
            cyclesThisSecond_ += (uint64_t)ran;
        } else if (running_ && !waitingMmio_) {
            /* 一条都没跑动（例如 WFI 停机等中断）：别在这儿空转 */
            cv_.waitMs(m_, 1);
        }
        /* ★ 指令边界让出（自驱动模式的必需项，不是优化）：
         *   自驱动满载时 due 恒 ≥ 1，循环**永远不会**走到上面那些 waitMs 分支，
         *   若这里不放锁，m_ 就被 run 线程整场独占 —— Java 侧的 heartbeat()、
         *   命令投递（postMemoryWrite）、memoryPointer 全部永久阻塞。
         *   而 heartbeat 是在 OC 的 machine 线程**持有 Machine 监视器**时被调用的：
         *   ⇒ 世界保存（Machine.saveData）等锁 ⇒ 服务端 tick 停摆 ⇒ 全服卡死。
         *   （2026-09-17 真机死锁；线程转储：Server thread BLOCKED 于 saveData，
         *     OpenComputers-Computer-3 持有 Machine 锁且停在 NativeSandbox.heartbeat。） */
        yieldLocked();
    }

    running_ = false;
    pushLocked(SB_MSG_STOPPED, 0, 0, 0);
    m_.unlock();
}

/* 让出间隔：解释循环最多持锁这么久，随后必须放开一次。
 * 取 200µs —— 宿主心跳是每 tick（50ms）一次、组件调用每帧一次，
 * 200µs 的等待对它们完全无感；而按时间而不是按指令让出，能把
 * EnterCriticalSection/LeaveCriticalSection 的成对开销摊到可以忽略。 */
namespace {
constexpr int64_t kSandboxYieldNs = 200'000;
}  /* namespace */

void Sandbox::yieldLocked()
{
    const int64_t now = nowNs();
    if (now - lastYieldNs_ < kSandboxYieldNs) {
        return;
    }
    lastYieldNs_ = now;
    m_.unlock();
#ifdef _WIN32
    ::SwitchToThread();   /* 让出剩余时间片，等锁的线程立刻有机会进来 */
#else
    std::this_thread::yield();
#endif
    m_.lock();
}

void Sandbox::clint(ClintState* out)
{
    if (!out) {
        return;
    }
    SbGuard lk(m_);
    out->mtime = machine_.mtime();
    out->mtimecmp = machine_.mtimecmp();
    out->enabled = machine_.timerEnabled() ? 1 : 0;
    out->pending = machine_.timerPending() ? 1 : 0;
    out->irqCount = machine_.timerIrqCount();
}

int Sandbox::runSliceLocked(int cycles)
{
    if (cycles < 1) {
        return 0;
    }
    machine_.setPendingInterrupts(irq_);
    const int ran = machine_.step(cycles);
    if (ran > 0) {
        instret_ += (uint64_t)ran;
    }

    if (machine_.hasMmio()) {
        const MmioTxn& t = machine_.mmio();
        waitingMmio_ = true;
        pushLocked(SB_MSG_MMIO, t.addr, t.value,
                   (uint32_t)(t.size & 0xFFu) | (t.isWrite != 0 ? 0x100u : 0u));
        return ran;
    }
    /* 停机优先判别：ECALL from M + haltWithoutHandler 是"程序正常结束"，
     * 不是硬件故障 —— 内核把它记成 cause=11 的停机，沙箱层要翻成 HALTED 消息。 */
    if (machine_.halted()) {
        running_ = false;
        const uint32_t cause = machine_.faultCause();
        if (machine_.faulted() && cause != CAUSE_ECALL_FROM_M) {
            pushLocked(SB_MSG_FAULT, cause, machine_.faultTval(), machine_.faultEpc());
        } else {
            pushLocked(SB_MSG_HALTED, machine_.pc(), 0, 0);
        }
        return ran;
    }
    if (machine_.faulted()) {
        running_ = false;
        pushLocked(SB_MSG_FAULT, machine_.faultCause(), machine_.faultTval(), machine_.faultEpc());
        return ran;
    }
    return ran;
}

void Sandbox::drainLocked()
{
    while (!cmds_.empty()) {
        Command c = std::move(cmds_.front());
        cmds_.pop_front();
        switch (c.type) {
        case CMD_LOAD:
            machine_.loadImage(c.a, c.data.data(), (uint32_t)c.data.size());
            pushLocked(SB_MSG_LOADED, c.a, (uint32_t)c.data.size(), 0);
            break;
        case CMD_RESET:
            machine_.reset();
            machine_.setPendingInterrupts(irq_);
            waitingMmio_ = false;
            running_ = false;
            instret_ = 0;
            secondStartNs_ = nowNs();
            cyclesThisSecond_ = 0;
            pushLocked(SB_MSG_RESET, machine_.pc(), 0, 0);
            break;
        case CMD_RUN:
            if (!machine_.faulted() && !machine_.halted()) {
                if (!running_) {
                    /* 从暂停恢复：本秒配额重新起算，不补暂停期间的周期 */
                    secondStartNs_ = nowNs();
                    cyclesThisSecond_ = 0;
                }
                idlePaused_ = false;
                running_ = true;
            }
            break;
        case CMD_PAUSE:
            if (running_) {
                running_ = false;
                pushLocked(SB_MSG_PAUSED, machine_.pc(), 0, 0);
            }
            break;
        case CMD_STEP: {
            running_ = false;
            int n = (int)c.a;
            if (n < 1) {
                n = 1;
            }
            if (n > 1000000) {
                n = 1000000;
            }
            runSliceLocked(n);
            pushLocked(SB_MSG_STEPPED, machine_.pc(), (uint32_t)n, 0);
            break;
        }
        case CMD_BP_SET:
            breakpoints_.insert(c.a);
            break;
        case CMD_BP_CLR:
            breakpoints_.erase(c.a);
            break;
        case CMD_BP_CLRALL:
            breakpoints_.clear();
            break;
        case CMD_WREG:
            machine_.setReg((int)c.a, c.b);
            break;
        case CMD_WMEM:
            machine_.writeMemory(c.a, c.data.data(), (uint32_t)c.data.size());
            break;
        case CMD_IRQ:
            irq_ = c.a;
            break;
        case CMD_MMIO:
            if (waitingMmio_) {
                machine_.completeMmio(c.a);
                waitingMmio_ = false;
                /* 设备往返耗时不计入配额：本秒重新起算（虚拟机的时间只由它自己执行的周期决定） */
                secondStartNs_ = nowNs();
                cyclesThisSecond_ = 0;
            }
            break;
        default:
            break;
        }
    }
}

void Sandbox::pushLocked(int32_t type, uint32_t a, uint32_t b, uint32_t c)
{
    /* 消息队列有上限：Java 长时间不轮询时丢弃最旧的，避免内存无界增长 */
    if (msgs_.size() >= 4096) {
        msgs_.pop_front();
    }
    Message m;
    m.type = type;
    m.a = a;
    m.b = b;
    m.c = c;
    msgs_.push_back(m);
    /* 唤醒阻塞在 waitMessage() 上的 Java 消息泵线程 —— 消息驱动的关键：
     * 没有这一句，等待者只能靠超时醒来，又退化成轮询。 */
    cv_.notifyAll();
}

/* ==================== 沙箱 → Java ==================== */

int Sandbox::poll(Message* out)
{
    SbGuard lk(m_);
    if (msgs_.empty()) {
        return 0;
    }
    if (out != nullptr) {
        *out = msgs_.front();
    }
    msgs_.pop_front();
    return 1;
}

int Sandbox::waitMessage(Message* out, int timeoutMs)
{
    SbGuard lk(m_);
    if (msgs_.empty() && timeoutMs > 0) {
        const int64_t deadline = nowNs() + (int64_t)timeoutMs * 1000000LL;
        /* 分片等待（<=50ms）：stop() / 状态变更都能及时把等待者叫醒，
         * 同时保证 stop 之后 run() 退出、Java 关闭流程不必等满整个 timeout。 */
        while (msgs_.empty() && !stop_) {
            const int64_t remain = deadline - nowNs();
            if (remain <= 0) {
                break;
            }
            unsigned long slice = (unsigned long)((remain + 999999LL) / 1000000LL);
            if (slice < 1) {
                slice = 1;
            }
            if (slice > 50) {
                slice = 50;
            }
            cv_.waitMs(m_, slice);
        }
    }
    if (msgs_.empty()) {
        /* -1 让 Java 侧区分"暂时没有消息"和"沙箱已经收了" */
        return stop_ ? -1 : 0;
    }
    if (out != nullptr) {
        *out = msgs_.front();
    }
    msgs_.pop_front();
    return 1;
}

void Sandbox::heartbeat(uint32_t irqBits, int quantum, Heartbeat* out)
{
    SbGuard lk(m_);
    /* 心跳本身就是一条宿主消息：刷新静默计时（否则空闲但心跳照发的机器会被看门狗误暂停）。 */
    noteHostMessageLocked();
    /* ① 下发宿主侧设备状态：等价于 CMD_IRQ / CMD_QUANTUM，但**不排队** —— 心跳要的是
     *    "立刻生效"，排队会让中断晚一个 drain 周期才进机器。 */
    irq_ = irqBits;
    if (quantum > 0) {
        quantum_ = quantum > 65536 ? 65536 : quantum;
    }
    cv_.notifyAll();

    if (out == nullptr) {
        return;
    }
    /* ② 取回沙箱状态（不含 32 个寄存器 —— 那属于 snapshot() 的诊断用途） */
    out->pc = machine_.pc();
    out->instret = instret_;
    out->running = running_ ? 1 : 0;
    out->waitingMmio = waitingMmio_ ? 1 : 0;
    out->faulted = machine_.faulted() ? 1 : 0;
    out->halted = machine_.halted() ? 1 : 0;
    out->pendingMessages = (int32_t)msgs_.size();
    out->faultCause = machine_.faultCause();
    out->faultTval = machine_.faultTval();
    out->faultEpc = machine_.faultEpc();
    out->idlePaused = idlePaused_ ? 1 : 0;
    out->cyclesThisSecond = cyclesThisSecond_;
}

uint8_t* Sandbox::memoryPointer(uint32_t addr, uint32_t len)
{
    SbGuard lk(m_);
    return machine_.memoryPointer(addr, len);
}

void Sandbox::snapshot(Snapshot* out)
{
    if (out == nullptr) {
        return;
    }
    SbGuard lk(m_);
    out->pc = machine_.pc();
    for (int i = 0; i < 32; i++) {
        out->regs[i] = machine_.reg(i);
    }
    out->instret = instret_;
    out->clockHz = clockHz_;
    out->running = running_ ? 1 : 0;
    out->waitingMmio = waitingMmio_ ? 1 : 0;
    out->faulted = machine_.faulted() ? 1 : 0;
    out->halted = machine_.halted() ? 1 : 0;
    out->breakpointCount = (int32_t)breakpoints_.size();
    out->faultCause = machine_.faultCause();
    out->faultTval = machine_.faultTval();
    out->faultEpc = machine_.faultEpc();
    out->pendingMessages = (int32_t)msgs_.size();
    out->idlePaused = idlePaused_ ? 1 : 0;
    out->cyclesThisSecond = cyclesThisSecond_;
}

uint64_t Sandbox::instructionsRetired()
{
    SbGuard lk(m_);
    return instret_;
}

int Sandbox::readMemory(uint32_t addr, uint8_t* out, uint32_t len)
{
    if (out == nullptr || len == 0) {
        return 0;
    }
    SbGuard lk(m_);
    machine_.readMemory(addr, out, len);
    return (int)len;
}

} /* namespace cryptand */
