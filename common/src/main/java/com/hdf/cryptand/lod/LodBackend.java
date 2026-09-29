package com.hdf.cryptand.lod;

/**
 * ===== LOD 后端 SPI（common，纯 Java 零 MC）=====
 *
 * <p>后端只负责<b>算档位</b>（纯数据），不负责渲染 —— 渲染实现（Flywheel、自研 quad、
 * Voxy/DH 的管线）在 MC 侧，通过实现本接口把"我能做什么、什么时候用我"告诉核心。</p>
 *
 * <p>约定：<b>不支持的方向必须返回 null</b>（不要返回 {@link LodLevel#NONE} 冒充支持）；
 * 不可用的后端由注册表跳过并<b>记日志</b>。</p>
 */
public interface LodBackend {

    /** 稳定标识（日志里用；如 {@code builtin-2d} / {@code flywheel} / {@code voxy}）。 */
    String name();

    /** 运行期是否可用（软依赖探测的结果；探测本身由 MC 侧适配器做）。 */
    boolean available();

    LodCapabilities capabilities();

    /** 2D 档位；不支持 2D ⇒ null。 */
    default LodLevel level2D(Lod2DRequest request) {
        return null;
    }

    /** 3D 档位；不支持 3D ⇒ null。 */
    default LodLevel level3D(Lod3DRequest request) {
        return null;
    }

    /**
     * 2D 步长<b>覆盖</b>钩子（可选，默认不覆盖）。
     *
     * <p>给"外部渲染后端"用：阶梯表已经按距离算出 {@code stairsStep}，后端若想接管抽稀级别
     * （例如用自己的视锥/纹理管线口径），返回一个 &gt; 0 的步长；返回 &le; 0 表示"不覆盖，用阶梯表"。</p>
     *
     * <p>这仍然是"只算档位、不负责渲染"，只是把档位的决定权开放给链首的外部后端
     * —— 用户定案的"配置后端链条，优先使用第一有效"就落在这里。</p>
     */
    default int overrideStep2D(int stairsStep, int pixelsW, int pixelsH, int blocksW, int blocksH,
                               double distanceBlocks) {
        return -1;
    }
}
