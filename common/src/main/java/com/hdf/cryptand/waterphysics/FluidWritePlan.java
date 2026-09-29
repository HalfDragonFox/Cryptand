package com.hdf.cryptand.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;

/**
 * 写意图（核心 → 主线程的唯一载体）。
 *
 * <p>三件事分开记：
 * <ul>
 *   <li>水位变更（可任意切分、可丢弃最早的一条——水位是派生量）；</li>
 *   <li>方块状态变更（跨 0 边界才产生；<b>必须整格原子</b>，不可拆）；</li>
 *   <li>拓扑事件（源/汇出现或消失 → 网络重建，由上层另行处理）。</li>
 * </ul>
 *
 * <p>带消费游标：主线程按每 tick 预算 {@link #consumeLevels(int, LevelSink)} 分帧消费，
 * 没消费完的留到下一 tick（这就是「主线程写有上限」的落点）。
 *
 * <p>坐标打包与 Minecraft 的 BlockPos.asLong 位布局一致（x 26 | z 26 | y 12），
 * 因此 neoforge 侧可直接 BlockPos.of(long) 还原。
 */
public final class FluidWritePlan {

    /** 水位变更接收器。 */
    public interface LevelSink {
        /** 绝对水位（本 region 内的格：plan 里记的就是最终值）。 */
        void accept(long packedPos, int level);

        /**
         * <b>跨 region 的转移（增量）</b>：写回侧按「该格当前水位 + delta」应用。
         *
         * <p>★ 跨 region 的落点必须是增量：plan 会在写回队列里跨 tick，
         * 期间目标格可能被它自己 region 的任务改写 —— 若用快照基数算出的绝对值去写，会把那些写入覆盖掉。
         */
        void acceptDelta(long packedPos, int delta);
    }

    /** 什么都不做的 sink：只推进消费游标（写回队列的「消费干净才算完成」测试用）。 */
    public static final LevelSink IGNORE = new LevelSink() {
        @Override
        public void accept(final long packedPos, final int level) {
        }

        @Override
        public void acceptDelta(final long packedPos, final int delta) {
        }
    };

    /**
     * 只关心「哪些格被写」的 sink（绝对值与增量一视同仁）。
     *
     * <p>注意：{@link #addLevel} / {@link #addLevelDelta} 的<b>值</b>语义不同
     * （绝对值 vs 增量），写回侧必须自己按 {@code FluidApplier} 的口径处理；
     * 这个工厂只适合「只数格数/只收集坐标」的场合。
     */
    public static LevelSink positions(final java.util.function.LongConsumer into) {
        return new LevelSink() {
            @Override
            public void accept(final long packedPos, final int level) {
                into.accept(packedPos);
            }

            @Override
            public void acceptDelta(final long packedPos, final int delta) {
                into.accept(packedPos);
            }
        };
    }

    /** 方块状态变更接收器。 */
    public interface PosSink {
        void accept(long packedPos);
    }

    private long[] levelPos = new long[64];
    private byte[] levelValue = new byte[64];
    /** 每条是「绝对值」还是「增量」：0 = 绝对值，1 = 增量（跨 region 转移）。 */
    private byte[] levelIsDelta = new byte[64];
    private int levelCount;
    private int levelCursor;

    private long[] blockPos = new long[64];
    private int blockCount;
    private int blockCursor;

    // ---------- 坐标打包（与 BlockPos.asLong 一致） ----------

    /**
     * 打包的方块坐标 → 它所在 <b>section</b> 的键（与 {@code WaterLevelStore.sectionKeyOf} 同源）。
     *
     * <p>★ section 只是<b>侧表</b>的键，不是采集 / 求解 / 写回的边界：阶段 2b 的片可以任意跨
     * section，写回时整片一份 plan 一次落地（片是原子单位，2026-09-29 用户口径）。
     */
    public static long sectionKeyOf(final long packedBlockPos) {
        return SectionCursor.key(unpackX(packedBlockPos) >> 4, unpackY(packedBlockPos) >> 4,
                unpackZ(packedBlockPos) >> 4);
    }

    public static long packPos(final int x, final int y, final int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    public static int unpackX(final long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(final long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int unpackZ(final long packed) {
        return (int) (packed << 26 >> 38);
    }

    // ---------- 产出（核心侧） ----------

    public void addLevel(final long packedPos, final int level) {
        if (levelCount == levelPos.length) {
            levelPos = java.util.Arrays.copyOf(levelPos, levelCount * 2);
            levelValue = java.util.Arrays.copyOf(levelValue, levelCount * 2);
            levelIsDelta = java.util.Arrays.copyOf(levelIsDelta, levelCount * 2);
        }
        levelPos[levelCount] = packedPos;
        levelValue[levelCount] = (byte) level;
        levelIsDelta[levelCount] = 0;
        levelCount++;
    }

    public void addLevel(final int x, final int y, final int z, final int level) {
        addLevel(packPos(x, y, z), level);
    }

    /** 跨 region 的转移：记的是**增量**（写回时加到该格现值上），不是最终值。 */
    public void addLevelDelta(final long packedPos, final int delta) {
        if (delta == 0) {
            return;
        }
        if (levelCount == levelPos.length) {
            levelPos = java.util.Arrays.copyOf(levelPos, levelCount * 2);
            levelValue = java.util.Arrays.copyOf(levelValue, levelCount * 2);
            levelIsDelta = java.util.Arrays.copyOf(levelIsDelta, levelCount * 2);
        }
        levelPos[levelCount] = packedPos;
        levelValue[levelCount] = (byte) delta;
        levelIsDelta[levelCount] = 1;
        levelCount++;
    }

    public void addLevelDelta(final int x, final int y, final int z, final int delta) {
        addLevelDelta(packPos(x, y, z), delta);
    }

    public void addBlock(final long packedPos) {
        if (blockCount == blockPos.length) {
            blockPos = java.util.Arrays.copyOf(blockPos, blockCount * 2);
        }
        blockPos[blockCount++] = packedPos;
    }

    public void addBlock(final int x, final int y, final int z) {
        addBlock(packPos(x, y, z));
    }

    /** 该坐标是否已在方块变更列表里（去重）。 */
    public boolean hasBlock(final long packedPos) {
        for (int i = blockCursor; i < blockCount; i++) {
            if (blockPos[i] == packedPos) {
                return true;
            }
        }
        return false;
    }

    // ---------- 消费（主线程侧，分帧） ----------

    public int levelCount() {
        return levelCount;
    }

    public int blockCount() {
        return blockCount;
    }

    public int pendingLevels() {
        return levelCount - levelCursor;
    }

    public int pendingBlocks() {
        return blockCount - blockCursor;
    }

    public boolean hasPending() {
        return pendingLevels() > 0 || pendingBlocks() > 0;
    }

    /**
     * 按预算消费水位变更。
     *
     * @return 本次消费条数
     */
    public int consumeLevels(final int budget, final LevelSink sink) {
        if (budget < 0) {
            throw new IllegalArgumentException("budget must be >= 0: " + budget);
        }
        // ★ 不许写成 Math.min(levelCount, levelCursor + budget)：游标非 0 时 budget = Integer.MAX_VALUE
        //   会让 levelCursor + budget 溢出成负数 ⇒ Math.min 取到负数 ⇒ while 一条都不走 ⇒ 返回 0
        //   而 plan 仍 hasPending ⇒ 队首永远出不了队，那份水位既没进世界也没进侧表（被静默丢掉）。
        //   减法写法不含加法，任何 budget 都安全（budget 与 levelCount - levelCursor 都非负）。
        final int end = budget >= levelCount - levelCursor ? levelCount : levelCursor + budget;
        int n = 0;
        while (levelCursor < end) {
            if (levelIsDelta[levelCursor] != 0) {
                sink.acceptDelta(levelPos[levelCursor], levelValue[levelCursor]);
            } else {
                sink.accept(levelPos[levelCursor], levelValue[levelCursor]);
            }
            levelCursor++;
            n++;
        }
        return n;
    }

    /** 按预算消费方块状态变更。 */
    public int consumeBlocks(final int budget, final PosSink sink) {
        if (budget < 0) {
            throw new IllegalArgumentException("budget must be >= 0: " + budget);
        }
        // ★ 同 consumeLevels：减法写法，不许出现 cursor + budget（budget = Integer.MAX_VALUE 时溢出为负）。
        final int end = budget >= blockCount - blockCursor ? blockCount : blockCursor + budget;
        int n = 0;
        while (blockCursor < end) {
            sink.accept(blockPos[blockCursor]);
            blockCursor++;
            n++;
        }
        return n;
    }

    /**
     * 把另一个 plan 的内容并入本 plan（求解线程产出 → 主线程合并）。
     *
     * <p>只并入对方尚未消费的部分；调用方需保证跨线程可见性（通常经并发队列传递）。
     */
    public void mergeFrom(final FluidWritePlan other) {
        for (int i = other.levelCursor; i < other.levelCount; i++) {
            if (other.levelIsDelta[i] != 0) {
                addLevelDelta(other.levelPos[i], other.levelValue[i]);
            } else {
                addLevel(other.levelPos[i], other.levelValue[i]);
            }
        }
        for (int i = other.blockCursor; i < other.blockCount; i++) {
            addBlock(other.blockPos[i]);
        }
    }

    /** 全部清空（含游标），对象可复用。 */
    public void clear() {
        levelCount = 0;
        levelCursor = 0;
        blockCount = 0;
        blockCursor = 0;
    }

    /** 清掉已消费的前缀，保留未消费部分（长时间积压时收缩数组）。 */
    public void compact() {
        if (levelCursor > 0) {
            final int remain = levelCount - levelCursor;
            System.arraycopy(levelPos, levelCursor, levelPos, 0, remain);
            System.arraycopy(levelValue, levelCursor, levelValue, 0, remain);
            System.arraycopy(levelIsDelta, levelCursor, levelIsDelta, 0, remain);
            levelCount = remain;
            levelCursor = 0;
        }
        if (blockCursor > 0) {
            final int remain = blockCount - blockCursor;
            System.arraycopy(blockPos, blockCursor, blockPos, 0, remain);
            blockCount = remain;
            blockCursor = 0;
        }
    }

    @Override
    public String toString() {
        return "FluidWritePlan{levels=" + pendingLevels() + "/" + levelCount
                + ", blocks=" + pendingBlocks() + "/" + blockCount + "}";
    }
}
