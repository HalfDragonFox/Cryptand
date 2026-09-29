package com.hdf.cryptand.core.frame;

/**
 * 分帧游标：跨帧（对 MC 而言就是跨 tick）记住「扫到哪」。
 *
 * <p>不变量：把一个大扫描切成若干帧执行时，每条数据【恰好处理一次】——不重、不漏。
 * 中断（本帧配额用尽）后保留游标状态，下一帧从断点继续。
 */
public interface FrameCursor {

    /** 本帧是否还有剩余工作。 */
    boolean hasRemaining();

    /** 已经用掉的配额（元素数）。 */
    int used();
}
