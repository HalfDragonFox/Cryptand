package com.hdf.cryptand.integratednetwork;

/**
 * Pipez 气体解析器（2026-08-30 用户：多定义——气体）。
 * 继承 {@link PipezAbstractResolver} 基础实现；过滤条目为气体（化学）注册名 id。
 * 注册 id = "pipez:gas"。
 */
public final class PipezGasResolver extends PipezAbstractResolver {

    public PipezGasResolver() {
        super(TransferType.GAS);
    }

    @Override
    public String id() {
        return "pipez:gas";
    }
}
