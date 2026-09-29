package com.hdf.cryptand.soc.factory;

import com.hdf.cryptand.soc.board.ChipConfig;
import com.hdf.cryptand.soc.board.ChipType;

/**
 * ===== 芯片蓝图的内容（2026-09-29，纯数据，放 common）=====
 *
 * <p>用户定案：「定义蓝图物品：右键可放到无蓝图的设计机里……蓝图被载入后所有相关变更要保存到此蓝图物品的
 * 信息中，拿出来天然有信息」—— 所以蓝图内容就是<b>名字 + 类型 + 完整配置</b>，与 MC 无关，放 common。</p>
 *
 * <p>状态语义：{@link #config()} 为空 = <b>空白蓝图</b>（还没设计，右键不会变芯片）；
 * 非空 = <b>完成的蓝图</b>（右键直接变出对应芯片，见 MC 侧的 {@code BlueprintItem}）。</p>
 *
 * @param name   蓝图名（玩家可改；空则由内容自动描述）
 * @param type   设计时选定的芯片类型（未选 = null）
 * @param config 完整配置（未设计完 = null）
 */
public record ChipBlueprint(String name, ChipType type, ChipConfig config) {

    public ChipBlueprint {
        name = name == null ? "" : name;
    }

    /** 空白蓝图（新做出来的那一张） */
    public static ChipBlueprint blank() {
        return new ChipBlueprint("", null, null);
    }

    /** 是否已完成（完成的蓝图右键可直接变成芯片） */
    public boolean complete() {
        return config != null;
    }

    /** 显示名（没名字就按内容给一个） */
    public String displayName() {
        if (!name.isEmpty()) {
            return name;
        }
        return config == null ? "空白芯片蓝图" : config.label() + " · " + config.mhz() + " MHz";
    }

    public ChipBlueprint withName(String newName) {
        return new ChipBlueprint(newName, type, config);
    }

    /** 设计机写入时用：类型与配置一起记下 */
    public ChipBlueprint withConfig(ChipConfig newConfig) {
        return new ChipBlueprint(name, newConfig == null ? null : newConfig.type(), newConfig);
    }
}
