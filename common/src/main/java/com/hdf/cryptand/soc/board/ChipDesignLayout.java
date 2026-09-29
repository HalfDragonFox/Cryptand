package com.hdf.cryptand.soc.board;

import java.util.ArrayList;
import java.util.List;

/**
 * ===== 芯片蓝图设计器的**设计态数据**（纯 Java 零 MC，2026-09-29 用户定案）=====
 *
 * <p>用户原话：「芯片蓝图设计器也是类似页面，只不过是给芯片做组装，上方有按钮可以创建点在芯片核心方框，
 * 然后从左侧拖入模块放置在 eda 中，然后模块带固定的点，固定点可以连接到芯片的通用点（白色点是通用协议，
 * 一般在芯片设计时使用），然后上面会显示芯片资源点数，每有一个连接就会消耗此连接的点数，
 * 一旦一次连线超过点数就无法连接并左下角提示」。</p>
 *
 * <p>规则（本类唯一口径）：</p>
 * <ul>
 *   <li><b>芯片通用点</b>（{@link ChipPoint}）：设计者在芯片核心方框上加的点，id 形如 {@code gp1}；
 *       画面上画白点（{@link com.hdf.cryptand.soc.link.PortKind#GENERIC}）；</li>
 *   <li><b>模块</b>（{@link Module}）：从左侧列表拖入画布，端口由模块定义固定给；</li>
 *   <li><b>连线</b>（{@link Wire}）：模块端口 ↔ 通用点，<b>一条线消耗 1 个资源点数</b>；</li>
 *   <li><b>资源点数</b>（{@code budget}）：就是原来的"组件数量"改名；超了就不许连，原因给中文
 *       （面板把原因打到画布左下角，见 {@code HardwareWiringCanvas.showStatus}）；</li>
 *   <li>一个通用点只能接一条线（点 = 一个待分配协议的引脚）。</li>
 * </ul>
 *
 * <p>本类不碰 NBT / MC 类型：蓝图落盘由 neoforge 侧把 {@link State} 抄进 CompoundTag。</p>
 */
public final class ChipDesignLayout {

    /** 芯片上的通用点（白点）。位置由画布几何算，不在这里存。 */
    public record ChipPoint(String id) {
    }

    /** 从左侧拖入的模块：端口固定（由模块定义给），只有位置是用户状态。 */
    public record Module(String id, String label, List<String> ports, int x, int y) {
        public Module {
            ports = ports == null ? List.of() : List.copyOf(ports);
        }
    }

    /** 一条设计连线：模块的某个端口 ↔ 芯片的某个通用点。 */
    public record Wire(String moduleId, String port, String chipPoint) {
    }

    /** 整个设计态。 */
    public record State(int budget, List<ChipPoint> points, List<Module> modules, List<Wire> wires) {
        public State {
            points = points == null ? List.of() : List.copyOf(points);
            modules = modules == null ? List.of() : List.copyOf(modules);
            wires = wires == null ? List.of() : List.copyOf(wires);
        }
    }

    /** 动作结果：失败时带中文原因（给左下角提示），并原样带回当前状态（调用方不用自己回滚）。 */
    public record Result(boolean ok, String reason, State next) {
        public static Result ok(State next) {
            return new Result(true, "", next);
        }

        public static Result fail(String reason, State state) {
            return new Result(false, reason, state);
        }
    }

    /** 通用点 id 前缀（{@code gp1}、{@code gp2}…）——必须与 {@code PortKind.GENERIC} 的 label 一致。 */
    public static final String POINT_PREFIX = "gp";

    private ChipDesignLayout() {
    }

    public static State empty(int budget) {
        return new State(Math.max(0, budget), List.of(), List.of(), List.of());
    }

    /** 已用资源点数 = 连线数（一条线 1 点）。 */
    public static int used(State s) {
        return s == null ? 0 : s.wires().size();
    }

    /** 剩余资源点数（可能为负？不会：connect 会拦住超额）。 */
    public static int remaining(State s) {
        return (s == null ? 0 : s.budget()) - used(s);
    }

    /** 顶部显示的"芯片资源点数：已用/预算"。 */
    public static String summary(State s) {
        return "芯片资源点数 " + used(s) + "/" + (s == null ? 0 : s.budget());
    }

    public static Module module(State s, String id) {
        if (s == null || id == null) {
            return null;
        }
        for (final Module m : s.modules()) {
            if (m.id().equals(id)) {
                return m;
            }
        }
        return null;
    }

    public static ChipPoint point(State s, String id) {
        if (s == null || id == null) {
            return null;
        }
        for (final ChipPoint p : s.points()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 芯片通用点

    /** 在芯片核心方框上加一个通用点（id 从 gp1 起找最小空号）。 */
    public static Result addPoint(State s) {
        if (s == null) {
            return Result.fail("设计态不存在", empty(0));
        }
        for (int n = 1; n < 512; n++) {
            final String id = POINT_PREFIX + n;
            if (point(s, id) == null) {
                final List<ChipPoint> next = new ArrayList<>(s.points());
                next.add(new ChipPoint(id));
                return Result.ok(new State(s.budget(), next, s.modules(), s.wires()));
            }
        }
        return Result.fail("通用点太多了（上限 511）", s);
    }

    /** 删掉一个通用点（连带删掉挂在它上面的线，退回资源点数）。 */
    public static Result removePoint(State s, String pointId) {
        if (s == null || point(s, pointId) == null) {
            return Result.fail("芯片上没有这个通用点", s);
        }
        final List<ChipPoint> nextPoints = new ArrayList<>();
        for (final ChipPoint p : s.points()) {
            if (!p.id().equals(pointId)) {
                nextPoints.add(p);
            }
        }
        final List<Wire> nextWires = new ArrayList<>();
        for (final Wire w : s.wires()) {
            if (!w.chipPoint().equals(pointId)) {
                nextWires.add(w);
            }
        }
        return Result.ok(new State(s.budget(), nextPoints, s.modules(), nextWires));
    }

    // ------------------------------------------------------------------ 模块

    /** 把一个模块放进画布（id 重复 ⇒ 拒绝）。 */
    public static Result addModule(State s, Module m) {
        if (s == null || m == null) {
            return Result.fail("模块不存在", s);
        }
        if (module(s, m.id()) != null) {
            return Result.fail("这个模块已经在画布上了：" + m.label(), s);
        }
        final List<Module> next = new ArrayList<>(s.modules());
        next.add(m);
        return Result.ok(new State(s.budget(), s.points(), next, s.wires()));
    }

    /** 拖动模块后的新位置。 */
    public static Result moveModule(State s, String moduleId, int x, int y) {
        final Module m = module(s, moduleId);
        if (m == null) {
            return Result.fail("画布上没有这个模块", s);
        }
        final List<Module> next = new ArrayList<>();
        for (final Module each : s.modules()) {
            next.add(each.id().equals(moduleId)
                    ? new Module(each.id(), each.label(), each.ports(), x, y) : each);
        }
        return Result.ok(new State(s.budget(), s.points(), next, s.wires()));
    }

    /** 从画布拿走模块（连带删掉它的线，退回点数）。 */
    public static Result removeModule(State s, String moduleId) {
        if (module(s, moduleId) == null) {
            return Result.fail("画布上没有这个模块", s);
        }
        final List<Module> nextModules = new ArrayList<>();
        for (final Module m : s.modules()) {
            if (!m.id().equals(moduleId)) {
                nextModules.add(m);
            }
        }
        final List<Wire> nextWires = new ArrayList<>();
        for (final Wire w : s.wires()) {
            if (!w.moduleId().equals(moduleId)) {
                nextWires.add(w);
            }
        }
        return Result.ok(new State(s.budget(), s.points(), nextModules, nextWires));
    }

    // ------------------------------------------------------------------ 连线（消耗资源点数）

    /** 这个模块端口当前连到的通用点 id（未连 = null）。 */
    public static String pointOf(State s, String moduleId, String port) {
        if (s == null) {
            return null;
        }
        for (final Wire w : s.wires()) {
            if (w.moduleId().equals(moduleId) && w.port().equals(port)) {
                return w.chipPoint();
            }
        }
        return null;
    }

    /** 这条线上有没有被别的模块占用（未连 = 无人占用）。 */
    public static String ownerOfPoint(State s, String pointId) {
        if (s == null) {
            return null;
        }
        for (final Wire w : s.wires()) {
            if (w.chipPoint().equals(pointId)) {
                return w.moduleId();
            }
        }
        return null;
    }

    /**
     * 连线：模块端口 → 芯片通用点。**每条线消耗 1 个资源点数**，不够就拒绝（中文原因）。
     *
     * <p>幂等：同一模块同一端口重复连同一个点 ⇒ 成功且不重复计数（画布乐观更新后服务端再确认时会走到）。</p>
     */
    public static Result connect(State s, String moduleId, String port, String pointId) {
        if (s == null) {
            return Result.fail("设计态不存在", empty(0));
        }
        final Module m = module(s, moduleId);
        if (m == null) {
            return Result.fail("画布上没有这个模块", s);
        }
        if (port == null || !m.ports().contains(port)) {
            return Result.fail("模块 " + m.label() + " 没有端口 " + port, s);
        }
        if (point(s, pointId) == null) {
            return Result.fail("芯片上没有通用点 " + pointId, s);
        }
        final String already = pointOf(s, moduleId, port);
        if (pointId.equals(already)) {
            return Result.ok(s);                       // 幂等
        }
        final String owner = ownerOfPoint(s, pointId);
        if (owner != null && !owner.equals(moduleId)) {
            final Module om = module(s, owner);
            return Result.fail("通用点 " + pointId + " 已经被 " + (om == null ? owner : om.label()) + " 占用", s);
        }
        // 换点也算新增一条线：只有 already == null 时才真正新增
        final boolean isNew = already == null;
        if (isNew && remaining(s) <= 0) {
            return Result.fail("芯片资源点数不够（已用 " + used(s) + "/" + s.budget() + "）", s);
        }
        final List<Wire> next = new ArrayList<>();
        for (final Wire w : s.wires()) {
            if (!(w.moduleId().equals(moduleId) && w.port().equals(port))) {
                next.add(w);
            }
        }
        next.add(new Wire(moduleId, port, pointId));
        return Result.ok(new State(s.budget(), s.points(), s.modules(), next));
    }

    /** 断线（退回 1 个资源点数）。 */
    public static Result disconnect(State s, String moduleId, String port) {
        if (pointOf(s, moduleId, port) == null) {
            return Result.fail("这条线本来就不在", s);
        }
        final List<Wire> next = new ArrayList<>();
        for (final Wire w : s.wires()) {
            if (!(w.moduleId().equals(moduleId) && w.port().equals(port))) {
                next.add(w);
            }
        }
        return Result.ok(new State(s.budget(), s.points(), s.modules(), next));
    }

    /** 改预算（族/档位变了 ⇒ 资源点数跟着变；超出部分拒绝：报"要减到多少条线"）。 */
    public static Result setBudget(State s, int budget) {
        final int b = Math.max(0, budget);
        if (used(s) > b) {
            return Result.fail("新预算 " + b + " 装不下当前 " + used(s) + " 条连线，先拆掉 " + (used(s) - b) + " 条", s);
        }
        return Result.ok(new State(b, s.points(), s.modules(), s.wires()));
    }
}
