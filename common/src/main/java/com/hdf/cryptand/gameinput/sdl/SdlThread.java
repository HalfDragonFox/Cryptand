/**
 * ===== SDL 专用线程（2026-09-14，用户定稿）=====
 *
 * 用户原话："SDL 可以单独一个线程，每次有消息时激活处理" + "向线程分配器申请消息激活的线程
 * 或者固定激活的" + "通过消息交互，防止竞态" + "常驻池用于必须绑定线程的任务…默认为 true…
 * 分配时优先分配任务到空闲的池，只有全部分配完成后再使用常驻池"。
 *
 * <p><b>为什么必须只有一条 OS 线程</b>：SDL 的子系统状态、joystick 句柄、事件队列都是进程级的，
 * 且不是线程安全的。实测事故：`SDL_OpenJoystick` 卡在 native 里永不返回（设备被其它程序独占 /
 * 驱动异常时），旧实现把它同步跑在设备池线程上 ⇒ 整条池线程冻死 ⇒ <b>所有</b>设备都连不上。
 * 把<em>全部</em> SDL 调用收进一条常驻平台线程后：卡住的只是这条线程，池线程照常做超时诊断与重试。
 *
 * <p>这条线程是分配器常驻池里的一条，且设为 <b>独占</b>（`allowOtherTasks = false`）：
 * 它是实时设备线程，被普通任务占用会让设备消息延迟。名额按用户定稿从虚拟线程配额里
 * "永久移出"一个（1000 → 999）。
 */

package com.hdf.cryptand.gameinput.sdl;

import com.hdf.cryptand.circuitsimulation.compute.PinnedWorker;
import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SdlThread {

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile PinnedWorker worker;

    private SdlThread() {
    }

    /**
     * 投递一条"必须在 SDL 线程上执行"的动作（任意线程可调，永不阻塞调用方）。
     * 队列是调用方与 SDL 之间<b>唯一</b>的交互通道 —— 池线程绝不直接碰 SDL 接口。
     */
    public static void post(Runnable message) {
        if (message == null) {
            return;
        }
        ensureStarted();
        PinnedWorker w = worker;
        if (w != null) {
            w.post(message);
        }
    }

    /** 确保 SDL 常驻线程已就绪（幂等；分配器不可用时允许下次重试）。 */
    public static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        try {
            worker = ThreadDispatchers.pinPermanent("Cryptand-SDL", false);
        } catch (Throwable t) {
            STARTED.set(false);
        }
    }

    /** 是否已就绪（诊断） */
    public static boolean isRunning() {
        return worker != null;
    }

    /** 待处理消息数（诊断；SDL 卡住时会持续增长，正是要看见的信号） */
    public static int backlog() {
        PinnedWorker w = worker;
        return w == null ? 0 : w.queuedTasks();
    }
}
