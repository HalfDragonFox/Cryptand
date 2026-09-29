package com.hdf.cryptand.engine;

import com.hdf.cryptand.circuitsimulation.solver.Complex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ===== 组装器组（AssemblerGroup，2026-09-12 用户）=====
 *
 * <p>用户原话："组装器提供组装器接口基类，提供计算与返回等接口，普通组装器继承此
 * 基类，然后组装器组也继承组装器接口基类，不过组装器组的作用是整合多个组装器，
 * 比如电机/发电机这种多方块的结构使用"。
 *
 * <p><b>组合模式</b>：组【本身就是一个组装器】（{@link Assembler}），对外与普通
 * 组装器完全等价——可以直接交给 {@code AssemblerFactory.registerDevice(...)}，
 * 也可以被另一个组当作成员（支持嵌套）。内部聚合若干【成员组装器】（单方块设备），
 * 把"电机/发电机"这类多方块结构呈现为【一个逻辑设备】：
 *
 * <ul>
 *   <li>{@link #terminalCount()}：成员端子数求和（结构总端子；子类可按结构覆写）</li>
 *   <li>{@link #build(GraphBuilder)}：按成员顺序委托构建（同一 GraphBuilder）</li>
 *   <li>{@link #bind(Binding)}：把同一个绑定对象分发给全部成员（null = 不绑定）</li>
 *   <li>{@link #isSource()}：任一成员有源 → 组有源（发电机结构）</li>
 *   <li>{@link #feature()}：组的特征名（由构造方指定，如 "motor" / "generator"）</li>
 * </ul>
 *
 * <p><b>计算与返回</b>：需要参与"计算/返回"的组装器实现 {@link AssemblerComputed}
 * （损耗功率 / 温度），组同样实现它并把请求【递归聚合】到成员：
 * 组损耗 = 各成员损耗之和；组温度 = 主成员温度（{@link #primary()}）。
 * 这样上层（引擎后处理/温度推进/诊断）只与组打交道，不必关心结构内部有几个块。
 */
public class AssemblerGroup implements Assembler, AssemblerComputed {

    /** 组特征名（普通组装器用 feature() 区分类型；组由构造方指定，如 motor/generator） */
    private final String feature;

    /** 成员组装器（按加入顺序；多方块结构的一块 = 一个成员） */
    private final List<Assembler> members = new ArrayList<>();

    /** 主成员下标（温度等聚合归属；-1 = 未指定 → 取第一个成员） */
    private int primaryIndex = -1;

    public AssemblerGroup(String feature) {
        this.feature = feature;
    }

    public AssemblerGroup(String feature, Assembler... members) {
        this(feature);
        if (members != null) {
            for (Assembler a : members) add(a);
        }
    }

    /** 添加成员（多方块结构的一块）；null 忽略；返回 this 便于链式 */
    public AssemblerGroup add(Assembler a) {
        if (a != null) members.add(a);
        return this;
    }

    /** 指定主成员（温度归属；越界忽略） */
    public AssemblerGroup primary(int index) {
        if (index >= 0 && index < members.size()) primaryIndex = index;
        return this;
    }

    /** 只读成员列表 */
    public List<Assembler> members() {
        return Collections.unmodifiableList(members);
    }

    /** 成员数量（多方块结构的块数） */
    public int size() {
        return members.size();
    }

    /** 主成员（温度/绑定归属）；空组返回 null */
    public Assembler primary() {
        if (members.isEmpty()) return null;
        int i = (primaryIndex >= 0 && primaryIndex < members.size()) ? primaryIndex : 0;
        return members.get(i);
    }

    // ==================== Assembler 接口（组即组装器） ====================

    @Override
    public String feature() {
        return feature;
    }

    /** 有源：任一成员有源 → 组有源（如发电机结构里含励磁/电枢源） */
    @Override
    public boolean isSource() {
        for (Assembler a : members) {
            try {
                if (a.isSource()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 端子数：成员端子数之和（结构总端子）；子类可按结构语义覆写 */
    @Override
    public int terminalCount() {
        int n = 0;
        for (Assembler a : members) {
            try {
                n += Math.max(a.terminalCount(), 0);
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    /** 构建：按成员顺序委托（同一 GraphBuilder——组不改变图语义，只做编排） */
    @Override
    public void build(GraphBuilder g) {
        if (g == null) return;
        for (Assembler a : members) {
            try {
                a.build(g);
            } catch (Throwable ignored) {
            }
        }
    }

    /** 绑定：同一个绑定对象分发给全部成员（null = 不绑定，与接口约定一致） */
    @Override
    public void bind(Binding binding) {
        if (binding == null) return;
        for (Assembler a : members) {
            try {
                a.bind(binding);
            } catch (Throwable ignored) {
            }
        }
    }

    // ==================== 计算与返回（递归聚合） ====================

    /** 组损耗 = 各成员（实现 {@link AssemblerComputed} 者）损耗之和 */
    @Override
    public double lossPower(Complex va, Complex vb, double omega) {
        double sum = 0;
        for (Assembler a : members) {
            if (!(a instanceof AssemblerComputed c)) continue;
            try {
                double v = c.lossPower(va, vb, omega);
                if (Double.isFinite(v) && v > 0) sum += v;
            } catch (Throwable ignored) {
            }
        }
        return sum;
    }

    /** 组温度 = 主成员温度（取不到则回退：第一个能给出有效温度的成员） */
    @Override
    public double temperatureC() {
        Assembler p = primary();
        if (p instanceof AssemblerComputed c) {
            try {
                double t = c.temperatureC();
                if (Double.isFinite(t)) return t;
            } catch (Throwable ignored) {
            }
        }
        for (Assembler a : members) {
            if (!(a instanceof AssemblerComputed c)) continue;
            try {
                double t = c.temperatureC();
                if (Double.isFinite(t)) return t;
            } catch (Throwable ignored) {
            }
        }
        return Double.NaN;
    }

    @Override
    public String toString() {
        return "AssemblerGroup{" + feature + " members=" + members.size()
                + " terminals=" + terminalCount() + "}";
    }
}
