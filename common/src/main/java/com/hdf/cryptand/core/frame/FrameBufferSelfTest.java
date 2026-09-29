package com.hdf.cryptand.core.frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 分帧缓冲闸门（离线，无 MC）：
 *   ./gradlew :common:runFrameBufferTest
 *
 * <p>覆盖：容量边界、满后抛异常契约、清空复用、flush 顺序、分帧等价性、
 * 随机中断不重不漏、单帧上限、特化版对拍、吞吐闸门、SectionCursor 续扫。
 */
public final class FrameBufferSelfTest {

    private static int failures = 0;
    private static int checks = 0;

    private FrameBufferSelfTest() {
    }

    public static void main(final String[] args) {
        testCapacityBoundary();
        testFullThenAddThrows();
        testClearThenReuse();
        testFlushOrderAndCount();
        testFrameEquivalence();
        testNoLossNoDuplicate();
        testPerFrameCap();
        testSpecializedMatchesGeneric();
        testThroughputGate();
        testSectionCursor();

        System.out.println(failures == 0
                ? "[OK] FrameBuffer 全部通过 (" + checks + " 项)"
                : "[FAIL] FrameBuffer 失败 " + failures + " 项 / 共 " + checks + " 项");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static boolean pass(final boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  [FAIL] check #" + checks);
        }
        return ok;
    }

    private static void fail(final String msg) {
        checks++;
        failures++;
        System.out.println("  [FAIL] " + msg);
    }

    // ---------- 1. 容量边界 ----------
    private static void testCapacityBoundary() {
        final BufferGroup<Integer> g = new BufferGroup<>(4);
        pass(!g.add(1));
        pass(!g.add(2));
        pass(!g.add(3));
        pass(g.add(4));
        pass(g.isFull());
        pass(g.size() == 4);
        pass(g.capacity() == 4);
        pass(g.remaining() == 0);
    }

    // ---------- 2. 满后 add 抛异常（契约，不兜底） ----------
    private static void testFullThenAddThrows() {
        final BufferGroup<Integer> g = new BufferGroup<>(2);
        g.add(1);
        g.add(2);
        boolean threw = false;
        try {
            g.add(3);
        } catch (final IllegalStateException expected) {
            threw = true;
        }
        pass(threw);
        pass(g.size() == 2);

        final LongBufferGroup gl = new LongBufferGroup(1);
        gl.add(7L);
        threw = false;
        try {
            gl.add(8L);
        } catch (final IllegalStateException expected) {
            threw = true;
        }
        pass(threw);

        final IntBufferGroup gn = new IntBufferGroup(1);
        gn.add(7);
        threw = false;
        try {
            gn.add(8);
        } catch (final IllegalStateException expected) {
            threw = true;
        }
        pass(threw);
    }

    // ---------- 3. clear 后可复用 ----------
    private static void testClearThenReuse() {
        final BufferGroup<Integer> g = new BufferGroup<>(2);
        g.add(1);
        g.add(2);
        g.clear();
        pass(g.size() == 0);
        pass(!g.isFull());
        pass(g.remaining() == 2);
        pass(!g.add(9));
        pass(g.add(10));
        pass(g.get(0) == 9);
        pass(g.get(1) == 10);
        boolean threw = false;
        try {
            g.get(2);
        } catch (final IndexOutOfBoundsException expected) {
            threw = true;
        }
        pass(threw);
    }

    // ---------- 4. flush 顺序与条数 ----------
    private static void testFlushOrderAndCount() {
        final BufferGroup<Integer> g = new BufferGroup<>(8);
        for (int i = 0; i < 5; i++) {
            g.add(i);
        }
        final List<Integer> got = new ArrayList<>();
        final int n = g.flush(got::add);
        pass(n == 5);
        pass(got.equals(List.of(0, 1, 2, 3, 4)));
        pass(g.size() == 0);
        pass(g.flush(got::add) == 0);

        final IntBufferGroup gn = new IntBufferGroup(3);
        gn.add(10);
        gn.add(20);
        final int[] sink = new int[2];
        final int[] at = {0};
        final int m = gn.flush(v -> sink[at[0]++] = v);
        pass(m == 2);
        pass(sink[0] == 10 && sink[1] == 20);
    }

    // ---------- 5. 分帧等价性 ----------
    private static void testFrameEquivalence() {
        final int total = 10000;
        final BufferGroup<Integer> big = new BufferGroup<>(total);
        final List<Integer> oneShot = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            big.add(i);
        }
        big.flush(oneShot::add);

        final BufferGroup<Integer> small = new BufferGroup<>(BufferGroup.DEFAULT_CAPACITY);
        final List<Integer> framed = new ArrayList<>();
        int frames = 0;
        for (int i = 0; i < total; i++) {
            if (small.add(i)) {
                small.flush(framed::add);
                frames++;
            }
        }
        small.flush(framed::add);
        pass(oneShot.equals(framed));
        pass(frames == total / BufferGroup.DEFAULT_CAPACITY);
    }

    // ---------- 6. 随机中断：不重不漏 ----------
    private static void testNoLossNoDuplicate() {
        final Random rnd = new Random(20260927L);
        boolean allOk = true;
        String detail = "";
        for (int round = 0; round < 200 && allOk; round++) {
            final int total = 1 + rnd.nextInt(5000);
            final int cap = 1 + rnd.nextInt(64);
            final BufferGroup<Integer> g = new BufferGroup<>(cap);
            final List<Integer> got = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                if (g.add(i)) {
                    g.flush(got::add);
                }
            }
            g.flush(got::add);
            if (got.size() != total) {
                allOk = false;
                detail = "round=" + round + " size=" + got.size() + " expected=" + total;
                break;
            }
            for (int i = 0; i < total; i++) {
                if (got.get(i) != i) {
                    allOk = false;
                    detail = "round=" + round + " at " + i + " got " + got.get(i);
                    break;
                }
            }
        }
        if (!allOk) {
            fail("不重不漏: " + detail);
        } else {
            pass(true);
        }
    }

    // ---------- 7. 单帧上限 ----------
    private static void testPerFrameCap() {
        final int cap = 100;
        final int produced = 5000;
        final BufferGroup<Integer> g = new BufferGroup<>(cap);
        int idx = 0;
        int maxFrame = 0;
        int frames = 0;
        while (idx < produced) {
            int frame = 0;
            while (idx < produced) {
                final boolean full = g.add(idx++);
                frame++;
                if (full) {
                    break;
                }
            }
            maxFrame = Math.max(maxFrame, frame);
            frames++;
            g.clear();
        }
        pass(maxFrame <= cap);
        pass(frames == produced / cap);
    }

    // ---------- 8. 特化版与泛型版对拍 ----------
    private static void testSpecializedMatchesGeneric() {
        final BufferGroup<Integer> gi = new BufferGroup<>(7);
        final LongBufferGroup gl = new LongBufferGroup(7);
        final IntBufferGroup gn = new IntBufferGroup(7);
        final List<Integer> ag = new ArrayList<>();
        final List<Integer> al = new ArrayList<>();
        final List<Integer> an = new ArrayList<>();
        boolean same = true;
        for (int i = 0; i < 100 && same; i++) {
            final boolean a = gi.add(i);
            final boolean b = gl.add(i);
            final boolean c = gn.add(i);
            if (a != b || b != c) {
                same = false;
                break;
            }
            if (a) {
                gi.flush(ag::add);
                gl.flush(v -> al.add((int) v));
                gn.flush(an::add);
            }
        }
        gi.flush(ag::add);
        gl.flush(v -> al.add((int) v));
        gn.flush(an::add);
        pass(same);
        pass(ag.equals(al));
        pass(al.equals(an));
    }

    // ---------- 9. 吞吐闸门（宽松；防退化，不做微基准） ----------
    private static void testThroughputGate() {
        final IntBufferGroup g = new IntBufferGroup(BufferGroup.DEFAULT_CAPACITY);
        final int rounds = 10000;
        final long t0 = System.nanoTime();
        long moves = 0;
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < BufferGroup.DEFAULT_CAPACITY; i++) {
                if (g.add(i)) {
                    moves += g.size();
                    g.clear();
                }
            }
            moves += g.size();
            g.clear();
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        final long expected = (long) rounds * BufferGroup.DEFAULT_CAPACITY;
        pass(moves == expected);
        pass(ms < 5000L);
        System.out.println("  [info] 吞吐闸门: " + expected + " 次 add, " + ms + " ms");
    }

    // ---------- 10. SectionCursor：索引往返 + 分帧续扫 ----------
    private static void testSectionCursor() {
        boolean roundTrip = true;
        for (int lx = 0; lx < 16 && roundTrip; lx++) {
            for (int ly = 0; ly < 16 && roundTrip; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    final int idx = SectionCursor.linearIndex(lx, ly, lz);
                    if (SectionCursor.localX(idx) != lx
                            || SectionCursor.localY(idx) != ly
                            || SectionCursor.localZ(idx) != lz) {
                        roundTrip = false;
                        break;
                    }
                }
            }
        }
        pass(roundTrip);
        pass(SectionCursor.SECTION_CELLS == BufferGroup.DEFAULT_CAPACITY);

        final long key = SectionCursor.key(-3, 17, -40);
        pass(SectionCursor.keyX(key) == -3);
        pass(SectionCursor.keyY(key) == 17);
        pass(SectionCursor.keyZ(key) == -40);

        // 4096 格分两帧扫完（每帧 2048），不重不漏
        final SectionCursor cur = new SectionCursor(0, 0, 0);
        final IntBufferGroup buf = new IntBufferGroup(2048);
        final List<Integer> seen = new ArrayList<>();
        int frames = 0;
        while (true) {
            final boolean more = cur.fill(buf);
            buf.flush(seen::add);
            frames++;
            if (!more) {
                break;
            }
        }
        boolean sequential = seen.size() == SectionCursor.SECTION_CELLS;
        if (sequential) {
            for (int i = 0; i < seen.size(); i++) {
                if (seen.get(i) != i) {
                    sequential = false;
                    break;
                }
            }
        }
        pass(sequential);
        pass(frames == 2);
        pass(!cur.hasRemaining());
        pass(cur.used() == SectionCursor.SECTION_CELLS);
        cur.reset();
        pass(cur.hasRemaining() && cur.used() == 0);
    }
}
