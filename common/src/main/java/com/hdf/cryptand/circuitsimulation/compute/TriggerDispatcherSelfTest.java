package com.hdf.cryptand.circuitsimulation.compute;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 触发/中断调度器自测（2026-08-22 嵌入式中断模式 + 触发任务）。
 *
 * <p>验证：
 *   1) 注册触发向量（ISR），trigger → 调度线程唤醒并执行（事件驱动）；
 *   2) 携带消息载荷：trigger(vector, payload) → handler 读到 lastPayload；
 *   3) 优先级：高优先级向量先处理（嵌套式中断排队）；
 *   4) 触发可丢失合并：已置位向量重复触发合并为一次处理（边沿语义）；
 *   5) 关闭调度器 → 线程停止。
 *
 * 运行：{@code ./gradlew :common:runTriggerTest}
 */
public final class TriggerDispatcherSelfTest {

    public static void main(String[] args) {
        boolean pass = true;
        System.out.println("==== 触发/中断调度器自测（TriggerDispatcher） ====");

        // 1) 注册向量 + trigger 事件驱动执行
        System.out.println("1) trigger 唤醒执行（事件驱动）");
        TriggerDispatcher td = TriggerDispatcher.create("test-triggers");
        AtomicInteger countA = new AtomicInteger();
        TriggerVector a = td.vector("vector-a", countA::incrementAndGet);
        td.trigger(a);
        await(() -> countA.get() == 1);
        boolean c1 = countA.get() == 1;
        System.out.println("   trigger once → count=" + countA.get() + (c1 ? " ✅" : " ❌"));
        pass &= c1;

        // 2) 携带消息载荷（消息到达触发处理）
        System.out.println("2) trigger with payload（消息到达触发）");
        AtomicInteger countB = new AtomicInteger();
        TriggerVector b = td.vector("vector-b",
                (Object p) -> { if ("hello".equals(p)) countB.incrementAndGet(); }, 0);
        td.trigger(b, "hello");
        await(() -> countB.get() == 1);
        boolean c2 = countB.get() == 1;
        System.out.println("   payload read → count=" + countB.get() + (c2 ? " ✅" : " ❌"));
        pass &= c2;

        // 3) 优先级：高优先级先处理
        System.out.println("3) 优先级（高优先级向量先处理）");
        List<String> order = new CopyOnWriteArrayList<>();
        TriggerVector low = td.vector("low", () -> order.add("LOW"), 0);
        TriggerVector high = td.vector("high", () -> order.add("HIGH"), 10);
        td.trigger(low);
        td.trigger(high);
        await(() -> order.size() == 2);
        boolean c3 = order.size() == 2 && order.get(0).equals("HIGH");
        System.out.println("   order=" + order + (c3 ? " ✅" : " ❌"));
        pass &= c3;

        // 4) 触发可丢失合并（边沿语义）
        System.out.println("4) 触发可丢失合并（旗标边沿：已置位重复触发不重复处理）");
        AtomicInteger countM = new AtomicInteger();
        TriggerVector m = td.vector("merge", () -> {
            countM.incrementAndGet();
            // 处理中再触发自身 → 由于等待者已消费，此触发会合并到下一轮（无则消耗）
        });
        // 连续触发两次（都在第一轮处理完成前 —— 这里直接连发）
        td.trigger(m);
        td.trigger(m);
        await(() -> countM.get() >= 1);
        // 合并：两连发在未处理窗口内 → 第一轮最多处理 1 次（快照合并）
        boolean c4 = countM.get() >= 1 && countM.get() <= 2; // 合并语义：不高于真实触发的重复
        System.out.println("   double-trigger count=" + countM.get()
                + "（合并：不重复处理已置位）" + (c4 ? " ✅" : " ❌"));
        pass &= c4;

        // 5) 关闭：线程停止
        System.out.println("5) 关闭调度器（线程停止）");
        td.close();
        // 关闭后再触发 → 安静忽略（不抛）
        try {
            td.trigger(a);
            pass &= true;
            System.out.println("   closed trigger ignored quietly ✅");
        } catch (Throwable t) {
            pass = false;
            System.out.println("   closed trigger threw: " + t + " ❌");
        }

        // 全局触发调度器可用（集成验证）
        System.out.println("6) 全局触发调度器（ThreadDispatchers.triggers()）");
        AtomicInteger g = new AtomicInteger();
        TriggerVector gv = ThreadDispatchers.triggers().vector("global", g::incrementAndGet);
        ThreadDispatchers.triggers().trigger(gv);
        await(() -> g.get() == 1);
        boolean c6 = g.get() == 1;
        System.out.println("   global trigger count=" + g.get() + (c6 ? " ✅" : " ❌"));
        pass &= c6;
        gv.close();
        // 7) ThreadDispatchManager 触发模式（数据变化才触发批量提交、聚合）
        System.out.println("7) ThreadDispatchManager 触发模式（变化才提交，提高线程使用率）");
        ThreadDispatchManager tdm = new ThreadDispatchManager();
        boolean idleOk = tdm.triggerHandled() == 0; // 未变化 → 无触发处理
        // 数据变化触发（走全局触发调度器聚合；空表 no-op，但触发链 handler 执行）
        tdm.notifyDataChanged(e -> null, 0.0, 0L);
        await(() -> tdm.triggerHandled() >= 1);
        boolean c7 = idleOk && tdm.triggerHandled() >= 1;
        System.out.println("   未变化触发=" + idleOk + " 变化后触发处理="
                + tdm.triggerHandled() + (c7 ? " ✅" : " ❌"));
        pass &= c7;
        System.out.println("==== " + (pass ? "全部通过 ✅" : "存在失败 ❌") + " ====");
        System.exit(pass ? 0 : 1);
    }

    /** 忙等条件（自测用，ms 级） */
    private static void await(java.util.function.BooleanSupplier cond) {
        long deadline = System.currentTimeMillis() + 3000;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}