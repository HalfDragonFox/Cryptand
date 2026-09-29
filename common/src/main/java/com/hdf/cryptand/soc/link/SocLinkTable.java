package com.hdf.cryptand.soc.link;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ===== 设备链路表（2026-09-28，纯 Java 零 MC）=====
 *
 * <p>按《接口准入》定案（用户 2026-09-27 裁定）：<b>接口 = 准入券</b>；每个设备声明
 * **支持接口列表**（顺序即优先级，具体列表放 neoforge，见 {@code SocDeviceInterfaces}）；
 * 组件分**源组件**（外设/CPU，单端）与**中间组件**（扩展卡/桥，双向，可级联）；
 * **级联上限 128**（裁定 E）；**链路速率 = 木桶效应**（整条路径上最低的一段，裁定 I）。</p>
 *
 * <p>本类只管**拓扑与协商**（谁和谁连、用什么协议、跳数与木桶速率），不管准入策略
 * （自动只限「外设 ↔ RV 芯片」、扩展卡必须手动等）——那是平台层准入器的事。</p>
 *
 * <p><b>2026-09-28 端点身份升级</b>：端点键从「坐标」改成 <b>{@link EndpointKey}（坐标 + 设备类型）</b>。
 * 原因是用户定案「扩展卡默认插上不连接外设和 RV，空连接，需要两边都连上」：扩展卡在机箱
 * **槽位**里，和机箱的 {@link #CHIP_DEVICE} 端点**同坐标** —— 按坐标唯一就放不下
 * 「一个方块上多个端点」。所以内部记账按端点键，坐标级 API 保留为便捷入口（单端点时行为不变，
 * 多端点时按"该坐标上任意端点"聚合）。</p>
 */
public final class SocLinkTable {

    /** 级联上限（用户裁定 E）：中间组件最多串这么多段。 */
    public static final int MAX_CASCADE = 128;

    /**
     * 芯片侧端点的 {@code deviceId}（RV/CPU 总线端点）。
     *
     * <p>约定来自「两端都连」判据：扩展卡一边连芯片、一边连外设，两边都连上才成立。</p>
     */
    public static final String CHIP_DEVICE = "chip";

    /** 世界坐标端点。 */
    public record Pos(int x, int y, int z) {
    }

    /**
     * 链路端点：位置 + 设备类型 id + 角色 + **支持接口列表**（顺序即优先级）。
     *
     * <p>接口列表在紧凑构造里规范化：去掉 null、去重、按全局优先级升序 —— 这样"列表顺序即优先级"
     * 永远成立，调用方（neoforge 的表）就算写乱了也不会产生两套口径。</p>
     */
    public record Endpoint(Pos pos, String deviceId, String port, SocLinkRole role, List<LinkProtocol> interfaces,
                           int maxLinks, PortRole linkRole) {

        /**
         * 端口端点（**每端默认只允许 1 条连接**）。
         *
         * <p>用户 2026-09-29 定案：「外设需要给每个点最多一个连接，即需要通道增加连接上限概念」——
         * 屏、键盘、盘这类终端端口天然只能接一条；芯片的总线端口（PCIe/USB/SPI/I2C）可以挂多台，
         * 由调用方显式传入上限（见 {@code SocDeviceInterfaces.maxLinks}）。</p>
         */
        public Endpoint(Pos pos, String deviceId, String port, SocLinkRole role, List<LinkProtocol> interfaces) {
            this(pos, deviceId, port, role, interfaces, 0, PortRole.PEER);
        }

        /** 端口端点 + 显式上限（0 = 跟随协议/角色）。 */
        public Endpoint(Pos pos, String deviceId, String port, SocLinkRole role, List<LinkProtocol> interfaces,
                        int maxLinks) {
            this(pos, deviceId, port, role, interfaces, maxLinks, PortRole.PEER);
        }

        /** 端口端点 + 主从角色（总线从设备 = 只能接一条）。 */
        public Endpoint(Pos pos, String deviceId, String port, SocLinkRole role, List<LinkProtocol> interfaces,
                        PortRole linkRole) {
            this(pos, deviceId, port, role, interfaces, 0, linkRole == null ? PortRole.PEER : linkRole);
        }

        /**
         * 设备级端点（没有端口概念的一端，例如老代码里的"整台设备"）。端口为 {@code ""}。
         */
        public Endpoint(Pos pos, String deviceId, SocLinkRole role, List<LinkProtocol> interfaces) {
            this(pos, deviceId, "", role, interfaces, 0, PortRole.PEER);
        }

        public Endpoint {
            final List<LinkProtocol> clean = new ArrayList<>();
            if (interfaces != null) {
                for (final LinkProtocol p : interfaces) {
                    if (p != null && !clean.contains(p)) {
                        clean.add(p);
                    }
                }
            }
            clean.sort((x, y) -> Integer.compare(x.priority(), y.priority()));
            interfaces = List.copyOf(clean);
            deviceId = deviceId == null ? "" : deviceId;
            port = port == null ? "" : port.trim();
            maxLinks = Math.max(0, maxLinks);   // 0 = 跟随角色/协议（推荐）；>0 = 显式覆盖
            linkRole = linkRole == null ? PortRole.PEER : linkRole;
        }

        /**
         * 这个端点对某协议最多能挂几条。
         *
         * <p>优先级：显式 {@code maxLinks} &gt; 角色（{@link PortRole#SLAVE} = 1 条） &gt;
         * 协议默认（{@link LinkProtocol#maxDevices()}，UART/DP/8080 这类点对点就是 1）。</p>
         */
        public int effectiveMaxLinks(LinkProtocol protocol) {
            if (maxLinks > 0) {
                return maxLinks;
            }
            if (linkRole == PortRole.SLAVE) {
                return 1;       // 从设备端口：一条总线只能挂一次（用户 2026-09-29 定案，I2C 就是这种）
            }
            return protocol == null ? 1 : protocol.maxDevices();
        }

        /**
         * 这个端点对该协议是否已经接满（{@code used} = 它当前已挂的链路数）。
         *
         * <p>⚠ <b>设备级端点（{@code port} 为空）不受约束</b>：它代表"整台设备"（例如一张扩展卡），
         * 两端的链路都挂在同一个键上 ⇒ 必须允许多条。只有**带端口的点**才按协议限数。</p>
         */
        public boolean isFull(int used, LinkProtocol protocol) {
            return hasPort() && used >= effectiveMaxLinks(protocol);
        }

        public boolean supports(LinkProtocol p) {
            return p != null && interfaces.contains(p);
        }

        /** 端口 id 形如 {@code rv} / {@code dp1}；设备级端点为空串。 */
        public boolean hasPort() {
            return !port.isEmpty();
        }

        public EndpointKey key() {
            return new EndpointKey(pos, deviceId, port);
        }
    }

    /**
     * 端点身份键：**坐标 + 设备类型**。
     *
     * <p>为什么不是只有坐标：机箱（{@link #CHIP_DEVICE}）与插在它槽位里的扩展卡同坐标，
     * 但它们是两个不同的端点 —— "卡 ↔ 芯片" 这条线就是同坐标的两个端点之间的链路。</p>
     */
    public record EndpointKey(Pos pos, String deviceId, String port) {

        /** 设备级端点键（无端口）。 */
        public EndpointKey(Pos pos, String deviceId) {
            this(pos, deviceId, "");
        }

        public EndpointKey {
            deviceId = deviceId == null ? "" : deviceId;
            port = port == null ? "" : port.trim();
        }

        /** 设备级端点键（port 为空）。 */
        public boolean deviceLevel() {
            return port.isEmpty();
        }
    }

    /** 一条链路（无向边；{@code a}/{@code b} 只是两端，不代表方向）。 */
    public record Link(Endpoint a, Endpoint b, LinkProtocol protocol) {

        public boolean touches(Pos p) {
            return a.pos().equals(p) || b.pos().equals(p);
        }

        public boolean touches(EndpointKey k) {
            return a.key().equals(k) || b.key().equals(k);
        }

        /** 另一端的坐标（p 不在这条链路上时返回 null）。同坐标两端时返回自身坐标（自环，无害）。 */
        public Pos other(Pos p) {
            if (a.pos().equals(p)) {
                return b.pos();
            }
            return b.pos().equals(p) ? a.pos() : null;
        }

        /** 另一端的端点键（k 不在这条链路上时返回 null）。 */
        public EndpointKey other(EndpointKey k) {
            if (a.key().equals(k)) {
                return b.key();
            }
            return b.key().equals(k) ? a.key() : null;
        }

        public boolean between(Pos x, Pos y) {
            return (a.pos().equals(x) && b.pos().equals(y)) || (a.pos().equals(y) && b.pos().equals(x));
        }

        public boolean between(EndpointKey x, EndpointKey y) {
            return (a.key().equals(x) && b.key().equals(y)) || (a.key().equals(y) && b.key().equals(x));
        }
    }

    /** 连线结果（成功/失败 + 协议 + 给玩家看的中文原因）。 */
    public record Result(boolean ok, LinkProtocol protocol, String reason) {

        public static Result ok(LinkProtocol protocol, String reason) {
            return new Result(true, protocol, reason == null ? "" : reason);
        }

        public static Result fail(String reason) {
            return new Result(false, null, reason == null ? "" : reason);
        }
    }

    private final List<Link> links = new ArrayList<>();
    /** 端点键 → 该端点上的链路（精确端点，不含"同坐标的其它端点"） */
    private final Map<EndpointKey, List<Link>> byKey = new LinkedHashMap<>();
    /** 端点键 → 端点快照（取中文名/角色用；两端全断后自动清） */
    private final Map<EndpointKey, Endpoint> byEndpoint = new LinkedHashMap<>();

    public static SocLinkTable of() {
        return new SocLinkTable();
    }

    // ------------------------------------------------------------------ 连线

    /**
     * 连接两个端点：协商协议（交集里全局优先级最小者）→ 查重边 → 查级联深度 → 落表。
     *
     * <p>失败原因都是给玩家看的中文句子（硬件调节器 UI 直接照抄到提示里）。</p>
     *
     * <p>⚠ 身份判据是 {@link EndpointKey}：同坐标但设备类型不同（机箱里的卡 ↔ 机箱的芯片端点）
     * 是**合法**的一条线；同一个端点自己连自己才失败。</p>
     */
    public Result connect(Endpoint a, Endpoint b) {
        if (a == null || b == null || a.pos() == null || b.pos() == null) {
            return Result.fail("端点为空");
        }
        if (a.key().equals(b.key())) {
            return Result.fail("两端不能是同一个端点");
        }
        if (findEdge(a.key(), b.key()) != null) {
            return Result.fail("这两端已经连过了");
        }
        final LinkProtocol protocol = LinkProtocol.firstCommon(a.interfaces(), b.interfaces());
        if (protocol == null) {
            return Result.fail("没有公共协议（" + idOf(a) + " " + ids(a.interfaces())
                    + " / " + idOf(b) + " " + ids(b.interfaces()) + "）");
        }
        // 多设备能力**由协议决定**（用户 2026-09-29）：UART/DP/8080 点对点 = 1，I2C/SPI/USB/PCIE 可多设备
        if (a.isFull(linkCount(a.key()), protocol)) {
            return Result.fail(idOf(a) + " 已经接满（" + protocol.id() + " 上限 "
                    + a.effectiveMaxLinks(protocol) + " 条连接）");
        }
        if (b.isFull(linkCount(b.key()), protocol)) {
            return Result.fail(idOf(b) + " 已经接满（" + protocol.id() + " 上限 "
                    + b.effectiveMaxLinks(protocol) + " 条连接）");
        }
        // 级联：已经能走到的话，这条边会形成一个环；只要环长不超过上限就允许（设计允许任意拓扑）。
        final int hops = hops(a.key(), b.key());
        if (hops > 0 && hops + 1 > MAX_CASCADE) {
            return Result.fail("级联超过上限 " + MAX_CASCADE + " 段（这条边会形成 " + (hops + 1) + " 段的环路）");
        }
        final Link link = new Link(a, b, protocol);
        links.add(link);
        byKey.computeIfAbsent(a.key(), k -> new ArrayList<>()).add(link);
        byKey.computeIfAbsent(b.key(), k -> new ArrayList<>()).add(link);
        byEndpoint.put(a.key(), a);
        byEndpoint.put(b.key(), b);
        final String roleNote = (a.role().isMiddle() || b.role().isMiddle()) ? "（含中间组件）" : "";
        return Result.ok(protocol, "已连接：" + idOf(a) + " ↔ " + idOf(b)
                + "，协议 " + protocol.id() + roleNote);
    }

    /** 某个端点键上已经挂了几个链路。 */
    private int linkCount(EndpointKey k) {
        final List<Link> bucket = byKey.get(k);
        return bucket == null ? 0 : bucket.size();
    }

    /** 精确断开两个**端点键**之间的那条链路（方向无关）。 */
    public boolean disconnect(EndpointKey a, EndpointKey b) {
        final Link l = findEdge(a, b);
        if (l == null) {
            return false;
        }
        drop(l);
        return true;
    }

    /** 便捷入口：断开两个**坐标**之间的第一条链路（多端点坐标时语义见类注释）。 */
    public boolean disconnect(Pos a, Pos b) {
        final Link l = findEdge(a, b);
        if (l == null) {
            return false;
        }
        drop(l);
        return true;
    }

    /** 断开某个坐标上**全部**端点上的**全部**链路，返回断开条数。 */
    public int disconnectAll(Pos pos) {
        final List<Link> hit = new ArrayList<>(linksAt(pos));
        for (final Link l : hit) {
            drop(l);
        }
        return hit.size();
    }

    /** 断开某个**端点键**上的全部链路，返回断开条数。 */
    public int disconnectAll(EndpointKey key) {
        final List<Link> hit = new ArrayList<>(linksAt(key));
        for (final Link l : hit) {
            drop(l);
        }
        return hit.size();
    }

    private void drop(Link link) {
        links.remove(link);
        removeFrom(link, link.a().key());
        removeFrom(link, link.b().key());
        forgetIfUnlinked(link.a().key());
        forgetIfUnlinked(link.b().key());
    }

    // ------------------------------------------------------------- 两端都连

    /** 该坐标上的全部端点键（机箱 + 槽位里的卡会有多个） */
    public Set<EndpointKey> keysAt(Pos pos) {
        final Set<EndpointKey> out = new LinkedHashSet<>();
        if (pos == null) {
            return out;
        }
        for (final EndpointKey k : byEndpoint.keySet()) {
            if (k.pos().equals(pos)) {
                out.add(k);
            }
        }
        for (final Link l : links) {
            if (l.a().pos().equals(pos)) {
                out.add(l.a().key());
            }
            if (l.b().pos().equals(pos)) {
                out.add(l.b().key());
            }
        }
        return out;
    }

    /** 全部端点键（UI 列设备用） */
    public Set<EndpointKey> endpointKeys() {
        return new LinkedHashSet<>(byEndpoint.keySet());
    }

    /**
     * 端点快照（可能为 null：从未连过）。
     *
     * <p>坐标级入口只在**该坐标恰好一个端点**时返回它；有多个（机箱 + 卡）返回 null ——
     * 那种情况请用 {@link #endpointAt(EndpointKey)}。</p>
     */
    public Endpoint endpointAt(Pos pos) {
        final Set<EndpointKey> ks = keysAt(pos);
        return ks.size() == 1 ? byEndpoint.get(ks.iterator().next()) : null;
    }

    public Endpoint endpointAt(EndpointKey key) {
        return key == null ? null : byEndpoint.get(key);
    }

    /** 该端点是否已接到芯片（RV/CPU 侧） */
    public boolean linkedToChip(EndpointKey key) {
        for (final Link l : linksAt(key)) {
            final Endpoint e = byEndpoint.get(l.other(key));
            if (e != null && CHIP_DEVICE.equals(e.deviceId())) {
                return true;
            }
        }
        return false;
    }

    /** 该端点是否已接到非芯片端点（外设侧） */
    public boolean linkedToPeripheral(EndpointKey key) {
        for (final Link l : linksAt(key)) {
            final Endpoint e = byEndpoint.get(l.other(key));
            if (e == null || !CHIP_DEVICE.equals(e.deviceId())) {
                return true;
            }
        }
        return false;
    }

    /** 坐标级聚合：该坐标上任意端点接到芯片即真（单端点时与键级完全一致） */
    public boolean linkedToChip(Pos pos) {
        for (final EndpointKey k : keysAt(pos)) {
            if (linkedToChip(k)) {
                return true;
            }
        }
        return false;
    }

    /** 坐标级聚合：该坐标上任意端点接到外设即真 */
    public boolean linkedToPeripheral(Pos pos) {
        for (final EndpointKey k : keysAt(pos)) {
            if (linkedToPeripheral(k)) {
                return true;
            }
        }
        return false;
    }

    public boolean fullyLinked(EndpointKey key) {
        return linkedToChip(key) && linkedToPeripheral(key);
    }

    /**
     * ===== 「两端都连」判据（用户 2026-09-28 定案）=====
     *
     * <p>扩展卡<b>插上 ≠ 连上</b>：默认是**空连接**（一条链路都没有）。要被虚拟机接入，
     * 必须<b>两边都连</b>——一边连芯片（RV/CPU 侧总线），另一边连外设；只连一边仍然按
     * 「未接入」列出（分析器可见、guest 不可见）。</p>
     *
     * <p>为什么不做"插上即连"：用户定稿的硬件模型是"接口准入 + 手动连线"，插槽只保证
     * 物理在位，线路要玩家自己接 —— 插上就自动通电的语义在真机上也不存在。</p>
     *
     * <p>坐标级入口：该坐标上有**任意一个**端点满足"两端都连"即真（单端点时完全等价）。</p>
     */
    public boolean fullyLinked(Pos pos) {
        for (final EndpointKey k : keysAt(pos)) {
            if (fullyLinked(k)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 查询

    public List<Link> links() {
        return List.copyOf(links);
    }

    public List<Link> linksAt(Pos pos) {
        final List<Link> out = new ArrayList<>();
        for (final Link l : links) {
            if (l.touches(pos)) {
                out.add(l);
            }
        }
        return List.copyOf(out);
    }

    public List<Link> linksAt(EndpointKey key) {
        final List<Link> l = byKey.get(key);
        return l == null ? List.of() : List.copyOf(l);
    }

    public boolean isLinked(Pos pos) {
        return !linksAt(pos).isEmpty();
    }

    public boolean isLinked(EndpointKey key) {
        return !linksAt(key).isEmpty();
    }

    public int size() {
        return links.size();
    }

    public void clear() {
        links.clear();
        byKey.clear();
        byEndpoint.clear();
    }

    public Link findEdge(Pos a, Pos b) {
        for (final Link l : links) {
            if (l.between(a, b)) {
                return l;
            }
        }
        return null;
    }

    public Link findEdge(EndpointKey a, EndpointKey b) {
        for (final Link l : links) {
            if (l.between(a, b)) {
                return l;
            }
        }
        return null;
    }

    /** 坐标上有哪些端点（含只有坐标、没有链路的孤儿键不会出现） */
    public Set<Pos> endpoints() {
        final Set<Pos> out = new LinkedHashSet<>();
        for (final EndpointKey k : byEndpoint.keySet()) {
            out.add(k.pos());
        }
        return out;
    }

    /**
     * 两端之间的最短跳数（不连通返回 -1；同一个端点返回 0）。
     *
     * <p><b>键级</b>：按端点身份走，机箱里的卡与机箱的芯片端点是两个不同的节点
     * （它们之间那条线算 1 跳）。</p>
     */
    public int hops(EndpointKey from, EndpointKey to) {
        if (from == null || to == null) {
            return -1;
        }
        if (from.equals(to)) {
            return 0;
        }
        final Map<EndpointKey, Integer> dist = new HashMap<>();
        final Deque<EndpointKey> queue = new ArrayDeque<>();
        dist.put(from, 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            final EndpointKey cur = queue.poll();
            final int d = dist.get(cur);
            for (final Link l : linksAt(cur)) {
                final EndpointKey next = l.other(cur);
                if (next == null || dist.containsKey(next)) {
                    continue;
                }
                if (next.equals(to)) {
                    return d + 1;
                }
                dist.put(next, d + 1);
                queue.add(next);
            }
        }
        return -1;
    }

    /**
     * 坐标级跳数：两端各取一个端点求最小跳数（单端点坐标时与键级一致）。
     *
     * <p>保留它是为了既有调用方（自测、准入器）与"点到点"语义；一个坐标上有多个端点时
     * 结果 = 这些端点组合里的最小跳数。</p>
     */
    public int hops(Pos from, Pos to) {
        if (from == null || to == null) {
            return -1;
        }
        if (from.equals(to)) {
            return 0;
        }
        int best = -1;
        for (final EndpointKey a : keysAt(from)) {
            for (final EndpointKey b : keysAt(to)) {
                final int h = hops(a, b);
                if (h >= 0 && (best < 0 || h < best)) {
                    best = h;
                }
            }
        }
        return best;
    }

    /**
     * 路径速率（木桶效应，用户裁定 I）：路径上所有链路协议速率的最小值；不连通返回 -1。
     *
     * <p>键级实现：BFS 逐层传播"到该端点为止的瓶颈速率"。同一坐标上的不同端点之间
     * 不自动视为连通（要连就显式连）—— 这正是"插上不算连上"。</p>
     */
    public long pathBitsPerSecond(EndpointKey from, EndpointKey to) {
        if (from == null || to == null) {
            return -1;
        }
        if (from.equals(to)) {
            return Long.MAX_VALUE;
        }
        final Map<EndpointKey, Long> best = new HashMap<>();
        final Deque<EndpointKey> queue = new ArrayDeque<>();
        best.put(from, Long.MAX_VALUE);
        queue.add(from);
        while (!queue.isEmpty()) {
            final EndpointKey cur = queue.poll();
            final long curRate = best.get(cur);
            for (final Link l : linksAt(cur)) {
                final EndpointKey next = l.other(cur);
                if (next == null) {
                    continue;
                }
                final long rate = Math.min(curRate, l.protocol().bitsPerSecond());
                final Long known = best.get(next);
                if (known == null || rate > known) {
                    best.put(next, rate);
                    queue.add(next);
                }
            }
        }
        final Long r = best.get(to);
        return r == null ? -1 : r;
    }

    /** 坐标级速率：两端各取一个端点求最大瓶颈（单端点坐标时与键级一致）。 */
    public long pathBitsPerSecond(Pos from, Pos to) {
        if (from == null || to == null) {
            return -1;
        }
        if (from.equals(to)) {
            return Long.MAX_VALUE;
        }
        long best = -1;
        for (final EndpointKey a : keysAt(from)) {
            for (final EndpointKey b : keysAt(to)) {
                final long r = pathBitsPerSecond(a, b);
                if (r > best) {
                    best = r;
                }
            }
        }
        return best;
    }

    /**
     * 途经的**中间组件个数**（级联段数）：源组件不算段，终点本身也不算。
     *
     * <p>口径：BFS 沿最短路径前进，路上每遇到一个 {@link SocLinkRole#MIDDLE} 的角色 +1；
     * 链式拓扑（芯片 → 卡1 → … → 卡N）下就等于 N，即"级联了几段"。</p>
     */
    public int middleHops(EndpointKey from, EndpointKey to) {
        if (from == null || to == null) {
            return -1;
        }
        if (from.equals(to)) {
            return 0;
        }
        final Map<EndpointKey, Integer> dist = new HashMap<>();
        final Deque<EndpointKey> queue = new ArrayDeque<>();
        dist.put(from, 0);
        queue.add(from);
        while (!queue.isEmpty()) {
            final EndpointKey cur = queue.poll();
            final int d = dist.get(cur);
            for (final Link l : linksAt(cur)) {
                final EndpointKey next = l.other(cur);
                if (next == null || dist.containsKey(next)) {
                    continue;
                }
                final Endpoint ep = byEndpoint.get(next);
                final int step = ep != null && ep.role().isMiddle() ? 1 : 0;
                if (next.equals(to)) {
                    return d + step;               // 终点若本身是中间组件，它也算一段
                }
                dist.put(next, d + step);
                queue.add(next);
            }
        }
        return -1;
    }

    /** 坐标级中间段数：两端各取一个端点求最小段数（单端点坐标时与键级一致）。 */
    public int middleHops(Pos from, Pos to) {
        if (from == null || to == null) {
            return -1;
        }
        if (from.equals(to)) {
            return 0;
        }
        int best = -1;
        for (final EndpointKey a : keysAt(from)) {
            for (final EndpointKey b : keysAt(to)) {
                final int h = middleHops(a, b);
                if (h >= 0 && (best < 0 || h < best)) {
                    best = h;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 持久化行

    /**
     * ===== 一条链路的纯数据行（落盘 / 跨层传输，2026-09-29）=====
     *
     * <p>为什么要有它：世界持久化以前只存「坐标 + 设备类型」，<b>端口身份丢了</b> ——
     * 重载后 {@link EndpointKey#port()} 全变空串，"断开指定端口"与面板高亮必然失配
     * （09-29 实况调研的头号硬缺口）。行里把两端的 {@code port} 与协商出的协议 id 一起带走，
     * 读回时按端口重建端点 ⇒ 端点身份与存档前完全一致。</p>
     *
     * <p>为什么不需要存「通道数」：端口的身份是 {@code deviceId + port}，与通道数无关 ——
     * 通道数只决定"该设备有几个 dp 点"。读回时按<b>上限通道数</b>（设备表的 clamp 上限 8）重建，
     * 任何一个真实存在的端口都能还原，且还原出的协议与 {@code protocolId} 相同
     * （端口收敛成单协议）。</p>
     */
    public record Row(Pos a, String aDevice, String aPort, Pos b, String bDevice, String bPort, String protocolId) {

        public Row {
            aDevice = aDevice == null ? "" : aDevice;
            bDevice = bDevice == null ? "" : bDevice;
            aPort = aPort == null ? "" : aPort;
            bPort = bPort == null ? "" : bPort;
            protocolId = protocolId == null ? "" : protocolId;
        }
    }

    /** 行 → 端点：认不出（设备/端口不在当前表里）返回 null，调用方丢弃该行。 */
    @FunctionalInterface
    public interface Resolver {
        Endpoint resolve(Pos pos, String deviceId, String port);
    }

    /** 当前全部链路 → 纯数据行（顺序 = 连接顺序）。 */
    public List<Row> rows() {
        final List<Row> out = new ArrayList<>(links.size());
        for (final Link l : links) {
            out.add(new Row(l.a().pos(), l.a().deviceId(), l.a().port(),
                    l.b().pos(), l.b().deviceId(), l.b().port(), l.protocol().id()));
        }
        return out;
    }

    /**
     * 把一组行重放进本表（世界读盘用）：逐行 {@link #connect(Endpoint, Endpoint)}，
     * 认不出的端点与连不上的行<b>跳过并回报原因</b>（不静默丢、不抛）。
     *
     * @param warn 可空；每丢弃一行 / 协议对不上时调用一次（中文原因）
     * @return 成功导入的条数
     */
    public int importRows(List<Row> rows, Resolver resolver, java.util.function.Consumer<String> warn) {
        if (rows == null || resolver == null) {
            return 0;
        }
        int imported = 0;
        for (final Row row : rows) {
            final Endpoint a = resolver.resolve(row.a(), row.aDevice(), row.aPort());
            final Endpoint b = resolver.resolve(row.b(), row.bDevice(), row.bPort());
            if (a == null || b == null) {
                if (warn != null) {
                    warn.accept("丢弃一条链路：设备或端口已不存在（"
                            + row.aDevice() + ":" + row.aPort() + " ↔ "
                            + row.bDevice() + ":" + row.bPort() + "）");
                }
                continue;
            }
            final Result r = connect(a, b);
            if (!r.ok()) {
                if (warn != null) {
                    warn.accept("丢弃一条链路：" + r.reason());
                }
                continue;
            }
            imported++;
            if (!row.protocolId().isEmpty() && !row.protocolId().equals(r.protocol().id()) && warn != null) {
                warn.accept("链路协议与存档不一致（存档 " + row.protocolId() + "，重建 "
                        + r.protocol().id() + "）：" + row.aDevice() + ":" + row.aPort());
            }
        }
        return imported;
    }

    // ------------------------------------------------------------------ 内部

    private void removeFrom(Link link, EndpointKey key) {
        final List<Link> list = byKey.get(key);
        if (list != null) {
            list.remove(link);
            if (list.isEmpty()) {
                byKey.remove(key);
            }
        }
    }

    private void forgetIfUnlinked(EndpointKey key) {
        if (linksAt(key).isEmpty() && byKey.get(key) == null) {
            byEndpoint.remove(key);
        }
    }

    private static String idOf(Endpoint e) {
        return e.deviceId().isEmpty() ? "(未知设备)" : e.deviceId();
    }

    private static String ids(List<LinkProtocol> ps) {
        final StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ps.size(); i++) {
            sb.append(i == 0 ? "" : ",").append(ps.get(i).id());
        }
        return sb.append(']').toString();
    }
}
