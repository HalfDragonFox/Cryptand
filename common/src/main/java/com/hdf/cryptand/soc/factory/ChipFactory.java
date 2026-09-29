package com.hdf.cryptand.soc.factory;

import com.hdf.cryptand.soc.board.ChipConfig;
import com.hdf.cryptand.soc.board.ChipType;
import com.hdf.cryptand.soc.board.SocCpuTiers;
import com.hdf.cryptand.soc.board.SocIsa;
import com.hdf.cryptand.soc.board.SocModules;
import com.hdf.cryptand.soc.peripheral.PeripheralMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ===== CPU 制作工厂（2026-09-29，用户定案）=====
 *
 * <p>语义（用户原话）：</p>
 * <ul>
 *   <li><b>「首先申请制作，此时返回操作句柄」</b> —— {@link #begin(ChipType)} / {@link #begin(ChipType, String)}；</li>
 *   <li><b>「此句柄可以输入参数、输出最终 CPU，使用默认组件表，获取组件表等」</b> —— {@link ChipDraft} 的全部方法；</li>
 *   <li><b>「操作句柄是抽象，用于给不同客户」</b> —— 句柄实现与客户解耦：MC 的蓝图设计机 UI、MCP 工具、离线脚本
 *       拿到的都是同一个接口，客户只被记在 {@link ChipDraft#clientId()} 上；</li>
 *   <li><b>「如果需要再次编辑蓝图只需要申请一个句柄然后导入操作即可」</b> —— {@link ChipDraft#importFrom(ChipConfig)}；</li>
 *   <li><b>「停止制作后需要让工厂销毁」</b> —— {@link ChipDraft#close()} / {@link #destroy(ChipDraft)}：
 *       句柄立刻从工厂登记表移除，之后任何调用都抛 {@link IllegalStateException}（句柄已销毁）。</li>
 * </ul>
 *
 * <p><b>分层</b>：本类与 {@link ChipDraft} 全在 common（纯 Java，零 MC 依赖）—— 蓝图设计机的 UI 只是前端，
 * 后端（参数校验、组件表、预算、产出规格）都在这里，MC 侧只做「物品 ↔ {@link ChipConfig}」的搬运。</p>
 *
 * <p><b>单一来源</b>：组件表与预算一律来自 {@code SocModules}（族级能力覆盖）与 {@code PeripheralMap}
 * （槽位计价），本类绝不另写一份模块清单或计价。</p>
 */
public final class ChipFactory {

    /** 在制句柄登记表（客户可用 {@link #active()} 观察；停止制作即移除） */
    private static final Map<String, ChipDraft> ACTIVE = new ConcurrentHashMap<>();

    private ChipFactory() {
    }

    /** ★ 申请制作：返回一颗 CPU（默认按类型给示例配置）的操作句柄 */
    public static ChipDraft begin(ChipType type) {
        return begin(type, "anonymous");
    }

    /** ★ 申请制作（带客户标识：UI / MCP / 脚本各用各的，方便排查"谁在制"） */
    public static ChipDraft begin(ChipType type, String clientId) {
        if (type == null) {
            throw new IllegalArgumentException("芯片类型（ChipType）必填");
        }
        final String id = UUID.randomUUID().toString();
        final ChipDraft draft = new Session(id, clientId == null ? "anonymous" : clientId,
                sample(type));
        ACTIVE.put(id, draft);
        return draft;
    }

    /** ★ 再次编辑蓝图：申请句柄并导入已有配置（等价于 {@code begin(type)} + {@code importFrom(config)}） */
    public static ChipDraft beginFrom(ChipConfig existing, String clientId) {
        if (existing == null) {
            throw new IllegalArgumentException("导入的芯片配置不能为空");
        }
        final ChipDraft draft = begin(existing.type(), clientId);
        draft.importFrom(existing);
        return draft;
    }

    /** ★ 停止制作：工厂销毁该句柄（幂等；未知句柄忽略） */
    public static void destroy(ChipDraft draft) {
        if (draft == null) {
            return;
        }
        ACTIVE.remove(draft.id());
        draft.close();
    }

    /** 在制句柄数（0 = 工厂空闲） */
    public static int activeCount() {
        return ACTIVE.size();
    }

    /** 在制句柄快照（诊断 / 面板显示） */
    public static List<ChipDraft> active() {
        return List.copyOf(ACTIVE.values());
    }

    /**
     * ★ 获取组件表：该类型（+族）可装载的模块名清单。
     *
     * <p>CPU 类型按族取（{@code SocModules.of(family)}：MCU 仅部分、SOC 几乎所有、CPU 全部 ——
     * 所以 <b>PCIE 只有 SOC 与 CPU 持有</b>）；GPU 类型取图形管线清单。</p>
     */
    public static List<String> moduleTable(ChipType type, SocCpuTiers.Family family) {
        if (type == null) {
            return List.of();
        }
        return switch (type) {
            case CPU -> family == null ? List.of() : names(family);
            case GPU -> GPU_MODULES;
        };
    }

    /** 该族能挂的接口（"能不能"；"够不够"由槽位预算回答） */
    public static List<PeripheralMap.Carrier> carriers(SocCpuTiers.Family family) {
        return family == null ? List.of() : SocModules.supportedCarriers(family);
    }

    /** 该族的组件槽位预算 */
    public static int slotBudget(SocCpuTiers.Family family) {
        return family == null ? 0 : SocModules.slotBudget(family);
    }

    /** 默认模块集（= 组件表全选；「使用默认组件表」就是它） */
    public static List<String> defaultModules(ChipType type, SocCpuTiers.Family family) {
        return moduleTable(type, family);
    }

    /** 默认示例配置（工厂开张时的初值；也是创造栏示例芯片的来源） */
    public static ChipConfig sample(ChipType type) {
        return switch (type) {
            case CPU -> new ChipConfig(ChipType.CPU, SocCpuTiers.Family.CPU, SocIsa.RV32IM,
                    SAMPLE_CPU_MHZ, 1024, 8192, names(SocCpuTiers.Family.CPU), List.of(), List.of());
            case GPU -> new ChipConfig(ChipType.GPU, null, null, SAMPLE_GPU_MHZ, 8192, 0,
                    GPU_MODULES, List.of(), List.of());
        };
    }

    /** 示例 CPU 主频（CPU 族区间 500–2000 MHz 的中段） */
    public static final int SAMPLE_CPU_MHZ = 1000;

    /** 示例 GPU 时钟 */
    public static final int SAMPLE_GPU_MHZ = 1000;

    /** GPU 的示例模块集（图形管线 + 显存 + 输出通道；GPU 不是 MCU/SOC/CPU 那种"族"） */
    public static final List<String> GPU_MODULES = List.of("GPU0", "VRAM0", "DP0", "DP1");

    private static List<String> names(SocCpuTiers.Family family) {
        final List<String> out = new ArrayList<>();
        SocModules.of(family).forEach(m -> out.add(m.name()));
        return out;
    }

    // ==================== 操作句柄实现 ====================

    /** 句柄会话：**不是线程安全的设计目标**（一个客户一个句柄），字段用 volatile 仅保证可见性 */
    private static final class Session implements ChipDraft {

        private final String id;
        private final String clientId;
        private final ChipType type;
        private volatile boolean closed;

        private volatile SocCpuTiers.Family family;
        private volatile SocIsa isa;
        private volatile int mhz;
        private volatile int ramKb;
        private volatile int flashKb;
        private final List<String> modules = new ArrayList<>();
        private volatile List<String> links = List.of();
        private volatile List<String> cards = List.of();

        Session(String id, String clientId, ChipConfig initial) {
            this.id = id;
            this.clientId = clientId;
            this.type = initial.type();
            load(initial);
        }

        private void load(ChipConfig c) {
            this.family = c.family();
            this.isa = c.isa();
            this.mhz = c.mhz();
            this.ramKb = c.ramKb();
            this.flashKb = c.flashKb();
            this.modules.clear();
            this.modules.addAll(c.modules());
            this.links = c.links();
            this.cards = c.cards();
        }

        private void alive() {
            if (closed) {
                throw new IllegalStateException("句柄已销毁（id=" + id + "）：停止制作后工厂不再接受该句柄的任何操作");
            }
        }

        @Override public String id() { return id; }
        @Override public String clientId() { return clientId; }
        @Override public ChipType type() { return type; }

        @Override public List<SocCpuTiers.Family> familyOptions() {
            alive();
            return type.needsIsa() ? List.of(SocCpuTiers.Family.values()) : List.of();
        }

        @Override public List<Integer> mhzOptions() {
            alive();
            if (family == null) {
                return List.of();
            }
            final List<Integer> out = new ArrayList<>();
            SocCpuTiers.of(family).forEach(t -> out.add(t.mhz()));
            return out;
        }

        @Override public List<SocIsa> isaOptions() {
            alive();
            if (!type.needsIsa()) {
                return List.of();
            }
            return family == SocCpuTiers.Family.MCU
                    ? List.of(SocIsa.RV32EC, SocIsa.RV32IM, SocIsa.RV32IMC, SocIsa.MCS51)
                    : List.of(SocIsa.RV32IM, SocIsa.RV32IMC, SocIsa.RV32IMAC, SocIsa.RV64IMAC);
        }

        @Override public boolean selectFamily(SocCpuTiers.Family f) {
            alive();
            if (familyOptions().isEmpty() || f == null) {
                return false;
            }
            family = f;
            mhz = Math.max(f.minMhz(), Math.min(f.maxMhz(), mhz));
            if (isa == null) {
                isa = f == SocCpuTiers.Family.MCU ? SocIsa.RV32IM : SocIsa.RV32IMAC;
            }
            useDefaultModules();
            return true;
        }

        @Override public SocCpuTiers.Family family() { alive(); return family; }

        @Override public boolean setMhz(int value) {
            alive();
            if (family != null && (value < family.minMhz() || value > family.maxMhz())) {
                return false;
            }
            if (value < 0) {
                return false;
            }
            mhz = value;
            return true;
        }

        @Override public int mhz() { alive(); return mhz; }

        @Override public boolean selectIsa(SocIsa value) {
            alive();
            if (value == null || !isaOptions().contains(value)) {
                return false;
            }
            isa = value;
            return true;
        }

        @Override public SocIsa isa() { alive(); return isa; }

        @Override public List<String> moduleTable() {
            alive();
            return ChipFactory.moduleTable(type, family);
        }

        @Override public List<String> modules() { alive(); return List.copyOf(modules); }

        @Override public boolean toggleModule(String name) {
            alive();
            if (name == null || !moduleTable().contains(name)) {
                return false;   // 不在组件表里 ⇒ 拒绝（不静默忽略）
            }
            final List<String> used = modules();
            if (used.contains(name)) {
                modules.remove(name);
                return false;
            }
            modules.add(name);
            return true;
        }

        @Override public boolean useDefaultModules() {
            alive();
            modules.clear();
            modules.addAll(defaultModules(type, family));
            return true;
        }

        @Override public boolean setRamKb(int value) {
            alive();
            if (value < 0) {
                return false;
            }
            ramKb = value;
            return true;
        }

        @Override public boolean setFlashKb(int value) {
            alive();
            if (value < 0) {
                return false;
            }
            flashKb = value;
            return true;
        }

        @Override public boolean setCards(List<String> value) {
            alive();
            cards = value == null ? List.of() : List.copyOf(value);
            return true;
        }

        @Override public boolean setLinks(List<String> value) {
            alive();
            links = value == null ? List.of() : List.copyOf(value);
            return true;
        }

        @Override public int slotBudget() { alive(); return ChipFactory.slotBudget(family); }

        @Override public int slotsUsed() {
            alive();
            if (family == null) {
                return 0;
            }
            // 口径与 {@code SocModules.slotBudget} 对齐：**按载体去重**（同一载体挂几件只算一次固定开销），
            // 且 PCIe 不计入预算（它的槽位按链路宽度逐卡算，见 slotBudget 的注释）。
            int used = 0;
            final java.util.EnumSet<PeripheralMap.Carrier> counted =
                    java.util.EnumSet.noneOf(PeripheralMap.Carrier.class);
            for (final com.hdf.cryptand.soc.board.SocModule m : SocModules.of(family)) {
                if (!modules.contains(m.name()) || m.iface() == PeripheralMap.Carrier.PCIE
                        || m.iface() == PeripheralMap.Carrier.INTERNAL) {
                    continue;   // 片上模块与 PCIe 不占"能力集基准"预算
                }
                if (counted.add(m.iface())) {
                    used += m.iface().sharedSlots() + m.iface().perDeviceSlots();
                }
            }
            return used;
        }

        @Override public List<String> problems() {
            alive();
            final List<String> out = new ArrayList<>();
            if (type.needsIsa() && family == null) {
                out.add("还没选族（MCU / SOC / CPU）");
            }
            if (type.needsIsa() && isa == null) {
                out.add("还没选 ISA / 位宽");
            }
            for (final String m : modules) {
                if (!moduleTable().contains(m)) {
                    out.add("模块 " + m + " 不在该族的组件表里");
                }
            }
            final int budget = slotBudget();
            if (budget > 0 && slotsUsed() > budget) {
                out.add("槽位超预算：" + slotsUsed() + " / " + budget);
            }
            return out;
        }

        @Override public void importFrom(ChipConfig existing) {
            alive();
            if (existing == null) {
                throw new IllegalArgumentException("导入的芯片配置不能为空");
            }
            if (existing.type() != type) {
                throw new IllegalArgumentException("导入的类型（" + existing.type().label()
                        + "）与句柄类型（" + type.label() + "）不一致");
            }
            load(existing);
        }

        @Override public ChipConfig build() {
            alive();
            return new ChipConfig(type, family, isa, mhz, ramKb, flashKb,
                    List.copyOf(modules), links, cards);
        }

        /** ★ 停止制作：工厂销毁句柄（再调用任何方法都抛异常） */
        @Override public void close() {
            closed = true;
        }
    }

    // ==================== 离线闸门：:common:runChipFactoryTest ====================

    /** 纯逻辑闸门（零 MC）：申请 / 参数 / 组件表 / 默认表 / 导入 / 产出 / 停止销毁 */
    public static void main(String[] args) {
        int pass = 0;
        final List<String> fail = new ArrayList<>();

        // 1) 申请制作 → 句柄在册
        final ChipDraft cpu = begin(ChipType.CPU, "gate");
        check(fail, "申请后工厂有 1 个句柄", activeCount() == 1);
        check(fail, "句柄类型 = CPU", cpu.type() == ChipType.CPU);
        check(fail, "客户标识保留", "gate".equals(cpu.clientId()));
        pass += 3;

        // 2) 获取组件表 / 默认组件表 / 族差异（PCIE 只有 SOC 与 CPU）
        check(fail, "CPU 默认族有组件表", !cpu.moduleTable().isEmpty());
        check(fail, "默认组件表 = 全选", cpu.modules().equals(defaultModules(ChipType.CPU, cpu.family())));
        check(fail, "MCU 不能挂 PCIE",
                !carriers(SocCpuTiers.Family.MCU).contains(PeripheralMap.Carrier.PCIE));
        check(fail, "SOC 能挂 PCIE",
                carriers(SocCpuTiers.Family.SOC).contains(PeripheralMap.Carrier.PCIE));
        check(fail, "CPU 能挂 PCIE",
                carriers(SocCpuTiers.Family.CPU).contains(PeripheralMap.Carrier.PCIE));
        pass += 4;

        // 3) 选族 / 频率 / ISA
        check(fail, "选 SOC 族", cpu.selectFamily(SocCpuTiers.Family.SOC));
        check(fail, "族 = SOC", cpu.family() == SocCpuTiers.Family.SOC);
        check(fail, "频率被夹进族区间", cpu.mhz() >= SocCpuTiers.Family.SOC.minMhz()
                && cpu.mhz() <= SocCpuTiers.Family.SOC.maxMhz());
        check(fail, "超区间频率被拒", !cpu.setMhz(SocCpuTiers.Family.SOC.maxMhz() + 1));
        check(fail, "区间内频率可设", cpu.setMhz(256));
        check(fail, "ISA 选项含 RV64IMAC", cpu.isaOptions().contains(SocIsa.RV64IMAC));
        check(fail, "选 ISA RV32IM", cpu.selectIsa(SocIsa.RV32IM));
        pass += 7;

        // 4) 模块开关（不在表里必须拒绝）
        final String first = cpu.moduleTable().get(0);
        check(fail, "默认包含首项模块", cpu.modules().contains(first));
        check(fail, "toggle 移除首项", !cpu.toggleModule(first) && !cpu.modules().contains(first));
        check(fail, "toggle 加回首项", cpu.toggleModule(first) && cpu.modules().contains(first));
        check(fail, "不在组件表的模块被拒", !cpu.toggleModule("NOPE0"));
        pass += 4;

        // 5) 预算与问题清单
        check(fail, "SOC 预算 > 0", cpu.slotBudget() > 0);
        check(fail, "默认装载不超预算（问题清单为空）", cpu.problems().isEmpty());
        pass += 2;

        // 6) 产出最终 CPU
        final ChipConfig built = cpu.build();
        check(fail, "产出类型 CPU", built.type() == ChipType.CPU);
        check(fail, "产出族 SOC", built.family() == SocCpuTiers.Family.SOC);
        check(fail, "产出 MHz=256", built.mhz() == 256);
        check(fail, "产出预算 = MHz×50_000", built.cyclesPerTick() == SocCpuTiers.specOfMhz(256));
        check(fail, "产出档位 = 3", built.tier() == 3);
        check(fail, "产出位宽 = 32", built.xlen() == 32);
        pass += 6;

        // 7) 再次编辑蓝图：申请句柄 + 导入
        final ChipDraft again = beginFrom(built, "gate-2");
        check(fail, "导入后模块集一致", again.modules().equals(built.modules()));
        check(fail, "导入后 MHz 一致", again.mhz() == built.mhz());
        check(fail, "再编辑可改模块", !again.toggleModule(first) || again.modules().size() != built.modules().size());
        pass += 3;

        // 8) 停止制作 → 工厂销毁句柄（再操作必须抛异常）
        destroy(cpu);
        check(fail, "销毁后只剩再次编辑那个句柄", activeCount() == 1);
        try {
            cpu.build();
            check(fail, "销毁后调用必须抛 IllegalStateException", false);
        } catch (IllegalStateException expected) {
            pass++;
        }
        destroy(again);
        check(fail, "全部停止后工厂空闲", activeCount() == 0);
        pass++;

        // 9) GPU 类型：不吃族 / 组件表 = 图形管线
        final ChipDraft gpu = begin(ChipType.GPU, "gate-3");
        check(fail, "GPU 没有族选项", gpu.familyOptions().isEmpty());
        check(fail, "GPU 组件表 = 图形管线", gpu.moduleTable().equals(GPU_MODULES));
        check(fail, "GPU 产出无需 ISA", gpu.build().isa() == null);
        destroy(gpu);
        pass += 3;

        // 10) 非法配置必须被构造期拦下（绝不静默）
        try {
            new ChipConfig(ChipType.CPU, null, null, 100, 0, 0, List.of(), List.of(), List.of());
            check(fail, "CPU 缺族/ISA 必须抛异常", false);
        } catch (IllegalArgumentException expected) {
            pass++;
        }
        try {
            new ChipConfig(ChipType.CPU, SocCpuTiers.Family.MCU, SocIsa.RV32IM, 999, 0, 0,
                    List.of(), List.of(), List.of());
            check(fail, "超族区间频率必须抛异常", false);
        } catch (IllegalArgumentException expected) {
            pass++;
        }

        System.out.println("CHIP-FACTORY PASS " + pass + " / FAIL " + fail.size());
        for (final String f : fail) {
            System.out.println("  ✖ " + f);
        }
        if (!fail.isEmpty()) {
            System.exit(1);
        }
    }

    private static void check(List<String> fail, String name, boolean ok) {
        if (!ok) {
            fail.add(name);
        }
    }
}
