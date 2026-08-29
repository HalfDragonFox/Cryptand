package com.hdf.cryptand.circuitsimulation.model.elements;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 半导体元件基类（2026-08-12 用户要求：原版三极管等电路组装元件纳入本架构，
 * 可添加时间参数）。
 * <p>
 * 覆盖 PowerGrid circuits 系统（电路板组装）的半导体元件：
 *   - 二极管（PNJunctionWire，非线性 PN 结）
 *   - 三极管 BJT（NPN/PNP，三端子 C/B/E，Ic = β·Ib）
 *   - 场效应管 VFET（三端子 D/S/G，Vgs 控制 Ids）
 *   - 电子管（栅极/阴极/阳极，非线性真空管特性）
 * <p>
 * 【三端子】a/b/c（c = -1 表示两端口）——MNA 支持任意节点索引，stamp 3×3 块。
 * 【非线性】相量线性求解不适用 → 用【分段线性 + 迭代】：以时间相关状态
 * （vPrev 上一轮电压/电流）判定工作区（导通/截止/放大/饱和），stamp 相应
 * 线性等效（电阻/受控源）——时间参数 t/dt 驱动迭代收敛。
 * 【时间参数】{@link #stampRealAt(MnaBuilder, double, double)}（t=当前仿真时间，
 * dt=步长）与 {@link #commit} 更新历史状态；AC 相量下退化为线性化小信号
 * （stampComplex，按工作点）。
 * <p>
 * 温度：{@link #temperatureCelsius} 影响特性（二极管 Vth 温度系数等），可由
 * 外部温度模型推进后设置（与 ThermalDevice 协作）。
 */
public abstract class SemiconductorElement implements Element {

    /** 三端子节点：a/b 为主通道，c 为控制端（栅极/基极；两端口 = -1） */
    public final int a, b, c;

    /** 当前仿真时间（s，时间参数，由 stampRealAt 更新） */
    public double t;
    /** 上一轮主通道电压（V，时间相关状态：分段线性迭代判定用） */
    public double vPrev;
    /** 上一轮控制端电压（V，三端子：Vbe/Vgs） */
    public double vControlPrev;
    /** 结温（°C，外部温度模型推进后设置；影响 Vth 等特性） */
    public double temperatureCelsius = 20.0;

    protected SemiconductorElement(int a, int b, int c) {
        this.a = a;
        this.b = b;
        this.c = c;
    }

    /** 结温温度系数：二极管/三极管 Vth 随温度变化（-2mV/K 典型） */
    public double vthAtTemp(double vth25) {
        return vth25 - 0.002 * (temperatureCelsius - 25.0);
    }

    /**
     * 从完整解更新工作点（2026-08-12 工作点迭代）：用于非线性元件在同一
     * 时间步内迭代收敛——用本次求解电压更新 vPrev（主通道 a-b）与
     * vControlPrev（控制端 c 相对 b：BJT Vbe、VFET Vgs、电子管 Vg）。
     *
     * @return 工作点变化量（收敛检测：越小越接近一致解）
     */
    public double updateOperatingPoint(double[] voltages) {
        if (voltages == null || a < 0 || b < 0 || a >= voltages.length || b >= voltages.length) {
            return 0;
        }
        double oldV = vPrev;
        double oldVc = vControlPrev;
        double va = voltages[a];
        double vb = voltages[b];
        vPrev = va - vb;
        if (c >= 0 && c < voltages.length) {
            vControlPrev = voltages[c] - vb;
        }
        return Math.abs(vPrev - oldV) + Math.abs(vControlPrev - oldVc);
    }

    @Override public int nodeA() { return a; }
    @Override public int nodeB() { return b; }

    /** 三端子：AC 相量退化为线性化（子类覆写；默认两端口导纳） */
    @Override
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        // 半导体非线性在相量域不可精确表达 → 默认小信号线性化（子类覆写）。
        // 未覆写 = 无导纳（该元件在 AC 下按开路处理，避免奇异）。
    }

    @Override
    public void commit(double va, double vb, double dt) {
        vPrev = va - vb;
    }

    @Override
    public ElementType type() { return ElementType.COMPOSITE; }
}
