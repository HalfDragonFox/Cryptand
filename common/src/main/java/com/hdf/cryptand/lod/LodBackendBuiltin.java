package com.hdf.cryptand.lod;

/**
 * ===== 内置 LOD 后端（自实现兜底，common 纯 Java 零 MC）=====
 *
 * <p>用户定案：「lod 后端有<b>自实现兜底</b>」。本后端就是那个兜底：LOD 算法全锁在核心内部
 * （{@link Lod2D} / {@link Lod3D}），<b>不依赖任何外部渲染库</b>，所以永远 {@code available()}。</p>
 *
 * <p>链条里 {@code builtin} 永远垫底：注册表构造时自动登记它，且 {@link LodBackendRegistry#setChain}
 * 会把 {@code builtin} 补到末尾 —— 因此"外部后端全挂了/全没登记"也一定还有一套可用实现。</p>
 */
public final class LodBackendBuiltin implements LodBackend {

    public static final String NAME = LodBackendRegistry.BUILTIN;

    @Override
    public String name() {
        return NAME;
    }

    /** 永远可用：它不依赖任何外部库（这就是"兜底"的含义）。 */
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LodCapabilities capabilities() {
        return LodCapabilities.of(LodCapabilities.DOWNSAMPLE_2D | LodCapabilities.GEOMETRY_3D);
    }

    @Override
    public LodLevel level2D(Lod2DRequest request) {
        return Lod2D.level(request);
    }

    @Override
    public LodLevel level3D(Lod3DRequest request) {
        return Lod3D.level(request);
    }
}