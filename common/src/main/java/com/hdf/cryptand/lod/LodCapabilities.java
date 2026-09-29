package com.hdf.cryptand.lod;

/**
 * ===== LOD 后端能力位（common，纯 Java 零 MC）=====
 *
 * <p>与 JIT 的 {@code ExecBackend} 同一纪律：后端<b>声明能力</b>，选择方按能力挑，<b>回退必须日志可见</b>。
 * 这里只描述"能做什么"，不描述"怎么做"（渲染实现留在 MC 侧）。</p>
 */
public final class LodCapabilities {

    /** 能做 2D 降采样（屏幕内容/纹理源）。 */
    public static final int DOWNSAMPLE_2D = 1;
    /** 能压缩帧（PNG 之类）。 */
    public static final int COMPRESS_FRAME = 1 << 1;
    /** 能在 GPU 侧做 LOD（不占 CPU 编码）。 */
    public static final int GPU_SIDE_LOD = 1 << 2;
    /** 能做 3D 几何/距离 LOD（体素、区块、世界几何）。 */
    public static final int GEOMETRY_3D = 1 << 3;
    /** 是外部渲染后端（如 Flywheel）—— 由 MC 侧的适配器实现，common 只记录。 */
    public static final int EXTERNAL_RENDER_BACKEND = 1 << 4;

    public static final LodCapabilities NONE = new LodCapabilities(0);

    private final int bits;

    private LodCapabilities(int bits) {
        this.bits = bits;
    }

    public static LodCapabilities of(int bits) {
        return bits == 0 ? NONE : new LodCapabilities(bits);
    }

    public int bits() {
        return bits;
    }

    public boolean has(int capability) {
        return (bits & capability) == capability;
    }

    public LodCapabilities plus(int capability) {
        return new LodCapabilities(bits | capability);
    }

    public boolean supports2D() {
        return has(DOWNSAMPLE_2D);
    }

    public boolean supports3D() {
        return has(GEOMETRY_3D);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("LodCapabilities[");
        if (has(DOWNSAMPLE_2D)) {
            sb.append("2D ");
        }
        if (has(GEOMETRY_3D)) {
            sb.append("3D ");
        }
        if (has(COMPRESS_FRAME)) {
            sb.append("compress ");
        }
        if (has(GPU_SIDE_LOD)) {
            sb.append("gpu ");
        }
        if (has(EXTERNAL_RENDER_BACKEND)) {
            sb.append("external ");
        }
        return sb.toString().trim() + "]";
    }
}
