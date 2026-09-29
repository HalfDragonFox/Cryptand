package com.hdf.cryptand.soc.board;

/**
 * ===== 整屏帧校验（common，纯 Java 零 MC，2026-09-29）=====
 *
 * <p>用户需求：每次帧传输带一个<b>整屏</b>校验值；校验由<b>屏自己</b>算（脏了才算、算完缓存，
 * 多个观看者复用同一份）；客户端**每次更新后**用同一算式对本地整屏再算一次，
 * <b>对得上即可</b>。</p>
 *
 * <h3>算法选择</h3>
 * <p>用户明确要求"性能最快、不保证完全正确"⇒ 用 FNV-1a 风格的 32 位滚动哈希：
 * 4 字节一组做一次乘法，逐字节收尾；无查表、无分支预测失败、单遍 O(n)。
 * <b>刻意不用</b> CRC32（查表/多项式开销）与 SHA/MD5（密码学强度在此无意义）。
 * 它的用途是"快速发现屏内容变了/帧传输没对上"，不是防篡改。</p>
 *
 * <h3>唯一算式</h3>
 * <p>服务端（{@link TrueColorScreen#vram()} 整块）与客户端（同构镜像设备的整块）必须调用
 * <b>同一个方法</b>——这里就是那一处；两侧各自再写一份哈希实现即违反"一套操作"。</p>
 */
public final class ScreenFrameChecksum {

    /** FNV-1a 32 位偏移基准。 */
    private static final int OFFSET_BASIS = 0x811C9DC5;

    /** FNV-1a 32 位素数。 */
    private static final int PRIME = 0x01000193;

    private ScreenFrameChecksum() {
    }

    /**
     * 整屏校验值。
     *
     * @param data   字节缓冲（通常就是设备的 {@code vram()}）
     * @param offset 起始偏移
     * @param length 参与校验的字节数（<b>整屏</b>：{@code device.frameBytes()}）
     */
    public static int of(byte[] data, int offset, int length) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IllegalArgumentException("区间越界：offset=" + offset + " length=" + length
                    + " 缓冲=" + data.length);
        }
        int sum = OFFSET_BASIS;
        final int end = offset + length;
        int i = offset;
        while (i + 4 <= end) {
            final int word = (data[i] & 0xFF)
                    | (data[i + 1] & 0xFF) << 8
                    | (data[i + 2] & 0xFF) << 16
                    | (data[i + 3] & 0xFF) << 24;
            sum = (sum ^ word) * PRIME;
            i += 4;
        }
        while (i < end) {
            sum = (sum ^ (data[i] & 0xFF)) * PRIME;
            i++;
        }
        return sum;
    }

    /** 整块设备的校验值（服务端屏侧与客户端镜像都用这一句）。 */
    public static int of(TrueColorScreen device) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        return of(device.vram(), 0, device.frameBytes());
    }

    /**
     * 客户端侧一行比对：本地整屏算出来的值是否与报文里的一致。
     *
     * <p>{@code expected} 是服务端随帧下发的值。</p>
     */
    public static boolean matches(int expected, TrueColorScreen device) {
        return of(device) == expected;
    }
}
