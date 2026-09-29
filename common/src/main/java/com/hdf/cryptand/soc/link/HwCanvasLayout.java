package com.hdf.cryptand.soc.link;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/**
 * ===== 硬件连接画布（EDA 风格）纯几何层（2026-09-29 用户定案）=====
 *
 * <p>用户给的示例图：器件图标摆在画布上，图标边上是彩色「接口点」，同色的点用同色的线连起来，
 * 布局像 EDA 原理图（器件符号 + 引脚 + 连线）。所以连接器打开的不是一张表单，而是一块<b>画布</b>：
 * 左边是机箱里的芯片（虚拟机），中间是扩展卡，右边是外设（屏/键盘…），点与点之间拉线。</p>
 *
 * <p>2026-09-29 第二轮（用户定案原话）：</p>
 * <ul>
 *   <li>「每个外设的名称和图标对应起来」⇒ 每种设备一个 {@link NodeKind}（图标）+ 中文名（名称由
 *       neoforge 的设备表给），名称与图标一一对应；</li>
 *   <li>「我们的芯片即虚拟机为单独一个」⇒ 画布上芯片只有一台（锚点机箱那台），
 *       枚举侧不再把周边机器都当成芯片；</li>
 *   <li>「所有外设通道比如 uart 之类的是一个通道，可以按照那种 soc 的引脚的方式给四周加上点，
 *       如果点放不下就放大框」⇒ {@link NodeKind#pinsAround()} 的设备沿<b>四边</b>布点
 *       （{@link #dots}），且框会按点数自动放大（{@link #fitFor}）。</li>
 * </ul>
 *
 * <p>本类是<b>唯一的布局与几何来源</b>（common，纯 Java 零 MC）：摆位、点坐标、命中测试、连线走线、
 * 用户拖动后的位置编码。面板（neoforge）只负责画像素与收鼠标事件 —— 这样几何能被离线闸门钉住
 * （{@code :common:runHwCanvasTest}），不必靠开客户端调。</p>
 *
 * <p>坐标一律是<b>画布像素坐标</b>（左上原点，整数；面板直接用 {@code CanvasSurface} 光栅化）。</p>
 */
public final class HwCanvasLayout {

    /**
     * 器件种类：<b>一种设备一个图标</b>（用户定案"每个外设的名称和图标对应起来"），
     * 同时决定分列（芯片 → 卡 → 外设）与布点方式。
     */
    public enum NodeKind {
        /** 机箱里那颗芯片 = 虚拟机本体（接线图的中枢；画布上只有一台）。 */
        CHIP,
        /** 图形扩展卡（金手指 + DP 输出口）。 */
        CARD_GPU,
        /** 其它扩展卡 / 底板。 */
        CARD,
        /** 磁盘 / 存储。 */
        DISK,
        /** 启动 EEPROM。 */
        EEPROM,
        /** 键盘。 */
        KEYBOARD,
        /** 屏（真彩屏 / OC 屏）。 */
        SCREEN,
        /** 其它外设。 */
        PERIPHERAL,
        /** 芯片设计器里的**模块**（可拖入画布，端口固定）。 */
        MODULE,
        /** 机箱本身（只画一个框，不参与连线）。 */
        CASE;

        /** 单边布点时点画在<b>左</b>边缘（外设类：信号进来的那一侧）。 */
        public boolean dotsOnLeft() {
            return this == SCREEN || this == KEYBOARD || this == DISK
                    || this == EEPROM || this == PERIPHERAL;
        }

        /** 设计器里模块的点在左（朝芯片那一侧）。 */
        public boolean isModule() {
            return this == MODULE;
        }

        /** 四边引脚式布点（芯片 = SoC：用户定案"给四周加上点"）。 */
        public boolean pinsAround() {
            return this == CHIP;
        }

        /** 两侧布点（卡类：输入在左朝芯片、输出在右朝外设）。 */
        public boolean dotsBothSides() {
            return this == CARD_GPU || this == CARD;
        }
    }

    /** 边的方位（布点用）。 */
    private enum Side {
        LEFT, RIGHT, TOP, BOTTOM
    }

    /** 器件规格（来自端点枚举，不含坐标）。 */
    public record NodeSpec(String key, String label, NodeKind kind, List<String> ports) {
        public NodeSpec {
            ports = ports == null ? List.of() : List.copyOf(ports);
        }
    }

    /** 画布上的器件（用户可拖动，位置是唯一被持久化的状态）。 */
    public record Node(String key, String label, NodeKind kind, int x, int y, int w, int h) {
    }

    /** 接口点（绝对画布坐标；kind 为 null = 端口 id 认不出来，画灰点且不可连）。 */
    public record Dot(String nodeKey, String portId, PortKind kind, int x, int y) {
        public int argb() {
            return kind == null ? 0xFF808080 : kind.argb();
        }
    }

    /** 一条连线（两端：器件 key + 端口 id）。 */
    public record Wire(String aNode, String aPort, String bNode, String bPort, PortKind kind) {
    }

    /** 器件框（**统一尺寸**：物品图标居中、名称在框内下方；点放不下再由 {@link #fitFor} 放大）。 */
    public static final int NODE_W = 64;
    public static final int NODE_H = 64;
    /** 机箱框。 */
    public static final int CASE_W = 104;
    public static final int CASE_H = 76;
    /** 框的下限（再挤也不小于它，宁可让画布滚动）。 */
    public static final int MIN_W = 40;
    public static final int MIN_H = 32;
    public static final int DOT_R = 4;
    /** 同一边相邻引脚的最小间距（点放不下 ⇒ 放大框）。 */
    public static final int PIN_GAP = 11;
    /** 一条边两端的留白（引脚不贴角）。 */
    public static final int PIN_MARGIN = 8;
    public static final int PAD = 24;
    /** 列间距（够画折线，也让器件之间留白）。 */
    public static final int COLUMN_GAP = 64;
    /** 列间距的下限（画布窄时也要能走线）。 */
    public static final int MIN_COLUMN_GAP = 12;

    private HwCanvasLayout() {
    }

    // ------------------------------------------------------------------ 尺寸

    /** 该种类的**基准**宽（还没按点数放大）：器件统一，机箱单独一档。 */
    public static int baseW(NodeKind kind) {
        return kind == NodeKind.CASE ? CASE_W : NODE_W;
    }

    /** 该种类的**基准**高（还没按点数放大）：器件统一，机箱单独一档。 */
    public static int baseH(NodeKind kind) {
        return kind == NodeKind.CASE ? CASE_H : NODE_H;
    }

    /** 名称两侧的最小留白（外框要比名称宽这么多，名称居中后才不会顶到边）。 */
    public static final int LABEL_PAD = 10;

    /**
     * 器件名称宽度估算（纯 Java；与画布绘制同一口径：0.85 缩放 ≈ 中文 8px/字、ASCII 5px/字）。
     *
     * <p>用户 2026-09-29 反馈"还有偏移"的真因：名称比外框宽时，居中后文字必然超框。
     * 所以外框宽度必须由名称宽度兜底 —— 见 {@link #fitFor}。</p>
     */
    public static int estimatedTextWidth(String s) {
        if (s == null) {
            return 0;
        }
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E80 ? 8 : 5;
        }
        return w;
    }

    /** 一条边放得下几个引脚（两端各留 {@link #PIN_MARGIN}）。 */
    public static int sideCapacity(int side) {
        return Math.max(1, (side - 2 * PIN_MARGIN) / PIN_GAP + 1);
    }

    /**
     * 按端口数把框放大到"点放得下"（用户定案："如果点放不下就放大框"）。
     *
     * <p>容量口径：四边布点 ⇒ 四条边之和；两侧布点 ⇒ 上下两条边（左右各一条）之和；
     * 单边布点 ⇒ 一条边。放大步长 8px，最多 64 步（不会无限放大）。</p>
     */
    public static Node fitFor(Node n, int portCount) {
        if (n == null) {
            return null;
        }
        final NodeKind kind = n.kind() == null ? NodeKind.PERIPHERAL : n.kind();
        int w = Math.max(MIN_W, n.w() > 0 ? n.w() : baseW(kind));
        int h = Math.max(MIN_H, n.h() > 0 ? n.h() : baseH(kind));
        // 名称必须装得下：外框比名称宽出两侧留白（否则名称居中后超出外框，看起来偏移）
        w = Math.max(w, estimatedTextWidth(n.label()) + LABEL_PAD);
        final int count = Math.max(0, portCount);
        for (int guard = 0; guard < 64; guard++) {
            final int capacity;
            if (kind.pinsAround()) {
                capacity = 2 * sideCapacity(w) + 2 * sideCapacity(h);
            } else if (kind.dotsBothSides()) {
                capacity = 2 * sideCapacity(h);
            } else {
                capacity = sideCapacity(h);
            }
            if (capacity >= count) {
                break;
            }
            if (kind.pinsAround()) {
                w += 8;      // 四边布点：框整体长大
                h += 8;
            } else {
                h += 8;      // 单边 / 两侧布点：点沿竖直边分布，加高就够
            }
        }
        return new Node(n.key(), n.label(), kind, n.x(), n.y(), w, h);
    }

    // ------------------------------------------------------------------ 摆位

    /**
     * 自动摆位：按 {@link #columnOf} 分列（芯片最左、卡居中、屏/外设最右），列内竖直均分。
     *
     * <p>每个器件的尺寸先按它自己的端口数 {@link #fitFor}（点放不下就放大框），同列取最大尺寸，
     * 保证同列对齐、列间留出走线间距。位置只作为<b>初值</b>：用户拖动后由 {@link #applyPositions} 覆盖。</p>
     */
    public static List<Node> autoLayout(List<NodeSpec> specs, int canvasW, int canvasH) {
        final List<Node> out = new ArrayList<>();
        if (specs == null || specs.isEmpty()) {
            return out;
        }
        final Map<Integer, List<NodeSpec>> columns = new TreeMap<>();
        for (final NodeSpec s : specs) {
            columns.computeIfAbsent(columnOf(s.kind()), k -> new ArrayList<>()).add(s);
        }
        // 先定每列的尺寸（按点数放大）
        final List<List<Node>> sized = new ArrayList<>();
        final List<Integer> widths = new ArrayList<>();
        final List<Integer> heights = new ArrayList<>();
        for (final List<NodeSpec> col : columns.values()) {
            final List<Node> colNodes = new ArrayList<>();
            int colW = 0;
            int colH = 0;
            for (final NodeSpec s : col) {
                final Node base = new Node(s.key(), s.label(), s.kind(), 0, 0, 0, 0);
                final Node fit = fitFor(base, s.ports().size());
                colNodes.add(fit);
                colW = Math.max(colW, fit.w());
                colH = Math.max(colH, fit.h());
            }
            sized.add(colNodes);
            widths.add(colW);
            heights.add(colH);
        }
        // 列间距：画布窄时压缩（但不小于下限），保证右边不越界
        int sumW = 0;
        for (final int w : widths) {
            sumW += w;
        }
        final int gaps = Math.max(0, sized.size() - 1);
        final int available = canvasW - 2 * PAD - sumW;
        final int gap = gaps == 0 ? 0 : Math.max(MIN_COLUMN_GAP, Math.min(COLUMN_GAP, available / gaps));
        int x = PAD;
        for (int c = 0; c < sized.size(); c++) {
            final List<Node> colNodes = sized.get(c);
            final int colW = widths.get(c);
            final int colH = heights.get(c);
            for (int i = 0; i < colNodes.size(); i++) {
                final Node n0 = colNodes.get(i);
                final int y = rowY(i, colNodes.size(), canvasH, colH);
                out.add(new Node(n0.key(), n0.label(), n0.kind(), x, y, colW, colH));
            }
            x += colW + gap;
        }
        return out;
    }

    /** 列序号：CHIP 0、卡 1、屏/外设/存储/键盘 2、CASE 3（排序用）。 */
    public static int columnOf(NodeKind kind) {
        if (kind == null) {
            return 2;
        }
        return switch (kind) {
            case CHIP -> 0;
            case CARD_GPU, CARD -> 1;
            case DISK, EEPROM, KEYBOARD, SCREEN, PERIPHERAL, MODULE -> 2;
            default -> 3;
        };
    }

    /** 同一列里第 i 个（共 n 个）的 y：把器件均匀铺在 [PAD, canvasH-PAD-nodeH] 上。 */
    public static int rowY(int i, int n, int canvasH, int nodeH) {
        final int top = PAD;
        final int bottom = Math.max(top, canvasH - PAD - nodeH);
        if (n <= 1) {
            return (top + bottom) / 2;
        }
        return top + (bottom - top) * i / (n - 1);
    }

    /** 兼容旧签名：按默认框高（{@link #NODE_H}）均分。 */
    public static int rowY(int i, int n, int canvasH) {
        return rowY(i, n, canvasH, NODE_H);
    }

    // ------------------------------------------------------------------ 接口点

    /**
     * 端口 id 列表 → 画布上的点（按 {@link NodeKind} 决定布点方式）。
     *
     * <ul>
     *   <li><b>芯片</b>：沿四条边（右 → 左 → 上 → 下）均匀分布 —— SoC 引脚式；</li>
     *   <li><b>卡</b>：输入（RV/PCIE）在左边缘、输出（DP/USB/…）在右边缘；</li>
     *   <li><b>屏/键盘/存储/外设</b>：单边（默认左边缘，信号进来的那一侧）。</li>
     * </ul>
     *
     * <p>点的颜色由 {@link PortKind#byId} 决定 —— 认不出来的端口画灰点，且 {@link #canConnect} 拒绝，
     * 绝不"猜"一种颜色去连。</p>
     */
    public static List<Dot> dots(Node n, List<String> ports) {
        final List<Dot> out = new ArrayList<>();
        if (n == null || ports == null || ports.isEmpty()) {
            return out;
        }
        final NodeKind kind = n.kind() == null ? NodeKind.PERIPHERAL : n.kind();
        if (kind.pinsAround()) {
            final int count = ports.size();
            final int perSide = (count + 3) / 4;
            final Side[] sides = {Side.RIGHT, Side.LEFT, Side.TOP, Side.BOTTOM};
            int index = 0;
            for (final Side side : sides) {
                final int take = Math.min(perSide, count - index);
                for (int j = 0; j < take; j++) {
                    out.add(place(n, ports.get(index++), side, j, take));
                }
                if (index >= count) {
                    break;
                }
            }
            return out;
        }
        if (kind.dotsBothSides()) {
            final List<String> in = new ArrayList<>();
            final List<String> outs = new ArrayList<>();
            for (final String p : ports) {
                if (PortKind.byId(p) == PortKind.RV) {
                    in.add(p);
                } else {
                    outs.add(p);
                }
            }
            for (int j = 0; j < in.size(); j++) {
                out.add(place(n, in.get(j), Side.LEFT, j, in.size()));
            }
            for (int j = 0; j < outs.size(); j++) {
                out.add(place(n, outs.get(j), Side.RIGHT, j, outs.size()));
            }
            return out;
        }
        final Side side = kind.dotsOnLeft() ? Side.LEFT : Side.RIGHT;
        for (int j = 0; j < ports.size(); j++) {
            out.add(place(n, ports.get(j), side, j, ports.size()));
        }
        return out;
    }

    /** 把一个点放到某条边上（沿边均分）。 */
    private static Dot place(Node n, String portId, Side side, int index, int count) {
        final int x;
        final int y;
        switch (side) {
            case LEFT -> {
                x = n.x();
                y = along(n.y(), n.h(), index, count);
            }
            case RIGHT -> {
                x = n.x() + n.w();
                y = along(n.y(), n.h(), index, count);
            }
            case TOP -> {
                x = along(n.x(), n.w(), index, count);
                y = n.y();
            }
            default -> {
                x = along(n.x(), n.w(), index, count);
                y = n.y() + n.h();
            }
        }
        return new Dot(n.key(), portId, PortKind.byId(portId), x, y);
    }

    /** 沿边均分：第 index 个（共 count 个）落在 (index+1)/(count+1) 处。 */
    private static int along(int start, int len, int index, int count) {
        return start + len * (index + 1) / (count + 1);
    }

    /** 命中最近的点（半径内）；没有则 null。 */
    public static Dot hitDot(List<Dot> dots, float x, float y, float radius) {
        Dot best = null;
        float bestD = radius * radius;
        if (dots == null) {
            return null;
        }
        for (final Dot d : dots) {
            final float dx = x - d.x();
            final float dy = y - d.y();
            final float dist = dx * dx + dy * dy;
            if (dist <= bestD) {
                bestD = dist;
                best = d;
            }
        }
        return best;
    }

    /** 命中器件矩形（用于拖动）；没有则 null。 */
    public static Node hitNode(List<Node> nodes, float x, float y) {
        if (nodes == null) {
            return null;
        }
        for (final Node n : nodes) {
            if (x >= n.x() && x <= n.x() + n.w() && y >= n.y() && y <= n.y() + n.h()) {
                return n;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 连线

    /** 同色才能连：一类接口 = 一种颜色，异色/未知一律拒绝。 */
    public static boolean canConnect(PortKind a, PortKind b) {
        return a != null && a == b;
    }

    /**
     * **设计期**连线规则（芯片设计器，2026-09-29 用户定案）：
     * 涉及"通用点"（{@link PortKind#GENERIC}，白点）时任意协议都能连，其余仍要求同色。
     *
     * <p>为什么单独一条：硬件连接器是真实链路（协议必须一致），而设计期的通用点是"待分配协议"，
     * 所以只在设计器里放宽 —— 绝不放宽 {@link #canConnect}。</p>
     */
    public static boolean canConnectDesign(PortKind a, PortKind b) {
        if (a == null || b == null) {
            return false;
        }
        return a == b || a == PortKind.GENERIC || b == PortKind.GENERIC;
    }

    /**
     * EDA 风格折线走线：横 → 竖 → 横（两端 y 相同则一段直线）。
     *
     * @return 线段列表，每段 {@code [x0,y0,x1,y1]}，相邻段首尾相接
     */
    public static List<int[]> route(int x0, int y0, int x1, int y1) {
        final List<int[]> segs = new ArrayList<>();
        if (y0 == y1) {
            segs.add(new int[]{x0, y0, x1, y1});
            return segs;
        }
        final int mid = (x0 + x1) / 2;
        segs.add(new int[]{x0, y0, mid, y0});
        segs.add(new int[]{mid, y0, mid, y1});
        segs.add(new int[]{mid, y1, x1, y1});
        return segs;
    }

    /** 命中某条连线（点到折线任意段距离 ≤ tol）；没有则 null。 */
    public static Wire hitWire(List<Wire> wires, BiFunction<String, String, Dot> dotOf, float x, float y, float tol) {
        if (wires == null || dotOf == null) {
            return null;
        }
        for (final Wire w : wires) {
            final Dot a = dotOf.apply(w.aNode(), w.aPort());
            final Dot b = dotOf.apply(w.bNode(), w.bPort());
            if (a == null || b == null) {
                continue;
            }
            for (final int[] s : route(a.x(), a.y(), b.x(), b.y())) {
                if (segmentDistance(x, y, s[0], s[1], s[2], s[3]) <= tol) {
                    return w;
                }
            }
        }
        return null;
    }

    /** 点到线段距离（像素）。 */
    public static float segmentDistance(float px, float py, float x0, float y0, float x1, float y1) {
        final float dx = x1 - x0;
        final float dy = y1 - y0;
        final float len2 = dx * dx + dy * dy;
        if (len2 <= 1e-6f) {
            final float ax = px - x0;
            final float ay = py - y0;
            return (float) Math.sqrt(ax * ax + ay * ay);
        }
        float t = ((px - x0) * dx + (py - y0) * dy) / len2;
        t = Math.max(0f, Math.min(1f, t));
        final float cx = x0 + t * dx;
        final float cy = y0 + t * dy;
        final float ex = px - cx;
        final float ey = py - cy;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }

    // ------------------------------------------------------------------ 拖动与持久化

    /** 拖动后的位置（夹在画布内，不许拖出去丢件）。 */
    public static Node move(Node n, int dx, int dy, int canvasW, int canvasH) {
        final int x = Math.max(0, Math.min(Math.max(0, canvasW - n.w()), n.x() + dx));
        final int y = Math.max(0, Math.min(Math.max(0, canvasH - n.h()), n.y() + dy));
        return new Node(n.key(), n.label(), n.kind(), x, y, n.w(), n.h());
    }

    /**
     * 整体平移所有器件（用户定案：在 EDA 设计页面的<b>空白处右键拖动</b> ⇒ 拖动整个布局）。
     *
     * <p>位移量按"所有器件都不出画布"夹住（相对位置保持不变），返回新列表、不改原表。</p>
     */
    public static List<Node> translateAll(List<Node> nodes, int dx, int dy, int canvasW, int canvasH) {
        final List<Node> out = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) {
            return out;
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (final Node n : nodes) {
            minX = Math.min(minX, n.x());
            minY = Math.min(minY, n.y());
            maxX = Math.max(maxX, n.x() + n.w());
            maxY = Math.max(maxY, n.y() + n.h());
        }
        final int loX = -minX;                        // 往右最多推这么多（左边贴 0）
        final int hiX = Math.max(loX, canvasW - maxX);
        final int loY = -minY;
        final int hiY = Math.max(loY, canvasH - maxY);
        final int stepX = Math.max(loX, Math.min(hiX, dx));
        final int stepY = Math.max(loY, Math.min(hiY, dy));
        for (final Node n : nodes) {
            out.add(new Node(n.key(), n.label(), n.kind(),
                    n.x() + stepX, n.y() + stepY, n.w(), n.h()));
        }
        return out;
    }

    /** 位置编码（连接器物品 NBT 用）：{@code key=x,y} 以 {@code ;} 分隔，key 里不许有分隔符。 */
    public static String encodePositions(List<Node> nodes) {
        final StringBuilder sb = new StringBuilder();
        if (nodes != null) {
            for (final Node n : nodes) {
                if (sb.length() > 0) {
                    sb.append(';');
                }
                sb.append(n.key()).append('=').append(n.x()).append(',').append(n.y());
            }
        }
        return sb.toString();
    }

    /**
     * 把编码过的位置套到<b>当前枚举出来的</b>器件上（标签/种类/端口都以实时枚举为准，
     * 只有 x/y 是用户状态）。认不出的 key 直接忽略（设备被拆了），坏数据不抛。
     */
    public static List<Node> applyPositions(List<Node> live, String encoded, int canvasW, int canvasH) {
        final List<Node> out = new ArrayList<>();
        if (live == null) {
            return out;
        }
        final Map<String, int[]> saved = decodePositions(encoded);
        for (final Node n : live) {
            final int[] xy = saved.get(n.key());
            if (xy == null) {
                out.add(n);
            } else {
                out.add(new Node(n.key(), n.label(), n.kind(),
                        Math.max(0, Math.min(Math.max(0, canvasW - n.w()), xy[0])),
                        Math.max(0, Math.min(Math.max(0, canvasH - n.h()), xy[1])),
                        n.w(), n.h()));
            }
        }
        return out;
    }

    /** 位置解码；坏条目跳过（不抛）。 */
    public static Map<String, int[]> decodePositions(String encoded) {
        final Map<String, int[]> out = new java.util.HashMap<>();
        if (encoded == null || encoded.isBlank()) {
            return out;
        }
        for (final String part : encoded.split(";")) {
            final int eq = part.indexOf('=');
            if (eq <= 0 || eq == part.length() - 1) {
                continue;
            }
            final String key = part.substring(0, eq).trim();
            final String[] xy = part.substring(eq + 1).split(",");
            if (key.isEmpty() || xy.length != 2) {
                continue;
            }
            try {
                out.put(key, new int[]{Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())});
            } catch (NumberFormatException ignored) {
                // 坏数据跳过
            }
        }
        return out;
    }
}
