package com.hdf.cryptand.neoforge.soc.content;

import com.hdf.cryptand.soc.oc.OcAbi;

/**
 * ===== SoC 部件类型（2026-09-15，参考 OpenComputers 的部件体系）=====
 *
 * <p>OC 的电脑由「机箱 + 主板 + CPU + 内存条 + 硬盘 + 扩展卡」组装，各部件决定能力。
 * Cryptand 沿用同一心智模型（MCU/SoC 方向）：<b>部件规格决定沙箱的预算与能力表</b>。</p>
 */
public enum SocPartKind {
    /** 芯片（SoC 内核）：spec = 每 tick 指令预算（等效主频 = 20 × spec） */
    CHIP("芯片", "每 tick 指令预算", ""),
    /** 内存条：spec = KB（扩展 RAM 容量） */
    RAM("内存条", "RAM 容量(KB)", ""),
    /**
     * 底板/主板：spec = 扩展槽位数量。
     *
     * <p>⚠ OC 里主板**不是组件**（它是机箱的一部分），这里给个我们自己命名的组件名只是为了
     * 诊断展示 —— 别指望 Lua 的 {@code component.list()} 里有它。</p>
     */
    BOARD("底板", "扩展槽位", "component_bus"),
    /** Flash/EEPROM：spec = KB（固件与参数持久化容量）—— 进机箱就是 OC 的 filesystem 组件 */
    FLASH("存储", "容量(KB)", "filesystem"),
    /**
     * 软盘（2026-09-17 加，对齐官方 OC）：**系统盘** —— Cryptand OS / UI OS / Linux 装在软盘里，
     * Cryptand Boot 按 "机箱软盘 → 外部软盘 → 机箱硬盘 → 外部硬盘" 的顺序找可引导程序。
     * spec = 容量(KB)。
     */
    FLOPPY("软盘", "容量(KB)", "filesystem"),
    /**
     * 启动介质（C 专属 EEPROM，2026-09-17 加）。
     *
     * <p>对标 OC 的 EEPROM 槽：OC 原生那颗装的是 **Lua BIOS**（由 Lua 架构解释执行），
     * 我们的架构跑 RV32 机器码 ⇒ 必须有自己的启动介质，承载 Cryptand OS 内核镜像。
     * spec = 固件容量(KB)。</p>
     */
    EEPROM("启动 EEPROM", "固件容量(KB)", "eeprom"),
    /** 扩展卡 GPIO：spec = 引脚数 —— 组件名照 OC 的**红石卡**（{@code RedstoneSignaller}）叫 {@code redstone} */
    CARD_GPIO("GPIO 扩展卡", "引脚数", "redstone"),
    /**
     * 扩展卡 PWM：spec = 通道数（电机转速控制）。
     *
     * <p>OC 没有 PWM 卡 ⇒ 组件名是我们自己的（{@code pwm}）。命名跟着 OC 的规矩走：
     * 小写 + 下划线，且**不与 OC 的 45 个组件名冲突**（见 {@code oc-component-inventory}）。</p>
     */
    CARD_PWM("PWM 扩展卡", "通道数", "pwm"),
    /** 扩展卡 ADC：spec = 通道数（电网采样）—— OC 无对应，自定名 {@code adc} */
    CARD_ADC("ADC 扩展卡", "通道数", "adc"),
    /** 扩展卡 UART：spec = 波特率分频（cyclesPerByte）—— OC 无对应，自定名 {@code serial} */
    CARD_UART("串口扩展卡", "每字节周期", "serial"),
    /**
     * 图形扩展卡：spec = 输出通道数（字符/像素输出能力）。
     *
     * <p>用户 2026-09-30 定案：我们的显卡叫 {@code rc_gpu}（与屏的 {@code rc_screen} 对称），
     * 不再借用 OC 原版显卡的组件名 {@code gpu} —— 名字来自 ABI 常量的单一来源。</p>
     */
    CARD_GPU("图形扩展卡", "输出通道数", OcAbi.GPU_COMPONENT_NAME);

    private final String label;
    private final String specLabel;
    private final String ocComponent;

    SocPartKind(String label, String specLabel, String ocComponent) {
        this.label = label;
        this.specLabel = specLabel;
        this.ocComponent = ocComponent;
    }

    public String label() {
        return label;
    }

    public String specLabel() {
        return specLabel;
    }

    /**
     * 这类部件在 OC 组件表里的**组件类型名**（{@code component.list()} 的第二列）。
     *
     * <p>为什么要有这一栏（用户 2026-09-26："组件 id 按照原版 oc 显示"）：</p>
     * <ul>
     *   <li><b>能对上的照 OC 叫</b>：盘 = {@code filesystem}、EEPROM = {@code eeprom}、
     *       显卡 = {@code gpu}、GPIO 卡 = {@code redstone}（OC 自己就是这么叫红石卡的）；</li>
     *   <li><b>对不上的不硬套</b>：PWM / ADC / 串口卡是我们自己的硬件，OC 没有对应物 ⇒
     *       用 {@code pwm} / {@code adc} / {@code serial}（小写下划线，且避开 OC 已有的 45 个名字）；</li>
     *   <li><b>空串 = 不是组件</b>：芯片与内存条在 OC 里是**机器内部**（{@code component.list()}
     *       里没有 cpu/ram），我们照这个语义，不凭空造两个组件出来。</li>
     * </ul>
     */
    public String ocComponent() {
        return ocComponent;
    }

    /** 这类部件进机箱后会不会成为一个 OC 组件 */
    public boolean isComponent() {
        return !ocComponent.isEmpty();
    }
}
