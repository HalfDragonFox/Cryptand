package com.hdf.cryptand.engine;

/**
 * ===== 组装器引擎接口（2026-08-30 用户分层：引擎部分放 common——纯 Java 零 MC；
 * 只有实际 MC 交互的代码放具体平台） =====
 *
 * 组装器 = 完整电气设备的【虚拟模型】（所有模型——元件/温度/能量——都在组装器内）。
 * 本接口为引擎侧抽象（不依赖任何 MC 类型）：
 *   - {@link #feature()}：特征（类型标志——普通/变压器/接口/代理…）
 *   - {@link #isSource()} / {@link #terminalCount()}：设备属性
 *   - {@link #build(GraphBuilder)}：向网络图添加元件/模型（引擎纯算法）
 *   - {@link #bind(CompositeElement)}：【绑定接口】——虚拟元件 ↔ 实际模型绑定
 *     （抽象——由具体平台实现：neoforge 实现 DeviceBinding/BeEventSink——MC 交互）
 *
 * 具体平台的组装器（neoforge device/Assembler）实现本接口（implements），
 * 平台侧补充 MC 交互（读 BE/缓存 → 参数 → build；bind 实现绑定）。
 */
public interface Assembler {

    /** 特征：返回组装器类型标志（普通/变压器/接口/代理——平台扩展枚举值） */
    String feature();

    /** 是否为有源设备（电池/创造源/发电机等）——源组件未加载 → 不组装防无限功率 */
    default boolean isSource() { return false; }

    /** 声明端子数（默认 2——两端口设备；多端子覆写） */
    default int terminalCount() { return 2; }

    /** 构建：向网络图添加元件/模型（引擎纯算法——图构建时由网络求解器调用）。
     *  GraphBuilder 提供 addElement/addModel（引擎侧抽象——平台提供图数据）。 */
    default void build(GraphBuilder g) {
    }

    /**
     * 【绑定接口】虚拟元件 ↔ 实际模型绑定（引擎仅提供绑定接口类——平台实现）：
     *  绑定对象由平台决定（MC：DeviceBinding——BE 模型；网页：控件绑定——
     *  按钮/滑块）。构建完成后由装配方调用 {@code binding.onBound(element)}。
     *  ⚠ 2026-08-30 用户：{@code binding == null} 表示【不绑定】——调用方
     *  （引擎）判 null 跳过（不调 onBound——设备无绑定对象/纯虚拟设备）。
     */
    void bind(Binding binding);
}
