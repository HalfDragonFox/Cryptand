package com.hdf.cryptand.dynamic.task;

/**
 * ===== 操作访问模式（common 框架工具，2026-09-29）=====
 *
 * <p>用户要求：「外部只需要定义读写文件或者其他操作，然后设置是否多线程以及**只读只写**等操作，
 * 由后端自动实现」。</p>
 *
 * <p>这个枚举就是"只读只写"的声明口径，框架据此决定能不能并行：
 * **只读天生可并行**（多份互不影响），**写必须独占**（否则两个写者互相踩）。</p>
 *
 * <p>安全优先：读改写（{@link #READ_WRITE}）按"写"对待 —— 拿不准的时候串行总是对的。</p>
 */
public enum AccessMode {

    /** 只读：可与其他只读并行，但不能与任何写并发。 */
    READ_ONLY(true),

    /** 只写：独占（同一资源键上不能有任何其他操作）。 */
    WRITE_ONLY(false),

    /** 读写混合：独占（按写处理，安全优先）。 */
    READ_WRITE(false);

    private final boolean shareable;

    AccessMode(boolean shareable) {
        this.shareable = shareable;
    }

    /** 是否可与其他同模式操作并行（仅只读为 true）。 */
    public boolean shareable() {
        return shareable;
    }
}
