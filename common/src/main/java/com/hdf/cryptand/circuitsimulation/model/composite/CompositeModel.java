package com.hdf.cryptand.circuitsimulation.model.composite;

import com.hdf.cryptand.circuitsimulation.model.Element;
import com.hdf.cryptand.circuitsimulation.model.ElementType;
import com.hdf.cryptand.circuitsimulation.model.thermal.ThermalModel;
import com.hdf.cryptand.circuitsimulation.solver.ComplexMnaBuilder;
import com.hdf.cryptand.circuitsimulation.solver.MnaBuilder;

/**
 * 设备复合模型基类：由简单理想元件组合而成（组合模式），继承统一复合元件
 * 基类 {@link CompositeElement}（含 reset/计算/update 生命周期）。
 *
 * 实际设备（电流表/万用表/变压器等）通过【包含】复合模型做电路解析：
 *   - 构造时把所有简单元件组合好（端口/内部节点在构造时确定）
 *   - {@link #decompose()} 展开为简单元件数组（供网络装配/序列化——网络只存
 *     简单元件，求解/序列化链路无需感知复合层）
 *   - {@link #stampComplex}/{@link #stampReal} 直接委托给全部简单元件
 *   - 带温度的设备覆写 {@link #lossPower}（由端口电压算损耗），求解后统一
 *     {@link #update} 推进温度
 *
 * ⚠ 线程分发（{@link ThreadDispatchElement}）是【可选】中间基类：只有需要单独
 *   线程计算的复合元件（如变压器多回路温度）才继承它，普通设备模型不继承。
 */
public abstract class CompositeModel extends CompositeElement {

    protected CompositeModel(Element[] simple) {
        super(simple);
    }

    protected CompositeModel(Element[] simple, ThermalModel thermal) {
        super(simple, thermal);
    }

    /** 时域/直流：委托给全部简单元件 */
    public void stampReal(MnaBuilder m, double dt) {
        for (Element e : simple) e.stampReal(m, dt);
    }

    /** 交流相量：委托给全部简单元件 */
    public void stampComplex(ComplexMnaBuilder m, double omega) {
        for (Element e : simple) e.stampComplex(m, omega);
    }

    /** 求解后提交状态（委托） */
    public void commit(double va, double vb, double dt) {
        for (Element e : simple) e.commit(va, vb, dt);
    }
}
