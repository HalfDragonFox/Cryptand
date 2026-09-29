package com.hdf.cryptand.neoforge.dynamic;

import com.hdf.cryptand.circuitsimulation.compute.ThreadDispatchers;
import com.hdf.cryptand.dynamic.api.DynamicScheduler;
import com.hdf.cryptand.dynamic.api.Stage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * ===== MC 侧调度适配（接项目多线程分配核心，2026-09-29）=====
 *
 * <p>用户定案：「实现为多线程后台实现，接入我们的多线程分配核心，支持多线程并发；
 * 怎么调用需要具体核心实现，基础核心只提供框架和接口」。</p>
 *
 * <ul>
 *   <li>`background` → {@link ThreadDispatchers#submitGeneric(Runnable)}，即
 *       {@code TaskMode.NORMAL}（<b>虚拟线程</b>；用户定案"使用虚拟线程最好"）。
 *       <b>绝不使用 EXCLUSIVE</b> —— TaskMode.java:24-25 明示"不建议，易卡死"。</li>
 *   <li>`mainThread` → 可注入的主线程派发（客户端 {@code mc.execute} / 服务端 {@code server.execute}）。</li>
 *   <li>取消语义：项目 {@code submitGenericTimed} 超时后任务仍在跑 ⇒ 一律靠 {@link #epoch()} 丢弃过期结果。</li>
 * </ul>
 *
 * <p><b>低侵入约束（2026-09-29 用户要求"对原版尽可能少的影响，防 mod 冲突"）</b>：本类
 * <b>不引用任何 {@code net.minecraft.*} 类型</b>（包括客户端类）—— 主线程派发由外部注入
 * （见 {@link ClientMainExecutor}）。这样服务端路径加载本类不会因缺客户端类而崩，
 * 也保证"关掉框架 ⇒ 零痕迹"。</p>
 */
public final class CryptandDynamicScheduler implements DynamicScheduler {

    private final AtomicLong epoch = new AtomicLong();
    private volatile Consumer<Runnable> mainExecutor = Runnable::run;

    /** 注入主线程派发（客户端 setup 后传 {@code mc::execute}；服务端 setup 传 {@code server::execute}）。 */
    public void setMainExecutor(Consumer<Runnable> executor) {
        if (executor != null) {
            this.mainExecutor = executor;
        }
    }

    @Override
    public <T> CompletableFuture<T> background(Stage stage, Supplier<T> task) {
        final CompletableFuture<T> out = new CompletableFuture<>();
        ThreadDispatchers.submitGeneric(() -> {
            try {
                out.complete(task.get());
            } catch (Throwable t) {
                out.completeExceptionally(t);
            }
        });
        return out;
    }

    @Override
    public void mainThread(Runnable task) {
        mainExecutor.accept(task);
    }

    @Override
    public long epoch() {
        return epoch.get();
    }

    /** 递增代次（每次 scan/reload 前由框架调用）。 */
    public long nextEpoch() {
        return epoch.incrementAndGet();
    }
}
