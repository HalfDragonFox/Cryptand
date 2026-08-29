package com.hdf.cryptand.circuitsimulation.compute;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 计算任务 —— 这就是“发送到 C++ 执行”的 Java 执行类。
 * 携带：任务 id、网络快照、最低线程质量等级、优先级、预算/期限、允许的引擎掩码。
 * 调度器依据这些元数据自动分配到 CPU/GPU/集群。
 */
public final class ComputeTask {
    private static final AtomicLong ID_SEQ = new AtomicLong(1);

    public final long id;
    public final NetworkSnapshot snapshot;
    /** 最低线程质量等级：调度器不得分配到低于该等级的引擎 */
    public final ThreadQuality minQuality;
    /** 优先级，越大越优先 */
    public final int priority;
    /** 允许执行的引擎掩码（CPU|GPU|CLUSTER） */
    public final int engineMask;
    /** 期限（纳秒，系统单调时钟），超期结果作废 */
    public final long deadlineNanos;
    /** 预算（纳秒）：超过则中止 */
    public final long budgetNanos;

    public ComputeTask(NetworkSnapshot snapshot, ThreadQuality minQuality, int priority,
                       int engineMask, long deadlineNanos, long budgetNanos) {
        this.id = ID_SEQ.getAndIncrement();
        this.snapshot = snapshot;
        this.minQuality = minQuality;
        this.priority = priority;
        this.engineMask = engineMask;
        this.deadlineNanos = deadlineNanos;
        this.budgetNanos = budgetNanos;
    }

    public boolean expired() {
        return deadlineNanos > 0 && System.nanoTime() > deadlineNanos;
    }

    // ---- 二进制序列化：这就是发送给 C++ 的字节流 ----
    public byte[] toByteArray() {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bos);
            o.writeLong(id);
            o.writeInt(minQuality.level);
            o.writeInt(priority);
            o.writeInt(engineMask);
            o.writeLong(deadlineNanos);
            o.writeLong(budgetNanos);
            byte[] body = snapshot.toByteArray();
            o.writeInt(body.length);
            o.write(body);
            o.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("ComputeTask serialize failed", e);
        }
    }

    @Override
    public String toString() {
        return "ComputeTask{id=" + id + ", " + snapshot + ", minQuality=" + minQuality
                + ", engineMask=" + engineMask + "}";
    }
}
