package com.hdf.cryptand.soc.board;

/**
 * ===== 屏幕帧体压缩（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>用户定案：「这个屏 **RLE 或者 LZ4** 之类的算法压缩传输」。</p>
 *
 * <p>为什么先做 RLE 而不是 LZ4：屏幕内容是"大片同色 + 少量字模像素"（终端形态），
 * RLE 对这种数据又快又紧，而且是<b>零依赖、纯 Java、可离线验伪</b>的；
 * LZ4 需要外部实现（或自己写一份），留作后续可选算法（本类按算法号分派，加 LZ4 只需加一个分支）。</p>
 *
 * <p>编码格式（小端无关，直接按 Java 字节序写）：</p>
 * <pre>
 *   重复若干次 { varint runLength, 4 字节 ARGB }
 * </pre>
 * <p>varint 用 7 位一组、最高位续接（和 protobuf 一样）：终端画面一个 run 常有几百个像素，
 * 2~3 字节就够，而固定 4 字节会让"同色长串"白付 1 字节的账。</p>
 *
 * <p>⚠ 调用方必须比较 {@code encoded.length} 与原始字节数，<b>压不动就用 NONE</b>——
 * 随机画面（图形模式照片）RLE 会膨胀到 2 倍，绝不能无条件发压缩体。</p>
 */
public final class FrameCompression {

    /** 不压缩（原样像素）。 */
    public static final int ALGORITHM_NONE = 0;

    /** RLE（本类实现）。 */
    public static final int ALGORITHM_RLE = 1;

    /** zlib（交给 MC 通道的 CompressedPacketBuilder，或将来自己压）。 */
    public static final int ALGORITHM_ZLIB = 2;

    /** LZ4（预留：需要实现或引入；配置里已可写，未实现时明确报错而不是静默降级）。 */
    public static final int ALGORITHM_LZ4 = 3;

    private FrameCompression() {
    }

    /** 算法名（日志/诊断用）。 */
    public static String algorithmName(int algorithm) {
        switch (algorithm) {
            case ALGORITHM_NONE:
                return "none";
            case ALGORITHM_RLE:
                return "rle";
            case ALGORITHM_ZLIB:
                return "zlib";
            case ALGORITHM_LZ4:
                return "lz4";
            default:
                return "unknown(" + algorithm + ")";
        }
    }

    /**
     * ===== auto：按数据特征选算法（用户定案：配置可 auto 或强制指定后端）=====
     *
     * <p>判据来自实测（`:common:runScreenFrameWireBenchmark`，400x256 一帧 = 409600 B）：</p>
     * <pre>
     *   终端画面（逐格异色）: zlib 5513 B/2.99ms   RLE 64000 B/2.09ms
     *   整行同色            : zlib 8941 B/5.39ms   RLE 45558 B/0.88ms
     *   随机像素（图形最坏）: zlib 351287 B/22.72ms RLE 512000 B(125%,膨胀)/1.39ms
     * </pre>
     *
     * <p>规律：<b>zlib 总是更紧、RLE 总是更快</b>。所以 auto 分三档：</p>
     * <ul>
     *   <li>RLE 体 < 原始一半 ⇒ 内容"高度可 RLE"（终端画面）⇒ 选 <b>RLE</b>（省 CPU，体积也够小）</li>
     *   <li>RLE 体 ≥ 原始 ⇒ 接近随机 ⇒ 选 <b>NONE</b>（此时 zlib 要 20ms+ 却只省 14%，不值得占主线程）</li>
     *   <li>中间地带 ⇒ 选 <b>ZLIB</b>（紧优先，代价可接受）</li>
     * </ul>
     *
     * @param rawBytes 原始字节数（= 像素数 × 4）
     * @param rleBytes 已算好的 RLE 体长度
     */
    public static int pickAuto(int rawBytes, int rleBytes) {
        if (rawBytes <= 0) {
            return ALGORITHM_NONE;
        }
        if (rleBytes * 2 < rawBytes) {
            return ALGORITHM_RLE;
        }
        if (rleBytes >= rawBytes) {
            return ALGORITHM_NONE;
        }
        return ALGORITHM_ZLIB;
    }

    /**
     * 把配置里的名字解析成算法号。
     *
     * <p>用户定案：配置可写 `auto` 或强制后端。**不认识的写法必须明确报错**，
     * 不能静默降级成默认值 —— 否则用户以为设了 lz4 其实在跑别的（这种"看不见的兜底"是事故温床）。</p>
     */
    public static int parseAlgorithm(String configured) {
        if (configured == null) {
            throw new IllegalArgumentException("压缩算法配置不能为 null");
        }
        switch (configured.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "auto":
                return -1;                 // 调用方按 pickAuto 决定
            case "none":
                return ALGORITHM_NONE;
            case "rle":
                return ALGORITHM_RLE;
            case "zlib":
                return ALGORITHM_ZLIB;
            case "lz4":
                return ALGORITHM_LZ4;
            default:
                throw new IllegalArgumentException("未知压缩算法：" + configured
                        + "（可选 auto / none / rle / zlib / lz4）");
        }
    }

    /**
     * RLE 编码一段 ARGB 像素。
     *
     * @param pixels 像素数组（ARGB）
     * @param offset 起点
     * @param count  像素数
     */
    public static byte[] encodeRle(int[] pixels, int offset, int count) {
        if (pixels == null) {
            throw new IllegalArgumentException("pixels 不能为 null");
        }
        if (offset < 0 || count < 0 || offset + count > pixels.length) {
            throw new IllegalArgumentException("范围越界：offset=" + offset + " count=" + count
                    + " length=" + pixels.length);
        }
        // 先数 run，再按需分配（避免用会溢出的"最大长度"预分配）
        int runs = 0;
        int i = 0;
        while (i < count) {
            final int value = pixels[offset + i];
            int run = 1;
            while (i + run < count && pixels[offset + i + run] == value) {
                run++;
            }
            runs++;
            i += run;
        }
        final byte[] out = new byte[runs * 5 + runs * 4];   // 上界：每 run 最多 5 字节 varint + 4 字节值
        int at = 0;
        i = 0;
        while (i < count) {
            final int value = pixels[offset + i];
            int run = 1;
            while (i + run < count && pixels[offset + i + run] == value) {
                run++;
            }
            at = writeVarInt(out, at, run);
            out[at++] = (byte) (value >>> 24);
            out[at++] = (byte) (value >>> 16);
            out[at++] = (byte) (value >>> 8);
            out[at++] = (byte) value;
            i += run;
        }
        final byte[] exact = new byte[at];
        System.arraycopy(out, 0, exact, 0, at);
        return exact;
    }

    /**
     * RLE 解码。
     *
     * @param data          编码体
     * @param expectedCount 期望像素数（**严格校验**：长度不符就是报文坏了，明确抛错而不是静默补零）
     */
    public static int[] decodeRle(byte[] data, int expectedCount) {
        if (data == null) {
            throw new IllegalArgumentException("data 不能为 null");
        }
        if (expectedCount < 0) {
            throw new IllegalArgumentException("expectedCount 不能为负：" + expectedCount);
        }
        final int[] out = new int[expectedCount];
        int at = 0;
        int produced = 0;
        while (at < data.length) {
            final int[] varint = readVarInt(data, at);
            final int run = varint[0];
            at = varint[1];
            if (run <= 0) {
                throw new IllegalArgumentException("run 长度非正：" + run);
            }
            if (at + 4 > data.length) {
                throw new IllegalArgumentException("像素值截断：at=" + at + " length=" + data.length);
            }
            final int value = ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                    | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
            at += 4;
            if (produced + run > expectedCount) {
                throw new IllegalArgumentException("run 溢出：produced=" + produced
                        + " run=" + run + " expected=" + expectedCount);
            }
            for (int k = 0; k < run; k++) {
                out[produced + k] = value;
            }
            produced += run;
        }
        if (produced != expectedCount) {
            throw new IllegalArgumentException("像素数不符：解出 " + produced + "，期望 " + expectedCount);
        }
        return out;
    }

    /**
     * 选压缩方式：压不动（不比原样小）就返回 {@link #ALGORITHM_NONE}。
     *
     * @return 实际应使用的算法号
     */
    public static int pickAlgorithm(int[] pixels, int offset, int count, byte[] encodedRle) {
        final long rawBytes = (long) count * 4L;
        if (encodedRle != null && encodedRle.length < rawBytes) {
            return ALGORITHM_RLE;
        }
        return ALGORITHM_NONE;
    }

    private static int writeVarInt(byte[] out, int at, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out[at++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[at++] = (byte) v;
        return at;
    }

    /** @return {值, 新下标} */
    private static int[] readVarInt(byte[] data, int at) {
        int value = 0;
        int shift = 0;
        while (true) {
            if (at >= data.length) {
                throw new IllegalArgumentException("varint 截断：at=" + at);
            }
            final int b = data[at++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return new int[]{value, at};
            }
            shift += 7;
            if (shift > 28) {
                throw new IllegalArgumentException("varint 过长（超过 int）");
            }
        }
    }
}
