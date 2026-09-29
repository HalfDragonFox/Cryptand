package com.hdf.cryptand.soc.link;

/**
 * ===== 接口「点」的种类（2026-09-28 用户定案）=====
 *
 * <p>用户原话：「配对采用一个硬件然后有图标，图标里面有颜色点可以连接，<b>两个同样的颜色点（同接口）
 * 可以被连接上</b>」。所以：<b>一类接口 = 一种颜色</b>，配对面板里同色才能连、异色连不上。</p>
 *
 * <p>端口 id 形如 {@code rv} / {@code dp1} / {@code usb2}：<b>前缀决定种类</b>（颜色与协议），
 * 序号只区分同类里的第几个点（显卡的一个 RV + 4 个 DP 就是 {@code rv}、{@code dp1..dp4}）。</p>
 *
 * <p>颜色只在这里定义一处（common，纯数据），面板与将来任何可视化都读它，避免两边各调一套色。</p>
 */
public enum PortKind {

    /** 系统总线点（显卡/扩展卡 ↔ 芯片/机器）。OC 侧对应 PCIE。 */
    RV(LinkProtocol.PCIE, "RV", 0xFFE04B4B),
    /** 显示输出/输入点（显卡 ↔ 屏）。 */
    DP(LinkProtocol.DP, "DP", 0xFF4B8BE0),
    USB(LinkProtocol.USB, "USB", 0xFF4BBF6B),
    SPI(LinkProtocol.SPI, "SPI", 0xFFE0A24B),
    UART(LinkProtocol.UART, "UART", 0xFFB04BE0),
    I2C(LinkProtocol.I2C, "I2C", 0xFF4BE0D0),
    P8080(LinkProtocol.P8080, "8080", 0xFFE04BBF),
    /**
     * 设计期"通用点"（芯片设计器专用，2026-09-29 用户定案）：画白点，**对端可以是任意协议**。
     *
     * <p>它不是宿主链路协议（{@link #protocol()} 为 null），端口 id 形如 {@code gp1}、{@code gp2}；
     * 设计期连线规则见 {@code HwCanvasLayout.canConnectDesign}。</p>
     */
    GENERIC(null, "gp", 0xFFFFFFFF);

    private final LinkProtocol protocol;
    private final String label;
    private final int argb;

    PortKind(LinkProtocol protocol, String label, int argb) {
        this.protocol = protocol;
        this.label = label;
        this.argb = argb;
    }

    public LinkProtocol protocol() {
        return protocol;
    }

    /** 给人看的短名（也是端口 id 的前缀，小写化后）。 */
    public String label() {
        return label;
    }

    /** 不透明 ARGB（面板画色点用）。 */
    public int argb() {
        return argb;
    }

    /** 第 index 个同类点的端口 id（从 1 开始）：RV 只有一个点，固定 {@code rv}。 */
    public String portId(int index) {
        final String base = label.toLowerCase(java.util.Locale.ROOT);
        return this == RV ? base : base + Math.max(1, index);
    }

    /** 协议 → 点种类；表里没有的协议返回 null（调用方自己兜底）。 */
    public static PortKind of(LinkProtocol protocol) {
        if (protocol == null) {
            return null;
        }
        for (final PortKind k : values()) {
            if (k.protocol == protocol) {
                return k;
            }
        }
        return null;
    }

    /**
     * 端口 id → 点种类：按<b>前缀</b>匹配（{@code dp1} → {@link #DP}）。
     * 认不出来返回 null（不是"默认某种颜色"—— 认不出就是不合法，宁可报错也不乱连）。
     */
    public static PortKind byId(String portId) {
        if (portId == null || portId.isBlank()) {
            return null;
        }
        final String id = portId.trim().toLowerCase(java.util.Locale.ROOT);
        PortKind best = null;
        for (final PortKind k : values()) {
            final String base = k.label.toLowerCase(java.util.Locale.ROOT);
            if (id.startsWith(base) && (best == null || base.length() > best.label.length())) {
                best = k;
            }
        }
        return best;
    }

    /** 两个端口 id 是否同色（同接口）—— 配对面板与闸门都用这一条判定。 */
    public static boolean sameKind(String a, String b) {
        final PortKind ka = byId(a);
        return ka != null && ka == byId(b);
    }

    /** 协议 → 颜色（认不出给灰色 0xFF808080，绝不抛）。 */
    public static int colorOf(LinkProtocol protocol) {
        final PortKind k = of(protocol);
        return k == null ? 0xFF808080 : k.argb;
    }
}
