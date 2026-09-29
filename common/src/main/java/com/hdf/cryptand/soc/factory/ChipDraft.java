package com.hdf.cryptand.soc.factory;

import com.hdf.cryptand.soc.board.ChipConfig;
import com.hdf.cryptand.soc.board.ChipType;
import com.hdf.cryptand.soc.board.SocCpuTiers;
import com.hdf.cryptand.soc.board.SocIsa;

import java.util.List;

/**
 * ===== 制作操作句柄（2026-09-29，用户定案）=====
 *
 * <p>用户原话：「操作句柄是抽象，用于给不同客户，如果需要再次编辑蓝图只需要申请一个句柄然后导入操作即可」、
 * 「制作需要完整操作句柄以及停止制作后需要让工厂销毁」。</p>
 *
 * <p>所以本接口是<b>唯一</b>的制作入口契约：MC 的蓝图设计机 UI、MCP 工具、离线脚本都拿它工作，
 * 客户身份只体现在 {@link #clientId()}；句柄由 {@link ChipFactory#begin} 申请、
 * {@link #close()}（或 {@link ChipFactory#destroy}）销毁 —— 销毁后再调用任何方法都抛
 * {@link IllegalStateException}。</p>
 *
 * <p>典型用法（前端可自由替换，后端永远是这一套）：</p>
 * <pre>
 *   try (ChipDraft d = ChipFactory.begin(ChipType.CPU, "designer-ui")) {
 *       d.selectFamily(SocCpuTiers.Family.SOC);   // 输入参数
 *       d.setMhz(256);
 *       d.useDefaultModules();                    // 使用默认组件表
 *       for (String m : d.moduleTable()) { ... }  // 获取组件表（UI 左栏）
 *       if (d.problems().isEmpty()) {
 *           ChipConfig chip = d.build();          // 输出最终 CPU
 *       }
 *   }                                          // 停止制作 ⇒ 工厂销毁句柄
 * </pre>
 */
public interface ChipDraft extends AutoCloseable {

    /** 句柄 id（客户可持久化它来引用同一次制作） */
    String id();

    /** 客户标识（UI / MCP / 脚本；只用于诊断与隔离，不影响语义） */
    String clientId();

    /** 这次制作的是什么类型（申请时定下，制作期间不可改） */
    ChipType type();

    // ==================== 可选范围（UI 用它们铺下拉/列表）====================

    /** 该类型可选的族（GPU 类型为空 —— 它不是 RV 内核） */
    List<SocCpuTiers.Family> familyOptions();

    /** 当前族可用的离散频率档（来自 {@code SocCpuTiers} 单一来源） */
    List<Integer> mhzOptions();

    /** 当前族可选的 ISA / 位宽 */
    List<SocIsa> isaOptions();

    // ==================== 输入参数 ====================

    /** 选族（越界的频率会被夹进该族区间；同时刷新默认组件表） */
    boolean selectFamily(SocCpuTiers.Family family);

    SocCpuTiers.Family family();

    /** 设主频（MHz）；超出该族区间返回 false 且不改动（绝不静默夹值） */
    boolean setMhz(int mhz);

    int mhz();

    /** 选 ISA / 位宽；不在可选范围返回 false */
    boolean selectIsa(SocIsa isa);

    SocIsa isa();

    /** 加/减一个模块（不在组件表里返回 false 且不改动）；返回值 = 现在是否已选 */
    boolean toggleModule(String name);

    List<String> modules();

    /** ★ 使用默认组件表（= 该类型的组件表全选） */
    boolean useDefaultModules();

    boolean setRamKb(int kb);

    boolean setFlashKb(int kb);

    boolean setCards(List<String> cards);

    boolean setLinks(List<String> links);

    // ==================== 查询与校验 ====================

    /** ★ 获取组件表：这次制作可装载的模块名清单（UI 左栏 / 蓝图设计层可用项） */
    List<String> moduleTable();

    /** 组件槽位预算（{@code SocModules.slotBudget} 单一来源） */
    int slotBudget();

    /** 当前选择占用的槽位 */
    int slotsUsed();

    /** 校验问题清单（空 = 可以产出；有值 = 明确原因，绝不静默放行） */
    List<String> problems();

    // ==================== 导入 / 产出 / 停止 ====================

    /** ★ 再次编辑蓝图：把已有配置导入本句柄（类型不一致时抛异常） */
    void importFrom(ChipConfig existing);

    /** ★ 输出最终 CPU 的完整配置 */
    ChipConfig build();

    /** 人话摘要（UI 标题栏 / 日志用） */
    default String describe() {
        return type().label() + " · 族=" + (family() == null ? "-" : family().label())
                + " · " + mhz() + " MHz · ISA=" + (isa() == null ? "-" : isa().label())
                + " · 模块 " + modules().size() + " 项 · 槽位 " + slotsUsed() + "/" + slotBudget();
    }

    /** ★ 停止制作：工厂销毁句柄（幂等） */
    @Override
    void close();
}
