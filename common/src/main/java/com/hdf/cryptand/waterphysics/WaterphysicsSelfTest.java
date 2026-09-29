package com.hdf.cryptand.waterphysics;

import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.fluid.FluidDelta;
import com.hdf.cryptand.fluid.FluidEngine;
import com.hdf.cryptand.fluid.FluidKind;
import com.hdf.cryptand.fluid.FluidWriteIntent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流体物理纯逻辑离线闸门（无 MC）：
 *   ./gradlew :common:runWaterphysicsTest
 *
 * <p>覆盖：水位场守恒、均衡器（数组版 + 场版）、逐格扩散（重力 / 水平收敛 / 每步守恒）、
 * 写意图分帧消费与坐标打包、黑盒模型、规则表、定点几何等量性。
 */
public final class WaterphysicsSelfTest {

    private static int failures = 0;
    private static int checks = 0;

    private WaterphysicsSelfTest() {
    }

    public static void main(final String[] args) {
        testFieldBasics();
        testMathFixedPoint();
        testEqualizerArray();
        testEqualizerField();
        testSpreadGravity();
        testSpreadOneCell();
        testFlowChain();
        testSpreadConverges();
        testChangesAreEmitted();
        testWriteBackQueue();
        testResolveLevel();
        testSpreadThroughput();
        testComputeBudget();
        testStepQuota();
        testEqualizeLines();
        testWaterBits();
        testBiomeSourceWater();
        testNaturalWaterSources();
        testMirrorSpread();
        testConnectorUTube();
        testConnectorLift();
        testPourOnFlat();
        testExternalInjection();
        testThinWaterStops();
        testLowestNeighbourAveraging();
        testWaterWorkSet();
        testProbe();
        testWritePlan();
        testBodyModel();
        testRules();
        testBodyCollectorCrossRegion();
        testBodyCollectorUnloaded();
        testBodyCollectorBudget();
        testBodyCollectorConservation();
        testBodyCollectorWriteIntent();

        System.out.println(failures == 0
                ? "[OK] WaterPhysics 全部通过 (" + checks + " 项)"
                : "[FAIL] WaterPhysics 失败 " + failures + " 项 / 共 " + checks + " 项");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static boolean pass(final boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            final StackTraceElement at = Thread.currentThread().getStackTrace()[2];
            System.out.println("  [FAIL] check #" + checks + " at "
                    + at.getMethodName() + ":" + at.getLineNumber());
        }
        return ok;
    }

    private static void fail(final String msg) {
        checks++;
        failures++;
        System.out.println("  [FAIL] " + msg);
    }

    private static int idx(final int lx, final int ly, final int lz) {
        return SectionCursor.linearIndex(lx, ly, lz);
    }

    /** 只读世界视图的测试实现：默认全固体、零水位，按需设置。 */
    private static final class ArrayView implements FluidCellView {
        private final Map<Long, Integer> kinds = new HashMap<>();
        private final Map<Long, Integer> levels = new HashMap<>();

        void set(final int x, final int y, final int z, final int kind, final int level) {
            final long k = FluidWritePlan.packPos(x, y, z);
            kinds.put(k, kind);
            levels.put(k, level);
        }

        @Override
        public int kindAt(final int x, final int y, final int z) {
            return kinds.getOrDefault(FluidWritePlan.packPos(x, y, z), FluidCellKind.SOLID);
        }

        @Override
        public int levelAt(final int x, final int y, final int z) {
            return levels.getOrDefault(FluidWritePlan.packPos(x, y, z), 0);
        }
    }

    private static WaterLevelField solidField() {
        final WaterLevelField f = new WaterLevelField();
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            f.setKind(i, FluidCellKind.SOLID);
        }
        return f;
    }

    // ---------- 1. 水位场基本 ----------
    private static void testFieldBasics() {
        final WaterLevelField f = new WaterLevelField();
        pass(f.volume() == 0);
        pass(f.kind(0) == FluidCellKind.AIR);
        f.setLevel(0, 5);
        pass(f.level(0) == 5);
        pass(f.volume() == 5);
        f.setLevel(0, 2);
        pass(f.volume() == 2);
        f.setLevel(1, 3);
        pass(f.volume() == 5);
        pass(f.recomputeVolume() == 5);

        f.setKind(1, FluidCellKind.SOLID);
        pass(f.level(1) == 0);
        pass(f.volume() == 2);

        boolean threw = false;
        try {
            f.setLevel(2, 9);
        } catch (final IllegalArgumentException expected) {
            threw = true;
        }
        pass(threw);

        threw = false;
        try {
            f.setKind(3, FluidCellKind.SOLID);
            f.setLevel(3, 1);
        } catch (final IllegalStateException expected) {
            threw = true;
        }
        pass(threw);
    }

    // ---------- 2. 定点几何与浮点等量 ----------
    private static void testMathFixedPoint() {
        boolean same = true;
        for (int level = 0; level <= FluidCellKind.MAX_LEVEL; level++) {
            final int fixed = WaterMath.heightFixed(level);
            final double classic = (level / 8.0) * 0.9375;      // 原版写法
            if (fixed != level * 15) {
                same = false;
                break;
            }
            if (WaterMath.heightOf(level) != classic) {         // 必须逐位相等
                same = false;
                break;
            }
            if (WaterMath.fixedToFloat(fixed) != (float) classic) {
                same = false;
                break;
            }
        }
        pass(same);
        pass(WaterMath.heightFixed(0) == 0);
        pass(WaterMath.heightFixed(8) == 120);                   // 0.9375 * 128
        pass(Math.abs(WaterMath.fixedToDouble(120) - 0.9375) < 1e-12);

        final int[] corners = WaterMath.cornerHeightsFixed(8, 8, 8, 8, 8);
        pass(corners[0] == 120 * 6);
        pass(Math.abs(WaterMath.cornerToDouble(corners[0]) - 0.9375) < 1e-12);

        final int[] flow = WaterMath.flowVectorFixed(4, 0, 0, 0, 8);
        pass(flow[0] > 0);
        pass(flow[1] == 0);

        pass(WaterMath.tickDelay(5) == 2);
        pass(WaterMath.tickDelay(1) == 1);
        pass(WaterMath.spread(new int[] {1, 4, 2}, 3) == 3);
        pass(WaterMath.total(new int[] {1, 4, 2}, 3) == 7);
    }

    // ---------- 3. 均衡器（数组版） ----------
    private static void testEqualizerArray() {
        final int[] levels = {8, 1, 0};
        final int[] caps = {8, 8, 8};
        final int changed = LevelEqualizer.equalize(levels, caps, 3);
        pass(changed == 3);
        pass(levels[0] == 3 && levels[1] == 3 && levels[2] == 3);
        pass(WaterMath.total(levels, 3) == 9);

        final int[] levels2 = {5, 5, 0, 0};
        final int[] caps2 = {8, 8, 8, 8};
        LevelEqualizer.equalize(levels2, caps2, 4);
        pass(WaterMath.total(levels2, 4) == 10);
        pass(WaterMath.spread(levels2, 4) <= 1);

        // 容量为 0 的格子不参与
        final int[] levels3 = {4, 0, 4};
        final int[] caps3 = {8, 0, 8};
        LevelEqualizer.equalize(levels3, caps3, 3);
        pass(levels3[1] == 0);
        pass(levels3[0] == 4 && levels3[2] == 4);

        // 确定性：同样输入两次结果一致
        final int[] a = {7, 2, 1, 0, 0};
        final int[] b = {7, 2, 1, 0, 0};
        final int[] cap = {8, 8, 8, 8, 8};
        LevelEqualizer.equalize(a, cap, 5);
        LevelEqualizer.equalize(b, cap, 5);
        boolean identical = true;
        for (int i = 0; i < 5; i++) {
            if (a[i] != b[i]) {
                identical = false;
                break;
            }
        }
        pass(identical);
    }

    // ---------- 4. 均衡器（场版） ----------
    private static void testEqualizerField() {
        final WaterLevelField f = solidField();
        f.setKind(idx(0, 0, 0), FluidCellKind.AIR);
        f.setKind(idx(1, 0, 0), FluidCellKind.AIR);
        f.setKind(idx(2, 0, 0), FluidCellKind.AIR);
        f.setLevel(idx(0, 0, 0), 8);
        f.setLevel(idx(1, 0, 0), 1);
        final int volume = f.volume();
        final int[] cells = {idx(0, 0, 0), idx(1, 0, 0), idx(2, 0, 0)};
        final int changed = LevelEqualizer.equalize(f, cells, 3);
        pass(changed == 3);
        pass(f.level(idx(0, 0, 0)) == 3);
        pass(f.level(idx(1, 0, 0)) == 3);
        pass(f.level(idx(2, 0, 0)) == 3);
        pass(f.volume() == volume);
        pass(LevelEqualizer.equalize(f, cells, 3) == 0);

        final int holders = LevelEqualizer.holders(f).length;
        pass(holders == 3);
    }

    // ---------- 5. 重力：水不悬空（且下泄时源格留 1） ----------
    private static void testSpreadGravity() {
        final WaterLevelField f = solidField();
        f.setKind(idx(0, 5, 0), FluidCellKind.AIR);
        f.setKind(idx(0, 4, 0), FluidCellKind.AIR);
        f.setLevel(idx(0, 5, 0), 8);
        final ArrayView view = new ArrayView();
        final FluidWritePlan plan = new FluidWritePlan();
        final int before = f.volume();
        final long key = SectionCursor.key(0, 0, 0);
        SpreadSolver.step(f, view, key, WaterWorkSet.full(), plan);
        // ★ 源格留 1：8 单位往下搬 ⇒ 下方 7、源留 1（水柱不会断头、水源方块不会凭空消失）
        pass(f.level(idx(0, 5, 0)) == 1);
        pass(f.level(idx(0, 4, 0)) == 7);
        pass(f.volume() == before);
        pass(plan.levelCount() == 0);

        // ★ 只有 1 单位时例外：整格落下去（留 1 就等于没搬）
        final WaterLevelField g = solidField();
        g.setKind(idx(0, 5, 0), FluidCellKind.AIR);
        g.setKind(idx(0, 4, 0), FluidCellKind.AIR);
        g.setLevel(idx(0, 5, 0), 1);
        SpreadSolver.step(g, view, key, WaterWorkSet.full(), plan);
        pass(g.level(idx(0, 5, 0)) == 0);
        pass(g.level(idx(0, 4, 0)) == 1);
        pass(g.volume() == 1);

        // 下方已经装了 7 ⇒ 只能再收 1 ⇒ 源格变成 7（这一搬不会搬空源格，所以不留）
        final WaterLevelField h = solidField();
        h.setKind(idx(0, 5, 0), FluidCellKind.AIR);
        h.setKind(idx(0, 4, 0), FluidCellKind.AIR);
        h.setLevel(idx(0, 5, 0), 8);
        h.setLevel(idx(0, 4, 0), 7);
        SpreadSolver.step(h, view, key, WaterWorkSet.full(), plan);
        pass(h.level(idx(0, 5, 0)) == 7);
        pass(h.level(idx(0, 4, 0)) == 8);
        pass(h.volume() == 15);
    }

    // ---------- 5.5 单格任务（单格粒度）：一格水同时向四边各走一格 ----------
    private static void testSpreadOneCell() {
        final WaterLevelField f = solidField();
        f.setKind(idx(5, 5, 5), FluidCellKind.AIR);
        f.setKind(idx(4, 5, 5), FluidCellKind.AIR);
        f.setKind(idx(6, 5, 5), FluidCellKind.AIR);
        f.setKind(idx(5, 5, 4), FluidCellKind.AIR);
        f.setKind(idx(5, 5, 6), FluidCellKind.AIR);
        f.setLevel(idx(5, 5, 5), 8);            // 下方是固体 ⇒ 只能走水平

        final ArrayView view = new ArrayView();
        final FluidWritePlan plan = new FluidWritePlan();
        final long key = SectionCursor.key(0, 0, 0);
        final int used = SpreadSolver.stepCell(f, view, key, idx(5, 5, 5), plan);

        // ★ 水头驱动：一次只让 1 单位给「水头最低」的那**一个**邻居（水头 = y*8 + 水位），
        //   不是四边各分 1。四个水平邻居都在 y=5、水位 0（水头 40，完全同分），
        //   同水头按 AXIAL 顺序（+x, −x, +z, −z, +y, −y）取第一个 ⇒ 只有 +x 拿到 1。
        pass(f.level(idx(6, 5, 5)) == 1);            // +x：AXIAL 里排最前的最低水头邻居
        pass(f.level(idx(4, 5, 5)) == 0);            // −x / ±z 水头相同但排在后面 ⇒ 这一次没有
        pass(f.level(idx(5, 5, 4)) == 0);
        pass(f.level(idx(5, 5, 6)) == 0);
        pass(f.level(idx(5, 5, 5)) == 7);            // 只让出 1（旧的「中心 −4」是已删除的均分语义）
        pass(f.volume() == 8);                       // 守恒
        pass(used == 1);                             // 消耗 = 1 次转移

        // ★ 一次计算只动一格：外圈（距中心 2 格）不动；被灌到水的那一格也不会在同一次计算里继续外扩
        //   （它们的水位变了 ⇒ 由调用方排进延后表，下一轮到它们时才继续流）
        pass(f.level(idx(3, 5, 5)) == 0);
        pass(f.level(idx(7, 5, 5)) == 0);
        pass(f.level(idx(5, 5, 3)) == 0);
        pass(f.level(idx(5, 5, 7)) == 0);

        // 没水的格什么都不做；而刚被灌到 1 的 (1,5,5) 也不会再往外让
        //   —— 水位 1 是自由水面的末端，只有被重新填到 ≥ 2 才再次参与（原版观感）
        pass(SpreadSolver.stepCell(f, view, key, idx(1, 5, 5), plan) == 0);

        // 额度以「格数」计：0 格 ⇒ 一个格都不碰
        final WaterLevelField g = spreadScene();
        final FluidWritePlan pg = new FluidWritePlan();
        pass(SpreadSolver.step(g, view, key, WaterWorkSet.full(), pg, 0) == 0);
        pass(SpreadSolver.step(g, view, key, WaterWorkSet.full(), pg, 1) > 0);
    }

    // ---------- 5.7 流动链：逐格式「到期格 → 算一次 → 水位变了的格再排队」必须把水一路送出去 ----------
    //   离线复现主线程循环：从延后队列取到期格 → 逐格 stepCell → 统计水位变了的格 → 排下一轮。
    //   这条闸门专治「水只在原地动一下就不动了」。
    private static void testFlowChain() {
        final WaterLevelField f = solidField();
        for (int x = 0; x < 6; x++) {                       // 一条水平沟槽
            f.setKind(idx(x, 5, 5), FluidCellKind.AIR);
        }
        f.setLevel(idx(0, 5, 5), 8);                        // 最左端一格水

        final ArrayView view = new ArrayView();
        syncToView(f, view);
        final long key = SectionCursor.key(0, 0, 0);
        final FluidWritePlan plan = new FluidWritePlan();
        final int volume = f.volume();
        pass(volume == 8);

        final int[] before = new int[WaterLevelField.CELLS];
        WaterWorkSet due = new WaterWorkSet();
        due.add(idx(0, 5, 5));                              // 起点：水位刚变过的那一格

        int round = 0;
        while (due.size() > 0 && round < 200) {
            for (int i = 0; i < WaterLevelField.CELLS; i++) {
                before[i] = f.level(i);
            }
            for (int k = 0; k < due.size(); k++) {
                SpreadSolver.stepCell(f, view, key, due.get(k), plan);
            }
            pass(f.volume() == volume);                     // 每一轮都守恒
            final WaterWorkSet next = new WaterWorkSet();
            for (int i = 0; i < WaterLevelField.CELLS; i++) {
                if (f.level(i) != before[i]) {
                    next.add(i);                            // 水位变了的格 ⇒ 下一轮到期
                }
            }
            due = next;
            syncToView(f, view);                            // 模拟写回世界
            round++;
        }

        pass(round < 200);                                  // 必须收敛（不能永远排不完）
        int wetFinal = 0;
        for (int x = 0; x < 6; x++) {
            if (f.level(idx(x, 5, 5)) > 0) {
                wetFinal++;
            }
        }
        pass(wetFinal >= 4);                                // 收敛时水已经铺开
        pass(f.level(idx(1, 5, 5)) > 0);                    // 水确实被送出去了
        // ★ 远端允许为 0：差 1 就是稳定态（1 的水不会自发流向 0 的邻居，否则会来回抖）。
        //   有限水量下的稳定态是「相邻差 ≤ 1」，不保证铺满整条沟槽。
    }

    private static void syncToView(final WaterLevelField f, final ArrayView view) {
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            view.set(SectionCursor.localX(i), SectionCursor.localY(i), SectionCursor.localZ(i),
                    f.kind(i), f.level(i));
        }
    }

    // ---------- 6. 水平扩散收敛 + 每步守恒 ----------
    private static void testSpreadConverges() {
        final WaterLevelField f = solidField();
        f.setKind(idx(0, 0, 0), FluidCellKind.AIR);
        f.setKind(idx(1, 0, 0), FluidCellKind.AIR);
        f.setKind(idx(2, 0, 0), FluidCellKind.AIR);
        f.setLevel(idx(0, 0, 0), 8);

        final ArrayView view = new ArrayView();
        final FluidWritePlan plan = new FluidWritePlan();
        final long key = SectionCursor.key(0, 0, 0);
        final WaterWorkSet workSet = WaterWorkSet.full();
        final int volume = f.volume();
        pass(volume == 8);

        boolean conserved = true;
        for (int i = 0; i < 200; i++) {
            SpreadSolver.step(f, view, key, workSet, plan);
            if (f.volume() != volume) {
                conserved = false;
                break;
            }
        }
        pass(conserved);
        // 水平均分（只跟「最低那一档」邻居）：{8,0,0} →
        //   (0)=8 与 0 均分 → {4,4,0}；(1)=4 与 0 均分 → {4,2,2}；
        //   然后 (0)=4 与更低的那档 2 均分 → {3,3,2}；此后谁都拉不动 ⇒ 稳定（差 ≤ 1）。
        final int l0 = f.level(idx(0, 0, 0));
        final int l1 = f.level(idx(1, 0, 0));
        final int l2 = f.level(idx(2, 0, 0));
        pass(l0 + l1 + l2 == 8);
        pass(l0 == 3 && l1 == 3 && l2 == 2);
        pass(plan.levelCount() == 0);

        // 稳定后不再流动
        final int stable = SpreadSolver.step(f, view, key, workSet, plan);
        pass(stable == 0);

        // 跨 region：把水放在 region 边界并让它往外流 → 产生写意图
        final WaterLevelField g = solidField();
        g.setKind(idx(15, 0, 0), FluidCellKind.AIR);
        g.setLevel(idx(15, 0, 0), 8);
        final ArrayView v2 = new ArrayView();
        v2.set(16, 0, 0, FluidCellKind.AIR, 0);
        final FluidWritePlan plan2 = new FluidWritePlan();
        SpreadSolver.step(g, v2, key, WaterWorkSet.full(), plan2);
        pass(plan2.levelCount() == 1);
        // 水头驱动：跨区邻居的水头 = 0*8 + 0 = 0，自己的水头 = 8，0 + 1 < 8 ⇒ 让 1 单位过去
        pass(g.level(idx(15, 0, 0)) == 7);      // 只让 1（旧的「均分 8/2 = 4」是已删除的均分语义）
        if (plan2.levelCount() == 1) {
            final List<Long> got = new ArrayList<>();
            final int[] gotDelta = {0};
            // ★ 跨 region 的输出必须是**增量**（写回时加到来值上）：这一次只让过去 1 单位 ⇒ 增量 1。
            //   用绝对值会在 plan 跨 tick 写回时覆盖目标 region 自己的写入（丢水）。
            plan2.consumeLevels(8, new FluidWritePlan.LevelSink() {
                @Override
                public void accept(final long pos, final int lv) {
                    fail("跨 region 的落点应当是增量，收到绝对值 " + lv);
                }

                @Override
                public void acceptDelta(final long pos, final int delta) {
                    got.add(pos);
                    gotDelta[0] = delta;
                }
            });
            pass(gotDelta[0] == 1);
            pass(got.size() == 1);
            pass(FluidWritePlan.unpackX(got.get(0)) == 16);
            pass(FluidWritePlan.unpackY(got.get(0)) == 0);
            pass(FluidWritePlan.unpackZ(got.get(0)) == 0);
        }
    }

    // ---------- 6.5 region 内的变化必须作为写意图输出（否则快照一丢，世界永远不变） ----------
    private static void testChangesAreEmitted() {
        final WaterLevelField f = solidField();
        f.setKind(idx(0, 0, 0), FluidCellKind.AIR);
        f.setKind(idx(1, 0, 0), FluidCellKind.AIR);
        f.setKind(idx(2, 0, 0), FluidCellKind.AIR);
        f.setLevel(idx(0, 0, 0), 8);

        final byte[] initial = new byte[WaterLevelField.CELLS];
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            initial[i] = (byte) f.level(i);
        }

        final ArrayView view = new ArrayView();
        final FluidWritePlan plan = new FluidWritePlan();
        final long key = SectionCursor.key(0, 0, 0);
        final WaterWorkSet workSet = WaterWorkSet.full();
        for (int i = 0; i < 200; i++) {
            SpreadSolver.step(f, view, key, workSet, plan);
        }
        final int emitted = SpreadSolver.emitChanges(f, initial, key, workSet, plan);
        pass(emitted > 0);

        // 用「初始状态 + 写意图重放」重建一份，必须与快照逐格一致
        final WaterLevelField replay = solidField();
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            replay.setKind(i, f.kind(i));
            replay.setLevel(i, initial[i] & 0xFF);
        }
        final int[] applied = {0};
        plan.consumeLevels(Integer.MAX_VALUE, new FluidWritePlan.LevelSink() {
            @Override
            public void accept(final long packedPos, final int level) {
                fail("写意图现在一律是增量，收到绝对值 " + level);
            }

            @Override
            public void acceptDelta(final long packedPos, final int delta) {
                final int ri = SpreadSolver.indexInRegion(0, 0, 0, FluidWritePlan.unpackX(packedPos),
                        FluidWritePlan.unpackY(packedPos), FluidWritePlan.unpackZ(packedPos));
                if (ri >= 0) {
                    // ★ 增量重放：replay 是从 initial 起步的，累加增量就得到最终状态。
                    //   这同时证明了「增量语义」与「绝对最终值」在顺序无关下的等价性。
                    replay.setLevel(ri, replay.level(ri) + delta);
                    applied[0]++;
                }
            }
        });
        pass(applied[0] == emitted);
        boolean same = true;
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            if (replay.level(i) != f.level(i)) {
                same = false;
                break;
            }
        }
        pass(same);
        // 水平均分：{8,0,0} 收敛到 {3,3,2}（只跟「最低那一档」邻居摊 ⇒ 最终差 ≤ 1）
        final int a = f.level(idx(0, 0, 0));
        final int b = f.level(idx(1, 0, 0));
        final int c = f.level(idx(2, 0, 0));
        pass(a + b + c == 8 && a == 3 && b == 3 && c == 2);
    }

    // ---------- 6.8 写回队列：一次请求跨多 tick 做完才算完成 ----------
    private static void testWriteBackQueue() {
        final WriteBackQueue q = new WriteBackQueue();
        pass(q.isEmpty());

        final FluidWritePlan a = new FluidWritePlan();
        a.addLevel(1, 2, 3, 5);
        q.enqueue(7L, a);
        pass(!q.isEmpty() && q.size() == 1 && q.isInFlight(7L));
        pass(!q.completeIfDrained());              // 还没消费完，不能算完成
        a.consumeLevels(8, FluidWritePlan.IGNORE);
        pass(q.completeIfDrained());                // 消费干净了才移出
        pass(q.isEmpty() && !q.isInFlight(7L));

        // 先进先出：前一个没写完，不会轮到下一个
        final FluidWritePlan b = new FluidWritePlan();
        final FluidWritePlan c = new FluidWritePlan();
        b.addLevel(0, 0, 0, 1);
        c.addLevel(1, 0, 0, 1);
        q.enqueue(1L, b);
        q.enqueue(2L, c);
        pass(q.peek().regionKey() == 1L);
        pass(!q.completeIfDrained());
        b.consumeLevels(1, FluidWritePlan.IGNORE);
        pass(q.completeIfDrained());
        pass(q.peek() != null && q.peek().regionKey() == 2L);
        q.clear();
        pass(q.isEmpty() && !q.isInFlight(1L) && !q.isInFlight(2L));
    }

    // ---------- 6.9 世界水位与侧表水位的合并（外部清除/注入/权威） ----------
    private static void testResolveLevel() {
        pass(FluidLevels.resolve(0, 0) == 0);
        pass(FluidLevels.resolve(0, 7) == 0);   // 外部清除（桶收走）→ 侧表必须跟清零
        pass(FluidLevels.resolve(8, 0) == 8);   // 外部注入（倒水）→ 采纳世界
        pass(FluidLevels.resolve(8, 3) == 3);   // 都在 → 侧表权威
        pass(FluidLevels.resolve(1, 0) == 1);
    }

    // ---------- 6.95 求解吞吐闸门（量化：满水 section 跑满步数要多久） ----------
    private static void testSpreadThroughput() {
        final WaterLevelField f = solidField();
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            f.setKind(i, FluidCellKind.AIR);
            f.setLevel(i, 4 + (i & 3));
        }
        final ArrayView view = new ArrayView();
        final FluidWritePlan plan = new FluidWritePlan();
        final long key = SectionCursor.key(0, 0, 0);
        final int rounds = 100;
        final long t0 = System.nanoTime();
        final WaterWorkSet workSet = WaterWorkSet.full();
        long changed = 0;
        for (int r = 0; r < rounds; r++) {
            for (int i = 0; i < SpreadSolver.MAX_SPREAD_STEPS; i++) {
                changed += SpreadSolver.step(f, view, key, workSet, plan);
            }
            plan.clear();
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        pass(changed > 0);
        pass(ms < 30000L);
        System.out.println("  [info] 求解吞吐: " + rounds + " 轮 x " + SpreadSolver.MAX_SPREAD_STEPS
                + " 步 @4096 满水格 = " + ms + " ms (" + (ms * 1000L / rounds) + " us/轮)");
    }

    // ---------- 6.97 运算额度：每 tick 发放（重置而不是累加） ----------
    private static void testComputeBudget() {
        final ComputeBudget b = new ComputeBudget();
        b.grant(1000);
        pass(b.remaining() == 1000 && !b.exhausted());
        pass(b.take(400) == 400 && b.remaining() == 600);
        pass(b.take(9999) == 600 && b.exhausted());   // 取不满就只给剩下的
        // 心跳重置：不管剩多少，直接设成配置值
        b.grant(1000);
        pass(b.remaining() == 1000 && !b.exhausted());
        b.grant(0);
        pass(b.exhausted() && b.take(5) == 0);

        // step 的额度限制：额度 0 时一步也不做，且报告消耗为 0
        final WaterLevelField f = solidField();
        f.setKind(idx(0, 0, 0), FluidCellKind.AIR);
        f.setKind(idx(1, 0, 0), FluidCellKind.AIR);
        f.setLevel(idx(0, 0, 0), 8);
        final ArrayView view = new ArrayView();
        final FluidWritePlan plan = new FluidWritePlan();
        final long key = SectionCursor.key(0, 0, 0);
        pass(SpreadSolver.step(f, view, key, WaterWorkSet.full(), plan, 0) == 0);
        pass(f.level(idx(0, 0, 0)) == 8);            // 额度为 0 ⇒ 水没动
        final int used = SpreadSolver.step(f, view, key, WaterWorkSet.full(), plan, 1);
        pass(used == 1);                              // 额度 1 ⇒ 只转移一次

        // 按 region 分摊：均分、至少 1、额度空则 0（分摊本身不扣减额度）
        final ComputeBudget shared = new ComputeBudget();
        shared.grant(1000);
        pass(shared.share(4) == 250);
        pass(shared.remaining() == 1000);
        pass(shared.share(0) == 1000);                // region 数 <= 0 按 1 算
        shared.grant(3);
        pass(shared.share(4) == 1);                   // 均分到 0 也要给 1，否则后面的 region 永远不动
        shared.grant(0);
        pass(shared.share(4) == 0);
    }

    // ---------- 6.97b 工作集：求解只遍历集合内的格（工作集） ----------
    private static void testWaterWorkSet() {
        final WaterWorkSet set = new WaterWorkSet();
        pass(set.isEmpty() && set.size() == 0);
        pass(set.add(7));
        pass(!set.add(7));                        // 去重
        pass(set.contains(7) && !set.contains(8));
        pass(set.size() == 1 && set.get(0) == 7);

        boolean threw = false;
        try {
            set.add(-1);
        } catch (final IndexOutOfBoundsException e) {
            threw = true;
        }
        pass(threw);
        threw = false;
        try {
            set.add(WaterWorkSet.MAX_CELLS);
        } catch (final IndexOutOfBoundsException e) {
            threw = true;
        }
        pass(threw);

        set.clear();
        pass(set.isEmpty() && !set.contains(7));
        pass(set.add(7) && set.size() == 1);      // 清空后可复用

        final WaterWorkSet all = WaterWorkSet.full();
        pass(all.size() == WaterLevelField.CELLS);
        pass(all.contains(0) && all.contains(WaterLevelField.CELLS - 1));

        // ★ 行为闸门一：求解只碰工作集里的格（集合外的格水位再大也不动）
        final WaterLevelField f = solidField();
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            f.setKind(i, FluidCellKind.AIR);
            f.setLevel(i, 4 + (i & 3));
        }
        final WaterWorkSet onlyFirst = new WaterWorkSet();
        onlyFirst.add(0);
        final ArrayView view = new ArrayView();
        final FluidWritePlan plan = new FluidWritePlan();
        final long key = SectionCursor.key(0, 0, 0);
        final int moved = SpreadSolver.step(f, view, key, onlyFirst, plan, Integer.MAX_VALUE);
        pass(moved <= 1);                          // 至多处理集合里那一个格

        // ★ 行为闸门二：emitChanges 也只报工作集里的格
        final byte[] initial = new byte[WaterLevelField.CELLS];
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            initial[i] = (byte) f.level(i);
        }
        f.setLevel(idx(5, 5, 5), 7);               // 集合外：改了也不该报
        f.setLevel(0, 3);                          // 集合内：改了就报
        final FluidWritePlan out = new FluidWritePlan();
        final int emitted = SpreadSolver.emitChanges(f, initial, key, onlyFirst, out);
        pass(emitted == 1);
    }

    // ---------- 6.98 性能探针：分段累计与报告 ----------
    private static void testProbe() {
        final WaterphysicsProbe p = new WaterphysicsProbe();
        pass(p.ticks() == 0 && p.report().contains("no samples"));
        p.addCapture(1, 1000);
        p.addCapture(2, 3000);
        p.addWriteBack(10, 8, 2, 500);
        p.addTick(10_000);
        p.addTick(20_000);
        pass(p.ticks() == 2);
        pass(p.totalCaptureCells() == 3);
        pass(p.totalWriteConsumed() == 10 && p.totalWriteApplied() == 8 && p.totalWriteSkipped() == 2);
        pass(p.averageAppliedPerTick() == 4);
        pass(p.averageTickMicros() == 15);
        p.addDiscard();
        pass(p.totalDiscarded() == 1);
        final String r = p.report();
        pass(r.contains("capture") && r.contains("setBlock") && r.contains("skipped")
                && r.contains("discarded"));
        p.reset();
        pass(p.ticks() == 0 && p.totalWriteApplied() == 0 && p.totalCaptureCells() == 0
                && p.totalDiscarded() == 0);
    }

    // ---------- 6.97c 额度以「格数」计：一格要么整算，要么完全不动 ----------
    private static void testStepQuota() {
        final WaterLevelField f = solidField();
        for (int x = 0; x < 4; x++) {
            f.setKind(idx(x, 5, 5), FluidCellKind.AIR);
            f.setLevel(idx(x, 5, 5), 8 - x);        // {8,7,6,5}：相邻差 1 ⇒ 谁都搬不动（稳定态）
        }
        final ArrayView view = new ArrayView();
        final FluidWritePlan plan = new FluidWritePlan();
        final long key = SectionCursor.key(0, 0, 0);

        pass(SpreadSolver.step(f, view, key, WaterWorkSet.full(), plan, 0) == 0);
        pass(SpreadSolver.step(f, view, key, WaterWorkSet.full(), plan, 2) == 0);
        pass(SpreadSolver.step(f, view, key, WaterWorkSet.full(), plan, Integer.MAX_VALUE) == 0);
        pass(f.volume() == 8 + 7 + 6 + 5);          // 一步都不动 ⇒ 总量不变

        // 换一片真正会流的：额度 1 ⇒ 最多处理一个格，但处理的必须是完整的一格
        final WaterLevelField g = spreadScene();    // {8,0,0}
        final FluidWritePlan pg = new FluidWritePlan();
        pass(SpreadSolver.step(g, view, key, WaterWorkSet.full(), pg, 1) > 0);
        pass(g.level(idx(0, 0, 0)) == 7);           // 头一格被完整处理：让 1 单位给最低水头邻居
        pass(g.level(idx(1, 0, 0)) == 1);           // 落过去的也只有这 1 单位
        pass(g.level(idx(2, 0, 0)) == 0);           // 额度 1 ⇒ 后面的格完全没碰（没有再往 (2) 推）
    }

    // ---------- 6.97f 均衡器（压力模型：一片水一个平衡水面 H*，超出目标的格往外让 1 单位） ----------
    private static void testEqualizeLines() {
       final WaterLevelField f = solidField();
        for (int x = 0; x < 10; x++) {
            f.setKind(idx(x, 5, 5), FluidCellKind.AIR);     // 一条 10 格长的沟槽
        }
        f.setLevel(idx(0, 5, 5), 8);
        final ArrayView view = new ArrayView();
        final long key = SectionCursor.key(0, 0, 0);
        final WaterWorkSet active = new WaterWorkSet();
        active.add(idx(0, 5, 5));

        // ★ 新压力模型（不再是「一次只拉平一处、两格各分一半」）：
        //   这片水 = 沟槽 10 格（每格下方都是固体 ⇒ 都算容器），8 单位按重力堆叠
        //   ⇒ H* = 5*8 + 8/10 = 40；每格 target = clamp(H* − 8y, 0, 8) = 0 ⇒ 谁有水谁就「超出」，
        //   于是把 1 单位让给超出量最低的邻居。一轮内反复 sweep 直到没人再让
        //   （水位 1 是自由水面的末端：只能顺着重力往下掉，不再横向扩散）
        //   ⇒ 8 单位从 (0,5,5) 一路铺成前 8 格各 1。
        //   返回值 = 这一轮一共搬了多少次（28 次），不再是「拉平次数」。
        pass(SpreadSolver.equalizeLines(f, view, key, active, 16) == 28);
        pass(f.level(idx(0, 5, 5)) == 1);
        pass(f.level(idx(1, 5, 5)) == 1);
        boolean laid = true;
        for (int x = 0; x < 8; x++) {
            laid &= f.level(idx(x, 5, 5)) == 1;         // 前 8 格各 1
        }
        for (int x = 8; x < 10; x++) {
            laid &= f.level(idx(x, 5, 5)) == 0;         // 水不够铺满：后 2 格还是干的
        }
        pass(laid);
        pass(f.volume() == 8);

        // 已经到平衡（每格 1、目标 0，且水位 1 是末端）⇒ 再来一轮一次都不搬。
        //   ★ 「这一格本批搬过」由求解器自己的 token 标记，标记数组参数已从签名里收掉；
        //     这里的静态来自水位本身。
        final WaterWorkSet again = new WaterWorkSet();
        again.add(idx(0, 5, 5));
        pass(SpreadSolver.equalizeLines(f, view, key, again, 16) == 0);
        pass(f.volume() == 8);

        // ★ 换一批（passed 全新、active 换成别的格）也一样：水位 1 是自由水面的末端，
        //   只能顺着重力往下掉，不会再横向扩散。要让水继续往外走，必须先把某一格重新填到 ≥ 2
        //   （真机上是上游来水或外部注入）—— 旧注释里的「4 与 0 拉成 2/2」是已删除的均分语义。
        final WaterWorkSet active2 = new WaterWorkSet();
        active2.add(idx(1, 5, 5));
        pass(SpreadSolver.equalizeLines(f, view, key, active2, 16) == 0);
        pass(f.level(idx(1, 5, 5)) == 1);
        pass(f.level(idx(2, 5, 5)) == 1);
        pass(f.volume() == 8);

        // 关掉均衡器（maxDist = 0）⇒ 一步都不动
        pass(SpreadSolver.equalizeLines(f, view, key, active2, 0) == 0);

        // ★ 「洞旁边的水」：水在 (2,5,5)，正前方 (3,5,5) 是固体，洞在低一层的 (3,4,5)。
        //   新模型**不穿角**：连通体只沿轴向相邻走 ⇒ (2,5,5) 与 (3,4,5) 根本不是同一片水。
        //   (2,5,5) 自己就是完整的一柱（H* = 5*8 + 8 = 48、target = 8），一点都没「超出」⇒ 一步不搬。
        //   这是正确行为：水不会斜着掉进低一层的洞；落洞由 stepCell 的重力分支负责
        //   （只有正下方那格才吃重力那一搬）。旧的「跨层整格搬移」（水全落进洞里）已经删除。
        final WaterLevelField h = solidField();
        h.setKind(idx(2, 5, 5), FluidCellKind.AIR);
        h.setLevel(idx(2, 5, 5), 8);
        h.setKind(idx(3, 4, 5), FluidCellKind.AIR);
        final WaterWorkSet hole = new WaterWorkSet();
        hole.add(idx(2, 5, 5));
        pass(SpreadSolver.equalizeLines(h, view, key, hole, 16) == 0);
        pass(h.level(idx(2, 5, 5)) == 8);           // 原地不动（洞在斜下方，够不着）
        pass(h.level(idx(3, 4, 5)) == 0);           // 斜下方的洞不会被灌满
        pass(h.volume() == 8);

        // 被固体挡住 ⇒ 不越过它去找远处的水（连通体走不过去 ⇒ (0,5,5) 自己是完整一柱、target 8 ⇒ 不动）
        final WaterLevelField g = solidField();
        g.setKind(idx(0, 5, 5), FluidCellKind.AIR);
        g.setLevel(idx(0, 5, 5), 8);
        final WaterWorkSet solo = new WaterWorkSet();
        solo.add(idx(0, 5, 5));
        pass(SpreadSolver.equalizeLines(g, view, key, solo, 16) == 0);
        pass(g.volume() == 8);
    }

    // ---------- 6.97g 镜像方向：8 单位水放在沟槽右端，必须往左铺平 ----------
    //   ★ 修复前：x=6 的两个候选 x=5 / x=7 在（超出量 excess、格位高低 ny、实时水头）上完全相同，
    //     于是退回 AXIAL 的固定顺序（+x 优先）—— 而 +x 正是回源方向，水在右边来回搬，
    //     真缺口（左边的 0）永远填不上 ⇒ 停在 0 0 0 0 1 1 2 1 2 1。
    //   ★ 修复后：每 sweep 一次多源 BFS 给出「到最近缺口的轴向距离」，平手时按距离近者优先，
    //     方向信号就有了 ⇒ 铺成 0 0 1 1 1 1 1 1 1 1，且再来一轮一次都不搬。
    // ---------- 有水格位图：稀疏遍历的地基（2026-09-28） ----------

    /**
     * 有水格位图必须与水位严格同步。
     *
     * <p>稀疏化之后 flood fill 的起点只从位图里枚举（不再扫整段 4096）：
     * 漏一位 = 那格水从此不参与求解（静默漏水），多一位 = 无谓遍历。
     * 三条写入路径（setLevel / setKind 挤水 / clear）各验一遍，再真跑一轮均衡器复核。
     */
    private static void testWaterBits() {
        final WaterLevelField f = solidField();
        pass(f.waterBitsConsistent());                  // 空场

        for (int x = 0; x < 10; x++) {
            f.setKind(idx(x, 5, 5), FluidCellKind.AIR);
        }
        pass(f.waterBitsConsistent());                  // 只改方块、还没水
        f.setLevel(idx(9, 5, 5), 8);
        pass(f.waterBitsConsistent());                  // 置位
        f.setLevel(idx(9, 5, 5), 3);
        pass(f.waterBitsConsistent());                  // 减水位仍算「有水」
        f.setLevel(idx(9, 5, 5), 0);
        pass(f.waterBitsConsistent());                  // 清位

        // setKind 把水挤掉那条路径：变不可容纳 ⇒ 水位归零 ⇒ 位图必须跟着清
        f.setLevel(idx(9, 5, 5), 5);
        f.setKind(idx(9, 5, 5), FluidCellKind.SOLID);
        pass(f.waterBitsConsistent());
        pass(f.level(idx(9, 5, 5)) == 0);
        pass(!f.hasWater());

        // 真跑一遍均衡器（求解期大量 setLevel）后再复核
        final WaterLevelField g = solidField();
        final WaterWorkSet active = new WaterWorkSet();
        for (int x = 0; x < 10; x++) {
            g.setKind(idx(x, 5, 5), FluidCellKind.AIR);
            active.add(idx(x, 5, 5));
        }
        g.setLevel(idx(0, 5, 5), 8);
        SpreadSolver.equalizeLines(g, new ArrayView(), SectionCursor.key(0, 0, 0), active, 16);
        pass(g.waterBitsConsistent());

        g.clear();
        pass(g.waterBitsConsistent());
        pass(!g.hasWater());
    }

    // ---------- 恒定水源：群系锁定满格（2026-09-28） ----------

    /**
     * 恒定水源：被标记的格水位永远锁在满格 —— 让出去的水立刻补回，**自己不减少**。
     *
     * <p>语义 = <b>无限水源</b>：海洋/河流不会被抽干，别处可以一直从它填充；代价是连通水域
     * 总量会增长（凭空产水）。验三件事：① 源格让水后仍回到 8；② 缺口确实被填（真的在供水）；
     * ③ 总量增加。
     */
    private static void testBiomeSourceWater() {
        final WaterLevelField f = solidField();
        for (int x = 0; x < 4; x++) {
            f.setKind(idx(x, 5, 5), FluidCellKind.AIR);
        }
        f.setLevel(idx(0, 5, 5), 8);                        // 只有最左边有水，并标记为恒定水源
        f.setSource(idx(0, 5, 5), true);
        pass(f.isSource(idx(0, 5, 5)));
        pass(!f.isSource(idx(1, 5, 5)));
        final int before = f.volume();
        pass(before == 8);

        final WaterWorkSet active = new WaterWorkSet();
        active.add(idx(0, 5, 5));
        SpreadSolver.equalizeLines(f, new ArrayView(), SectionCursor.key(0, 0, 0), active, 16);

        pass(f.level(idx(0, 5, 5)) == 8);                   // ① 源格没被抽干
        pass(f.level(idx(1, 5, 5)) > 0);                    // ② 缺口被填（真的在供水）
        pass(f.volume() > before);                          // ③ 凭空产水（无限水源语义）

        f.clear();                                          // clear 必须连源位一起复位
        pass(!f.isSource(idx(0, 5, 5)));
        pass(f.volume() == 0);
    }

    // ---------- 自然水源集合：群系命中 +「世界生成时就在」（2026-09-29） ----------

    /**
     * 自然水源登记表：判据 = <b>群系命中</b> + <b>section 第一次被看到时就在</b>的水源方块。
     *
     * <p>验六件事：① 未登记一律 false；② 首次登记只收「生成时就在」的水源格；
     * ③ <b>二次登记不补收</b>（玩家在海洋里自己放的水没有效果）；④ 群系没命中的 section
     * 既不登记也不占条目；⑤ 位图逐列生效、位边界正确；⑥ 序列化往返一致。
     */
    private static void testNaturalWaterSources() {
        final NaturalWaterSources sources = new NaturalWaterSources();
        final long key = SectionCursor.key(3, 4, 5);
        final byte[] allHit = new byte[NaturalWaterScanner.COLUMNS];
        java.util.Arrays.fill(allHit, NaturalWaterScanner.COLUMN_HIT);
        final boolean[] water = new boolean[NaturalWaterSources.CELLS];
        final NaturalWaterScanner.SourceProbe probe = (lx, ly, lz) -> water[idx(lx, ly, lz)];

        // ① 没登记过：任何格都不是自然水源
        pass(!sources.isRegistered(key));
        pass(!sources.isNaturalSource(key, 0));
        pass(!sources.isNaturalSource(key, 4095));
        pass(sources.bitSectionCount() == 0 && sources.registeredCount() == 0);

        // ② 首次登记：只有「生成时就在」的水源格进集合（含位边界 0 / 63 / 64 / 4095）
        water[idx(0, 0, 0)] = true;         // index 0
        water[idx(0, 3, 15)] = true;        // index 63
        water[idx(1, 0, 0)] = true;         // index 64
        water[idx(15, 15, 15)] = true;      // index 4095
        water[idx(1, 2, 3)] = true;
        pass(NaturalWaterScanner.register(sources, key, allHit, probe));
        pass(sources.isRegistered(key));
        pass(sources.isNaturalSource(key, idx(0, 0, 0)));
        pass(sources.isNaturalSource(key, idx(0, 3, 15)));
        pass(sources.isNaturalSource(key, idx(1, 0, 0)));
        pass(sources.isNaturalSource(key, idx(15, 15, 15)));
        pass(sources.isNaturalSource(key, idx(1, 2, 3)));
        pass(!sources.isNaturalSource(key, idx(2, 2, 3)));      // 同 section 的非水源格
        pass(sources.bitSectionCount() == 1);
        pass(sources.registeredCount() == 1);

        // ③ 二次「首次加载」：玩家后来放的水绝不补登记 —— 这是用户要的「自己放的没有效果」
        water[idx(4, 4, 4)] = true;
        pass(!NaturalWaterScanner.register(sources, key, allHit, probe));
        pass(!sources.isNaturalSource(key, idx(4, 4, 4)));
        pass(sources.isNaturalSource(key, idx(0, 0, 0)));       // 老的自然水源不丢
        pass(sources.registeredCount() == 1);

        // ④ 群系一列都没命中：不登记、不占条目（非水系群系完全不付出存档代价）
        final long missKey = SectionCursor.key(9, 0, 9);
        final byte[] allMiss = new byte[NaturalWaterScanner.COLUMNS];
        java.util.Arrays.fill(allMiss, NaturalWaterScanner.COLUMN_MISS);
        pass(!NaturalWaterScanner.register(sources, missKey, allMiss, (lx, ly, lz) -> true));
        pass(!sources.isRegistered(missKey));
        pass(!sources.isNaturalSource(missKey, 0));
        pass(sources.bitSectionCount() == 1 && sources.registeredCount() == 1);

        // ⑤ 群系命中但整段一格水都没有：仍然要标「已登记」
        //    ⇒ 玩家之后在这段放的水也不会被当成自然水源（高空 section 也必须记住「看过了」）
        final long emptyKey = SectionCursor.key(9, 0, 10);
        pass(NaturalWaterScanner.register(sources, emptyKey, allHit, (lx, ly, lz) -> false));
        pass(sources.isRegistered(emptyKey));
        pass(!sources.isNaturalSource(emptyKey, 0));
        pass(sources.bitSectionCount() == 1 && sources.registeredCount() == 2);

        // ⑥ 逐列：只有命中列上的水源格才算（列不命中的格哪怕有水也不收）
        final long partKey = SectionCursor.key(9, 0, 11);
        final byte[] oneColumn = new byte[NaturalWaterScanner.COLUMNS];
        oneColumn[NaturalWaterScanner.columnIndex(2, 3)] = NaturalWaterScanner.COLUMN_HIT;
        pass(NaturalWaterScanner.register(sources, partKey, oneColumn, (lx, ly, lz) -> true));
        boolean columnOk = true;
        for (int ly = 0; ly < 16; ly++) {
            columnOk &= sources.isNaturalSource(partKey, idx(2, ly, 3));
        }
        pass(columnOk);
        pass(!sources.isNaturalSource(partKey, idx(3, 3, 3)));  // 相邻列不命中
        pass(!sources.isNaturalSource(partKey, idx(2, 3, 4)));  // 相邻列不命中（z 方向）
        pass(sources.registeredCount() == 3);

        // ⑦ 列命中表判定辅助
        pass(NaturalWaterScanner.anyColumnHit(oneColumn));
        pass(!NaturalWaterScanner.anyColumnHit(allMiss));
        pass(NaturalWaterScanner.isColumnHit(oneColumn, 2, 3));
        pass(!NaturalWaterScanner.isColumnHit(oneColumn, 3, 3));
        pass(NaturalWaterScanner.columnIndex(2, 3) == 0x32);

        // ⑧ 序列化往返（neoforge 的 SavedData 只是把这两份数据搬进 NBT）
        final NaturalWaterSources copy = new NaturalWaterSources();
        for (final Map.Entry<Long, long[]> e : sources.bitSections().entrySet()) {
            copy.putBitSection(e.getKey(), e.getValue());
        }
        for (final long k : sources.registeredKeys()) {
            copy.putRegistered(k);
        }
        pass(copy.isRegistered(key) && copy.isRegistered(emptyKey) && copy.isRegistered(partKey));
        pass(!copy.isRegistered(missKey));
        pass(copy.isNaturalSource(key, idx(0, 0, 0)) && !copy.isNaturalSource(key, idx(4, 4, 4)));
        pass(copy.isNaturalSource(partKey, idx(2, 5, 3)) && !copy.isNaturalSource(partKey, idx(3, 5, 3)));
        pass(copy.bitSectionCount() == sources.bitSectionCount());
        pass(copy.registeredCount() == sources.registeredCount());

        // 长度不符的位图一律忽略（外部破坏的存档不能污染其余数据）
        final NaturalWaterSources bad = new NaturalWaterSources();
        bad.putBitSection(key, new long[3]);
        pass(!bad.isRegistered(key));
        pass(bad.bitSectionCount() == 0);

        // ⑨ clear 全清
        sources.clear();
        pass(!sources.isRegistered(key) && !sources.isNaturalSource(partKey, idx(2, 5, 3)));
        pass(sources.bitSectionCount() == 0 && sources.registeredCount() == 0);
    }

    private static void testMirrorSpread() {
        final WaterLevelField f = solidField();
        final WaterWorkSet active = new WaterWorkSet();
        for (int x = 0; x < 10; x++) {
            f.setKind(idx(x, 5, 5), FluidCellKind.AIR);
            active.add(idx(x, 5, 5));
        }
        f.setLevel(idx(9, 5, 5), 8);                    // 水源在**右端**（正向用例的水源在左端）

        final ArrayView view = new ArrayView();
        final long key = SectionCursor.key(0, 0, 0);
        pass(SpreadSolver.equalizeLines(f, view, key, active, 16) == 28);
        pass(f.volume() == 8);
        boolean laid = true;
        for (int x = 2; x < 10; x++) {
            laid &= f.level(idx(x, 5, 5)) == 1;         // 8 单位铺到右 8 格
        }
        for (int x = 0; x < 2; x++) {
            laid &= f.level(idx(x, 5, 5)) == 0;         // 水不够铺满：左 2 格还是干的
        }
        pass(laid);

        // 已经到平衡（每格 1、水位 1 是末端）⇒ 再来一轮一次都不搬
        final WaterWorkSet again = new WaterWorkSet();
        again.add(idx(2, 5, 5));
        pass(SpreadSolver.equalizeLines(f, view, key, again, 16) == 0);
        pass(f.volume() == 8);
    }

    // ---------- 6.97h 连通器：水面必须沿连通水域拉平（2026-09-28） ----------

    /**
     * U 形管：左井 → 底部通道 → 右井。
     *
     * <p>修复前：均衡器只沿一条直线走（允许上下起伏一层），走到井壁就断 ⇒
     * 左井的水柱永远悬在原地（水面 4 格高）、右井一滴都进不去 —— 实机正是「一边高一边低」。
     * 修复后：均衡器沿<b>连通水域</b>广搜，水沿通道绕到右井、水面按连通体摊平。
     */
    private static void testConnectorUTube() {
        final WaterLevelField f = solidField();
        for (int y = 0; y <= 5; y++) {
            f.setKind(idx(0, y, 0), FluidCellKind.AIR);
            f.setKind(idx(8, y, 0), FluidCellKind.AIR);
        }
        for (int x = 0; x <= 8; x++) {
            f.setKind(idx(x, 0, 0), FluidCellKind.AIR);
        }
        for (int y = 0; y <= 5; y++) {
            f.setLevel(idx(0, y, 0), 8);
        }
        final int volume0 = f.volume();
        pass(volume0 == 48);

        final ArrayView view = new ArrayView();
        final long key = SectionCursor.key(0, 0, 0);
        // 真机语义：本轮水位变了的格 + 它的六邻居被唤醒（写回唤醒 postApplied）
        final boolean[] active = new boolean[WaterLevelField.CELLS];
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            if (f.capacity(i) > 0) {
                active[i] = true;
            }
        }
        for (int round = 0; round < 80; round++) {
            if (!driveOneRound(f, view, key, active)) {
                break;
            }
        }
        pass(f.volume() == volume0);
        // 水面等高 = 有水的那一层内部差 ≤ 1（整数水位摊平的极限）
        int min = WaterLevelField.MAX_LEVEL;
        int max = 0;
        for (int x = 0; x <= 8; x++) {
            final int l = f.level(idx(x, 0, 0));
            min = Math.min(min, l);
            max = Math.max(max, l);
        }
        pass(min >= 5 && max - min <= 1);       // 48 单位 / 底层 72 容量 ⇒ 5~6
        // 水量根本不够填到第二层：底层 9 格容量 72 > 48 ⇒ 按重力堆叠 H* = 48/9 = 5、
        // 目标水面就在 y=0 ⇒ y ≥ 1 必须一滴都没有（修复前这里是 8）。
        //   ★ 实测（压力模型版）：收敛后 (8,1,0) 会剩下 1 单位，底层只有 47 ——
        //     stepCell 按重力把它往下搬 1，equalizeLines 又按「超出量最低的邻居」把它搬回上面，
        //     一轮净变化 = 0 ⇒ 驱动端认为收敛、就此停住（把 active 换成全格再单独调 equalizeLines，
        //     它照样返回 moved > 0 而水位原地不动 ⇒ 它也没把这一格收回去）。
        //     这条断言是那个死循环的哨兵：它断言的是新模型**自己**算出的平衡水面（水全在 y=0），
        //     不是旧语义，所以这里保留不改 —— 要绿必须修 SpreadSolver（两套模型对同一单位的取舍要一致）。
        boolean lifted = false;
        for (int y = 1; y <= 5; y++) {
            lifted |= f.level(idx(0, y, 0)) > 0 || f.level(idx(8, y, 0)) > 0;
        }
        pass(!lifted);
    }

    /**
     * 连通器必须能把水面<b>抬起来</b>：水量超过最底层容量时，水得进到第二层。
     *
     * <p>修复前：低侧井底满 8 之后水再也进不去（没有任何往上一层填的路径）⇒
     * 一侧 10 格水柱、另一侧永远空 —— 这是「一边高一边低」最刺眼的形态。
     */
    private static void testConnectorLift() {
        final WaterLevelField f = solidField();
        for (int y = 0; y <= 9; y++) {
            f.setKind(idx(0, y, 0), FluidCellKind.AIR);
            f.setKind(idx(4, y, 0), FluidCellKind.AIR);
        }
        for (int x = 0; x <= 4; x++) {
            f.setKind(idx(x, 0, 0), FluidCellKind.AIR);
        }
        for (int y = 0; y <= 9; y++) {
            f.setLevel(idx(0, y, 0), 8);
        }
        final int volume0 = f.volume();
        pass(volume0 == 80);                    // 底层容量只有 5 格 × 8 = 40 ⇒ 必须抬升

        final ArrayView view = new ArrayView();
        final long key = SectionCursor.key(0, 0, 0);
        final boolean[] active = new boolean[WaterLevelField.CELLS];
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            if (f.capacity(i) > 0) {
                active[i] = true;
            }
        }
        for (int round = 0; round < 200; round++) {
            if (!driveOneRound(f, view, key, active)) {
                break;
            }
        }
        pass(f.volume() == volume0);
        final int left = f.level(idx(0, 1, 0));
        final int right = f.level(idx(4, 1, 0));
        pass(left > 0 && right > 0);               // 两侧都得有水抬上来
        pass(Math.abs(left - right) <= 1);                 // 连通器：两侧水面等高
    }

    // ---------- 6.97i 平地倒水不许「乱跑」（2026-09-28 第二轮回归） ----------

    /**
     * 平地倒一桶水：水必须**就地摊开**，不许跑到十几格外去。
     *
     * <p>修复前：均衡器的候选按 region 线性索引排序（同层等高的候选取「索引最小」的那一格），
     * 水会一路朝 -x/-z 方向跑出去（实机症状：倒水后水乱跑一段距离、几十次运算后才停）。
     * 现在排序键是「绝对水位 → 到本格的距离 → 格索引」，而且广搜**只沿水体走**（不许跳过空气到远处）。
     */
    private static void testPourOnFlat() {
        final WaterLevelField f = solidField();
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 15; z++) {
                f.setKind(idx(x, 0, z), FluidCellKind.AIR);
                f.setKind(idx(x, 1, z), FluidCellKind.AIR);
            }
        }
        final int cx = 8;
        final int cz = 8;
        f.setLevel(idx(cx, 0, cz), 8);
        final ArrayView view = new ArrayView();
        final long key = SectionCursor.key(0, 0, 0);
        final boolean[] active = new boolean[WaterLevelField.CELLS];
        wakeAround(active, idx(cx, 0, cz));
        for (int round = 0; round < 60; round++) {
            if (!driveOneRound(f, view, key, active)) {
                break;
            }
        }
        pass(f.volume() == 8);
        int wet = 0;
        int spread = 0;
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 15; z++) {
                if (f.level(idx(x, 0, z)) > 0) {
                    wet++;
                    spread = Math.max(spread, Math.abs(x - cx) + Math.abs(z - cz));
                }
            }
        }
        pass(wet > 1);
        pass(spread <= 2);        // 修复前这里是 15+（水跑到 region 角上）
    }

    /**
     * 推进一步（= 真机一个 tick 的一轮求解）：对 active 跑 spread + 均衡器，
     * 然后把「水位变了的格 + 六邻居」设成下一轮的 active（写回唤醒 postApplied 的语义）。
     *
     * @return false = 这一轮一步没动（收敛）
     */
    private static boolean driveOneRound(final WaterLevelField f, final ArrayView view,
                                         final long key, final boolean[] active) {
        final byte[] before = new byte[WaterLevelField.CELLS];
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            before[i] = (byte) f.level(i);
        }
        final WaterWorkSet work = new WaterWorkSet();
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            if (active[i]) {
                work.add(i);
            }
        }
        if (work.size() == 0) {
            return false;
        }
        SpreadSolver.step(f, view, key, work, new FluidWritePlan(), Integer.MAX_VALUE);
        SpreadSolver.equalizeLines(f, view, key, work, 16);
        boolean changed = false;
        java.util.Arrays.fill(active, false);
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            if ((before[i] & 0xFF) != f.level(i)) {
                changed = true;
                wakeAround(active, i);
            }
        }
        return changed;
    }

    /** 唤醒一格 + 它的六邻居（= 写回唤醒 postApply 的语义）。 */
    private static void wakeAround(final boolean[] active, final int index) {
        final int lx = SectionCursor.localX(index);
        final int ly = SectionCursor.localY(index);
        final int lz = SectionCursor.localZ(index);
        wakeCell(active, lx, ly, lz);
        wakeCell(active, lx - 1, ly, lz);
        wakeCell(active, lx + 1, ly, lz);
        wakeCell(active, lx, ly - 1, lz);
        wakeCell(active, lx, ly + 1, lz);
        wakeCell(active, lx, ly, lz - 1);
        wakeCell(active, lx, ly, lz + 1);
    }

    private static void wakeCell(final boolean[] active, final int lx, final int ly, final int lz) {
        if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) {
            return;
        }
        active[SectionCursor.linearIndex(lx, ly, lz)] = true;
    }

    // ---------- 6.97g 外部注入必须压过侧表：在已有水里放水源（2026-09-28 回归闸门） ----------
    private static void testExternalInjection() {
        // ★ 规则本身：世界有水 && 侧表非 0 ⇒ 取侧表（求解器是权威）。
        //   这正是「在已有水里放水源不生效」的来源：新放的水源世界值是 8，侧表还留着旧的 3，
        //   求解器于是看到 3 —— 和邻居一样平，一步都不动。
        pass(FluidLevels.resolve(8, 3) == 3);
        // ★ 所以外部变更（postCell）必须清掉「自己 + 六邻居」的侧表（WaterLevelStore.forget）：
        //   清掉后 resolve 取世界的 8，外部注入才真的进来。
        pass(FluidLevels.resolve(8, 0) == 8);
        // 世界没水 ⇒ 一律 0：外部清除生效，不留「幽灵水」。
        pass(FluidLevels.resolve(0, 3) == 0);
        pass(FluidLevels.resolve(0, 0) == 0);
    }

    // ---------- 6.97h 薄水末端：水位 1 的水不会自己滑到远处（旧「薄层滑移」逻辑已删除） ----------
    private static void testThinWaterStops() {
        // ★ 新模型里「水位 1 = 末端」由两处共同保证：equalizeLines 的 stub 判定
        //   （level ≤ 1 且本格水面低于上一层 ⇒ 只允许顺着重力往下让）与 stepCell 的 level ≤ 1 直接返回 0。
        //   所以 1 单位的水**不许横向搬运**，只有被重新填到 ≥ 2 才再次扩散。
        //   这里前方 (2,5,5) 的下方就是落差：以前会被「薄层滑移」整格搬过去，现在必须原地不动。
        final WaterLevelField f = solidField();
        for (int x = 0; x < 8; x++) {
            f.setKind(idx(x, 5, 5), FluidCellKind.AIR);
        }
        f.setKind(idx(2, 4, 5), FluidCellKind.AIR);
        f.setLevel(idx(0, 5, 5), 1);
        final ArrayView view = new ArrayView();
        final long key = SectionCursor.key(0, 0, 0);
        final WaterWorkSet active = new WaterWorkSet();
        active.add(idx(0, 5, 5));
        pass(SpreadSolver.equalizeLines(f, view, key, active, 16) == 0);
        pass(f.level(idx(0, 5, 5)) == 1);
        pass(f.level(idx(1, 5, 5)) == 0);
        pass(f.volume() == 1);

        // ★ 被重新填到 2 ⇒ 再次扩散（让 1 单位给水头最低的邻居：0 变 1、(1,5,5) 得 1）
        final WaterLevelField g = solidField();
        for (int x = 0; x < 8; x++) {
            g.setKind(idx(x, 5, 5), FluidCellKind.AIR);
        }
        g.setLevel(idx(0, 5, 5), 2);
        final WaterWorkSet activeG = new WaterWorkSet();
        activeG.add(idx(0, 5, 5));
        pass(SpreadSolver.equalizeLines(g, view, key, activeG, 16) == 1);
        pass(g.level(idx(0, 5, 5)) == 1);
        pass(g.level(idx(1, 5, 5)) == 1);
        pass(g.volume() == 2);
    }

    // ---------- 6.97i 水头驱动：一次只让 1 单位给「水头最低」的邻居（最小值收集） ----------
    //   （方法名是历史遗留：旧的「最低档均分 sum/count」逻辑已删除，
    //     移入 弃置区/SpreadSolver-dead-code-2026-09-29.txt）
    private static void testLowestNeighbourAveraging() {
        // ★ 满水挨着浅水：{8, 3} —— 邻居水头 = 5*8 + 3 = 43，自己的水头 = 5*8 + 8 = 48，
        //   43 + 1 < 48 ⇒ 让 1 单位 ⇒ {7, 4}。
        //   （旧的「最低档均分 ⇒ sum 11 / count+1 2 ⇒ 6 / 5」已经删除，不再是均分。）
        final WaterLevelField f = solidField();
        f.setKind(idx(0, 5, 5), FluidCellKind.AIR);
        f.setKind(idx(1, 5, 5), FluidCellKind.AIR);
        f.setLevel(idx(0, 5, 5), 8);
        f.setLevel(idx(1, 5, 5), 3);
        final ArrayView view = new ArrayView();
        final long key = SectionCursor.key(0, 0, 0);
        final FluidWritePlan plan = new FluidWritePlan();
        pass(SpreadSolver.stepCell(f, view, key, idx(0, 5, 5), plan) > 0);
        pass(f.level(idx(0, 5, 5)) == 7);       // 自己只减 1
        pass(f.level(idx(1, 5, 5)) == 4);       // 邻居只加 1
        pass(f.volume() == 11);

        // ★ 「水头最低」是唯一判据：{8, 7,7,6,6} 的四邻水头 = 47、47、46、46
        //   ⇒ 最低的是 46（+z = (5,5,6) 与 −z = (5,5,4)），同水头按 AXIAL 顺序取第一个 ⇒ +z 拿到 1。
        //   一次只搬 1 单位、且要求 nHead + 1 < ownHead，所以永远不会算出「自己加到 10」那种越界
        //   （旧的整格均分才会）。
        final WaterLevelField g = solidField();
        g.setKind(idx(5, 5, 5), FluidCellKind.AIR);
        g.setKind(idx(6, 5, 5), FluidCellKind.AIR);
        g.setKind(idx(4, 5, 5), FluidCellKind.AIR);
        g.setKind(idx(5, 5, 6), FluidCellKind.AIR);
        g.setKind(idx(5, 5, 4), FluidCellKind.AIR);
        g.setLevel(idx(5, 5, 5), 8);
        g.setLevel(idx(6, 5, 5), 7);
        g.setLevel(idx(4, 5, 5), 7);
        g.setLevel(idx(5, 5, 6), 6);
        g.setLevel(idx(5, 5, 4), 6);
        final FluidWritePlan plan2 = new FluidWritePlan();
        pass(SpreadSolver.stepCell(g, view, key, idx(5, 5, 5), plan2) == 1);
        pass(g.level(idx(5, 5, 5)) == 7);       // 中心让出 1
        pass(g.level(idx(6, 5, 5)) == 7);       // +x 水头 47 ⇒ 不是最低，不动
        pass(g.level(idx(5, 5, 6)) == 7);       // +z：最低水头里 AXIAL 排最前 ⇒ +1
        pass(g.level(idx(5, 5, 4)) == 6);       // −z 水头一样低但排在 +z 之后 ⇒ 这一次没有
        pass(g.volume() == 34);                 // 守恒
    }

    /** 造一片会流的水：{8,0,0} 三格连通（水头驱动：8 那格让 1 单位给水头最低的邻居 = 空邻居）。 */
    private static WaterLevelField spreadScene() {
        final WaterLevelField f = solidField();
        f.setKind(idx(0, 0, 0), FluidCellKind.AIR);
        f.setKind(idx(1, 0, 0), FluidCellKind.AIR);
        f.setKind(idx(2, 0, 0), FluidCellKind.AIR);
        f.setLevel(idx(0, 0, 0), 8);
        return f;
    }

    // ---------- 7. 写意图：坐标打包 + 分帧消费 ----------
    private static void testWritePlan() {
        final int x = -1000;
        final int y = -64;
        final int z = 2000;
        final long packed = FluidWritePlan.packPos(x, y, z);
        pass(FluidWritePlan.unpackX(packed) == x);
        pass(FluidWritePlan.unpackY(packed) == y);
        pass(FluidWritePlan.unpackZ(packed) == z);
        pass(FluidWritePlan.packPos(0, 0, 0) == 0L);

        final FluidWritePlan plan = new FluidWritePlan();
        for (int i = 0; i < 10; i++) {
            plan.addLevel(i, 0, 0, i % 8);
        }
        pass(plan.levelCount() == 10);
        pass(plan.pendingLevels() == 10);

        final List<Long> seen = new ArrayList<>();
        int consumed = 0;
        int frames = 0;
        while (plan.pendingLevels() > 0) {
            consumed += plan.consumeLevels(3, FluidWritePlan.positions(seen::add));
            frames++;
        }
        pass(consumed == 10);
        pass(seen.size() == 10);
        pass(frames == 4);
        boolean noDup = true;
        for (int i = 0; i < seen.size(); i++) {
            if (FluidWritePlan.unpackX(seen.get(i)) != i) {
                noDup = false;
                break;
            }
        }
        pass(noDup);
        pass(!plan.hasPending());

        plan.addBlock(1, 2, 3);
        plan.addBlock(1, 2, 3);
        pass(plan.blockCount() == 2);
        pass(plan.hasBlock(FluidWritePlan.packPos(1, 2, 3)));
        pass(!plan.hasBlock(FluidWritePlan.packPos(1, 2, 4)));
        pass(plan.consumeBlocks(1, pos -> { }) == 1);
        pass(plan.pendingBlocks() == 1);
        plan.compact();
        pass(plan.blockCount() == 1);

        // ★ 消费预算溢出回归：游标非 0 时传 Integer.MAX_VALUE。
        //   旧写法 `Math.min(count, cursor + budget)` 会溢出成负数 ⇒ 一条都不消费 ⇒
        //   队首永久 hasPending ⇒ 写回队列堵死。减法写法必须把剩下的全部消费掉。
        final FluidWritePlan big = new FluidWritePlan();
        for (int i = 0; i < 5; i++) {
            big.addLevel(i, 0, 0, i % 8);
            big.addBlock(i, 1, 0);
        }
        pass(big.consumeLevels(2, FluidWritePlan.IGNORE) == 2);
        pass(big.consumeBlocks(2, pos -> { }) == 2);
        pass(big.pendingLevels() == 3 && big.pendingBlocks() == 3);
        pass(big.consumeLevels(Integer.MAX_VALUE, FluidWritePlan.IGNORE) == 3);
        pass(big.consumeBlocks(Integer.MAX_VALUE, pos -> { }) == 3);
        pass(!big.hasPending());

        plan.clear();
        pass(plan.levelCount() == 0 && plan.blockCount() == 0);
    }

    // ---------- 8. 黑盒模型 ----------
    private static void testBodyModel() {
        final FluidBodyModel m = new FluidBodyModel();
        final long key = SectionCursor.key(0, 0, 0);
        m.bind(key);
        pass(m.regionKey() == key);
        pass(m.volume() == 0);

        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            m.field().setKind(i, FluidCellKind.SOLID);
        }
        m.setKind(0, 0, 0, FluidCellKind.AIR);
        m.setKind(1, 0, 0, FluidCellKind.AIR);
        m.setLevel(0, 0, 0, 8);
        m.setLevel(1, 0, 0, 0);
        pass(m.level(0, 0, 0) == 8);
        pass(m.kind(1, 0, 0) == FluidCellKind.AIR);
        pass(m.volume() == 8);
        pass(!m.isEqualized());

        final int changed = m.equalize();
        pass(changed == 2);
        pass(m.level(0, 0, 0) == 4 && m.level(1, 0, 0) == 4);
        pass(m.isEqualized());
        pass(m.volume() == 8);

        final ArrayView view = new ArrayView();
        final FluidWritePlan plan = new FluidWritePlan();
        pass(m.spreadStep(view, plan) == 0);
    }

    // ---------- 9. 规则表 ----------
    private static void testRules() {
        final FluidRules rules = new FluidRules();
        pass(rules.passable("minecraft:oak_fence") == FluidRules.PASS_DEFAULT);
        pass(rules.isPassable("minecraft:oak_fence"));
        rules.setPassable("minecraft:obsidian", FluidRules.PASS_NEVER);
        pass(!rules.isPassable("minecraft:obsidian"));
        rules.setPassable("minecraft:iron_bars", FluidRules.PASS_FORCE);
        pass(rules.passable("minecraft:iron_bars") == FluidRules.PASS_FORCE);
        pass(rules.size() == 2);
        rules.setDestroyable("minecraft:torch");
        pass(rules.isDestroyable("minecraft:torch"));
        pass(!rules.isDestroyable("minecraft:stone"));
        pass(FluidRules.passableByKind(FluidCellKind.AIR));
        pass(!FluidRules.passableByKind(FluidCellKind.SOLID));
        pass(FluidCellKind.capacity(FluidCellKind.SOLID) == 0);
        pass(FluidCellKind.capacity(FluidCellKind.WATERLOGGABLE) == 8);
        // ★ PASSABLE（栅栏/树叶/玻璃板）**不能**容纳水：水位投影不到这类格子，
        //   放进去下一批就会被判「外部清除」而丢水（见 FluidCellKind.canHold 的注释）。
        pass(!FluidCellKind.canHold(FluidCellKind.PASSABLE));
        pass(FluidCellKind.capacity(FluidCellKind.PASSABLE) == 0);
        pass(FluidCellKind.canHold(FluidCellKind.AIR));
        pass(FluidCellKind.canHold(FluidCellKind.FLUID));
    }

    // ==================================================================
    // 10. 阶段 2b：整片连通水采集（跨 region / 未加载 / 预算 / 守恒 / 写意图分组）
    // ==================================================================

    /**
     * 【跨 region 的整片展开】只点名一格，整片水（x = 8..27，跨过 16 边界）必须全部进片 ——
     * region / chunk 概念在采集器里一次都不出现。
     */
    private static void testBodyCollectorCrossRegion() {
        final PieceWorld w = trench(8, 27);
        final long[] seeds = {FluidWritePlan.packPos(10, 1, 0)};
        final FluidBodyCollector.Piece piece =
                new FluidBodyCollector().collect(w, seeds, seeds.length, 1 << 20);

        pass(!piece.unfinished());                       // 世界到头了（未加载/固体），不是预算截断
        pass(piece.fluid() == FluidKind.WATER);
        pass(piece.cells() >= 20);                       // 20 格水全在片内
        for (int x = 8; x <= 27; x++) {
            pass(piece.cellIndexAt(x, 1, 0) >= 0);       // 含跨过 16 的那一段
        }
        pass(piece.cellIndexAt(15, 1, 0) >= 0 && piece.cellIndexAt(16, 1, 0) >= 0);
        // 片确实横跨两个 section（x >> 4 = 0 与 1）—— region 只是写回分组的键，不是采集的边界
        pass(FluidWritePlan.sectionKeyOf(FluidWritePlan.packPos(15, 1, 0))
                != FluidWritePlan.sectionKeyOf(FluidWritePlan.packPos(16, 1, 0)));
        // 左端空格（下方固体 ⇒ 停得住）进片内的容器域；右端空格下方也是空 ⇒ 停不住 ⇒ 只能当边界格
        pass(piece.cellIndexAt(7, 1, 0) >= 0);
        pass(piece.cellIndexAt(28, 1, 0) < 0);
        pass(piece.borderIndexAt(28, 1, 0) >= 0);
        // 固体：既不是片内也不是边界格（引擎对两者的判定相同）
        pass(piece.cellIndexAt(10, 2, 0) < 0);
        pass(piece.borderIndexAt(10, 2, 0) < 0);
    }

    /**
     * 【未加载截断】未加载是唯一的天然边界：既不进片内，也不给边界格
     * （那一侧一滴水都不许出去），并且它不是「没采完」。
     */
    private static void testBodyCollectorUnloaded() {
        final PieceWorld w = trench(0, 24);
        for (int x = 20; x <= 26; x++) {
            for (int y = 0; y <= 2; y++) {
                w.unload(x, y, 0);
            }
        }
        final long[] seeds = {FluidWritePlan.packPos(5, 1, 0)};
        final FluidBodyCollector.Piece piece =
                new FluidBodyCollector().collect(w, seeds, seeds.length, 1 << 20);

        pass(!piece.unfinished());                       // 未加载截断不是「没采完」
        pass(piece.cellIndexAt(19, 1, 0) >= 0);          // 加载范围里最右的水格进片
        pass(piece.cellIndexAt(20, 1, 0) < 0);           // 未加载 ⇒ 不进片内
        pass(piece.borderIndexAt(20, 1, 0) < 0);         // 未加载 ⇒ 也不给边界格

        // 引擎算一步：一步都不许往未加载的那一侧搬（守恒）
        final FluidDelta delta = new FluidDelta();
        FluidEngine.step(piece.body(), delta);
        int outside = 0;
        for (int k = 0; k < delta.size(); k++) {
            if (FluidWritePlan.unpackX(delta.packed(k)) >= 20) {
                outside++;
            }
        }
        pass(outside == 0);
        pass(conserved(piece.body(), delta));
    }

    /** 【预算不足分片且 unfinished】预算用尽是「没采完」，绝不是「水不动了」。 */
    private static void testBodyCollectorBudget() {
        final PieceWorld w = trench(0, 99);
        final long[] seeds = {FluidWritePlan.packPos(0, 1, 0)};

        final FluidBodyCollector.Piece cut = new FluidBodyCollector().collect(w, seeds, 1, 10);
        pass(cut.unfinished());                          // 预算用尽 ⇒ 没采完
        pass(cut.cells() <= 10);                         // 不许超预算
        pass(cut.body().size() == cut.cells());
        pass(!cut.empty());                              // 仍然是「有水的片」，能算
        pass(cut.reads() > 0);

        final FluidBodyCollector.Piece full = new FluidBodyCollector().collect(w, seeds, 1, 1 << 20);
        pass(!full.unfinished());
        pass(full.cells() >= 100);

        final FluidBodyCollector.Piece zero = new FluidBodyCollector().collect(w, seeds, 1, 0);
        pass(zero.unfinished());                         // ★ 预算 0 ≠ 不动点（驱动层不许判收敛）
        pass(zero.cells() == 0);
    }

    /**
     * 【守恒】端到端：每轮「从变化格出发采整片 → 引擎一步 → 写回世界」，
     * 直到引擎报不动点。8 单位水跨过 16 边界铺开，总量逐单位不变。
     */
    private static void testBodyCollectorConservation() {
        final PieceWorld w = new PieceWorld();
        for (int x = 13; x <= 32; x++) {
            w.put(x, 0, 0, FluidCellKind.SOLID, 0);      // 地板（左端 x = 14 在下面被改成墙）
            w.put(x, 2, 0, FluidCellKind.SOLID, 0);      // 顶
            w.put(x, 1, -1, FluidCellKind.SOLID, 0);
            w.put(x, 1, 1, FluidCellKind.SOLID, 0);
            w.put(x, 1, 0, FluidCellKind.AIR, 0);
        }
        w.put(14, 1, 0, FluidCellKind.SOLID, 0);         // 左侧一堵墙 ⇒ 水只能往 +x（必然跨 16）
        w.put(15, 1, 0, FluidCellKind.FLUID, 8);         // 倒 8 单位

        final FluidBodyCollector collector = new FluidBodyCollector();
        final FluidDelta delta = new FluidDelta();
        long[] seeds = {FluidWritePlan.packPos(15, 1, 0)};
        int rounds = 0;
        while (rounds < 200) {
            final FluidBodyCollector.Piece piece = collector.collect(w, seeds, seeds.length, 1 << 20);
            if (piece.empty()) {
                break;
            }
            final int moved = FluidEngine.step(piece.body(), delta);
            final long[] next = new long[delta.size()];
            for (int k = 0; k < delta.size(); k++) {
                final long p = delta.packed(k);
                final int v = delta.amount(k);
                // 写回：与 FluidApplier 同口径（有量 ⇒ 水格，0 ⇒ 空气）
                w.put(FluidWritePlan.unpackX(p), FluidWritePlan.unpackY(p), FluidWritePlan.unpackZ(p),
                        v > 0 ? FluidCellKind.FLUID : FluidCellKind.AIR, v);
                next[k] = p;
            }
            rounds++;
            if (moved == 0 && !delta.unfinished() && !piece.unfinished()) {
                break;                                   // 真的到不动点了
            }
            seeds = next;
        }
        pass(rounds < 200);
        pass(w.total() == 8);                            // 守恒：一滴不多一滴不少
        pass(w.wetCount() == 8);                         // 8 单位铺成 8 格
        pass(w.amountAt(20, 1, 0) > 0);                  // 确实铺过了 16 边界
    }

    /** 【写意图】跨 region 的片算一步后，写意图逐格对齐引擎的绝对新量（不拆组、不漏格）。 */
    private static void testBodyCollectorWriteIntent() {
        // 宽敞的平层（x = 8..40，跨 3 个 section）＋ 一格水源：引擎一步必定搬水
        // （整片每格水位 1 的「薄水层」会被自由水面末端规则钉住、一步都不搬，那样测不到变化集）。
        final PieceWorld w = new PieceWorld();
        for (int x = 8; x <= 40; x++) {
            w.put(x, 0, 0, FluidCellKind.SOLID, 0);
            w.put(x, 2, 0, FluidCellKind.SOLID, 0);
            w.put(x, 1, -1, FluidCellKind.SOLID, 0);
            w.put(x, 1, 1, FluidCellKind.SOLID, 0);
            w.put(x, 1, 0, FluidCellKind.AIR, 0);
        }
        // ★ 一轮 = 一次 sweep：一轮只推一格，所以把水源顶在 15、左侧 13/14 封死，
        //   它这一轮唯一的去处就是 x=16（section 0 → section 1），才测得到「按目标 section 分组」。
        w.put(13, 1, 0, FluidCellKind.SOLID, 0);
        w.put(14, 1, 0, FluidCellKind.SOLID, 0);
        w.put(15, 1, 0, FluidCellKind.FLUID, 8);
        final long[] seeds = {FluidWritePlan.packPos(15, 1, 0)};
        final FluidBodyCollector.Piece piece =
                new FluidBodyCollector().collect(w, seeds, seeds.length, 1 << 20);
        final FluidDelta delta = new FluidDelta();
        FluidEngine.step(piece.body(), delta);
        pass(delta.size() > 0);

        final FluidWritePlan plan = new FluidWritePlan();
        final int emitted = FluidWriteIntent.emit(delta, piece.body(), plan);
        pass(emitted == delta.size());                   // 一个变化格一条写意图，不漏格
        pass(plan.pendingLevels() == emitted);           // 水位段条数 = 变化集条数

        // ★ 值语义必须是「绝对新量」：直接落地就是引擎算出的值 ⇒ 同一片重复落地幂等
        //   （这是「片级认领 + 片级原子写回」不失守恒的前提）。这里若出现增量条目，
        //   说明又回到「侧表当前值 + delta」的双计口径了。
        final Map<Long, Integer> landed = new HashMap<>();
        plan.consumeLevels(plan.pendingLevels(), new FluidWritePlan.LevelSink() {
            @Override
            public void accept(final long packedPos, final int level) {
                landed.put(packedPos, level);
            }

            @Override
            public void acceptDelta(final long packedPos, final int delta) {
                throw new IllegalStateException("写意图不再产生增量条目（口径 = 绝对新量）");
            }
        });
        int exact = 0;
        for (int i = 0; i < piece.body().size(); i++) {
            final long packed = piece.body().packed(i);
            final int now = delta.newAmountAt(packed, Integer.MIN_VALUE);
            if (now != Integer.MIN_VALUE) {
                exact++;
                pass(landed.getOrDefault(packed, -1) == now);
            }
        }
        pass(exact >= 2);                                // 源格让出 1 + 目标格收 1
        pass(landed.size() == exact);                    // 没有多写的格
    }

    /**
     * 离线「水沟」：y = 1 层是水（x = fromX..toX），上下与两侧是固体；
     * 两端是空气容器，其中右端空格的下方也是空 ⇒ 它只能当<b>边界格</b>。
     */
    private static PieceWorld trench(final int fromX, final int toX) {
        final PieceWorld w = new PieceWorld();
        for (int x = fromX - 1; x <= toX + 1; x++) {
            w.put(x, 0, 0, FluidCellKind.SOLID, 0);
            w.put(x, 2, 0, FluidCellKind.SOLID, 0);
            w.put(x, 1, -1, FluidCellKind.SOLID, 0);
            w.put(x, 1, 1, FluidCellKind.SOLID, 0);
        }
        for (int x = fromX; x <= toX; x++) {
            w.put(x, 1, 0, FluidCellKind.FLUID, 1);
        }
        w.put(fromX - 1, 1, 0, FluidCellKind.AIR, 0);
        w.put(toX + 1, 1, 0, FluidCellKind.AIR, 0);
        w.put(toX + 1, 0, 0, FluidCellKind.AIR, 0);
        return w;
    }

    /** 片（片内 + 边界）在引擎一步之后总量守恒（水源是唯一例外，这里没有水源）。 */
    private static boolean conserved(final com.hdf.cryptand.fluid.FluidBodyView body,
                                     final FluidDelta delta) {
        final Map<Long, Integer> before = new HashMap<>();
        for (int i = 0; i < body.size(); i++) {
            before.put(body.packed(i), body.amount(i));
        }
        for (int j = 0; j < body.borderSize(); j++) {
            before.put(body.borderPacked(j), body.borderAmount(j));
        }
        final Map<Long, Integer> after = new HashMap<>(before);
        for (int k = 0; k < delta.size(); k++) {
            after.put(delta.packed(k), delta.amount(k));
        }
        int sumBefore = 0;
        for (final int v : before.values()) {
            sumBefore += v;
        }
        int sumAfter = 0;
        for (final int v : after.values()) {
            sumAfter += v;
        }
        return sumBefore == sumAfter;
    }

    /** 把一份写意图的水位条目全读出来（绝对值与增量一视同仁，只比「哪一格写了什么」）。 */
    private static Map<Long, Integer> levelsOf(final FluidWritePlan plan) {
        final Map<Long, Integer> out = new HashMap<>();
        plan.consumeLevels(plan.pendingLevels(), new FluidWritePlan.LevelSink() {
            @Override
            public void accept(final long packedPos, final int level) {
                out.put(packedPos, level);
            }

            @Override
            public void acceptDelta(final long packedPos, final int delta) {
                out.put(packedPos, delta);
            }
        });
        return out;
    }

    /** 离线「世界」（整片采集用）：Map 世界 + 可指定哪些格未加载。 */
    private static final class PieceWorld implements FluidBodyCollector.Source {

        private final Map<Long, Integer> kinds = new HashMap<>();
        private final Map<Long, Integer> levels = new HashMap<>();
        private final Set<Long> sources = new HashSet<>();
        private final Set<Long> unloaded = new HashSet<>();

        PieceWorld put(final int x, final int y, final int z, final int kind, final int level) {
            final long p = FluidWritePlan.packPos(x, y, z);
            kinds.put(p, kind);
            levels.put(p, level);
            return this;
        }

        PieceWorld source(final int x, final int y, final int z) {
            sources.add(FluidWritePlan.packPos(x, y, z));
            return this;
        }

        /** 把这一格标成「所在区块未加载」。 */
        PieceWorld unload(final int x, final int y, final int z) {
            unloaded.add(FluidWritePlan.packPos(x, y, z));
            return this;
        }

        int amountAt(final int x, final int y, final int z) {
            return levels.getOrDefault(FluidWritePlan.packPos(x, y, z), 0);
        }

        /** 世界总水量（守恒断言用）。 */
        int total() {
            int sum = 0;
            for (final int v : levels.values()) {
                sum += v;
            }
            return sum;
        }

        /** 水量 &gt; 0 的格数。 */
        int wetCount() {
            int c = 0;
            for (final int v : levels.values()) {
                if (v > 0) {
                    c++;
                }
            }
            return c;
        }

        @Override
        public boolean isLoaded(final int x, final int y, final int z) {
            return !unloaded.contains(FluidWritePlan.packPos(x, y, z));
        }

        @Override
        public int kind(final int x, final int y, final int z) {
            return kinds.getOrDefault(FluidWritePlan.packPos(x, y, z), FluidCellKind.SOLID);
        }

        @Override
        public int level(final int x, final int y, final int z) {
            return amountAt(x, y, z);
        }

        @Override
        public boolean isSource(final int x, final int y, final int z) {
            return sources.contains(FluidWritePlan.packPos(x, y, z));
        }
    }
}
