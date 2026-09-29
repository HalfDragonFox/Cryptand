package com.hdf.cryptand.integratednetwork;

/**
 * Pipez 流体解析器（2026-08-30 用户：多定义——流体）。
 * 继承 {@link PipezAbstractResolver} 基础实现；过滤条目为流体注册名 id。
 * 注册 id = "pipez:fluid"。
 */
public final class PipezFluidResolver extends PipezAbstractResolver {

    public PipezFluidResolver() {
        super(TransferType.FLUID);
    }

    @Override
    public String id() {
        return "pipez:fluid";
    }
}
