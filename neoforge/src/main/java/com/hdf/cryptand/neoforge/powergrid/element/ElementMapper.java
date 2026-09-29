package com.hdf.cryptand.neoforge.powergrid.element;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ===== 元件映射器（抽象）=====
 *
 * <p>「一类设备方块 → 引擎元件」的映射契约。原 {@code PhasorNetworkBuilder.addElement}
 * 是一条 233 行的 {@code if (be instanceof ...) ... else if ...} 长链，把源/电容/
 * 电感/电阻/绕组/原版组装器六类设备的装配细节挤在一个方法里。这里按<b>策略模式</b>
 * 重构：抽象基类固定流程，子类只回答两件事——「是不是我管的方块」与「怎么装」。
 *
 * <p>使用方式：{@link ElementMappers#dispatch} 按有序表逐个询问，命中即停。
 */
public abstract class ElementMapper {

    protected ElementMapper() {}

    /** 映射器名称（诊断/自检用）。 */
    abstract String name();

    /** 本映射器是否负责该方块。 */
    abstract boolean supports(BlockEntity be);

    /**
     * 模板方法：判定 → 映射。子类只实现 {@link #supports} 与 {@link #doMap}，
     * 不要覆写本方法。
     */
    final void apply(ElementMapContext c, BlockEntity be) {
        if (be == null || !supports(be)) return;
        doMap(c, be);
    }

    /** 实际映射：装元件 + 登记温度/储能/组装器/绑定。 */
    protected abstract void doMap(ElementMapContext c, BlockEntity be);
}
