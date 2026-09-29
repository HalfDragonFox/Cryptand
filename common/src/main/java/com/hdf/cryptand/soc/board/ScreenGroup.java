package com.hdf.cryptand.soc.board;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ===== 真彩屏拼合（视频墙，common 纯 Java 零 MC，2026-09-26）=====
 *
 * <p>用户定案："彩屏和原版 OC 一样可以被连接拼合，然后分辨率和显存对应增加" +
 * "一个屏幕如果被拼起来算一个"（占 GPU 的**一个通道**）。</p>
 *
 * <h3>规则</h3>
 * <ul>
 *   <li><b>显存是分布式的</b>：每块屏各持自己的 VRAM，各块之和 = 组的总显存
 *       ⇒ "分辨率和显存对应增加"自然成立（不额外分配一张大表面）；</li>
 *   <li><b>锁是组级的</b>：写任意一块的锁 = 锁整组；宿主渲染时整组跳过，{@code skipped} 记在组上。
 *       不做单块子锁 —— 那会让"一帧里有的块刷新了、有的没刷新"，画面撕裂且难以解释；</li>
 *   <li><b>非法拼合明确拒绝</b>（重叠、不连续/有缝、id 重复、偏移为负）并给出原因 ——
 *       绝不静默降级成单屏。</li>
 * </ul>
 */
public final class ScreenGroup {

    /** 一块屏在组里的位置（偏移 = 它的左上角在组坐标里的位置） */
    public record Block(String id, TrueColorScreen screen, int offsetX, int offsetY) {
    }

    /** 布局计算结果（不做合法性判断 —— 非法拼合要能先算出来再被拒绝，错误信息才具体） */
    public record Bounds(int width, int height) {
    }

    private final List<Block> blocks;
    private final int width;
    private final int height;
    private final long[] counter = new long[2];        // [0] = frames, [1] = skipped

    private ScreenGroup(List<Block> blocks, int width, int height) {
        this.blocks = List.copyOf(blocks);
        this.width = width;
        this.height = height;
    }

    /**
     * 布局计算：总分辨率 = 各块偏移 + 尺寸的最大值（对 2×1 / 1×2 / 2×2 / L 形 / 不同尺寸都成立）。
     */
    public static Bounds layout(List<Block> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            throw new IllegalArgumentException("空的屏组：至少要有一块屏");
        }
        int w = 0;
        int h = 0;
        for (final Block b : blocks) {
            if (b.screen() == null) {
                throw new IllegalArgumentException("屏 " + b.id() + " 没有设备");
            }
            if (b.offsetX() < 0 || b.offsetY() < 0) {
                throw new IllegalArgumentException("屏 " + b.id() + " 的偏移为负：" + b.offsetX() + "," + b.offsetY());
            }
            w = Math.max(w, b.offsetX() + b.screen().width());
            h = Math.max(h, b.offsetY() + b.screen().height());
        }
        return new Bounds(w, h);
    }

    /**
     * 建组：算布局 + **合法性校验**。
     *
     * @throws IllegalStateException 重叠 / 有缝（不是完整矩形）/ 形态不一致
     */
    public static ScreenGroup of(List<Block> blocks) {
        final Bounds bounds = layout(blocks);
        final Set<String> ids = new HashSet<>();
        for (final Block b : blocks) {
            if (!ids.add(b.id())) {
                throw new IllegalStateException("屏 id 重复：" + b.id());
            }
        }
        // 两两不重叠
        for (int i = 0; i < blocks.size(); i++) {
            for (int j = i + 1; j < blocks.size(); j++) {
                if (overlaps(blocks.get(i), blocks.get(j))) {
                    throw new IllegalStateException("拼合重叠：屏 " + blocks.get(i).id()
                            + " 与 " + blocks.get(j).id() + " 的矩形相交");
                }
            }
        }
        // 无缝：面积之和 == 外接矩形面积 ⇒ 恰好铺满（不连续就会出现空洞或越界）
        long area = 0;
        for (final Block b : blocks) {
            area += (long) b.screen().width() * b.screen().height();
        }
        final long box = (long) bounds.width() * bounds.height();
        if (area != box) {
            throw new IllegalStateException("拼合不连续（有缝或超出边界）：各块面积和 " + area
                    + " ≠ 外接矩形 " + bounds.width() + "x" + bounds.height() + " = " + box);
        }
        final TrueColorScreen.Mode mode = blocks.get(0).screen().mode();
        for (final Block b : blocks) {
            if (b.screen().mode() != mode) {
                throw new IllegalStateException("拼合里的屏形态不一致：屏 " + b.id()
                        + " 是 " + b.screen().mode() + "，组里其余是 " + mode);
            }
        }
        return new ScreenGroup(blocks, bounds.width(), bounds.height());
    }

    private static boolean overlaps(Block a, Block b) {
        return a.offsetX() < b.offsetX() + b.screen().width()
                && b.offsetX() < a.offsetX() + a.screen().width()
                && a.offsetY() < b.offsetY() + b.screen().height()
                && b.offsetY() < a.offsetY() + a.screen().height();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public List<Block> blocks() {
        return blocks;
    }

    /** 组坐标 → 归属的那块屏（越界返回 null，调用方自己决定报错还是忽略） */
    public Block blockAt(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return null;
        }
        for (final Block b : blocks) {
            if (x >= b.offsetX() && x < b.offsetX() + b.screen().width()
                    && y >= b.offsetY() && y < b.offsetY() + b.screen().height()) {
                return b;
            }
        }
        return null;                                    // 合法组里不该发生（有缝会被 of() 拒掉）
    }

    /** 组坐标 → 该块屏内的局部坐标（越界返回 null） */
    public int[] localOf(int x, int y) {
        final Block b = blockAt(x, y);
        return b == null ? null : new int[]{x - b.offsetX(), y - b.offsetY()};
    }

    /** 在组坐标上写**直色**像素（分派到对应块 —— 跨块写因此天然正确） */
    public void setRgb(int x, int y, int rgb) {
        final int[] local = requireLocal(x, y);
        blockAt(x, y).screen().setRgb(local[0], local[1], rgb);
    }

    /** 在组坐标上写**调色板索引**像素（调色板色深的组用这个） */
    public void setIndex(int x, int y, int index) {
        final int[] local = requireLocal(x, y);
        blockAt(x, y).screen().setIndex(local[0], local[1], index);
    }

    private int[] requireLocal(int x, int y) {
        final int[] local = localOf(x, y);
        if (local == null) {
            throw new IllegalArgumentException("组坐标越界：(" + x + ", " + y + ") 不在 " + width + "x" + height + " 内");
        }
        return local;
    }

    // ==================== 组级锁 ====================

    /** 锁整组（任意一块写锁 = 整组锁住） */
    public void lock() {
        for (final Block b : blocks) {
            b.screen().lock();
        }
    }

    public void unlock() {
        for (final Block b : blocks) {
            b.screen().unlock();
        }
    }

    public boolean locked() {
        for (final Block b : blocks) {
            if (b.screen().locked()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 组渲染一拍：**任一块锁着 ⇒ 整组跳过**（{@code skipped} 记在组上，各块不单独记）。
     *
     * @return 整组这一拍是否真的渲染了
     */
    public boolean renderTick() {
        if (locked()) {
            counter[1]++;
            return false;
        }
        counter[0]++;
        return true;
    }

    public long frames() {
        return counter[0];
    }

    public long skipped() {
        return counter[1];
    }

    /** 一行摘要（日志/UI/无人化断言） */
    public String summary() {
        final StringBuilder sb = new StringBuilder("screen group " + width + "x" + height + " ("
                + blocks.size() + " blocks:");
        for (final Block b : blocks) {
            sb.append(' ').append(b.id()).append('@').append(b.offsetX()).append(',').append(b.offsetY());
        }
        return sb.append(')').toString();
    }

    /** 组里各块之和 = 组的总显存（"分辨率和显存对应增加"） */
    public int totalVramBytes() {
        int sum = 0;
        for (final Block b : blocks) {
            sum += b.screen().frameBytes();
        }
        return sum;
    }

    /** 诊断用的块清单副本（不可变） */
    public List<String> blockIds() {
        final List<String> out = new ArrayList<>(blocks.size());
        for (final Block b : blocks) {
            out.add(b.id());
        }
        return List.copyOf(out);
    }
}
