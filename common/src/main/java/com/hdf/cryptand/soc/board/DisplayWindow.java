package com.hdf.cryptand.soc.board;

import com.hdf.cryptand.soc.api.Sizes;
import com.hdf.cryptand.soc.device.ByteWindowDevice;

/**
 * ===== 显存窗口协议（common，纯 Java 零 MC，2026-09-18）=====
 *
 * <p>现实里 CPU 刷屏不是"逐字符调 GPU 方法"，而是<b>写显存</b>（PCIe BAR 映射的窗口 / DMA），
 * 由 GPU 的显示控制器扫描输出。这里就把"显存"做成 guest RAM 里的一段窗口：</p>
 *
 * <pre>
 *   +0x00 magic 'CVRM'     宿主机校验
 *   +0x04 cols / +0x08 rows 分辨率（字符格数）
 *   +0x0C frame_seq         ★ 门铃：固件写完一帧 +1，宿主比对它判断有没有新帧
 *   +0x10 dirty            脏区（x,y,w,h 各 8 位打包；0 = 全屏）
 *   +0x14 format           0 = 字符三平面（code/fg/bg）；1 = RGB565（真彩）
 *   +0x18 backend          宿主回写：实际用了 CPU 还是 GPU（固件读它打 UART 供断言）
 *   +0x1C flags            bit0 允许 GPU / bit1 强制 CPU
 *   +0x20 数据面           字符：code[cols*rows] + fg[...] + bg[...]
 * </pre>
 *
 * <p>🔴 窗口必须落在 native 自持 RAM 范围内，否则每次字符写都变成一次 MMIO 往返（≈0.44ms）
 * —— 见 {@code OcAbi.MAILBOX_BASE} 那段说明。</p>
 *
 * <p>宿主侧只做两件事：比对门铃 → 取整屏；然后按能力上屏（有 GPU 走显存页 + 一次 blit，
 * 没有则 CPU 手动刷新）。固件侧只做两件事：写窗口、门铃 +1。</p>
 */
public final class DisplayWindow {

    /** 魔数 'CVRM'（Cryptand VRAM） */
    public static final int MAGIC = 0x4356524D;
    /** 控制块大小 */
    public static final int HEADER_BYTES = 0x20;

    public static final int OFF_MAGIC = 0x00;
    public static final int OFF_COLS = 0x04;
    public static final int OFF_ROWS = 0x08;
    public static final int OFF_FRAME_SEQ = 0x0C;
    public static final int OFF_DIRTY = 0x10;
    public static final int OFF_FORMAT = 0x14;
    public static final int OFF_BACKEND = 0x18;
    public static final int OFF_FLAGS = 0x1C;

    /** 字符三平面（码点 / 前景 / 背景）——与 OC 的 TextBuffer 结构对齐 */
    public static final int FORMAT_TEXT = 0;
    /** 真彩 RGB565 */
    public static final int FORMAT_RGB565 = 1;

    public static final int BACKEND_CPU = 0;
    public static final int BACKEND_GPU = 1;

    public static final int FLAG_ALLOW_GPU = 1;
    public static final int FLAG_FORCE_CPU = 1 << 1;

    private DisplayWindow() {
    }

    /** 字符模式窗口总字节数（控制块 + 三平面） */
    public static int textWindowBytes(int cols, int rows) {
        if (cols <= 0 || rows <= 0) {
            throw new IllegalArgumentException("cols/rows must be > 0");
        }
        return HEADER_BYTES + 3 * cols * rows;
    }

    /** 真彩窗口总字节数（控制块 + w*h*2 字节 RGB565） */
    public static int rgb565WindowBytes(int cols, int rows) {
        if (cols <= 0 || rows <= 0) {
            throw new IllegalArgumentException("cols/rows must be > 0");
        }
        return HEADER_BYTES + cols * rows * 2;
    }

    /** 一帧（扫描器取出来的内容） */
    public record Frame(int cols, int rows, int format, int seq, byte[] code, byte[] fg, byte[] bg) {
    }

    /**
     * 从一段**原始窗口字节**里解析一帧（不依赖 MMIO 设备）。
     *
     * <p>为什么需要它：显存窗口在真机上**就是 guest RAM 的一段**（VRAM 与内存同占用），
     * 宿主用 {@code CpuCore.readMemory} 取出来本来就是 byte[] —— 解析逻辑只该有一份，
     * 所以 {@link Scanner} 也走这里。</p>
     *
     * @throws IllegalStateException 魔数不对 / 尺寸非法 / 格式不支持 / 容量不够
     */
    public static Frame parse(byte[] window) {
        if (window == null || window.length < HEADER_BYTES) {
            throw new IllegalStateException("显存窗口太小：至少 " + HEADER_BYTES + " 字节，实际 "
                    + (window == null ? 0 : window.length));
        }
        final int magic = rd32(window, OFF_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalStateException("显存窗口魔数不对：0x" + Integer.toHexString(magic)
                    + "（固件还没初始化窗口？）");
        }
        final int cols = rd32(window, OFF_COLS);
        final int rows = rd32(window, OFF_ROWS);
        final int format = rd32(window, OFF_FORMAT);
        final int seq = rd32(window, OFF_FRAME_SEQ);
        if (cols <= 0 || rows <= 0) {
            throw new IllegalStateException("显存窗口尺寸非法：" + cols + "x" + rows);
        }
        if (format != FORMAT_TEXT) {
            throw new IllegalStateException("目前只支持字符三平面（format=" + format + "）；真彩上屏待接");
        }
        final int plane = cols * rows;
        if (HEADER_BYTES + 3 * plane > window.length) {
            throw new IllegalStateException("显存窗口装不下整屏：需要 " + (HEADER_BYTES + 3 * plane)
                    + " 字节，实际 " + window.length);
        }
        final byte[] code = java.util.Arrays.copyOfRange(window, HEADER_BYTES, HEADER_BYTES + plane);
        final byte[] fg = java.util.Arrays.copyOfRange(window, HEADER_BYTES + plane,
                HEADER_BYTES + 2 * plane);
        final byte[] bg = java.util.Arrays.copyOfRange(window, HEADER_BYTES + 2 * plane,
                HEADER_BYTES + 3 * plane);
        return new Frame(cols, rows, format, seq, code, fg, bg);
    }

    /**
     * ===== 图形面（程序"直接画图像"）那一帧：{@link #FORMAT_RGB565}（2026-09-27 任务 G）=====
     *
     * <p>与字符帧（{@link Frame}，三平面）并列的第二种内容 —— 它表达的是"<b>程序画了什么</b>"：
     * 字符帧是程序在画字符，本帧是程序在画图像。至于这一帧最终<b>直接画像素</b>还是<b>转成字符</b>，
     * 由虚拟机（GPU/屏链路）按目标屏的能力自动决定，见
     * {@code com.hdf.cryptand.soc.board.ScreenOutputFace}（唯一判定规则）。</p>
     *
     * <p><b>像素字节序</b>：每像素 2 字节 <b>小端</b>，与 {@link TrueColorScreen} 的 16bpp VRAM 布局
     * <b>逐字节一致</b>（{@code TrueColorScreen.writeBits} 的 {@code case 16}）⇒ 目标是真彩屏时
     * 宿主侧可以整块 {@code arraycopy} 进设备 VRAM，<b>不需要任何像素格式转换</b>（少一条换算就少一类错）。
     * 需要按像素读颜色时走设备自己的 {@code rgbAt}（RGB565 展开只有那一处实现）。</p>
     *
     * @param width  像素宽（写在窗口 {@link #OFF_COLS}）
     * @param height 像素高（写在窗口 {@link #OFF_ROWS}）
     * @param seq    门铃（与字符帧同一个 {@link #OFF_FRAME_SEQ}）
     * @param rgb565 像素字节（长度 = width × height × 2）
     */
    public record ImageFrame(int width, int height, int seq, byte[] rgb565) {
    }

    /** 窗口控制块里的格式字段（只要读 32 字节控制块就能判"程序画的是字符还是图像"）。 */
    public static int formatOf(byte[] window) {
        if (window == null || window.length < HEADER_BYTES) {
            throw new IllegalStateException("显存窗口太小：至少 " + HEADER_BYTES + " 字节，实际 "
                    + (window == null ? 0 : window.length));
        }
        final int magic = rd32(window, OFF_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalStateException("显存窗口魔数不对：0x" + Integer.toHexString(magic)
                    + "（固件还没初始化窗口？）");
        }
        return rd32(window, OFF_FORMAT);
    }

    /**
     * 从窗口字节里解析**图形面**帧（{@link #FORMAT_RGB565}）。
     *
     * <p>字符帧请走 {@link #parse}：两条路各自校验自己的容量（同一套"一帧多少字节"的算式
     * {@link #rgb565WindowBytes}／{@link #textWindowBytes}），不互相兜底。</p>
     *
     * @throws IllegalStateException 魔数不对 / 格式不是 RGB565 / 尺寸非法 / 容量不够
     */
    public static ImageFrame parseImage(byte[] window) {
        if (window == null || window.length < HEADER_BYTES) {
            throw new IllegalStateException("显存窗口太小：至少 " + HEADER_BYTES + " 字节，实际 "
                    + (window == null ? 0 : window.length));
        }
        final int magic = rd32(window, OFF_MAGIC);
        if (magic != MAGIC) {
            throw new IllegalStateException("显存窗口魔数不对：0x" + Integer.toHexString(magic)
                    + "（固件还没初始化窗口？）");
        }
        final int format = rd32(window, OFF_FORMAT);
        if (format != FORMAT_RGB565) {
            throw new IllegalStateException("这一帧不是图形面：format=" + format
                    + "（图形面 = " + FORMAT_RGB565 + "；字符三平面请用 DisplayWindow.parse）");
        }
        final int width = rd32(window, OFF_COLS);
        final int height = rd32(window, OFF_ROWS);
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("图形面尺寸非法：" + width + "x" + height);
        }
        final int need = rgb565WindowBytes(width, height);
        if (window.length < need) {
            throw new IllegalStateException("显存窗口装不下这一帧图形面：需要 " + need
                    + " 字节，实际 " + window.length);
        }
        final byte[] pixels = java.util.Arrays.copyOfRange(window, HEADER_BYTES, HEADER_BYTES
                + width * height * 2);
        return new ImageFrame(width, height, rd32(window, OFF_FRAME_SEQ), pixels);
    }

    /**
     * 读控制块里的一个 32 位字段（小端，与固件/内核字节序一致）。
     *
     * <p>宿主"先比门铃、再取整屏"的第一步就靠它：只读 32 字节控制块即可判断有没有新帧，
     * 不必把整屏（可能几十 KB）搬过来。</p>
     */
    public static int read32(byte[] window, int offset) {
        if (window == null || offset < 0 || offset + 4 > window.length) {
            throw new IllegalArgumentException("读控制块越界：offset=" + offset
                    + "，窗口 " + (window == null ? 0 : window.length) + " 字节");
        }
        return rd32(window, offset);
    }

    /** 小端读 32 位（与固件/内核的字节序一致） */
    private static int rd32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /**
     * 宿主侧扫描器：**门铃变了才搬整屏**（这就是"主机只做同步、不做逐字符交互"的落点）。
     */
    public static final class Scanner {

        private final ByteWindowDevice window;
        private int lastSeq = -1;
        private int scannedFrames;

        public Scanner(ByteWindowDevice window) {
            if (window == null) {
                throw new IllegalArgumentException("window must not be null");
            }
            this.window = window;
        }

        /** 当前门铃值 */
        public int frameSeq() {
            return (int) window.load(OFF_FRAME_SEQ, Sizes.SIZE_32);
        }

        /** 有没有新帧（门铃与上次不同） */
        public boolean dirty() {
            return frameSeq() != lastSeq;
        }

        /** 已经取过多少帧 */
        public int scannedFrames() {
            return scannedFrames;
        }

        /**
         * 有新帧就取整屏；**没有则返回 null**（不重复搬运）。
         *
         * @throws IllegalStateException 魔数不对 / 尺寸非法 / 格式不支持
         */
        public Frame scanIfDirty() {
            final int seq = frameSeq();
            if (seq == lastSeq) {
                return null;
            }
            // 导出整段窗口字节后走**同一份解析**（parse）—— 真机上窗口可能就在 guest RAM 里，
            // 那时宿主拿到的本来就是 byte[]，解析逻辑只该有一份。
            final byte[] raw = new byte[window.getLength()];
            for (int i = 0; i < raw.length; i++) {
                raw[i] = window.get(i);
            }
            final Frame frame = parse(raw);
            lastSeq = seq;
            scannedFrames++;
            return frame;
        }

        /** 宿主回写"这次实际用了哪个后端"（固件读它打 UART，供无人化断言） */
        public void reportBackend(int backend) {
            window.store(OFF_BACKEND, backend, Sizes.SIZE_32);
        }

        /** 宿主读固件的 flags（是否允许 GPU） */
        public int flags() {
            return (int) window.load(OFF_FLAGS, Sizes.SIZE_32);
        }
    }
}
