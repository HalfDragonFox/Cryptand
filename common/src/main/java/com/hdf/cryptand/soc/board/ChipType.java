package com.hdf.cryptand.soc.board;

/**
 * ===== 芯片类型（2026-09-29，用户定案"芯片定义多个，先定义 GPU、CPU 这两种"）=====
 *
 * <p>这是**蓝图设计机**（见 OV {@code chip-blueprint-designer-requirement-2026-09-27.md}）
 * "选择设计哪种类型的芯片"所依赖的第一层分类：类型 → 可插的槽位 / 参与哪条链路 / 需要哪些模块族。</p>
 *
 * <p>关系：<b>类型（{@link ChipType}）</b> ⊃ <b>族（{@link SocCpuTiers.Family}：MCU/SOC/CPU）</b> ⊃
 * <b>档位</b>（频率 + 位宽）。GPU 类型目前不吃族（它不是 RV 内核，而是固定的图形管线 + 显存）。</p>
 */
public enum ChipType {

    /** 处理器：走 OC 的 CPU 槽，必须声明族与 ISA / 位宽 */
    CPU("cpu", "CPU 芯片", "通用处理器：运行 RV32 内核与固件，插 OC 的 CPU 槽"),

    /** 图形处理器：走 OC 的显卡槽，自带显存与输出通道 */
    GPU("gpu", "GPU 芯片", "图形处理器：自带显存与 DP 输出通道，插 OC 的显卡槽");

    private final String id;
    private final String label;
    private final String desc;

    ChipType(String id, String label, String desc) {
        this.id = id;
        this.label = label;
        this.desc = desc;
    }

    /** 规范小写名（落 NBT / 蓝图用）：{@code cpu} / {@code gpu} */
    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String desc() {
        return desc;
    }

    /** 该类型是否需要一个 ISA / 位宽声明（GPU 不是 RV 内核 ⇒ 不需要） */
    public boolean needsIsa() {
        return this == CPU;
    }

    /** 按规范小写名回查；查不到返回 {@code null}（调用方明确报错，不猜） */
    public static ChipType byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (final ChipType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }
}
