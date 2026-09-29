package com.hdf.cryptand.waterphysics;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * 写回队列：求解结果按 region 排队，逐个写回，一个 region 没写完就不算完成。
 *
 * <p>它承载的语义是「<b>一次请求必须跨多 tick 做完再返回</b>」：
 * <ul>
 *   <li>只要队列非空，就说明上一轮的写意图还没落干净 —— 此时<b>不得开始新的一轮扫描</b>，
 *       否则会捕获到「算了一半」的世界状态，水位会来回倒退；</li>
 *   <li>队首那个 region 的 plan 可以分任意多个 tick 消费（受每 tick 写预算限制），
 *       消费干净了才移出，之后才轮到下一个 region；</li>
 *   <li>写回期间若又收到该 region 的触发，记进 {@code refreshAfterWrite} 语义（由调用方处理），
 *       避免读到半成品。</li>
 * </ul>
 *
 * <p>纯 Java，可离线闸门（见 WaterphysicsSelfTest#testWriteBackQueue）。
 */
public final class WriteBackQueue {

    /** 队首元素：一个 region 的写意图。 */
    public record Entry(long regionKey, FluidWritePlan plan) {
    }

    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final Set<Long> inFlight = new HashSet<>();

    public void enqueue(final long regionKey, final FluidWritePlan plan) {
        queue.addLast(new Entry(regionKey, plan));
        inFlight.add(regionKey);
    }

    /** 队首（正在写回的）元素；队列空返回 null。 */
    public Entry peek() {
        return queue.peekFirst();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }

    public int size() {
        return queue.size();
    }

    /** 该 region 是否有写意图尚未落干净。 */
    public boolean isInFlight(final long regionKey) {
        return inFlight.contains(regionKey);
    }

    /**
     * 队首的 plan 是否已经消费干净；干净则移出并解除 in-flight。
     *
     * @return true 表示本次调用移出了一个已完成的 region
     */
    public boolean completeIfDrained() {
        final Entry head = queue.peekFirst();
        if (head == null) {
            return false;
        }
        if (head.plan().hasPending()) {
            return false;
        }
        queue.pollFirst();
        inFlight.remove(head.regionKey());
        return true;
    }

    public void clear() {
        queue.clear();
        inFlight.clear();
    }
}
