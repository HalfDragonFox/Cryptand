package com.hdf.cryptand.soc.board;

import java.util.Arrays;

/**
 * ===== 真彩屏设备模型（common，纯 Java 零 MC，2026-09-26）=====
 *
 * <p>用户定案（{@code repo/truetone-screen-design-2026-09-25.md}）：真彩屏 <b>1bpp 起一路到 32bpp</b>，
 * <b>自带一块显示内存（VRAM）</b>，guest "只需要内存写入即可"；对 OC 呈现为普通 screen（字符 + 16 色），
 * 对我们自己的 OS 呈现为**像素设备**；带一个<b>锁</b>，被锁住时宿主**跳过这一帧**（不等待、不阻塞）；
 * 可以被拼合（拼合后分辨率与显存对应增加，锁是**组级**）。</p>
 *
 * <h3>这个类负责什么</h3>
 * <ul>
 *   <li><b>几何</b>：stride = ceil(width × bpp / 8)（1bpp 按位打包、4bpp 半个字节）；整帧字节数；</li>
 *   <li><b>调色板</b>：1/2/4/8bpp 用索引 + 调色板；16/24/32bpp 直色；</li>
 *   <li><b>像素读写</b>：写进 VRAM（模拟内存）并读回解码 —— 越界**不越界写**、非法色深**明确报错**；</li>
 *   <li><b>双形态</b>：{@link Mode#TEXT 文本模式}（字符格 + 前景/背景）与 {@link Mode#GRAPHICS 图形模式}
 *       （像素），两种解释互不串味（模式不对就报错，不静默按另一种解释）；</li>
 *   <li><b>锁与状态</b>：{@link #lock()}/{@link #unlock()} + {@link #renderTick()}：
 *       图形模式下锁着 ⇒ 跳过本帧（{@code skipped} 累计）；文本模式由组件调用驱动，**锁不参与**。</li>
 * </ul>
 *
 * <p>⚠ 与"显存窗口"（{@code DisplayWindow}）的分工：那个是**字符屏三平面**的 guest 内存窗口；
 * 这里是**像素屏**的 VRAM 描述符 + 编解码。两边的"一帧多少字节"都由各自唯一的算式给出。</p>
 */
public final class TrueColorScreen {

    /** 支持/拒绝的色深档位（照真实显卡表示法） */
    public enum Depth {
        B1(1), B2(2), B4(4), B8(8), B16(16), B24(24), B32(32);

        private final int bits;

        Depth(int bits) {
            this.bits = bits;
        }

        public int bits() {
            return bits;
        }

        /** 是否调色板色深（1/2/4/8bpp） */
        public boolean palette() {
            return bits <= 8;
        }

        /** 非法的色深值**明确报错**（不静默取近似） */
        public static Depth of(int bpp) {
            for (final Depth d : values()) {
                if (d.bits == bpp) {
                    return d;
                }
            }
            throw new IllegalArgumentException("不支持的色深：" + bpp + "bpp（支持 1/2/4/8/16/24/32）");
        }
    }

    /** 形态：文本模式（OC 看到的字符屏） / 图形模式（我们自己的像素屏） */
    public enum Mode {
        TEXT, GRAPHICS
    }

    /** 状态快照（给 guest 读 / 给无人化断言看；不静默） */
    public record Status(boolean locked, long frames, long skipped, Mode mode, String error) {
    }

    private final int width;
    private final int height;
    private final Depth depth;
    private final int stride;
    private final byte[] vram;
    private final int[] palette;

    private Mode mode = Mode.TEXT;
    private boolean locked;
    private long frames;
    private long skipped;
    private String error = "";

    /** 字符模式下的字符格（8×8 一格；与像素模式共用同一块设备，只是解释不同） */
    private final int textCols;
    private final int textRows;
    private final char[] text;
    private final int[] textFg;
    private final int[] textBg;

    private TrueColorScreen(int width, int height, Depth depth) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("屏幕尺寸非法：" + width + "x" + height);
        }
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.stride = stride(width, depth.bits());
        this.vram = new byte[stride * height];
        this.palette = new int[depth.palette() ? (1 << depth.bits()) : 0];
        // 默认调色板：一条能从黑到白的灰阶 + 低索引给几个基本色（够用即可，玩家/固件都能改）
        for (int i = 0; i < palette.length; i++) {
            final int level = palette.length <= 2 ? i * 255 : (i * 255) / (palette.length - 1);
            palette[i] = (level << 16) | (level << 8) | level;
        }
        this.textCols = Math.max(1, width / CELL_W);
        this.textRows = Math.max(1, height / CELL_H);
        this.text = new char[textCols * textRows];
        this.textFg = new int[text.length];
        this.textBg = new int[text.length];
        Arrays.fill(this.text, ' ');
    }

    /**
     * 字符格**宽**（像素）。
     *
     * <p>⚠ 这台设备本质是**像素设备**：字符格尺寸不是"另一种分辨率"，而是"TEXT 模式下一个字符占几个像素"的口径。
     * 取 OC 终端的真实参数：一字符格 = {@code CELL_W × CELL_H} = <b>8 × 16</b> 像素
     * （以前是 8×8 正方形 ⇒ 拼宽屏后窗口又宽又扁，真机 2026-09-27 反馈）。</p>
     */
    public static final int CELL_W = 8;

    /** 字符格**高**（像素）：8×8 的字模画在格子的**上 8 行**，下 8 行留背景（换代 8×16 字模时这条不变）。 */
    public static final int CELL_H = 16;

    public static TrueColorScreen of(int width, int height, int bpp) {
        return new TrueColorScreen(width, height, Depth.of(bpp));
    }

    // ==================== 几何（唯一的算式） ====================

    /** 一行多少字节：1bpp 按位打包、4bpp 半个字节 ⇒ 向上取整 */
    public static int stride(int width, int bpp) {
        if (width <= 0) {
            throw new IllegalArgumentException("宽度非法：" + width);
        }
        return (width * bpp + 7) / 8;
    }

    /** 整帧字节数 */
    public static int frameBytes(int width, int height, int bpp) {
        if (height <= 0) {
            throw new IllegalArgumentException("高度非法：" + height);
        }
        return stride(width, bpp) * height;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Depth depth() {
        return depth;
    }

    public int stride() {
        return stride;
    }

    public int frameBytes() {
        return vram.length;
    }

    /** VRAM 本体（宿主侧就是 guest RAM 的映射视图；自测里是假内存） */
    public byte[] vram() {
        return vram;
    }

    // ==================== 调色板 ====================

    public int paletteSize() {
        return palette.length;
    }

    public int paletteColor(int index) {
        if (palette.length == 0) {
            throw new IllegalStateException(bits() + "bpp 是直色，没有调色板");
        }
        if (index < 0 || index >= palette.length) {
            throw new IllegalArgumentException("调色板索引越界：0.." + (palette.length - 1) + "，给了 " + index);
        }
        return palette[index];
    }

    public void setPaletteColor(int index, int rgb) {
        if (palette.length == 0) {
            throw new IllegalStateException(bits() + "bpp 是直色，没有调色板");
        }
        if (index < 0 || index >= palette.length) {
            throw new IllegalArgumentException("调色板索引越界：0.." + (palette.length - 1) + "，给了 " + index);
        }
        palette[index] = rgb & 0xFFFFFF;
    }

    private int bits() {
        return depth.bits();
    }

    // ==================== 模式 ====================

    public Mode mode() {
        return mode;
    }

    /** 切形态（真实显卡的文本/图形模式；切的时候不搬数据，只是换解释） */
    public void setMode(Mode next) {
        if (next == null) {
            throw new IllegalArgumentException("模式不能为 null");
        }
        this.mode = next;
    }

    // ==================== 像素（图形模式） ====================

    /** 写一个像素：调色板色深写**索引** */
    public void setIndex(int x, int y, int index) {
        requireGraphics();
        if (!depth.palette()) {
            throw new IllegalStateException(bits() + "bpp 是直色，请用 setRgb");
        }
        if (index < 0 || index >= palette.length) {
            throw new IllegalArgumentException("索引越界：0.." + (palette.length - 1) + "，给了 " + index);
        }
        writeBits(x, y, index);
    }

    /** 写一个像素：直色写 RGB（16bpp = RGB565 / 24bpp = RGB888 / 32bpp = XRGB8888） */
    public void setRgb(int x, int y, int rgb) {
        requireGraphics();
        if (depth.palette()) {
            throw new IllegalStateException(bits() + "bpp 是调色板色深，请用 setIndex");
        }
        switch (depth) {
            case B16 -> writeBits(x, y, ((rgb >> 19) & 0x1F) << 11 | ((rgb >> 10) & 0x3F) << 5 | ((rgb >> 3) & 0x1F));
            case B24 -> writeBits(x, y, rgb & 0xFFFFFF);
            case B32 -> writeBits(x, y, rgb & 0xFFFFFF);
            default -> throw new IllegalStateException("不支持的直色深度：" + bits());
        }
    }

    /** 读一个像素的**原始值**（调色板 = 索引；直色 = 打包值） */
    public int rawAt(int x, int y) {
        requireGraphics();
        return readBits(x, y);
    }

    /** 读一个像素的 RGB（调色板色深会走调色板解码） */
    public int rgbAt(int x, int y) {
        requireGraphics();
        final int raw = readBits(x, y);
        if (depth.palette()) {
            if (raw < 0 || raw >= palette.length) {
                throw new IllegalStateException("VRAM 里的索引越界：" + raw);
            }
            return palette[raw];
        }
        return switch (depth) {
            case B16 -> {
                final int r = (raw >> 11) & 0x1F;
                final int g = (raw >> 5) & 0x3F;
                final int b = raw & 0x1F;
                yield (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
            }
            default -> raw & 0xFFFFFF;
        };
    }

    /** 是否落在屏内（越界写**不越界**：直接报错，调用方自己裁剪） */
    public boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    private void requireGraphics() {
        if (mode != Mode.GRAPHICS) {
            throw new IllegalStateException("当前是文本模式：像素读写要先 setMode(GRAPHICS)");
        }
    }

    private void writeBits(int x, int y, int value) {
        if (!inside(x, y)) {
            throw new IllegalArgumentException("像素越界：(" + x + ", " + y + ") 不在 " + width + "x" + height + " 内");
        }
        final int row = y * stride;
        switch (bits()) {
            case 1, 2, 4, 8 -> {
                final int perByte = 8 / bits();
                final int byteIndex = row + x / perByte;
                final int slot = x % perByte;                     // 高位在前（与真实位打包一致）
                final int shift = 8 - bits() * (slot + 1);
                final int mask = ((1 << bits()) - 1) << shift;
                vram[byteIndex] = (byte) ((vram[byteIndex] & ~mask) | ((value << shift) & mask));
            }
            case 16 -> {
                final int at = row + x * 2;
                vram[at] = (byte) (value & 0xFF);
                vram[at + 1] = (byte) ((value >> 8) & 0xFF);
            }
            case 24 -> {
                final int at = row + x * 3;
                vram[at] = (byte) (value & 0xFF);
                vram[at + 1] = (byte) ((value >> 8) & 0xFF);
                vram[at + 2] = (byte) ((value >> 16) & 0xFF);
            }
            case 32 -> {
                final int at = row + x * 4;
                vram[at] = (byte) (value & 0xFF);
                vram[at + 1] = (byte) ((value >> 8) & 0xFF);
                vram[at + 2] = (byte) ((value >> 16) & 0xFF);
                vram[at + 3] = (byte) 0xFF;
            }
            default -> throw new IllegalStateException("不支持的色深：" + bits());
        }
    }

    private int readBits(int x, int y) {
        if (!inside(x, y)) {
            throw new IllegalArgumentException("像素越界：(" + x + ", " + y + ") 不在 " + width + "x" + height + " 内");
        }
        final int row = y * stride;
        switch (bits()) {
            case 1, 2, 4, 8 -> {
                final int perByte = 8 / bits();
                final int byteIndex = row + x / perByte;
                final int slot = x % perByte;
                final int shift = 8 - bits() * (slot + 1);
                return (vram[byteIndex] >> shift) & ((1 << bits()) - 1);
            }
            case 16 -> {
                final int at = row + x * 2;
                return (vram[at] & 0xFF) | ((vram[at + 1] & 0xFF) << 8);
            }
            case 24 -> {
                final int at = row + x * 3;
                return (vram[at] & 0xFF) | ((vram[at + 1] & 0xFF) << 8) | ((vram[at + 2] & 0xFF) << 16);
            }
            case 32 -> {
                final int at = row + x * 4;
                return (vram[at] & 0xFF) | ((vram[at + 1] & 0xFF) << 8) | ((vram[at + 2] & 0xFF) << 16);
            }
            default -> throw new IllegalStateException("不支持的色深：" + bits());
        }
    }

    // ==================== 字符（文本模式，OC 看到的那一面） ====================

    public int textCols() {
        return textCols;
    }

    public int textRows() {
        return textRows;
    }

    public void setText(int col, int row, char ch, int fg, int bg) {
        requireText();
        if (col < 1 || row < 1 || col > textCols || row > textRows) {
            throw new IllegalArgumentException("字符格越界：(" + col + ", " + row + ") 不在 "
                    + textCols + "x" + textRows + " 内");
        }
        final int at = (row - 1) * textCols + (col - 1);
        text[at] = ch;
        textFg[at] = fg & 0xFFFFFF;
        textBg[at] = bg & 0xFFFFFF;
    }

    public char textAt(int col, int row) {
        requireText();
        return text[(row - 1) * textCols + (col - 1)];
    }

    public int textForeground(int col, int row) {
        requireText();
        return textFg[(row - 1) * textCols + (col - 1)];
    }

    public int textBackground(int col, int row) {
        requireText();
        return textBg[(row - 1) * textCols + (col - 1)];
    }

    private void requireText() {
        if (mode != Mode.TEXT) {
            throw new IllegalStateException("当前是图形模式：字符读写要先 setMode(TEXT)");
        }
    }

    // ==================== 锁与状态（宿主每 tick 调一次 renderTick） ====================

    public void lock() {
        locked = true;
    }

    public void unlock() {
        locked = false;
    }

    public boolean locked() {
        return locked;
    }

    /**
     * 宿主渲染一拍：**被锁住就跳过这一帧**（不等待、不阻塞；{@code skipped} 累计，guest 据此
     * 判断自己的锁有没有持有过久）。
     *
     * <p>⚠ 锁只作用于**图形模式**：文本模式是 OC 的 {@code gpu.set} 驱动绘制，OC 不知道、
     * 也不该被我们的锁影响（否则会出现"OC 写了却没显示"这种无从排查的现象）。</p>
     *
     * @return 这一拍是否真的渲染了
     */
    public boolean renderTick() {
        if (mode == Mode.GRAPHICS && locked) {
            skipped++;
            return false;
        }
        frames++;
        return true;
    }

    public long frames() {
        return frames;
    }

    public long skipped() {
        return skipped;
    }

    /**
     * 客户端镜像用：把服务端的计数抄过来。
     *
     * <p>为什么要抄：宿主渲染与计数都在**服务端**（{@code renderTick()} 在那边推进），客户端只拿到帧内容 +
     * 计数 —— 渲染器据此判断"这一帧和上一帧是不是同一帧"（帧号没变就不重复上传贴图）。</p>
     */
    public void markSynced(long syncedFrames, long syncedSkipped) {
        this.frames = syncedFrames;
        this.skipped = syncedSkipped;
    }

    /** 记一次失败原因（不静默） */
    public void fail(String message) {
        error = message == null ? "" : message;
    }

    public String error() {
        return error;
    }

    public Status status() {
        return new Status(locked, frames, skipped, mode, error);
    }
}
