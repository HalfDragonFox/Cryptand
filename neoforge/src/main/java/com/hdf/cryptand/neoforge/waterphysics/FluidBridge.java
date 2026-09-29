package com.hdf.cryptand.neoforge.waterphysics;

import com.hdf.cryptand.fluid.ArrayBody;
import com.hdf.cryptand.fluid.FluidDelta;
import com.hdf.cryptand.fluid.FluidEngine;
import com.hdf.cryptand.fluid.FluidWriteIntent;
import com.hdf.cryptand.waterphysics.FluidBodyCollector;
import com.hdf.cryptand.waterphysics.FluidWritePlan;

/**
 * 桥接（新引擎旁路；阶段 2a 单 region 装配，阶段 2b 整片采集）：
 * 片（单 region 快照 / 整片采集结果）→ {@link FluidEngine#step} 一步 → 变化集 →
 * 同一份 {@link FluidWritePlan}（增量语义）。
 *
 * <p>★ 阶段 2b（{@link #solvePiece}）：片改由 {@link FluidBodyCollector} 从点名格出发
 * <b>跨 region 展开「整片连通水」</b>得到（世界坐标，region 概念不出现），因此工作集不再是
 * 局部点名格 —— 均衡器看得到整片，假平衡消失。两条入口的<b>输出口径完全相同</b>。
 *
 * <p>接口刻意做得极窄：进去的是「已经采集冻结的 region 快照」，出来的是「写意图 + 三个数
 * （搬运次数 / 写意图条数 / 本步是不是被预算打断）」。写回、排队、摘 region 全在
 * {@link WaterPhysicsBridge} 里（与旧路径共用同一条写回链路 {@code WriteBackQueue → FluidApplier}），
 * 所以两条路径的落地口径天然一致。
 *
 * <p><b>预算</b>：沿用主线程下发的额度（旧路径的单位是「格数」，新引擎的单位是「搬运次数」，
 * 见 {@code ConfigWaterphysics.COMPUTE_BUDGET_PER_TICK} 的注释）。引擎的契约是「最小分片单位 =
 * 一个完整 sweep」⇒ 返回值可能略大于预算；预算打断时 {@link FluidDelta#unfinished()} 为真，
 * <b>驱动层绝不许据此判收敛</b>（否则水会被永久冻住，见 {@code WaterPhysicsBridge#drainResults}）。
 *
 * <p><b>线程约定</b>：{@link #solve} 只由求解线程（物理线程池）调用。{@link FluidDelta} 复用缓冲
 * 放在 {@link ThreadLocal} 里（照 {@code FluidEngine.Scratch} 的每线程一份模式），避免每步新分配
 * HashMap；{@link ArrayBody} 是每步新建的（阶段 2a 的取舍：先要正确，等实机测出分配压力再谈复用）。
 */
public final class FluidBridge {

    /** 一步的结果。 */
    public record Result(int moved, int emitted, boolean unfinished) {
    }

    /** 每线程一份的复用变化集（{@code step} 每次入口都会 {@code clear()}，连跑多步不串味）。 */
    private static final ThreadLocal<FluidDelta> DELTA = ThreadLocal.withInitial(FluidDelta::new);

    private FluidBridge() {
    }

    /**
     * 算一步并把结果换算成写意图。
     *
     * @param snapshot 已采集冻结的单 region 快照（调用方保证片内有水：{@code active()} 非空）
     * @param out      写意图出口（本步的水位增量会追加进去）
     * @param budget   本步的搬运次数上限（&le; 0 时引擎立刻返回「没算完」，什么都不搬）
     */
    public static Result solve(final RegionSnapshot snapshot, final FluidWritePlan out, final int budget) {
        final ArrayBody body = new FluidBodyAdapter(snapshot).assemble();
        final FluidDelta delta = DELTA.get();
        final int moved = FluidEngine.step(body, delta, budget);
        final int emitted = FluidWriteIntent.emit(delta, body, out);
        return new Result(moved, emitted, delta.unfinished());
    }

    /**
     * 阶段 2b：整片采集结果 → 引擎一步 → 写意图。
     *
     * <p><b>unfinished 是两个来源的并</b>：
     * <ul>
     *   <li>{@link FluidDelta#unfinished()} —— 引擎被预算 / sweep 护栏打断（这一步没走遍整片）；</li>
     *   <li>{@link FluidBodyCollector.Piece#unfinished()} —— <b>采集侧</b>被片格数预算截断，
     *       这一片本来就只采了一部分（片外还有同种液体）。</li>
     * </ul>
     * 两者都意味着「没算完」，驱动层必须整片重排、<b>绝不许据此判收敛</b>
     * （否则水会被永久冻住）。
     *
     * @param piece  主线程采集冻结的整片（片内格 + 一圈边界格）
     * @param out    写意图出口（本步的水位增量会追加进去；可能跨 region，由调用方按 section 拆开）
     * @param budget 本步的搬运次数上限（&le; 0 时引擎立刻返回「没算完」，什么都不搬）
     */
    public static Result solvePiece(final FluidBodyCollector.Piece piece, final FluidWritePlan out,
                                    final int budget) {
        if (piece == null || piece.body() == null) {
            throw new IllegalArgumentException("piece / piece.body() 都不能为 null（没有液体时调用方不该派发）");
        }
        final FluidDelta delta = DELTA.get();
        final int moved = FluidEngine.step(piece.body(), delta, budget);
        final int emitted = FluidWriteIntent.emit(delta, piece.body(), out);
        return new Result(moved, emitted, delta.unfinished() || piece.unfinished());
    }
}
