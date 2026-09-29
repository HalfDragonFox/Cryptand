/*
 * ============================================================================
 * Cryptand 沙箱 —— 自驱动虚拟机（sandbox.h，2026-09-17）
 *
 * 用户 2026-09-17 定稿的架构：
 *
 *   Java ──► 沙箱 ──► 程序
 *
 *   · 沙箱 = 完整虚拟机环境（CPU + 内存 + 时钟 + 设备总线），Java 只与沙箱对接
 *   · **配置一次**（1 秒执行多少周期）后，沙箱按内部时钟**自动运行**，
 *     Java 不需要每 tick 同步交互 —— 双方通过**消息队列**通讯
 *   · 沙箱**不自建线程**：Java 把它分配到自己的线程里（调 run()），
 *     线程分配走 Cryptand 的 ThreadDispatchers，不占用原版线程池
 *   · **一周期一条指令**：死循环也只会吃掉"这个沙箱自己的周期预算"，
 *     不会拖垮主线程；随时可暂停 / 单步 / 设断点 / 读寄存器内存
 *
 * 与同步 step() 的关系：Machine 的同步接口**完全保留**（OC 机器仍在用），
 * 沙箱是叠加在其上的"自驱动外壳"，不改变原语义。
 * ============================================================================
 */
#ifndef CRYPTAND_SANDBOX_H
#define CRYPTAND_SANDBOX_H

#include <chrono>
#include <cstdint>
#include <deque>
#include <unordered_set>
#include <vector>

#include "rv32.h"

/*
 * ---------- 平台同步原语（重要） ----------
 *
 * 为什么不直接用 std::mutex / std::condition_variable：
 *   MinGW 的这两样都住在 libwinpthread-1.dll 里，而那个 DLL 不在 MC 进程的
 *   搜索路径里 ⇒ System.load 直接失败（实测 UnsatisfiedLinkError: 找不到指定的程序，
 *   上层静默回落纯 Java 内核）。静态链 libwinpthread 又会把 CRT 启动对象
 *   （crtexewin.o → WinMain）拖进来而链接失败。
 *
 * 所以 Windows 上直接用 KERNEL32 的原生原语（CRITICAL_SECTION / CONDITION_VARIABLE，
 * 零额外依赖、也不需要任何 DLL）；其它平台仍走标准库。
 */
#ifdef _WIN32
#  ifndef WIN32_LEAN_AND_MEAN
#    define WIN32_LEAN_AND_MEAN
#  endif
#  include <windows.h>
#else
#  include <condition_variable>
#  include <mutex>
#endif

namespace cryptand {

/* ---------- 互斥量 ---------- */
#ifdef _WIN32
struct SbLock {
    CRITICAL_SECTION cs;

    SbLock() {
        InitializeCriticalSection(&cs);
    }

    ~SbLock() {
        DeleteCriticalSection(&cs);
    }

    SbLock(const SbLock&) = delete;
    SbLock& operator=(const SbLock&) = delete;

    void lock() {
        EnterCriticalSection(&cs);
    }

    void unlock() {
        LeaveCriticalSection(&cs);
    }
};
#else
struct SbLock {
    std::mutex m;

    void lock() {
        m.lock();
    }

    void unlock() {
        m.unlock();
    }
};
#endif

/* ---------- 等待 / 唤醒 ---------- */
#ifdef _WIN32
struct SbWait {
    CONDITION_VARIABLE cv;

    SbWait() {
        InitializeConditionVariable(&cv);
    }

    void notifyAll() {
        WakeAllConditionVariable(&cv);
    }

    /** 等待最多 ms 毫秒（等待期间释放 m）。 */
    void waitMs(SbLock& m, unsigned long ms) {
        SleepConditionVariableCS(&cv, &m.cs, (DWORD)ms);
    }
};
#else
struct SbWait {
    std::condition_variable cv;

    void notifyAll() {
        cv.notify_all();
    }

    void waitMs(SbLock& m, unsigned long ms) {
        std::unique_lock<std::mutex> lk(m.m, std::adopt_lock);
        cv.wait_for(lk, std::chrono::milliseconds(ms));
        lk.release();
    }
};
#endif

/* ---------- RAII 加锁 ---------- */
struct SbGuard {
    SbLock& m;

    explicit SbGuard(SbLock& target) : m(target) {
        m.lock();
    }

    ~SbGuard() {
        m.unlock();
    }

    SbGuard(const SbGuard&) = delete;
    SbGuard& operator=(const SbGuard&) = delete;
};

/* ---------- 微秒级睡眠（Windows 定时器精度 ~1ms，不足按 1ms 处理） ---------- */
void sbSleepMicros(unsigned long us);

/* ---------- 沙箱 → Java 的消息 ---------- */
enum SandboxMsg : int32_t {
    SB_MSG_NONE = 0,
    SB_MSG_RESET = 1,       /* 已复位            a = 入口地址       */
    SB_MSG_LOADED = 2,      /* 镜像已灌入        a = 地址, b = 长度 */
    SB_MSG_BREAKPOINT = 3,  /* 命中断点          a = PC            */
    SB_MSG_FAULT = 4,       /* 硬件故障          a = cause, b = tval, c = epc */
    SB_MSG_HALTED = 5,      /* 停机              a = PC            */
    SB_MSG_MMIO = 6,        /* 需要设备兑现      a = addr, b = value, c = size | (isWrite<<8) */
    SB_MSG_PAUSED = 7,      /* 已暂停            a = PC            */
    SB_MSG_STEPPED = 8,     /* 单步完成          a = PC, b = 本次执行条数 */
    SB_MSG_STOPPED = 9,     /* run() 已退出      --                */
    /* 宿主静默超时，虚拟机自己暂停：a = 静默阈值 ms（再来一条宿主消息即自动恢复） */
    SB_MSG_IDLE_PAUSED = 10,
};

class Sandbox {
public:
    struct Message {
        int32_t type;
        uint32_t a, b, c;
    };

    /** Java 侧轮询到的状态快照（一次把要看的都取走，避免多次加锁） */
    struct Snapshot {
        uint32_t pc;
        uint32_t regs[32];
        uint64_t instret;      /* 累计执行指令数（= 周期数，一周期一指令） */
        uint64_t clockHz;      /* 当前频率（周期/秒） */
        int32_t running;       /* 正在跑 */
        int32_t waitingMmio;   /* 卡在设备访问上，等 Java 兑现 */
        int32_t faulted;
        int32_t halted;
        int32_t breakpointCount;
        uint32_t faultCause, faultTval, faultEpc;
        int32_t pendingMessages;
        int32_t idlePaused;         /* 因宿主静默而自暂停 */
        uint64_t cyclesThisSecond;  /* 本秒已执行周期（配额见 clockHz） */
    };

    explicit Sandbox(const Config& cfg);
    ~Sandbox();

    Sandbox(const Sandbox&) = delete;
    Sandbox& operator=(const Sandbox&) = delete;

    /* ==================== 配置（设置一次即可） ==================== */

    /** 主频：每秒执行多少条指令（= 周期）。10MHz ⇒ 传 10'000'000。 */
    void setClockHz(uint64_t hz);
    uint64_t clockHz();

    /** 每次唤醒最多连续执行多少条（越小越"实时"，越大越省调度）。默认 512。 */
    void setQuantum(int cycles);

    /**
     * 宿主静默看门狗：连续多久**没收到宿主的任何消息**就让虚拟机自己暂停。
     *
     * 虚拟机是独立机器（不挂在主线程上）：主线程一旦消失（世界卸载 / 服务端停 / 崩溃），
     * 没人会来通知它，它就会一直占着 CPU。这个阈值让它在"没人理它"时自己停下；
     * 之后任何一条宿主消息（心跳 / 命令 / 设备兑现）都会让它自动恢复运行。
     *
     * @param ms 静默阈值毫秒；0 = 关闭看门狗（永不自动暂停）。默认 10000（10 秒，可配置）
     */
    void setIdlePauseMs(uint32_t ms);
    uint32_t idlePauseMs();

    /* ==================== Java → 沙箱 命令（线程安全） ==================== */

    void cmdLoadImage(uint32_t addr, const uint8_t* data, uint32_t len);
    void cmdReset();
    void cmdRun();                       /* 开始 / 从暂停处继续 */
    void cmdPause();
    void cmdStep(int cycles);            /* 单步 N 条后自动暂停 */
    void cmdSetBreakpoint(uint32_t addr);
    void cmdClearBreakpoint(uint32_t addr);
    void cmdClearBreakpoints();
    void cmdWriteReg(int index, uint32_t value);
    void cmdWriteMem(uint32_t addr, const uint8_t* data, uint32_t len);
    void cmdSetInterrupts(uint32_t bits); /* 宿主推进设备中断位图（MTIP 等） */
    void cmdMmioResult(uint32_t value);   /* Java 在设备上兑现后交回（写事务传 0） */

    /* ==================== 运行（Java 在自己分配的线程里调） ==================== */

    /** 阻塞运行：内部时钟自动推进，直到 stop()。 */
    void run();

    /** 请求 run() 退出（可从任意线程调）。 */
    void stop();

    /* ==================== 沙箱 → Java ==================== */

    /** 取一条消息；返回 1 = 有消息，0 = 队列空。 */
    int poll(Message* out);

    /**
     * 阻塞等待一条消息（Java 侧消息泵用）。
     *
     * 为什么需要它：poll() 是"问一次答一次"，Java 侧只能靠定时轮询占位
     * （1ms 一次 ⇒ 每秒 1000 次 JNI 调用，其中绝大多数返回"队列空"）。
     * 本方法内部用条件变量阻塞，消息入队时才唤醒 ⇒ JNI 调用次数与**事件数**同阶。
     *
     * @param timeoutMs 最长等待毫秒数（<=0 表示只查一次，等价于 poll）
     * @return 1 = 取到消息，0 = 超时，-1 = 沙箱已 stop（run() 已退出，调用方应收摊）
     */
    int waitMessage(Message* out, int timeoutMs);

    /** Java 每 tick 心跳一次取回的状态（一次调用取全，避免多次小 JNI 拼接状态） */
    struct Heartbeat {
        uint32_t pc;
        uint64_t instret;       /* 累计执行周期（= 指令数），宿主据此算实测 MHz */
        int32_t running;
        int32_t waitingMmio;
        int32_t faulted;
        int32_t halted;
        int32_t pendingMessages;
        uint32_t faultCause;
        uint32_t faultTval;
        uint32_t faultEpc;
        int32_t idlePaused;          /* 因宿主心跳断了而自暂停 */
        uint64_t cyclesThisSecond;   /* 本秒已执行周期（虚拟机自走配额） */
    };

    /**
     * 心跳：**一次调用**完成"下发宿主侧设备状态 + 取回沙箱状态"。
     *
     * 为什么合并：心跳正是"配置一次就自跑"模型下 Java 唯一的周期动作，
     * 若拆成 setInterrupts()/snapshot()/faulted() 三四次 JNI，每 tick 就是三四倍开销；
     * 合起来后每 tick 只有 1 次 JNI（且不含 44 个寄存器的快照拷贝）。
     *
     * @param irqBits   设备待处理中断位图（RISC-V 标准位：7 = MTIP）
     * @param quantum   连跑粒度；<=0 表示不改（配置一次即可）
     * @param out       非空时写入状态
     */
    void heartbeat(uint32_t irqBits, int quantum, Heartbeat* out);

    /**
     * 内存直通（零拷贝）：返回 guest 物理地址 addr 起 len 字节的**主机指针**。
     *
     * ⚠ 语义与 readMemory 不同：这是**共享**内存，不是快照 —— 返回后沙箱可能仍在写它。
     * 只适用于"读一块自己会立刻消费掉的数据"，且调用方必须保证在此期间沙箱不被销毁
     * （destroy 会 free 掉这块内存）。不属于 ROM/RAM 或越界的返回 nullptr。
     */
    uint8_t* memoryPointer(uint32_t addr, uint32_t len);

    /** 读状态快照（含寄存器组，调试用）。 */
    void snapshot(Snapshot* out);

    /**
     * CLINT（虚拟机自建定时器）状态 —— **宿主只读**，诊断/分析器用。
     *
     * 为什么单列：定时器不再是宿主设备，宿主不许写它（2026-09-28 定案：周期管理全由虚拟机完成），
     * 只允许"看"。真要改时间只能走 guest 程序自己写 mtimecmp。
     */
    struct ClintState {
        uint64_t mtime;      /* 虚拟机内部时间（随执行周期推进） */
        uint64_t mtimecmp;   /* 下一次到期的比较值 */
        int32_t  enabled;    /* 是否已启用（写过 mtimecmp） */
        int32_t  pending;     /* MTIP 电平：mtime >= mtimecmp */
        uint64_t irqCount;   /* MTIP 由 0 变 1 的次数（= 已产生的 tick 数） */
    };

    /** 读 CLINT 状态（只读快照，加锁）。 */
    void clint(ClintState* out);

    /** 累计执行周期（= 指令数）；轻量加锁读，供消息泵随消息带回时间基准。 */
    uint64_t instructionsRetired();

    /** 读内存（诊断；ROM/RAM 均可）。返回实际拷贝字节数。 */
    int readMemory(uint32_t addr, uint8_t* out, uint32_t len);

private:
    /* ---------- 内部命令 ---------- */
    enum CmdType : int32_t {
        CMD_LOAD = 1, CMD_RESET, CMD_RUN, CMD_PAUSE, CMD_STEP,
        CMD_BP_SET, CMD_BP_CLR, CMD_BP_CLRALL, CMD_WREG, CMD_WMEM,
        CMD_IRQ, CMD_MMIO, CMD_CLOCK, CMD_QUANTUM,
    };

    struct Command {
        int32_t type = 0;
        uint32_t a = 0, b = 0;
        std::vector<uint8_t> data;
    };

    void drainLocked();
    int  runSliceLocked(int cycles);      /* @return 本片实际执行的指令数 */
    /** 记一次"宿主说话了"：刷新静默计时；若之前因静默自暂停则自动恢复。 */
    void noteHostMessageLocked();
    /** 宿主静默看门狗（内部按阈值 1/4 降频检查，不影响解释循环）。 */
    void checkHostWatchdogLocked();
    /* 解释循环的"让出点"：解锁 → 让出处理器 → 重新加锁。
     * 自驱动满载时 run() 不会进入任何 waitMs 分支，没有它 m_ 会被整场独占。 */
    void yieldLocked();
    void pushLocked(int32_t type, uint32_t a, uint32_t b, uint32_t c);
    void postLocked(const Command& cmd);
    static int64_t nowNs();

    Machine machine_;

    mutable SbLock m_;
    SbWait cv_;

    std::deque<Command> cmds_;
    std::deque<Message> msgs_;
    std::unordered_set<uint32_t> breakpoints_;

    uint64_t clockHz_ = 1'000'000;   /* 默认 1MHz */
    int quantum_ = 512;
    uint32_t irq_ = 0;

    bool stop_ = false;
    bool running_ = false;
    bool waitingMmio_ = false;
    /* 因宿主静默而自动暂停（区别于用户/程序主动 pause）：任何宿主消息都会清掉它 */
    bool idlePaused_ = false;

    /* ---------- 自走时钟：每秒配额，虚拟机自己算（不跨秒欠账、不追补） ---------- */
    int64_t secondStartNs_ = 0;      /* 本秒起点（宿主墙钟只用来切秒） */
    uint64_t cyclesThisSecond_ = 0;  /* 本秒已执行周期数 */
    /* ---------- 宿主静默看门狗 ---------- */
    uint32_t idlePauseMs_ = 10'000;  /* 默认 10 秒（用户定案：可配置） */
    int64_t lastHostMsgNs_ = 0;      /* 最近一次收到宿主消息的时刻 */
    int64_t lastIdleCheckNs_ = 0;    /* 看门狗上一次检查的时刻（降频用，省 nowNs()） */

    /* 上一次让出锁的时刻（ns）—— 让出粒度按时间而非按指令：无论解释循环跑多快，
     * Java 侧的心跳 / 命令投递等待时间都不会超过一个让出间隔。 */
    int64_t lastYieldNs_ = 0;
    uint64_t instret_ = 0;
};

} /* namespace cryptand */

#endif /* CRYPTAND_SANDBOX_H */
