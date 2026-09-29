package com.hdf.cryptand.neoforge.soc.content;

import com.hdf.cryptand.soc.board.ChipType;
import com.hdf.cryptand.soc.factory.ChipFactory;

import java.util.List;

/**
 * ===== 示例芯片规格（2026-09-29）=====
 *
 * <p>用户定案："去除目前所有已经有的芯片，只给每个种类一种芯片，并且有大多数经典模块作为示例"。</p>
 * <p>⚠ 规格本体在 <b>common</b>：{@link ChipFactory#sample(ChipType)}（CPU 制作工厂的默认初值）。
 * 这里只是把 common 的纯数据包成 MC 侧物品要用的 {@link SocSpec} —— 绝不在 MC 侧另写一份数值。</p>
 */
public final class ChipSpecs {

    private ChipSpecs() {
    }

    /** 按类型取示例规格（内容来自 common 的工厂默认值） */
    public static SocSpec sample(ChipType type) {
        return SocSpec.of(ChipFactory.sample(type));
    }

    /** GPU 示例模块集（common 单一来源的转发，供旧调用点使用） */
    public static final List<String> GPU_SAMPLE_MODULES = ChipFactory.GPU_MODULES;
}
