package com.hdf.cryptand.integratednetwork;

/**
 * Pipez 能量解析器（2026-08-30 用户：多定义——能量）。
 * 继承 {@link PipezAbstractResolver} 基础实现；能量无过滤（按容量），
 * 输出接口参数通常为 ACCEPT_ALL（或按能量类型/电压过滤）。
 * 注册 id = "pipez:energy"。
 */
public final class PipezEnergyResolver extends PipezAbstractResolver {

    public PipezEnergyResolver() {
        super(TransferType.ENERGY);
    }

    @Override
    public String id() {
        return "pipez:energy";
    }
}
