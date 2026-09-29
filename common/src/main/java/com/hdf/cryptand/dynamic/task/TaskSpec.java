package com.hdf.cryptand.dynamic.task;

import com.hdf.cryptand.dynamic.api.Stage;

import java.util.Objects;

/**
 * ===== 操作声明（common 框架工具，2026-09-29）=====
 *
 * <p>用户要求：「外部只需要定义读写文件或者其他操作，然后**设置是否多线程以及只读只写**等操作，
 * 由**后端自动实现**」。</p>
 *
 * <p>于是外部只交两样东西：一段 {@link Operation}（做什么）+ 一份 {@code TaskSpec}（怎么跑）。
 * 框架读这份声明后自动决定：丢给虚拟线程池并行、排队独占、还是派回主线程。</p>
 *
 * @param name        名字（日志/统计用，别用匿名串 —— 出问题时要能一眼认出来）
 * @param stage       所属阶段（沿用动态框架的 Stage 词汇，便于统一观测）
 * @param access      访问模式（决定能否并行）
 * @param parallel    是否允许并行（{@code false} = 串行执行；只读且 true 才会真并行）
 * @param resourceKey 资源键：**同一键上按访问模式互斥**（可为 null = 不参与资源互斥）
 * @param mainThread  是否必须回主线程执行（UI/世界状态等；优先级最高）
 */
public record TaskSpec(String name, Stage stage, AccessMode access, boolean parallel,
                       String resourceKey, boolean mainThread) {

    public TaskSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(access, "access");
        if (name.isBlank()) {
            throw new IllegalArgumentException("任务名不能为空");
        }
    }

    /** 只读文件/数据：可并行。 */
    public static TaskSpec read(String name, Stage stage, String resourceKey) {
        return new TaskSpec(name, stage, AccessMode.READ_ONLY, true, resourceKey, false);
    }

    /** 写文件/数据：独占（parallel 永远为 false —— 写不能并行，别给调用方这个机会）。 */
    public static TaskSpec write(String name, Stage stage, String resourceKey) {
        return new TaskSpec(name, stage, AccessMode.WRITE_ONLY, false, resourceKey, false);
    }

    /** 必须回主线程（UI/世界状态）：其它字段仅作记录。 */
    public static TaskSpec onMainThread(String name, Stage stage) {
        return new TaskSpec(name, stage, AccessMode.WRITE_ONLY, false, null, true);
    }

    /** 有效的并行判定：声明要并行 **且** 访问模式允许（写永远不会被并行）。 */
    public boolean effectiveParallel() {
        return parallel && access.shareable();
    }
}
