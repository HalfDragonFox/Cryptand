package com.hdf.cryptand.fluid;

import com.hdf.cryptand.core.frame.SectionCursor;
import com.hdf.cryptand.waterphysics.FluidCellKind;
import com.hdf.cryptand.waterphysics.FluidCellView;
import com.hdf.cryptand.waterphysics.FluidWritePlan;
import com.hdf.cryptand.waterphysics.SpreadSolver;
import com.hdf.cryptand.waterphysics.WaterLevelField;
import com.hdf.cryptand.waterphysics.WaterWorkSet;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 液体物理引擎离线闸门（纯 Java 零 MC）：
 *   ./gradlew :common:runFluidTest
 *
 * <p>覆盖用户定案的 5 个场景 + 两条必须钉住的语义：
 * <ol>
 *   <li>平地倒 8 单位 ⇒ 铺成 8 格各 1（与 {@code %TEMP%\wpsim\Wp3} 的 S1 同源）；</li>
 *   <li>沟槽 10 格倒 8 单位 ⇒ 前 8 格各 1、后 2 格 0（与 Wp3 基线逐格一致）；</li>
 *   <li>U 形管：水量超过底层容量必须抬升，两侧水面差 ≤ 1 片（1 单位）；</li>
 *   <li>跨区块的片（x = 14..20 故意跨过 16）⇒ 水面照样拉平 —— 旧实现做不到，这是新引擎存在的理由；</li>
 *   <li>未加载截断：那一侧没有边界格 ⇒ 水一步都不许出去，总量守恒。</li>
 * </ol>
 * 另外钉住：边界格能接水（片会逐轮长大）、恒定水源是守恒的唯一例外、
 * 「水位 1 = 自由水面末端」不许横向滑动。
 *
 * <p>审查缺陷回归闸门（2026-09-28 审查 B1..B8）：含水方块只接受整格（中间档一个都不许注入）/
 * 预算 0 与预算中途用尽<b>不</b>被判成收敛（{@link FluidDelta#unfinished()}）/
 * 同一个 {@link FluidDelta} 连跑多步不串味 / 超容量 amount 被 clamp 且守恒 /
 * 混液片（水 + 岩浆）互不搬运 / 悬空与离群水源两条路径都补回满格 /
 * null kind 入口即抛 / 单步分配基准（片 64 / 512 / 4096 格）。
 *
 * <p>驱动方式 = 转接类的离线版：每轮「从有水格拼出一整片 → 引擎算一步 → 把变化集写回世界」，
 * 直到 {@code step} 返回 0（整片再也动不了）。
 *
 * <p>阶段 2a 追加：<b>单 region 装配</b>（{@link FluidRegionAssembly}：FluidCellKind → {@link FluidKind}
 * 映射表、片内格取舍、容量、恒定水源、一圈边界格的可用性）与<b>写意图换算</b>
 * （{@link FluidWriteIntent}：绝对新量 → 增量，落地口径对齐 {@code FluidApplier.applyLevelDelta}），
 * 外加「预算打断时变化集为空也不许判收敛」这一条驱动层铁律的离线钉定。
 */
public final class FluidEngineSelfTest {

    private static final FluidKind WATER = FluidKind.WATER;
    private static final FluidKind AIR = FluidKind.AIR;
    private static final FluidKind SOLID = FluidKind.SOLID;

    private static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}};

    private static int failures = 0;
    private static int checks = 0;

    private FluidEngineSelfTest() {
    }

    public static void main(final String[] args) {
        testFlatPour();
        testTrenchTen();
        testUTube();
        testCrossRegion();
        testUnloadedBorder();
        testBorderCanGrow();
        testInfiniteSource();
        testApiContract();
        testSameAsLegacySolver();
        testBudgetedSteps();
        testWaterloggedRejectsPartial();
        testCanReceivePredicate();
        testBudgetNotConverged();
        testDeltaReuse();
        testRoundAdvance();
        testStaircaseShape();
        testOverCapacityClamped();
        testMixedFluidNoTransfer();
        testSourceBothPaths();
        testNullKindRejected();
        testFallWholeColumn();
        testFallStopsOnWaterSurface();
        testSourceAnchoredNoUpSupply();
        testSourceEndlessSupply();
        testRegionAssembly();
        testAssemblyWithoutLiquid();
        testDeltaWriteIntent();
        testRegionRoundTrip();
        testDenseSparseEquivalent();
        benchmark();

        System.out.println(failures == 0
                ? "[OK] FluidEngine 全部通过 (" + checks + " 项)"
                : "[FAIL] FluidEngine 失败 " + failures + " 项 / 共 " + checks + " 项");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static boolean pass(final boolean ok) {
        checks++;
        if (!ok) {
            failures++;
            final StackTraceElement at = Thread.currentThread().getStackTrace()[2];
            System.out.println("  [FAIL] check #" + checks + " at " + at.getMethodName() + ":" + at.getLineNumber());
        }
        return ok;
    }

    private static void fail(final String msg) {
        checks++;
        failures++;
        System.out.println("  [FAIL] " + msg);
    }

    /**
     * 跑到不动点（★ 一轮 = 一次 sweep）：每轮 `step` 后把增量落到可写的 {@link ArrayBody} 上，
     * 并把各轮增量合并成一份「相对初始的最终变化」。
     *
     * @return 合并后的增量（用 {@link FluidDelta#newAmountAt} 读最终水位；没有变化的格不在里面）
     */
    private static ArrayBody settleBody(final ArrayBody start, final FluidDelta acc, final int maxRounds) {
        ArrayBody cur = start;
        for (int r = 0; r < maxRounds; r++) {
            final FluidDelta d = new FluidDelta();
            final int moved = FluidEngine.step(cur, d);
            for (int k = 0; k < d.size(); k++) {
                acc.set(d.packed(k), d.amount(k));
            }
            // ★ ArrayBody.cell() 是「追加」不是「覆盖」⇒ 每轮必须重建一片，
            //   否则同一坐标会越堆越多，引擎读到的是第一个旧值 ⇒ 永不收敛。
            final ArrayBody next = new ArrayBody(WATER);
            for (int i = 0; i < cur.size(); i++) {
                final long p = cur.packed(i);
                final Integer nv = d.newAmountAt(p);
                final int v = nv == null ? cur.amount(i) : nv;
                next.cell(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p), FluidBodyView.unpackZ(p),
                        project(cur.kind(i), v), v);
            }
            for (int j = 0; j < cur.borderSize(); j++) {
                final long p = cur.borderPacked(j);
                next.border(FluidBodyView.unpackX(p), FluidBodyView.unpackY(p), FluidBodyView.unpackZ(p),
                        cur.borderKind(j), cur.borderAmount(j));
            }
            cur = next;
            if (moved == 0 && !d.unfinished()) {
                return cur;
            }
        }
        fail("settleBody 跑满 " + maxRounds + " 轮仍未收敛");
        return cur;
    }

    /** 介质投影（与 World.apply 同款）：接了水的容器格变水格；干了的液体格变回空气。 */
    private static FluidKind project(final FluidKind k, final int amount) {
        if (amount <= 0) {
            return k.isLiquid() ? AIR : k;
        }
        return !k.isLiquid() && k.accepts(WATER) ? WATER : k;
    }

    // ---------- 1. 平地倒 8 单位：铺成 8 格各 1 ----------

    private static void testFlatPour() {
        final World w = new World();
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                w.put(x, 5, z, AIR, 0);
                w.put(x, 4, z, SOLID, 0);               // 地板
            }
        }
        w.put(0, 5, 0, WATER, 8);

        pass(w.settle(64) > 0);                         // ★ 一轮 = 一次 sweep：多轮跑到不动点
        pass(w.total() == 8);                           // 守恒

        int wet = 0;
        boolean allOne = true;
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 8; z++) {
                final int a = w.amountAt(x, 5, z);
                if (a > 0) {
                    wet++;
                    allOne &= a == 1;
                }
            }
        }
        pass(wet == 8);                                 // 8 单位正好铺 8 格
        pass(allOne);                                   // 每格 1（不是 2/0 的搭配）

        final FluidDelta again = new FluidDelta();
        pass(FluidEngine.step(w.capture(), again) == 0);   // 不动点
        pass(again.isEmpty());                          // 返回 0 时输出必为空
    }

    // ---------- 2. 沟槽 10 格：前 8 格各 1，后 2 格 0（对齐 Wp3 基线） ----------

    private static void testTrenchTen() {
        final World w = trench(0, 9, 8, 0);
        pass(w.settle(64) > 0);                         // ★ 一轮 = 一次 sweep：多轮跑到不动点
        pass(w.total() == 8);
        final String got = levels(w, 0, 9);
        if (!"1 1 1 1 1 1 1 1 0 0".equals(got)) {
            fail("沟槽 10 格布局 = [" + got + "]，期望 [1 1 1 1 1 1 1 1 0 0]（Wp3 S1）");
        } else {
            pass(true);
        }

        final FluidDelta again = new FluidDelta();
        pass(FluidEngine.step(w.capture(), again) == 0);
        pass(again.isEmpty());

        // 镜像：水源在右端。没有「缺口距离场」时这里会停在 0 0 0 0 1 1 2 1 2 1（水在两格间来回搬）
        final World mirror = trench(0, 9, 8, 9);
        pass(mirror.settle(64) > 0);
        pass(mirror.total() == 8);
        final String mgot = levels(mirror, 0, 9);
        if (!"0 0 1 1 1 1 1 1 1 1".equals(mgot)) {
            fail("镜像沟槽布局 = [" + mgot + "]，期望 [0 0 1 1 1 1 1 1 1 1]");
        } else {
            pass(true);
        }
    }

    /** x0..x1 的沟槽（y = 5、z = 5，下方固体、两端固体墙），水放在 wetX。 */
    private static World trench(final int x0, final int x1, final int amount, final int wetX) {
        final World w = new World();
        for (int x = x0; x <= x1; x++) {
            w.put(x, 5, 5, AIR, 0);
            w.put(x, 4, 5, SOLID, 0);
        }
        w.put(x0 - 1, 5, 5, SOLID, 0);
        w.put(x1 + 1, 5, 5, SOLID, 0);
        w.put(wetX, 5, 5, WATER, amount);
        return w;
    }

    private static String levels(final World w, final int x0, final int x1) {
        final StringBuilder sb = new StringBuilder();
        for (int x = x0; x <= x1; x++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(w.amountAt(x, 5, 5));
        }
        return sb.toString();
    }

    // ---------- 3. U 形管：超过底层容量必须抬升，两侧水面差 ≤ 1 片 ----------

    private static void testUTube() {
        final World w = new World();
        for (int y = 0; y <= 9; y++) {
            w.put(0, y, 0, WATER, 8);                   // 左井：10 格满 = 80
            w.put(4, y, 0, AIR, 0);                     // 右井
        }
        for (int x = 1; x <= 3; x++) {
            w.put(x, 0, 0, AIR, 0);                     // 底部通道
        }
        pass(w.total() == 80);                          // 底层只有 5 格 × 8 = 40 容量 ⇒ 必须抬升

        final int rounds = w.settle(400);
        if (rounds < 0) {
            fail("U 形管 400 轮没收敛");
        } else {
            pass(true);
        }
        pass(w.total() == 80);                          // 守恒
        final int left = w.amountAt(0, 1, 0);
        final int right = w.amountAt(4, 1, 0);
        pass(left > 0 && right > 0);                    // 两侧都被抬起来
        pass(Math.abs(left - right) <= 1);              // 连通器：两侧水面差 ≤ 1 片（= 1 单位）
        // 底层只有 5 格 × 8 = 40 容量，80 单位 ⇒ 必须抬到第二层
        pass(w.amountAt(0, 2, 0) > 0 || w.amountAt(4, 2, 0) > 0);
    }

    // ---------- 4. 跨区块的片：x = 14..20 故意跨过 16 ----------

    private static void testCrossRegion() {
        final World w = new World();
        for (int x = 14; x <= 20; x++) {
            w.put(x, 5, 5, AIR, 0);
            w.put(x, 4, 5, SOLID, 0);
        }
        w.put(13, 5, 5, SOLID, 0);
        w.put(21, 5, 5, SOLID, 0);
        w.put(14, 5, 5, WATER, 8);

        pass(w.settle(64) > 0);                         // ★ 一轮 = 一次 sweep：多轮跑到不动点
        pass(w.total() == 8);

        int min = Integer.MAX_VALUE;
        int max = 0;
        int wet = 0;
        for (int x = 14; x <= 20; x++) {
            final int a = w.amountAt(x, 5, 5);
            min = Math.min(min, a);
            max = Math.max(max, a);
            if (a > 0) {
                wet++;
            }
        }
        pass(wet == 7);                                 // 水面一直铺到 x = 20（跨过 x = 16）
        pass(min == 1 && max == 2);                     // 8 单位 / 7 格 ⇒ 1 格 2 + 6 格 1，水面平
        pass(w.amountAt(20, 5, 5) > 0);
    }

    // ---------- 5. 未加载截断：没有边界格 ⇒ 一滴都不许出去 ----------

    private static void testUnloadedBorder() {
        // A) 两端未加载（连边界格都没有）
        final World a = new World();
        for (int x = 0; x <= 4; x++) {
            a.put(x, 5, 5, AIR, 0);
            a.put(x, 4, 5, SOLID, 0);
        }
        a.put(0, 5, 5, WATER, 8);
        final FluidDelta da = new FluidDelta();
        pass(FluidEngine.step(a.capture(), da) > 0);
        boolean inside = true;
        for (int k = 0; k < da.size(); k++) {
            inside &= da.x(k) >= 0 && da.x(k) <= 4;
        }
        pass(inside);                                   // 变化集里不许出现未加载方向上的格
        a.apply(da);
        a.settle(64);                                   // ★ 一轮 = 一次 sweep：跑到不动点再看稳态
        pass(a.total() == 8);                           // 守恒
        int min = Integer.MAX_VALUE;
        int max = 0;
        for (int x = 0; x <= 4; x++) {
            final int v = a.amountAt(x, 5, 5);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        pass(min == 1 && max == 2);                     // 8 单位铺 5 格 ⇒ 2/2/2/1/1
        final FluidDelta again = new FluidDelta();
        pass(FluidEngine.step(a.capture(), again) == 0);

        // B) 边界不可容纳（固体墙）与未加载同效
        final World b = trench(0, 4, 8, 0);
        final FluidDelta db = new FluidDelta();
        pass(FluidEngine.step(b.capture(), db) > 0);
        b.apply(db);
        b.settle(64);                                   // ★ 跑到不动点
        pass(b.total() == 8);
        pass(b.amountAt(4, 5, 5) > 0);

        // C) 只有一格水、下方未加载 ⇒ 连重力都不许生效
        final World c = new World();
        c.put(0, 5, 5, WATER, 8);
        final FluidDelta dc = new FluidDelta();
        pass(FluidEngine.step(c.capture(), dc) == 0);
        pass(dc.isEmpty());
        pass(c.total() == 8);
        pass(c.amountAt(0, 5, 5) == 8);
    }

    // ---------- 6. 边界格能接水：片逐轮长大（未加载截断的对照组） ----------

    private static void testBorderCanGrow() {
        final World w = new World();
        w.put(0, 5, 5, WATER, 8);
        w.put(0, 4, 5, AIR, 0);                         // 正下方已加载的空气 ⇒ 水必须掉下去
        final int rounds = w.settle(200);
        if (rounds < 0) {
            fail("单柱下落 200 轮没收敛");
        } else {
            pass(true);
        }
        pass(w.total() == 8);
        pass(w.amountAt(0, 4, 5) == 8);                 // 水落到下一格
        pass(w.amountAt(0, 5, 5) == 0);
    }

    // ---------- 7. 恒定水源：守恒的唯一例外 ----------

    private static void testInfiniteSource() {
        final World w = new World();
        for (int x = 0; x <= 3; x++) {
            w.put(x, 5, 5, AIR, 0);
            w.put(x, 4, 5, SOLID, 0);
        }
        w.put(-1, 5, 5, SOLID, 0);
        w.put(4, 5, 5, SOLID, 0);
        w.put(0, 5, 5, WATER, 8);
        w.source(0, 5, 5);

        final int rounds = w.settle(400);
        if (rounds < 0) {
            fail("恒定水源 400 轮没收敛");
        } else {
            pass(true);
        }
        pass(w.amountAt(0, 5, 5) == 8);                 // ① 水源格自己不被抽干
        pass(w.total() == 32);                          // ② 容器被填满（4 格 × 8）：凭空产水
        pass(w.amountAt(3, 5, 5) == 8);
    }

    // ---------- 8. 接口契约 ----------

    private static void testApiContract() {
        // 世界坐标打包往返（含负坐标）
        final long p = FluidBodyView.pack(-12345, -60, 987654);
        pass(FluidBodyView.unpackX(p) == -12345);
        pass(FluidBodyView.unpackY(p) == -60);
        pass(FluidBodyView.unpackZ(p) == 987654);

        // kind 判定
        pass(WATER.isLiquid() && WATER.fill() == 8);
        pass(AIR.accepts(WATER));
        pass(FluidKind.WATERLOGGABLE.accepts(WATER) && FluidKind.WATERLOGGABLE.fullCubeOnly());
        pass(!SOLID.accepts(WATER));
        pass(!FluidKind.LAVA.accepts(WATER));           // 不同液体不混一片
        pass(WATER.accepts(WATER));
        pass(AIR.capacityFor(WATER) == 8);
        pass(SOLID.capacityFor(WATER) == 0);
        pass(FluidKind.byId(WATER.id()) == WATER);

        // FluidDelta 按目标聚合：同一格重复记录 = 覆盖
        final FluidDelta d = new FluidDelta();
        final long k = FluidBodyView.pack(1, 2, 3);
        d.set(k, 5);
        d.set(k, 6);
        pass(d.size() == 1);
        pass(d.newAmountAt(k) == 6);

        // 水位 1 = 自由水面末端：薄薄一层水不许横向滑动（否则两格之间来回搬、永不收敛）
        final World thin = new World();
        for (int x = 0; x < 3; x++) {
            thin.put(x, 5, 5, AIR, 0);
            thin.put(x, 4, 5, SOLID, 0);
        }
        thin.put(-1, 5, 5, SOLID, 0);
        thin.put(3, 5, 5, SOLID, 0);
        thin.put(0, 5, 5, WATER, 1);
        thin.put(1, 5, 5, WATER, 1);
        final FluidDelta d2 = new FluidDelta();
        pass(FluidEngine.step(thin.capture(), d2) == 0);
        pass(d2.isEmpty());
        pass(thin.total() == 2);

        // 空片 / 没有液体：什么都不输出
        final FluidDelta empty = new FluidDelta();
        pass(FluidEngine.step(new ArrayBody(WATER), empty) == 0);
        pass(empty.isEmpty());
    }

    // ---------- 9. 与旧实现逐格对齐（行为一致性闸门） ----------

    /**
     * 同一场景分别跑新引擎与旧 {@code SpreadSolver.equalizeLines}，逐格比对水位。
     *
     * <p>这是一致性的硬证据：不是「看起来差不多」，而是两个实现在同一输入上给出同一串水位。
     * 旧实现是 public API，离线闸门直接调它（不改动 waterphysics 包的任何文件）。
     */
    private static void testSameAsLegacySolver() {
        for (final int wet : new int[]{0, 9}) {
            final String legacy = legacyTrench(wet);
            final World w = trench(0, 9, 8, wet);
            final FluidDelta d = new FluidDelta();
            final int moved = FluidEngine.step(w.capture(), d);
            w.apply(d);
            final int rounds = w.settle(64);                // ★ 一轮 = 一次 sweep：跑到不动点再比
            final String mine = levels(w, 0, 9);
            if (!legacy.equals(mine)) {
                fail("沟槽(wet=" + wet + ") 新引擎 [" + mine + "] ≠ 旧实现 [" + legacy + "]");
            } else {
                pass(true);
            }
            pass(moved > 0);
            pass(w.total() == 8);
            if (wet == 0) {
                System.out.println("[info] 沟槽 10 格 wet=0：新引擎 moved=" + moved
                        + "，旧 equalizeLines moved=" + legacyMoved + "，布局 [" + mine + "]");
            }
        }
    }

    /** 旧实现最后一次调用的搬运次数（只用于 info 行）。 */
    private static int legacyMoved;

    /** 旧实现（waterphysics.SpreadSolver.equalizeLines）在同一沟槽场景下的逐格水位。 */
    private static String legacyTrench(final int wetX) {
        final WaterLevelField f = new WaterLevelField();
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            f.setKind(i, FluidCellKind.SOLID);
        }
        for (int x = 0; x <= 9; x++) {
            f.setKind(SectionCursor.linearIndex(x, 5, 5), FluidCellKind.AIR);
        }
        f.setLevel(SectionCursor.linearIndex(wetX, 5, 5), 8);
        final WaterWorkSet active = new WaterWorkSet();
        for (int i = 0; i < WaterLevelField.CELLS; i++) {
            if (f.capacity(i) > 0) {
                active.add(i);
            }
        }
        final FluidCellView view = new FluidCellView() {
            @Override
            public int kindAt(final int x, final int y, final int z) {
                return FluidCellKind.SOLID;
            }

            @Override
            public int levelAt(final int x, final int y, final int z) {
                return 0;
            }
        };
        legacyMoved = SpreadSolver.equalizeLines(f, view, SectionCursor.key(0, 0, 0), active, 16);
        final StringBuilder sb = new StringBuilder();
        for (int x = 0; x <= 9; x++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(f.level(SectionCursor.linearIndex(x, 5, 5)));
        }
        return sb.toString();
    }

    // ---------- 10. 分片迭代：预算 = 1 次搬运/步也必须收敛到同一布局 ----------

    private static void testBudgetedSteps() {
        final World w = trench(0, 9, 8, 0);
        int rounds = 0;
        int maxChanges = 0;
        for (; rounds < 500; rounds++) {
            final FluidDelta d = new FluidDelta();
            if (FluidEngine.step(w.capture(), d, 1) == 0) {     // 每步只准搬 1 次
                break;
            }
            maxChanges = Math.max(maxChanges, d.size());
            w.apply(d);
        }
        pass(rounds < 500);                                     // 分片迭代必须收敛
        pass(rounds > 1);                                       // 确实被切成了多步（预算生效）
        pass(maxChanges > 0);                                   // 每一步都真的搬了东西
        pass(w.total() == 8);                                   // 分片不丢水
        pass("1 1 1 1 1 1 1 1 0 0".equals(levels(w, 0, 9)));    // 与不限预算时同一布局
    }

    // ---------- 11. 含水方块（waterlogged）：只接受整格，中间档水位一个都不许注入（审查 B2） ----------

    private static void testWaterloggedRejectsPartial() {
        final FluidKind log = FluidKind.WATERLOGGABLE;

        // A) 沟槽 7 格 + 末端 1 格含水方块，倒 8 单位：
        //   8 单位不够把它整格填满 ⇒ 一格都不放（旧实现会往它里面写 1 级看不见的水位）。
        final World a = new World();
        for (int x = 0; x <= 6; x++) {
            a.put(x, 5, 5, AIR, 0);
            a.put(x, 4, 5, SOLID, 0);
        }
        a.put(7, 5, 5, log, 0);
        a.put(7, 4, 5, SOLID, 0);
        a.put(-1, 5, 5, SOLID, 0);
        a.put(8, 5, 5, SOLID, 0);
        a.put(0, 5, 5, WATER, 8);
        int rounds = 0;
        boolean legal = true;
        for (; rounds < 300; rounds++) {
            final FluidDelta d = new FluidDelta();
            if (FluidEngine.step(a.capture(), d) == 0) {
                break;
            }
            final Integer at = d.newAmountAt(FluidBodyView.pack(7, 5, 5));
            legal &= at == null || at == 0 || at == 8;          // 注入只准 0 / 满格
            a.apply(d);
        }
        pass(rounds < 300);                                     // 必须收敛（不许围着含水方块来回搬）
        pass(legal);                                            // 中间档一次都没注入
        pass(a.total() == 8);                                   // 守恒
        pass(a.amountAt(7, 5, 5) == 0);                         // 8 单位不够整格填 ⇒ 保持空
        System.out.println("[info] 含水方块沟槽：round=" + rounds + " 布局 [" + levels(a, 0, 7)
                + "]（x=7 是 waterlogged）");

        // B) 已经 7 级的含水方块 + 相邻恒定水源：链式推挤只准补「正好那一格」⇒ 8，不许停在 1..7
        final World b = new World();
        b.put(0, 5, 5, WATER, 8);
        b.source(0, 5, 5);
        b.put(1, 5, 5, log, 7);
        b.put(0, 4, 5, SOLID, 0);
        b.put(1, 4, 5, SOLID, 0);
        b.put(-1, 5, 5, SOLID, 0);
        b.put(2, 5, 5, SOLID, 0);
        final FluidDelta db = new FluidDelta();
        pass(FluidEngine.step(b.capture(), db) > 0);
        final Integer injected = db.newAmountAt(FluidBodyView.pack(1, 5, 5));
        pass(injected == null || injected == 8);                // 注入值只能是满格
        b.apply(db);
        pass(b.amountAt(1, 5, 5) == 8);                         // 正好补满
        pass(b.amountAt(0, 5, 5) == 8);                         // 水源自己补回满格

        // C) 重力路径：含水方块下面 4 级、上面 8 单位的水柱落下来 ⇒ 一次搬 4 单位正好整格填满
        final World c = new World();
        c.put(0, 6, 5, WATER, 8);
        c.put(0, 5, 5, log, 4);
        c.put(0, 4, 5, SOLID, 0);
        c.put(1, 6, 5, SOLID, 0);
        c.put(-1, 6, 5, SOLID, 0);
        c.put(1, 5, 5, SOLID, 0);
        c.put(-1, 5, 5, SOLID, 0);
        final FluidDelta dc = new FluidDelta();
        pass(FluidEngine.step(c.capture(), dc) > 0);
        final Integer dropped = dc.newAmountAt(FluidBodyView.pack(0, 5, 5));
        pass(dropped == null || dropped == 8);                  // 落进去的只能是整格
        c.apply(dc);
        pass(c.amountAt(0, 5, 5) == 8);                         // 4 + 4 = 8
        pass(c.total() == 12);                                  // 守恒（水柱剩下的 4 + 8）
    }

    // ---------- 12. canReceive 判定本身（审查 B2 的唯一注入判据） ----------

    private static void testCanReceivePredicate() {
        final FluidKind log = FluidKind.WATERLOGGABLE;
        pass(!FluidEngine.canReceive(log, 8, 0, 1));            // 0 → 1：中间档，不许
        pass(!FluidEngine.canReceive(log, 8, 6, 1));            // 6 → 7：中间档，不许
        pass(FluidEngine.canReceive(log, 8, 7, 1));             // 7 → 8：正好整格，允许
        pass(FluidEngine.canReceive(log, 8, 0, 8));             // 0 → 8：正好整格，允许
        pass(!FluidEngine.canReceive(log, 8, 0, 9));            // 超容量，不许
        pass(FluidEngine.canReceive(AIR, 8, 7, 1));             // 普通格：装得下就收
        pass(!FluidEngine.canReceive(AIR, 8, 8, 1));            // 满了，不收
        pass(!FluidEngine.canReceive(AIR, 3, 3, 1));            // 调用方报的小容量也要守
        pass(!FluidEngine.canReceive(AIR, 8, 0, 0));            // 搬 0 单位不叫接收
    }

    // ---------- 13. 预算 0 / 预算中途用尽：都不许被判成收敛（审查 B3） ----------

    private static void testBudgetNotConverged() {
        // A) 预算 = 0：一步都不搬，但绝不能是「动不了」（旧实现返回 0 ⇒ 驱动层判收敛 ⇒ 水永久停住）
        final World a = trench(0, 9, 8, 0);
        final FluidDelta d0 = new FluidDelta();
        pass(FluidEngine.step(a.capture(), d0, 0) == 0);
        pass(d0.isEmpty());                                     // 什么都没搬
        pass(d0.unfinished());                                  // ★ 未完成标记
        int rounds = 0;
        for (; rounds < 50; rounds++) {
            final FluidDelta d = new FluidDelta();
            if (FluidEngine.step(a.capture(), d, 0) == 0 && !d.unfinished()) {
                break;                                          // 驱动层口径：这样才算收敛
            }
        }
        pass(rounds == 50);                                     // 预算 0 ⇒ 50 轮都不许判收敛
        pass(a.total() == 8);                                   // 也没丢水

        // B) 预算中途用尽：被预算打断的步都带未完成标记；按正确口径驱动仍收敛到同一布局
        final World b = trench(0, 9, 8, 0);
        int converted = 0;
        int cutOff = 0;
        for (int r = 0; r < 500; r++) {
            final FluidDelta d = new FluidDelta();
            final int moved = FluidEngine.step(b.capture(), d, 3);
            if (moved == 0 && !d.unfinished()) {
                converted = r + 1;
                break;
            }
            if (d.unfinished()) {
                cutOff++;
            }
            if (moved > 0) {
                b.apply(d);
            }
        }
        pass(converted > 0);                                    // 收敛了
        pass(cutOff > 0);                                       // 确实经历过「被预算打断」的步
        pass(b.total() == 8);                                   // 分片不丢水
        pass("1 1 1 1 1 1 1 1 0 0".equals(levels(b, 0, 9)));    // 与不限预算时同一布局
        System.out.println("[info] 预算 3/步：收敛用了 " + converted + " 步，其中 " + cutOff + " 步被预算打断");
    }

    // ---------- 14. 同一个 FluidDelta 连跑多步：不串味（审查：输出复用） ----------

    private static void testDeltaReuse() {
        final World w = trench(0, 9, 8, 0);
        final FluidDelta d = new FluidDelta();                  // ★ 全程复用同一个 delta
        int rounds = 0;
        boolean stale = false;
        for (; rounds < 500; rounds++) {
            final Map<Long, Integer> before = w.snapshot();
            final int moved = FluidEngine.step(w.capture(), d);
            if (moved == 0) {
                pass(d.isEmpty());                              // 返回 0 时输出必为空（上一步残留不许留着）
                break;
            }
            for (int k = 0; k < d.size(); k++) {
                if (d.amount(k) == before.getOrDefault(d.packed(k), 0)) {
                    stale = true;                               // 记了「没变」的格 = 上一步的残留
                }
            }
            w.apply(d);
        }
        pass(rounds < 500);
        pass(!stale);
        pass(w.total() == 8);
        pass("1 1 1 1 1 1 1 1 0 0".equals(levels(w, 0, 9)));
    }

    // ---------- 14c. 用户口径的形态实证：5 4 3 2 1 逐轮怎么变 ----------

    private static void testStaircaseShape() {
        final World w = new World();
        for (int x = 0; x <= 4; x++) {
            w.put(x, 5, 5, AIR, 0);
            w.put(x, 4, 5, SOLID, 0);
        }
        w.put(-1, 5, 5, SOLID, 0);
        w.put(5, 5, 5, SOLID, 0);
        w.put(0, 5, 5, WATER, 5);
        w.put(1, 5, 5, WATER, 4);
        w.put(2, 5, 5, WATER, 3);
        w.put(3, 5, 5, WATER, 2);
        w.put(4, 5, 5, WATER, 1);
        final StringBuilder sb = new StringBuilder();
        sb.append("[0] ").append(levels(w, 0, 4)).append("  ");
        for (int r = 1; r <= 10; r++) {
            final FluidDelta d = new FluidDelta();
            if (FluidEngine.step(w.capture(), d) == 0) {
                sb.append('[').append(r).append("] settled");
                break;
            }
            w.apply(d);
            sb.append('[').append(r).append("] ").append(levels(w, 0, 4)).append("  ");
        }
        System.out.println("[shape] 5 4 3 2 1 逐轮：" + sb);
        pass(w.total() == 15);
        pass(w.settle(64) >= 0);
        System.out.println("[shape] settled = " + levels(w, 0, 4));
    }

    // ---------- 14b. 一轮 = 一次 sweep：逐轮推进一格（用户 2026-09-29 口径） ----------

    /**
     * 把「一轮 = 一次 sweep」的可观测形态钉住：沟槽里水从端头出发，每轮最多多湿一格
     * （薄层一格一格往前推），而不是一轮直接冲到稳态。
     */
    private static void testRoundAdvance() {
        final World w = trench(0, 9, 8, 0);
        final StringBuilder sb = new StringBuilder();
        int prevWet = w.wetCount();
        boolean oneStepAtATime = true;
        for (int r = 1; r <= 12; r++) {
            final FluidDelta d = new FluidDelta();
            if (FluidEngine.step(w.capture(), d) == 0) {
                break;
            }
            w.apply(d);
            final int wet = w.wetCount();
            oneStepAtATime &= wet - prevWet <= 1;           // ★ 一轮最多多湿一格
            prevWet = wet;
            sb.append('[').append(r).append("] ").append(levels(w, 0, 9)).append("  ");
        }
        System.out.println("[info] 逐轮形态（沟槽 8 单位 @x=0）：" + sb);
        pass(oneStepAtATime);                               // ★ 一轮 = 一次 sweep
        pass(w.total() == 8);                               // 守恒
        pass(w.settle(64) >= 0);                            // 多轮后收敛
        pass("1 1 1 1 1 1 1 1 0 0".equals(levels(w, 0, 9)));
    }

    // ---------- 15. 超容量注入被 clamp 且守恒（审查 B4） ----------

    private static void testOverCapacityClamped() {
        // A) 片内格报 100 单位（容量 8）⇒ 入口 clamp 成 8，绝不把 100 写回世界
        //    （水格必须是液体 kind：往空气格里塞 100 是「拼片拼错了」，引擎按「没有液体」直接不动）
        final ArrayBody body = new ArrayBody(WATER);
        body.cell(0, 5, 5, WATER, 100);
        for (int x = 1; x < 4; x++) {
            body.cell(x, 5, 5, AIR, 0);
        }
        final FluidDelta d = new FluidDelta();
        final ArrayBody settled = settleBody(body, d, 64);       // ★ 一轮 = 一次 sweep：跑到不动点
        pass(settled.size() == 4);                              // 片没被写胖（追加写会越长越多）
        int total = 0;
        boolean inRange = true;
        for (int x = 0; x < 4; x++) {
            final long k = FluidBodyView.pack(x, 5, 5);
            final int was = Math.min(body.amountAt(x, 5, 5), 8);    // 引擎眼里的输入量
            final int now = d.newAmountAt(k, was);
            total += now;
            inRange &= now >= 0 && now <= 8;
            pass(now == 2);                                     // 8 单位铺 4 格 ⇒ 每格 2
        }
        pass(inRange);
        pass(total == 8);                                       // clamp 成 8 之后再守恒（不是 100）

        // B) 容量不被信任：报超大（100）压回满格 8；报超小（3）就按 3 用，绝不许超容量写回
        final FluidBodyView lying = new FluidBodyView() {
            @Override
            public int size() {
                return 2;
            }

            @Override
            public long packed(final int i) {
                return FluidBodyView.pack(i, 5, 5);
            }

            @Override
            public FluidKind kind(final int i) {
                return i == 0 ? WATER : AIR;
            }

            @Override
            public int amount(final int i) {
                return i == 0 ? 8 : 0;
            }

            @Override
            public int capacity(final int i) {
                return i == 0 ? 100 : 3;
            }
        };
        // ★ 一轮 = 一次 sweep：这个 body 是只读匿名体（没有可写演变的入口），
        //   所以「超额被压回满格」用第一轮的中间态验：8 让 1 ⇒ 7（没压回的话会是 99）。
        final FluidDelta ld = new FluidDelta();
        pass(FluidEngine.step(lying, ld) > 0);
        pass(ld.newAmountAt(FluidBodyView.pack(0, 5, 5), 8) == 7);
        pass(ld.newAmountAt(FluidBodyView.pack(1, 5, 5), 0) == 1);   // 邻居收 1（容量 3 装得下）
        int ltotal = 0;
        for (int k = 0; k < ld.size(); k++) {
            ltotal += ld.amount(k);
            pass(ld.amount(k) >= 0 && ld.amount(k) <= 8);
        }
        pass(ltotal == 8);
    }

    // ---------- 16. 混液片（水 + 岩浆）：互不搬运（审查 B5） ----------

    private static void testMixedFluidNoTransfer() {
        final ArrayBody body = new ArrayBody(WATER);
        body.cell(0, 5, 5, WATER, 8);
        body.cell(1, 5, 5, AIR, 0);
        body.cell(2, 5, 5, FluidKind.LAVA, 8);                  // 混进来的岩浆格
        body.cell(3, 5, 5, AIR, 0);
        final FluidDelta d = new FluidDelta();
        settleBody(body, d, 64);                                    // ★ 一轮 = 一次 sweep：跑到不动点
        pass(d.newAmountAt(FluidBodyView.pack(2, 5, 5)) == null);   // ★ 岩浆格一个字都不许写
        pass(d.newAmountAt(FluidBodyView.pack(3, 5, 5)) == null);   // 水也不许穿过岩浆格
        pass(d.newAmountAt(FluidBodyView.pack(0, 5, 5), 8) == 4);   // 水只在同种液体之间铺
        pass(d.newAmountAt(FluidBodyView.pack(1, 5, 5), 0) == 4);
    }

    // ---------- 17. 恒定水源两条路径都补回满格（审查 B6） ----------

    private static void testSourceBothPaths() {
        // A) 悬空水源（重力路径、唯一邻居是边界格）：让出后必须立刻补满 ——
        //    旧实现只在链式路径补，这里水源自己单独成片（面积 = 它自己），压力场算它「正好到目标」
        //    ⇒ 一次都不让 ⇒ 补水的那条路也走不到 ⇒ 水源被自己抽到 1 级（水柱断头、源头方块消失）。
        final ArrayBody a = new ArrayBody(WATER);
        a.cell(0, 6, 5, WATER, 8, true);                        // 片内只有这一格，且是恒定水源
        a.border(0, 5, 5, AIR, 0);                              // 正下方：已加载的空气（边界格，能接水）
        final FluidDelta da = new FluidDelta();
        pass(FluidEngine.step(a, da) > 0);
        pass(da.newAmountAt(FluidBodyView.pack(0, 6, 5)) == null);   // ★ 水源格仍是 8（没变 ⇒ 不记）
        pass(da.newAmountAt(FluidBodyView.pack(0, 5, 5), 0) == 7);   // 让出的 7 单位落在边界格上

        // A2) 片内一格接着水源（对照）：悬空水源让出后仍必须是满格
        final World a2 = new World();
        a2.put(0, 6, 5, WATER, 8);
        a2.source(0, 6, 5);
        a2.put(0, 5, 5, AIR, 0);
        a2.put(0, 4, 5, SOLID, 0);
        a2.put(1, 6, 5, SOLID, 0);
        a2.put(-1, 6, 5, SOLID, 0);
        a2.put(1, 5, 5, SOLID, 0);
        a2.put(-1, 5, 5, SOLID, 0);
        boolean alwaysFull = true;
        int rounds = 0;
        for (; rounds < 100; rounds++) {
            final FluidDelta d = new FluidDelta();
            alwaysFull &= a2.amountAt(0, 6, 5) == 8;            // 每一轮水源格都是满格
            if (FluidEngine.step(a2.capture(), d) == 0) {
                break;
            }
            a2.apply(d);
        }
        pass(rounds < 100);
        pass(alwaysFull);                                       // ★ 一次都没停在 1 级
        pass(a2.amountAt(0, 6, 5) == 8);
        pass(a2.amountAt(0, 5, 5) == 8);                        // 让出来的水落到下面
        pass(a2.total() == 16);                                 // 守恒的唯一例外：水源凭空补了 8

        // B) 离群水源（链式路径）：平地上孤零零一格水源，每让 1 单位就补回满格
        final World b = new World();
        for (int x = 0; x <= 1; x++) {
            b.put(x, 5, 5, AIR, 0);
            b.put(x, 4, 5, SOLID, 0);
        }
        b.put(-1, 5, 5, SOLID, 0);
        b.put(2, 5, 5, SOLID, 0);
        b.put(0, 5, 5, WATER, 8);
        b.source(0, 5, 5);
        boolean bFull = true;
        for (int r = 0; r < 200; r++) {
            final FluidDelta d = new FluidDelta();
            bFull &= b.amountAt(0, 5, 5) == 8;
            if (FluidEngine.step(b.capture(), d) == 0) {
                break;
            }
            b.apply(d);
        }
        pass(bFull);
        pass(b.amountAt(0, 5, 5) == 8);
        pass(b.amountAt(1, 5, 5) == 8);                         // 邻居被水源灌满
        pass(b.total() == 16);
    }

    // ---------- 18. null kind：入口即抛，全文件一个口径（审查 B1） ----------

    private static void testNullKindRejected() {
        final FluidBodyView badCell = new FluidBodyView() {
            @Override
            public int size() {
                return 1;
            }

            @Override
            public long packed(final int i) {
                return FluidBodyView.pack(0, 5, 5);
            }

            @Override
            public FluidKind kind(final int i) {
                return null;
            }

            @Override
            public int amount(final int i) {
                return 0;
            }

            @Override
            public int capacity(final int i) {
                return 0;
            }
        };
        pass(throwsIllegalArgument(badCell));

        final FluidBodyView badBorder = new FluidBodyView() {
            @Override
            public int size() {
                return 1;
            }

            @Override
            public long packed(final int i) {
                return FluidBodyView.pack(0, 5, 5);
            }

            @Override
            public FluidKind kind(final int i) {
                return WATER;
            }

            @Override
            public int amount(final int i) {
                return 8;
            }

            @Override
            public int capacity(final int i) {
                return 8;
            }

            @Override
            public int borderSize() {
                return 1;
            }

            @Override
            public long borderPacked(final int i) {
                return FluidBodyView.pack(1, 5, 5);
            }

            @Override
            public FluidKind borderKind(final int i) {
                return null;
            }

            @Override
            public int borderAmount(final int i) {
                return 0;
            }
        };
        pass(throwsIllegalArgument(badBorder));

        // ArrayBody 自己也不许拼 null 格
        boolean threw = false;
        try {
            new ArrayBody(WATER).cell(0, 5, 5, null, 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        pass(threw);
    }

    private static boolean throwsIllegalArgument(final FluidBodyView body) {
        try {
            FluidEngine.step(body, new FluidDelta());
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    // ---------- 19. 阶段 2a：单 region 装配（FluidRegionAssembly）与写意图换算（FluidWriteIntent） ----------

    /**
     * 单 region 装配：种类映射表 + 片内格取舍 + 容量 + 水源 + 一圈边界格。
     *
     * <p>这是「真机采集快照 → 引擎片」那一步的离线对照：真机侧只有
     * {@code FluidBodyAdapter}（取数），全部判断都在 {@link FluidRegionAssembly} 里。
     */
    private static void testRegionAssembly() {
        // (a) 种类映射表：FluidCellKind → FluidKind（旧口径 → 新引擎口径的唯一一张表）
        pass(FluidKindMap.of(FluidCellKind.AIR) == FluidKind.AIR);
        pass(FluidKindMap.of(FluidCellKind.SOLID) == FluidKind.SOLID);
        pass(FluidKindMap.of(FluidCellKind.WATERLOGGABLE) == FluidKind.WATERLOGGABLE);
        pass(FluidKindMap.of(FluidCellKind.FLUID) == FluidKind.WATER);
        // PASSABLE（栅栏/树叶/玻璃板）旧口径 canHold == false ⇒ 按挡水处理，与旧求解器一致
        pass(FluidKindMap.of(FluidCellKind.PASSABLE) == FluidKind.SOLID);
        boolean unknownKind = false;
        try {
            FluidKindMap.of(99);
        } catch (IllegalArgumentException e) {
            unknownKind = true;
        }
        pass(unknownKind);

        // (b) 片内格：只收「有水 或 装得下本液体」且「已采集」的格
        final Region region = new Region(16, 64, -32);
        region.put(16, 64, -32, FluidCellKind.FLUID, 8).source(16, 64, -32);
        region.put(17, 64, -32, FluidCellKind.AIR, 0);              // 空容器格：压力场要用它算水面
        region.put(18, 64, -32, FluidCellKind.SOLID, 0);            // 固体：不进片
        region.put(19, 64, -32, FluidCellKind.PASSABLE, 0);         // 装不下：不进片
        region.put(20, 64, -32, FluidCellKind.WATERLOGGABLE, 0);    // 含水方块（空）：进片、只收整格
        region.put(21, 64, -32, FluidCellKind.FLUID, 3);            // 水位 3 的液体格
        // (26,64,-32) 有意不采集 ⇒ 不进片

        final ArrayBody body = FluidRegionAssembly.assemble(region);
        pass(body.fluid() == FluidKind.WATER);
        pass(body.cellCount() == 4);                                // 16 / 17 / 20 / 21 四格（18、19 不进片）
        pass(body.amountAt(16, 64, -32) == 8);
        pass(body.amountAt(17, 64, -32) == 0);
        pass(body.amountAt(18, 64, -32) == -1);                     // 固体不进片
        pass(body.amountAt(19, 64, -32) == -1);                     // PASSABLE 不进片
        pass(body.amountAt(20, 64, -32) == 0);
        pass(body.amountAt(21, 64, -32) == 3);
        pass(body.amountAt(26, 64, -32) == -1);                     // 没采集的格不进片
        pass(body.kind(body.indexOf(20, 64, -32)) == FluidKind.WATERLOGGABLE);
        pass(body.source(body.indexOf(16, 64, -32)));
        // 坐标必须是**世界坐标**（引擎只认世界坐标）：packed 与 FluidBodyView.pack 逐位一致
        pass(body.packed(body.indexOf(16, 64, -32)) == FluidBodyView.pack(16, 64, -32));
        pass(FluidBodyView.unpackX(body.packed(body.indexOf(21, 64, -32))) == 21);
        // 容量 = 该 kind 对本液体的容量（空气 / 水 / 含水方块对水都是 8 = MAX_LEVEL）
        pass(body.capacity(body.indexOf(17, 64, -32)) == WaterLevelField.MAX_LEVEL);
        pass(body.capacity(body.indexOf(20, 64, -32)) == WaterLevelField.MAX_LEVEL);
        pass(body.capacity(body.indexOf(21, 64, -32)) == WaterLevelField.MAX_LEVEL);

        // (c) 边界格 = region 外一圈里「已采集且装得下」的格；未采集 ⇒ 不可用（水一滴都不许出去）
        region.put(15, 64, -32, FluidCellKind.AIR, 0);              // -X 面：已采集的空格
        region.put(32, 64, -32, FluidCellKind.FLUID, 2);            // +X 面：邻居 region 的水
        region.put(16, 63, -32, FluidCellKind.AIR, 5);              // -Y 面：已采集的空格
        region.put(16, 64, -31, FluidCellKind.SOLID, 0);            // -Z 面：固体 ⇒ 不进边界
        // (16,64,-30) 有意不采集 ⇒ 不可用
        final ArrayBody withBorder = FluidRegionAssembly.assemble(region);
        pass(withBorder.borderCount() == 3);
        pass(borderIndexOf(withBorder, 15, 64, -32) >= 0);
        pass(borderIndexOf(withBorder, 32, 64, -32) >= 0);
        pass(borderIndexOf(withBorder, 16, 63, -32) >= 0);
        pass(borderIndexOf(withBorder, 16, 64, -31) < 0);           // 固体不进边界
        pass(borderIndexOf(withBorder, 16, 64, -30) < 0);           // 未采集 ⇒ 不可用
        final int b32 = borderIndexOf(withBorder, 32, 64, -32);
        pass(withBorder.borderKind(b32) == FluidKind.WATER);
        pass(withBorder.borderAmount(b32) == 2);
        final int b15 = borderIndexOf(withBorder, 15, 64, -32);
        pass(withBorder.borderKind(b15) == FluidKind.AIR);
        pass(withBorder.capacity(withBorder.indexOf(16, 64, -32)) == WaterLevelField.MAX_LEVEL);
    }

    /** 装配的调用方契约：片内没有「有量的液体格」时必须抛（绝不许静默拼出一片空水）。 */
    private static void testAssemblyWithoutLiquid() {
        final Region dry = new Region(0, 0, 0);
        dry.put(0, 0, 0, FluidCellKind.AIR, 0);                     // 只有容器格，没有液体
        dry.put(1, 0, 0, FluidCellKind.SOLID, 0);
        boolean threw = false;
        try {
            FluidRegionAssembly.assemble(dry);
        } catch (IllegalStateException e) {
            threw = true;
        }
        pass(threw);
    }

    /**
     * 写意图换算：{@link FluidDelta}（绝对新量）→ {@link FluidWritePlan}（增量）。
     *
     * <p>落地侧照 {@code FluidApplier.applyLevelDelta} 的口径（该格当前值 + 增量，clamp 到 [0, 8]），
     * 逐格比出「新量」—— 片内格与边界格都必须一模一样。
     */
    private static void testDeltaWriteIntent() {
        // (a) 片内：4 格沟槽倒 8 单位 ⇒ 每格 2
        final World w = new World();
        for (int x = 0; x < 4; x++) {
            w.put(x, 5, 0, AIR, 0);
            w.put(x, 4, 0, SOLID, 0);
        }
        w.put(0, 5, 0, WATER, 8);
        final ArrayBody body = w.capture();
        final FluidDelta delta = new FluidDelta();
        pass(FluidEngine.step(body, delta) > 0);
        pass(!delta.unfinished());                                  // 不限预算 ⇒ 一定算完

        final FluidWritePlan plan = new FluidWritePlan();
        pass(FluidWriteIntent.emit(delta, body, plan) == delta.size());
        pass(plan.levelCount() == delta.size());

        final Map<Long, Integer> side = seedSide(body);
        applyDeltas(plan, side);
        pass(sameAsDelta(delta, side));                             // 逐格 == 引擎的新量
        pass(total(side) == 8);                                     // 守恒

        // (b) 边界格（片外）走同一条增量语义：8 单位落到片外已有 3 单位的空格
        final ArrayBody out = new ArrayBody(WATER);
        out.cell(0, 0, 0, WATER, 8);
        out.border(0, -1, 0, AIR, 3);
        final FluidDelta borderDelta = new FluidDelta();
        pass(FluidEngine.step(out, borderDelta) > 0);
        final FluidWritePlan borderPlan = new FluidWritePlan();
        pass(FluidWriteIntent.emit(borderDelta, out, borderPlan) == borderDelta.size());
        final Map<Long, Integer> borderSide = seedSide(out);
        applyDeltas(borderPlan, borderSide);
        pass(sameAsDelta(borderDelta, borderSide));
        pass(borderSide.get(FluidBodyView.pack(0, -1, 0)) == 8);
        pass(borderSide.get(FluidBodyView.pack(0, 0, 0)) == 3);

        // (c) 空变化集 ⇒ 一条写意图都不产出（收敛）
        final FluidWritePlan empty = new FluidWritePlan();
        pass(FluidWriteIntent.emit(new FluidDelta(), body, empty) == 0);
        pass(empty.levelCount() == 0);

        // (d) 变化集里出现片外坐标 = 引擎契约破裂（水会凭空消失）⇒ 必须抛，不许静默丢
        final FluidDelta stray = new FluidDelta();
        stray.set(FluidBodyView.pack(999, 999, 999), 5);
        boolean threw = false;
        try {
            FluidWriteIntent.emit(stray, body, new FluidWritePlan());
        } catch (IllegalStateException e) {
            threw = true;
        }
        pass(threw);

        // (e) 被预算打断：预算 0 ⇒ 变化集为空但 unfinished 为真
        //     ★ 驱动层的收敛判据必须是「变化集为空 且 未被打断」，否则这一片会被判收敛、水永久冻住
        final FluidDelta zeroBudget = new FluidDelta();
        pass(FluidEngine.step(body, zeroBudget, 0) == 0);
        pass(zeroBudget.isEmpty());
        pass(zeroBudget.unfinished());
    }

    /**
     * 单 region 的整链路往返（压缩版驱动）：每轮「装配 → 引擎一步 → 变化集投影回 region」，
     * 直到 {@code step} 不再动。
     *
     * <p>钉住的是阶段 2a 的交付链路本身：装配出来的片喂给引擎连跑多轮，结果必须与直接拼的
     * {@code ArrayBody} 一致（平地倒 8 单位 ⇒ 8 格各 1）、总量守恒、水位合法，且最后一步
     * 既没变化也没被打断（只有这样驱动层才允许把这个 region 判成收敛、摘掉）。
     */
    private static void testRegionRoundTrip() {
        final Region region = new Region(0, 0, 0);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                region.put(x, 0, z, FluidCellKind.SOLID, 0);          // 地板
                region.put(x, 1, z, FluidCellKind.AIR, 0);            // 一层空（压力场要用它算水面）
            }
        }
        region.put(0, 1, 0, FluidCellKind.FLUID, 8);                  // 倒 8 单位（水源在角落）

        final FluidDelta delta = new FluidDelta();
        int rounds = 0;
        int moved;
        do {
            final ArrayBody body = FluidRegionAssembly.assemble(region);
            moved = FluidEngine.step(body, delta);
            // 写回：把引擎报的新量投影回 region（有水 ⇒ 液体格，没水 ⇒ 空气），与 FluidApplier 同口径
            for (int k = 0; k < delta.size(); k++) {
                final long packed = delta.packed(k);
                final int v = delta.amount(k);
                region.put(FluidBodyView.unpackX(packed), FluidBodyView.unpackY(packed),
                        FluidBodyView.unpackZ(packed), v > 0 ? FluidCellKind.FLUID : FluidCellKind.AIR, v);
            }
            rounds++;
        } while ((moved > 0 || delta.unfinished()) && rounds < 64);

        pass(rounds < 64);                                            // 收住了（没有打满轮数）
        pass(!delta.unfinished());                                    // 最后一步是真的不动点
        pass(region.totalLevel() == 8);                               // 守恒：一滴不多一滴不少
        pass(region.wetCells() == 8);                                 // 8 单位铺成 8 格
        pass(region.maxLevel() == 1);                                 // 每格 1（水位合法且不堆叠）
    }

    /** 侧表基准 = 装配时的量（采集侧把权威水位写进侧表，写回侧按「当前值 + 增量」落地）。 */
    private static Map<Long, Integer> seedSide(final FluidBodyView body) {
        final Map<Long, Integer> side = new HashMap<>();
        for (int i = 0; i < body.size(); i++) {
            side.put(body.packed(i), body.amount(i));
        }
        for (int j = 0; j < body.borderSize(); j++) {
            side.put(body.borderPacked(j), body.borderAmount(j));
        }
        return side;
    }

    /** 照 {@code FluidApplier.applyLevelDelta} 的口径落地写意图（当前值 + 增量，clamp 到 [0, MAX_LEVEL]）。 */
    private static void applyDeltas(final FluidWritePlan plan, final Map<Long, Integer> side) {
        plan.consumeLevels(plan.pendingLevels(), new FluidWritePlan.LevelSink() {
            @Override
            public void accept(final long packedPos, final int level) {
                side.put(packedPos, level);
            }

            @Override
            public void acceptDelta(final long packedPos, final int delta) {
                final int now = side.getOrDefault(packedPos, 0);
                side.put(packedPos, Math.max(0, Math.min(WaterLevelField.MAX_LEVEL, now + delta)));
            }
        });
    }

    /** 变化集里每一格的落地值都等于引擎报的新量。 */
    private static boolean sameAsDelta(final FluidDelta delta, final Map<Long, Integer> side) {
        for (int k = 0; k < delta.size(); k++) {
            if (!Integer.valueOf(delta.amount(k)).equals(side.get(delta.packed(k)))) {
                return false;
            }
        }
        return true;
    }

    private static int total(final Map<Long, Integer> side) {
        int sum = 0;
        for (final int v : side.values()) {
            sum += v;
        }
        return sum;
    }

    /** 边界格下标（按世界坐标找）；没有返回 -1。 */
    private static int borderIndexOf(final FluidBodyView body, final int x, final int y, final int z) {
        final long packed = FluidBodyView.pack(x, y, z);
        for (int j = 0; j < body.borderSize(); j++) {
            if (body.borderPacked(j) == packed) {
                return j;
            }
        }
        return -1;
    }

    /**
     * 离线假 region（{@link FluidRegionAssembly.Source} 的数组实现）：
     * 片内用 16³ 数组，圈外一格用世界坐标直接记（<b>没记 = 没采集</b>）。
     */
    private static final class Region implements FluidRegionAssembly.Source {

        private final int baseX;
        private final int baseY;
        private final int baseZ;
        private final int[] kind = new int[FluidRegionAssembly.CELLS];
        private final int[] level = new int[FluidRegionAssembly.CELLS];
        private final boolean[] scanned = new boolean[FluidRegionAssembly.CELLS];
        private final boolean[] source = new boolean[FluidRegionAssembly.CELLS];
        /** 圈外一格：packed → {kind, level}；没有键 = 没采集（不可用）。 */
        private final Map<Long, int[]> outside = new HashMap<>();

        Region(final int baseX, final int baseY, final int baseZ) {
            this.baseX = baseX;
            this.baseY = baseY;
            this.baseZ = baseZ;
            Arrays.fill(kind, FluidCellKind.SOLID);
        }

        /** 记一格（片内 / 圈外都记在这里，按坐标自动分流）。 */
        Region put(final int x, final int y, final int z, final int cellKind, final int cellLevel) {
            final int index = indexIn(x, y, z);
            if (index >= 0) {
                scanned[index] = true;
                kind[index] = cellKind;
                level[index] = cellLevel;
            } else {
                outside.put(FluidBodyView.pack(x, y, z), new int[]{cellKind, cellLevel});
            }
            return this;
        }

        /** 把片内某格打成恒定水源。 */
        Region source(final int x, final int y, final int z) {
            final int index = indexIn(x, y, z);
            if (index >= 0) {
                source[index] = true;
            }
            return this;
        }

        /** 片内总水量（守恒断言用）。 */
        int totalLevel() {
            int sum = 0;
            for (final int v : level) {
                sum += v;
            }
            return sum;
        }

        /** 片内有水的格数。 */
        int wetCells() {
            int c = 0;
            for (final int v : level) {
                if (v > 0) {
                    c++;
                }
            }
            return c;
        }

        /** 片内最高水位（校验不堆叠 / 不越界）。 */
        int maxLevel() {
            int max = 0;
            for (final int v : level) {
                max = Math.max(max, v);
            }
            return max;
        }

        private int indexIn(final int x, final int y, final int z) {
            final int lx = x - baseX;
            final int ly = y - baseY;
            final int lz = z - baseZ;
            if ((lx | ly | lz) < 0 || lx > 15 || ly > 15 || lz > 15) {
                return -1;
            }
            return SectionCursor.linearIndex(lx, ly, lz);
        }

        @Override
        public int baseX() {
            return baseX;
        }

        @Override
        public int baseY() {
            return baseY;
        }

        @Override
        public int baseZ() {
            return baseZ;
        }

        @Override
        public boolean insideScanned(final int index) {
            return scanned[index];
        }

        @Override
        public int insideKind(final int index) {
            return kind[index];
        }

        @Override
        public int insideLevel(final int index) {
            return level[index];
        }

        @Override
        public boolean insideSource(final int index) {
            return source[index];
        }

        @Override
        public boolean outsideScanned(final int x, final int y, final int z) {
            return outside.containsKey(FluidBodyView.pack(x, y, z));
        }

        @Override
        public int outsideKind(final int x, final int y, final int z) {
            // 装配器先问 outsideScanned；未采集的坐标不会被问到（这里给 SOLID 只为不 NPE）
            final int[] cell = outside.get(FluidBodyView.pack(x, y, z));
            return cell == null ? FluidCellKind.SOLID : cell[0];
        }

        @Override
        public int outsideLevel(final int x, final int y, final int z) {
            final int[] cell = outside.get(FluidBodyView.pack(x, y, z));
            return cell == null ? 0 : cell[1];
        }
    }

    // ---------- 22. 稠密盒路 ⇄ 稀疏哈希路：同一个片、同一步、逐格等价 ----------

    /**
     * 引擎有两条建表路：bbox 密度够就走<b>稠密盒</b>、否则走<b>开放寻址的 long→int 表</b>
     * （判据在 {@code FluidEngine.Run} 构造里）。生产片永远命中稠密路 ⇒ 稀疏路会完全没有回归覆盖。
     * 这个用例用包内开关 {@link FluidEngine#forceSparse} 对同一个片把两条路各算一步、逐格对拍：
     * moved 相同、变化集条数相同、每条坐标的新量相同。
     */
    private static void testDenseSparseEquivalent() {
        assertSameDelta("16³ 盒（顶部整层水）", wetBox(true, 16));
        assertSameDelta("16³ 盒（底部两层满水）", wetBox(false, 16));
        assertSameDelta("拉长片 1×1×40（稠密判据命中）", longStrip());
        assertSameDelta("空盒里的稀疏片（盒里全是洞）", sparseInBox());
    }

    private static void assertSameDelta(final String label, final ArrayBody body) {
        final FluidDelta denseOut = new FluidDelta();
        FluidEngine.forceSparse = false;
        final int denseMoved = FluidEngine.step(body, denseOut);
        final FluidDelta sparseOut = new FluidDelta();
        FluidEngine.forceSparse = true;
        final int sparseMoved = FluidEngine.step(body, sparseOut);
        FluidEngine.forceSparse = false;
        boolean same = denseMoved == sparseMoved && denseOut.size() == sparseOut.size();
        for (int k = 0; same && k < denseOut.size(); k++) {
            same = denseOut.amount(k) == sparseOut.newAmountAt(denseOut.packed(k), Integer.MIN_VALUE);
        }
        if (!same) {
            System.out.println("[FAIL] 稠密路与稀疏路不一致：" + label
                    + "（moved " + denseMoved + " / " + sparseMoved
                    + "，条数 " + denseOut.size() + " / " + sparseOut.size() + "）");
        }
        pass(same);
    }

    /** 立方盒片：边长 {@code s} 的盒，y=0 是地形。 */
    private static ArrayBody wetBox(final boolean pour, final int s) {
        final ArrayBody body = new ArrayBody(WATER);
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                for (int z = 0; z < s; z++) {
                    if (y == 0) {
                        body.cell(x, y, z, SOLID, 0);
                    } else if (pour) {
                        body.cell(x, y, z, y == s - 1 ? WATER : AIR, y == s - 1 ? 8 : 0);
                    } else {
                        body.cell(x, y, z, y <= 2 ? WATER : AIR, y <= 2 ? 8 : 0);
                    }
                }
            }
        }
        for (int y = 0; y < s; y++) {
            for (int x = 0; x < s; x++) {
                body.border(x, y, -1, AIR, 0);
                body.border(x, y, s, AIR, 0);
            }
            for (int z = 0; z < s; z++) {
                body.border(-1, y, z, AIR, 0);
                body.border(s, y, z, AIR, 0);
            }
        }
        return body;
    }

    /** 一根 40 格长的水平管（bbox = 40 ⇒ 稠密判据命中）。 */
    private static ArrayBody longStrip() {
        final ArrayBody body = new ArrayBody(WATER);
        for (int z = 0; z < 40; z++) {
            body.cell(0, 5, z, z == 0 ? WATER : AIR, z == 0 ? 8 : 0);
        }
        return body;
    }

    /** 3×3×1 的盒里只放 7 格：bbox 体积 9 ≤ 4×7 ⇒ 稠密判据命中，但盒里全是空洞。 */
    private static ArrayBody sparseInBox() {
        final ArrayBody body = new ArrayBody(WATER);
        for (int i = 0; i < 7; i++) {
            body.cell(i % 3, (i / 3) % 3, i / 9, i == 0 ? WATER : AIR, i == 0 ? 8 : 0);
        }
        return body;
    }

    // ---------- 20. 单步耗时/分配基准（审查 B7：每线程复用缓冲 + 无装箱 + 一次基数排序） ----------

    private static void benchmark() {
        System.out.println("[bench] 单步耗时/分配（min of 3 次 × 100 步；片 = 平地 N 格 + 一圈固体边界）");
        final int[] sizes = {64, 512, 4096};
        final int[] widths = {8, 16, 64};
        for (int i = 0; i < sizes.length; i++) {
            final int n = sizes[i];
            final int w = widths[i];
            final int h = n / w;
            bench("倒 8 单位（铺开）", n, benchBody(w, h, true));
            bench("静止水面（每格 1）", n, benchBody(w, h, false));
        }
    }

    private static ArrayBody benchBody(final int w, final int h, final boolean pour) {
        final ArrayBody body = new ArrayBody(WATER);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < h; z++) {
                // ⚠ 片内格坐标必须互不相同：先 cell(AIR) 再 cell(WATER) 会造出两个同坐标的片内格，
                //   稀疏哈希路会把它们当成一格（后者覆盖前者）、稠密盒路则会漏格 —— 那是畸形输入，
                //   不是引擎该容忍的东西（稠密路的「漏格」断言就是用来钉这条契约的）。
                final boolean wet = pour && x == 0 && z == 0;
                body.cell(x, 5, z, wet ? WATER : (pour ? AIR : WATER), wet ? 8 : (pour ? 0 : 1));
            }
        }
        for (int x = -1; x <= w; x++) {
            body.border(x, 5, -1, SOLID, 0);
            body.border(x, 5, h, SOLID, 0);
        }
        for (int z = 0; z < h; z++) {
            body.border(-1, 5, z, SOLID, 0);
            body.border(w, 5, z, SOLID, 0);
        }
        return body;
    }

    private static void bench(final String label, final int n, final ArrayBody body) {
        final FluidDelta d = new FluidDelta();                  // 复用同一个 delta（引擎每次入口自清）
        for (int i = 0; i < 50; i++) {
            FluidEngine.step(body, d);
        }
        final int iters = 100;
        long bestNs = Long.MAX_VALUE;
        long bestBytes = Long.MAX_VALUE;
        final com.sun.management.ThreadMXBean mx =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        final boolean alloc = mx.isThreadAllocatedMemorySupported();
        for (int round = 0; round < 3; round++) {
            final long b0 = alloc ? mx.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0L;
            final long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) {
                FluidEngine.step(body, d);
            }
            final long t1 = System.nanoTime();
            final long b1 = alloc ? mx.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0L;
            bestNs = Math.min(bestNs, (t1 - t0) / iters);
            if (alloc) {
                bestBytes = Math.min(bestBytes, (b1 - b0) / iters);
            }
        }
        System.out.printf("[bench] %-18s n=%5d  %8.1f us/step  %8.1f KB/step  delta=%d%n",
                label, n, bestNs / 1000.0, bestBytes == Long.MAX_VALUE ? -1.0 : bestBytes / 1024.0, d.size());
        if (alloc && n >= 4096) {
            pass(bestBytes < 64L * 1024L);                      // ★ 每步不该再新建几十个数组（旧实现 ~1.4 MB/步）
        }
    }

    // ---------- 21. 悬空水柱整块下落（用户 2026-09-29：水下落 = 整块下落，保持形状） ----------

    /**
     * 3 格水柱悬在空中（下方 5 格空气、再下面地形）⇒ <b>一次 step</b> 整柱落到地形上，
     * 各格相对水位分布不变（不是「每格每步只掉 1 单位」），总量逐单位守恒。
     *
     * <p>「形状不变」用非均匀分布 8/8/2 来验：满格柱子在压力场眼里本来就没得动，
     * 非均匀分布才是真判据（落定后仍是 8/8/2，没被摊平）。
     */
    private static void testFallWholeColumn() {
        final World w = new World();
        w.put(0, 5, 0, SOLID, 0);                               // 地形
        for (int y = 6; y <= 10; y++) {
            w.put(0, y, 0, AIR, 0);                             // 下方 5 格空气
        }
        w.put(0, 11, 0, WATER, 8);
        w.put(0, 12, 0, WATER, 8);
        w.put(0, 13, 0, WATER, 2);                              // 形状 = 8 / 8 / 2
        pass(w.total() == 18);

        final FluidDelta d = new FluidDelta();
        pass(FluidEngine.step(w.capture(), d) > 0);
        w.apply(d);
        pass(w.total() == 18);                                  // ★ 守恒：整块下落只是搬位置
        pass(w.amountAt(0, 6, 0) == 8);                         // ★ 一次落到地形上（不是只掉 1 单位）
        pass(w.amountAt(0, 7, 0) == 8);
        pass(w.amountAt(0, 8, 0) == 2);                         // ★ 形状不变
        pass(w.amountAt(0, 9, 0) == 0);
        pass(w.amountAt(0, 13, 0) == 0);                        // 原位置已清空

        final FluidDelta again = new FluidDelta();
        pass(FluidEngine.step(w.capture(), again) == 0);        // 落定即不动点
        pass(again.isEmpty());
        System.out.println("[info] 悬空水柱 8/8/2（下方 5 格空气）：一步落下 5 格 ⇒ y=6/7/8 = "
                + w.amountAt(0, 6, 0) + "/" + w.amountAt(0, 7, 0) + "/" + w.amountAt(0, 8, 0)
                + "，总水量 = " + w.total());
    }

    // ---------- 22. 下落中途被水面接住：停在水面上、不穿透、总量守恒 ----------

    private static void testFallStopsOnWaterSurface() {
        final World w = new World();
        w.put(0, 4, 0, SOLID, 0);
        for (int y = 5; y <= 7; y++) {
            w.put(0, y, 0, WATER, 8);                           // 已有水面（3 格满 = 24）
        }
        for (int y = 8; y <= 10; y++) {
            w.put(0, y, 0, AIR, 0);                             // 空隙 3 格
        }
        for (int y = 11; y <= 13; y++) {
            w.put(0, y, 0, WATER, 8);                           // 悬空水柱 3 格 = 24
        }
        pass(w.total() == 48);

        final FluidDelta d = new FluidDelta();
        pass(FluidEngine.step(w.capture(), d) > 0);
        w.apply(d);
        pass(w.total() == 48);                                  // ★ 守恒（不穿透、不丢水也不产水）
        boolean surfaceIntact = true;
        for (int y = 5; y <= 7; y++) {
            surfaceIntact &= w.amountAt(0, y, 0) == 8;          // ★ 下面的水原样（被接住，没被冲散）
        }
        pass(surfaceIntact);
        boolean landed = true;
        for (int y = 8; y <= 10; y++) {
            landed &= w.amountAt(0, y, 0) == 8;                 // ★ 水柱停在水面上（没落进水里）
        }
        pass(landed);
        pass(w.amountAt(0, 11, 0) == 0 && w.amountAt(0, 13, 0) == 0);

        final FluidDelta again = new FluidDelta();
        pass(FluidEngine.step(w.capture(), again) == 0);
        System.out.println("[info] 水柱落到水面上：y=5..13 水量 = " + w.amountAt(0, 5, 0) + "/"
                + w.amountAt(0, 6, 0) + "/" + w.amountAt(0, 7, 0) + " | "
                + w.amountAt(0, 8, 0) + "/" + w.amountAt(0, 9, 0) + "/" + w.amountAt(0, 10, 0) + " | "
                + w.amountAt(0, 11, 0) + "/" + w.amountAt(0, 12, 0) + "/" + w.amountAt(0, 13, 0));
    }

    // ---------- 23. 源格：位置 / 水位不变 + 不给正上方（用户 2026-09-29 修正口径） ----------

    /**
     * 恒定水源两条独立断言：
     * <ol>
     *   <li><b>不给正上方</b>：构造「源格的五个方向里只剩正上方可给」的场景（同层邻格是一只
     *       接不下 1 单位的空含水方块）—— 旧实现会把水顶上去，新语义下必须一滴都不给、
     *       上方仍为空。</li>
     *   <li><b>位置与水位的绝对量不变</b>：整块下落时源格悬在落体上方，落体整块落走，
     *       源格留在原处且始终满格（{@link FluidDelta} 里一个字都没有它）。</li>
     * </ol>
     */
    private static void testSourceAnchoredNoUpSupply() {
        // A) 源格不给正上方
        final World a = new World();
        a.put(0, 5, 5, WATER, 8);
        a.source(0, 5, 5);
        a.put(1, 5, 5, FluidKind.WATERLOGGABLE, 0);             // 同层：空含水方块 ⇒ 接不下这 1 单位
        a.put(0, 6, 5, AIR, 0);                                 // 源格正上方：空格
        a.put(0, 4, 5, SOLID, 0);
        a.put(1, 4, 5, SOLID, 0);
        a.put(0, 7, 5, SOLID, 0);
        a.put(1, 6, 5, SOLID, 0);
        a.put(-1, 5, 5, SOLID, 0);
        a.put(2, 5, 5, SOLID, 0);
        boolean aFull = true;
        boolean aAbove = true;
        boolean aUntouched = true;
        int aRounds = 0;
        for (; aRounds < 20; aRounds++) {
            final FluidDelta d = new FluidDelta();
            final int moved = FluidEngine.step(a.capture(), d);
            aUntouched &= d.newAmountAt(FluidBodyView.pack(0, 5, 5)) == null;
            a.apply(d);
            aFull &= a.amountAt(0, 5, 5) == 8;                  // 水位始终满格
            aAbove &= a.amountAt(0, 6, 5) == 0;                 // ★ 一滴都不给正上方
            if (moved == 0) {
                break;
            }
        }
        pass(aFull);
        pass(aAbove);
        pass(aUntouched);                                       // 源格的量一个字都没被改
        pass(a.total() == 8);                                   // 五个方向都没得给 ⇒ 一步都没搬
        pass(a.amountAt(1, 5, 5) == 0);                         // 含水方块也没被灌中间档
        System.out.println("[info] 源格不给正上方：" + aRounds + " 轮后上方 = " + a.amountAt(0, 6, 5)
                + "，源格 = " + a.amountAt(0, 5, 5) + "，总水量 = " + a.total());

        // B) 整块下落时源格的位置与水位都不变
        final World b = new World();
        b.put(0, 5, 0, SOLID, 0);
        for (int y = 6; y <= 10; y++) {
            b.put(0, y, 0, AIR, 0);
        }
        b.put(0, 11, 0, WATER, 8);
        b.put(0, 12, 0, WATER, 8);
        b.put(0, 13, 0, WATER, 8);
        b.source(0, 13, 0);                                     // 顶上那一格是恒定水源
        pass(b.total() == 24);

        final FluidDelta bd = new FluidDelta();
        pass(FluidEngine.step(b.capture(), bd) > 0);
        pass(bd.newAmountAt(FluidBodyView.pack(0, 13, 0)) == null);   // ★ 源格没被搬运
        b.apply(bd);
        pass(b.amountAt(0, 13, 0) == 8);                        // ★ 水位仍满格
        pass(b.amountAt(0, 6, 0) == 8 && b.amountAt(0, 7, 0) == 8);   // 它下面那两格整块落到地形上
        boolean bFull = true;
        boolean bUntouched = true;
        for (int r = 0; r < 10; r++) {
            final FluidDelta d = new FluidDelta();
            FluidEngine.step(b.capture(), d);
            bUntouched &= d.newAmountAt(FluidBodyView.pack(0, 13, 0)) == null;
            b.apply(d);
            bFull &= b.amountAt(0, 13, 0) == 8;
        }
        pass(bFull);
        pass(bUntouched);
        System.out.println("[info] 源格悬在落体上方：10 轮后源格 (0,13,0) = " + b.amountAt(0, 13, 0)
                + "，总水量 = " + b.total() + "（水源是守恒唯一例外）");
    }

    // ---------- 24. 恒定水源：源源不断（源格 + 相邻大缺口，连跑 N ≥ 10 步） ----------

    /**
     * 「源格 + 相邻大缺口」连跑 12 步：源格坐在 4×4×8 深坑的坑顶一角（整坑 128 格 / 1024 容量
     * 都是它的缺口）—— ① 每一步之后源格都是满格（不是抽干后停下）；
     * ② 总水量持续增长（无限水源，不是有限储水罐）；③ 缺口被逐步填上（湿格变多、水一路流到坑底）。
     *
     * <p>守恒断言在这类场景里<b>排除源格</b>（它是守恒的唯一例外）：源格每步都满格 ⇒
     * 非源格总量的增量恰好等于总增量的增量；另外逐格断言水位不越界（[0, 满格量]）。
     */
    private static void testSourceEndlessSupply() {
        final World w = new World();
        final int side = 4;
        final int deep = 8;
        final int topY = 4 + deep;                              // 12：坑顶那一层（源格坐在这里）
        for (int x = 0; x < side; x++) {
            for (int z = 0; z < side; z++) {
                w.put(x, 4, z, SOLID, 0);
                for (int y = 5; y <= topY; y++) {
                    w.put(x, y, z, AIR, 0);
                }
            }
        }
        w.put(0, topY, 0, WATER, 8);
        w.source(0, topY, 0);
        pass(w.total() == 8);
        pass(w.wetCount() == 1);

        int prevTotal = w.total();
        int prevOther = w.totalExceptSources();
        int firstWet = -1;
        boolean sourceAlwaysFull = true;
        boolean alwaysGrew = true;
        boolean othersConserved = true;
        boolean withinBounds = true;
        final int steps = 12;
        for (int r = 0; r < steps; r++) {
            final FluidDelta d = new FluidDelta();
            FluidEngine.step(w.capture(), d);
            w.apply(d);
            sourceAlwaysFull &= w.amountAt(0, topY, 0) == 8;                     // ①
            alwaysGrew &= w.total() > prevTotal;                                // ②
            othersConserved &= w.totalExceptSources() - prevOther
                    == w.total() - prevTotal;                                   // 守恒（排除源格）
            withinBounds &= w.allWithin(8);
            prevTotal = w.total();
            prevOther = w.totalExceptSources();
            if (r == 0) {
                firstWet = w.wetCount();
            }
        }
        pass(sourceAlwaysFull);
        pass(alwaysGrew);
        pass(othersConserved);
        pass(withinBounds);
        pass(firstWet > 1);                                     // ③ 第一步就已经浇到源格之外
        pass(w.wetCount() > firstWet * 3);                      // ③ 缺口被逐步填上（湿格成倍变多）
        pass(w.amountAt(0, 5, 0) > 0);                          // ③ 水一路流到坑底（真流出去了）
        pass(w.total() >= 80);                                  // ③ 缺口真的吸走了几百单位（不是空转）
        System.out.println("[info] 恒定水源 + 4×4×8 深坑（源格在坑顶：" + topY + "）：" + steps
                + " 步后总水量 = " + w.total() + "（起始 8），湿格 = " + w.wetCount()
                + "（第一步后 " + firstWet + "），已流到坑底 = " + (w.amountAt(0, 5, 0) > 0));
    }

    // ---------- 离线世界（转接类的离线版） ----------

    /**
     * 世界 = 格 →（介质, 液体量）；<b>没有记录的格 = 未加载</b>。
     *
     * <p>{@link #capture()} 就是用户口径的「读取时拼出模型」：从所有有水格出发，
     * 沿「能容纳本液体的格」拼出一整片；{@link #apply(FluidDelta)} 就是写回。
     */
    private static final class World {

        private final Map<Long, FluidKind> kinds = new HashMap<>();
        private final Map<Long, Integer> amount = new HashMap<>();
        private final Set<Long> sources = new HashSet<>();

        World put(final int x, final int y, final int z, final FluidKind kind, final int amt) {
            final long k = FluidBodyView.pack(x, y, z);
            kinds.put(k, kind);
            amount.put(k, amt);
            return this;
        }

        World source(final int x, final int y, final int z) {
            sources.add(FluidBodyView.pack(x, y, z));
            return this;
        }

        int amountAt(final int x, final int y, final int z) {
            return amount.getOrDefault(FluidBodyView.pack(x, y, z), 0);
        }

        int total() {
            int sum = 0;
            for (final int a : amount.values()) {
                sum += a;
            }
            return sum;
        }


        /** 水量 > 0 的格数（缺口被填掉多少的最直观量）。 */
        int wetCount() {
            int c = 0;
            for (final int a : amount.values()) {
                if (a > 0) {
                    c++;
                }
            }
            return c;
        }

        /** 最高的一格有水的世界 y；一格水都没有返回 Integer.MIN_VALUE。 */
        int topWetY() {
            int top = Integer.MIN_VALUE;
            for (final Map.Entry<Long, Integer> e : amount.entrySet()) {
                if (e.getValue() > 0) {
                    top = Math.max(top, FluidBodyView.unpackY(e.getKey()));
                }
            }
            return top;
        }

        /**
         * 除恒定水源格以外的总水量。
         *
         * <p>守恒断言里水源是唯一例外（它凭空产水）⇒ 涉及水源的场景要把它排除在外，
         * 其它格照常逐单位守恒。
         */
        int totalExceptSources() {
            int sum = 0;
            for (final Map.Entry<Long, Integer> e : amount.entrySet()) {
                if (!sources.contains(e.getKey())) {
                    sum += e.getValue();
                }
            }
            return sum;
        }

        /** 每一格的水位都在 [0, max] 内（引擎不变量：绝不许超容量 / 负水位）。 */
        boolean allWithin(final int max) {
            for (final int a : amount.values()) {
                if (a < 0 || a > max) {
                    return false;
                }
            }
            return true;
        }

        /** 世界当前的水量快照（{@link #apply} 之前的样子；用于检出 delta 里的残留）。 */
        Map<Long, Integer> snapshot() {
            return new HashMap<>(amount);
        }

        /** 从有水格拼出一整片（片内格 + 一圈已加载的边界格）。 */
        ArrayBody capture() {
            final Set<Long> piece = new LinkedHashSet<>();
            final Deque<Long> stack = new ArrayDeque<>();
            for (final Map.Entry<Long, Integer> e : amount.entrySet()) {
                final FluidKind k = kinds.get(e.getKey());
                if (e.getValue() > 0 && k != null && k.isLiquid() && piece.add(e.getKey())) {
                    stack.add(e.getKey());
                }
            }
            while (!stack.isEmpty()) {
                final long cur = stack.poll();
                final int x = FluidBodyView.unpackX(cur);
                final int y = FluidBodyView.unpackY(cur);
                final int z = FluidBodyView.unpackZ(cur);
                for (final int[] dir : DIRS) {
                    final long nb = FluidBodyView.pack(x + dir[0], y + dir[1], z + dir[2]);
                    if (piece.contains(nb)) {
                        continue;
                    }
                    final FluidKind k = kinds.get(nb);
                    if (k == null || !k.accepts(WATER)) {
                        continue;                       // 未加载 / 装不下 ⇒ 不是片内格
                    }
                    piece.add(nb);
                    stack.add(nb);
                }
            }
            final ArrayBody body = new ArrayBody(WATER);
            for (final long cur : piece) {
                body.cell(FluidBodyView.unpackX(cur), FluidBodyView.unpackY(cur), FluidBodyView.unpackZ(cur),
                        kinds.get(cur), amount.getOrDefault(cur, 0), sources.contains(cur));
            }
            for (final long cur : piece) {
                final int x = FluidBodyView.unpackX(cur);
                final int y = FluidBodyView.unpackY(cur);
                final int z = FluidBodyView.unpackZ(cur);
                for (final int[] dir : DIRS) {
                    final long nb = FluidBodyView.pack(x + dir[0], y + dir[1], z + dir[2]);
                    if (piece.contains(nb)) {
                        continue;
                    }
                    final FluidKind k = kinds.get(nb);
                    if (k == null) {
                        continue;                       // 未加载 ⇒ 不给边界格（那一侧一滴都不许出去）
                    }
                    body.border(FluidBodyView.unpackX(nb), FluidBodyView.unpackY(nb), FluidBodyView.unpackZ(nb),
                            k, amount.getOrDefault(nb, 0));
                }
            }
            return body;
        }

        /** 写回：按世界坐标覆盖，并同步「水格 ⇄ 空气格」的介质（世界投影）。 */
        void apply(final FluidDelta delta) {
            for (int k = 0; k < delta.size(); k++) {
                final long p = delta.packed(k);
                final int v = delta.amount(k);
                amount.put(p, v);
                final FluidKind cur = kinds.get(p);
                if (cur == null) {
                    continue;
                }
                if (v <= 0) {
                    if (cur.isLiquid()) {
                        kinds.put(p, AIR);
                    }
                } else if (!cur.isLiquid() && cur.accepts(WATER)) {
                    kinds.put(p, WATER);
                }
            }
        }

        /**
         * 反复「拼片 → 算一步 → 写回」直到 {@link FluidEngine#step} 返回 0。
         *
         * @return 用掉的轮数；返回 -1 = 打满轮数还没收敛
         */
        int settle(final int maxRounds) {
            for (int r = 0; r < maxRounds; r++) {
                final FluidDelta delta = new FluidDelta();
                if (FluidEngine.step(capture(), delta) == 0) {
                    return r;
                }
                apply(delta);
            }
            return -1;
        }
    }
}
